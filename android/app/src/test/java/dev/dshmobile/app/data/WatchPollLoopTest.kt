package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPollLoopTest {
    private val id = "abcdefab-0000-4000-8000-000000000001"
    @Test fun offlineWaitsWithoutBackoffAndNetworkCallbackImmediatelyChecksFakeServer() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "completed", "turn")
            var local = NotificationLocal().registerWatch("device", "chat", id, 100)
            suspend fun update(change: (NotificationLocal) -> NotificationLocal) = change(local).also { local = it }
            var online = false; var backoffs = 0
            val reconnect = NotificationReconnect(); val posted = CompletableDeferred<Unit>(); val offlineEntered = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                reconnect.run {
                    val api = fake.api()
                    try {
                        val c = WatchCoordinator({ local }, ::update, api::promptOutcomes, { _, _ -> posted.complete(Unit) }, { 101 })
                        WatchPollLoop(::update, c, { api.capabilities().capabilities.promptOutcomes },
                            { offlineEntered.complete(Unit); online }, { 101 }, backoff = { _, _ -> backoffs++ }).run()
                    } finally { withContext(NonCancellable) { api.closeAndAwait() } }
                }
            }
            try {
                withTimeout(5000) { offlineEntered.await() }
                assertEquals(0, fake.server.requestCount); assertEquals(0, backoffs)
                online = true; reconnect.networkChanged()
                withTimeout(5000) { posted.await() }
                val requests = listOf(fake.next(), fake.next())
                assertEquals("/v1/capabilities", requests.first().path)
                assertEquals("0", requests.last().requestUrl!!.queryParameter("waitSeconds"))
                assertEquals(0, backoffs)
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun onlineErrorsUseBoundedBackoffButOfflineNeverSleepsAndExpiryStops() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", id, 0)
        val calls = mutableListOf<Int>(); var online = true; var backoffs = 0
        suspend fun update(change: (NotificationLocal) -> NotificationLocal) = change(local).also { local = it }
        val c = WatchCoordinator({ local }, ::update, { _, _, _, seconds -> calls += seconds; throw MobileFailure("network_unavailable") }, { _, _ -> fail("no alert") }, { testScheduler.currentTime })
        val loop = WatchPollLoop(::update, c, { true }, { online }, { testScheduler.currentTime }, backoff = { attempt, _ ->
            assertEquals(0, attempt); backoffs++; online = false
        })
        val job = launch { loop.run() }; runCurrent()
        assertEquals(listOf(0), calls); assertEquals(1, backoffs)
        advanceTimeBy(WATCH_TTL_MS); runCurrent(); job.join()
        assertEquals(1, backoffs); assertEquals(1, calls.size); assertTrue(local.watches.isEmpty())
    }
    @Test fun permissionRevokedStopsBeforeAnotherPollOrBackoff() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", id, 100)
        var allowed = true; var calls = 0; var retries = 0
        suspend fun update(change: (NotificationLocal) -> NotificationLocal) = change(local).also { local = it }
        val c = WatchCoordinator({ local }, ::update, { _, _, _, _ ->
            calls++; allowed = false
            PromptOutcomes("t", listOf(PromptOutcome(id, "completed", "2026-11-01T12:00:00Z", "turn")))
        }, { _, _ -> throw MobileFailure("notifications_blocked") }, { 101 })
        WatchPollLoop(::update, c, { true }, { true }, { 101 }, allowed = { allowed }, backoff = { _, _ -> retries++ }).run()
        assertEquals(1, calls); assertEquals(0, retries); assertEquals(1, local.outbox.size)
    }
    @Test fun restoreAndForegroundReconnectUseNonWaitingCheckAndJoinOldPoll() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", id, 100)
        suspend fun update(change: (NotificationLocal) -> NotificationLocal) = change(local).also { local = it }
        val reconnect = NotificationReconnect(); val seconds = mutableListOf<Int>(); var inFlight = 0; var max = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reconnect.run {
                val c = WatchCoordinator({ local }, ::update, { _, ids, _, wait ->
                    inFlight++; max = maxOf(max, inFlight)
                    try { seconds += wait; if (wait > 0) awaitCancellation()
                        PromptOutcomes("token", ids.map { PromptOutcome(it, "running", "2026-11-01T12:00:00Z", "turn") })
                    } finally { inFlight-- }
                }, { _, _ -> }, { 101 })
                WatchPollLoop(::update, c, { true }, { true }, { 101 }).run()
            }
        }
        runCurrent(); assertEquals(listOf(0, 25), seconds)
        reconnect.networkChanged(); runCurrent(); assertEquals(listOf(0, 25, 0, 25), seconds)
        assertEquals(1, max); job.cancelAndJoin(); assertEquals(0, inFlight)
    }
}

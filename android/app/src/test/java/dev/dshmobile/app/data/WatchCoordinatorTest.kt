package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchCoordinatorTest {
    private val request = "00000000-0000-4000-8000-000000000001"
    private fun cut() = PromptOutcomes("t", listOf(PromptOutcome(request, "completed", "2026-11-01T12:00:00Z", "turn-1")))
    @Test fun `N5 reconnect and process recreation resync exactly one completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        var reads = 0; val posts = mutableListOf<WatchAlert>()
        suspend fun update(change: (NotificationLocal) -> NotificationLocal): NotificationLocal = change(local).also { local = it }
        fun coordinator() = WatchCoordinator({ local }, ::update, { _, _, _, _ -> reads++; cut() }, { alert, _ -> posts += alert }, { 101 })
        coordinator().resync()
        local = mobileJson.decodeFromString(mobileJson.encodeToString(NotificationLocal.serializer(), local))
        coordinator().resync()
        assertEquals(1, reads); assertEquals(1, posts.size); assertTrue(local.watches.isEmpty()); assertTrue(local.outbox.isEmpty())
    }
    @Test fun `N5 broken first chat cannot starve an unrelated completed watch`() = runTest {
        var local = NotificationLocal().registerWatch("device", "broken", "00000000-0000-4000-8000-000000000002", 100)
            .registerWatch("device", "chat", request, 100)
        var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } },
            { session, _, _, _ -> if (session == "broken") throw java.io.IOException("Synthetic unavailable") else cut() }, { _, _ -> posts++ }, { 101 })
        try { c.resync(); fail("failed chat must remain retryable") } catch (_: java.io.IOException) {}
        assertEquals(1, posts); assertEquals(listOf("broken"), local.watches.map { it.sessionId })
    }
    @Test fun `N5 concurrent foreground wakeup and reconnect serialize one completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } },
            { _, _, _, _ -> entered.complete(Unit); release.await(); cut() }, { _, _ -> posts++ }, { 101 })
        val first = async { c.resync() }; entered.await()
        val second = async { c.resync() }; runCurrent(); release.complete(Unit)
        first.await(); second.await(); assertEquals(1, posts)
    }
    @Test fun `N5 failed post persists outbox and process recreation retries stable identity`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val identities = mutableSetOf<String>(); var fail = true
        fun coordinator() = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { _, _, _, _ -> cut() },
            { alert, replay -> assertEquals(!fail, replay); identities += alert.key; if (fail) throw java.io.IOException("Synthetic crash") }, { 101 })
        try { coordinator().resync(); fail("must fail") } catch (_: java.io.IOException) {}
        assertEquals(1, local.outbox.size); assertTrue(local.deliveredTurns.isEmpty())
        fail = false; coordinator().drain(); coordinator().resync()
        assertEquals(1, identities.size); assertTrue(local.outbox.isEmpty()); assertEquals(1, local.deliveredTurns.size)
    }
    @Test fun `N6 switch disabled during outcome HTTP prevents a queued completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } },
            { _, _, _, _ -> local = local.copy(enabled = false, watches = emptyList()); cut() }, { _, _ -> posts++ }, { 101 })
        c.resync(); assertEquals(0, posts); assertTrue(local.outbox.isEmpty())
    }
    @Test fun newRequestInvalidatesTokenAndSameSessionIsCheckedWithoutWaiting() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val calls = mutableListOf<Pair<String?, Int>>()
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { _, ids, since, seconds ->
            calls += since to seconds
            PromptOutcomes("opaque", ids.map { PromptOutcome(it, "running", "2026-11-01T12:00:00Z", "turn") })
        }, { _, _ -> }, { 101 })
        c.resync(); c.round()
        local = local.registerWatch("device", "chat", "00000000-0000-4000-8000-000000000002", 101)
        c.round()
        assertEquals(listOf(null to 0, "opaque" to 25, null to 0), calls)
    }
    @Test fun deviceChangesDuringPollNeverPostsOldDeviceOutcome() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { _, _, _, _ ->
            local = local.registerWatch("other-device", "chat", request, 100); cut()
        }, { _, _ -> posts++ }, { 101 })
        c.resync(); assertEquals(0, posts); assertTrue(local.outbox.isEmpty()); assertEquals(1, local.watches.size)
    }
    @Test fun `N5 cancellation of reconnect resync never settles a watch`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { _, _, _, _ -> throw CancellationException("route changed") }, { _, _ -> fail("no post") }, { 101 })
        try { c.resync(); fail("must cancel") } catch (_: CancellationException) {}
        assertEquals(1, local.watches.size); assertTrue(local.outbox.isEmpty())
    }
}

package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchCoordinatorTest {
    private val request = "00000000-0000-4000-8000-000000000001"
    private fun cut() = SessionSnapshot(SessionSummary("chat", "Synthetic", "w", 1, false, true), emptyList(), 1, false, "idle",
        promptOutcomes = listOf(PromptOutcome(request, "turn-1", "completed")))
    @Test fun `N5 reconnect and process recreation resync exactly one completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        var reads = 0; val posts = mutableListOf<WatchAlert>()
        suspend fun update(change: (NotificationLocal) -> NotificationLocal): NotificationLocal = change(local).also { local = it }
        fun coordinator() = WatchCoordinator({ local }, ::update, { reads++; cut() }, { alert, _ -> posts += alert }, { 101 })
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
            { session -> if (session == "broken") throw java.io.IOException("Synthetic unavailable") else cut() }, { _, _ -> posts++ }, { 101 })
        try { c.resync(); fail("failed chat must remain retryable") } catch (_: java.io.IOException) {}
        assertEquals(1, posts); assertEquals(listOf("broken"), local.watches.map { it.sessionId })
    }
    @Test fun `N5 concurrent feed wakeup and reconnect serialize one completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } },
            { entered.complete(Unit); release.await(); cut() }, { _, _ -> posts++ }, { 101 })
        val first = async { c.resync() }; entered.await()
        val second = async { c.resync() }; runCurrent(); release.complete(Unit)
        first.await(); second.await(); assertEquals(1, posts)
    }
    @Test fun `N5 failed post persists outbox and process recreation retries stable identity`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val identities = mutableSetOf<String>(); var fail = true
        fun coordinator() = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { cut() },
            { alert, replay -> assertTrue(replay); identities += alert.key; if (fail) throw java.io.IOException("Synthetic crash") }, { 101 })
        try { coordinator().resync(); fail("must fail") } catch (_: java.io.IOException) {}
        assertEquals(1, local.outbox.size); assertTrue(local.deliveredTurns.isEmpty())
        fail = false; coordinator().drain(); coordinator().resync()
        assertEquals(1, identities.size); assertTrue(local.outbox.isEmpty()); assertEquals(1, local.deliveredTurns.size)
    }
    @Test fun `N6 switch disabled during snapshot IO prevents a queued completion`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        var posts = 0
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } },
            { local = local.copy(enabled = false, watches = emptyList()); cut() }, { _, _ -> posts++ }, { 101 })
        c.resync(); assertEquals(0, posts); assertTrue(local.outbox.isEmpty())
    }
    @Test fun `N5 cancellation of reconnect resync never settles a watch`() = runTest {
        var local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val c = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { throw CancellationException("route changed") }, { _, _ -> fail("no post") }, { 101 })
        try { c.resync(); fail("must cancel") } catch (_: CancellationException) {}
        assertEquals(1, local.watches.size); assertTrue(local.outbox.isEmpty())
    }
}

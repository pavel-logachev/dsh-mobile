package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

class PhoneWatchTest {
    private val request = "00000000-0000-4000-8000-000000000001"
    private fun snapshot(states: List<PromptOutcome> = emptyList()) = SessionSnapshot(
        SessionSummary("chat", "Synthetic chat", "w", 1, false, true), emptyList(), 1, false, "idle", promptOutcomes = states)
    @Test fun `N1 no service without active watches and default preference is on`() {
        assertTrue(NotificationLocal().enabled)
        assertFalse(NotificationLocal().needsWatchService(100))
    }
    @Test fun `N3 desktop and preexisting turns never notify`() {
        val desktop = PromptOutcome(request, "turn-old", "completed")
        assertTrue(NotificationLocal().reconcileWatches(snapshot(listOf(desktop)), 100).outbox.isEmpty())
        val local = NotificationLocal().registerWatch("device", "chat", request, 100)
        assertTrue(local.reconcileWatches(snapshot(), 101).outbox.isEmpty())
        assertTrue(local.reconcileWatches(snapshot(listOf(desktop.copy(requestId = "00000000-0000-4000-8000-000000000002"))), 101).outbox.isEmpty())
    }
    @Test fun `N3 N5 reconnect authoritative completion creates one durable alert and stops last watch`() {
        val local = NotificationLocal().registerWatch("device", "chat", request, 100)
        assertTrue(local.needsWatchService(101))
        val done = snapshot(listOf(PromptOutcome(request, "turn-1", "completed")))
        val synced = local.reconcileWatches(done, 102)
        assertFalse(synced.needsWatchService(102)); assertEquals(1, synced.outbox.size)
        assertEquals("Synthetic chat", synced.outbox.single().title)
        val delivered = synced.acknowledgeWatchAlert(synced.outbox.single().key, 103)
        assertTrue(delivered.reconcileWatches(done, 104).outbox.isEmpty())
        assertEquals(1, delivered.deliveredTurns.size)
    }
    @Test fun `N3 error and question preserve summary and kind`() {
        for ((status, kind) in listOf("failed" to "error", "waiting" to "attention")) {
            val local = NotificationLocal().registerWatch("device", "chat", request, 100)
            val result = local.reconcileWatches(snapshot(listOf(PromptOutcome(request, "turn-1", status, "Synthetic failure"))), 101)
            assertEquals(kind, result.outbox.single().kind)
            assertEquals("Synthetic failure", result.outbox.single().summary)
            assertTrue(result.watches.isEmpty())
        }
    }
    @Test fun `N4 watch cap expiry duplicate registration and disabled preference`() {
        var local = NotificationLocal()
        repeat(100) { local = local.registerWatch("device", "chat", "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", 100 + it.toLong()) }
        assertEquals(32, local.watches.size)
        val latest = local.watches.last()
        assertEquals(32, local.registerWatch("device", "chat", latest.requestId, 500).watches.size)
        assertFalse(local.needsWatchService(200 + WATCH_TTL_MS))
        assertTrue(local.pruneWatches(200 + WATCH_TTL_MS).watches.isEmpty())
        assertTrue(NotificationLocal(enabled = false).registerWatch("device", "chat", request, 100).watches.isEmpty())
    }
    @Test fun `N4 process recreation restores watches and pending delivery`() {
        val local = NotificationLocal().registerWatch("device", "chat", request, 100)
        val restored = mobileJson.decodeFromString<NotificationLocal>(mobileJson.encodeToString(NotificationLocal.serializer(), local))
        assertEquals(local, restored); assertTrue(restored.needsWatchService(101))
        val done = restored.reconcileWatches(snapshot(listOf(PromptOutcome(request, "t", "completed"))), 102)
        val recreated = mobileJson.decodeFromString<NotificationLocal>(mobileJson.encodeToString(NotificationLocal.serializer(), done))
        assertEquals(done.outbox, recreated.outbox)
    }
    @Test fun `N3 multiple phone prompts merged in one turn dedupe by turn not feed event`() {
        val other = "00000000-0000-4000-8000-000000000002"
        val local = NotificationLocal().registerWatch("device", "chat", request, 100).registerWatch("device", "chat", other, 101)
        val result = local.reconcileWatches(snapshot(listOf(PromptOutcome(request, "same-turn", "completed"), PromptOutcome(other, "same-turn", "completed"))), 102)
        assertEquals(1, result.outbox.size); assertTrue(result.watches.isEmpty())
    }
}

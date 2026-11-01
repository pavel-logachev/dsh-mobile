package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test

class PhoneWatchTest {
    private val request = "00000000-0000-4000-8000-000000000001"
    private fun cut(status: String, id: String = request, turn: String? = "turn", at: String = "2026-11-01T12:00:00Z") =
        PromptOutcomes("token", listOf(PromptOutcome(id, status, at, turn)))
    private fun local() = NotificationLocal().registerWatch("device", "chat", request, 100)
    @Test fun noServiceWithoutActiveWatchesAndDefaultPreferenceOn() {
        assertTrue(NotificationLocal().enabled); assertFalse(NotificationLocal().needsWatchService(100))
    }
    @Test fun desktopAndUnknownNeverNotify() {
        assertTrue(NotificationLocal().reconcileWatches("chat", cut("completed"), 101).outbox.isEmpty())
        assertTrue(local().reconcileWatches("chat", cut("unknown"), 101).outbox.isEmpty())
        assertTrue(local().reconcileWatches("chat", cut("completed", "00000000-0000-4000-8000-000000000002"), 101).outbox.isEmpty())
    }
    @Test fun waitingEpisodesRemainWatchedAndFinalGetsDistinctKeySameChatTag() {
        var state = local().reconcileWatches("chat", cut("waiting"), 101)
        val waiting = state.outbox.single(); assertEquals("attention", waiting.kind); assertEquals(1, state.watches.size)
        state = state.acknowledgeWatchAlert(waiting.key, 102).reconcileWatches("chat", cut("waiting", at = "2026-11-01T12:01:00Z"), 103)
        assertTrue(state.outbox.isEmpty()) // updatedAt alone does not define an episode.
        state = state.reconcileWatches("chat", cut("running"), 104).reconcileWatches("chat", cut("waiting"), 105)
        val second = state.outbox.single(); assertNotEquals(waiting.key, second.key)
        state = state.acknowledgeWatchAlert(second.key, 106).reconcileWatches("chat", cut("completed"), 107)
        val final = state.outbox.single(); assertEquals("answer", final.kind); assertNotEquals(waiting.key, final.key)
        assertEquals(watchNotificationTag("device", waiting.sessionId), watchNotificationTag("device", final.sessionId))
        assertTrue(state.watches.isEmpty())
    }
    @Test fun watch33EvictsOldestAndExpiresExactlyAt24Hours() {
        var state = NotificationLocal()
        repeat(33) { state = state.registerWatch("device", "chat", "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", 100) }
        assertEquals(32, state.watches.size); assertFalse(state.watches.any { it.requestId.endsWith("000000000000") })
        assertEquals(state, state.registerWatch("device", "chat", state.watches.last().requestId, 101))
        assertEquals(32, state.pruneWatches(100 + WATCH_TTL_MS - 1).watches.size)
        assertTrue(state.pruneWatches(100 + WATCH_TTL_MS).watches.isEmpty())
        assertTrue(NotificationLocal(enabled = false).registerWatch("device", "chat", request, 100).watches.isEmpty())
    }
    @Test fun multipleRequestsInSameTurnDedupeWaitingAndFinal() {
        val other = "00000000-0000-4000-8000-000000000002"
        var state = local().registerWatch("device", "chat", other, 101)
        for (status in listOf("waiting", "completed")) {
            state = state.reconcileWatches("chat", PromptOutcomes("t", listOf(
                PromptOutcome(request, status, "2026-11-01T12:00:00Z", "same"), PromptOutcome(other, status, "2026-11-01T12:00:00Z", "same"))), 102)
            assertEquals(1, state.outbox.size)
            state = state.acknowledgeWatchAlert(state.outbox.single().key, 103)
        }
        assertTrue(state.watches.isEmpty()); assertEquals(2, state.deliveredTurns.size)
    }
    @Test fun opaqueTurnCannotCollideWithMissingTurnRequestFallback() {
        val other = "00000000-0000-4000-8000-000000000002"
        val state = local().registerWatch("device", "chat", other, 101)
        val rows = listOf(PromptOutcome(request, "completed", "2026-11-01T12:00:00Z"),
            PromptOutcome(other, "completed", "2026-11-01T12:00:00Z", "request:$request"))
        assertEquals(2, state.reconcileWatches("chat", PromptOutcomes("t", rows), 102).outbox.size)
    }
    @Test fun optionalTurnStillDeliversProvenTerminalAndRejectedDoesNot() {
        assertEquals(1, local().reconcileWatches("chat", cut("completed", turn = null), 101).outbox.size)
        val rejected = local().reconcileWatches("chat", cut("rejected", turn = null), 101)
        assertTrue(rejected.watches.isEmpty()); assertTrue(rejected.outbox.isEmpty())
    }
}

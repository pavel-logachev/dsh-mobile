package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.Workspace
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private fun event(id: String, sequence: Long = 0) = NotificationEvent(1, id, sequence, 100, 1000, "alpha", "chat", "answer-finished", 4)
    @Test fun pageApplicationCommitsCursorAndDeduplicatesEvents() {
        val page = NotificationPage(1, "epoch", listOf(event("event")), "cursor", false, false, "ready")
        val first = NotificationLocal().applyPage(page, 200)
        assertEquals("cursor", first.state.cursor)
        assertEquals(1, first.display.size)
        assertTrue(first.state.applyPage(page, 200).display.isEmpty())
        assertEquals("cursor", first.state.applyPage(page, 200).state.cursor)
    }
    @Test fun resolvedAttentionInSamePageNeverProducesAnAudibleStaleAlert() {
        val needed = event("needed", 1).copy(kind = "attention-needed", attentionId = "episode")
        val cleared = event("cleared", 2).copy(kind = "attention-cleared", attentionId = "episode")
        val result = NotificationLocal().applyPage(NotificationPage(1, "epoch", listOf(needed, cleared), "head", false, false, "ready"), 200)
        assertEquals(listOf("attention-cleared"), result.display.map { it.kind }); assertTrue(result.state.attention.isEmpty())
    }
    @Test fun resetDropsOldAttentionAndExpiredAnswersAreSilent() {
        val page = NotificationPage(1, "new", listOf(event("old")), "head", false, true, "degraded")
        val result = NotificationLocal(attention = mapOf("chat" to "episode")).applyPage(page, 2000)
        assertTrue(result.display.isEmpty()); assertTrue(result.state.attention.isEmpty())
    }
    @Test fun reconnectIsBoundedJitteredAndRespectsRetryAfter() {
        assertEquals(1000L, notificationRetryDelay(0, 0, 1.0))
        assertEquals(2000L, notificationRetryDelay(1, 0, 1.0))
        assertEquals(60000L, notificationRetryDelay(20, 0, 1.0))
        assertEquals(120000L, notificationRetryDelay(1, 120000, 0.5))
        assertFalse(notificationRetryable("tls_failed")); assertFalse(notificationRetryable("unauthorized"))
        assertTrue(notificationRetryable("network_unavailable"))
    }
    @Test fun localPolicyWinsUntilReconciledAndChatOverrideBeatsProject() {
        val local = NotificationSettings(enabled = true, projects = listOf(ProjectNotificationSetting("alpha", false)), chats = listOf(ChatNotificationSetting("chat", true)))
        assertTrue(local.allows(event("event")))
        assertEquals(local.copy(revision = 4), reconcileNotificationSettings(local, NotificationSettings(revision = 4), true))
        assertEquals(NotificationSettings(revision = 4), reconcileNotificationSettings(local, NotificationSettings(revision = 4), false))
    }
    @Test fun textUsesOnlyLocalNamesAndFallsBackWithoutResponseContent() {
        assertEquals("Project · Chat", notificationNames(event("event"), listOf(Workspace("alpha", "Project", false)), listOf(SessionSummary("chat", "Chat", "alpha", 0, false, false))))
        assertNull(notificationNames(event("event"), emptyList(), emptyList()))
    }
}

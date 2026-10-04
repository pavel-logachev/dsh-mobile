package dev.dshmobile.app.model

import org.junit.Assert.*
import org.junit.Test

class MobileReducerTest {
    private val session = SessionSummary("chat", "Synthetic", "workspace", 10, true, true)
    private fun snapshot(messages: List<ChatMessage>, cursor: Long = 1) = SessionSnapshot(session, messages, cursor, false, "running")
    @Test fun `replacement snapshot discards provisional text and old attempts even after cursor reset`() {
        val old = snapshot(listOf(ChatMessage("old", "assistant", "Discarded attempt", 1), ChatMessage("live", "assistant", "Provisional", 2, provisional = true)), 90)
        val canonical = snapshot(listOf(ChatMessage("final", "assistant", "Canonical response", 3)), 2)
        val state = MobileState(snapshot = old, sessions = listOf(session))
        val replacement = MobileReducer.replaceSnapshot(state, canonical, 30)
        assertEquals(listOf("final"), replacement.snapshot!!.messages.map { it.id })
        assertEquals(2, replacement.snapshot!!.cursor)
        assertEquals(30L, replacement.lastSyncedAt)
    }
    @Test fun `only canonical user message in the command session reconciles its request ID`() {
        val id = "61ea5a55-682f-4ab4-af97-8dad7a606a4d"
        val pending = PendingCommand(id, "send", "chat", "Synthetic prompt", "uncertain")
        val user = ChatMessage("user", "user", "Synthetic prompt", 1, requestId = id)
        assertTrue(MobileReducer.reconcilesPrompt(pending, snapshot(listOf(user))))
        assertFalse(MobileReducer.reconcilesPrompt(pending, snapshot(listOf(user.copy(provisional = true)))))
        assertFalse(MobileReducer.reconcilesPrompt(pending, snapshot(listOf(user.copy(role = "assistant")))))
        assertFalse(MobileReducer.reconcilesPrompt(pending.copy(sessionId = "other"), snapshot(listOf(user))))
        assertFalse(MobileReducer.reconcilesPrompt(pending.copy(kind = "cancel"), snapshot(listOf(user))))
    }
}

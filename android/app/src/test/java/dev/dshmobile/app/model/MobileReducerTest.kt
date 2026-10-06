package dev.dshmobile.app.model

import org.junit.Assert.*
import org.junit.Test

class MobileReducerTest {
    private val session = SessionSummary("chat", "Synthetic", "workspace", 10, true, true)
    private fun snapshot(messages: List<ChatMessage>, cursor: Long = 1) = SessionSnapshot(session, messages, cursor, false, "running")
    @Test fun `online running draft can send again while an admitted prompt awaits history`() {
        val online = MobileState(connection = ConnectionState.ONLINE,
            capabilities = MobileCapabilities(true, true, true, false, false, false, false, false),
            workspaces = listOf(Workspace("workspace", "Synthetic", true)), snapshot = snapshot(emptyList()))
        assertTrue(MobileReducer.canSend(online, "Next prompt"))
        val queued = PendingCommand("first", "send", "chat", "First prompt", "queued")
        assertTrue(MobileReducer.canSend(online.copy(acceptedPrompts = listOf(queued)), "Next prompt"))
        assertFalse(MobileReducer.canSend(online.copy(acceptedPrompts = listOf(queued, queued.copy(requestId = "second"), queued.copy(requestId = "third"))), "Next prompt"))
        assertTrue(MobileReducer.canSend(online.copy(acceptedPrompts = List(3) { queued.copy(requestId = "$it", sessionId = "other") }), "Next prompt"))
        assertTrue(MobileReducer.canSend(online.copy(acceptedPrompts = List(3) { queued.copy(requestId = "$it", status = "unconfirmed") }), "Next prompt"))
        assertFalse(MobileReducer.canSend(online.copy(pending = queued.copy(status = "uncertain")), "Next prompt"))
        assertFalse(MobileReducer.canSend(online.copy(connection = ConnectionState.OFFLINE), "Next prompt"))
        assertFalse(MobileReducer.canSend(online.copy(busy = true), "Next prompt"))
        assertFalse(MobileReducer.canSend(online, " "))
        assertFalse(MobileReducer.canSend(online, "я".repeat(16_385)))
    }
    @Test fun `canonical user admission removes only matching local queued copy`() {
        val queued = PendingCommand("first", "send", "chat", "First prompt", "queued")
        val second = queued.copy(requestId = "second", text = "Second prompt")
        val state = MobileState(acceptedPrompts = listOf(queued, second))
        val canonical = ChatMessage("user", "user", "First prompt", 10, requestId = "first")
        assertEquals(listOf(queued, second), MobileReducer.replaceSnapshot(state, snapshot(listOf(canonical.copy(provisional = true))), 30).acceptedPrompts)
        val reconciled = MobileReducer.replaceSnapshot(state, snapshot(listOf(canonical)), 30)
        assertEquals(listOf(second), reconciled.acceptedPrompts)
        assertEquals(listOf("user"), reconciled.snapshot!!.messages.map { it.id })
        assertEquals(listOf(second), MobileReducer.replaceSnapshot(reconciled, snapshot(listOf(canonical)), 40).acceptedPrompts)
    }
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

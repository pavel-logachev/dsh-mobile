package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ActionFeedbackTest {
    private val snapshot = SessionSnapshot(SessionSummary("a", "Synthetic", "w", 1, false, true), emptyList(), 1, false, "idle")
    @Test fun `only newly accepted own chat prompts qualify for send completion feedback`() {
        val before = MobileState(snapshot = snapshot)
        val accepted = PendingCommand("new", "send", "a", "text", "queued")
        assertTrue(sendWasAccepted(before, before.copy(acceptedPrompts = listOf(accepted)), "text"))
        assertFalse(sendWasAccepted(before.copy(acceptedPrompts = listOf(accepted)), before.copy(acceptedPrompts = listOf(accepted)), "text"))
        assertFalse(sendWasAccepted(before, before.copy(acceptedPrompts = listOf(accepted.copy(sessionId = "b"))), "text"))
        assertFalse(sendWasAccepted(before, before.copy(acceptedPrompts = listOf(accepted.copy(text = "another desktop prompt"))), "text"))
        assertFalse(sendWasAccepted(before, before.copy(pending = accepted.copy(status = "uncertain")), "text"))
        assertFalse(sendWasAccepted(before, before.copy(snapshot = snapshot.copy(messages = listOf(ChatMessage("stream", "assistant", "text", 2, provisional = true)))), "text"))
        val canonical = ChatMessage("user", "user", "text", 3, requestId = "own")
        assertTrue(sendWasAccepted(before, before.copy(snapshot = snapshot.copy(messages = listOf(canonical))), "text"))
        assertFalse(sendWasAccepted(before, before.copy(snapshot = snapshot.copy(messages = listOf(canonical.copy(text = "other")))), "text"))
    }
}

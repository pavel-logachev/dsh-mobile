package dev.dshmobile.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dshmobile.app.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentActivityGroupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyHiddenItemOpensFullSelectableCopyableText() {
        val fullEvent = "Synthetic agent event\nSecond line with full result"
        val group = ChatItem.Activity(listOf(ChatMessage("event", "user", fullEvent, 1, kind = "agent_event"),
            ChatMessage("context", "user", "SYNTHETIC CHECKPOINT", 2, kind = "context"),
            ChatMessage("suffix:human", "user", "\n\n<system-reminder>Full suffix</system-reminder>", 3, kind = "context")))
        var copied = ""
        compose.setContent { MaterialTheme { AgentActivityGroup(group) { copied = it } } }
        compose.onNodeWithTag("agent_activity_details").assertDoesNotExist()
        compose.onNodeWithTag("agent_activity_expander").performClick()
        for (message in group.messages) {
            compose.onNodeWithTag("agent_activity_item:${message.id}").assertHasClickAction().performClick()
            compose.onNodeWithTag("agent_activity_full_text").assertTextEquals(message.text)
            compose.onNodeWithTag("agent_activity_copy").performClick()
            compose.runOnIdle { assertEquals(message.text, copied) }
            compose.onNodeWithTag("agent_activity_close").performClick()
        }
    }

    @Test fun expansionSurvivesRecompositionAppendAndOverlappingHistoryCut() {
        fun event(id: String) = ChatMessage(id, "user", "Synthetic event", 1, kind = "agent_event")
        val state = mutableStateOf(chatItems(listOf(ChatMessage("human", "user", "Question", 1), event("e0"), event("e1"))))
        compose.setContent { MaterialTheme { AgentActivityGroup(state.value.filterIsInstance<ChatItem.Activity>().single()) } }
        compose.onNodeWithTag("agent_activity_expander").performClick()
        compose.runOnIdle { state.value = chatItems(listOf(ChatMessage("human", "user", "Question", 1), event("e0"), event("e1"), event("e2")), state.value) }
        compose.onNodeWithTag("agent_activity_details").assertIsDisplayed()
        compose.runOnIdle { state.value = chatItems(listOf(event("e1"), event("e2")), state.value) }
        compose.onNodeWithTag("agent_activity_details").assertIsDisplayed()
        compose.onNodeWithTag("agent_activity_item:e2").assertIsDisplayed()
    }
}

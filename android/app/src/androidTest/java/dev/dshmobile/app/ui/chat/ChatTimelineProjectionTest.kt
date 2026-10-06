package dev.dshmobile.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dshmobile.app.model.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class ChatTimelineProjectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacingOrClearingSurfaceNeverShowsOldCopyableAnswerWhileClassificationWaits() {
        val old = listOf(ChatMessage("old", "assistant", "Obsolete answer", 1))
        val next = listOf(ChatMessage("next", "assistant", "Replacement answer", 2))
        val session = SessionSummary("s", "Synthetic", "w", 1, false, true)
        val snapshot = mutableStateOf(SessionSnapshot(session, old, 1, false, "idle"))
        val oldGate = CompletableDeferred<Unit>()
        val nextGate = CompletableDeferred<Unit>()
        compose.setContent {
            MaterialTheme { ChatTimeline(snapshot.value, null, {}, classify = { input, previous ->
                if (input === old) oldGate.await() else if (input === next) nextGate.await()
                chatItems(input, previous)
            }) }
        }
        compose.onNodeWithTag("chat_classification_loading").assertIsDisplayed()
        compose.onNodeWithText("Obsolete answer").assertDoesNotExist()
        compose.runOnIdle { oldGate.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("Obsolete answer").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { snapshot.value = snapshot.value.copy(messages = next, cursor = 2) }
        compose.onNodeWithText("Obsolete answer").assertDoesNotExist()
        compose.onNodeWithTag("chat_classification_loading").assertIsDisplayed()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(messages = emptyList(), activity = "unknown", cursor = 3) }
        compose.onNodeWithText("Obsolete answer").assertDoesNotExist()
        compose.onNodeWithText("Replacement answer").assertDoesNotExist()
        compose.onNodeWithTag("chat_classification_loading").assertDoesNotExist()
        compose.runOnIdle { nextGate.complete(Unit) }
        compose.onNodeWithText("Replacement answer").assertDoesNotExist()
    }
}

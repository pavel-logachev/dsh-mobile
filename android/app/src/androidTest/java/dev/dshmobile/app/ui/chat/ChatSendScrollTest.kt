package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatSendScrollTest {
    @get:Rule val compose = createComposeRule()
    @Test fun localSendFromHistoryGoesToBottomOnceButReceiptDoesNotYankReader() {
        val session = SessionSummary("s", "Synthetic", "w", 1, false, true)
        val messages = (0..35).map { ChatMessage("m$it", "assistant", "History $it\n" + "Readable line\n".repeat(5), it.toLong()) }
        val snapshot = SessionSnapshot(session, messages, 1, false, "idle")
        var pending by mutableStateOf<PendingCommand?>(null)
        lateinit var list: LazyListState
        compose.setContent { MaterialTheme {
            list = rememberLazyListState()
            ChatTimeline(snapshot, pending, {}, list = list)
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat_classification_loading").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(8)
        compose.runOnIdle { pending = PendingCommand("request", "send", "s", "Synthetic task", "sending") }
        compose.waitForIdle()
        assertFalse("Sending while reading must reach bottom", list.canScrollForward)
        compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(8)
        val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        compose.runOnIdle { pending = pending!!.copy(status = "pending") }
        compose.waitForIdle()
        assertEquals("Receipt update must not repeat the send scroll", position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
    }
}

package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.model.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Observe the real LazyColumn while an off-main projection is in flight. */
class ChatScrollRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val session = SessionSummary("scroll", "Synthetic", "w", 1, false, true)
    private val history = (0..29).map { ChatMessage("m$it", "assistant", "History $it\n" + "Readable history line.\n".repeat(5), it.toLong()) }
    private lateinit var list: LazyListState
    private val records = mutableListOf<String>()
    private fun trace(stage: String) = compose.runOnIdle {
        records.add("$stage,${list.firstVisibleItemIndex},${list.firstVisibleItemScrollOffset},${list.layoutInfo.totalItemsCount},${list.canScrollForward}")
    }
    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "$name.csv").writeText("stage,index,offset,count,canScrollForward\n" + records.joinToString("\n"))
    }
    private fun show(state: State<SessionSnapshot>, gate: CompletableDeferred<Unit>, changed: () -> Boolean) {
        compose.setContent { MaterialTheme {
            list = rememberLazyListState()
            ChatTimeline(state.value, null, {}, Modifier.height(480.dp), list = list, classify = { input, previous ->
                if (changed()) gate.await()
                chatItems(input, previous)
            })
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("chat_classification_loading").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }
    @Test fun sendingDoesNotCollapseHistoryWhileProjectionWaits() {
        val state = mutableStateOf(SessionSnapshot(session, history, 1, false, "idle"))
        val gate = CompletableDeferred<Unit>(); var changed = false
        show(state, gate) { changed }
        trace("before-send")
        compose.runOnIdle { changed = true; state.value = state.value.copy(messages = history + ChatMessage("sent", "user", "Synthetic new task", 50), cursor = 2) }
        trace("send-projection-pending")
        try {
            assertTrue("Sending removed the history and reset the LazyColumn", list.layoutInfo.totalItemsCount >= history.size)
        } finally { compose.runOnIdle { gate.complete(Unit) }; trace("send-projected"); save("send-regression") }
    }
    @Test fun finishingLongReplyDoesNotTemporarilyRemoveVisibleTail() {
        val reply = ChatMessage("provisional:reply", "assistant", "Streamed line\n\n".repeat(60), 50, provisional = true)
        val state = mutableStateOf(SessionSnapshot(session, history + reply, 1, false, "running"))
        val gate = CompletableDeferred<Unit>(); var changed = false
        show(state, gate) { changed }
        // Read inside the long reply, not against the max-scroll clamp. Finishing removes the
        // existing provisional label, so its old maximum offset is no longer a valid position.
        compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(history.size)
        val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        trace("before-finish")
        compose.runOnIdle { changed = true; state.value = state.value.copy(messages = history + reply.copy(id = "saved", provisional = false), activity = "idle", cursor = 2) }
        trace("finish-projection-pending")
        try {
            assertEquals("Completion removed the visible reply during classification", history.size + 1, list.layoutInfo.totalItemsCount)
            assertEquals("Completion moved the viewport", position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        } finally { compose.runOnIdle { gate.complete(Unit) }; trace("finish-projected"); save("finish-regression") }
    }
    @Test fun streamingDoesNotResetReaderPositionWhileProjectionWaits() {
        val reply = ChatMessage("reply", "assistant", "Streaming line", 50, provisional = true)
        val state = mutableStateOf(SessionSnapshot(session, history + reply, 1, false, "running"))
        val gate = CompletableDeferred<Unit>(); var changed = false
        show(state, gate) { changed }
        // Following is a user intent. A semantics scroll alone bypasses the drag lifecycle.
        compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(12)
        trace("reading-before-stream")
        val index = list.firstVisibleItemIndex; val offset = list.firstVisibleItemScrollOffset
        compose.runOnIdle { changed = true; state.value = state.value.copy(messages = history + reply.copy(text = reply.text + "\nMore streamed text"), cursor = 2) }
        trace("stream-projection-pending")
        try {
            assertEquals("Streaming reset the reader index", index, list.firstVisibleItemIndex)
            assertEquals("Streaming reset the reader offset", offset, list.firstVisibleItemScrollOffset)
        } finally { compose.runOnIdle { gate.complete(Unit) }; trace("stream-projected"); save("stream-regression") }
    }
}

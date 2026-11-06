package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.data.HostApi
import dev.dshmobile.app.data.HostEndpoint
import dev.dshmobile.app.data.StoredCommand
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real SessionController/Agent/JSONL stand, synthetic content only. */
class ChatScrollStandTest {
    @get:Rule val compose = createComposeRule()
    @Test fun journey() = runBlocking {
        val run = InstrumentationRegistry.getArguments().getString("scrollRun") ?: return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = JSONObject(File(context.cacheDir, "scroll-stand.json").readText())
        val session = config.getString("sessionId")
        val api = HostApi(HostEndpoint(config.getString("baseUrl")), config.getString("deviceToken"))
        suspend fun control(action: String) = withContext(Dispatchers.IO) {
            val conn = java.net.URL("http://127.0.0.1:${config.getInt("controlPort")}/$action").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            try { check(conn.responseCode == 200); conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
        }
        val state = mutableStateOf(api.snapshot(session))
        val pending = mutableStateOf<PendingCommand?>(null)
        lateinit var list: LazyListState
        val traces = mutableListOf<String>()
        val violations = mutableListOf<String>()
        var stage = "open"
        var sampling = true
        var readingIndex = -1
        var readingOffset = -1
        compose.setContent { MaterialTheme {
            list = rememberLazyListState()
            DisposableEffect(list) {
                val choreographer = android.view.Choreographer.getInstance()
                val callback = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        val info = list.layoutInfo
                        val tail = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                        val bottomGap = tail?.let { it.offset + it.size + info.afterContentPadding - info.viewportEndOffset }
                        traces.add("$time,$stage,${list.firstVisibleItemIndex},${list.firstVisibleItemScrollOffset},${info.totalItemsCount},${list.canScrollForward},$bottomGap")
                        if (stage == "finish-bottom" && bottomGap != 0) violations.add("finish-bottom gap=$bottomGap px")
                        if (stage in setOf("send", "stream", "read-stream") && info.totalItemsCount < 10) violations.add("$stage collapsed to ${info.totalItemsCount} items")
                        if (stage == "read-stream" && readingIndex >= 0 && (list.firstVisibleItemIndex != readingIndex || list.firstVisibleItemScrollOffset != readingOffset)) violations.add("reader moved to ${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}")
                        if (sampling) choreographer.postFrameCallback(this)
                    }
                }
                choreographer.postFrameCallback(callback)
                onDispose { choreographer.removeFrameCallback(callback) }
            }
            Column(Modifier.fillMaxSize()) {
                ChatTimeline(state.value, pending.value, {}, Modifier.weight(1f), list = list)
                ChatActivity(MobileState(connection = ConnectionState.ONLINE, snapshot = state.value))
            }
        } }
        fun settle() { compose.waitForIdle(); Thread.sleep(80); compose.waitForIdle() }
        suspend fun update() { val next = api.snapshot(session); compose.runOnIdle { state.value = next }; settle() }
        fun capture(name: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(context.cacheDir, "$run-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            settle(); capture("open")
            val request = UUID.randomUUID().toString()
            compose.runOnIdle { stage = "send"; pending.value = PendingCommand(request, "send", session, "SCROLL_SYNTHETIC_REPLY", "sending") }
            settle()
            assertEquals("accepted", api.command(StoredCommand(request, "send", session, "SCROLL_SYNTHETIC_REPLY", delivery = "queue")).status)
            update(); compose.runOnIdle { pending.value = null }; settle(); capture("sent")
            compose.runOnIdle { stage = "stream" }
            repeat(20) { control("step"); delay(80); update() }
            capture("stream-bottom")
            if (list.canScrollForward) violations.add("bottom did not follow stream")
            compose.runOnIdle { stage = "read-setup" }
            compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
            settle()
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(10)
            settle()
            compose.runOnIdle { readingIndex = list.firstVisibleItemIndex; readingOffset = list.firstVisibleItemScrollOffset; stage = "read-stream" }
            repeat(8) { control("step"); delay(80); update() }
            capture("reading")
            compose.onNodeWithTag("new_messages").assertIsDisplayed()
            compose.runOnIdle { stage = "running-status" }
            val beforeRunningStatus = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            compose.runOnIdle { state.value = state.value.copy(activityDetail = ActivityDetail(1, "synthetic-tool")) }; settle()
            compose.onNodeWithTag("activity_row").assertIsDisplayed()
            if (beforeRunningStatus != (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) violations.add("running status moved viewport")
            compose.runOnIdle { stage = "finish" }
            val beforeFinish = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            control("finish"); update(); capture("finished")
            if (beforeFinish != (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) violations.add("completion moved reader")
            compose.runOnIdle { stage = "jump-latest" }
            compose.onNodeWithTag("new_messages").performClick(); settle(); capture("latest")
            if (list.canScrollForward) violations.add("jump to latest did not reach bottom")
            compose.runOnIdle { stage = "idle-status" }
            val beforeStatus = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            compose.runOnIdle { state.value = state.value.copy(activityDetail = ActivityDetail(1, "synthetic-status")) }; settle()
            if (beforeStatus != (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) violations.add("status moved viewport")
            // A second real turn finishes with the reader inside the provisional reply itself.
            compose.runOnIdle { stage = "second-send" }
            val second = UUID.randomUUID().toString()
            assertEquals("accepted", api.command(StoredCommand(second, "send", session, "SCROLL_SYNTHETIC_REPLY", delivery = "queue")).status)
            update()
            repeat(30) { control("step"); delay(50); update() }
            compose.onNodeWithTag("chat_timeline").performTouchInput { swipeDown(durationMillis = 500) }
            settle()
            compose.onNodeWithTag("chat_timeline").performScrollToIndex(list.layoutInfo.totalItemsCount - 1)
            settle()
            val insideReply = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            compose.runOnIdle { stage = "finish-reply" }
            control("finish"); update(); capture("finished-reply")
            if (insideReply != (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)) violations.add("completion moved reader inside reply")
            // Finish at the exact bottom, including the disappearing provisional/footer and activity row.
            compose.runOnIdle { stage = "bottom-setup" }
            compose.onNodeWithTag("new_messages").performClick(); settle()
            val third = UUID.randomUUID().toString()
            assertEquals("accepted", api.command(StoredCommand(third, "send", session, "SCROLL_SYNTHETIC_REPLY", delivery = "queue")).status)
            update()
            repeat(30) { control("step"); delay(50); update() }
            if (list.canScrollForward) violations.add("not at bottom before finish")
            capture("before-finish-bottom")
            compose.runOnIdle { stage = "finish-bottom" }
            control("finish"); update(); capture("finished-bottom")
            compose.runOnIdle { stage = "done" }
        } finally {
            compose.runOnIdle { sampling = false }
            File(context.cacheDir, "$run-trace.csv").writeText("nanos,stage,index,offset,count,canScrollForward,bottomGapPx\n" + traces.joinToString("\n"))
            File(context.cacheDir, "$run-violations.txt").writeText(violations.distinct().joinToString("\n"))
            api.closeAndAwait()
        }
        assertTrue(violations.distinct().joinToString("\n"), violations.isEmpty())
    }
}

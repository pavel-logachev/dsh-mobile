package dev.dshmobile.app.ui.chat

import android.app.Application
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.MobileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic repository, actual ChatScreen/Composer/Question UI; no network or installed profile. */
class AuditChatRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val run get() = InstrumentationRegistry.getArguments().getString("scrollRun") ?: "audit"
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle(); instrumentation.waitForIdleSync(); Thread.sleep(200)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "$run-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private class Repo(initial: MobileState) : MobileRepository {
        override val state = MutableStateFlow(initial)
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        override suspend fun restore() {}
        override suspend fun refresh() {}
        override suspend fun pair(invitationJson: String, deviceName: String) {}
        override suspend fun pair(invitation: Invitation, deviceName: String) {}
        override suspend fun selectSession(sessionId: String) {}
        override suspend fun createSession(workspaceId: String, presetId: String?, requestId: String) {}
        override suspend fun updateDraft(text: String) {}
        override suspend fun sendMessage(text: String) {}
        override suspend fun cancelRun() {}
        override suspend fun resolvePending() {}
        override suspend fun abandonPending() {}
        override suspend fun forget() {}
        override suspend fun setForeground(active: Boolean) {}
        override fun close() {}
    }
    private val session = SessionSummary("audit", "Synthetic audit", "w", 1, true, true)
    @Test fun searchNeverTurnsServiceIntoUserBubble() {
        val messages = listOf(
            ChatMessage("human", "user", "CANARY own question", 1),
            ChatMessage("runtime", "user", "Current runtime context. This snapshot supersedes earlier runtime-context snapshots.\n\nCurrent working directory: synthetic CANARY workspace", 2),
            ChatMessage("system", "system", "CANARY system", 3),
            ChatMessage("event", "user", "CANARY event", 4, kind = "agent_event"),
            ChatMessage("suffix", "user", "CANARY own prefix\n\n<system-reminder>CANARY service suffix</system-reminder>", 5),
            ChatMessage("answer", "assistant", "CANARY reply", 6))
        compose.setContent { MobileTheme {
            ChatTimeline(SessionSnapshot(session, messages, 1, false, "idle"), null, {}, searchQuery = "CANARY", selectedMatch = SearchMatch("human", 0, 6))
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("message:human").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message:human").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        capture("D01-search")
        compose.onNodeWithTag("message:runtime").assertDoesNotExist()
        compose.onNodeWithTag("message:system").assertDoesNotExist()
        compose.onNodeWithTag("message:event").assertDoesNotExist()
        compose.onNodeWithTag("message:human").assertIsDisplayed()
        compose.onNodeWithTag("search_text:suffix", useUnmergedTree = true).assertTextEquals("CANARY own prefix")
        compose.onAllNodesWithTag("agent_activity_expander").assertCountEquals(2)
    }
    @Test fun largeFontQueueLeavesHistoryAndQuestionReachableWithIme() {
        val landscape = !run.contains("portrait")
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        instrumentation.waitForIdleSync()
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale("ru")) }
        val localized = compose.activity.createConfigurationContext(config)
        val question = PendingQuestion("call", "open", listOf(QuestionItem("q", "Какую проверку запустить?", "Проверка", options = listOf(QuestionOption("Тесты")), allowFreeText = true)))
        val snapshot = SessionSnapshot(session, listOf(ChatMessage("history", "assistant", "История переписки доступна", 1)), 1, false, "waiting",
            pendingQueue = listOf(QueueItem("queued", "Synthetic queued message", "queue")), pendingQuestions = listOf(question))
        val state = MobileState(paired = true, demo = true, connection = ConnectionState.ONLINE,
            capabilities = MobileCapabilities(true, true, true, true, true, true, true, true, queue = true),
            workspaces = listOf(Workspace("w", "Synthetic workspace", true)), snapshot = snapshot)
        lateinit var vm: MobileViewModel
        instrumentation.runOnMainSync { vm = MobileViewModel(context.applicationContext as Application) { Repo(state) } }
        try {
            compose.setContent {
                val owner = requireNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides localized, androidx.compose.ui.platform.LocalConfiguration provides config, androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                    MobileTheme { ChatScreen(state, vm, "audit", {}, {}) }
                }
            }
            compose.waitForIdle(); capture("D02-initial")
            val timeline = compose.onNodeWithTag("chat_timeline").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue("Timeline height ${timeline.height / density}dp must remain usable", timeline.height / density >= 96f)
            compose.onNodeWithTag("question_submit").performScrollTo().assertIsDisplayed()
            capture("D02-question")
            compose.onNodeWithTag("message_input").performScrollTo().assertIsDisplayed().performClick().performTextInput("Synthetic draft")
            compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle(); capture("D02-ime")
            assertTrue("IME did not open", androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            val imeTimeline = compose.onNodeWithTag("chat_timeline").fetchSemanticsNode().boundsInRoot
            assertTrue("IME removed timeline: ${imeTimeline.height / density}dp", imeTimeline.height / density >= 96f)
            // Move focus into the pending question while the actual keyboard remains open.
            // This also exercises focus relocation across the outer short-window scroll fallback.
            compose.onNodeWithTag("question_custom").performScrollTo().performClick().performTextInput("Synthetic answer")
            compose.onNodeWithTag("chat_short_window_scroll").performScrollToNode(hasTestTag("question_submit"))
            compose.onNodeWithTag("question_submit").performScrollTo().assertIsDisplayed()
            capture("D02-ime-question")
            val questionNode = compose.onNodeWithTag("question_submit").fetchSemanticsNode()
            val visibleQuestion = questionNode.boundsInRoot
            val windowY = questionNode.positionInWindow.y
            val windowInsets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            val imeTop = compose.activity.window.decorView.height - (windowInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0)
            File(context.cacheDir, "$run-layout.txt").writeText("timelineDp=${timeline.height / density}\nimeTimelineDp=${imeTimeline.height / density}\nimeTopPx=$imeTop\nquestionBounds=$visibleQuestion\nwindowY=$windowY\nviewHeight=${compose.activity.window.decorView.height}\n")
            assertTrue("Question is covered by IME: bounds=$visibleQuestion imeTop=$imeTop", windowY >= 0 && windowY + visibleQuestion.height <= imeTop)
        } finally { instrumentation.runOnMainSync { ViewModelStore().apply { put("vm", vm); clear() } } }
    }
}

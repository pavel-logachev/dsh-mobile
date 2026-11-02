package dev.dshmobile.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerReceiptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class Repo : MobileRepository {
        val answer = QuestionAnswer(listOf(AnswerItem("q", listOf("Beta"))))
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            workspaces = listOf(Workspace("w", "Synthetic", true)),
            capabilities = MobileCapabilities(true, true, false, false, false, true, false, false),
            snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic question", "w", 1, true, true), emptyList(), 1, false, "waiting",
                pendingQuestions = listOf(PendingQuestion("call", "open", listOf(QuestionItem("q", "Alpha or Beta?", options = listOf(QuestionOption("Alpha"), QuestionOption("Beta")))))))))
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        override suspend fun answer(callId: String, answer: QuestionAnswer) {
            state.value = state.value.copy(pending = PendingCommand("receipt", "answer", "chat", null, "pending"))
        }
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
    @Test fun `pending answer renders neutral receipt then settled answer without failure`() {
        val repo = Repo(); val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { repo }
        try {
            compose.setContent { MaterialTheme { ChatScreen(repo.state.collectAsState().value, vm, "chat", {}, {}) } }
            compose.runOnIdle { vm.answer("call", repo.answer) }
            compose.waitUntil { repo.state.value.pending != null && vm.phoneAction == null }
            compose.onNodeWithText(compose.activity.getString(R.string.mobile_question_send_failed)).assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.mobile_delivery_pending)).assertIsDisplayed()
            capture("answer-pending.png")
            compose.runOnIdle { repo.state.value = repo.state.value.copy(pending = null, snapshot = repo.state.value.snapshot!!.copy(
                pendingQuestions = emptyList(), resolvedQuestions = listOf(ResolvedQuestion("call", "answered", repo.answer.answers)))) }
            compose.onNodeWithText(compose.activity.getString(R.string.mobile_delivery_pending)).assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.mobile_question_answer, "Beta")).assertIsDisplayed()
            assertNull(vm.phoneError); capture("answer-settled.png")
        } finally { androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() } }
    }
    private fun capture(name: String) {
        System.getenv("DSH_ANDROID_RENDER_DIR")?.let { output -> compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            java.io.File(output, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } }
    }
}

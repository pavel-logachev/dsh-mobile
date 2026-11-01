package dev.dshmobile.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class TurnFailureScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class Repo : MobileRepository {
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            capabilities = MobileCapabilities(true, true, false, false, false, false, false, false),
            snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic audit", "w", 1, false, true),
                listOf(ChatMessage("message", "user", "Synthetic message", 1)), 1, false, "idle",
                failure = TurnFailure("Synthetic upstream error ".repeat(70)))))
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
    @Test fun `AND02 actual chat preserves transcript and composer with maximum failure`() = render("portrait")
    @Test @Config(qualifiers = "ru-rRU-w800dp-h400dp-land")
    fun `AND02 landscape actual chat preserves transcript and composer`() = render("landscape")
    private fun render(orientation: String) {
        val repo = Repo(); val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { repo }
        try {
            val initial = repo.state.value
            compose.setContent { MaterialTheme { ChatScreen(initial, vm, "chat", {}, {}) } }
            compose.onNodeWithText("Synthetic message").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("turn_failure").fetchSemanticsNode().boundsInRoot.height < 200)
            compose.onNodeWithTag("message_input").assertIsDisplayed()
            System.getenv("DSH_ANDROID_RENDER_DIR")?.let { output -> compose.runOnIdle {
                val view = compose.activity.window.decorView
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                java.io.File(output, "turn-failure-chat-$orientation-fixed.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } }
        } finally { androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() } }
    }
}

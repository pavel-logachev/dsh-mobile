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
class QueueRemovalReceiptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class Repo(val status: String) : MobileRepository {
        var removals = 0
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            workspaces = listOf(Workspace("w", "Synthetic", true)),
            capabilities = MobileCapabilities(true, true, true, false, false, false, false, false, queue = true),
            snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic queue", "w", 1, true, true), emptyList(), 1, false, "running",
                pendingQueue = listOf(QueueItem("item", "Synthetic queued message", "queue")))))
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        override suspend fun queueAction(itemId: String, action: String) {
            assertEquals("item", itemId); assertEquals("remove", action); removals++
            state.value = state.value.copy(
                pending = if (status == "rejected") null else PendingCommand("receipt", "queue", "chat", null, status),
                error = when (status) { "rejected" -> "command_rejected"; "uncertain" -> "command_uncertain"; else -> null })
        }
        override suspend fun restore() {}
        override suspend fun refresh() {}
        override suspend fun pair(invitationJson: String, deviceName: String) {}
        override suspend fun pair(invitation: Invitation, deviceName: String) {}
        override suspend fun selectSession(sessionId: String) {}
        override suspend fun createSession(workspaceId: String, presetId: String?, requestId: String) {}
        override suspend fun updateDraft(text: String) {}
        override suspend fun sendMessage(text: String) { error("Removal must never send a message") }
        override suspend fun cancelRun() {}
        override suspend fun resolvePending() {}
        override suspend fun abandonPending() {}
        override suspend fun forget() {}
        override suspend fun setForeground(active: Boolean) {}
        override fun close() {}
    }
    @Test fun `pending removal is neutral until confirmed snapshot removes item`() = checkRemoval("pending")
    @Test fun `uncertain removal is not a definite failure and never resends`() = checkRemoval("uncertain")
    @Test fun `definite removal rejection still shows error and retains item`() = checkRemoval("rejected")

    private fun checkRemoval(status: String) {
        val repo = Repo(status); val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { repo }
        try {
            compose.setContent { MaterialTheme { ChatScreen(repo.state.collectAsState().value, vm, "chat", {}, {}) } }
            compose.onNodeWithTag("queue_remove_item").performClick()
            compose.waitUntil { repo.removals == 1 && vm.phoneAction == null }
            val error = compose.onNodeWithText(compose.activity.getString(R.string.mobile_queue_remove_failed))
            compose.onNodeWithTag("queue_item").assertIsDisplayed()
            if (status == "rejected") {
                error.assertIsDisplayed(); assertEquals("command_rejected", vm.phoneError)
                capture("queue-rejected.png")
            } else {
                error.assertDoesNotExist(); assertNull(vm.phoneError); assertNull(vm.phoneErrorAction)
                compose.onNodeWithText(compose.activity.getString(R.string.mobile_error_command_uncertain)).assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(if (status == "pending") R.string.mobile_delivery_pending else R.string.mobile_delivery_uncertain)).assertIsDisplayed()
                compose.onNodeWithTag("queue_remove_item").assertIsNotEnabled()
                capture("queue-$status.png")
                compose.runOnIdle { repo.state.value = repo.state.value.copy(pending = null, error = null,
                    snapshot = repo.state.value.snapshot!!.copy(cursor = 2, pendingQueue = emptyList())) }
                compose.onNodeWithTag("queue_item").assertDoesNotExist()
                error.assertDoesNotExist(); capture("queue-$status-settled.png")
            }
            compose.runOnIdle { assertEquals("No automatic resend", 1, repo.removals) }
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

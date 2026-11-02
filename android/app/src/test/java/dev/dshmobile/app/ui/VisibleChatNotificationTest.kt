package dev.dshmobile.app.ui

import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VisibleChatNotificationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Repo : MobileRepository {
        private val chats = listOf("chat", "other").map { SessionSummary(it, "Synthetic $it", "w", 1, false, true) }
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            sessions = chats, workspaces = listOf(Workspace("w", "Synthetic", true))))
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        override suspend fun selectSession(sessionId: String) {
            state.value = state.value.copy(snapshot = SessionSnapshot(chats.single { it.id == sessionId }, emptyList(), 1, false, "idle"), observingSession = true)
        }
        override suspend fun restore() {}
        override suspend fun refresh() {}
        override suspend fun pair(invitationJson: String, deviceName: String) {}
        override suspend fun pair(invitation: Invitation, deviceName: String) {}
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
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private var sequence = 0
    private val store by lazy { NotificationStore(MemoryWatchRecord(), StateCipher {
        javax.crypto.spec.SecretKeySpec(ByteArray(32) { 5 }, "AES") }) }
    private fun post(session: String = "chat", kind: String = "answer"): Int {
        val alert = WatchAlert("alert-${sequence++}", session, "turn", kind, "Synthetic", expiresAt = Long.MAX_VALUE)
        runBlocking { store.update { NotificationLocal(deviceId = "device", outbox = listOf(alert)) } }
        manager.cancelAll()
        NotificationPosting.recordForegroundPost()
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        runBlocking {
            WatchCoordinator(store::read, store::update, poll = { _, _, _, _ -> error("No poll during drain") },
                post = { item, replay -> postWatchAlert(app, "device", item, replay, store) }).drain()
            val settled = store.read()
            assertTrue("Suppressed and posted alerts both leave the outbox", settled.outbox.isEmpty())
            assertTrue(settled.deliveredTurns.any { it.id == alert.key })
        }
        return manager.activeNotifications.count { it.id == 2 }
    }
    @Test fun `completion and question follow rendered chat and lifecycle while error stays unchanged`() {
        val vm = MobileViewModel(app) { Repo() }
        try {
            ensureNotificationChannels(app)
            compose.setContent { MaterialTheme { MobileApp(vm) } }
            compose.onNodeWithTag("session_chat").performClick()
            compose.onNodeWithTag("chat_screen").assertIsDisplayed()
            assertEquals("Visible target chat must not notify", 0, post())
            assertEquals("Another chat must notify", 1, post("other"))
            assertEquals("Visible question must not notify", 0, post(kind = "attention"))
            assertEquals("Error alerts unchanged", 1, post(kind = "error"))
            capture("visible-chat.png")

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Background target chat must notify", 1, post())
            assertEquals("Background question must notify", 1, post(kind = "attention"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("chat_screen").assertIsDisplayed()
            assertEquals("Resume restores visible target suppression", 0, post())

            compose.onNodeWithTag("chat_drawer").performClick()
            compose.onNodeWithTag("home_screen").assertIsDisplayed()
            assertEquals("Home must notify even with selected repository snapshot", 1, post())
            assertEquals("Home question must notify", 1, post(kind = "attention"))
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("settings_screen").assertIsDisplayed()
            assertEquals("Settings must notify", 1, post())
            assertEquals("Settings question must notify", 1, post(kind = "attention"))
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("session_other").performClick()
            compose.onNodeWithTag("chat_screen").assertIsDisplayed()
            assertEquals("Different open chat must notify", 1, post())
            assertEquals("Different open chat question must notify", 1, post(kind = "attention"))
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() }
        }
    }
    @Test fun `opening a chat cancels only its question alert and resume clears background question`() {
        val vm = MobileViewModel(app) { Repo() }
        try {
            ensureNotificationChannels(app)
            compose.setContent { MaterialTheme { MobileApp(vm) } }
            assertEquals(1, post(kind = "attention"))
            val target = manager.activeNotifications.single { it.id == 2 }
            manager.notify(watchNotificationTag("device", "other"), 2, target.notification)
            compose.onNodeWithTag("session_chat").performClick()
            compose.onNodeWithTag("chat_screen").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf(watchNotificationTag("device", "other")), manager.activeNotifications.filter { it.id == 2 }.map { it.tag })
            }
            capture("question-opened.png")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("Background question posts", 1, post(kind = "attention"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("chat_screen").assertIsDisplayed()
            compose.runOnIdle { assertTrue(manager.activeNotifications.none { it.id == 2 }) }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() }
        }
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

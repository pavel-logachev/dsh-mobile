package dev.dshmobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsNavigationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Repo : MobileRepository {
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE))
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
    private fun withApp(test: (MobileViewModel) -> Unit) {
        val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { Repo() }
        try {
            compose.setContent { MaterialTheme { MobileApp(vm) } }
            test(vm)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() } }
    }
    @Test fun `rapid settings taps preserve return route for toolbar back`() = withApp {
        // Both taps are delivered before recomposition removes the outgoing home screen.
        val click = compose.onNodeWithTag("settings").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() }
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        capture("settings-before-back.png")
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        capture("settings-after-back.png")
    }
    @Test fun `repeated settings intent preserves return route for system back`() = withApp { vm ->
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.runOnIdle { vm.openNotificationSettings() }
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
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

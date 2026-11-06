package dev.dshmobile.app.acceptance

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.settings.NotificationSettingsContent
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.dshmobile.app.ui.settings.NotificationSettingsPanel
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.model.MobileCapabilities

/** Opt-in disposable-emulator capture and permission wiring; no host or pairing writes. */
class NotificationSettingsStandTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun wiring() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stage = InstrumentationRegistry.getArguments().getString("notificationWiringStage") ?: return
        val context = instrumentation.targetContext
        if (stage == "first" || stage == "request") context.getSharedPreferences("notification-permissions", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MobileTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    NotificationSettingsPanel(MobileState(capabilities = MobileCapabilities(false, false, false, false, false, false, false, false, promptOutcomes = true)))
                }
            } } }
            if (stage == "granted") {
                compose.onNode(isToggleable()).performClick()
                compose.waitUntil(10_000) { runCatching { compose.onNode(isToggleable()).assertIsOn(); true }.getOrDefault(false) }
            } else compose.onNode(isToggleable()).assertIsOff()
            if (stage == "granted") {
                compose.onNodeWithText("Не удалось включить уведомления.", substring = true).assertDoesNotExist()
                compose.onNodeWithText("Разрешите уведомления, чтобы узнавать о результате задач.").assertDoesNotExist()
            } else if (stage == "denied") {
                compose.onNodeWithText("Не удалось включить уведомления.", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Разрешите уведомления, чтобы узнавать о результате задач.").assertDoesNotExist()
            } else {
                compose.onNodeWithText("Разрешите уведомления, чтобы узнавать о результате задач.").assertIsDisplayed()
                compose.onNodeWithText("Не удалось включить уведомления.", substring = true).assertDoesNotExist()
            }
            compose.waitForIdle()
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.getExternalFilesDir(null), "settings-wiring-$stage.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            if (stage == "request") {
                compose.onNode(isToggleable()).assertIsEnabled().performClick()
                // Reuse this instrumentation's accessibility connection, not uiautomator's.
                compose.waitUntil(10_000) {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Запретить")?.firstOrNull()
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                compose.waitUntil(10_000) { context.getSharedPreferences("notification-permissions", android.content.Context.MODE_PRIVATE).getBoolean("denied", false) }
                compose.onNodeWithText("Не удалось включить уведомления.", substring = true).assertIsDisplayed()
            }
        }
    }
    @Test fun capture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stage = InstrumentationRegistry.getArguments().getString("notificationSettingsStage") ?: return
        val failed = stage == "denied"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MobileTheme {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    NotificationSettingsContent(true, false, failed, true, {}, {})
                }
            } } }
            instrumentation.waitForIdleSync()
            Thread.sleep(700)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "settings-$stage.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}

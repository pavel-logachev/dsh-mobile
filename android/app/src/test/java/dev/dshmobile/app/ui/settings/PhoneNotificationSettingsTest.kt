package dev.dshmobile.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w400dp-h800dp")
class PhoneNotificationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun `N6 one enabled switch no idle status and off action is observable`() {
        var enabled by mutableStateOf(true)
        compose.setContent { MaterialTheme { Column { NotificationSettingsContent(enabled, false, false, false, { enabled = it }, {}) } } }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        assertFalse(enabled)
        compose.onNodeWithText("Системные настройки уведомлений").assertIsDisplayed()
    }
    @Test fun `N6 permission missing and busy render honest disabled controls`() {
        compose.setContent { MaterialTheme { Column { NotificationSettingsContent(true, true, false, true, {}, {}) } } }
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("Не удалось включить уведомления.", substring = true).assertIsDisplayed()
    }
}

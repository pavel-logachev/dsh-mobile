package dev.dshmobile.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class NotificationChannelsTest {
    @Test fun localWatchChannelsPreserveUserSettingsAndRetireFeedChannels() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("answers_alert_v2", "User channel", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel("answers_silent_v2", "Retired", NotificationManager.IMPORTANCE_LOW))
        ensureNotificationChannels(app)
        assertEquals(setOf("answers_alert_v2", "attention_alert_v2"), notificationChannels.map { it.id }.toSet())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("answers_alert_v2").importance)
        assertNull(manager.getNotificationChannel("answers_alert_v2").sound)
        assertNull(manager.getNotificationChannel("answers_silent_v2"))
        val ru = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("ru")) })
        refreshNotificationChannelNames(ru)
        assertEquals("Ответы агента", manager.getNotificationChannel("answers_alert_v2").name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("answers_alert_v2").importance)
    }
}

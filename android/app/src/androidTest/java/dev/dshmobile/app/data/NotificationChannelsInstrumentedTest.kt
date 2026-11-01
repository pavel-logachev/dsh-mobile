package dev.dshmobile.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable-device follow-up only; local test acceptance does not claim this was run. */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsInstrumentedTest {
    @Test fun languageRefreshPreservesUserOwnedWatchChannelSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureNotificationChannels(context)
        val before = manager.getNotificationChannel("answers_alert_v2")
        val ru = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("ru")) })
        refreshNotificationChannelNames(ru)
        val after = manager.getNotificationChannel("answers_alert_v2")
        assertEquals("Ответы агента", after.name.toString()); assertEquals(before.id, after.id)
        assertEquals(before.importance, after.importance); assertEquals(before.sound, after.sound)
        assertEquals(before.shouldVibrate(), after.shouldVibrate())
        manager.createNotificationChannel(NotificationChannel("answers_silent_v2", "Retired", NotificationManager.IMPORTANCE_LOW))
        ensureNotificationChannels(context); assertNull(manager.getNotificationChannel("answers_silent_v2"))
    }
}

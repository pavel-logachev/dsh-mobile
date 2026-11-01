package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WatchPlatformTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun `N1 resume without persisted watches never starts a service`() = runBlocking {
        NotificationStore(app).reset()
        resumePhoneWatches(app)
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test @Config(sdk = [35])
    fun `N6 Android13 permission gate rejects ungranted post notifications`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(watchAlertsAllowed(app))
    }
    @Test fun `N1 direct empty service startup stops without foreground notification`() {
        val service = Robolectric.buildService(NotificationService::class.java).create()
        runBlocking { NotificationStore(app).reset() }
        assertEquals(android.app.Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertNull(shadowOf(service.get()).lastForegroundNotification)
        service.destroy()
    }
    @Test fun `N4 foreground channel is minimum silent neutral and has no badge`() {
        ensureWatchChannel(app)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(NotificationService.CONNECTION)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate()); assertFalse(channel.canShowBadge())
        val notification = watchForegroundNotification(app)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.PRIORITY_MIN, notification.priority)
        assertNotNull(notification.contentIntent)
    }
    @Test fun `N6 permission denial does not permit watch service`() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(watchAlertsAllowed(app))
    }
    @Test fun actualNotificationManagerReplacesWaitingWithFinalAndTapTargetsChat() = runBlocking {
        val store = NotificationStore(MemoryWatchRecord(), StateCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 5 }, "AES") })
        app.getSharedPreferences("mobile_language", 0).edit().putString("language", "ru").commit()
        for (channel in notificationChannels) app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(channel.id, channel.id, NotificationManager.IMPORTANCE_HIGH))
        val waiting = WatchAlert("waiting-key", "chat", "turn", "attention", "Synthetic", expiresAt = Long.MAX_VALUE)
        store.update { NotificationLocal(deviceId = "device", outbox = listOf(waiting)) }
        NotificationPosting.recordForegroundPost()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        postWatchAlert(app, "device", waiting, false, store)
        val manager = app.getSystemService(NotificationManager::class.java)
        val first = manager.activeNotifications.single { it.id == 2 }
        assertEquals("Нужен ответ", first.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, first.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val final = waiting.copy(key = "final-key", kind = "answer")
        store.update { it.copy(outbox = listOf(final)) }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        postWatchAlert(app, "device", final, false, store)
        val second = manager.activeNotifications.single { it.id == 2 }
        assertEquals(first.tag, second.tag); assertEquals("Ответ готов", second.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, second.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val intent = shadowOf(second.notification.contentIntent).savedIntent
        assertEquals("chat", intent.getStringExtra("notificationChat")); assertEquals("device", intent.getStringExtra("notificationDevice"))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        postWatchAlert(app, "device", final, true, store)
        assertTrue(manager.activeNotifications.single { it.id == 2 }.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val failure = final.copy(key = "error", kind = "error", summary = "Ошибка 😀")
        store.update { it.copy(outbox = listOf(failure)) }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        postWatchAlert(app, "device", failure, false, store)
        assertTrue(manager.activeNotifications.single { it.id == 2 }.notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("Ошибка 😀"))
    }
    @Test fun `boot restore receiver ignores unrelated broadcast without starting work`() {
        WatchRestoreReceiver().onReceive(app, Intent("synthetic.UNRELATED"))
        assertNull(shadowOf(app).nextStartedService)
    }
}

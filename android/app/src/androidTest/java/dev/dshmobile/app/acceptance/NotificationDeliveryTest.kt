package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Disposable-device lifecycle check. Authoritative outcome cases live in WatchCoordinatorTest. */
class NotificationDeliveryTest {
    @Test fun idleStartupHasNoForegroundNotificationOrService(): Unit = runBlocking {
        NotificationTestHost().use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startService(Intent(context, NotificationService::class.java)) }
                withTimeout(5000) {
                    while (context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                            .any { it.service.className == NotificationService::class.java.name }) delay(50)
                }
                assertTrue(manager.activeNotifications.none { it.id == 1 })
            }
        }
    }
}

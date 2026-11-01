package dev.dshmobile.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.data.*
import org.junit.Assert.*
import org.junit.Test

/** Platform channel policy, without the retired always-on feed fixture. */
class NotificationFixtureTest {
    @Test fun phoneTaskChannelRequestsMinimumSilentImportance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ensureWatchChannel(context)
        val channel = context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(NotificationService.CONNECTION)
        // Android may raise an active FGS channel; exact request is covered under Robolectric.
        assertTrue(channel.importance <= android.app.NotificationManager.IMPORTANCE_LOW)
        assertFalse(channel.canShowBadge()); assertNull(channel.sound); assertFalse(channel.shouldVibrate())
    }
}

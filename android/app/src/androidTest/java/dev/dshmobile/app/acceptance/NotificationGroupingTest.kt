package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotificationGroupingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: NotificationTestHost
    @org.junit.Before fun freshHost() { host = NotificationTestHost() }
    @org.junit.After fun stopHost() { host.close() }
    @Test fun summaryPluralsSelectRussianAndEnglishFormsWithoutChildContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((locale, expected) in listOf("ru" to listOf("1 чат", "2 чата", "5 чатов", "21 чат"), "en" to listOf("1 chat", "2 chats", "5 chats", "21 chats"))) {
            val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(locale)) })
            listOf(1, 2, 5, 21).forEachIndexed { index, count ->
                val model = NotificationSummary(count, count, count)
                assertEquals(expected[index], summaryTitle(localized, model))
                assertTrue(summaryBody(localized, model).contains(" · "))
            }
        }
    }
    @Test fun oneAlert() = exercise(1)
    @Test fun fourAlerts() = exercise(4)
    private fun exercise(count: Int) {
        val stage = InstrumentationRegistry.getArguments().getString("manualStage") == "true"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll(); VisibleNotificationChat.sessionId = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val service = Intent(context, NotificationService::class.java)
        runBlocking { ensureNotificationChannels(context) }
        val original = android.app.Notification.Builder(context, "answers_alert_v2")
            .setSmallIcon(dev.dshmobile.app.R.drawable.ic_launcher_foreground).setGroup(ALERT_GROUP)
            .setContentTitle("Synthetic").setContentIntent(notificationChatIntent(context, "synthetic", "chat", null))
            .addExtras(alertLanguageExtras("answers", "Synthetic")).build()
        runBlocking { NotificationPosting.paced { manager.notify("synthetic:chat:answers", 2, original); updateAlertSummary(context, added = "synthetic:chat:answers" to original) } }
        for (index in 2..count) {
            val tag = "synthetic-group-$index"
            val child = android.app.Notification.Builder(context, original.channelId)
                .setSmallIcon(dev.dshmobile.app.R.drawable.ic_launcher_foreground).setGroup(ALERT_GROUP)
                .setContentTitle("Synthetic").setContentIntent(original.contentIntent)
                .addExtras(android.os.Bundle(original.extras).apply { putString(ALERT_CHAT, "chat-$index") }).build()
            runBlocking { NotificationPosting.paced { manager.notify(tag, 2, child); updateAlertSummary(context, added = tag to child) } }
        }
        compose.waitUntil(5000) { manager.activeNotifications.count { it.id == 2 } == count && manager.activeNotifications.any { it.id == 3 && it.notification.number == count } }
        assertTrue(manager.activeNotifications.none { it.id == 1 })
        val summary = manager.activeNotifications.single { it.id == 3 }
        assertEquals(ALERT_GROUP, summary.notification.group)
        assertEquals(count, summary.notification.number)
        assertEquals(SUMMARY_CHANNEL, summary.notification.channelId)
        assertFalse(summary.notification.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains("computer"))
        assertTrue(manager.activeNotifications.filter { it.id == 2 }.all { it.groupKey == summary.groupKey })
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys notification --noredact")
        File(context.filesDir, "notification-groups-$count.txt").outputStream().use { out -> android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).use { it.copyTo(out) } }
        if (!stage) {
            // Deterministic routing check; actual shade taps are recorded separately via adb.
            summary.notification.contentIntent.send()
            val tag = "session_chat"
            compose.waitUntil(15000) {
                try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                catch (_: IllegalStateException) { false } // CLEAR_TOP can briefly remove all compositions.
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            context.stopService(service); manager.cancelAll()
            instrumentation.runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }.filterIsInstance<MainActivity>().forEach { it.finishAndRemoveTask() }
            }
            VisibleNotificationChat.sessionId = null
        }
        host.closeScenario(scenario)
    }
}

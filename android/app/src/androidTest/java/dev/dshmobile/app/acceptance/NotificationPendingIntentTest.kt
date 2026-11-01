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

/** Exercises the production chat PendingIntent and routing independently of SystemUI/service. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NotificationPendingIntentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: NotificationTestHost
    @org.junit.Before fun freshHost() { host = NotificationTestHost() }
    @org.junit.After fun stopHost() { host.close() }
    @Test fun coldActivity() = exercise("cold")
    @Test fun warmBackground() = exercise("warm")
    @Test fun foreground() = exercise("foreground")
    @Test fun rapidAToBSupersedesSlowTargetWithoutLateNavigationOrActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        host.twoChats = true
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val service = Intent(context, NotificationService::class.java)
        try {
            val first = notificationChatIntent(context, "synthetic", "chat", null)
            host.historyDelayMs = 3000
            first.send()
            compose.waitUntil(2000) { compose.onAllNodesWithTag("chat_screen").fetchSemanticsNodes().isNotEmpty() }
            val second = android.app.PendingIntent.getActivity(context, 90, Intent(context, MainActivity::class.java)
                .putExtra("notificationDevice", "synthetic").putExtra("notificationChat", "other")
                .addFlags(NOTIFICATION_ACTIVITY_FLAGS), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            second.send()
            compose.waitUntil(10000) { try { compose.onNodeWithTag("message_input").assertIsEnabled(); true } catch (_: AssertionError) { false } }
            compose.onAllNodesWithTag("question_question").assertCountEquals(0)
            Thread.sleep(3200)
            compose.onAllNodesWithTag("question_question").assertCountEquals(0)
        } finally { host.closeScenario(scenario); context.stopService(service) }
    }
    @Test fun failedNotificationTargetRecoversOnReconnectWithoutAnotherTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        host.twoChats = true
        // Persist A as the previous selected chat before the Activity handles B.
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val repository = NetworkMobileRepository(EncryptedStateStore(context), owner, true)
        try { runBlocking { repository.setForeground(true); repository.restore(); repository.selectSession("chat") } }
        finally { repository.close(); owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        host.failOtherHistory = true
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            android.app.PendingIntent.getActivity(context, 91, Intent(context, MainActivity::class.java)
                .putExtra("notificationDevice", "synthetic").putExtra("notificationChat", "other")
                .addFlags(NOTIFICATION_ACTIVITY_FLAGS), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT).send()
            compose.waitUntil(10000) { host.historyRequests.contains("/v1/sessions/other") }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("chat_screen").fetchSemanticsNodes().isNotEmpty() &&
                    (compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isEmpty() ||
                        try { compose.onNodeWithTag("message_input").assertIsNotEnabled(); true } catch (_: AssertionError) { false })
            }
            val oldReads = host.historyRequests.count { it == "/v1/sessions/chat" }
            host.failOtherHistory = false
            try {
                compose.waitUntil(15000) { try { compose.onNodeWithTag("message_input").assertIsEnabled(); true } catch (_: AssertionError) { false } }
            } catch (failure: Throwable) {
                throw AssertionError("History requests: ${host.historyRequests}; selected: ${runBlocking { EncryptedStateStore(context).read().selectedSessionId }}; UI: ${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
            }
            assertEquals(oldReads, host.historyRequests.count { it == "/v1/sessions/chat" })
            assertEquals("other", runBlocking { EncryptedStateStore(context).read().selectedSessionId })
            assertTrue(host.historyRequests.count { it == "/v1/sessions/other" } >= 2)
        } finally { host.closeScenario(scenario) }
    }
    @Test fun delayedHistoryShowsTargetBeforeNetworkCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val service = Intent(context, NotificationService::class.java)
        try {
            val notification = android.app.Notification.Builder(context, "attention_alert_v2")
                .setSmallIcon(dev.dshmobile.app.R.drawable.ic_launcher_foreground)
                .setContentIntent(notificationChatIntent(context, "synthetic", "chat", null)).build()
            host.historyDelayMs = 3000
            notificationTrace("test_pending_send_delayed")
            notification.contentIntent.send()
            compose.waitUntil(2000) { try { compose.onAllNodesWithTag("chat_screen").fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false } }
            // Cached options may edit a local draft, but no stale answer can be sent.
            compose.onNodeWithTag("question_option_0").performScrollTo().performClick()
            compose.onNodeWithTag("question_submit").assertIsNotEnabled()
            notificationTrace("test_loading_visible_delayed")
            compose.waitUntil(10000) { try { compose.onNodeWithTag("question_submit").assertIsEnabled(); true } catch (_: AssertionError) { false } }
            notificationTrace("test_question_visible_delayed")
        } finally { host.closeScenario(scenario); context.stopService(service) }
    }
    @Test fun zResolvedBeforeTap() = exercise("resolved")
    private fun exercise(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val service = Intent(context, NotificationService::class.java)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val notification = android.app.Notification.Builder(context, "attention_alert_v2")
                .setSmallIcon(dev.dshmobile.app.R.drawable.ic_launcher_foreground)
                .setContentIntent(notificationChatIntent(context, "synthetic", "chat", null)).build()
            assertNotNull(notification.contentIntent)
            if (mode == "cold") host.closeScenario(scenario)
            if (mode == "warm") {
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
                compose.waitUntil(5000) { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
            }
            if (mode == "resolved") {
                val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val repository = NetworkMobileRepository(EncryptedStateStore(context), owner, true)
                try { runBlocking {
                    repository.setForeground(true); repository.restore(); repository.selectSession("chat")
                    repository.answer("question", dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Unit tests")))))
                    assertNull(repository.state.value.error)
                } } finally { repository.close(); owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
                host.closeScenario(scenario)
            }
            notificationTrace("test_pending_send_$mode")
            var originalActivity: MainActivity? = null
            if (mode == "warm" || mode == "foreground") scenario.onActivity { originalActivity = it }
            notification.contentIntent.send()
            val tag = if (mode == "resolved") "question_record_question" else "question_question"
            compose.waitUntil(15000) {
                // On API30 HOME -> PendingIntent can briefly have no registered composition.
                try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                catch (_: IllegalStateException) { false }
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            if (originalActivity != null) scenario.onActivity { assertSame(originalActivity, it) }
            if (mode != "resolved") compose.onNodeWithTag("question_option_0").assertExists()
            val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
            File(context.filesDir, "notification-activity-$mode.txt").outputStream().use { out -> android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).use { it.copyTo(out) } }
            notificationTrace("test_question_visible_$mode")
            if (mode != "resolved") {
                compose.onNodeWithTag("question_option_0").performScrollTo().performClick()
                compose.onNodeWithTag("question_submit").assertIsEnabled()
            }
        } finally {
            host.closeScenario(scenario); context.stopService(service); manager.cancelAll()
            // CLEAR_TOP recreates the standard-launchMode Activity; scenario owns the old instance.
            instrumentation.runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }
                    .filterIsInstance<MainActivity>().forEach { if (!it.isFinishing) it.finishAndRemoveTask() }
            }
            instrumentation.waitForIdleSync()
        }
    }
}

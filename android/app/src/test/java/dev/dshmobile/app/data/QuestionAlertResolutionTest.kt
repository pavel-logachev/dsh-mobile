package dev.dshmobile.app.data

import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class QuestionAlertResolutionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val request = "00000000-0000-4000-8000-000000000001"
    private val store = NotificationStore(MemoryWatchRecord(), StateCipher {
        javax.crypto.spec.SecretKeySpec(ByteArray(32) { 5 }, "AES") })
    private fun outcome(status: String) = PromptOutcomes(status, listOf(PromptOutcome(request, status, "2026-11-01T12:00:00Z", "turn")))
    private suspend fun drain() {
        NotificationPosting.recordForegroundPost()
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        WatchCoordinator(store::read, store::update, { _, _, _, _ -> error("No poll") },
            { alert, replay -> postWatchAlert(app, "device", alert, replay, store) },
            clearResolved = { cancelResolvedQuestionAlerts(app, it) }).drain()
    }
    private suspend fun waiting() {
        ensureNotificationChannels(app)
        store.update { NotificationLocal().registerWatch("device", "chat", request, System.currentTimeMillis()).reconcileWatches("chat", outcome("waiting"), System.currentTimeMillis()) }
        drain()
        val question = manager.activeNotifications.single { it.id == 2 }
        manager.notify(watchNotificationTag("device", "other"), 2, question.notification)
    }
    @Test fun `desktop resolution while phone is backgrounded removes only target question before completion`() = runBlocking {
        waiting()
        store.update { it.reconcileWatches("chat", outcome("running"), System.currentTimeMillis()) }
        drain()
        assertEquals(listOf(watchNotificationTag("device", "other")), manager.activeNotifications.filter { it.id == 2 }.map { it.tag })
        assertTrue(store.read().outbox.isEmpty())
        // A reconstructed durable owner must not resurrect the resolved question.
        drain()
        assertEquals(1, manager.activeNotifications.count { it.id == 2 })
    }
    @Test fun `visible final clears preexisting question even when completion is suppressed`() = runBlocking {
        waiting()
        val owner = Any()
        visibleChat.show(owner, "chat")
        try {
            store.update { it.reconcileWatches("chat", outcome("completed"), System.currentTimeMillis()) }
            drain()
            assertEquals(listOf(watchNotificationTag("device", "other")), manager.activeNotifications.filter { it.id == 2 }.map { it.tag })
        } finally { visibleChat.clear(owner) }
    }
    @Test fun `resolved queued question is acknowledged without stale publication`() = runBlocking {
        ensureNotificationChannels(app)
        store.update { NotificationLocal().registerWatch("device", "chat", request, System.currentTimeMillis())
            .reconcileWatches("chat", outcome("waiting"), System.currentTimeMillis())
            .reconcileWatches("chat", outcome("running"), System.currentTimeMillis()) }
        drain()
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(store.read().outbox.isEmpty())
        assertEquals(1, store.read().deliveredTurns.size)
    }
    @Test fun `rejected previously waiting turn clears question without a final alert`() = runBlocking {
        waiting()
        store.update { it.reconcileWatches("chat", outcome("rejected"), System.currentTimeMillis()) }
        drain()
        assertEquals(listOf(watchNotificationTag("device", "other")), manager.activeNotifications.filter { it.id == 2 }.map { it.tag })
    }
    @Test fun `opening never cancels completion failure other chat or foreground notification`() = runBlocking {
        waiting()
        val question = manager.activeNotifications.first { it.id == 2 }.notification
        manager.notify(null, 1, watchForegroundNotification(app))
        val ticket = NotificationPosting.ticket(watchNotificationTag("device", "chat"), 2)
        try {
            cancelChatQuestionAlert(app, "chat")
            NotificationPosting.commit(ticket) { manager.notify(watchNotificationTag("device", "chat"), 2, question) }
            assertTrue(manager.activeNotifications.none { it.tag == watchNotificationTag("device", "chat") })
            for (kind in listOf("answer", "error")) {
                val alert = WatchAlert(kind, "chat", "turn", kind, "Synthetic", expiresAt = Long.MAX_VALUE)
                store.update { it.copy(outbox = listOf(alert)) }
                drain()
                cancelChatQuestionAlert(app, "chat")
                assertEquals(3, manager.activeNotifications.size)
            }
        } finally { NotificationPosting.release(ticket) }
    }
    @Test fun `unknown and repeated waiting do not clear question and later episodes still alert`() = runBlocking {
        waiting()
        store.update { it.reconcileWatches("chat", outcome("unknown"), System.currentTimeMillis()) }
        drain()
        assertEquals(2, manager.activeNotifications.count { it.id == 2 })
        store.update { it.reconcileWatches("chat", outcome("waiting"), System.currentTimeMillis()) }
        drain()
        assertEquals(2, manager.activeNotifications.count { it.id == 2 })
        store.update { it.reconcileWatches("chat", outcome("running"), System.currentTimeMillis()) }
        drain()
        store.update { it.reconcileWatches("chat", outcome("waiting"), System.currentTimeMillis()) }
        drain()
        assertEquals(2, manager.activeNotifications.count { it.id == 2 })
    }
}

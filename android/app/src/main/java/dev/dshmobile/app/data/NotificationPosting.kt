package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground promotion is synchronous; only subsequent, best-effort posts wait in this lane. */
internal object NotificationPosting {
    val errors = CoroutineExceptionHandler { _, failure -> logFailure(failure) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
    private val lane = Mutex()
    @Volatile private var lastPost = SystemClock.elapsedRealtime()
    private var refresh: Job? = null
    private val pending = mutableSetOf<Ticket>()
    internal class Ticket(val tag: String?, val id: Int, var valid: Boolean = true)

    fun logFailure(failure: Throwable) { Log.w("NotificationPosting", "Notification operation failed: ${failure.javaClass.simpleName}") }
    fun recordForegroundPost() { lastPost = SystemClock.elapsedRealtime() }
    @Synchronized fun ticket(tag: String?, id: Int) = Ticket(tag, id).also { pending.add(it) }
    @Synchronized fun release(ticket: Ticket) { pending.remove(ticket) }
    @Synchronized fun commit(ticket: Ticket, block: () -> Unit) { if (ticket.valid) block() }
    @Synchronized fun invalidateChat(chat: String) {
        pending.filter { it.tag?.endsWith(":$chat:answers") == true || it.tag?.endsWith(":$chat:attention") == true }.forEach { it.valid = false }
    }
    @Synchronized fun invalidate(tag: String?, id: Int) { pending.filter { it.tag == tag && it.id == id }.forEach { it.valid = false } }
    @Synchronized fun cancel(context: Context, tag: String?, id: Int) {
        invalidate(tag, id)
        safely {
            context.getSystemService(NotificationManager::class.java).cancel(tag, id)
            if (id == 2) updateAlertSummary(context, removed = tag)
        }
    }
    @Synchronized fun cancelAll(context: Context) {
        pending.forEach { it.valid = false }
        clearAlertSummaryState()
        safely { context.getSystemService(NotificationManager::class.java).cancelAll() }
    }
    @Synchronized fun cancelAlerts(context: Context) {
        pending.filter { it.id == 2 || it.id == 3 }.forEach { it.valid = false }
        clearAlertSummaryState()
        safely { context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.id == 2 || it.id == 3 }.forEach { context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id) } }
    }
    fun safely(block: () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { logFailure(failure) }
    }
    suspend fun <T> paced(block: suspend () -> T): T = lane.withLock {
        // A synchronous foreground promotion can move the timestamp while this coroutine waits.
        while (true) {
            val remaining = 260L - (SystemClock.elapsedRealtime() - lastPost)
            if (remaining <= 0) break
            delay(remaining)
        }
        currentCoroutineContext().ensureActive()
        try { block() } finally { lastPost = SystemClock.elapsedRealtime() }
    }
    @Synchronized fun refresh(context: Context): Job {
        refresh?.cancel()
        return scope.launch { withTimeoutOrNull(60_000) { refreshNotificationLanguagePass(context.applicationContext) } }.also { refresh = it }
    }
}

/** Render at delivery, not enqueue time; cancellations invalidate all already queued matching posts. */
internal suspend fun postNotification(context: Context, tag: String?, id: Int, notification: Notification) {
    val ticket = NotificationPosting.ticket(tag, id)
    try {
        NotificationPosting.paced {
            NotificationPosting.safely {
                val latest = translatedNotification(context, id, tag, notification, silent = false) ?: notification
                NotificationPosting.commit(ticket) { context.getSystemService(NotificationManager::class.java).notify(tag, id, latest) }
            }
        }
    } finally { NotificationPosting.release(ticket) }
}

package dev.dshmobile.app.data

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
    @Synchronized fun cancelAll(context: Context) {
        pending.forEach { it.valid = false }
        safely { context.getSystemService(NotificationManager::class.java).cancelAll() }
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

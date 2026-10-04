package dev.dshmobile.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque

/** One transport-owned worker; terminal cleanup has a reserved slot and replaces queued work. */
internal class TransportIoOwner {
    private val lock = Object()
    private val pending = ArrayDeque<() -> Unit>()
    private val finished = CompletableDeferred<Unit>()
    private var retired = false
    private var terminal: (() -> Unit)? = null
    private var worker: Thread? = null

    fun submit(task: () -> Unit): Boolean = synchronized(lock) {
        if (retired || pending.size >= MAX_PENDING) return false
        pending.addLast(task)
        startLocked()
        lock.notifyAll()
        true
    }

    /** Never rejects the single terminal task, even when all eight ordinary slots are occupied. */
    fun close(cleanup: () -> Unit) = synchronized(lock) {
        if (retired) return
        retired = true
        pending.clear()
        terminal = cleanup
        startLocked()
        lock.notifyAll()
    }

    suspend fun awaitClosed() {
        if (kotlinx.coroutines.withTimeoutOrNull(CLOSE_TIMEOUT_MS) { finished.await(); true } == null)
            throw MobileFailure("network_unavailable")
    }

    private fun startLocked() {
        if (worker != null) return
        worker = Thread({ work() }, "dsh-transport-cleanup").apply { isDaemon = true; start() }
    }

    private fun work() {
        while (true) {
            var isTerminal = false
            val task = synchronized(lock) {
                while (terminal == null && pending.isEmpty()) lock.wait()
                if (terminal != null) {
                    isTerminal = true
                    terminal!!.also { terminal = null }
                } else pending.removeFirst()
            }
            try {
                task()
                if (isTerminal) { finished.complete(Unit); return }
            } catch (failure: Exception) {
                if (isTerminal) { finished.completeExceptionally(failure); return }
                // An ordinary cancellation can fail; terminal cleanup still owns every socket.
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 8
        const val CLOSE_TIMEOUT_MS = 10_000L
    }
}

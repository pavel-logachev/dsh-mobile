package dev.dshmobile.app.data

import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Bounded opaque bytes between the WebSocket receiver and the local CONNECT socket. */
internal class RelayIncoming(private val nanoTime: () -> Long = System::nanoTime) {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    @Volatile private var aborted = false
    @Volatile private var endedAt = 0L
    @Volatile private var writingAt = 0L
    val isFinished: Boolean get() = aborted || endedAt != 0L

    fun offer(bytes: ByteArray) {
        if (bytes.size > RelayWebSocketDraft.MAX_FRAME) throw IOException("invalid relay frame")
        val until = nanoTime() + STALL_NANOS
        synchronized(lock) {
            while (!isFinished && chunks.size >= MAX_PENDING) {
                val remaining = until - nanoTime()
                if (remaining <= 0) throw IOException("relay stalled")
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            if (isFinished) return
            chunks.addLast(bytes)
            lock.notifyAll()
        }
    }

    /** EOF is ordered after the last accepted chunk; it is not transport cancellation. */
    fun finish() = synchronized(lock) {
        if (endedAt == 0L) endedAt = nanoTime()
        lock.notifyAll()
    }
    fun abort() = synchronized(lock) {
        aborted = true
        chunks.clear()
        lock.notifyAll()
    }

    fun drainTo(output: OutputStream) {
        while (true) {
            val bytes = synchronized(lock) {
                while (!aborted && chunks.isEmpty() && endedAt == 0L) lock.wait()
                if (aborted || chunks.isEmpty()) return
                chunks.removeFirst().also { writingAt = nanoTime(); lock.notifyAll() }
            }
            // Never hold a lifecycle/buffer monitor across physical IO; abort closes the socket.
            try { if (!aborted) { output.write(bytes); output.flush() } }
            finally { writingAt = 0L }
        }
    }

    fun isStalled(now: Long): Boolean = stalled { now }
    /** Clock overload is an internal deterministic scheduling seam, absent from the normal allocation-free path. */
    internal fun isStalled(now: () -> Long): Boolean = stalled(now)
    private inline fun stalled(now: () -> Long): Boolean {
        val ended = endedAt
        val writing = writingAt
        val time = now()
        return (ended != 0L && time - ended >= STALL_NANOS) || (writing != 0L && time - writing >= STALL_NANOS)
    }
    private companion object {
        const val MAX_PENDING = 8
        val STALL_NANOS = TimeUnit.SECONDS.toNanos(10)
    }
}

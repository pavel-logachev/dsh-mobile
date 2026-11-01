package dev.dshmobile.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One sequential consumer over a conflated slot. Producers never wait and never
 * grow a queue: while the consumer is busy, only the newest replacement survives.
 * Full snapshots are self-contained, so dropping an older one loses no increment.
 */
internal class SnapshotIngress<T>(
    scope: CoroutineScope,
    private val consume: suspend (T) -> Unit,
) {
    private val channel = Channel<T>(Channel.CONFLATED)
    private val job: Job = scope.launch { for (value in channel) consume(value) }

    /** Returns false after close; callers must not treat that as an observed snapshot. */
    fun offer(value: T): Boolean = channel.trySend(value).isSuccess

    fun close() {
        channel.close()
        job.cancel()
    }
}

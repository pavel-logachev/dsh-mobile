package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by the service and tests. Serializes authoritative cuts with outbox delivery.
 * A post is acknowledged only after notify succeeds; crash replay replaces the same stable ID,
 * with onlyAlertOnce, rather than manufacturing another notification or replaying a mutation. */
internal class WatchCoordinator(private val read: suspend () -> NotificationLocal,
    private val update: suspend ((NotificationLocal) -> NotificationLocal) -> NotificationLocal,
    private val snapshot: suspend (String) -> SessionSnapshot,
    private val post: suspend (WatchAlert, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    suspend fun resync() = lock.withLock {
        val local = update { it.pruneWatches(now()) }
        if (!local.enabled) return@withLock
        var failed: Exception? = null
        for (session in local.watches.map { it.sessionId }.distinct()) {
            try {
                val cut = snapshot(session)
                update { current -> if (current.enabled && current.deviceId == local.deviceId) current.reconcileWatches(cut, now()) else current }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = failure }
        }
        drainLocked() // One broken chat must not starve successful unrelated outcomes.
        failed?.let { throw it }
    }
    suspend fun drain() = lock.withLock { drainLocked() }
    private suspend fun drainLocked() {
        for (alert in read().outbox) {
            val current = read()
            if (!current.enabled || current.outbox.none { it.key == alert.key } || alert.expiresAt <= now()) continue
            post(alert, true) // Replays retain the same identity, channel and only-alert-once flag.
            update { it.acknowledgeWatchAlert(alert.key, now()) }
        }
    }
}

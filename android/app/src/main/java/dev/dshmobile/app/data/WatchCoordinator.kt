package dev.dshmobile.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner serializes every HTTP poll and durable outbox drain. Tokens are scoped to the
 * exact ordered request set: adding a watch must never wait behind an unrelated old token. */
internal class WatchCoordinator(private val read: suspend () -> NotificationLocal,
    private val update: suspend ((NotificationLocal) -> NotificationLocal) -> NotificationLocal,
    private val poll: suspend (String, List<String>, String?, Int) -> PromptOutcomes,
    private val post: suspend (WatchAlert, Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private data class Cut(val ids: List<String>, val token: String)
    private val tokens = mutableMapOf<String, Cut>()
    suspend fun resync() = round(wait = false)
    suspend fun round(wait: Boolean = true) = lock.withLock {
        val local = update { it.pruneWatches(now()) }
        if (!local.enabled) return@withLock
        drainLocked()
        var failed: Exception? = null
        val sessions = local.watches.map { it.sessionId }.distinct()
        tokens.keys.retainAll(sessions.toSet())
        for (session in sessions) {
            val current = read().pruneWatches(now())
            if (!current.enabled || current.deviceId != local.deviceId) break
            val ids = current.watches.filter { it.sessionId == session }.map { it.requestId }
            if (ids.isEmpty()) continue
            val since = tokens[session]?.takeIf { it.ids == ids }?.token
            // A lone group can hold 25 s. Multiple groups get a 1 s slice, preventing a
            // quiet chat from delaying all other tasks by 25 s each. No periodic sleep.
            val seconds = if (wait && since != null) if (sessions.size == 1) 25 else 1 else 0
            val result = try { poll(session, ids, since, seconds) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is MobileFailure && failure.key in setOf("forbidden", "session_not_found")) {
                    tokens.remove(session)
                    update { value -> if (value.deviceId == local.deviceId)
                        value.copy(watches = value.watches.filterNot { it.sessionId == session }) else value }
                    continue
                }
                // Device-global errors dominate transient failures, independent of group order.
                if (failure is MobileFailure && !notificationRetryable(failure.key)) throw failure
                if (failed == null) failed = failure
                continue
            }
            // Storage / posting errors are not per-session HTTP failures and must not be masked.
            run {
                tokens[session] = Cut(ids, result.token)
                update { value -> if (value.enabled && value.deviceId == local.deviceId)
                    value.reconcileWatches(session, result, now()) else value }
                drainLocked()
            }
        }
        failed?.let { throw it }
    }
    suspend fun drain() = lock.withLock { drainLocked() }
    private suspend fun drainLocked() {
        for (alert in read().outbox) {
            val current = read()
            if (!current.enabled || current.outbox.none { it.key == alert.key } || alert.expiresAt <= now()) continue
            // Persist attempt BEFORE crossing NotificationManager. Replay may replace the
            // visible item silently; a new waiting/final phase may alert again on the same tag.
            update { it.copy(outbox = it.outbox.map { item -> if (item.key == alert.key) item.copy(attempted = true) else item }) }
            post(alert, alert.attempted)
            update { it.acknowledgeWatchAlert(alert.key, now()) }
        }
    }
}

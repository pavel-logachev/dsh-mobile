package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlin.random.Random

/** A reconnect generation owns one loop. Offline suspends until the network callback cancels
 * it; the only deadlines are watch expiry and bounded online-error backoff, not idle polling. */
internal class WatchPollLoop(private val update: suspend ((NotificationLocal) -> NotificationLocal) -> NotificationLocal,
    private val coordinator: WatchCoordinator,
    private val supported: suspend () -> Boolean,
    private val online: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val allowed: () -> Boolean = { true },
    private val backoff: suspend (Int, Long) -> Unit = { attempt, retryAfter ->
        delay(notificationRetryDelay(attempt, retryAfter, Random.nextDouble(0.5, 1.0)))
    }) {
    suspend fun run() {
        var negotiated = false
        var immediate = true
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val local = update { it.pruneWatches(now()) }
            if (!allowed() || !local.enabled || (local.watches.isEmpty() && local.outbox.isEmpty())) return
            val expiry = (local.watches.map { it.expiresAt } + local.outbox.map { it.expiresAt }).min()
            try {
                withTimeout((expiry - now()).coerceAtLeast(1)) {
                    if (!online()) awaitCancellation() // Network callback restarts with waitSeconds=0.
                    if (!negotiated) {
                        if (!supported()) {
                            update { it.copy(watches = emptyList(), outbox = emptyList()) }
                            return@withTimeout
                        }
                        negotiated = true
                    }
                    coordinator.round(wait = !immediate)
                    immediate = false
                    attempt = 0
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                update { it.pruneWatches(now()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is MobileFailure && (failure.key == "notifications_blocked" || !notificationRetryable(failure.key))) return
                // Losing connectivity never enters the sleep/backoff path.
                if (online()) withTimeoutOrNull((expiry - now()).coerceAtLeast(1)) {
                    backoff(attempt++, (failure as? MobileFailure)?.retryAfterMs ?: 0)
                }
                immediate = true
            }
        }
    }
}

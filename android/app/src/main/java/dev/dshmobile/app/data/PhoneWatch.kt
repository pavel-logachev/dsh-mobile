package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.serialization.Serializable

internal const val WATCH_TTL_MS = 24 * 60 * 60 * 1000L
private const val WATCH_CAP = 32
@Serializable internal data class PhoneWatch(val sessionId: String, val requestId: String, val expiresAt: Long)
@Serializable internal data class WatchAlert(val key: String, val sessionId: String, val turnId: String,
    val kind: String, val title: String, val summary: String? = null, val expiresAt: Long)

internal fun NotificationLocal.pruneWatches(now: Long) = copy(
    watches = watches.filter { it.expiresAt > now }.takeLast(WATCH_CAP),
    outbox = outbox.filter { it.expiresAt > now }.takeLast(WATCH_CAP),
    deliveredTurns = deliveredTurns.filter { it.at > now - WATCH_TTL_MS }.takeLast(2048))
internal fun NotificationLocal.needsWatchService(now: Long) = enabled && watches.any { it.expiresAt > now }
internal fun NotificationLocal.registerWatch(device: String, session: String, request: String, now: Long): NotificationLocal {
    if (!enabled) return this
    val current = (if (deviceId == null || deviceId == device) this else NotificationLocal(enabled = enabled)).pruneWatches(now)
    if (current.watches.any { it.requestId == request }) return current
    return current.copy(deviceId = device, watches = (current.watches + PhoneWatch(session, request, now + WATCH_TTL_MS)).takeLast(WATCH_CAP))
}
internal fun watchTurnKey(session: String, turn: String) = "${session.length}:$session$turn"

/** Only explicit per-request authoritative outcomes decide settlement. Idle/chat timestamps/feed
 * events cannot identify a phone turn after an observation gap. Missing outcomes fail closed. */
internal fun NotificationLocal.reconcileWatches(snapshot: SessionSnapshot, now: Long): NotificationLocal {
    var local = pruneWatches(now)
    for (watch in local.watches.filter { it.sessionId == snapshot.session.id }) {
        val outcome = snapshot.promptOutcomes.firstOrNull { it.requestId == watch.requestId } ?: continue
        if (outcome.status !in setOf("completed", "failed", "waiting", "rejected")) continue
        local = local.copy(watches = local.watches.filterNot { it.requestId == watch.requestId })
        if (outcome.status == "rejected") continue
        val key = watchTurnKey(snapshot.session.id, outcome.turnId)
        if (local.deliveredTurns.any { it.id == key } || local.outbox.any { it.key == key }) continue
        val kind = when (outcome.status) { "failed" -> "error"; "waiting" -> "attention"; else -> "answer" }
        local = local.copy(outbox = (local.outbox + WatchAlert(key, snapshot.session.id, outcome.turnId, kind,
            snapshot.session.title, outcome.summary, watch.expiresAt)).takeLast(WATCH_CAP))
    }
    return local
}
internal fun NotificationLocal.acknowledgeWatchAlert(key: String, now: Long) = copy(outbox = outbox.filterNot { it.key == key },
    deliveredTurns = (deliveredTurns.filterNot { it.id == key } + ProcessedNotification(key, now)).takeLast(2048))

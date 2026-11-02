package dev.dshmobile.app.data

import kotlinx.serialization.Serializable

internal const val WATCH_TTL_MS = 24 * 60 * 60 * 1000L
private const val WATCH_CAP = 32
@Serializable internal data class PhoneWatch(val sessionId: String, val requestId: String, val expiresAt: Long, val title: String = "")
@Serializable internal data class WatchAlert(val key: String, val sessionId: String, val turnId: String,
    val kind: String, val title: String, val summary: String? = null, val expiresAt: Long,
    val attempted: Boolean = false, val aliases: List<String> = emptyList())
@Serializable internal data class WatchTurnState(val sessionId: String, val turnId: String,
    val waiting: Boolean, val episode: Int, val expiresAt: Long, val aliases: List<String> = emptyList())

internal fun NotificationLocal.pruneWatches(now: Long) = copy(
    watches = watches.filter { it.expiresAt > now }.takeLast(WATCH_CAP),
    outbox = outbox.filter { it.expiresAt > now }.takeLast(WATCH_CAP),
    watchTurns = watchTurns.filter { it.expiresAt > now }.takeLast(2048),
    deliveredTurns = deliveredTurns.filter { it.at > now - WATCH_TTL_MS }.takeLast(2048))
internal fun NotificationLocal.needsWatchService(now: Long) = enabled && watches.any { it.expiresAt > now }
internal fun NotificationLocal.registerWatch(device: String, session: String, request: String, now: Long, title: String = ""): NotificationLocal {
    if (!enabled) return this
    val current = (if (deviceId == null || deviceId == device) this else NotificationLocal(enabled = enabled)).pruneWatches(now)
    val id = request.lowercase()
    if (current.watches.any { it.requestId == id }) return current
    return current.copy(deviceId = device, watches = (current.watches + PhoneWatch(session, id, now + WATCH_TTL_MS, title)).takeLast(WATCH_CAP))
}
internal fun watchTurnKey(session: String, turn: String) = "${session.length}:$session$turn"
internal fun watchNotificationTag(device: String, session: String) = "$device:phone-chat:$session"

/** Only explicit device-scoped endpoint outcomes settle watches. Unknown is not a rejection.
 * Turn/episode identity is durable, and shared by all phone requests consumed by that turn. */
internal fun NotificationLocal.reconcileWatches(session: String, outcomes: PromptOutcomes, now: Long): NotificationLocal {
    var local = pruneWatches(now)
    val watched = local.watches.filter { it.sessionId == session }.associateBy { it.requestId }
    for (outcome in outcomes.outcomes) {
        val watch = watched[outcome.requestId] ?: continue
        if (outcome.status in setOf("completed", "failed", "rejected"))
            local = local.copy(watches = local.watches.filterNot { it.requestId == watch.requestId })
        // Contract permits omitted turnId even on terminal outcomes. Use the request identity
        // in that case; never drop a proven outcome just because this optional field is absent.
        val turn = outcome.turnId ?: "request:${outcome.requestId}"
        val supplied = listOfNotNull("request:${outcome.requestId}", outcome.turnId?.let { "turn:$it" })
        val linked = local.watchTurns.filter { it.sessionId == session && (it.turnId in supplied || it.aliases.any { alias -> alias in supplied }) }
        val identities = (supplied + linked.flatMap { it.aliases } + linked.map { it.turnId }).distinct()
        val previous = local.watchTurns.filter { it.sessionId == session && it.turnId in identities }
        val waiting = outcome.status == "waiting"
        val episode = (previous.maxOfOrNull { it.episode } ?: 0) + if (waiting && previous.none { it.waiting }) 1 else 0
        // Keep both aliases in sync. Unknown supplies no evidence of leaving waiting.
        if (outcome.status != "unknown") local = local.copy(watchTurns = (local.watchTurns.filterNot {
            it.sessionId == session && it.turnId in identities
        } + identities.map { WatchTurnState(session, it, waiting, episode,
            maxOf(watch.expiresAt, previous.maxOfOrNull { prior -> prior.expiresAt } ?: 0), identities) }).takeLast(2048))
        if (outcome.status !in setOf("completed", "failed", "waiting")) continue
        val keys = identities.map { identity ->
            val base = watchTurnKey(session, identity)
            if (waiting) "$base:waiting:$episode" else "$base:final"
        }
        val delivered = local.deliveredTurns.firstOrNull { it.id in keys || it.aliases.any { alias -> alias in keys } }
        val queued = local.outbox.firstOrNull { it.key in keys || it.aliases.any { alias -> alias in keys } }
        // Learning turnId also teaches the existing alert its new alias, including before ack.
        if (delivered != null) {
            local = local.copy(deliveredTurns = local.deliveredTurns.map { if (it == delivered)
                it.copy(aliases = (it.aliases + keys).distinct()) else it })
            continue
        }
        if (queued != null) {
            local = local.copy(outbox = local.outbox.map { if (it == queued)
                it.copy(aliases = (it.aliases + keys).distinct()) else it })
            continue
        }
        val kind = when (outcome.status) { "failed" -> "error"; "waiting" -> "attention"; else -> "answer" }
        val key = keys.last()
        local = local.copy(outbox = (local.outbox + WatchAlert(key, session, turn, kind, watch.title, outcome.summary, watch.expiresAt,
            aliases = keys)).takeLast(WATCH_CAP))
    }
    return local
}
internal fun NotificationLocal.acknowledgeWatchAlert(key: String, now: Long) = copy(outbox = outbox.filterNot { it.key == key },
    deliveredTurns = (deliveredTurns.filterNot { it.id == key } + ProcessedNotification(key, now,
        outbox.firstOrNull { it.key == key }?.aliases.orEmpty())).takeLast(2048))

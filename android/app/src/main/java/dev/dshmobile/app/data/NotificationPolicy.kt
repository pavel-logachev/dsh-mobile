package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.Workspace
import kotlinx.serialization.Serializable

@Serializable internal data class ProjectNotificationSetting(val workspaceId: String, val enabled: Boolean)
@Serializable internal data class ChatNotificationSetting(val sessionId: String, val enabled: Boolean)
@Serializable internal data class NotificationSettings(val revision: Long = 0, val enabled: Boolean = false,
    val projects: List<ProjectNotificationSetting> = emptyList(), val chats: List<ChatNotificationSetting> = emptyList()) {
    fun allows(e: NotificationEvent) = enabled && (chats.find { it.sessionId == e.sessionId }?.enabled
        ?: projects.find { it.workspaceId == e.workspaceId }?.enabled ?: true)
}
@Serializable internal data class NotificationSettingsBody(val expectedRevision: Long, val enabled: Boolean,
    val projects: List<ProjectNotificationSetting>, val chats: List<ChatNotificationSetting>)
@Serializable internal data class NotificationEvent(val version: Int, val eventId: String, val sequence: Long,
    val occurredAt: Long, val expiresAt: Long, val workspaceId: String, val sessionId: String,
    val kind: String, val sourceSeq: Long, val turn: Long? = null, val attentionId: String? = null)
@Serializable internal data class PendingAttention(val sessionId: String, val workspaceId: String, val attentionId: String)
@Serializable internal data class NotificationPage(val version: Int, val epoch: String, val items: List<NotificationEvent>,
    val nextCursor: String, val hasMore: Boolean, val resetRequired: Boolean, val legacyState: String = "",
    val pending: List<PendingAttention> = emptyList()) {
    fun checked(): NotificationPage {
        if (version != 1 || !validId(epoch) || nextCursor.length !in 1..256 || !nextCursor.all { it.isLetterOrDigit() || it in "_-" } ||
            items.size > 100 || pending.size > 1000 ||
            items.any { it.version != 1 || !validId(it.eventId) || !validId(it.sessionId) || !validId(it.workspaceId) || it.sequence < 0 || it.sourceSeq < -1 || it.occurredAt < 0 || it.expiresAt < 0 || it.kind !in setOf("answer-finished", "attention-needed", "attention-cleared") || (it.kind != "answer-finished" && !validId(it.attentionId.orEmpty())) } ||
            items.zipWithNext().any { it.first.sequence >= it.second.sequence } || pending.any { !validId(it.sessionId) || !validId(it.workspaceId) || !validId(it.attentionId) }) throw MobileFailure("invalid_response")
        return this
    }
}
@Serializable internal data class ProcessedNotification(val id: String, val at: Long)
@Serializable internal data class NotificationLocal(val enabled: Boolean = true, val deviceId: String? = null,
    val watches: List<PhoneWatch> = emptyList(), val outbox: List<WatchAlert> = emptyList(),
    val deliveredTurns: List<ProcessedNotification> = emptyList(),
    val settings: NotificationSettings = NotificationSettings(), val dirty: Boolean = false,
    val epoch: String? = null, val cursor: String? = null, val lastSequence: Long = -1,
    val processed: List<ProcessedNotification> = emptyList(), val attention: Map<String, String> = emptyMap(),
    val answerMode: AlertMode = AlertMode.SOUND, val attentionMode: AlertMode = AlertMode.SOUND,
    val channelModesMigrated: Boolean = false) {
    fun applyPage(page: NotificationPage, now: Long): AppliedNotifications {
        val reset = page.resetRequired || epoch != page.epoch
        var seen = (if (reset) emptyList() else processed).filter { it.at > now - 7 * 86400000L }
        val pending = (if (reset) page.pending.associate { it.sessionId to it.attentionId } else attention).toMutableMap()
        val display = mutableListOf<NotificationEvent>()
        var sequence = if (reset) -1 else lastSequence
        for (event in page.items) {
            if (event.sequence < sequence) throw MobileFailure("invalid_response")
            sequence = event.sequence
            if (seen.any { it.id == event.eventId }) continue
            seen = (seen + ProcessedNotification(event.eventId, now)).takeLast(2000)
            when (event.kind) {
                "attention-needed" -> pending[event.sessionId] = event.attentionId!!
                "attention-cleared" -> if (pending[event.sessionId] == event.attentionId) pending.remove(event.sessionId)
            }
            if (event.kind == "attention-cleared" || event.expiresAt > now) display += event
        }
        return AppliedNotifications(copy(epoch = page.epoch, cursor = page.nextCursor, lastSequence = sequence,
            processed = seen, attention = pending.toMap()), display.filter { it.kind != "attention-needed" || pending[it.sessionId] == it.attentionId }
                .groupBy { it.sessionId to it.kind }.values.map { it.last() }
                .filter { it.kind != "answer-finished" || it.sessionId !in pending }, reset)
    }
}
internal data class AppliedNotifications(val state: NotificationLocal, val display: List<NotificationEvent>, val reset: Boolean)
internal fun reconcileNotificationSettings(local: NotificationSettings, remote: NotificationSettings, dirty: Boolean) =
    if (dirty) local.copy(revision = remote.revision) else remote
internal fun notificationRetryable(key: String) = key !in setOf("tls_failed", "unauthorized", "revoked", "forbidden", "invalid_response", "incompatible_protocol", "unsupported", "not_paired", "storage_failed", "certificate_invalid")
internal fun notificationRetryDelay(attempt: Int, retryAfterMs: Long, jitter: Double): Long =
    minOf(5000L, maxOf(retryAfterMs, (minOf(5000L, 1000L shl attempt.coerceIn(0, 6)) * jitter.coerceIn(0.5, 1.0)).toLong()))
internal fun notificationNames(event: NotificationEvent, workspaces: List<Workspace>, sessions: List<SessionSummary>): String? {
    val chat = sessions.find { it.id == event.sessionId && it.workspaceId == event.workspaceId } ?: return null
    val title = chat.title.trim().takeIf { it.isNotEmpty() } ?: return null
    val project = workspaces.find { it.id == event.workspaceId }?.name?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(project?.take(128), title.take(128)).joinToString(" · ")
}

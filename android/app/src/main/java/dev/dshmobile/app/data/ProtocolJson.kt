package dev.dshmobile.app.data

import dev.dshmobile.app.model.AttachmentRef
import dev.dshmobile.app.model.PhoneRules
import dev.dshmobile.app.model.MobileCapabilities
import dev.dshmobile.app.model.Preset
import dev.dshmobile.app.model.SessionSnapshot
import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

internal val mobileJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = false
    explicitNulls = true
}

@Serializable internal class PairingResponse(
    val deviceId: String, val deviceToken: String, val hostName: String, val protocolVersion: Int,
    val relayAccess: RelayAccess? = null,
)
@Serializable internal data class CapabilitiesResponse(val protocolVersion: Int, val hostName: String, val upstreamVersion: String, val capabilities: MobileCapabilities, val hostVersion: String? = null)
@Serializable internal class WorkspaceResponse(val items: List<Workspace>)
@Serializable internal class PresetResponse(val items: List<Preset>)
@Serializable internal class SessionResponse(val items: List<SessionSummary>, val nextCursor: String?)
@Serializable internal class ErrorEnvelope(val error: WireError)
@Serializable internal class WireError(val code: String, val message: String, val retryable: Boolean)
@Serializable internal data class ReceiptError(val code: String, val message: String)
@Serializable internal data class ReceiptResult(val sessionId: String? = null)
@Serializable internal data class CommandReceipt(
    val requestId: String, val status: String, val updatedAt: Long,
    val result: ReceiptResult? = null, val error: ReceiptError? = null,
)
@Serializable internal class PairingBody(val pairingToken: String, val deviceName: String)
@Serializable internal class CreateBody(val requestId: String, val workspaceId: String, val presetId: String? = null)
@Serializable internal class MessageBody(val requestId: String, val text: String, val delivery: String? = null, val uploadIds: List<String>? = null)
@Serializable internal class QueueBody(val requestId: String, val itemId: String, val action: String)
@Serializable internal class TitleBody(val requestId: String, val title: String)
@Serializable internal class ModelBody(val requestId: String, val provider: String, val model: String, val reasoningEffort: String? = null)
@Serializable internal class AnswerBody(val requestId: String, val callId: String, val answer: dev.dshmobile.app.model.QuestionAnswer)
@Serializable internal class SkipBody(val requestId: String, val callId: String, val skip: Boolean)
@Serializable internal class UploadReceipt(val uploadId: String, val expiresAt: Long, val attachment: dev.dshmobile.app.model.AttachmentRef)
@Serializable internal class CancelBody(val requestId: String, val expectedCursor: Long)

internal inline fun <reified T> decodeWire(text: String): T = try {
    mobileJson.decodeFromString<T>(text)
} catch (_: SerializationException) {
    throw MobileFailure("invalid_response")
} catch (_: IllegalArgumentException) {
    throw MobileFailure("invalid_response")
}

internal fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 512 && id.none { it.isISOControl() }
internal fun validRequestId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
internal fun SessionSummary.checked(): SessionSummary {
    if (!validId(id) || !validId(workspaceId) || updatedAt < 0 || title.length > 4096) throw MobileFailure("invalid_response")
    return if (runningSubagents < 0) copy(runningSubagents = 0) else this
}
internal fun SessionSnapshot.checked(expectedId: String): SessionSnapshot {
    session.checked()
    if (promptOutcomes.size > 1024 || promptOutcomes.map { it.requestId }.distinct().size != promptOutcomes.size ||
        promptOutcomes.any { !validRequestId(it.requestId) || !validId(it.turnId) ||
            it.status !in setOf("queued", "running", "waiting", "completed", "failed", "rejected") ||
            (it.summary != null && (it.summary.toByteArray().size > 2048 || it.summary.any { c -> c.isISOControl() })) })
        throw MobileFailure("invalid_response")
    if (session.id != expectedId || cursor < -1 || messages.size > 100 || notice.orEmpty().length > 4096 ||
        (failure != null && (failure.message.toByteArray().size > 2048 || failure.message.any { it.isISOControl() } ||
            (failure.code != null && (failure.code.length > 64 || !Regex("[A-Za-z0-9_.:-]{1,64}").matches(failure.code))))) ||
        messages.map { it.id }.distinct().size != messages.size || messages.any {
            !validId(it.id) || it.createdAt < 0 ||
                (it.requestId != null && !validRequestId(it.requestId))
        }) throw MobileFailure("invalid_response")
    fun validAttachment(a: AttachmentRef) = validId(a.id) && a.name.toByteArray().size <= 256 &&
        a.name.none { it.isISOControl() || it == '/' || it == '\\' } && a.size in 0..9_007_199_254_740_991L && a.mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/octet-stream", "application/pdf", "text/plain") && a.kind in setOf("image", "file")
    fun safeAttachments(items: List<AttachmentRef>) = items.take(100).filter(::validAttachment)
    if (pendingQueue.size > 100 || pendingQueue.map { it.id }.distinct().size != pendingQueue.size ||
        pendingQueue.any { !validId(it.id) || it.delivery !in setOf("queue", "steer") || it.text.toByteArray().size > 32768 } ||
        pendingQuestions.size > 20 || pendingQuestions.map { it.callId }.distinct().size != pendingQuestions.size || pendingQuestions.any { p ->
            !validId(p.callId) || p.state !in setOf("open", "continued") || p.questions.isEmpty() || p.questions.size > 20 || p.questions.map { it.id }.distinct().size != p.questions.size ||
                p.questions.any { q -> !validId(q.id) || q.question.toByteArray().size > 32768 || q.options.size > 100 || q.options.map { it.label }.distinct().size != q.options.size ||
                    q.options.any { it.label.isBlank() || it.label.toByteArray().size > 4096 || (it.description?.toByteArray()?.size ?: 0) > 32768 } }
        }) throw MobileFailure("invalid_response")
    fun validModel(value: dev.dshmobile.app.model.SessionModel?) = value == null || (validId(value.provider) && validId(value.id) && (value.reasoningEffort == null || validId(value.reasoningEffort)))
    if (!validModel(model) || !validModel(session.model) || messages.any { it.delivery != null && it.delivery !in setOf("queue", "steer") } ||
        resolvedQuestions.orEmpty().size > 20 || resolvedQuestions.orEmpty().map { it.callId }.distinct().size != resolvedQuestions.orEmpty().size || resolvedQuestions.orEmpty().any { r ->
            !validId(r.callId) || r.outcome !in setOf("answered", "skipped") || r.answers.size > 20 || r.answers.any { a -> !validId(a.id) || a.selected.size > 100 || a.selected.any { it.toByteArray().size > 4096 } || (a.custom?.toByteArray()?.size ?: 0) > 32768 }
        }) throw MobileFailure("invalid_response")
    // Unknown role/activity are NOT interpreted as idle/user; disable mutations for this snapshot.
    val unknown = activity !in setOf("idle", "running", "waiting", "unknown") ||
        messages.any { it.role !in setOf("user", "assistant", "system") }
    return copy(
        session = session.checked().copy(canExecute = session.canExecute && !unknown,
            runningSubagents = maxOf(session.runningSubagents.coerceAtLeast(0), runningSubagents.coerceAtLeast(0))),
        runningSubagents = maxOf(session.runningSubagents.coerceAtLeast(0), runningSubagents.coerceAtLeast(0)),
        messages = messages.map { message -> (if (message.role in setOf("user", "assistant", "system")) message else message.copy(role = "system", text = "")).copy(attachments = safeAttachments(message.attachments)) },
        pendingQueue = pendingQueue.map { it.copy(attachments = safeAttachments(it.attachments)) },
        activity = if (unknown) "unknown" else activity,
        notice = if (unknown) "unsupported" else notice,
        activityDetail = activityDetail?.takeIf { !unknown && activity in setOf("running", "waiting") }?.copy(
            turnStartedAt = activityDetail.turnStartedAt?.takeIf { it >= 0 },
            tool = activityDetail.tool?.takeIf { Regex("[A-Za-z0-9_.:/-]{1,128}").matches(it) },
        ),
    )
}
internal fun CommandReceipt.checked(expectedId: String): CommandReceipt {
    if (requestId != expectedId || !validRequestId(requestId) || updatedAt < 0 ||
        (result?.sessionId != null && !validId(result.sessionId))) throw MobileFailure("invalid_response")
    return if (status in setOf("pending", "accepted", "rejected", "uncertain")) this else copy(status = "uncertain")
}

internal fun hostErrorKey(code: String): String = when (code) {
    "unauthorized" -> "unauthorized"
    "revoked" -> "revoked"
    "forbidden" -> "forbidden"
    "rate_limited", "staging_full" -> "rate_limited"
    "conflict", "request_conflict", "request_id_conflict", "idempotency_conflict" -> "request_conflict"
    "unsupported", "unsupported_operation" -> "unsupported"
    "invalid_text", "text_too_large" -> "invalid_text"
    "recovery_hold" -> "recovery_hold"
    "unavailable" -> "network_unavailable"
    else -> "host_error"
}

package dev.dshmobile.app.data

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
@Serializable internal data class CapabilitiesResponse(val protocolVersion: Int, val hostName: String, val upstreamVersion: String, val capabilities: MobileCapabilities)
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
@Serializable internal class MessageBody(val requestId: String, val text: String)
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
    return this
}
internal fun SessionSnapshot.checked(expectedId: String): SessionSnapshot {
    session.checked()
    if (session.id != expectedId || cursor < -1 || messages.size > 100 || notice.orEmpty().length > 4096 ||
        messages.map { it.id }.distinct().size != messages.size || messages.any {
            !validId(it.id) || it.createdAt < 0 ||
                (it.requestId != null && !validRequestId(it.requestId))
        }) throw MobileFailure("invalid_response")
    // Unknown role/activity are NOT interpreted as idle/user; disable mutations for this snapshot.
    val unknown = activity !in setOf("idle", "running", "waiting", "unknown") ||
        messages.any { it.role !in setOf("user", "assistant", "system") }
    return copy(
        session = if (unknown) session.copy(canExecute = false) else session,
        messages = messages.map { if (it.role in setOf("user", "assistant", "system")) it else it.copy(role = "system", text = "") },
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
    "rate_limited" -> "rate_limited"
    "conflict", "request_conflict", "request_id_conflict", "idempotency_conflict" -> "request_conflict"
    "unsupported", "unsupported_operation" -> "unsupported"
    "invalid_text", "text_too_large" -> "invalid_text"
    "unavailable" -> "network_unavailable"
    else -> "host_error"
}

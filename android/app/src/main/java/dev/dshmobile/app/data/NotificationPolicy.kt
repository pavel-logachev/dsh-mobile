package dev.dshmobile.app.data

import kotlinx.serialization.Serializable

@Serializable internal data class ProcessedNotification(val id: String, val at: Long, val aliases: List<String> = emptyList())
/** Local-only policy and encrypted phone outcome delivery state. Old feed fields are ignored
 * by the additive JSON reader during upgrade; no host preference/cursor is retained. */
@Serializable internal data class NotificationLocal(val enabled: Boolean = true, val deviceId: String? = null,
    val watches: List<PhoneWatch> = emptyList(), val outbox: List<WatchAlert> = emptyList(),
    val deliveredTurns: List<ProcessedNotification> = emptyList(),
    val watchTurns: List<WatchTurnState> = emptyList())

internal fun notificationRetryable(key: String) = key !in setOf("tls_failed", "unauthorized", "revoked", "forbidden", "invalid_response", "incompatible_protocol", "unsupported", "not_paired", "storage_failed", "certificate_invalid")
internal fun notificationRetryDelay(attempt: Int, retryAfterMs: Long, jitter: Double): Long =
    minOf(5000L, maxOf(retryAfterMs, (minOf(5000L, 1000L shl attempt.coerceIn(0, 6)) * jitter.coerceIn(0.5, 1.0)).toLong()))

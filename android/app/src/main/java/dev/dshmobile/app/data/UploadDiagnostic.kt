package dev.dshmobile.app.data

import android.util.Log

/** Release-safe upload evidence. Never log exceptions, URIs, names, IDs, URLs or payloads. */
internal object UploadDiagnostic {
    private val stages = setOf("start", "source_open", "source_read", "transport", "response", "receipt", "editor")
    private val codes = setOf("ok", "unknown", "timeout", "tls", "io", "source_denied", "source_missing", "short_upload", "long_upload",
        "network_unavailable", "tls_failed", "invalid_response", "forbidden", "invalid_selection", "rate_limited", "staging_full",
        "unauthorized", "revoked", "not_found", "invalid_request", "unsupported_media_type", "payload_too_large", "unavailable", "internal_error", "host_error", "storage_failed")
    fun code(failure: Exception): String = when (failure) {
        is MobileFailure -> failure.key.takeIf { it in codes } ?: "unknown"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is SecurityException -> "source_denied"
        is java.io.FileNotFoundException -> "source_missing"
        is java.io.IOException -> "io"
        else -> "unknown"
    }
    fun line(stage: String, code: String, relay: Boolean? = null, status: Int? = null, sent: Long = 0, size: Long = 0, elapsedMs: Long = 0): String =
        "stage=${stage.takeIf { it in stages } ?: "unknown"} code=${code.takeIf { it in codes } ?: "unknown"} transport=${when (relay) { true -> "relay"; false -> "direct"; null -> "unknown" }} http=${status?.takeIf { it in 100..599 } ?: 0} sent=${sent.coerceAtLeast(0)} size=${size.coerceAtLeast(0)} elapsedMs=${elapsedMs.coerceAtLeast(0)}"
    fun record(stage: String, code: String, relay: Boolean? = null, status: Int? = null, sent: Long = 0, size: Long = 0, elapsedMs: Long = 0) {
        runCatching { Log.i("DshMobileUpload", line(stage, code, relay, status, sent, size, elapsedMs)) }
    }
}

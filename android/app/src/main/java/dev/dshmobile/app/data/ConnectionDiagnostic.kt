package dev.dshmobile.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

internal data class ConnectionNetwork(val validated: Boolean? = null, val vpn: Boolean? = null, val blocked: Boolean? = null)

/** Release-enabled metadata only. Fixed vocabulary, no messages/causes/identities, 120 lines/minute.
 * No disk state, retained events, endpoint references or throwable retention. */
internal class ConnectionDiagnostic(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sink: (String) -> Unit = { Log.i("DshMobileConn", it); Unit },
    private val foreground: () -> Boolean = { false },
    private val network: () -> ConnectionNetwork = { ConnectionNetwork() },
) {
    private val attempts = AtomicInteger()
    private val origin = clock()
    private var window = origin
    private var emitted = 0
    @Volatile var blocked: Boolean? = null
    fun begin() { attempts.updateAndGet { if (it == Int.MAX_VALUE) 1 else it + 1 }; record("connect", "start") }
    @Synchronized fun record(stage: String, code: String = "ok", failure: Throwable? = null, startedAt: Long = origin) {
        val instant = clock()
        if (instant - window >= 60_000 || instant < window) { window = instant; emitted = 0 }
        if (emitted >= 120) return
        emitted++
        runCatching {
            val flags = network().copy(blocked = blocked)
            sink(line(stage, if (failure != null) code(failure) else code, failure, attempts.get(), instant - startedAt, foreground(), flags))
        }
    }
    companion object {
        private val stages = setOf("restore", "connect", "relay-open", "snapshot", "sse-open", "sse-fail", "reconnect-scheduled", "manual-refresh", "network-callback", "foreground", "http", "list-tick")
        private val codes = setOf("ok", "start", "fail", "unknown", "timeout", "tls", "io", "dns", "cancelled", "closed", "available", "capabilities", "blocked", "unblocked", "lost", "queued", "entered", "background", "foreground", "network_unavailable", "tls_failed", "invalid_response", "host_error", "rate_limited", "unauthorized", "revoked", "forbidden", "chat_unavailable", "storage_failed", "incompatible_protocol")
        fun code(failure: Throwable): String = when (failure) {
            is MobileFailure -> failure.key.takeIf { it in codes } ?: "unknown"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
            is javax.net.ssl.SSLException -> "tls"
            is java.net.UnknownHostException -> "dns"
            is kotlinx.coroutines.CancellationException -> "cancelled"
            is java.io.IOException -> "io"
            else -> "unknown"
        }
        // Never use javaClass.simpleName: an arbitrary class name can contain private text.
        private fun exception(failure: Throwable?): String = when (failure) {
            null -> "none"
            is MobileFailure -> "MobileFailure"
            is java.net.SocketTimeoutException -> "SocketTimeoutException"
            is javax.net.ssl.SSLHandshakeException -> "SSLHandshakeException"
            is javax.net.ssl.SSLException -> "SSLException"
            is java.net.UnknownHostException -> "UnknownHostException"
            is java.net.ConnectException -> "ConnectException"
            is java.net.SocketException -> "SocketException"
            is java.io.InterruptedIOException -> "InterruptedIOException"
            is java.io.EOFException -> "EOFException"
            is java.io.IOException -> "IOException"
            is kotlinx.coroutines.CancellationException -> "CancellationException"
            else -> "other"
        }
        fun line(stage: String, code: String, failure: Throwable?, attempt: Int, elapsedMs: Long, foreground: Boolean, network: ConnectionNetwork): String =
            "stage=${stage.takeIf { it in stages } ?: "unknown"} code=${code.takeIf { it in codes } ?: "unknown"} exception=${exception(failure)} attempt=${attempt.coerceAtLeast(0)} elapsedMs=${elapsedMs.coerceAtLeast(0)} foreground=$foreground validated=${network.validated ?: "unknown"} vpn=${network.vpn ?: "unknown"} blocked=${network.blocked ?: "unknown"}"
    }
}

internal fun connectionNetwork(context: Context): () -> ConnectionNetwork {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        ConnectionNetwork(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
}

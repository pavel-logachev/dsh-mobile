package dev.dshmobile.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Relay capabilities are distinct from host pairing secrets and device bearers. */
@Serializable
data class RelaySettings(
    val url: String, val routeId: String, val accessId: String, val accessToken: String, val expiresAt: Long,
) {
    override fun toString() = "RelaySettings(redacted)"
    internal fun withAccess(access: RelayAccess) = copy(accessId = access.accessId, accessToken = access.accessToken, expiresAt = access.expiresAt)
}

@Serializable
data class RelayAccess(val accessId: String, val accessToken: String, val expiresAt: Long) {
    override fun toString() = "RelayAccess(redacted)"
}

internal object RelayPolicy {
    private val routePattern = Regex("[0-9a-f]{32}")
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    private val pathSegment = Regex("[A-Za-z0-9_-][A-Za-z0-9._~-]{0,127}")

    fun invitation(value: JsonObject, now: Long, debug: Boolean): RelaySettings {
        val expiry = number(value, "expiresAt")
        if (expiry <= now || expiry - now > 15 * 60_000L) throw MobileFailure("invitation_invalid")
        val relay = value["relay"] as? JsonObject ?: throw MobileFailure("invitation_invalid")
        return RelaySettings(string(relay, "url"), string(relay, "routeId"), string(relay, "accessId"), string(relay, "accessToken"), expiry)
            .also { validateSettings(it, debug) }
    }

    fun validate(endpoint: HostEndpoint, debug: Boolean) {
        val relay = endpoint.relay ?: return
        validateSettings(relay, debug)
        if (endpoint.baseUrl != "https://h-${relay.routeId}.dsh.invalid" || endpoint.certificatePem == null || endpoint.pinSha256 == null)
            throw MobileFailure("invitation_invalid")
        val certificate = SecureTransport.certificate(endpoint.certificatePem)
        if (certificate.subjectAlternativeNames?.none { it.size == 2 && it[0] == 2 && it[1] == "h-${relay.routeId}.dsh.invalid" } != false)
            throw MobileFailure("certificate_invalid")
        if (okhttp3.CertificatePinner.pin(certificate) != endpoint.pinSha256) throw MobileFailure("certificate_invalid")
    }

    fun validateSettings(relay: RelaySettings, debug: Boolean) {
        if (!routePattern.matches(relay.routeId) || !canonicalId(relay.accessId) || !validToken(relay.accessToken) || relay.expiresAt <= 0)
            throw MobileFailure("invitation_invalid")
        val uri = try { URI(relay.url) } catch (_: Exception) { throw MobileFailure("invitation_invalid") }
        if (relay.url.length > 2048 || relay.url.any { it.isWhitespace() || it.isISOControl() } || '\\' in relay.url ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.host == null || uri.rawAuthority?.contains('@') == true ||
            uri.port !in -1..65535 || uri.port == 0 || '%' in relay.url) throw MobileFailure("invitation_invalid")
        if (uri.scheme != "wss" && !(debug && uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost")))
            throw MobileFailure("transport_not_allowed")
        val path = uri.rawPath.orEmpty()
        if (path.length > 512 || (path.isNotEmpty() && (!path.startsWith('/') || path.endsWith('/') || path.split('/').drop(1).any {
                it == "." || it == ".." || !pathSegment.matches(it)
            }))) throw MobileFailure("invitation_invalid")
        val httpUrl = relay.url.replaceFirst(if (uri.scheme == "wss") "wss://" else "ws://", if (uri.scheme == "wss") "https://" else "http://").toHttpUrlOrNull()
            ?: throw MobileFailure("invitation_invalid")
        // Reject normalization, including uppercase authorities/default ports and path tricks.
        val canonical = httpUrl.toString().removeSuffix("/").replaceFirst(if (uri.scheme == "wss") "https://" else "http://", if (uri.scheme == "wss") "wss://" else "ws://")
        if (canonical != relay.url) throw MobileFailure("invitation_invalid")
    }

    fun checkedAccess(access: RelayAccess, now: Long): RelayAccess {
        if (!canonicalId(access.accessId) || !validToken(access.accessToken) || access.expiresAt <= now || access.expiresAt - now > 366L * 24 * 60 * 60_000)
            throw MobileFailure("invalid_response")
        return access
    }

    fun requireCurrent(relay: RelaySettings, now: Long = System.currentTimeMillis()) {
        if (relay.expiresAt <= now) throw MobileFailure("network_unavailable")
    }

    private fun canonicalId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun validToken(value: String): Boolean = tokenPattern.matches(value) && runCatching {
        val bytes = Base64.getUrlDecoder().decode(value)
        bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
    }.getOrDefault(false)
    private fun string(value: JsonObject, name: String): String = (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw MobileFailure("invitation_invalid")
    private fun number(value: JsonObject, name: String): Long = (value[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        ?: throw MobileFailure("invitation_invalid")
}

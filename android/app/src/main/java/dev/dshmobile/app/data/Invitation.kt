package dev.dshmobile.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Base64

/** Stable, non-sensitive UI key only. Never wrap raw server or TLS exception text. */
class MobileFailure(val key: String) : Exception(key)

@Serializable
data class HostEndpoint(
    val baseUrl: String, val pinSha256: String? = null, val certificatePem: String? = null,
    val relay: RelaySettings? = null,
) {
    override fun toString(): String = "HostEndpoint(redacted, relay=${relay != null})"
}

internal object EndpointPolicy {
    fun validate(endpoint: HostEndpoint, debug: Boolean): HostEndpoint {
        val value = endpoint.baseUrl
        if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) throw MobileFailure("invitation_invalid")
        val uri = try { URI(value) } catch (_: Exception) { throw MobileFailure("invitation_invalid") }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in setOf(null, "", "/") || uri.host == null || uri.rawAuthority?.contains('@') == true) throw MobileFailure("invitation_invalid")
        val url = value.toHttpUrlOrNull() ?: throw MobileFailure("invitation_invalid")
        if (url.scheme == "http") {
            if (!debug || uri.host !in setOf("localhost", "127.0.0.1") || uri.rawAuthority?.contains('%') == true) throw MobileFailure("transport_not_allowed")
            if (endpoint.certificatePem != null || endpoint.pinSha256 != null) throw MobileFailure("invitation_invalid")
        } else if (url.scheme == "https") {
            val pin = endpoint.pinSha256 ?: throw MobileFailure("pin_required")
            val decoded = try { Base64.getDecoder().decode(pin.removePrefix("sha256/")) } catch (_: Exception) { throw MobileFailure("pin_required") }
            if (!pin.startsWith("sha256/") || decoded.size != 32 || "sha256/" + Base64.getEncoder().encodeToString(decoded) != pin) throw MobileFailure("pin_required")
            endpoint.certificatePem?.let { SecureTransport.certificate(it) }
        } else throw MobileFailure("transport_not_allowed")
        endpoint.relay?.let { RelayPolicy.validate(endpoint, debug) }
        return HostEndpoint(url.toString().removeSuffix("/"), endpoint.pinSha256, endpoint.certificatePem, endpoint.relay)
    }
}

/** Linear guard before the recursive JSON parser; invitation schemas need at most two levels. */
private fun requireShallowJson(json: String) {
    var depth = 0
    var inString = false
    var escaped = false
    for (character in json) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else when (character) {
            '"' -> inString = true
            '{', '[' -> if (++depth > 32) throw MobileFailure("invitation_invalid")
            '}', ']' -> if (--depth < 0) throw MobileFailure("invitation_invalid")
        }
    }
    if (inString || depth != 0) throw MobileFailure("invitation_invalid")
}

/** Invitation is intentionally not a data class: its generated toString must never leak a secret. */
class Invitation(val endpoint: HostEndpoint, val pairingToken: String) {
    companion object {
        fun parse(json: String, debug: Boolean, now: Long = System.currentTimeMillis()): Invitation {
            if (json.length > 64 * 1024 || json.toByteArray(Charsets.UTF_8).size > 64 * 1024) throw MobileFailure("invitation_invalid")
            requireShallowJson(json)
            val objectValue = try { mobileJson.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null }
                ?: throw MobileFailure("invitation_invalid")
            val version = objectValue["version"] as? JsonPrimitive
            if (version == null || version.isString || version.intOrNull !in setOf(1, 2)) throw MobileFailure("invitation_invalid")
            fun string(key: String, optional: Boolean = false): String? {
                val value = objectValue[key]
                if (value == null && optional) return null
                val primitive = value as? JsonPrimitive ?: throw MobileFailure("invitation_invalid")
                if (!primitive.isString) throw MobileFailure("invitation_invalid")
                return primitive.content
            }
            val token = string("pairingToken")!!
            if (!safeToken(token)) throw MobileFailure("invitation_invalid")
            val relay = if (version.intOrNull == 2) RelayPolicy.invitation(objectValue, now, debug)
                else { if (objectValue["relay"] != null || objectValue["expiresAt"] != null) throw MobileFailure("invitation_invalid"); null }
            val endpoint = EndpointPolicy.validate(HostEndpoint(string("baseUrl")!!, string("pinSha256", true), string("certificatePem", true), relay), debug)
            return Invitation(endpoint, token)
        }
    }
}

package dev.dshmobile.app.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class RelayInvitationTest {
    private val route = "0123456789abcdef0123456789abcdef"
    private val now = System.currentTimeMillis()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("h-$route.dsh.invalid").build()
    private fun fixture(): JsonObject = buildJsonObject {
        put("version", 2); put("baseUrl", "https://h-$route.dsh.invalid")
        put("pinSha256", CertificatePinner.pin(cert.certificate)); put("certificatePem", cert.certificatePem())
        put("pairingToken", "synthetic-pairing-secret"); put("expiresAt", now + 600_000)
        put("relay", buildJsonObject { put("url", "wss://relay.example/prefix"); put("routeId", route)
            put("accessId", "a172cf3d-40e0-43e6-93c4-b27bdb3b8877"); put("accessToken", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") })
    }
    @Test fun `remote policy rejects expired invitations wrong identities and normalization tricks`() {
        val value = fixture()
        for (change in listOf("expiresAt" to JsonPrimitive(now), "expiresAt" to JsonPrimitive(now + 900_001),
            "baseUrl" to JsonPrimitive("https://h-ffffffffffffffffffffffffffffffff.dsh.invalid"),
            "baseUrl" to JsonPrimitive("https://h-$route.dsh.invalid:443"),
            "pinSha256" to JsonPrimitive("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            "certificatePem" to JsonPrimitive(HeldCertificate.Builder().addSubjectAlternativeName("wrong.invalid").build().certificatePem()))) {
            assertThrows(MobileFailure::class.java) { Invitation.parse(JsonObject(value + change).toString(), false, now) }
        }
        val relay = value["relay"] as JsonObject
        for (url in listOf("ws://relay.example/prefix", "wss://RELAY.example/prefix", "wss://relay.example:443", "wss://u:p@relay.example",
            "wss://relay.example/prefix/", "wss://relay.example/prefix/../x", "wss://relay.example/prefix/%2E", "wss://relay.example?secret=x", "wss://relay.example#x")) {
            val candidate = JsonObject(value + ("relay" to JsonObject(relay + ("url" to JsonPrimitive(url)))))
            assertThrows(MobileFailure::class.java) { Invitation.parse(candidate.toString(), false, now) }
        }
        val debug = JsonObject(value + ("relay" to JsonObject(relay + ("url" to JsonPrimitive("ws://127.0.0.1:9443")))))
        assertNotNull(Invitation.parse(debug.toString(), true, now).endpoint.relay)
        assertThrows(MobileFailure::class.java) { Invitation.parse(debug.toString(), false, now) }
    }
    @Test fun `relay state serializes capabilities but all generated diagnostic strings redact secrets`() {
        val parsed = Invitation.parse(fixture().toString(), false, now)
        val state = StoredState(host = PairedHost(parsed.endpoint, "synthetic-device", "synthetic-device-secret", "Synthetic"))
        val encoded = mobileJson.encodeToString(state)
        val restored = mobileJson.decodeFromString<StoredState>(encoded)
        assertEquals(parsed.endpoint, restored.host!!.endpoint)
        assertFalse(parsed.endpoint.toString().contains("AAAAAAAA"))
        assertFalse(parsed.endpoint.relay.toString().contains("AAAAAAAA"))
        assertFalse(parsed.toString().contains("synthetic-pairing-secret"))
        assertEquals(parsed.endpoint.relay!!.expiresAt, restored.host!!.endpoint.relay!!.expiresAt)
    }

    @Test fun `remote invitation retains a logical HTTPS host and separate relay identity`() {
        val route = "0123456789abcdef0123456789abcdef"
        val host = "h-$route.dsh.invalid"
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        val invitation = buildJsonObject {
            put("version", 2)
            put("baseUrl", "https://$host")
            put("pinSha256", CertificatePinner.pin(cert.certificate))
            put("certificatePem", cert.certificatePem())
            put("pairingToken", "synthetic-pairing-secret")
            put("expiresAt", System.currentTimeMillis() + 600_000)
            put("relay", buildJsonObject {
                put("url", "wss://relay.example/mobile-prefix")
                put("routeId", route)
                put("accessId", "a172cf3d-40e0-43e6-93c4-b27bdb3b8877")
                put("accessToken", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            })
        }.toString()
        assertEquals("https://$host", Invitation.parse(invitation, debug = false).endpoint.baseUrl)
    }
}

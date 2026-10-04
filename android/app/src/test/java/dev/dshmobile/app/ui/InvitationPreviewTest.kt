package dev.dshmobile.app.ui

import dev.dshmobile.app.data.MobileFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

/** Synthetic invitation data only; no production credentials or transport setup. */
class InvitationPreviewTest {
    private val route = "0123456789abcdef0123456789abcdef"
    private val host = "h-$route.dsh.invalid"
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun remoteInvitation(expires: Long = System.currentTimeMillis() + 600_000, url: String = "wss://relay.example:9443/private-prefix"): String {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        return buildJsonObject {
            put("version", 2)
            put("baseUrl", "https://$host")
            put("pinSha256", CertificatePinner.pin(certificate.certificate))
            put("certificatePem", certificate.certificatePem())
            put("pairingToken", "synthetic-one-use-secret")
            put("expiresAt", expires)
            put("relay", buildJsonObject {
                put("url", url)
                put("routeId", route)
                put("accessId", "a172cf3d-40e0-43e6-93c4-b27bdb3b8877")
                put("accessToken", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            })
        }.toString()
    }

    @Test fun `v2 preview keeps pinned inner identity and relay origin only`() {
        val preview = previewInvitation(remoteInvitation(), debug = false)
        assertEquals("https://$host", preview.endpoint)
        assertEquals("wss://relay.example:9443", preview.relayOrigin)
        assertTrue(preview.pin.startsWith("sha256/"))
        assertTrue(preview.customCertificate)
        assertFalse(preview.debugHttp)
        val safePresentation = preview.toString()
        assertFalse(safePresentation.contains("private-prefix"))
        assertFalse(safePresentation.contains("synthetic-one-use-secret"))
        assertFalse(safePresentation.contains("a172cf3d-40e0-43e6-93c4-b27bdb3b8877"))
        assertFalse(safePresentation.contains("accessToken"))
        assertFalse(safePresentation.contains("BEGIN CERTIFICATE"))
    }

    @Test fun `direct v1 remains available without invented relay mode`() {
        val preview = previewInvitation("""{"version":1,"baseUrl":"https://computer.example","pinSha256":"$pin","pairingToken":"synthetic-one-use-secret"}""", debug = false)
        assertEquals("https://computer.example", preview.endpoint)
        assertEquals(pin, preview.pin)
        assertNull(preview.relayOrigin)
        assertFalse(preview.customCertificate)
    }

    @Test fun `preview applies canonical expiry and URL validation`() {
        assertThrows(MobileFailure::class.java) { previewInvitation(remoteInvitation(expires = 1), debug = false) }
        assertThrows(MobileFailure::class.java) { previewInvitation(remoteInvitation(url = "wss://relay.example/?token=unsafe"), debug = false) }
        assertThrows(MobileFailure::class.java) {
            previewInvitation("""{"version":1,"baseUrl":"https://computer.example","pinSha256":"sha256/abc","pairingToken":"synthetic-one-use-secret"}""", debug = false)
        }
    }

    @Test fun `manual paste accepts the exact compact invitation printed by the PC`() {
        val json = remoteInvitation()
        val output = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) } }
        val compact = "dshm1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        assertEquals(previewInvitation(json, debug = false), previewInvitation(compact, debug = false))
    }

    @Test(timeout = 5000) fun `import and paste preview reject ten and thirty thousand levels as invitation invalid`() {
        for (depth in listOf(10_000, 30_000)) {
            val json = "{\"nested\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
            val output = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(json.toByteArray()) } }
            val compact = "dshm1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
            for (input in listOf(json, compact)) {
                val failure = assertThrows(MobileFailure::class.java) { previewInvitation(input, debug = false) }
                assertEquals("invitation_invalid", failure.key)
                assertNull(failure.cause)
            }
        }
    }

    @Test fun `release never previews debug HTTP bypass`() {
        val invitation = """{"version":1,"baseUrl":"http://localhost:9443","pairingToken":"synthetic-one-use-secret"}"""
        assertTrue(previewInvitation(invitation, debug = true).debugHttp)
        assertThrows(MobileFailure::class.java) { previewInvitation(invitation, debug = false) }
    }
}

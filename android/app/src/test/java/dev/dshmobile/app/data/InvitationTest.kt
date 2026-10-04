package dev.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvitationTest {
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun invitation(url: String, pinValue: String? = pin): String =
        """{"version":1,"baseUrl":"$url","pairingToken":"synthetic-one-use-token"${pinValue?.let { ",\"pinSha256\":\"$it\"" } ?: ""}}"""

    @Test fun `only exact debug loopback HTTP is accepted`() {
        for (url in listOf("http://localhost:9443", "http://127.0.0.1:9443")) {
            assertEquals(url, Invitation.parse(invitation(url, null), debug = true).endpoint.baseUrl)
            assertEquals("transport_not_allowed", assertThrows(MobileFailure::class.java) {
                Invitation.parse(invitation(url, null), debug = false)
            }.key)
        }
        for (url in listOf("http://localhost.example", "http://LOCALHOST:9443", "http://127.1", "http://127.0.0.2", "http://[::1]", "http://192.0.2.1")) {
            assertThrows(MobileFailure::class.java) { Invitation.parse(invitation(url, null), debug = true) }
        }
    }

    @Test fun `credentials and non-root paths cannot enter a host URL`() {
        for (url in listOf("https://user:pass@computer.example", "https://@computer.example", "https://computer.example/v1", "https://computer.example/..", "https://computer.example/%2f", "https://computer.example?", "https://computer.example#", "https://computer.example/?token=secret", "https://computer.example\\\\evil")) {
            assertThrows(MobileFailure::class.java) { Invitation.parse(invitation(url), debug = true) }
        }
        assertThrows(MobileFailure::class.java) { Invitation.parse(invitation("https://computer.example", "sha256/abc"), debug = false) }
        assertThrows(MobileFailure::class.java) { Invitation.parse(" ".repeat(65_537), debug = false) }
        assertThrows(MobileFailure::class.java) { Invitation.parse("""{"version":1,"baseUrl":42,"pairingToken":"synthetic-one-use-token"}""", debug = true) }
        assertThrows(MobileFailure::class.java) { Invitation.parse(invitation("https://computer.example").replace("\"version\":1", "\"version\":2"), debug = false) }
    }

    @Test fun `HTTPS endpoint is normalized and requires its SPKI pin`() {
        val parsed = Invitation.parse(invitation("https://COMPUTER.example:443/"), debug = false)
        assertEquals("https://computer.example", parsed.endpoint.baseUrl)
        assertEquals(pin, parsed.endpoint.pinSha256)
        assertEquals("pin_required", assertThrows(MobileFailure::class.java) {
            Invitation.parse(invitation("https://computer.example", null), debug = false)
        }.key)
    }
}

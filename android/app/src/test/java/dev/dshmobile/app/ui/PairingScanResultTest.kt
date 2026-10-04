package dev.dshmobile.app.ui

import dev.dshmobile.app.ui.pairing.InvitationScanResult
import dev.dshmobile.app.ui.pairing.PairingScanOutcome
import dev.dshmobile.app.ui.pairing.handleInvitationScan
import org.junit.Assert.*
import org.junit.Test

class PairingScanResultTest {
    private val json = """{"version":1,"baseUrl":"https://COMPUTER.example:443/","pinSha256":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","pairingToken":"synthetic-one-use-token"}"""

    @Test fun `successful scan requires the same identity review as imported JSON`() {
        val outcome = handleInvitationScan(InvitationScanResult.Scanned(json), debug = false) as PairingScanOutcome.Review
        assertEquals(json, outcome.json)
        assertEquals(previewInvitation(json, debug = false), outcome.preview)
        assertEquals("https://computer.example", outcome.preview.endpoint)
        assertFalse(outcome.toString().contains("synthetic-one-use-token"))
        assertFalse(InvitationScanResult.Scanned(json).toString().contains("synthetic-one-use-token"))
    }

    @Test fun `compact scan decodes before the canonical trust review`() {
        val output = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(json.toByteArray()) } }
        val payload = "dshm1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        val outcome = handleInvitationScan(InvitationScanResult.Scanned(payload), debug = false) as PairingScanOutcome.Review
        assertEquals(json, outcome.json)
        assertEquals(previewInvitation(json, debug = false), outcome.preview)
    }

    @Test(timeout = 5000) fun `compact scan rejects ten and thirty thousand JSON levels with a safe error`() {
        for (depth in listOf(10_000, 30_000)) {
            val nested = "{\"nested\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
            val output = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(nested.toByteArray()) } }
            val payload = "dshm1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
            val outcome = handleInvitationScan(InvitationScanResult.Scanned(payload), debug = false)
            assertEquals("invitation_invalid", (outcome as PairingScanOutcome.Error).key)
        }
    }

    @Test fun `empty foreign and invalid invitations never offer trust confirmation`() {
        for (value in listOf(null, "", "https://computer.example", "dshm2:anything", "{}", json.replace("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "sha256/bad"))) {
            assertTrue(handleInvitationScan(InvitationScanResult.Scanned(value), debug = false) is PairingScanOutcome.Error)
        }
    }

    @Test fun `release scan validation cannot allow the debug loopback transport`() {
        val local = """{"version":1,"baseUrl":"http://127.0.0.1:9443","pairingToken":"synthetic-one-use-token"}"""
        assertEquals("transport_not_allowed", (handleInvitationScan(InvitationScanResult.Scanned(local), debug = false) as PairingScanOutcome.Error).key)
        assertTrue((handleInvitationScan(InvitationScanResult.Scanned(local), debug = true) as PairingScanOutcome.Review).preview.debugHttp)
    }

    @Test fun `cancel is a no-op while unavailable and failed scanner provide fallback keys`() {
        assertSame(PairingScanOutcome.Cancelled, handleInvitationScan(InvitationScanResult.Cancelled, debug = false))
        assertEquals("invitation_scan_unavailable", (handleInvitationScan(InvitationScanResult.Unavailable, debug = false) as PairingScanOutcome.Error).key)
        assertEquals("invitation_scan_failed", (handleInvitationScan(InvitationScanResult.Failed, debug = false) as PairingScanOutcome.Error).key)
    }
}


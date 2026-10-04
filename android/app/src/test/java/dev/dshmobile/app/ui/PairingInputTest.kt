package dev.dshmobile.app.ui

import dev.dshmobile.app.ui.pairing.InvitationScanResult
import dev.dshmobile.app.ui.pairing.PairingInput
import dev.dshmobile.app.ui.pairing.PairingScanOutcome
import dev.dshmobile.app.ui.pairing.handleInvitationScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PairingInputTest {
    private fun invitation(host: String, token: String) = """{"version":1,"baseUrl":"https://$host.example","pinSha256":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","pairingToken":"$token"}"""

    @Test fun `delayed import after scanned trust review cannot retarget confirmation`() = runTest {
        val paired = mutableListOf<Pair<dev.dshmobile.app.data.Invitation, String>>()
        val input = PairingInput(this) { parsed, name -> paired += parsed to name }
        val slowContent = CompletableDeferred<String>()
        input.import { withContext(NonCancellable) { slowContent.await() } }
        runCurrent()
        val scanned = invitation("shown", "synthetic-shown-token")
        val review = handleInvitationScan(InvitationScanResult.Scanned(scanned), debug = false) as PairingScanOutcome.Review
        input.edit(review.json)
        slowContent.complete(invitation("unshown", "synthetic-unshown-token"))
        runCurrent()
        input.pair(review.review, "Synthetic phone")
        runCurrent()
        assertEquals("https://shown.example", review.preview.endpoint)
        assertEquals(1, paired.size)
        assertSame(review.review.invitation, paired.single().first)
        assertEquals("synthetic-shown-token", paired.single().first.pairingToken)
        assertEquals("Synthetic phone", paired.single().second)
        assertEquals(scanned, input.invitation)
        assertNull(input.importError)
    }

    @Test fun `confirmation sends the reviewed object even after backing input changes`() = runTest {
        val paired = mutableListOf<dev.dshmobile.app.data.Invitation>()
        val input = PairingInput(this) { parsed, _ -> paired += parsed }
        val review = InvitationReview.parse(invitation("shown", "synthetic-shown-token"), debug = false)
        input.edit(invitation("other", "synthetic-other-token"))
        input.pair(review, "Synthetic phone")
        runCurrent()
        assertSame(review.invitation, paired.single())
        assertEquals("https://shown.example", paired.single().endpoint.baseUrl)
        assertEquals("synthetic-shown-token", paired.single().pairingToken)
        assertFalse(review.toString().contains("synthetic-shown-token"))
    }

    @Test fun `new import wins over a late failing provider and keeps its busy admission`() = runTest {
        val input = PairingInput(this) { _, _ -> fail("Import must not pair") }
        val stale = CompletableDeferred<String?>()
        val latest = CompletableDeferred<String?>()
        input.import { withContext(NonCancellable) { stale.await() } }
        assertTrue(input.importing)
        runCurrent()
        input.import { latest.await() }
        runCurrent()
        stale.complete(null)
        runCurrent()
        assertTrue(input.importing)
        assertNull(input.importError)
        latest.complete(invitation("latest", "synthetic-latest-token"))
        runCurrent()
        assertFalse(input.importing)
        assertEquals(invitation("latest", "synthetic-latest-token"), input.invitation)
        assertNull(input.importError)
    }

    @Test fun `paste supersedes a late failing import without reviving import errors`() = runTest {
        val input = PairingInput(this) { _, _ -> fail("Paste must not pair automatically") }
        val stale = CompletableDeferred<String?>()
        input.import { withContext(NonCancellable) { stale.await() } }
        runCurrent()
        input.edit(invitation("pasted", "synthetic-pasted-token"))
        assertFalse(input.importing)
        stale.complete(null)
        runCurrent()
        assertEquals(invitation("pasted", "synthetic-pasted-token"), input.invitation)
        assertNull(input.importError)
    }
}

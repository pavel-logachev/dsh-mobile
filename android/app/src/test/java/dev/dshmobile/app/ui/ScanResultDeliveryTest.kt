package dev.dshmobile.app.ui

import dev.dshmobile.app.ui.pairing.InvitationScanResult
import dev.dshmobile.app.ui.pairing.ScanResultDelivery
import org.junit.Assert.*
import org.junit.Test

class ScanResultDeliveryTest {
    @Test fun `decode queued while owner stops cannot apply to a disappearing trust dialog`() {
        var active = true
        var stops = 0
        val queue = mutableListOf<() -> Unit>()
        val received = mutableListOf<InvitationScanResult>()
        val delivery = ScanResultDelivery({ active }, { queue += it }, { stops++ }, { received += it })
        delivery.deliver(InvitationScanResult.Scanned("synthetic-payload"))
        active = false // Rotation lifecycle STOP precedes Compose disposal / SavedState recreation.
        queue.removeAt(0).invoke()
        assertEquals("A stopped lifecycle suspends the camera; do not destroy a resumable session", 0, stops)
        assertTrue("The new owner must rescan rather than receive a lost secret", received.isEmpty())
        assertFalse(delivery.isEnded)
        active = true
        delivery.resumed()
        assertTrue("A secret decoded while stopped must not be retained for resume", received.isEmpty())
        val fresh = InvitationScanResult.Scanned("synthetic-fresh-payload")
        delivery.deliver(fresh)
        queue.removeAt(0).invoke()
        assertEquals(1, stops)
        assertEquals(listOf(fresh), received)
    }

    @Test fun `nonsecret startup failure waits for resumed owner instead of leaving scanner loading`() {
        var active = false
        val queue = mutableListOf<() -> Unit>()
        val received = mutableListOf<InvitationScanResult>()
        val delivery = ScanResultDelivery({ active }, { queue += it }, {}, { received += it })
        delivery.deliver(InvitationScanResult.Unavailable)
        queue.removeAt(0).invoke()
        assertTrue(received.isEmpty())
        active = true
        delivery.resumed()
        delivery.resumed()
        assertEquals(listOf(InvitationScanResult.Unavailable), received)
    }

    @Test fun `queued old decode is dropped after disposal and new owner receives one fresh decode`() {
        val queue = mutableListOf<() -> Unit>()
        val received = mutableListOf<InvitationScanResult>()
        val order = mutableListOf<String>()
        val old = ScanResultDelivery({ true }, { queue += it }, { order += "old-stop" }, { received += it })
        old.deliver(InvitationScanResult.Scanned("synthetic-old-payload"))
        old.close()
        val fresh = InvitationScanResult.Scanned("synthetic-fresh-payload")
        val rotated = ScanResultDelivery({ true }, { queue += it }, { order += "new-stop" }, { order += "review"; received += it })
        rotated.deliver(fresh)
        rotated.deliver(fresh) // Racing decoder frames from the new session are admitted once.
        queue.toList().forEach { it() }
        assertEquals(listOf(fresh), received)
        assertEquals(listOf("old-stop", "new-stop", "review"), order)
    }
}

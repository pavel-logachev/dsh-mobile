package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRecoveryTest {
    @Test fun networkChangeInterruptsBackoffAndResetsAttempt() = runTest {
        val reconnect = NotificationReconnect(); val attempts = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reconnect.run { var attempt = 0; attempts += attempt++; delay(notificationRetryDelay(20, 3600000, 1.0)); attempts += attempt }
        }
        advanceTimeBy(100); reconnect.networkChanged(); runCurrent()
        assertEquals(listOf(0, 0), attempts); job.cancelAndJoin()
    }
    @Test fun stableVpnRouteChangeAndLateLostCallbackAreHandled() {
        var changes = 0
        val networks = NotificationNetworkChanges { changes++ }
        networks.available("vpn"); networks.capabilities("vpn", "wifi")
        networks.capabilities("vpn", "wifi"); networks.capabilities("vpn", "lte")
        networks.available("new"); networks.lost("vpn"); assertEquals(3, changes)
        networks.lost("new"); assertEquals(4, changes)
    }
    @Test fun networkRestartJoinsOldOwnerBeforeOpeningNext() = runTest {
        val reconnect = NotificationReconnect(); val transitions = mutableListOf<String>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reconnect.run { transitions += "open"; try { awaitCancellation() } finally { transitions += "close" } }
        }
        reconnect.networkChanged(); runCurrent(); assertEquals(listOf("open", "close", "open"), transitions)
        job.cancelAndJoin(); assertEquals("close", transitions.last())
    }
    @Test fun foregroundRetryAndRetryAfterNeverExceedFiveSeconds() {
        assertEquals(5000L, notificationRetryDelay(20, 3600000, 1.0))
    }
}

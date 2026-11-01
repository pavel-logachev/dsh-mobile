package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRecoveryTest {
    @Test fun networkChangeInterruptsBackoffAndResetsAttempt() = runTest {
        val reconnect = NotificationReconnect()
        val attempts = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reconnect.run {
                var attempt = 0
                attempts += attempt++
                delay(notificationRetryDelay(20, 3600000, 1.0))
                attempts += attempt
            }
        }
        advanceTimeBy(100)
        reconnect.networkChanged()
        runCurrent()
        assertEquals(listOf(0, 0), attempts)
        job.cancelAndJoin()
    }

    @Test fun replayAfterPostingFailureDoesNotAdvanceCursorOrDuplicateSuccessfulPosts() = runTest {
        var local = NotificationLocal(enabled = true, deviceId = "device", epoch = "epoch", cursor = "old")
        val event = NotificationEvent(1, "event", 1, 100, 1000, "workspace", "chat", "answer-finished", 1)
        val page = NotificationPage(1, "epoch", listOf(event), "new", false, false, "ready")
        val visible = mutableMapOf<String, NotificationEvent>()
        var posts = 0
        suspend fun deliver(fail: Boolean) = deliverNotificationPage(page, 200, { local }, { change -> change(local).also { local = it } },
            post = { item, _, _ -> if (fail) throw java.io.IOException("post failed"); visible[item.sessionId] = item; posts++ })
        try { deliver(true); fail("must propagate posting failure") } catch (_: java.io.IOException) { }
        assertEquals("old", local.cursor); assertTrue(local.processed.isEmpty())
        deliver(false); deliver(false)
        assertEquals("new", local.cursor); assertEquals(1, posts); assertEquals(listOf("event"), visible.values.map { it.eventId })
    }

    @Test fun stableVpnRouteChangeAndLateLostCallbackAreHandled() {
        var changes = 0
        val networks = NotificationNetworkChanges { changes++ }
        networks.available("vpn"); networks.capabilities("vpn", "wifi")
        networks.capabilities("vpn", "wifi"); networks.capabilities("vpn", "lte")
        networks.available("new"); networks.lost("vpn")
        assertEquals(3, changes)
        networks.lost("new"); assertEquals(4, changes)
    }

    @Test fun networkRestartJoinsOldStreamBeforeOpeningNext() = runTest {
        val reconnect = NotificationReconnect()
        val transitions = mutableListOf<String>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reconnect.run {
                transitions += "open"
                try { awaitCancellation() } finally { transitions += "close" }
            }
        }
        reconnect.networkChanged(); runCurrent()
        assertEquals(listOf("open", "close", "open"), transitions)
        job.cancelAndJoin()
        assertEquals("close", transitions.last())
    }

    @Test fun crashAfterNotifyBeforeCommitReplacesSameNotificationSilentlyOnReplay() = runTest {
        var local = NotificationLocal(enabled = true, deviceId = "device", epoch = "epoch", cursor = "old")
        val event = NotificationEvent(1, "event", 1, 100, 1000, "workspace", "chat", "answer-finished", 1)
        val page = NotificationPage(1, "epoch", listOf(event), "new", false, false, "ready")
        val visible = mutableMapOf<String, String>()
        var audible = 0
        var crash = true
        suspend fun deliver() = deliverNotificationPage(page, 200, { local }, { change ->
            if (crash) throw java.io.IOException("crash before persistence")
            change(local).also { local = it }
        }, post = { item, _, _ ->
            if (!notificationUpdateSilent(false, item.eventId, visible[item.sessionId])) audible++
            visible[item.sessionId] = item.eventId
        })
        try { deliver(); fail("must simulate crash") } catch (_: java.io.IOException) { }
        assertEquals("old", local.cursor)
        crash = false; deliver(); deliver()
        assertEquals(1, visible.size); assertEquals(1, audible); assertEquals("new", local.cursor)
    }

    @Test fun streamAndHeaderShareLivenessWithoutChangingCommandState() {
        val online = dev.dshmobile.app.model.ConnectionState.ONLINE
        val offline = dev.dshmobile.app.model.ConnectionState.OFFLINE
        assertEquals(online, notificationHeaderConnection(online, "offline"))
        assertEquals(offline, notificationHeaderConnection(offline, "ready"))
        assertEquals(online, notificationHeaderConnection(online, "degraded"))
        assertEquals(offline, notificationHeaderConnection(offline, null))
    }

    @Test fun foregroundRetryAndRetryAfterNeverExceedFiveSeconds() {
        assertEquals(5000L, notificationRetryDelay(20, 3600000, 1.0))
    }
}

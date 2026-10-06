package dev.dshmobile.app.ui

import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun intentIsNotConsumedUntilTargetSelectionFinishesSuccessfully() {
        assertFalse(notificationTargetSelected("new", "old", false, null))
        assertFalse(notificationTargetSelected("new", "new", true, null))
        assertFalse(notificationTargetSelected("new", "new", false, "network_unavailable"))
        assertTrue(notificationTargetSelected("new", "new", false, null))
    }
}

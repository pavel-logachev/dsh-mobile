package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun reconnectIsBoundedJitteredAndRespectsRetryAfter() {
        assertEquals(1000L, notificationRetryDelay(0, 0, 1.0))
        assertEquals(2000L, notificationRetryDelay(1, 0, 1.0))
        assertEquals(5000L, notificationRetryDelay(20, 0, 1.0))
        assertEquals(5000L, notificationRetryDelay(1, 120000, 0.5))
        assertFalse(notificationRetryable("tls_failed")); assertFalse(notificationRetryable("unauthorized"))
        assertTrue(notificationRetryable("network_unavailable"))
    }
    @Test fun upgradeIgnoresRetiredFeedStateAndPreservesLocalSwitchAndWatch() {
        val request = "00000000-0000-4000-8000-000000000001"
        val old = """{"enabled":false,"deviceId":"device","settings":{"enabled":true},"dirty":true,"epoch":"retired","cursor":"head","answerMode":"OFF","watches":[{"sessionId":"chat","requestId":"$request","expiresAt":1000}]}"""
        val current = mobileJson.decodeFromString<NotificationLocal>(old)
        assertFalse(current.enabled); assertEquals(request, current.watches.single().requestId)
        val saved = mobileJson.encodeToString(NotificationLocal.serializer(), current)
        for (retired in listOf("settings", "dirty", "epoch", "cursor", "answerMode")) assertFalse(saved.contains("\"$retired\""))
    }
}

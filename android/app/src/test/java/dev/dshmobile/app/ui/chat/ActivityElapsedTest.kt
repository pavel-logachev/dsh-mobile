package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ActivityElapsedTest {
    private val now = 10_000_000_000L
    private fun elapsed(minutes: Long) = activityElapsed(now - minutes * 60_000L, now)

    @Test fun `elapsed activity uses humane units at minute hour and day boundaries`() {
        assertEquals(ActivityElapsed.LessThanMinute, elapsed(0))
        assertEquals(ActivityElapsed.Minutes(1), elapsed(1))
        assertEquals(ActivityElapsed.Minutes(59), elapsed(59))
        assertEquals(ActivityElapsed.Hours(1, 0), elapsed(60))
        assertEquals(ActivityElapsed.Hours(23, 59), elapsed(1439))
        assertEquals(ActivityElapsed.Days(1), elapsed(1440))
        assertEquals(ActivityElapsed.Days(7), elapsed(10080))
    }

    @Test fun `missing future and stale timestamps do not invent elapsed duration`() {
        assertNull(activityElapsed(null, now))
        assertNull(activityElapsed(now + 1, now))
        assertNull(activityElapsed(now - 7 * 24 * 60 * 60_000L - 1, now))
        assertNull(activityElapsed(Long.MIN_VALUE, now))
    }
}

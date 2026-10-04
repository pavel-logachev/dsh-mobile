package dev.dshmobile.app.ui.home

import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.Workspace
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatListPresentationTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun session(id: String, title: String, project: String, age: Long) =
        SessionSummary(id, title, project, now - age, false, true)

    @Test fun `project filters count all chats and order by latest activity`() {
        val projects = listOf(Workspace("a", "Alpha", true), Workspace("b", "Beta", false), Workspace("c", "Empty", true))
        val sessions = listOf(session("1", "Old", "a", 5000), session("2", "New", "b", 0), session("3", "Other", "a", 1000))
        val result = projectSummaries(projects, sessions)
        assertEquals(listOf("b", "a", "c"), result.map { it.workspace.id })
        assertEquals(listOf(1, 2, 0), result.map { it.chatCount })
        assertFalse(result.first().workspace.canExecute)
    }

    @Test fun `search is local case insensitive title only and intersects project`() {
        val sessions = listOf(session("1", "Исправить ЭКСПОРТ", "a", 500), session("2", "Экспорт", "b", 0), session("3", "Other", "a", 0))
        assertEquals(listOf("1"), filterSessions(sessions, "a", "  экспорт ").map { it.id })
        assertEquals(listOf("2", "1"), filterSessions(sessions, null, "экспорт").map { it.id })
        assertTrue(filterSessions(sessions, null, "a").isEmpty())
    }

    @Test fun `date buckets use local calendar days and Monday week boundary`() {
        fun at(day: Int) = ZonedDateTime.of(2026, 10, day, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sessions = listOf(7, 6, 5, 4).map { SessionSummary("$it", "$it", "a", at(it), false, true) }
        assertEquals(listOf(DateSection.TODAY, DateSection.YESTERDAY, DateSection.THIS_WEEK, DateSection.EARLIER),
            groupSessionsByDate(sessions, now, zone, Locale.GERMANY).map { it.section })
    }

    @Test fun `week grouping follows the device locale first weekday`() {
        val sunday = ZonedDateTime.of(2026, 10, 4, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(DateSection.THIS_WEEK, dateSection(sunday, now, zone, Locale.US))
        assertEquals(DateSection.EARLIER, dateSection(sunday, now, zone, Locale.GERMANY))
    }

    @Test fun `row timestamps give section relevant localized calendar context`() {
        fun at(year: Int, month: Int, day: Int) = ZonedDateTime.of(year, month, day, 10, 13, 0, 0, zone).toInstant().toEpochMilli()
        val ru = Locale.forLanguageTag("ru")
        assertEquals("10:13", sessionTimestamp(at(2026, 10, 7), DateSection.TODAY, now, zone, ru))
        assertEquals("10:13", sessionTimestamp(at(2026, 10, 6), DateSection.YESTERDAY, now, zone, ru))
        assertEquals("пн, 10:13", sessionTimestamp(at(2026, 10, 5), DateSection.THIS_WEEK, now, zone, ru))
        assertEquals("12 сент.", sessionTimestamp(at(2026, 9, 12), DateSection.EARLIER, now, zone, ru))
        assertEquals("12 сент. 2025", sessionTimestamp(at(2025, 9, 12), DateSection.EARLIER, now, zone, ru))
        assertEquals("Mon, 10:13\u202fAM", sessionTimestamp(at(2026, 10, 5), DateSection.THIS_WEEK, now, zone, Locale.US))
        assertEquals("Sep 12", sessionTimestamp(at(2026, 9, 12), DateSection.EARLIER, now, zone, Locale.US))
        assertEquals("Sep 12, 2025", sessionTimestamp(at(2025, 9, 12), DateSection.EARLIER, now, zone, Locale.US))
    }

    @Test fun `day grouping handles daylight saving and future timestamps`() {
        val dstNow = ZonedDateTime.of(2026, 3, 30, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 3, 29, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(DateSection.YESTERDAY, dateSection(yesterday, dstNow, zone))
        assertEquals(DateSection.TODAY, dateSection(dstNow + 100_000, dstNow, zone))
    }
}

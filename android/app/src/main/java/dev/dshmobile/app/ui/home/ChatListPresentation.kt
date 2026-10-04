package dev.dshmobile.app.ui.home

import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.Workspace
import java.time.temporal.WeekFields
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal data class ProjectSummary(val workspace: Workspace, val chatCount: Int, val latestActivity: Long)
internal enum class DateSection { TODAY, YESTERDAY, THIS_WEEK, EARLIER }
internal data class SessionGroup(val section: DateSection, val sessions: List<SessionSummary>)

/** Count once over the bounded host window; never pretend these are totals for truncated history. */
internal fun projectSummaries(workspaces: List<Workspace>, sessions: List<SessionSummary>): List<ProjectSummary> {
    val grouped = sessions.groupBy { it.workspaceId }
    return workspaces.map { project ->
        val chats = grouped[project.id].orEmpty()
        ProjectSummary(project, chats.size, chats.maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE)
    }.sortedWith(compareByDescending<ProjectSummary> { it.latestActivity }.thenBy { it.workspace.name.lowercase(Locale.ROOT) }.thenBy { it.workspace.id })
}

internal fun filterSessions(sessions: List<SessionSummary>, projectId: String?, query: String): List<SessionSummary> {
    val search = query.trim()
    return sessions.filter { (projectId == null || it.workspaceId == projectId) && (search.isEmpty() || it.title.contains(search, ignoreCase = true)) }
        .sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.id })
}

internal fun dateSection(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): DateSection {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> DateSection.TODAY
        day == today.minusDays(1) -> DateSection.YESTERDAY
        !day.isBefore(today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))) -> DateSection.THIS_WEEK
        else -> DateSection.EARLIER
    }
}

/** Section headers supply the day for recent chats; older rows supply their calendar date. */
internal fun sessionTimestamp(timestamp: Long, section: DateSection, now: Long,
    zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(zone)
    val currentYear = Instant.ofEpochMilli(now).atZone(zone).year
    val time = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
    return when (section) {
        DateSection.TODAY, DateSection.YESTERDAY -> date.format(time)
        DateSection.THIS_WEEK -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", locale)) + ", " + date.format(time)
        DateSection.EARLIER -> {
            val pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                java.time.format.FormatStyle.MEDIUM, null, java.time.chrono.IsoChronology.INSTANCE, locale)
            val monthFirst = pattern.indexOf('M') < pattern.indexOf('d')
            val compact = (if (monthFirst) "MMM d" else "d MMM") +
                if (date.year != currentYear) { if (monthFirst) ", uuuu" else " uuuu" } else ""
            date.format(java.time.format.DateTimeFormatter.ofPattern(compact, locale))
        }
    }
}

internal fun groupSessionsByDate(sessions: List<SessionSummary>, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<SessionGroup> =
    sessions.groupBy { dateSection(it.updatedAt, now, zone, locale) }.entries.sortedBy { it.key.ordinal }
        .map { SessionGroup(it.key, it.value.sortedByDescending { session -> session.updatedAt }) }

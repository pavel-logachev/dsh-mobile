package dev.dshmobile.app.ui.chat

/** Presentation-only estimate from the last user message, not an authoritative run start. */
internal sealed interface ActivityElapsed {
    data object LessThanMinute : ActivityElapsed
    data class Minutes(val value: Int) : ActivityElapsed
    data class Hours(val hours: Int, val minutes: Int) : ActivityElapsed
    data class Days(val value: Int) : ActivityElapsed
}

internal fun activityElapsed(started: Long?, now: Long): ActivityElapsed? {
    // Compare before subtraction to reject future/stale times and avoid Long overflow.
    if (started == null || started > now || started < now - 7 * 24 * 60 * 60_000L) return null
    val minutes = (now - started) / 60_000L
    return when {
        minutes == 0L -> ActivityElapsed.LessThanMinute
        minutes < 60L -> ActivityElapsed.Minutes(minutes.toInt())
        minutes < 1440L -> ActivityElapsed.Hours((minutes / 60).toInt(), (minutes % 60).toInt())
        else -> ActivityElapsed.Days((minutes / 1440).toInt())
    }
}

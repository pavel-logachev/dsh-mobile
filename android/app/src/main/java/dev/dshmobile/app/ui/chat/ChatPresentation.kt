package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.model.ChatMessage
import dev.dshmobile.app.model.SessionSnapshot

private const val checkpoint = "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. Treat the captured context as established background and build on it without restating it. Continue the task directly from the messages that follow, without acknowledging this checkpoint."
private val relay = Regex("^Agent [A-Za-z0-9._:-]+ sent a message: ")
private val settled = Regex("^Background subagent [A-Za-z0-9._:-]+ (?:finished and will do no further work unless you send it more\\.|was stopped before it finished\\.|ran out of room before it finished\\.|declined the task\\.|failed before it finished\\.)(?:Its closing message:|It left no closing message\\.)")
private val jobId = Regex("^[A-Za-z0-9._:-]+$")
// Detail and status accept the same characters; never backtrack across their delimiters.
private fun isJobNotice(text: String): Boolean {
    val prefix = "background job "
    val ending = ". Read its output with job_output."
    if (!text.startsWith(prefix) || !text.endsWith(ending) || '\n' in text || '\r' in text) return false
    val open = text.indexOf(" (", prefix.length)
    if (open < 0 || !jobId.matches(text.substring(prefix.length, open))) return false
    val statusEnd = text.length - ending.length
    val delimiter = ") finished "
    val split = text.lastIndexOf(delimiter, statusEnd - delimiter.length - 1)
    return split > open + 2 && split + delimiter.length < statusEnd
}
private val tags = listOf("system-reminder", "hindsight_knowledge", "hindsight_knowledge_refresh")
private val timeReading = Regex("^Time sampled while preparing turn \\d+, step \\d+: \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})\\[[^\\]\\r\\n]+\\](?:\\r?\\nBrowser time zone for this request: [^\\r\\n]+\\r?\\nElapsed since the preceding (?:model-visible message|step context): (?:unavailable|(?:(?:\\d+d )?(?:\\d+h )?(?:\\d+m )?\\d+s))\\.)?$")

/** One monotonic scan; ranges are disjoint and walked backwards without rescanning text. */
private fun suffixStart(text: String): Int {
    val ends = HashMap<Int, Int>()
    var i = 0
    while (i < text.length) {
        val separator = when {
            i == 0 -> 0
            i >= 4 && text.regionMatches(i - 4, "\r\n\r\n", 0, 4) -> i - 4
            i >= 2 && text.regionMatches(i - 2, "\n\n", 0, 2) -> i - 2
            else -> -1
        }
        if (separator < 0) { i++; continue }
        val tag = tags.firstOrNull { text.startsWith("<$it>", i) }
        var end = -1
        if (tag != null) {
            val close = "</$tag>"
            val closing = text.indexOf(close, i + tag.length + 2)
            if (closing < 0) break
            end = closing + close.length
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        } else if (text.startsWith("Time sampled while preparing turn ", i)) {
            val lines = text.substring(i, minOf(text.length, i + 4096)).split('\n', limit = 4)
            val count = if (lines.getOrNull(1)?.startsWith("Browser time zone for this request: ") == true) 3 else 1
            val candidate = lines.take(count).joinToString("\n").removeSuffix("\r")
            if (timeReading.matches(candidate)) end = i + candidate.length
        }
        if (end < 0) { i++; continue }
        ends[end] = separator
        i = end
    }
    var cut = text.length
    while (true) {
        val end = when {
            cut >= 2 && text.regionMatches(cut - 2, "\r\n", 0, 2) -> cut - 2
            cut > 0 && text[cut - 1] == '\n' -> cut - 1
            else -> cut
        }
        cut = ends[cut] ?: ends[end] ?: return cut
    }
}

/** Presentation-only fallback: never mutate canonical messages or delivery reconciliation. */
internal fun presentMessage(message: ChatMessage): ChatMessage {
    if (message.kind != null || message.role != "user") return message
    val text = message.text
    if (relay.containsMatchIn(text) || settled.containsMatchIn(text) || isJobNotice(text)) return message.copy(kind = "agent_event")
    if (text.startsWith("$checkpoint\n\n") || text.startsWith("Current runtime context. This snapshot supersedes earlier runtime-context snapshots.\n\n") ||
        text == "Current runtime context: none. Earlier runtime-context snapshots no longer apply.") return message.copy(kind = "context")
    val cut = suffixStart(text)
    return if (cut == 0) message.copy(kind = "context") else message.copy(text = text.substring(0, cut),
        serviceText = if (cut < text.length) text.substring(cut) else message.serviceText)
}

internal sealed interface ChatItem {
    val id: String
    data class Message(val message: ChatMessage) : ChatItem { override val id = message.id }
    data class Activity(val messages: List<ChatMessage>, override val id: String = "history-start") : ChatItem
}

internal fun chatItems(messages: List<ChatMessage>, previous: List<ChatItem> = emptyList()): List<ChatItem> = buildList {
    val oldGroups = previous.filterIsInstance<ChatItem.Activity>()
    val oldIds = oldGroups.flatMap { group -> group.messages.map { it.id to group.id } }.toMap()
    val service = mutableListOf<ChatMessage>()
    var anchor = "history-start"
    val used = mutableSetOf<String>()
    fun flush() {
        if (service.isEmpty()) return
        val retained = service.firstNotNullOfOrNull { oldIds[it.id] }
        val id = (retained ?: anchor).takeIf { it !in used } ?: anchor
        used.add(id)
        add(ChatItem.Activity(service.toList(), id)); service.clear()
    }
    messages.forEach { original ->
        val message = presentMessage(original)
        if (message.kind in setOf("agent_event", "context") || message.role == "system") service.add(message)
        else {
            flush(); add(ChatItem.Message(message)); anchor = "after:${message.id}"
            message.serviceText?.takeIf { it.isNotEmpty() }?.let { service.add(message.copy(
                id = "suffix:${message.id}", text = it, kind = "context", serviceText = null)) }
        }
    }
    flush()
}

/** The result belongs to exactly one immutable input list, not merely equal content. */
internal class TimelineProjection(val input: List<ChatMessage>, val items: List<ChatItem>)
internal class TimelineInputKey(private val input: List<ChatMessage>) {
    override fun equals(other: Any?) = other is TimelineInputKey && input === other.input
    override fun hashCode() = System.identityHashCode(input)
}
/** null means loading; authoritative empty input never exposes a prior surface. */
internal fun visibleTimeline(input: List<ChatMessage>, projection: TimelineProjection?): List<ChatItem>? =
    if (input.isEmpty()) emptyList() else projection?.takeIf { it.input === input }?.items

internal fun activityStartedAt(snapshot: SessionSnapshot): Long? = snapshot.activityDetail?.turnStartedAt
    ?: snapshot.messages.asReversed().firstOrNull { it.role == "user" && presentMessage(it).kind !in setOf("agent_event", "context") }?.createdAt

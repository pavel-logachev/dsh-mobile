package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.model.ChatMessage

/** UTF-16 offsets match AnnotatedString. Literal, case-insensitive, non-overlapping occurrences. */
internal data class SearchMatch(val messageId: String, val start: Int, val end: Int)

internal fun searchHistory(messages: List<ChatMessage>, query: String): List<SearchMatch> {
    if (query.isBlank()) return emptyList()
    return buildList {
        messages.forEach { message ->
            var from = 0
            while (from <= message.text.length - query.length) {
                val start = message.text.indexOf(query, from, ignoreCase = true)
                if (start < 0) break
                add(SearchMatch(message.id, start, start + query.length))
                from = start + query.length
            }
        }
    }
}

internal fun nextSearchIndex(current: Int, count: Int, direction: Int): Int =
    if (count == 0) -1 else Math.floorMod(current + direction, count)

package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatSearchTest {
    @Test fun `literal Cyrillic search returns every occurrence in loaded order without regex interpretation`() {
        val messages = listOf(ChatMessage("a", "user", "Привет привет [x]", 1), ChatMessage("b", "assistant", "ПРИВЕТ", 2))
        assertEquals(listOf(SearchMatch("a", 0, 6), SearchMatch("a", 7, 13), SearchMatch("b", 0, 6)), searchHistory(messages, "привет"))
        assertEquals(listOf(SearchMatch("a", 14, 17)), searchHistory(messages, "[x]"))
        assertTrue(searchHistory(messages, " ").isEmpty())
        assertTrue(searchHistory(messages, "absent").isEmpty())
    }
    @Test fun `navigation wraps in both directions and empty results have no selection`() {
        assertEquals(0, nextSearchIndex(2, 3, 1))
        assertEquals(2, nextSearchIndex(0, 3, -1))
        assertEquals(-1, nextSearchIndex(0, 0, 1))
    }
}

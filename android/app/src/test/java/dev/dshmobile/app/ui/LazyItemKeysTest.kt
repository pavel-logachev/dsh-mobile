package dev.dshmobile.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LazyItemKeysTest {
    @Test fun `all workspace is distinct from the all projects filter`() {
        val keys = listOf(LazyItemKeys.ALL_PROJECTS, LazyItemKeys.project("all"), LazyItemKeys.project("default"))
        assertEquals("Every project filter must have a unique stable key", keys.size, keys.toSet().size)
        assertEquals(listOf("filter:all", "project:all", "project:default"), keys)
    }

    @Test fun `chat headers and placeholders cannot collide with host session ids`() {
        val keys = listOf(LazyItemKeys.EMPTY_CHATS, LazyItemKeys.TRUNCATED_CHATS, LazyItemKeys.section("TODAY")) +
            listOf("empty", "truncated", "TODAY", "section:TODAY").map(LazyItemKeys::session)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("section:TODAY", LazyItemKeys.section("TODAY"))
        assertEquals("session:section:TODAY", LazyItemKeys.session("section:TODAY"))
    }

    @Test fun `timeline notices messages and pending commands use separate namespaces`() {
        val keys = listOf(LazyItemKeys.HISTORY_LIMIT, LazyItemKeys.EMPTY_CONVERSATION,
            LazyItemKeys.message("history-limit"), LazyItemKeys.message("same"), LazyItemKeys.pending("same"))
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("notice:history-limit", LazyItemKeys.HISTORY_LIMIT)
        assertEquals("notice:empty-conversation", LazyItemKeys.EMPTY_CONVERSATION)
    }

    @Test fun `new chat sheet placeholders cannot collide with projects or presets`() {
        assertNotEquals(LazyItemKeys.EMPTY_PROJECTS, LazyItemKeys.project("empty-projects"))
        assertNotEquals(LazyItemKeys.DEFAULT_PRESET, LazyItemKeys.preset("default"))
        assertEquals("filter:default", LazyItemKeys.DEFAULT_PRESET)
        assertEquals("project:default", LazyItemKeys.project("default"))
    }
}

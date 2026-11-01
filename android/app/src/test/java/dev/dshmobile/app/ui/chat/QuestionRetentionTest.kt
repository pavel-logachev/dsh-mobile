package dev.dshmobile.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionRetentionTest {
    @Test fun `resolved questions without a draft are released`() {
        assertEquals(setOf("b", "c"), retainedQuestionIds(setOf("c"), setOf("b"), listOf("a", "b", "c")))
    }

    @Test fun `pending questions survive the limit`() {
        assertEquals(setOf("a", "b", "c"), retainedQuestionIds(setOf("a", "b", "c"), emptySet(), listOf("a", "b", "c"), limit = 2))
    }

    @Test fun `optional entries are trimmed oldest first`() {
        assertEquals(setOf("c", "d"), retainedQuestionIds(setOf("d"), setOf("c"), listOf("a", "b", "c", "d"), limit = 2))
    }
}

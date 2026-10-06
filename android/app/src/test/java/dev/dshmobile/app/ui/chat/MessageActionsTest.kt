package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MessageActionsTest {
    @Test fun `message menu and accessibility offer copy plain text and select in the same order`() {
        assertEquals(listOf(MessageAction.Copy, MessageAction.CopyAsText, MessageAction.Select), messageActions)
    }
    @Test fun `copy as text removes markdown while preserving code and link label`() {
        assertEquals("Title\n\nHello world link\n\nval x = 1", messagePlainText("# Title\n\nHello **world** [link](https://example.invalid)\n\n```kotlin\nval x = 1\n```"))
    }
}

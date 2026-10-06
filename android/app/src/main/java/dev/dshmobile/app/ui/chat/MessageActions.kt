package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.ui.markdown.*

internal enum class MessageAction { Copy, CopyAsText, Select }
internal val messageActions = listOf(MessageAction.Copy, MessageAction.CopyAsText, MessageAction.Select)

/** Same bounded Markdown interpretation as the rendered preview; raw Copy remains lossless. */
internal fun messagePlainText(text: String): String = parseMarkdown(text).blocks.joinToString("\n\n") { block ->
    fun plain(value: String) = parseInline(value).joinToString("") { it.text }
    when (block) {
        is MarkdownBlock.Heading -> plain(block.text)
        is MarkdownBlock.Paragraph -> plain(block.text)
        is MarkdownBlock.ListEntry -> "${block.marker} ${plain(block.text)}"
        is MarkdownBlock.Quote -> plain(block.text)
        is MarkdownBlock.Code -> block.text
        is MarkdownBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { plain(it) } }
        MarkdownBlock.Rule -> "—"
    }
}

package dev.dshmobile.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.ui.MobileIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Linear parse cost at this size is a few milliseconds on desktop JVM; larger texts parse off the main thread. */
private const val SYNC_PARSE_LIMIT = 8_192

@Composable
internal fun MarkdownContent(text: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    // Short messages parse synchronously (the parser is linear), so the first frame is never empty.
    // Long messages show the last completed result, or plain selectable text on first composition,
    // while a background parse runs; streaming updates cancel obsolete background parses.
    val immediate = remember(text) { if (text.length <= SYNC_PARSE_LIMIT) parseMarkdown(text) else null }
    // produceState keeps its value across key changes, so a growing streamed message keeps
    // showing its latest completed document instead of flashing plain text.
    val latest by produceState<MarkdownDocument?>(null, text) {
        value = immediate ?: withContext(Dispatchers.Default) { parseMarkdown(text) }
    }
    val document = immediate ?: latest
    if (document == null) {
        SelectionContainer(modifier.fillMaxWidth()) { Text(text, style = MaterialTheme.typography.bodyLarge) }
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        document.blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> SelectionContainer {
                    Text(inlineText(block.text), modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    })
                }
                is MarkdownBlock.Paragraph -> SelectionContainer { Text(inlineText(block.text), style = MaterialTheme.typography.bodyLarge) }
                is MarkdownBlock.ListEntry -> Row(Modifier.padding(start = if (block.depth == 1) 20.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, Modifier.widthIn(min = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer(Modifier.weight(1f)) { Text(inlineText(block.text), style = MaterialTheme.typography.bodyLarge) }
                }
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(1.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.outline))
                    SelectionContainer(Modifier.weight(1f)) { Text(inlineText(block.text), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic) }
                }
                MarkdownBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                is MarkdownBlock.Code -> CodeBlock(block, onCopy)
                is MarkdownBlock.Table -> MarkdownTable(block)
            }
        }
        if (document.truncated) Text(stringResource(R.string.mobile_markdown_limit), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun inlineText(text: String): AnnotatedString {
    val spans = remember(text) { parseInline(text) }
    val colors = MaterialTheme.colorScheme
    return remember(spans, colors) {
        buildAnnotatedString {
            spans.forEach { span ->
                withStyle(SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) colors.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Unspecified,
                    color = when { span.link != null -> colors.primary; span.code -> colors.onSurface; else -> androidx.compose.ui.graphics.Color.Unspecified },
                    textDecoration = if (span.link != null) TextDecoration.Underline else null)) {
                    // A URL annotation is intentionally absent: links are styled, selectable, never opened.
                    append(span.text)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code, onCopy: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { stringResource(R.string.mobile_code) }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onCopy(block.copyText) }) { Icon(MobileIcons.Copy, stringResource(R.string.mobile_copy_code), Modifier.size(18.dp)) }
            }
            SelectionContainer {
                Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, softWrap = false)
            }
            if (!block.closed) Text(stringResource(R.string.mobile_code_incomplete), Modifier.padding(start = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table) {
    val widths = remember(block) { block.header.indices.map { column ->
        val chars = (listOf(block.header) + block.rows).maxOf { it.getOrNull(column).orEmpty().length }
        (chars.coerceIn(10, 32) * 8 + 24).dp
    } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        SelectionContainer {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(block.header) + block.rows).forEachIndexed { rowIndex, row ->
                    Row(Modifier.background(if (rowIndex == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)) {
                        row.forEachIndexed { column, cell ->
                            Text(cell, Modifier.width(widths[column]).padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                                fontWeight = if (rowIndex == 0) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                    if (rowIndex < block.rows.size) HorizontalDivider()
                }
            }
        }
    }
}

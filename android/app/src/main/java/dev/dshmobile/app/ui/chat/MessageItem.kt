package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.ChatMessage
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.shortTime
import dev.dshmobile.app.ui.markdown.MarkdownContent

@Composable
internal fun MessageItem(message: ChatMessage, onCopy: (String) -> Unit, searchQuery: String = "", selectedMatch: SearchMatch? = null) {
    val user = message.role == "user"
    var menu by remember(message.id) { mutableStateOf(false) }
    var selecting by remember(message.id) { mutableStateOf(false) }
    val labels = messageActions.associateWith { action -> stringResource(when (action) {
        MessageAction.Copy -> R.string.mobile_copy_message
        MessageAction.CopyAsText -> R.string.mobile_copy_as_text
        MessageAction.Select -> R.string.mobile_select_message
    }) }
    val menuLabel = stringResource(R.string.mobile_message_actions)
    fun perform(action: MessageAction) {
        menu = false
        when (action) {
            MessageAction.Copy -> onCopy(message.text)
            MessageAction.CopyAsText -> onCopy(messagePlainText(message.text))
            MessageAction.Select -> selecting = true
        }
    }
    Column(Modifier.fillMaxWidth().testTag("message:${message.id}")
        .combinedClickable(onClick = {}, onLongClickLabel = menuLabel, onLongClick = { menu = true }, hapticFeedbackEnabled = false)
        .semantics { customActions = messageActions.map { action -> CustomAccessibilityAction(labels.getValue(action)) { perform(action); true } } },
        horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val content: @Composable () -> Unit = {
            if (searchQuery.isNotBlank()) SearchableMessageText(message, searchQuery, selectedMatch)
            else if (user) Text(message.text, style = MaterialTheme.typography.bodyLarge)
            else MarkdownContent(message.text, onCopy, selectable = false)
        }
        if (user) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                Text(shortTime(message.createdAt), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(MobileIcons.Mark, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(if (message.role == "assistant") R.string.mobile_role_assistant else R.string.mobile_role_system),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!user) Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { onCopy(message.text) }, modifier = Modifier.size(48.dp).testTag("copy:${message.id}")) {
                    Icon(MobileIcons.Copy, labels.getValue(MessageAction.Copy), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    messageActions.forEach { action -> DropdownMenuItem(text = { Text(labels.getValue(action)) }, onClick = { perform(action) }) }
                }
            }
        }
        if (message.provisional) Text(stringResource(R.string.mobile_provisional), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (selecting) androidx.compose.ui.window.Dialog(onDismissRequest = { selecting = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                TextButton(onClick = { selecting = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(MobileIcons.Back, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mobile_back))
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(labels.getValue(MessageAction.Select), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.mobile_selection_hint), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(message.text, Modifier.testTag("select_message_text"), style = MaterialTheme.typography.bodyLarge) }
                    OutlinedButton(onClick = { onCopy(message.text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(labels.getValue(MessageAction.Copy)) }
                }
            }
        }
    }
}

/** Search shows literal source so match offsets never drift through Markdown formatting. */
@Composable
private fun SearchableMessageText(message: ChatMessage, query: String, selected: SearchMatch?) {
    val colors = MaterialTheme.colorScheme
    val ranges = remember(message.text, query) { searchHistory(listOf(message), query) }
    val annotated = remember(message.text, ranges, selected, colors) { buildAnnotatedString {
        append(message.text)
        ranges.forEach { range -> addStyle(SpanStyle(
            background = if (range == selected) colors.primary else colors.secondaryContainer,
            color = if (range == selected) colors.onPrimary else colors.onSecondaryContainer), range.start, range.end) }
    } }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(message.text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(selected, layout) {
        val result = layout
        if (selected != null && result != null && selected.start < result.layoutInput.text.length) {
            requester.bringIntoView(result.getBoundingBox(selected.start))
        }
    }
    Text(annotated, Modifier.bringIntoViewRequester(requester).testTag("search_text:${message.id}"),
        style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
}

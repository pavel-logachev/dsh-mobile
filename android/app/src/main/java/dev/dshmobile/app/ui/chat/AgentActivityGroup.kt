package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.ChatMessage

/** Disclosure is local; full original text is always available, never only an ellipsized preview. */
@Composable
internal fun AgentActivityGroup(group: ChatItem.Activity, onCopy: (String) -> Unit = {}) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChatMessage?>(null) }
    val label = stringResource(R.string.mobile_agent_activity_count, group.messages.size)
    val disclosure = stringResource(if (expanded) R.string.mobile_activity_expanded else R.string.mobile_activity_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp).testTag("agent_activity_expander").semantics { stateDescription = disclosure },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp).testTag("agent_activity_details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                group.messages.forEach { message ->
                    TextButton(onClick = { selected = message }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent_activity_item:${message.id}"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(if (message.kind == "context") stringResource(R.string.mobile_context_updated)
                            else message.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    selected?.let { message ->
        val full = message.text + message.serviceText.orEmpty()
        AlertDialog(onDismissRequest = { selected = null }, modifier = Modifier.testTag("agent_activity_dialog"),
            title = { Text(stringResource(R.string.mobile_activity_full_text)) },
            text = {
                SelectionContainer { Text(full, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).testTag("agent_activity_full_text"),
                    style = MaterialTheme.typography.bodyMedium) }
            },
            confirmButton = { TextButton(onClick = { onCopy(full) }, modifier = Modifier.testTag("agent_activity_copy")) { Text(stringResource(R.string.mobile_copy_message)) } },
            dismissButton = { TextButton(onClick = { selected = null }, modifier = Modifier.testTag("agent_activity_close")) { Text(stringResource(R.string.mobile_activity_close)) } })
    }
}

package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.ChatMessage
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.shortTime
import dev.dshmobile.app.ui.markdown.MarkdownContent

@Composable
internal fun MessageItem(message: ChatMessage, onCopy: (String) -> Unit) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (user) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                Text(shortTime(message.createdAt), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(MobileIcons.Mark, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(if (message.role == "assistant") R.string.mobile_role_assistant else R.string.mobile_role_system),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MarkdownContent(message.text, onCopy)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!user) Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onCopy(message.text) }, modifier = Modifier.size(48.dp)) {
                Icon(MobileIcons.Copy, stringResource(R.string.mobile_copy_message), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.provisional) Text(stringResource(R.string.mobile_provisional), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

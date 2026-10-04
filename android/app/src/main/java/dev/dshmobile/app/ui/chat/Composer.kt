package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.LocalMobileColors

@Composable
internal fun Composer(state: MobileState, model: MobileViewModel, onCancel: () -> Unit) {
    val accents = LocalMobileColors.current
    val draftSize = remember(model.editorDraft) { model.editorDraft.toByteArray(Charsets.UTF_8).size }
    val sendEnabled = state.connection == ConnectionState.ONLINE && state.capabilities?.textPrompt == true && executable(state) && !state.busy && !model.interactionBlocked &&
        state.pending == null && model.editorDraft.isNotBlank() && draftSize <= 32_768 && state.snapshot?.activity in listOf("idle", "running")
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PendingDelivery(state, model)
            if (state.snapshot != null) {
                if (draftSize > 32_768) ErrorText("invalid_text")
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = model.editorDraft, onValueChange = model::updateDraft,
                        label = { Text(stringResource(R.string.mobile_message)) }, shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f).testTag("message_input"), minLines = 1, maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        enabled = executable(state) && state.capabilities?.textPrompt == true && !state.busy && !model.interactionBlocked && state.pending == null)
                    val running = state.snapshot.activity == "running"
                    FilledIconButton(onClick = if (running) onCancel else model::sendMessage, enabled = if (running) canCancel(state) && !model.interactionBlocked else sendEnabled,
                        shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accents.action, contentColor = accents.onAction),
                        modifier = Modifier.padding(bottom = 2.dp).size(52.dp).testTag(if (running) "stop_run" else "send_message")) {
                        Icon(if (running) MobileIcons.Stop else MobileIcons.Send, stringResource(if (running) R.string.mobile_cancel_run else R.string.mobile_send))
                    }
                }
                if (state.connection != ConnectionState.ONLINE && executable(state)) Text(stringResource(R.string.mobile_offline_draft),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

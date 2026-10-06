package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val haptic = rememberActionHaptic()
    val draftSize = remember(model.editorDraft) { model.editorDraft.toByteArray(Charsets.UTF_8).size }
    val sendEnabled = MobileReducer.canSend(state, model.editorDraft) && !model.interactionBlocked
    val running = state.snapshot?.activity == "running"
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PendingDelivery(state, model)
            }
            if (state.snapshot != null) {
                if (draftSize > 32_768) ErrorText("invalid_text")
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = model.editorDraft, onValueChange = model::updateDraft,
                        label = { Text(stringResource(if (running) R.string.mobile_message_queued_hint else R.string.mobile_message)) }, shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f).testTag("message_input"), minLines = 1, maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        enabled = executable(state) && state.capabilities?.textPrompt == true && !state.busy && !model.interactionBlocked && state.pending == null)
                    // Secondary Stop retains ChatScreen's existing confirmation callback.
                    if (running) FilledTonalIconButton(onClick = onCancel, enabled = canCancel(state) && !model.interactionBlocked,
                        shape = CircleShape, colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.padding(bottom = 2.dp).size(48.dp).testTag("stop_run")) {
                        Icon(MobileIcons.Stop, stringResource(R.string.mobile_cancel_run))
                    }
                    FilledIconButton(onClick = { model.sendMessage(haptic) }, enabled = sendEnabled,
                        shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accents.action, contentColor = accents.onAction),
                        modifier = Modifier.padding(bottom = 2.dp).size(52.dp).testTag("send_message")) {
                        Icon(MobileIcons.Send, stringResource(R.string.mobile_send))
                    }
                }
                if (state.connection != ConnectionState.ONLINE && executable(state)) Text(stringResource(R.string.mobile_offline_draft),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

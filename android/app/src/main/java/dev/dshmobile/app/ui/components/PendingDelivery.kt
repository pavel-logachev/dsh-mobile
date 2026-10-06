package dev.dshmobile.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.model.MobileReducer
import androidx.compose.ui.text.style.TextOverflow
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.theme.LocalMobileColors
import kotlinx.coroutines.launch

/** Also lives on Home: an uncertain create has no chat/composer yet, but must remain resolvable. */
@Composable
internal fun PendingDelivery(state: MobileState, model: MobileViewModel, modifier: Modifier = Modifier) {
    val accents = LocalMobileColors.current
    // Accepted inbox prompts are informational local bubbles, not delivery warnings.
    val snapshot = state.snapshot
    state.acceptedPrompts.filter { snapshot != null && it.sessionId == snapshot.session.id && !MobileReducer.reconcilesPrompt(it, snapshot) }.forEach { command ->
        key(command.requestId) {
            val scope = rememberCoroutineScope()
            var confirmResend by remember { mutableStateOf(false) }
            var acting by remember { mutableStateOf(false) }
            val actions = state.localPromptActions
            val enabled = !acting && !state.busy && !model.interactionBlocked
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
                modifier = modifier.fillMaxWidth().testTag("accepted_prompt_${command.requestId}")) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(when (command.status) {
                        "queued" -> R.string.mobile_delivery_queued
                        "unconfirmed" -> R.string.mobile_delivery_unconfirmed
                        else -> R.string.mobile_delivery_accepted
                    }),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    val queued = command.status == "queued"
                    Text(command.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = if (queued) 1 else 2, overflow = TextOverflow.Ellipsis)
                    // The local-only warning matters when the user is deciding about an unconfirmed prompt;
                    // on an ordinary queued item it doubled the card height.
                    if (!queued) Text(stringResource(R.string.mobile_local_prompt_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            acting = true
                            scope.launch { try { actions?.dismiss?.invoke(command.requestId) } finally { acting = false } }
                        }, enabled = enabled && actions != null, modifier = Modifier.testTag("dismiss_prompt_${command.requestId}")) {
                            Text(stringResource(R.string.mobile_dismiss_local_prompt))
                        }
                        TextButton(onClick = { confirmResend = true }, enabled = enabled && actions != null && MobileReducer.canSend(state, command.text.orEmpty()),
                            modifier = Modifier.testTag("resend_prompt_${command.requestId}")) { Text(stringResource(R.string.mobile_resend_prompt)) }
                    }
                }
            }
            if (confirmResend) AlertDialog(onDismissRequest = { confirmResend = false },
                title = { Text(stringResource(R.string.mobile_resend_prompt)) },
                text = { Text(stringResource(R.string.mobile_resend_warning)) },
                confirmButton = { TextButton(onClick = {
                    confirmResend = false; acting = true
                    scope.launch { try { actions?.resend?.invoke(command.requestId) } finally { acting = false } }
                }, enabled = enabled && MobileReducer.canSend(state, command.text.orEmpty()), modifier = Modifier.testTag("resend_prompt_confirm")) { Text(stringResource(R.string.mobile_resend_prompt)) } },
                dismissButton = { TextButton(onClick = { confirmResend = false }) { Text(stringResource(R.string.mobile_back)) } })
        }
    }
    if (MobileReducer.blockingPromptCount(state) >= MobileReducer.MAX_ACCEPTED_PROMPTS) {
        Text(stringResource(R.string.mobile_queue_full), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val pending = state.pending ?: return
    var abandon by remember(pending.requestId) { mutableStateOf(false) }
    Surface(color = accents.warningContainer, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(when {
                pending.kind == "cancel" && pending.status == "accepted" -> R.string.mobile_cancel_accepted
                pending.status == "accepted" -> R.string.mobile_delivery_accepted
                pending.status == "rejected" -> R.string.mobile_delivery_rejected
                pending.status == "uncertain" -> R.string.mobile_delivery_uncertain
                pending.status in listOf("pending", "sending") -> R.string.mobile_delivery_pending
                else -> R.string.mobile_delivery_unknown
            }), color = accents.warning, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = model::resolvePending, enabled = !state.busy, modifier = Modifier.testTag("resolve_pending")) { Text(stringResource(R.string.mobile_check_delivery)) }
            if (pending.status in listOf("uncertain", "accepted")) TextButton(onClick = { abandon = true }, enabled = !state.busy, modifier = Modifier.testTag("abandon_pending")) {
                Text(stringResource(R.string.mobile_abandon_pending))
            }
        }
    }
    if (abandon) AlertDialog(onDismissRequest = { abandon = false }, title = { Text(stringResource(R.string.mobile_abandon_title)) },
        text = { Text(stringResource(R.string.mobile_abandon_confirmation)) },
        confirmButton = { TextButton(onClick = { abandon = false; model.abandonPending() }, enabled = !state.busy, modifier = Modifier.testTag("abandon_pending_confirm")) { Text(stringResource(R.string.mobile_abandon_pending)) } },
        dismissButton = { TextButton(onClick = { abandon = false }) { Text(stringResource(R.string.mobile_back)) } })
}

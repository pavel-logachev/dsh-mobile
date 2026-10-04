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
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.theme.LocalMobileColors

/** Also lives on Home: an uncertain create has no chat/composer yet, but must remain resolvable. */
@Composable
internal fun PendingDelivery(state: MobileState, model: MobileViewModel, modifier: Modifier = Modifier) {
    val pending = state.pending ?: return
    var abandon by remember { mutableStateOf(false) }
    val accents = LocalMobileColors.current
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

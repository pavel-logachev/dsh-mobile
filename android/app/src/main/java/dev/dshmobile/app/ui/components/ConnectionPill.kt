package dev.dshmobile.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.animateSize
import dev.dshmobile.app.ui.theme.motionEnabled
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Spacing

@Composable
internal fun displayedConnection(connection: ConnectionState): ConnectionState {
    return connection // Task notification transport never overrides the UI connection.
}

@Composable
internal fun connectionLabel(connection: ConnectionState): String = stringResource(when (displayedConnection(connection)) {
    ConnectionState.ONLINE -> R.string.mobile_pc_online
    ConnectionState.CONNECTING -> R.string.mobile_connecting
    ConnectionState.SYNCING -> R.string.mobile_short_syncing
    ConnectionState.REVOKED -> R.string.mobile_revoked
    ConnectionState.INCOMPATIBLE -> R.string.mobile_incompatible
    ConnectionState.OFFLINE -> R.string.mobile_offline
    ConnectionState.DISCONNECTED -> R.string.mobile_disconnected
})

/** The status dot is the only place connection colour appears; the label always carries the meaning. */
@Composable
internal fun connectionColor(connection: ConnectionState): Color = when (displayedConnection(connection)) {
    ConnectionState.ONLINE -> LocalMobileColors.current.success
    ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE -> MaterialTheme.colorScheme.error
    ConnectionState.OFFLINE, ConnectionState.DISCONNECTED -> LocalMobileColors.current.warningIndicator
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun ConnectionPill(state: MobileState, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = connectionLabel(state.connection)
    // Label changes (connecting → online) resize the pill smoothly instead of jumping.
    Row(modifier.animateSize(motionEnabled).heightIn(min = 48.dp).widthIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick)
        .padding(horizontal = if (compact) 0.dp else 12.dp)
        .testTag("connection_state").semantics(mergeDescendants = true) {
            role = Role.Button
            stateDescription = label
            liveRegion = LiveRegionMode.Polite
            if (compact) contentDescription = label
        }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        StatusDot(connectionColor(state.connection), pulse = state.connection in listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING))
        if (!compact) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionDetails(state: MobileState, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    val accents = LocalMobileColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mobile_connection_details), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.hostName, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(connectionColor(state.connection))
                    Text(connectionLabel(state.connection), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(stringResource(when (state.connection) {
                ConnectionState.ONLINE -> R.string.mobile_online_help
                ConnectionState.REVOKED -> R.string.mobile_revoked_help
                ConnectionState.INCOMPATIBLE -> R.string.mobile_incompatible_help
                else -> if (state.snapshot != null) R.string.mobile_stale_state else R.string.mobile_history_unavailable
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.lastSyncedAt?.let { stringResource(R.string.mobile_last_sync, formattedTime(it)) }
                ?: stringResource(R.string.mobile_never_synced), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.remoteMode) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.mobile_remote_mode), Modifier.testTag("remote_mode"), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.mobile_pc_online_required), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { ErrorText(it) }
            if (state.connection !in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) {
                Button(onClick = onRefresh, enabled = !state.busy, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accents.action, contentColor = accents.onAction),
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("reconnect")) {
                    Text(stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_refresh else R.string.mobile_retry), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Appears only for a connection issue; online takes no timeline height. While it shows, it is the only
 * place the connection label is written: headers keep just the dot. [detail] is an optional second
 * line (for example, why the chat shows an older state).
 */
@Composable
internal fun ConnectionIssueStrip(state: MobileState, onRefresh: () -> Unit, detail: String? = null) {
    val motion = motionEnabled
    // Slides open under the top bar and folds away on reconnect, showing the last issue while it leaves.
    val held = remember { arrayOf<Pair<MobileState, String?>?>(null) }
    if (state.connection != ConnectionState.ONLINE) held[0] = state to detail
    AnimatedVisibility(state.connection != ConnectionState.ONLINE, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "connection issue") {
        held[0]?.let { (shown, shownDetail) -> IssueStripContent(shown, onRefresh, shownDetail) }
    }
}

@Composable
private fun IssueStripContent(state: MobileState, onRefresh: () -> Unit, detail: String?) {
    val label = connectionLabel(state.connection)
    Column(Modifier.animateSize(motionEnabled)) {
        Row(Modifier.fillMaxWidth().heightIn(min = ControlSize.TouchTarget).padding(start = Spacing.Gutter, end = Spacing.S), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(connectionColor(state.connection), pulse = state.connection in listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING))
            Text(label, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium,
                color = if (state.connection in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.connection in listOf(ConnectionState.OFFLINE, ConnectionState.DISCONNECTED)) {
                TextButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.testTag("reconnect"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.mobile_retry)) }
            }
        }
        // Second row aligns with the label, past the 8dp dot and its 10dp gap.
        detail?.takeIf { it != label }?.let {
            Text(it, Modifier.padding(start = Spacing.Gutter + 18.dp, end = Spacing.Gutter, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

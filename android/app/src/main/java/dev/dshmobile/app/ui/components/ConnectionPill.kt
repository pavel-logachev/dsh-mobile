package dev.dshmobile.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*

@Composable
internal fun connectionLabel(connection: ConnectionState): String = stringResource(when (connection) {
    ConnectionState.ONLINE -> R.string.mobile_pc_online
    ConnectionState.CONNECTING -> R.string.mobile_connecting
    ConnectionState.SYNCING -> R.string.mobile_short_syncing
    ConnectionState.REVOKED -> R.string.mobile_revoked
    ConnectionState.INCOMPATIBLE -> R.string.mobile_incompatible
    ConnectionState.OFFLINE -> R.string.mobile_offline
    ConnectionState.DISCONNECTED -> R.string.mobile_disconnected
})

@Composable
internal fun ConnectionPill(state: MobileState, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = connectionLabel(state.connection)
    val color = when (state.connection) {
        ConnectionState.ONLINE -> MaterialTheme.colorScheme.primary
        ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape,
        modifier = modifier.heightIn(min = 48.dp).testTag("connection_state").semantics {
            stateDescription = state.connection.name
            liveRegion = LiveRegionMode.Polite
            if (compact) contentDescription = label
        }) {
        Row(Modifier.padding(horizontal = if (compact) 20.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(color, pulse = state.connection in listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING))
            if (!compact) Text(label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionDetails(state: MobileState, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.mobile_connection_details), style = MaterialTheme.typography.titleLarge)
            Text(state.hostName, style = MaterialTheme.typography.titleMedium)
            Text(connectionLabel(state.connection), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(when (state.connection) {
                ConnectionState.ONLINE -> R.string.mobile_online_help
                ConnectionState.REVOKED -> R.string.mobile_revoked_help
                ConnectionState.INCOMPATIBLE -> R.string.mobile_incompatible_help
                else -> if (state.snapshot != null) R.string.mobile_stale_state else R.string.mobile_history_unavailable
            }), style = MaterialTheme.typography.bodyMedium)
            Text(state.lastSyncedAt?.let { stringResource(R.string.mobile_last_sync, formattedTime(it)) }
                ?: stringResource(R.string.mobile_never_synced), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.remoteMode) {
                Text(stringResource(R.string.mobile_remote_mode), Modifier.testTag("remote_mode"), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.mobile_pc_online_required), style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { ErrorText(it) }
            if (state.connection !in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) {
                Button(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("reconnect")) {
                    Text(stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_refresh else R.string.mobile_retry))
                }
            }
        }
    }
}

/** Appears only for a connection issue; online takes no timeline height. */
@Composable
internal fun ConnectionIssueStrip(state: MobileState, onRefresh: () -> Unit) {
    if (state.connection == ConnectionState.ONLINE) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(connectionLabel(state.connection), Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium,
                color = if (state.connection in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.connection in listOf(ConnectionState.OFFLINE, ConnectionState.DISCONNECTED)) {
                TextButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.testTag("reconnect")) { Text(stringResource(R.string.mobile_retry)) }
            }
        }
    }
}

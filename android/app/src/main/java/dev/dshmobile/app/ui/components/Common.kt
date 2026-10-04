package dev.dshmobile.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.safeErrorResource
import java.text.DateFormat
import java.util.Date

internal fun canCreate(state: MobileState) = state.connection == ConnectionState.ONLINE && state.capabilities?.sessions == true && !state.busy && state.pending == null && state.workspaces.any { it.canExecute }
internal fun executable(state: MobileState) = state.snapshot?.let { snapshot -> snapshot.session.canExecute && state.workspaces.any { it.id == snapshot.session.workspaceId && it.canExecute } } == true
internal fun canCancel(state: MobileState) = state.connection == ConnectionState.ONLINE && state.capabilities?.cancel == true && executable(state) && !state.busy && state.pending == null && state.snapshot?.activity == "running" && state.snapshot.session.running
internal fun formattedTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
internal fun shortTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

@Composable
internal fun workspaceName(state: MobileState, id: String) = state.workspaces.find { it.id == id }?.name ?: stringResource(R.string.mobile_workspace_unknown)

@Composable
internal fun ErrorText(key: String, modifier: Modifier = Modifier) {
    Text(stringResource(safeErrorResource(key)), modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.widthIn(max = 440.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(MobileIcons.Chat, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

package dev.dshmobile.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.MobileState
import kotlinx.coroutines.launch

/** Wiring owns permission/platform work; content only renders immutable state and callbacks. */
@Composable internal fun NotificationSettingsPanel(state: MobileState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { NotificationStore(context) }
    var local by remember { mutableStateOf(NotificationLocal()) }
    var failed by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf(false) }
    LaunchedEffect(store) { try { local = store.read(); local = syncNotificationPreferences(context) } catch (_: Exception) { failed = true } }
    fun change(enabled: Boolean, project: String? = null, chat: String? = null) {
        scope.launch {
            try {
                val host = EncryptedStateStore(context).read().host ?: return@launch
                local = store.update { old ->
                    val current = if (old.deviceId == host.deviceId) old else NotificationLocal(deviceId = host.deviceId)
                    val policy = current.settings
                    current.copy(enabled = if (project == null && chat == null) enabled else current.enabled,
                        settings = policy.copy(enabled = if (project == null && chat == null) enabled else policy.enabled,
                            projects = if (project != null) (policy.projects.filterNot { it.workspaceId == project } + ProjectNotificationSetting(project, enabled)).takeLast(100) else policy.projects,
                            chats = if (chat != null) (policy.chats.filterNot { it.sessionId == chat } + ChatNotificationSetting(chat, enabled)).takeLast(256) else policy.chats), dirty = true)
                }
                context.stopService(Intent(context, NotificationService::class.java))
                if (local.enabled) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
                else context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                failed = false
                runCatching { local = syncNotificationPreferences(context) }
            } catch (_: Exception) { failed = true; runCatching { local = store.update { it.copy(enabled = false) } } }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        pendingPermission = false
        if (allowed) change(true) else failed = true
    }
    NotificationSettingsContent(state, local, failed,
        onEnabled = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else change(enabled)
        },
        onProject = { id, enabled -> change(enabled, project = id) }, onChat = { id, enabled -> change(enabled, chat = id) },
        onBattery = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
        onSystem = { runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } },
        busy = pendingPermission)
}

@Composable internal fun NotificationSettingsContent(state: MobileState, local: NotificationLocal, failed: Boolean,
    onEnabled: (Boolean) -> Unit, onProject: (String, Boolean) -> Unit, onChat: (String, Boolean) -> Unit,
    onBattery: () -> Unit, onSystem: () -> Unit, busy: Boolean) {
    ToggleLine(stringResource(R.string.notif_title), local.enabled, onEnabled, !busy && (local.enabled || state.capabilities?.notifications == true))
    Text(stringResource(R.string.notif_rationale), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.notif_restart), style = MaterialTheme.typography.bodySmall)
    if (state.capabilities?.notifications != true) Text(stringResource(R.string.notif_unsupported))
    if (failed) Text(stringResource(R.string.notif_blocked), color = MaterialTheme.colorScheme.error)
    if (local.dirty) Text(stringResource(R.string.notif_unsynced), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onSystem) { Text(stringResource(R.string.notif_system)) }
    OutlinedButton(onClick = onBattery) { Text(stringResource(R.string.notif_battery)) }
    Text(stringResource(R.string.notif_oneplus), style = MaterialTheme.typography.bodySmall)
    var filters by remember { mutableStateOf(false) }
    TextButton(onClick = { filters = !filters }) { Text(stringResource(R.string.notif_filters)) }
    if (filters) {
        Text(stringResource(R.string.notif_projects), style = MaterialTheme.typography.titleSmall)
        state.workspaces.forEach { workspace ->
            ToggleLine(workspace.name, local.settings.projects.find { it.workspaceId == workspace.id }?.enabled ?: true,
                { onProject(workspace.id, it) }, !busy)
        }
        Text(stringResource(R.string.notif_chats), style = MaterialTheme.typography.titleSmall)
        state.sessions.take(256).forEach { chat ->
            ToggleLine(chat.title, local.settings.chats.find { it.sessionId == chat.id }?.enabled
                ?: local.settings.projects.find { it.workspaceId == chat.workspaceId }?.enabled ?: true,
                { onChat(chat.id, it) }, !busy)
        }
    }
}
@Composable private fun ToggleLine(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, change, enabled = enabled, modifier = Modifier.padding(start = 8.dp)
            .then(Modifier.semanticsLabel(label)))
    }
}
private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription = label })

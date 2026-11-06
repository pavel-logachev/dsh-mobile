package dev.dshmobile.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.MobileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Wiring owns durable preference and permission effects; content is independently renderable. */
@Composable internal fun NotificationSettingsPanel(state: MobileState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { NotificationStore(context) }
    var enabled by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(watchAlertsAllowed(context)) }
    val permissionHistory = remember(context) { context.getSharedPreferences("notification-permissions", android.content.Context.MODE_PRIVATE) }
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    // Android retains a previous denial on upgrade even before this local history exists.
    var permissionDenied by remember { mutableStateOf(permissionHistory.getBoolean("denied", false) ||
        (Build.VERSION.SDK_INT >= 33 && activity != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) }
    fun recordDenial(denied: Boolean) {
        permissionDenied = denied
        permissionHistory.edit { putBoolean("denied", denied) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permitted = watchAlertsAllowed(context)
                if (permitted) recordDenial(false)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store) {
        try { store.changes.collect { enabled = store.read().enabled; loading = false } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loading = false; failed = true }
    }
    fun change(value: Boolean) {
        scope.launch {
            try {
                store.update { it.copy(enabled = value, watches = if (value) it.watches else emptyList(), outbox = if (value) it.outbox else emptyList()) }
                enabled = value; failed = false
                if (!value) { context.stopService(Intent(context, NotificationService::class.java)); NotificationPosting.cancelAll(context) }
                else resumePhoneWatches(context)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionPending = false; permitted = allowed
        recordDenial(!allowed)
        change(allowed)
    }
    NotificationSettingsContent(enabled, loading || permissionPending, failed || (permissionDenied && !permitted), !permitted,
        onEnabled = { value ->
            if (value && Build.VERSION.SDK_INT >= 33 && !watchAlertsAllowed(context)) {
                permissionPending = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else change(value)
        }, onSystem = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        supported = state.capabilities?.promptOutcomes == true)
}

@Composable internal fun NotificationSettingsContent(enabled: Boolean, busy: Boolean, failed: Boolean,
    permissionMissing: Boolean, onEnabled: (Boolean) -> Unit, onSystem: () -> Unit, supported: Boolean = true) {
    val label = stringResource(R.string.notif_title)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled && !permissionMissing, onCheckedChange = onEnabled, enabled = !busy,
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = label })
    }
    Text(stringResource(if (supported) R.string.notif_rationale else R.string.notif_outcomes_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(stringResource(R.string.notif_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    else if (permissionMissing) Text(stringResource(R.string.notif_permission_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsLinkRow(stringResource(R.string.notif_system), onSystem)
}

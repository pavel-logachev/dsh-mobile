package dev.dshmobile.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: MobileState, model: MobileViewModel, onBack: () -> Unit) {
    var forget by remember { mutableStateOf(false) }
    val theme = LocalThemeSetting.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mobile_settings_connection), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Icon(MobileIcons.Back, stringResource(R.string.mobile_back)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).testTag("settings_screen"),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsGroup(stringResource(R.string.mobile_computer_section)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(MobileIcons.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(state.hostName, style = MaterialTheme.typography.titleLarge) }
                }
                if (state.demo) Text(stringResource(R.string.mobile_demo_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup(stringResource(R.string.mobile_connection_section)) {
                Text(connectionLabel(state.connection), style = MaterialTheme.typography.labelLarge)
                Text(state.lastSyncedAt?.let { stringResource(R.string.mobile_last_sync, formattedTime(it)) } ?: stringResource(R.string.mobile_never_synced),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.remoteMode) {
                    Text(stringResource(R.string.mobile_remote_mode), Modifier.testTag("settings_remote_mode"), style = MaterialTheme.typography.titleSmall)
                    state.relayHost?.let { host -> SelectionContainer { Text(stringResource(R.string.mobile_relay_host, host), style = MaterialTheme.typography.labelMedium) } }
                    Text(stringResource(R.string.mobile_remote_explanation), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.mobile_pc_online_required), style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { ErrorText(it) }
                if (state.connection == ConnectionState.REVOKED) Text(stringResource(R.string.mobile_revoked_help))
                if (state.connection == ConnectionState.INCOMPATIBLE) Text(stringResource(R.string.mobile_incompatible_help))
                if (state.connection !in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) OutlinedButton(onClick = model::refresh, enabled = !state.busy) {
                    Icon(MobileIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mobile_refresh_connection))
                }
            }
            SettingsGroup(stringResource(R.string.mobile_appearance)) {
                Text(stringResource(R.string.mobile_theme), style = MaterialTheme.typography.titleSmall)
                ThemePreference.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme_${option.name.lowercase()}")
                        .selectable(selected = theme.value == option, role = Role.RadioButton, onClick = { theme.update(option) }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(theme.value == option, onClick = null)
                        Text(stringResource(when (option) { ThemePreference.DARK -> R.string.mobile_theme_dark; ThemePreference.LIGHT -> R.string.mobile_theme_light; ThemePreference.SYSTEM -> R.string.mobile_theme_system }), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            SettingsGroup(stringResource(R.string.mobile_device_section)) {
                NotificationSettingsPanel(state)
                Text(stringResource(R.string.mobile_phone_limits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text(stringResource(R.string.mobile_forget_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { forget = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("forget_connection"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.mobile_forget)) }
            }
        }
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text(stringResource(R.string.mobile_forget_title)) },
        text = { Text(stringResource(R.string.mobile_forget_confirmation)) },
        confirmButton = { TextButton(onClick = { forget = false; model.forget() }, enabled = !state.busy, modifier = Modifier.testTag("forget_connection_confirm")) { Text(stringResource(R.string.mobile_forget_confirm)) } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text(stringResource(R.string.mobile_back)) } })
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

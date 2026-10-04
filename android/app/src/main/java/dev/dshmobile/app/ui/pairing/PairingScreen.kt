package dev.dshmobile.app.ui.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.data.MobileFailure
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.ui.*
import dev.dshmobile.app.ui.components.ErrorText
import dev.dshmobile.app.ui.theme.LocalMobileColors
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingScreen(state: MobileState, model: MobileViewModel) {
    var trust by remember { mutableStateOf<TrustPreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.mobile_default_device)
    val accents = LocalMobileColors.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { trust = null; previewError = null; model.importInvitation(uri) }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MobileIcons.Mark, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.mobile_brand), style = MaterialTheme.typography.titleLarge)
        } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(MobileIcons.Computer, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.mobile_pair_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.mobile_pair_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(R.string.mobile_pair_step_one, R.string.mobile_pair_step_two, R.string.mobile_pair_step_three).forEachIndexed { index, text ->
                    val stepLabel = stringResource(R.string.mobile_pair_step_number, index + 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.size(32.dp).semantics { contentDescription = stepLabel }) {
                            Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium) }
                        }
                        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            Button(onClick = { importer.launch("*/*") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pairing_import"),
                colors = ButtonDefaults.buttonColors(containerColor = accents.action, contentColor = accents.onAction), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.mobile_import)) }
            TextButton(onClick = { manual = !manual }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("pairing_manual")) {
                Text(stringResource(if (manual) R.string.mobile_hide_json else R.string.mobile_paste_json))
            }
            if (model.invitation.isNotBlank()) Text(stringResource(R.string.mobile_invitation_ready), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (manual) OutlinedTextField(value = model.invitation, onValueChange = { model.editInvitation(it); previewError = null; trust = null },
                label = { Text(stringResource(R.string.mobile_invitation)) }, supportingText = { Text(stringResource(R.string.mobile_invitation_hint)) },
                visualTransformation = PasswordVisualTransformation(), isError = previewError != null, minLines = 2, maxLines = 4, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("pairing_invitation"))
            OutlinedTextField(value = model.deviceName, onValueChange = model::editDeviceName, label = { Text(stringResource(R.string.mobile_device_name)) },
                placeholder = { Text(defaultName) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("pairing_device_name"))
            previewError?.let { ErrorText(it) }; model.importError?.let { ErrorText(it) }; state.error?.let { ErrorText(it) }
            OutlinedButton(onClick = {
                try { trust = previewInvitation(model.invitation); previewError = null }
                catch (failure: MobileFailure) { trust = null; previewError = failure.key }
                catch (_: Exception) { trust = null; previewError = "invitation_invalid" }
            }, enabled = model.invitation.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairing_preview")) {
                Text(stringResource(if (state.busy) R.string.mobile_connecting else R.string.mobile_preview_host))
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) OutlinedButton(onClick = { reset = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("reset_connection")) {
                Text(stringResource(R.string.mobile_reset_connection))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(MobileIcons.Shield, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.mobile_pair_security), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    trust?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.busy) trust = null }, title = { Text(stringResource(R.string.mobile_trust_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mobile_trust_intro))
                IdentityBlock(stringResource(R.string.mobile_invitation_host), URI(preview.endpoint).host.orEmpty())
                Text(stringResource(R.string.mobile_host_name_before_pair), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdentityBlock(stringResource(R.string.mobile_endpoint), preview.endpoint)
                if (preview.debugHttp) Text(stringResource(R.string.mobile_debug_http), color = MaterialTheme.colorScheme.error)
                else IdentityBlock(stringResource(R.string.mobile_host_fingerprint), preview.pin)
                preview.relayOrigin?.let { origin ->
                    Text(stringResource(R.string.mobile_remote_mode), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(stringResource(R.string.mobile_relay_host, origin), Modifier.testTag("pairing_relay"), fontFamily = FontFamily.Monospace) }
                    Text(stringResource(R.string.mobile_remote_explanation))
                    Text(stringResource(R.string.mobile_pc_online_required))
                }
                if (preview.customCertificate) Text(stringResource(R.string.mobile_custom_certificate))
                Text(stringResource(R.string.mobile_trust_verify))
            }
        }, confirmButton = { TextButton(onClick = { trust = null; model.pair(defaultName) }, enabled = !state.busy, modifier = Modifier.testTag("pairing_connect")) { Text(stringResource(R.string.mobile_trust_connect)) } },
            dismissButton = { TextButton(onClick = { trust = null }, enabled = !state.busy) { Text(stringResource(R.string.mobile_back)) } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(stringResource(R.string.mobile_forget_title)) }, text = { Text(stringResource(R.string.mobile_forget_confirmation)) },
        confirmButton = { TextButton(onClick = { reset = false; trust = null; previewError = null; model.forget() }, enabled = !state.busy, modifier = Modifier.testTag("reset_connection_confirm")) { Text(stringResource(R.string.mobile_reset_connection)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.mobile_back)) } })
}

@Composable
private fun IdentityBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
            SelectionContainer { Text(value, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
        }
    }
}

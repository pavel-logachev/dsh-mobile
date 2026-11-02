package dev.dshmobile.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import dev.dshmobile.app.ui.theme.AnimatedKeyed
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.motionEnabled

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.Spinner
import dev.dshmobile.app.ui.theme.LocalMobileColors

/** Length-prefixed provider keeps separators inside either host identifier unambiguous. */
internal fun modelCatalogKey(provider: String, id: String) = "${provider.length}:$provider$id"

@Composable
internal fun SendControl(enabled: Boolean, working: Boolean, steerSupported: Boolean, onSend: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val steer = stringResource(R.string.mobile_send_steer); val queue = stringResource(R.string.mobile_send_queue)
    val label = stringResource(R.string.mobile_send)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val colors = LocalMobileColors.current
    Box {
        Box(Modifier.size(48.dp).testTag("send_message").combinedClickable(enabled = enabled, role = Role.Button,
            onClick = { onSend(null) }, onLongClick = if (working && steerSupported) ({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }) else null)
            .semantics {
                contentDescription = label
                if (working && steerSupported) customActions = listOf(
                    CustomAccessibilityAction(steer) { menu = true; true }, CustomAccessibilityAction(queue) { onSend(null); true })
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).background(if (enabled) colors.action else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(MobileIcons.Send, null, Modifier.size(24.dp), tint = if (enabled) colors.onAction else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Opening does not dispatch: a fresh, separate click is required to select an action.
        DropdownMenu(menu, { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Column { Text(steer); Text(stringResource(R.string.mobile_send_steer_hint), style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = { Icon(MobileIcons.Steer, null) }, modifier = Modifier.testTag("send_steer"), onClick = { menu = false; onSend("steer") })
            HorizontalDivider()
            DropdownMenuItem(text = { Column { Text(queue); Text(stringResource(R.string.mobile_send_queue_hint), style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = { Icon(MobileIcons.Clock, null) }, modifier = Modifier.testTag("send_queue"), onClick = { menu = false; onSend("queue") })
        }
    }
}

@Composable
internal fun QueueGroup(items: List<QueueItem>, enabled: Boolean, working: Boolean, acting: String?, error: String?, keyboardOpen: Boolean, failedAction: String? = null, steerSupported: Boolean = true, onAction: (String, String) -> Unit) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(items.size >= 3, keyboardOpen) { if (items.size >= 3 || keyboardOpen) expanded = false }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("queue_group")) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MobileIcons.Clock, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.mobile_queue_count, items.size, items.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) MobileIcons.ChevronDown else MobileIcons.ChevronRight,
                    stringResource(if (expanded) R.string.mobile_queue_collapse else R.string.mobile_queue_expand)) }
            }
            // The list unfolds under its header; items join and leave the open list with the same reveal.
            val motion = motionEnabled
            AnimatedVisibility(expanded, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "queue items") { Column {
            AnimatedKeyed(items, key = { it.id }, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion)) { item -> Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp).testTag("queue_${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                    if (item.attachments.isNotEmpty()) { Icon(MobileIcons.Attach, null, Modifier.size(14.dp)); Text("${item.attachments.size}", style = MaterialTheme.typography.labelSmall); Spacer(Modifier.width(6.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(item.text.ifBlank { item.attachments.firstOrNull()?.name.orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (acting?.startsWith("queue:${item.id}:") == true) Row(verticalAlignment = Alignment.CenterVertically) { Spinner(size = 12.dp); Text(stringResource(if (acting.endsWith(":remove")) R.string.mobile_queue_removing else R.string.mobile_queue_steering), style = MaterialTheme.typography.bodySmall) }
                        if (error != null && acting == null && failedAction?.startsWith("queue:${item.id}:") == true) PhoneError(stringResource(if (failedAction.endsWith(":remove")) R.string.mobile_queue_remove_failed else R.string.mobile_queue_steer_failed))
                    }
                    if (steerSupported) IconButton(onClick = { onAction(item.id, "steer") }, enabled = enabled && working && acting == null, modifier = Modifier.testTag("queue_steer_${item.id}")) {
                        Icon(MobileIcons.Steer, stringResource(if (working) R.string.mobile_queue_steer_now else R.string.mobile_queue_steer_unavailable)) }
                    IconButton(onClick = { onAction(item.id, "remove") }, enabled = enabled && acting == null, modifier = Modifier.testTag("queue_remove_${item.id}")) { Icon(MobileIcons.Close, stringResource(R.string.mobile_queue_remove)) }
                }
            } }
            } }
        }
    }
}

@Composable internal fun PhoneError(text: String) { Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

@Composable
internal fun RenameDialog(title: String, busy: Boolean, error: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }; val keyboard = LocalSoftwareKeyboardController.current
    val valid = PhoneRules.validTitle(field.text)
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.mobile_rename_title)) }, text = {
            Column {
                OutlinedTextField(field, { field = it.copy(text = it.text.filterNot(Char::isISOControl)) }, enabled = !busy, maxLines = 3,
                    modifier = Modifier.focusRequester(focus).testTag("rename_input"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid && !busy) onSave(field.text) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.onSurface),
                    trailingIcon = { IconButton(onClick = { field = TextFieldValue("") }, enabled = !busy) { Icon(MobileIcons.Close, stringResource(R.string.mobile_rename_clear)) } })
                if (!valid) PhoneError(stringResource(if (field.text.toByteArray().size > 256) R.string.mobile_rename_too_long else R.string.mobile_rename_empty))
                if (error) PhoneError(stringResource(R.string.mobile_rename_failed))
            }
        }, confirmButton = { TextButton(onClick = { onSave(field.text) }, enabled = valid && !busy, modifier = Modifier.testTag("rename_save")) {
            if (busy) Spinner(size = 16.dp); Text(stringResource(R.string.mobile_rename_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.mobile_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(catalog: ModelCatalog?, current: ModelSelection?, loading: Boolean, failed: Boolean, busy: Boolean, imagesNeeded: Boolean,
    onRetry: () -> Unit, onDismiss: () -> Unit, onSelect: (ModelSelection) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.mobile_model_title), style = MaterialTheme.typography.titleLarge)
            if (imagesNeeded) Text(stringResource(R.string.mobile_model_images_needed), color = LocalMobileColors.current.warning, style = MaterialTheme.typography.bodySmall)
            if (loading) Spinner(Modifier.padding(20.dp), size = 20.dp)
            if (failed) { PhoneError(stringResource(R.string.mobile_model_load_failed)); TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_retry_generic)) } }
            if (catalog?.items?.isEmpty() == true) Text(stringResource(R.string.mobile_model_empty))
            if ((catalog?.items?.size ?: 0) > 8) OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.mobile_model_search)) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 440.dp).selectableGroup()) {
                items(catalog?.items.orEmpty().filter { search.isBlank() || it.name.contains(search, true) || it.provider.contains(search, true) }, key = { modelCatalogKey(it.provider, it.id) }) { item ->
                    val selected = current?.let { it.provider == item.provider && it.model == item.id } == true
                    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
                        .selectable(selected, enabled = !busy, role = Role.RadioButton, onClick = { onSelect(ModelSelection(item.provider, item.id)) })
                        .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("model_${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.name, style = MaterialTheme.typography.bodyLarge); Text(item.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Box(Modifier.width(28.dp)) { if (item.supportsImages == true) Icon(MobileIcons.Image, stringResource(R.string.mobile_model_supports_images), Modifier.size(18.dp)) }
                        Box(Modifier.width(24.dp)) { if (selected) Icon(MobileIcons.Check, null, Modifier.size(20.dp)) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(MobileIcons.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mobile_model_images_legend), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.mobile_model_applies_next_turn), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

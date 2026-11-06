package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import androidx.compose.ui.text.style.TextOverflow
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.motionEnabled
import androidx.compose.animation.AnimatedVisibility

@Composable
internal fun Composer(state: MobileState, model: MobileViewModel, onCancel: () -> Unit, onModels: () -> Unit = {}) {
    val haptic = rememberActionHaptic()
    val draftSize = remember(model.editorDraft) { model.editorDraft.toByteArray(Charsets.UTF_8).size }
    val session = state.snapshot?.session?.id
    val attachments = model.phone.inSession(session)
    val selected = state.snapshot?.model?.selection()
    val imagesBlocked = selected?.let { s -> model.modelCatalog?.items?.find { it.provider == s.provider && it.id == s.model }?.supportsImages == false } == true && attachments.any { it.attachment.mime in PhoneRules.imageMimes }
    val sendEnabled = MobileReducer.canSend(state, model.editorDraft, attachments.isNotEmpty()) && !model.interactionBlocked && !imagesBlocked && attachments.none { it.uploading || it.error || it.attachment.uploadId == null }
    val running = state.snapshot?.activity in setOf("running", "waiting")
    var picking by androidx.compose.runtime.saveable.rememberSaveable(session) { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val inputEnabled = canEditDraft(state, model.interactionBlocked)
    val motion = motionEnabled
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Queue and delivery cards animate their own entry, exit and disclosure; the stack itself must not add a
            // second, lagging size animation on top (it showed as an empty card shrinking after its rows had gone).
            // With the keyboard down there is room for a whole delivery card (actions included); with it up the
            // timeline keeps priority and the stack scrolls within 180dp as before.
            val stackMax = if (keyboardOpen) 180.dp else maxOf(180.dp, with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() } * 0.4f)
            Column(Modifier.fillMaxWidth().heightIn(max = stackMax).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QueueGroup(if (state.capabilities?.queue == true) state.snapshot?.pendingQueue.orEmpty() else emptyList(), PhoneRules.canMutate(state) && !model.interactionBlocked,
                    state.snapshot?.session?.running == true, model.phoneAction, model.phoneError, keyboardOpen, model.phoneErrorAction, state.capabilities?.steer == true, model::queueAction)
                PendingDelivery(if (state.capabilities?.queue == true) state.copy(acceptedPrompts = state.acceptedPrompts.filter { p -> state.snapshot?.pendingQueue.orEmpty().none { q -> q.requestId == p.requestId } }) else state, model)
            }
            if (state.snapshot != null) {
                if (draftSize > 32_768) ErrorText("invalid_text", Modifier.padding(horizontal = 8.dp))
                if (imagesBlocked) Row(Modifier.fillMaxWidth().background(LocalMobileColors.current.warningContainer, RoundedCornerShape(14.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mobile_image_model_unsupported), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = LocalMobileColors.current.warning)
                    TextButton(onClick = onModels, enabled = PhoneRules.canMutate(state)) { Text(stringResource(R.string.mobile_image_change_model)) }
                }
                PillField(value = model.editorDraft, onValueChange = model::updateDraft,
                    placeholder = stringResource(if (running) R.string.mobile_message_queued_hint else R.string.mobile_message),
                    enabled = inputEnabled, modifier = Modifier.testTag("message_input"),
                    leading = if (state.capabilities?.attachments == true) ({ IconButton(onClick = { picking = true }, enabled = PhoneRules.canMutate(state) && !model.interactionBlocked) { Icon(MobileIcons.Attach, stringResource(R.string.mobile_attach), Modifier.size(24.dp)) } }) else null,
                    header = {
                        // The chip band opens above the text and folds away with its last chip still showing.
                        val held = remember(session) { arrayOf(attachments) }
                        if (attachments.isNotEmpty()) held[0] = attachments
                        AnimatedVisibility(attachments.isNotEmpty(), enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "attachment chips") {
                            AttachmentChips(held[0], imagesBlocked, { id -> session?.let { model.phone.remove(it, id) } }, { id -> session?.let { model.phone.retry(it, id) } })
                        }
                    }) {
                    // Secondary Stop retains ChatScreen's existing confirmation callback.
                    // Stop is the tonal twin of Send: same 40dp disc in a 48dp target, filled only by the surface tone.
                    if (running) FilledTonalIconButton(onClick = onCancel, enabled = canCancel(state) && !model.interactionBlocked, shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.testTag("stop_run")) {
                        Icon(MobileIcons.StopFilled, stringResource(R.string.mobile_cancel_run), Modifier.size(IconSize.Action))
                    }
                    SendControl(sendEnabled, running, state.capabilities?.steer == true) { delivery -> model.sendMessageWithDelivery(delivery, haptic) }
                }
                if (attachments.any { it.error }) PhoneError(stringResource(R.string.mobile_attach_fix_errors))
                model.phone.error?.let { resource ->
                    if (resource == R.plurals.mobile_attach_skipped_too_large) PhoneError(androidx.compose.ui.res.pluralStringResource(R.plurals.mobile_attach_skipped_too_large, model.phone.skippedLarge, model.phone.skippedLarge, 20))
                    else PhoneError(if (resource == R.string.mobile_attach_images_limit) stringResource(resource, 20) else stringResource(resource))
                }
                if (state.connection != ConnectionState.ONLINE && executable(state)) Text(stringResource(R.string.mobile_offline_draft),
                    Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (picking && session != null) AttachmentPicker({ picking = false }, attachments.size) { uris, temporary -> model.phone.pick(session, uris, temporary) }
}

/** Rounded field with trailing actions inside the same pill, as in mobile chat clients. */
@Composable
internal fun PillField(value: String, onValueChange: (String) -> Unit, placeholder: String, enabled: Boolean, modifier: Modifier = Modifier,
    minLines: Int = 1, maxLines: Int = 6, leading: (@Composable () -> Unit)? = null, header: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(28.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(28.dp))) {
        header?.invoke()
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (leading == null) 20.dp else 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        leading?.invoke()
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, textStyle = text,
            cursorBrush = SolidColor(colors.onSurface), minLines = minLines, maxLines = maxLines,
            modifier = modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, end = 8.dp).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    inner()
                }
            })
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Primary round action: the inverse of the ground, the app's only filled control. */
@Composable
internal fun RoundActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accents = LocalMobileColors.current
    FilledIconButton(onClick = onClick, enabled = enabled, shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accents.action, contentColor = accents.onAction,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        // Material's default 40dp disc (ControlSize.RoundAction); its minimumInteractiveComponentSize keeps a 48dp target.
        modifier = modifier) {
        Icon(icon, label, Modifier.size(IconSize.Action))
    }
}

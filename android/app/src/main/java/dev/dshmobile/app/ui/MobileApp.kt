package dev.dshmobile.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshmobile.app.R
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.ui.chat.ChatScreen
import dev.dshmobile.app.ui.components.ConnectionDetails
import dev.dshmobile.app.ui.components.canCreate
import dev.dshmobile.app.ui.components.PendingDelivery
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.ui.home.ChatListScreen
import dev.dshmobile.app.ui.newchat.NewChatSheet
import dev.dshmobile.app.ui.pairing.PairingScreen
import dev.dshmobile.app.ui.settings.SettingsScreen
import dev.dshmobile.app.ui.theme.LocalMotionEnabled
import kotlinx.coroutines.CancellationException

@Composable
fun MobileApp(model: MobileViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.foreground(true)
                Lifecycle.Event.ON_STOP -> model.foreground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        model.foreground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); model.foreground(false) }
    }
    if (!state.paired) PairingScreen(state, model) else PairedApp(state, model)
}

@Composable
private fun PairedApp(state: MobileState, model: MobileViewModel) {
    var route by rememberSaveable { mutableStateOf("home") }
    var previousRoute by rememberSaveable { mutableStateOf("home") }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var newChat by rememberSaveable { mutableStateOf(false) }
    var connection by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var beforeCreate by rememberSaveable { mutableStateOf<String?>(null) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val motion = LocalMotionEnabled.current
    val savedScreens = rememberSaveableStateHolder()
    fun goBack() { route = if (route == "settings") previousRoute else "home" }
    LaunchedEffect(model.notificationChat, state.busy, model.interactionBlocked) {
        val target = model.notificationChat
        if (target != null && !state.busy && !model.interactionBlocked) {
            sessionId = target; model.selectSession(target); route = "chat"; model.consumeNotificationChat()
        }
    }
    // Native predictive Back commits only on completion; a canceled gesture keeps the screen.
    PredictiveBackHandler(enabled = route != "home" && !newChat && !connection) { events ->
        try { events.collect { backProgress = it.progress }; goBack() }
        catch (_: CancellationException) { /* Canceled gesture: no navigation. */ }
        finally { backProgress = 0f }
    }
    LaunchedEffect(state.snapshot?.session?.id, state.busy, model.interactionBlocked, state.error) {
        if (creating && state.snapshot?.session?.id != null && state.snapshot.session.id != beforeCreate) {
            sessionId = state.snapshot.session.id; route = "chat"; creating = false
        } else if (creating && !state.busy && !model.interactionBlocked && state.error != null) creating = false
    }
    LaunchedEffect(state.workspaces) { if (project != null && state.workspaces.isNotEmpty() && state.workspaces.none { it.id == project }) project = null }
    Box(Modifier.fillMaxSize().graphicsLayer {
        if (motion) { scaleX = 1f - backProgress * 0.025f; scaleY = 1f - backProgress * 0.025f; alpha = 1f - backProgress * 0.1f }
    }) {
        when (route) {
            "settings" -> SettingsScreen(state, model, onBack = ::goBack)
            "chat" -> savedScreens.SaveableStateProvider("chat:${sessionId}") {
                ChatScreen(state, model, sessionId, onBack = { route = "home" }, onConnection = { connection = true })
            }
            else -> savedScreens.SaveableStateProvider("home") {
                ChatListScreen(state, search, { search = it }, project, { project = it },
                    onSelect = { id ->
                        sessionId = id
                        if (state.snapshot?.session?.id != id) model.selectSession(id)
                        route = "chat"
                    },
                    onNewChat = { if (canCreate(state) && !model.interactionBlocked) newChat = true },
                    onSettings = { previousRoute = route; route = "settings" }, onConnection = { connection = true },
                    onRefresh = model::refresh, interactionBlocked = model.interactionBlocked,
                    pendingDelivery = { PendingDelivery(state, model, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) })
            }
        }
    }
    if (newChat) NewChatSheet(state, preferredProject = project,
        onDismiss = { newChat = false }, onCreate = { workspace, preset ->
            newChat = false; beforeCreate = state.snapshot?.session?.id; creating = true; model.createSession(workspace, preset)
        })
    if (connection) ConnectionDetails(state, onDismiss = { connection = false }, onRefresh = model::refresh)
}

/** Exhaustive known-key map; unknown content is never echoed into UI. */
internal fun safeErrorResource(key: String): Int = when (key) {
    "invitation_invalid" -> R.string.mobile_error_invitation_invalid
    "invitation_import_failed" -> R.string.mobile_error_invitation_import_failed
    "invitation_scan_unavailable" -> R.string.mobile_error_invitation_scan_unavailable
    "invitation_scan_failed" -> R.string.mobile_error_invitation_scan_failed
    "invitation_camera_denied" -> R.string.mobile_error_invitation_camera_denied
    "transport_not_allowed" -> R.string.mobile_error_transport_not_allowed
    "pin_required" -> R.string.mobile_error_pin_required
    "certificate_invalid" -> R.string.mobile_error_certificate_invalid
    "pairing_failed" -> R.string.mobile_error_pairing_failed
    "unauthorized" -> R.string.mobile_error_unauthorized
    "revoked" -> R.string.mobile_error_revoked
    "forbidden" -> R.string.mobile_error_forbidden
    "incompatible_protocol" -> R.string.mobile_error_incompatible_protocol
    "unsupported" -> R.string.mobile_error_unsupported
    "invalid_response" -> R.string.mobile_error_invalid_response
    "network_unavailable" -> R.string.mobile_error_network_unavailable
    "tls_failed" -> R.string.mobile_error_tls_failed
    "rate_limited" -> R.string.mobile_error_rate_limited
    "host_error" -> R.string.mobile_error_host_error
    "storage_failed" -> R.string.mobile_error_storage_failed
    "not_paired" -> R.string.mobile_error_not_paired
    "offline_no_send" -> R.string.mobile_error_offline_no_send
    "command_unresolved" -> R.string.mobile_error_command_unresolved
    "command_uncertain" -> R.string.mobile_error_command_uncertain
    "command_rejected" -> R.string.mobile_error_command_rejected
    "request_conflict" -> R.string.mobile_error_request_conflict
    "invalid_text" -> R.string.mobile_error_invalid_text
    "invalid_selection" -> R.string.mobile_error_invalid_selection
    else -> R.string.mobile_error_generic
}

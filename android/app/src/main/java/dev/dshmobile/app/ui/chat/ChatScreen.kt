package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(state: MobileState, model: MobileViewModel, requestedSession: String?, onBack: () -> Unit, onConnection: () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.mobile_copied)
    val onCopy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DSH", text))); snackbars.showSnackbar(copied) } }
    var cancel by remember { mutableStateOf(false) }
    val snapshot = state.snapshot?.takeIf { requestedSession == null || it.session.id == requestedSession }
    val visibleState = if (snapshot == null) state.copy(snapshot = null) else state
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbars) },
        topBar = { TopAppBar(navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("chat_drawer")) { Icon(MobileIcons.Back, stringResource(R.string.mobile_chats)) }
        }, title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(snapshot?.session?.title?.ifBlank { stringResource(R.string.mobile_untitled) } ?: stringResource(R.string.mobile_chats),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(snapshot?.let { workspaceName(state, it.session.workspaceId) } ?: state.hostName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }, actions = {
            ConnectionPill(state, onConnection, compact = true)
            IconButton(onClick = model::refresh, enabled = !state.busy, modifier = Modifier.testTag("refresh_chat")) { Icon(MobileIcons.Refresh, stringResource(R.string.mobile_refresh)) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = {
            Column {
                snapshot?.takeIf { it.activity in listOf("running", "waiting") }?.let { ActivityRow(it) }
                Composer(visibleState, model, onCancel = { cancel = true })
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("chat_screen")) {
            ConnectionIssueStrip(state, model::refresh)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            if (state.demo) Text(stringResource(R.string.mobile_demo_tag), Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("demo_label"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot?.let {
                if (!executable(state)) Notice(stringResource(R.string.mobile_read_only_notice))
                if (it.activity == "waiting") Notice(stringResource(R.string.mobile_desktop_notice))
                else if (it.notice != null) Notice(stringResource(R.string.mobile_partial_information))
                if (it.activity !in listOf("idle", "running", "waiting")) Notice(stringResource(R.string.mobile_unknown_activity))
                key(it.session.id) { ChatTimeline(it, state.pending, onCopy, Modifier.weight(1f)) }
            } ?: Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.busy || model.interactionBlocked) CircularProgressIndicator()
                else EmptyState(
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_title else R.string.mobile_offline),
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_body else R.string.mobile_history_unavailable))
            }
        }
    }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text(stringResource(R.string.mobile_cancel_title)) },
        text = { Text(stringResource(R.string.mobile_cancel_explanation)) },
        confirmButton = { TextButton(onClick = { cancel = false; model.cancelRun() }, enabled = snapshot != null && canCancel(visibleState) && !model.interactionBlocked, modifier = Modifier.testTag("stop_run_confirm")) { Text(stringResource(R.string.mobile_cancel_run)) } },
        dismissButton = { TextButton(onClick = { cancel = false }) { Text(stringResource(R.string.mobile_keep_running)) } })
}

@Composable
private fun ActivityRow(snapshot: SessionSnapshot) {
    val started by produceState<Long?>(snapshot.activityDetail?.turnStartedAt, snapshot.messages, snapshot.activityDetail) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { activityStartedAt(snapshot) }
    }
    var now by remember(snapshot.session.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.session.id, snapshot.activity, started) {
        now = System.currentTimeMillis()
        while (snapshot.activity == "running") { delay(30_000); now = System.currentTimeMillis() }
    }
    val elapsed = activityElapsed(started, now)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("activity_row")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (snapshot.activity == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, pulse = snapshot.activity == "running")
            Text(when {
                snapshot.activity == "waiting" -> stringResource(R.string.mobile_waiting_desktop)
                snapshot.activityDetail == null -> when (elapsed) {
                    null -> stringResource(R.string.mobile_agent_working)
                    ActivityElapsed.LessThanMinute -> stringResource(R.string.mobile_agent_seconds)
                    is ActivityElapsed.Minutes -> stringResource(R.string.mobile_agent_minutes, elapsed.value)
                    is ActivityElapsed.Hours -> stringResource(R.string.mobile_agent_hours, elapsed.hours, elapsed.minutes)
                    is ActivityElapsed.Days -> stringResource(R.string.mobile_agent_days, elapsed.value)
                }
                else -> {
                    val base = stringResource(R.string.mobile_agent_working)
                    val duration = when (elapsed) {
                        null -> null
                        ActivityElapsed.LessThanMinute -> stringResource(R.string.mobile_activity_seconds)
                        is ActivityElapsed.Minutes -> stringResource(R.string.mobile_activity_minutes, elapsed.value)
                        is ActivityElapsed.Hours -> stringResource(R.string.mobile_activity_hours, elapsed.hours, elapsed.minutes)
                        is ActivityElapsed.Days -> stringResource(R.string.mobile_activity_days, elapsed.value)
                    }
                    listOfNotNull(base, snapshot.activityDetail?.tool, duration).joinToString(" · ")
                }
            }, style = MaterialTheme.typography.labelMedium)
        }
    }
}

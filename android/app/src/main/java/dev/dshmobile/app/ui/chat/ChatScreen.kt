package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.motionEnabled
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(state: MobileState, model: MobileViewModel, requestedSession: String?, onBack: () -> Unit, onConnection: () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileUnavailable = stringResource(R.string.mobile_file_unavailable)
    val fileFailed = stringResource(R.string.mobile_file_download_failed)
    val unknownTurnFailure = stringResource(R.string.mobile_turn_failed_unknown)
    val saveDocument = rememberDocumentSaver(snackbars)
    val downloads = remember { kotlinx.coroutines.sync.Semaphore(2) }
    val copied = stringResource(R.string.mobile_copied)
    val modelChangeFailed = stringResource(R.string.mobile_model_change_failed)
    LaunchedEffect(model.phoneError, model.phoneErrorAction) {
        if (model.phoneError != null && model.phoneErrorAction == "model") snackbars.showSnackbar(modelChangeFailed)
    }
    val haptic = rememberActionHaptic()
    val refreshChat = {
        if (requestedSession != null) model.selectSession(requestedSession)
        else model.refresh()
    }
    val onCopy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DSH", text))); haptic(); snackbars.showSnackbar(copied) } }
    var cancel by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    var imagesNeeded by remember { mutableStateOf(false) }
    var viewer by remember(requestedSession) { mutableStateOf<Pair<List<AttachmentRef>, Int>?>(null) }
    val imageCache = remember(model, requestedSession) { ImageMemoryCache { id -> model.loadImage(requireNotNull(requestedSession ?: state.snapshot?.session?.id), id) } }
    val questionHistory = remember(requestedSession) { mutableStateMapOf<String, PendingQuestion>() }
    LaunchedEffect(state.snapshot?.pendingQuestions, state.answerDrafts, requestedSession) {
        val active = state.snapshot?.pendingQuestions.orEmpty()
        active.forEach { questionHistory[it.callId] = it }
        val sessionId = state.snapshot?.session?.id
        val prefix = sessionId?.let { "${it.length}:$it" }
        val draftIds = if (prefix == null) emptySet() else state.answerDrafts.keys
            .filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        val retained = retainedQuestionIds(active.map { it.callId }.toSet(), draftIds, questionHistory.keys.toList())
        questionHistory.keys.retainAll(retained)
    }
    val openModels = { imagesNeeded = false; models = true; model.loadModels() }
    var searching by androidx.compose.runtime.saveable.rememberSaveable(requestedSession) { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable(requestedSession) { mutableStateOf("") }
    var matchIndex by remember(requestedSession, query) { mutableIntStateOf(0) }
    val matches = remember(state.snapshot?.messages, query) { searchHistory(state.snapshot?.messages.orEmpty(), query) }
    val selectedMatch = matches.getOrNull(matchIndex.coerceAtMost(matches.lastIndex))
    val snapshot = state.snapshot?.takeIf { requestedSession == null || it.session.id == requestedSession }
    val visibleState = if (snapshot == null) state.copy(snapshot = null) else state
    val currentModel = snapshot?.model?.selection()
    LaunchedEffect(snapshot?.session?.id, currentModel) { if (currentModel != null && state.capabilities?.models == true && model.modelCatalog == null) model.loadModels() }
    val modelName = currentModel?.let { selected -> model.modelCatalog?.items?.find { it.provider == selected.provider && it.id == selected.model }?.name ?: selected.model }
    val header = snapshot?.session ?: state.sessions.firstOrNull { it.id == requestedSession }
    val motion = motionEnabled
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbars) },
        topBar = { CenterAlignedTopAppBar(navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("chat_drawer")) { Icon(MobileIcons.ChevronLeft, stringResource(R.string.mobile_chats), Modifier.size(IconSize.Action)) }
        }, title = {
            // The title block doubles as the connection affordance; acceptance waits on its state on every screen.
            // One tap target opens connection details; the title stays its own heading node and the
            // subtitle row carries connection_state (enum stateDescription + polite live region).
            Column(Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = stringResource(R.string.mobile_connection_details), onClick = onConnection)
                .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(header?.title?.ifBlank { stringResource(R.string.mobile_untitled) } ?: stringResource(R.string.mobile_chats),
                    Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                val connectionDescription = connectionLabel(state.connection)
                Row(Modifier.testTag("connection_state").semantics(mergeDescendants = true) {
                    stateDescription = connectionDescription
                    liveRegion = LiveRegionMode.Polite
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Off-line the issue strip writes the label; the subtitle keeps the project and adds only the dot.
                    if (state.connection != ConnectionState.ONLINE) {
                        val label = connectionLabel(state.connection)
                        StatusDot(connectionColor(state.connection), Modifier.semantics { contentDescription = label })
                    }
                    Text(header?.let { workspaceName(state, it.workspaceId) } ?: state.hostName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, actions = {
            Box {
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("chat_more")) { Icon(MobileIcons.More, stringResource(R.string.mobile_more_actions), Modifier.size(IconSize.Action)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (state.capabilities?.rename == true) DropdownMenuItem(text = { Text(stringResource(R.string.mobile_chat_rename)) }, leadingIcon = { Icon(MobileIcons.Edit, null) },
                        enabled = PhoneRules.canMutate(state) && !model.interactionBlocked, modifier = Modifier.testTag("chat_rename"), onClick = { more = false; rename = true })
                    if (state.capabilities?.models == true) DropdownMenuItem(text = { Column {
                        Text(stringResource(R.string.mobile_chat_model))
                        Text(modelName ?: stringResource(R.string.mobile_model_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }, leadingIcon = { Icon(MobileIcons.Model, null) }, enabled = PhoneRules.canMutate(state) && !model.interactionBlocked,
                        modifier = Modifier.testTag("chat_model"), onClick = { more = false; openModels() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mobile_search_history)) }, leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(IconSize.Row)) },
                        enabled = snapshot != null, modifier = Modifier.testTag("search_history"),
                        onClick = { more = false; searching = !searching; if (!searching) query = "" })
                    DropdownMenuItem(text = { Text(stringResource(if (state.busy) R.string.mobile_short_syncing else R.string.mobile_refresh)) }, leadingIcon = {
                        if (state.busy) Spinner(size = 16.dp) else Icon(MobileIcons.Refresh, null, Modifier.size(IconSize.Row)) },
                        modifier = Modifier.testTag("refresh_chat"), onClick = { if (!state.busy) { more = false; refreshChat() } })

                }
            }
        }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = {
            Column {
                ChatActivity(visibleState, model.sendingSessionId == visibleState.snapshot?.session?.id && model.sendingSessionId != null)
                Composer(visibleState, model, onCancel = { cancel = true }, onModels = { imagesNeeded = true; models = true; model.loadModels() })
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("chat_screen")) {
            AnimatedVisibility(searching, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "history search") {
                HistorySearchBar(query, { query = it }, matches.size, if (selectedMatch == null) -1 else matches.indexOf(selectedMatch),
                    onPrevious = { matchIndex = nextSearchIndex(matches.indexOf(selectedMatch), matches.size, -1) },
                    onNext = { matchIndex = nextSearchIndex(matches.indexOf(selectedMatch), matches.size, 1) },
                    onClose = { searching = false; query = "" })
            }
            val staleText = if (snapshot != null && state.snapshotStale)
                stringResource(if (state.busy || model.interactionBlocked) R.string.mobile_short_syncing else R.string.mobile_stale_state) else null
            // Off-line, the stale-state explanation is the strip's second row instead of a separate third line.
            ConnectionIssueStrip(state, refreshChat, detail = staleText)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.S)) }
            if (state.demo) {
                // Opaque band with a hairline: the timeline scrolls under an edge, not under floating text.
                // The band grows with the badge (it wraps at large font scales) and keeps the 8dp rhythm under the top bar.
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = Spacing.Gutter, vertical = Spacing.S)) { DemoBadge() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (staleText != null && state.connection == ConnectionState.ONLINE) Text(staleText,
                Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.XS).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot?.let {
                if (model.taskNotSentIn(it.session.id, model.editorDraft)) Text(stringResource(R.string.mobile_task_not_sent), Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("task_not_sent").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = LocalMobileColors.current.warning)
                if (!executable(state)) Notice(stringResource(R.string.mobile_read_only_notice))
                it.failure?.let { failure ->
                    val detail = failure.message.ifBlank { unknownTurnFailure }
                    Text(stringResource(R.string.mobile_turn_failed, detail),
                        Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.S).testTag("turn_failure")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (it.activity == "waiting" && !(state.capabilities?.questions == true && it.pendingQuestions.isNotEmpty())) Notice(stringResource(R.string.mobile_desktop_notice))
                else if (it.notice != null) Notice(stringResource(R.string.mobile_partial_information))
                if (it.activity !in listOf("idle", "running", "waiting")) Notice(stringResource(R.string.mobile_unknown_activity))
                key(it.session.id) { ChatTimeline(it, state.pending, onCopy, Modifier.weight(1f), searchQuery = query, selectedMatch = selectedMatch, questionTarget = model.notificationQuestion,
                    questions = if (state.capabilities?.questions == true && (questionHistory.isNotEmpty() || it.resolvedQuestions.orEmpty().isNotEmpty())) ({
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { questionHistory.values.forEach { question ->
                            val resolved = it.resolvedQuestions.orEmpty().find { r -> r.callId == question.callId }
                            QuestionBlock(question, state.answerDrafts[questionDraftKey(it.session.id, question.callId)], !executable(state), state.connection == ConnectionState.ONLINE && !state.snapshotStale,
                                model.phoneAction == "answer:${question.callId}", model.phoneError != null && model.phoneErrorAction == "answer:${question.callId}",
                                expired = resolved == null && it.pendingQuestions.none { q -> q.callId == question.callId } && (it.resolvedQuestions != null || model.answeredQuestions[questionDraftKey(it.session.id, question.callId)] == null),
                                answered = resolved?.takeIf { r -> r.outcome == "answered" }?.let { r -> QuestionAnswer(r.answers) } ?: model.answeredQuestions[questionDraftKey(it.session.id, question.callId)].takeIf { _ -> it.resolvedQuestions == null },
                                skipped = resolved?.outcome == "skipped", skipSupported = state.capabilities?.questionSkip == true,
                                onSkip = { model.skipQuestion(question.callId) }, focusTarget = model.notificationQuestion == question.callId, onChange = { answer -> model.saveAnswer(it.session.id, question.callId, answer) }, onSubmit = { answer -> model.answer(question.callId, answer) })
                        }
                        it.resolvedQuestions.orEmpty().filter { r -> r.callId !in questionHistory }.forEach { resolved ->
                            Text(if (resolved.outcome == "skipped") stringResource(R.string.mobile_question_skipped) else stringResource(R.string.mobile_question_answer, resolved.answers.flatMap { a -> a.selected + listOfNotNull(a.custom) }.joinToString(", ")),
                                Modifier.testTag("question_record_${resolved.callId}"), style = MaterialTheme.typography.bodyMedium)
                        } }
                    }) else null,
                    media = if (state.capabilities?.attachments == true) ({ message -> MediaAttachments(message, imageCache, onFile = if (state.capabilities?.fileDownload == true && state.connection == ConnectionState.ONLINE) ({ file ->
                        scope.launch {
                            downloads.acquire()
                            try {
                                val data = model.loadFile(it.session.id, file.id)
                                if (android.os.Build.VERSION.SDK_INT < 29) saveDocument(file, data) else openDownloadedFile(context, file, data)
                            }
                            catch (failure: Exception) { snackbars.showSnackbar(if ((failure as? dev.dshmobile.app.data.MobileFailure)?.key == "chat_unavailable") fileUnavailable else fileFailed) }
                            finally { downloads.release() }
                        }
                    }) else null) { refs, index -> viewer = refs to index } }) else null) }
            } ?: Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.busy || model.interactionBlocked) CircularProgressIndicator()
                else EmptyState(
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_title else R.string.mobile_offline),
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_body else R.string.mobile_history_unavailable))
            }
        }
    }
    viewer?.let { (refs, index) ->
        val timestamp = snapshot?.messages?.firstOrNull { message -> message.attachments.any { a -> a.id == refs.firstOrNull()?.id } }?.createdAt
        ChatViewerOverlay(refs, index, imageCache, timestamp) { viewer = null }
    }
    if (rename && header != null) RenameDialog(header.title, model.phoneAction == "rename", model.phoneError != null && model.phoneErrorAction == "rename", { rename = false }) { title -> model.rename(title) { rename = false } }
    if (models) ModelSheet(model.modelCatalog, currentModel, model.modelsLoading, model.modelsFailed, model.phoneAction == "model", imagesNeeded,
        model::loadModels, { models = false }) { selection -> model.selectModel(selection) { models = false } }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text(stringResource(R.string.mobile_cancel_title)) },
        text = { Text(stringResource(R.string.mobile_cancel_explanation)) },
        confirmButton = { TextButton(onClick = { cancel = false; model.cancelRun(haptic) }, enabled = snapshot != null && canCancel(visibleState) && !model.interactionBlocked, modifier = Modifier.testTag("stop_run_confirm")) { Text(stringResource(R.string.mobile_cancel_run)) } },
        dismissButton = { TextButton(onClick = { cancel = false }) { Text(stringResource(R.string.mobile_keep_running)) } })
}

/** Separate window retains the chat list, classification and follow state underneath. */
@Composable
internal fun ChatViewerOverlay(refs: List<AttachmentRef>, index: Int, cache: ImageMemoryCache, timestamp: Long? = null, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImageViewer(refs, index, cache, timestamp, onClose)
    }
}

/** Checked accepted prompts can be optimistic working. In-flight local/host-pending sends
 * only show neutral sending. Neither fabricates canonical running, timestamps or cancel authority. */
@Composable
internal fun ChatActivity(state: MobileState, preparing: Boolean = false) {
    val snapshot = state.snapshot
    val accepted = state.connection == ConnectionState.ONLINE && !state.snapshotStale &&
        state.acceptedPrompts.any { it.sessionId == snapshot?.session?.id && it.status == "accepted" }
    // Local in-flight is progress, NOT evidence of host admission or an agent turn.
    val sending = state.pending?.let { it.kind == "send" && it.sessionId == snapshot?.session?.id && it.status in setOf("sending", "pending") } == true
    val activity = snapshot?.let {
        when {
            it.activity in listOf("running", "waiting") || (it.activity == "idle" && it.session.runningSubagents > 0) -> it
            it.activity == "idle" && accepted -> it.copy(activity = "running", activityDetail = null, messages = emptyList())
            sending || (preparing && state.pending?.status !in setOf("uncertain", "rejected")) -> it.copy(activity = "sending", activityDetail = null, messages = emptyList())
            else -> null
        }
    }
    val held = remember(snapshot?.session?.id) { arrayOfNulls<SessionSnapshot>(1) }
    if (activity != null) held[0] = activity
    val motion = motionEnabled
    AnimatedVisibility(activity != null, enter = Motion.riseIn(motion), exit = Motion.riseOut(motion), label = "activity row") {
        held[0]?.let { ActivityRow(it, state.capabilities?.questions == true && it.pendingQuestions.isNotEmpty()) }
    }
}

@Composable
private fun ActivityRow(snapshot: SessionSnapshot, answerable: Boolean = false) {
    val started by produceState<Long?>(snapshot.activityDetail?.turnStartedAt, snapshot.messages, snapshot.activityDetail) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { activityStartedAt(snapshot) }
    }
    var now by remember(snapshot.session.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.session.id, snapshot.activity, started) {
        now = System.currentTimeMillis()
        while (snapshot.activity == "running") { delay(30_000); now = System.currentTimeMillis() }
    }
    val elapsed = activityElapsed(started, now)
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 24.dp).testTag("activity_row").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        run {
            if (snapshot.activity in setOf("running", "sending") || (snapshot.activity == "idle" && snapshot.session.runningSubagents > 0)) Spinner(size = ControlSize.StatusGlyph, strokeWidth = ControlSize.StatusStroke) else StatusDot(LocalMobileColors.current.warningIndicator)
            Text(when {
                snapshot.activity == "sending" -> stringResource(R.string.mobile_delivery_pending)
                snapshot.activity == "idle" && snapshot.session.runningSubagents > 0 -> stringResource(R.string.mobile_subagents_activity, snapshot.session.runningSubagents)
                snapshot.activity == "waiting" -> stringResource(if (answerable) R.string.mobile_waiting_answer else R.string.mobile_waiting_desktop)
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
            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

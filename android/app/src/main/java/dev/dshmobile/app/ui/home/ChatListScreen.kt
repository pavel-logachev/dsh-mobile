package dev.dshmobile.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.ui.layout.Layout
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.motionEnabled
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.tabular

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreen(state: MobileState, query: String, onQuery: (String) -> Unit, projectId: String?, onProject: (String?) -> Unit,
    onSelect: (String) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit,
    interactionBlocked: Boolean, pendingDelivery: @Composable () -> Unit) {
    val sessions = state.sessions // Disk index is presentation only; connection still gates all host actions.
    val projects = remember(state.workspaces, sessions) { projectSummaries(state.workspaces, sessions) }
    val visible = remember(sessions, query, projectId) { filterSessions(sessions, projectId, query) }
    val locale = LocalConfiguration.current.locales[0]
    val now = remember(visible, locale, state.lastSyncedAt) { System.currentTimeMillis() }
    val groups = remember(visible, locale, now) { groupSessionsByDate(visible, now, locale = locale) }
    val list = rememberLazyListState()
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false; onQuery("") }
    val canStart = canCreate(state) && !interactionBlocked
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                        Icon(MobileIcons.Settings, stringResource(R.string.mobile_settings_connection), Modifier.size(IconSize.Action))
                    }
                },
                title = { HomeTitle(state, onConnection) },
                actions = {
                    // The header keeps the magnifier as a toggle (tonal while open); the only cross is the field's clear action.
                    IconToggleButton(checked = searchOpen, onCheckedChange = { searchOpen = it; if (!it) onQuery("") }, modifier = Modifier.testTag("home_search_toggle"),
                        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            checkedContentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(MobileIcons.Search, stringResource(R.string.mobile_search_chats), Modifier.size(IconSize.Action))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = { NewTaskBar(canStart, onNewChat) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("home_screen")) {
            ConnectionIssueStrip(state, onRefresh)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            pendingDelivery()
            val pull = rememberPullToRefreshState()
            // The repository flips manualRefreshing only once it owns its lock, so a released pull could
            // snap back and reappear (or blink for a fast refresh). Hold the indicator from the release
            // until that refresh has started and finished, and for at least one readable spin.
            var pulledAt by remember { mutableStateOf<Long?>(null) }
            var started by remember { mutableStateOf(false) }
            var outcomeAtPull by remember { mutableStateOf<Pair<Long?, String?>?>(null) }
            val outcome = state.lastSyncedAt to state.error
            LaunchedEffect(pulledAt, state.manualRefreshing, outcome) {
                val since = pulledAt ?: return@LaunchedEffect
                if (state.manualRefreshing) { started = true; return@LaunchedEffect }
                // A very fast refresh can finish between two frames; its sync time or error still changes.
                val finished = started || outcome != outcomeAtPull
                kotlinx.coroutines.delay(if (finished) (MIN_REFRESH_SPIN_MS - (System.currentTimeMillis() - since)).coerceAtLeast(0) else REFRESH_START_TIMEOUT_MS)
                pulledAt = null; started = false
            }
            val refreshing = state.manualRefreshing || pulledAt != null
            // Rows fade in/out and glide to new positions (filters, new chats, reorder); off without motion.
            val motion = motionEnabled
            val itemFade = remember(motion) { if (motion) Motion.tween<Float>(Motion.Medium, true) else null }
            val itemPlacement = remember(motion) { if (motion) Motion.tween<androidx.compose.ui.unit.IntOffset>(Motion.Medium, true) else null }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { pulledAt = System.currentTimeMillis(); started = false; outcomeAtPull = outcome; onRefresh() }, state = pull,
                indicator = { PullToRefreshDefaults.Indicator(state = pull, isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (searchOpen) 112.dp else 56.dp)) },
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("chat_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                    // Filters scroll with the list so large landscape text cannot consume the row viewport.
                    item(key = "list-filters") {
                        Column {
                            AnimatedVisibility(searchOpen, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "chat search") { SearchField(query, onQuery) }
                            val tabs = rememberLazyListState()
                            // Tab text starts on the gutter (row padding + the tab's own 12dp); edges fade where more tabs scroll in.
                            LazyRow(Modifier.fillMaxWidth().edgeFade(tabs).testTag("project_filters"), state = tabs,
                                contentPadding = PaddingValues(horizontal = Spacing.Gutter - Spacing.M)) {
                                item(key = LazyItemKeys.ALL_PROJECTS) { ProjectTab(stringResource(R.string.mobile_all_projects), projectId == null, "project_all") { onProject(null) } }
                                items(projects, key = { LazyItemKeys.project(it.workspace.id) }) { project ->
                                    ProjectTab(stringResource(R.string.mobile_project_filter, project.workspace.name, project.chatCount), projectId == project.workspace.id,
                                        "project_filter_${project.workspace.id}") { onProject(project.workspace.id) }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (state.demo) DemoBadge(Modifier.padding(start = Spacing.Gutter, end = Spacing.Gutter, top = Spacing.L))
                        }
                    }
                    if (visible.isEmpty()) item(key = LazyItemKeys.EMPTY_CHATS) {
                        EmptyState(modifier = Modifier.animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade).fillMaxWidth().padding(top = 32.dp),
                            title = stringResource(when {
                                query.isNotBlank() -> R.string.mobile_no_search_title
                                state.connection != ConnectionState.ONLINE && sessions.isEmpty() -> R.string.mobile_offline
                                projectId != null -> R.string.mobile_no_project_chats
                                else -> R.string.mobile_home_empty_title
                            }),
                            body = stringResource(when {
                                query.isNotBlank() -> R.string.mobile_no_search_body
                                state.connection != ConnectionState.ONLINE && sessions.isEmpty() -> R.string.mobile_history_unavailable
                                projectId != null -> R.string.mobile_no_project_body
                                else -> R.string.mobile_home_empty_body
                            }))
                    }
                    groups.forEach { group ->
                        item(key = LazyItemKeys.section(group.section.name)) {
                            Text(stringResource(when (group.section) {
                                DateSection.WORKING -> R.string.mobile_working_now
                                DateSection.TODAY -> R.string.mobile_today
                                DateSection.YESTERDAY -> R.string.mobile_yesterday
                                DateSection.THIS_WEEK -> R.string.mobile_this_week
                                DateSection.EARLIER -> R.string.mobile_earlier
                            }), Modifier.animateItem(fadeInSpec = itemFade, placementSpec = itemPlacement, fadeOutSpec = itemFade)
                                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(group.sessions, key = { LazyItemKeys.session(it.id) }, contentType = { "session" }) { session ->
                            SessionRow(session, workspaceName(state, session.workspaceId),
                                timestamp = sessionTimestamp(session.updatedAt, group.section, now, locale = locale),
                                readOnly = !session.canExecute || state.workspaces.none { it.id == session.workspaceId && it.canExecute },
                                enabled = !interactionBlocked && state.connection in listOf(ConnectionState.ONLINE, ConnectionState.SYNCING, ConnectionState.OFFLINE),
                                onClick = { onSelect(session.id) },
                                modifier = Modifier.animateItem(fadeInSpec = itemFade, placementSpec = itemPlacement, fadeOutSpec = itemFade))
                        }
                    }
                    if (state.sessionsTruncated) item(key = LazyItemKeys.TRUNCATED_CHATS) {
                        Text(stringResource(R.string.mobile_sessions_truncated), Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Brand plus live connection state; the whole block opens connection details. */
@Composable
private fun HomeTitle(state: MobileState, onConnection: () -> Unit) {
    val label = connectionLabel(state.connection)
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onConnection).heightIn(min = 48.dp).padding(horizontal = 12.dp)
        .testTag("connection_state").semantics(mergeDescendants = true) {
            stateDescription = label
            liveRegion = LiveRegionMode.Polite
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.mobile_brand), style = MaterialTheme.typography.titleMedium)
        // Off-line the issue strip below writes the label; the header keeps only the dot (label stays for TalkBack).
        val online = dev.dshmobile.app.ui.components.displayedConnection(state.connection) == ConnectionState.ONLINE
        Row(Modifier.heightIn(min = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(connectionColor(state.connection), if (online) Modifier else Modifier.semantics { contentDescription = label },
                pulse = state.connection in listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING))
            if (online || state.connection == ConnectionState.ONLINE) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(stringResource(R.string.mobile_search_chats)) },
        leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(IconSize.Row)) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(MobileIcons.Close, stringResource(R.string.mobile_clear_search), Modifier.size(IconSize.Row)) } },
        shape = CircleShape, colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant, focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("chat_search"))
}

private const val MIN_REFRESH_SPIN_MS = 600L
/** Upper bound for a refresh that never reports start (closed repository); the lock wait itself is covered. */
private const val REFRESH_START_TIMEOUT_MS = 8_000L

internal fun sessionWorkLabel(session: SessionSummary, main: String, subagents: String, count: String, waiting: String? = null): String? = when {
    session.waitingForAnswer -> waiting
    session.running -> main + if (session.runningSubagents > 0) " · $count" else ""
    session.runningSubagents > 0 -> subagents
    else -> null
}

/** Text tab with an underline; selection semantics stay native for TalkBack and tests. */
@Composable
private fun ProjectTab(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val ink = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    // The underline is drawn, not laid out, so the tab hugs its label instead of growing to the max width.
    Box(Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp).testTag(tag)
        .drawBehind { if (selected) drawRoundRect(line, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx())) },
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionRow(session: SessionSummary, project: String, timestamp: String, readOnly: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val work = sessionWorkLabel(session, stringResource(R.string.mobile_working),
        stringResource(R.string.mobile_subagents_working, session.runningSubagents), stringResource(R.string.mobile_subagents_count, session.runningSubagents), stringResource(R.string.mobile_waiting_for_answer))
    val readOnlyLabel = if (readOnly) stringResource(R.string.mobile_read_only) else null
    // The status is what the user must read, so it is never the part that gets cut: the project (with the
    // read-only note) yields first, and when even that leaves no room the status takes its own line.
    val lead = listOfNotNull(project, readOnlyLabel).joinToString(" · ")
    val statusColor = if (session.waitingForAnswer) LocalMobileColors.current.warning else muted
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp).testTag("session_${session.id}"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalAlignment = Alignment.Top) {
        // One fixed cell, centred on the first title line (22sp line height × font scale), so running and idle rows share a left edge.
        val titleLine = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
        val status = when { session.waitingForAnswer -> RowStatus.WAITING; session.running || session.runningSubagents > 0 -> RowStatus.WORKING; else -> RowStatus.IDLE }
        val waitingLabel = stringResource(R.string.mobile_waiting_for_answer)
        // Status swaps crossfade in place; the cell never resizes, so the title does not shift.
        val motion = motionEnabled
        AnimatedContent(status, Modifier.size(ControlSize.StatusCell, maxOf(ControlSize.StatusCell, titleLine)), transitionSpec = { Motion.crossfade(motion) },
            contentAlignment = Alignment.Center, label = "chat status") { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (shown) {
                    // Waiting is the one status that asks for the user: the amber status hue, as on the chat's activity row.
                    RowStatus.WAITING -> WaitingGlyph(waitingLabel)
                    RowStatus.WORKING -> Spinner(size = ControlSize.StatusGlyph, strokeWidth = ControlSize.StatusStroke)
                    RowStatus.IDLE -> IdleRing()
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(session.title.ifBlank { stringResource(R.string.mobile_untitled) }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SessionMeta(lead, work, statusColor, timestamp, muted)
        }
    }
}

private enum class RowStatus { WAITING, WORKING, IDLE }

/**
 * Second row line: `project · status ……… time`. The status is measured at full width and always shown whole;
 * the project ellipsizes to make room. If the project would drop below [MIN_LEAD] (large fonts, long statuses),
 * the status moves to its own line under `project ……… time` and may wrap there instead of being cut.
 */
@Composable
private fun SessionMeta(lead: String, status: String?, statusColor: Color, timestamp: String, muted: Color) {
    val body = MaterialTheme.typography.bodySmall
    // An ellipsized Text still reports the full width it was given; the separator follows the drawn ellipsis.
    val leadEnd = remember { IntArray(1) }
    Layout(contents = listOf(
        { Text(lead, style = body, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            onTextLayout = { leadEnd[0] = kotlin.math.ceil(it.getLineRight(0)).toInt() }) },
        { if (status != null) Text(" · ", style = body, color = muted, maxLines = 1) },
        { if (status != null) Text(status, style = body, color = statusColor) },
        { Text(timestamp, style = MaterialTheme.typography.labelSmall.tabular(), color = muted, maxLines = 1) },
    )) { (leadM, sepM, statusM, timeM), constraints ->
        val width = constraints.maxWidth
        val gap = Spacing.S.roundToPx()
        val time = timeM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = width))
        val room = (width - time.width - gap).coerceAtLeast(0)
        val statusPart = statusM.firstOrNull()
        if (statusPart == null) {
            val leadP = leadM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = room))
            val h = maxOf(leadP.height, time.height)
            return@Layout layout(width, h) {
                leadP.place(0, (h - leadP.height) / 2); time.place(width - time.width, (h - time.height) / 2)
            }
        }
        val sep = sepM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = room))
        val statusWide = statusPart.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val leadWide = leadM.first().maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val leadRoom = room - sep.width - statusWide
        if (leadRoom >= minOf(leadWide, MIN_LEAD.roundToPx())) {
            val leadP = leadM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = leadRoom))
            val statusP = statusPart.measure(androidx.compose.ui.unit.Constraints.fixedWidth(statusWide))
            val h = maxOf(leadP.height, statusP.height, time.height)
            layout(width, h) {
                leadP.place(0, (h - leadP.height) / 2)
                val x = minOf(leadP.width, leadEnd[0])
                sep.place(x, (h - sep.height) / 2)
                statusP.place(x + sep.width, (h - statusP.height) / 2)
                time.place(width - time.width, (h - time.height) / 2)
            }
        } else {
            val leadP = leadM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = room))
            val statusP = statusPart.measure(androidx.compose.ui.unit.Constraints(maxWidth = width))
            val first = maxOf(leadP.height, time.height)
            val spacing = Spacing.XXS.roundToPx()
            layout(width, first + spacing + statusP.height) {
                leadP.place(0, (first - leadP.height) / 2); time.place(width - time.width, (first - time.height) / 2)
                statusP.place(0, first + spacing)
            }
        }
    }
}

/** Shortest project fragment still worth showing beside a status (a word plus an ellipsis). */
private val MIN_LEAD = 72.dp

/** "Needs your decision": the question outline in the warning hue, sized so its circle matches the 14dp ring and spinner. */
@Composable
private fun WaitingGlyph(label: String) {
    Icon(MobileIcons.Question, label, Modifier.size(IconSize.Meta), tint = LocalMobileColors.current.warning)
}

/** Composer-shaped entry point: one tap opens project, preset and task text together. */
@Composable
private fun NewTaskBar(enabled: Boolean, onNewChat: () -> Unit) {
    val accents = LocalMobileColors.current
    val label = stringResource(R.string.mobile_new_chat)
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onNewChat)
            .padding(start = Spacing.Gutter, end = Spacing.S)
            .testTag("new_chat").semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mobile_new_task_hint), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(ControlSize.RoundAction).background(if (enabled) accents.action else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(MobileIcons.Plus, null, Modifier.size(IconSize.Action), tint = if (enabled) accents.onAction else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

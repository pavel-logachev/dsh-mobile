package dev.dshmobile.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.LocalMobileColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreen(state: MobileState, query: String, onQuery: (String) -> Unit, projectId: String?, onProject: (String?) -> Unit,
    onSelect: (String) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit,
    interactionBlocked: Boolean, pendingDelivery: @Composable () -> Unit) {
    val sessions = if (state.capabilities?.sessions == true) state.sessions else emptyList()
    val projects = remember(state.workspaces, sessions) { projectSummaries(state.workspaces, sessions) }
    val visible = remember(sessions, query, projectId) { filterSessions(sessions, projectId, query) }
    val locale = LocalConfiguration.current.locales[0]
    val now = remember(visible, locale, state.lastSyncedAt) { System.currentTimeMillis() }
    val groups = remember(visible, locale, now) { groupSessionsByDate(visible, now, locale = locale) }
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val list = rememberLazyListState()
    val expanded by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 64 } }
    val accents = LocalMobileColors.current
    val newChatLabel = stringResource(R.string.mobile_new_chat)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(MobileIcons.Mark, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.mobile_brand), style = MaterialTheme.typography.titleLarge)
                }
            }, actions = {
                Box(Modifier.widthIn(max = 190.dp)) { ConnectionPill(state, onConnection) }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                    Icon(MobileIcons.Settings, stringResource(R.string.mobile_settings_connection))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, floatingActionButton = {
            // The disabled semantics are intentional: no offline/new identity mutation shortcut.
            ExtendedFloatingActionButton(onClick = { if (canCreate(state) && !interactionBlocked) onNewChat() }, expanded = expanded,
                icon = { Icon(MobileIcons.Edit, null) }, text = { Text(stringResource(R.string.mobile_new_chat)) },
                containerColor = if (canCreate(state) && !interactionBlocked) accents.action else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (canCreate(state) && !interactionBlocked) accents.onAction else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("new_chat").semantics {
                    if (!canCreate(state) || interactionBlocked) disabled()
                    contentDescription = newChatLabel
                })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("home_screen")) {
            ConnectionIssueStrip(state, onRefresh)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            pendingDelivery()
            if (state.demo) Text(stringResource(R.string.mobile_demo_tag), Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("demo_label"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PullToRefreshBox(isRefreshing = state.busy && state.connection == ConnectionState.SYNCING, onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("chat_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, // Reserve the FAB plus its 16dp margin and extra scaled-label space; Scaffold already consumes bars.
                    bottom = 96.dp + navigationBottom + (24 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp)) {
                    // Filters scroll with the list so large landscape text cannot consume the row viewport.
                    item(key = "list-filters") {
                        Column {
                            OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
                                label = { Text(stringResource(R.string.mobile_search_chats)) },
                                leadingIcon = { Icon(MobileIcons.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(MobileIcons.Close, stringResource(R.string.mobile_clear_search)) } },
                                shape = CircleShape, colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("chat_search"))
                            LazyRow(Modifier.fillMaxWidth().testTag("project_filters"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = LazyItemKeys.ALL_PROJECTS) { ProjectChip(stringResource(R.string.mobile_all_projects), projectId == null, "project_all") { onProject(null) } }
                                items(projects, key = { LazyItemKeys.project(it.workspace.id) }) { project ->
                                    ProjectChip(stringResource(R.string.mobile_project_filter, project.workspace.name, project.chatCount), projectId == project.workspace.id,
                                        "project_filter_${project.workspace.id}") { onProject(project.workspace.id) }
                                }
                            }
                        }
                    }
                    if (visible.isEmpty()) item(key = LazyItemKeys.EMPTY_CHATS) {
                        EmptyState(
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
                            }), modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            action = {
                                if (query.isNotBlank()) TextButton(onClick = { onQuery("") }) { Text(stringResource(R.string.mobile_clear_search)) }
                                else Button(onClick = onNewChat, enabled = canCreate(state) && !interactionBlocked, modifier = Modifier.testTag("new_chat")) { Text(stringResource(R.string.mobile_new_chat)) }
                            })
                    }
                    groups.forEach { group ->
                        item(key = LazyItemKeys.section(group.section.name)) {
                            Text(stringResource(when (group.section) {
                                DateSection.TODAY -> R.string.mobile_today
                                DateSection.YESTERDAY -> R.string.mobile_yesterday
                                DateSection.THIS_WEEK -> R.string.mobile_this_week
                                DateSection.EARLIER -> R.string.mobile_earlier
                            }), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp).semantics { heading() },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(group.sessions, key = { LazyItemKeys.session(it.id) }, contentType = { "session" }) { session ->
                            SessionRow(session, workspaceName(state, session.workspaceId),
                                timestamp = sessionTimestamp(session.updatedAt, group.section, now, locale = locale),
                                readOnly = !session.canExecute || state.workspaces.none { it.id == session.workspaceId && it.canExecute },
                                enabled = !state.busy && !interactionBlocked && (state.connection == ConnectionState.ONLINE ||
                                    (state.connection in listOf(ConnectionState.OFFLINE, ConnectionState.DISCONNECTED) && state.snapshot?.session?.id == session.id)),
                                onClick = { onSelect(session.id) })
                        }
                    }
                    if (state.sessionsTruncated) item(key = LazyItemKeys.TRUNCATED_CHATS) {
                        Text(stringResource(R.string.mobile_sessions_truncated), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalMobileColors.current
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = colors.action, selectedLabelColor = colors.onAction),
        modifier = Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp).testTag(tag))
}

@Composable
private fun SessionRow(session: SessionSummary, project: String, timestamp: String, readOnly: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = if (session.running) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("session_${session.id}")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(MobileIcons.Chat, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.title.ifBlank { stringResource(R.string.mobile_untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$project · $timestamp", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (session.running || readOnly) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (session.running) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(MaterialTheme.colorScheme.primary, pulse = true)
                        Text(stringResource(R.string.mobile_working), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    if (readOnly) Text(stringResource(R.string.mobile_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

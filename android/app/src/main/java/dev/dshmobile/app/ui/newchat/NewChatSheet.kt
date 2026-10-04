package dev.dshmobile.app.ui.newchat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.components.canCreate
import dev.dshmobile.app.ui.home.projectSummaries
import dev.dshmobile.app.ui.theme.LocalMobileColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatSheet(state: MobileState, preferredProject: String?, onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    val projects = remember(state.workspaces, state.sessions) { projectSummaries(state.workspaces, state.sessions) }
    val initial = preferredProject?.takeIf { id -> projects.any { it.workspace.id == id && it.workspace.canExecute } }
        ?: projects.firstOrNull { it.workspace.canExecute }?.workspace?.id
    var workspace by rememberSaveable { mutableStateOf(initial) }
    var preset by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(projects, search) { projects.filter { it.workspace.name.contains(search.trim(), ignoreCase = true) } }
    val accents = LocalMobileColors.current
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.88f }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp).testTag("new_chat_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mobile_new_chat), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.mobile_projects), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (projects.size > 8) OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text(stringResource(R.string.mobile_search_projects)) }, leadingIcon = { Icon(MobileIcons.Search, null) },
                modifier = Modifier.fillMaxWidth().testTag("workspace_search"), shape = CircleShape)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 64.dp, max = 320.dp).testTag("workspace_picker")) {
                if (filtered.isEmpty()) item(key = LazyItemKeys.EMPTY_PROJECTS) { Text(stringResource(R.string.mobile_projects_unavailable), Modifier.padding(16.dp)) }
                items(filtered, key = { LazyItemKeys.project(it.workspace.id) }) { project ->
                    val item = project.workspace
                    val selected = workspace == item.id
                    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("workspace_${item.id}")
                            .selectable(selected = selected, enabled = item.canExecute, role = Role.RadioButton, onClick = { workspace = item.id })
                            .padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(MobileIcons.Folder, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (item.canExecute) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (item.canExecute) pluralStringResource(R.plurals.mobile_project_count, project.chatCount, project.chatCount) else stringResource(R.string.mobile_read_only),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected, onClick = null, enabled = item.canExecute)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                }
            }
            if (projects.none { it.workspace.canExecute }) Text(stringResource(R.string.mobile_no_executable_workspace), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mobile_workspace_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.presets.isNotEmpty()) {
                Text(stringResource(R.string.mobile_presets), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(Modifier.fillMaxWidth().testTag("preset_picker"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = LazyItemKeys.DEFAULT_PRESET) {
                        FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accents.action, selectedLabelColor = accents.onAction, selectedLeadingIconColor = accents.onAction), selected = preset == null, onClick = { preset = null }, label = { Text(stringResource(R.string.mobile_host_default), maxLines = 1) },
                            leadingIcon = { if (preset == null) Icon(MobileIcons.Check, null, Modifier.size(16.dp)) }, shape = CircleShape,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("preset_default"))
                    }
                    items(state.presets, key = { LazyItemKeys.preset(it.id) }) { item ->
                        FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accents.action, selectedLabelColor = accents.onAction, selectedLeadingIconColor = accents.onAction), selected = preset == item.id, onClick = { preset = item.id }, label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = CircleShape,
                            leadingIcon = { if (preset == item.id) Icon(MobileIcons.Check, null, Modifier.size(16.dp)) },
                            modifier = Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp).testTag("preset_${item.id}"))
                    }
                }
            }
            Button(onClick = { workspace?.let { onCreate(it, preset) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("create_chat"),
                enabled = canCreate(state) && state.workspaces.any { it.id == workspace && it.canExecute } && (preset == null || state.presets.any { it.id == preset }),
                shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = accents.action, contentColor = accents.onAction)) {
                Text(stringResource(R.string.mobile_create))
            }
        }
    }
}

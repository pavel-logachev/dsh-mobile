package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R

/** A bounded disclosure, not host-sized text above a weighted transcript. */
@Composable internal fun TurnFailureNotice(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    val detail = message.ifBlank { stringResource(R.string.mobile_turn_failed_unknown) }
    val text = stringResource(R.string.mobile_turn_failed, detail)
    val label = stringResource(R.string.mobile_error_details)
    Text(text, Modifier.fillMaxWidth().clickable { expanded = true }
        .padding(horizontal = 20.dp, vertical = 8.dp).testTag("turn_failure")
        .semantics { liveRegion = LiveRegionMode.Polite; onClick(label) { expanded = true; true } },
        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error)
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text(label) },
        text = { SelectionContainer { Text(text, Modifier.heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()).testTag("turn_failure_detail")) } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.mobile_error_close)) } })
}

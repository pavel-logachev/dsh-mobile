package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.ui.MobileIcons

@Composable
internal fun HistorySearchBar(query: String, onQuery: (String) -> Unit, count: Int, index: Int,
    onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, singleLine = true, label = { Text(stringResource(R.string.mobile_search_history)) },
            modifier = Modifier.fillMaxWidth().testTag("history_query"), trailingIcon = {
                IconButton(onClick = onClose) { Icon(MobileIcons.Close, stringResource(R.string.mobile_close_search)) }
            })
        Text(stringResource(R.string.mobile_search_loaded_only), style = MaterialTheme.typography.bodySmall)
        if (query.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 0) stringResource(R.string.mobile_search_no_matches) else stringResource(R.string.mobile_search_count, index + 1, count),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onPrevious, enabled = count > 0, modifier = Modifier.testTag("history_previous")) {
                Icon(MobileIcons.Back, stringResource(R.string.mobile_search_previous), Modifier.rotate(90f))
            }
            IconButton(onClick = onNext, enabled = count > 0, modifier = Modifier.testTag("history_next")) {
                Icon(MobileIcons.Back, stringResource(R.string.mobile_search_next), Modifier.rotate(270f))
            }
        }
    }
}

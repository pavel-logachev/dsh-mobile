package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.LazyItemKeys
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun ChatTimeline(snapshot: SessionSnapshot, pending: PendingCommand?, onCopy: (String) -> Unit, modifier: Modifier = Modifier,
    classify: suspend (List<ChatMessage>, List<ChatItem>) -> List<ChatItem> = { messages, previous ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { chatItems(messages, previous) }
    }) {
    val list = rememberLazyListState()
    var following by rememberSaveable { mutableStateOf(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) }
    var unread by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val unconfirmed = pending?.takeIf { it.kind == "send" && it.sessionId == snapshot.session.id && !it.text.isNullOrBlank() && snapshot.messages.none { message -> message.requestId == it.requestId } }
    // Classify off the composition thread; keep prior overlapping groups for stable identities.
    val projection by produceState<TimelineProjection?>(null, TimelineInputKey(snapshot.messages)) {
        val input = snapshot.messages
        val previous = value?.items.orEmpty()
        value = TimelineProjection(input, classify(input, previous))
    }
    val visible = visibleTimeline(snapshot.messages, projection)
    val loading = visible == null
    val timeline = visible.orEmpty()
    val messageCount = timeline.size + if (unconfirmed != null) 1 else 0
    val totalItems = messageCount + if (snapshot.hasMore) 1 else 0
    LaunchedEffect(list, threshold) {
        snapshotFlow {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            val nearBottom = last == null || (last.index == list.layoutInfo.totalItemsCount - 1 && last.offset + last.size - list.layoutInfo.viewportEndOffset <= threshold)
            list.isScrollInProgress to nearBottom
        }.distinctUntilChanged().collect { (scrolling, nearBottom) ->
            if (scrolling) { following = nearBottom; if (nearBottom) unread = false }
        }
    }
    LaunchedEffect(messageCount, timeline, loading) {
        if (loading) return@LaunchedEffect
        if (following && totalItems > 0) { list.scrollToItem(totalItems - 1); list.scrollBy(Float.MAX_VALUE) }
        else if (messageCount > 0) unread = true
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp).fillMaxSize().testTag("chat_timeline"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (snapshot.hasMore) item(key = LazyItemKeys.HISTORY_LIMIT) {
                Text(stringResource(R.string.mobile_history_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) item(key = "classification-loading") {
                CircularProgressIndicator(Modifier.size(24.dp).testTag("chat_classification_loading"))
            }
            if (!loading && timeline.isEmpty() && unconfirmed == null) item(key = LazyItemKeys.EMPTY_CONVERSATION) {
                Text(stringResource(R.string.mobile_empty_conversation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(timeline, key = { if (it is ChatItem.Activity) "activity:${it.id}" else LazyItemKeys.message(it.id) },
                contentType = { if (it is ChatItem.Activity) "activity" else "message" }) { item ->
                when (item) {
                    is ChatItem.Message -> MessageItem(item.message, onCopy)
                    is ChatItem.Activity -> AgentActivityGroup(item, onCopy)
                }
            }
            unconfirmed?.let { command -> item(key = LazyItemKeys.pending(command.requestId)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mobile_unconfirmed_message), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(command.text.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } }
        }
        if (unread && !following) FilledTonalButton(onClick = {
            following = true; unread = false
            scope.launch { if (list.layoutInfo.totalItemsCount > 0) { list.scrollToItem(list.layoutInfo.totalItemsCount - 1); list.scrollBy(Float.MAX_VALUE) } }
        }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("new_messages")) { Text(stringResource(R.string.mobile_new_messages)) }
    }
}

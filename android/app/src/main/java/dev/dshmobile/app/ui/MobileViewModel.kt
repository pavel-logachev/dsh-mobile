package dev.dshmobile.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dshmobile.app.ui.pairing.PairingInput
import dev.dshmobile.app.data.needsWatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MobileViewModel @JvmOverloads constructor(application: Application,
    repositoryFactory: ((kotlinx.coroutines.CoroutineScope) -> dev.dshmobile.app.data.MobileRepository)? = null,
) : AndroidViewModel(application) {
    // Explicitly admitted sends belong to the process, not Activity/ViewModel cancellation.
    // This bounded owner is retired after its last send drains when the ViewModel is cleared.
    private val sendScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    private var admittedSend: kotlinx.coroutines.Job? = null
    private var cleared = false
    private val ownsRepository = repositoryFactory != null
    private val repository = repositoryFactory?.invoke(sendScope) ?: dev.dshmobile.app.data.ApplicationMobileRepository.get(application)
    val state = repository.state
    internal val phone = dev.dshmobile.app.ui.chat.PhoneEditor(application, repository, viewModelScope)
    var modelCatalog by mutableStateOf<dev.dshmobile.app.model.ModelCatalog?>(null); private set
    var modelsLoading by mutableStateOf(false); private set
    var modelsFailed by mutableStateOf(false); private set
    var selectedModels by mutableStateOf<Map<String, dev.dshmobile.app.model.ModelSelection>>(emptyMap()); private set
    var phoneAction by mutableStateOf<String?>(null); private set
    var phoneError by mutableStateOf<String?>(null); private set
    var phoneErrorAction by mutableStateOf<String?>(null); private set
    var answeredQuestions by mutableStateOf<Map<String, dev.dshmobile.app.model.QuestionAnswer>>(emptyMap()); private set
    fun loadModels() {
        if (modelsLoading) return
        modelsLoading = true; modelsFailed = false
        viewModelScope.launch {
            try { modelCatalog = repository.models() }
            catch (_: Exception) { modelsFailed = true }
            finally { modelsLoading = false }
        }
    }
    private fun phoneMutation(key: String, operation: suspend () -> Unit, accepted: () -> Unit = {}) {
        if (interactionBlocked || !dev.dshmobile.app.model.PhoneRules.canMutate(state.value)) return
        interactionBlocked = true; phoneAction = key; phoneError = null; phoneErrorAction = null
        viewModelScope.launch {
            try {
                editorActions.withLock { operation() }
                // An unresolved answer has a delivery receipt, not proof of a failed submission.
                if (key.startsWith("answer:") && state.value.pending?.kind == "answer") Unit
                else if (state.value.pending == null && state.value.error == null) accepted() else phoneError = state.value.error ?: "command_uncertain"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { phoneError = "host_error" }
            finally { if (phoneError != null) phoneErrorAction = key; interactionBlocked = false; phoneAction = null }
        }
    }
    fun rename(title: String, accepted: () -> Unit) = phoneMutation("rename", { repository.rename(title) }, accepted)
    fun queueAction(id: String, action: String) = phoneMutation("queue:$id:$action", { repository.queueAction(id, action) })
    fun selectModel(selection: dev.dshmobile.app.model.ModelSelection, accepted: () -> Unit) {
        val session = state.value.snapshot?.session?.id ?: return
        phoneMutation("model", { repository.selectModel(selection) }) { selectedModels = selectedModels + (session to selection); accepted() }
    }
    fun answer(callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) {
        val session = state.value.snapshot?.session?.id ?: return
        phoneMutation("answer:$callId", { repository.answer(callId, answer) }) {
            answeredQuestions = (answeredQuestions + (dev.dshmobile.app.model.questionDraftKey(session, callId) to answer)).entries.toList().takeLast(32).associate { it.toPair() }
        }
    }
    fun saveAnswer(sessionId: String, callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) { viewModelScope.launch { repository.saveAnswerDraft(sessionId, callId, answer) } }
    fun skipQuestion(callId: String) = phoneMutation("answer:$callId", { repository.skipQuestion(callId) }) {}
    suspend fun loadImage(session: String, id: String) = repository.image(session, id)
    suspend fun loadFile(session: String, id: String) = repository.file(session, id)

    // Memory-only: invitation secrets never enter SavedState, logs, or Compose saveable state.
    private val pairingInput = PairingInput(viewModelScope, repository::pair)
    val invitation: String get() = pairingInput.invitation
    val importError: String? get() = pairingInput.importError
    val importingInvitation: Boolean get() = pairingInput.importing
    var deviceName by mutableStateOf("")
        private set
    var editorDraft by mutableStateOf("")
        private set
    private var editorSessionId: String? = null
    private var answerSessionId: String? = null
    private var pendingDraftWrites = 0
    private val editorActions = Mutex()
    var interactionBlocked by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { repository.restore() }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(state, repository.acceptedCreate) { current, accepted -> current to accepted }.collect { (current, accepted) ->
                if (current.error == "command_rejected" && current.pending == null) firstTaskContinuation = null
                val continuation = firstTaskContinuation
                if (continuation != null && accepted?.requestId == continuation.requestId && !current.busy && current.pending == null &&
                    current.snapshot?.session?.id == accepted.sessionId && !current.snapshotStale) {
                    admittedSend = sendScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        editorActions.withLock { continueFirstTask(continuation, accepted.sessionId) }
                    }
                }
            }
        }
        viewModelScope.launch {
            state.collect {
                if (it.paired) pairingInput.edit("")
                phone.restore(it)
                // A first task whose delivery was uncertain is settled by its receipt: only a trustworthy
                // rejection proves "not sent"; acceptance or explicit local abandonment just ends tracking.
                awaitingTaskIn?.let { (created, task) -> if (it.pending == null) {
                    awaitingTaskIn = null
                    if (it.error == "command_rejected") taskNotSent = taskNotSent + (created to task)
                } }
                val sessionId = it.snapshot?.session?.id
                if (sessionId != answerSessionId) { answeredQuestions = emptyMap(); answerSessionId = sessionId }
                if (sessionId != editorSessionId || it.pending != null || pendingDraftWrites == 0) {
                    editorSessionId = sessionId
                    editorDraft = it.draft
                }
            }
        }
    }

    fun editInvitation(value: String) { pairingInput.edit(value) }
    fun editDeviceName(value: String) { deviceName = value.take(80) }
    fun importInvitation(uri: Uri) {
        pairingInput.import {
            withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 65_536)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("unavailable")
                }.getOrNull()
            }
        }
    }
    fun supersedeInvitationImport() { pairingInput.supersedeImport() }
    internal fun pair(review: InvitationReview, defaultName: String) {
        val name = deviceName.trim().ifBlank { defaultName }
        pairingInput.pair(review, name)
    }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
    fun selectSession(id: String) {
        if (interactionBlocked) return
        firstTaskContinuation = null // Explicit navigation supersedes the pending first-task intent.
        interactionBlocked = true // Close the tap-to-repository-launch race immediately.
        viewModelScope.launch {
            try {
                editorActions.withLock { repository.selectSession(id) }
                if (pendingDraftWrites == 0) {
                    editorSessionId = state.value.snapshot?.session?.id
                    editorDraft = state.value.draft
                }
            } finally { interactionBlocked = false }
        }
    }
    fun leaveSession() {
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { repository.leaveSession() }
    }
    /** Task text typed in the New task sheet; in memory only, kept until it reaches a created chat's draft. */
    var newTaskText by mutableStateOf("")
        private set
    /**
     * Chats whose first task is known not to have been sent, with that exact text. The notice shows only while
     * the chat's draft still holds this text, so any later confirmed send (immediate or settled by receipt)
     * clears the draft and the notice with it; another chat's failure never hides this one.
     */
    private var taskNotSent by mutableStateOf<Map<String, String>>(emptyMap())
    fun taskNotSentIn(sessionId: String, draft: String): Boolean = taskNotSent[sessionId]?.let { it.isNotBlank() && it == draft } == true
    /** Session (and exact text) whose first task send is still uncertain; never resent, only observed. */
    private var awaitingTaskIn: Pair<String, String>? = null
    private data class FirstTaskContinuation(val requestId: String, val task: String)
    // Memory-only intent, not an outbox. Process death never restarts a possibly delivered send.
    private var firstTaskContinuation: FirstTaskContinuation? = null
    fun editNewTask(text: String) { if (text != newTaskText) firstTaskContinuation = null; newTaskText = text }

    /** Only the checked receipt identity can authorize this continuation, never a changed snapshot. */
    fun createSession(workspaceId: String, presetId: String?, task: String = "") {
        if (interactionBlocked || state.value.busy || state.value.pending != null) return
        val requestId = java.util.UUID.randomUUID().toString()
        firstTaskContinuation = task.takeIf { it.isNotBlank() }?.let { FirstTaskContinuation(requestId, it) }
        interactionBlocked = true
        viewModelScope.launch {
            try {
                editorActions.withLock {
                    repository.createSession(workspaceId, presetId, requestId)
                    if (state.value.pending == null && repository.acceptedCreate.value?.requestId != requestId) firstTaskContinuation = null
                }
            } finally { interactionBlocked = false }
        }
    }
    private suspend fun continueFirstTask(continuation: FirstTaskContinuation, created: String) {
        if (firstTaskContinuation != continuation || state.value.snapshot?.session?.id != created || state.value.pending != null) return
        // Consume BEFORE the first suspension/POST. Receipt repeats and an uncertain send cannot re-enter.
        firstTaskContinuation = null
        interactionBlocked = true
        try {
            repository.updateDraft(continuation.task)
            newTaskText = ""
            editorSessionId = created
            editorDraft = continuation.task
            val previous = state.value
            if (dev.dshmobile.app.model.MobileReducer.canSend(previous, continuation.task)) repository.sendMessage(continuation.task)
            when {
                dev.dshmobile.app.ui.chat.sendWasAccepted(previous, state.value, continuation.task) -> Unit
                state.value.pending != null -> awaitingTaskIn = created to continuation.task
                else -> taskNotSent = taskNotSent + (created to continuation.task)
            }
        } finally { interactionBlocked = false }
    }
    fun updateDraft(text: String) {
        if ((!state.value.snapshotStale && (interactionBlocked || state.value.busy)) || state.value.pending != null) return
        val origin = state.value.snapshot?.session?.id ?: return
        if (origin != editorSessionId) return
        editorDraft = text // Immediate editor feedback; disk persistence is asynchronous.
        pendingDraftWrites++
        viewModelScope.launch {
            try {
                editorActions.withLock {
                    if (state.value.snapshot?.session?.id == origin) repository.updateDraft(text)
                }
            } finally { pendingDraftWrites-- }
        }
    }
    var sendingSessionId by mutableStateOf<String?>(null); private set
    fun retryNotSent() {
        if (interactionBlocked || !state.value.sendNotSent || state.value.pending != null) return
        if (state.value.connection == dev.dshmobile.app.model.ConnectionState.ONLINE && !state.value.snapshotStale) sendMessage()
        else {
            val origin = state.value.snapshot?.session?.id ?: return
            interactionBlocked = true
            admittedSend = sendScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try { repository.refresh() } finally { interactionBlocked = false }
                if (!cleared && state.value.snapshot?.session?.id == origin && state.value.sendNotSent)
                    sendMessage() // Explicit user retry, never a reconnect-triggered outbox.
            }
        }
    }
    fun sendMessage(onAccepted: () -> Unit = {}) = sendMessageWithDelivery(null, onAccepted)
    fun sendMessageWithDelivery(delivery: String?, onAccepted: () -> Unit = {}) {
        val attachments = phone.inSession(state.value.snapshot?.session?.id)
        if (interactionBlocked || state.value.pending != null) return
        val origin = state.value.snapshot?.session?.id ?: return
        if (origin != editorSessionId) return
        if (!dev.dshmobile.app.model.MobileReducer.canSend(state.value, editorDraft, attachments.isNotEmpty())) {
            val text = editorDraft
            interactionBlocked = true
            admittedSend = sendScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try { editorActions.withLock { repository.markSendNotSent(origin, text) } }
                finally { interactionBlocked = false }
            }
            return
        }
        val text = editorDraft // Exact visible text, not a possibly delayed repository draft.
        sendingSessionId = origin // Immediate local progress, even while draft persistence waits.
        interactionBlocked = true // Navigation cannot retarget an admitted send.
        admittedSend = sendScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                editorActions.withLock {
                    if (state.value.snapshot?.session?.id == origin && phone.prepareSend(origin, attachments.map { it.attachment.localId })) {
                        val before = state.value
                        // Upload refresh precedes admission: exactly one send command/requestId is created.
                        repository.sendMessage(text, delivery, phone.inSession(origin).mapNotNull { it.attachment.uploadId })
                        if (dev.dshmobile.app.ui.chat.sendWasAccepted(before, state.value, text) || (state.value.pending == null && state.value.error == null && attachments.isNotEmpty())) {
                            phone.restore(state.value)
                            if (!cleared) onAccepted()
                        }
                    } else repository.markSendNotSent(origin, text)
                }
            } finally { sendingSessionId = null; interactionBlocked = false }
        }
    }
    fun cancelRun(onAdmitted: () -> Unit = {}) {
        if (interactionBlocked || !dev.dshmobile.app.ui.components.canCancel(state.value)) return
        interactionBlocked = true
        onAdmitted() // Feedback for the explicit stop request, not a claim that the host stopped.
        viewModelScope.launch {
            try { editorActions.withLock { repository.cancelRun() } }
            finally { interactionBlocked = false }
        }
    }
    fun resolvePending() { viewModelScope.launch { repository.resolvePending() } }
    fun abandonPending() { firstTaskContinuation = null; viewModelScope.launch { repository.abandonPending() } }
    fun forget() {
        firstTaskContinuation = null
        answeredQuestions = emptyMap()
        pairingInput.edit("")
        viewModelScope.launch {
            val context = getApplication<Application>()
            context.stopService(android.content.Intent(context, dev.dshmobile.app.data.NotificationService::class.java))
            dev.dshmobile.app.data.NotificationPosting.cancelAll(context)
            try { dev.dshmobile.app.data.NotificationStore(context).reset() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional notifications cannot prevent credential deletion. */ }
            finally { repository.forget() }
        }
    }
    private val notificationRequests = NotificationTargetRequests()
    private var notificationRead: kotlinx.coroutines.Job? = null
    var notificationSelecting by mutableStateOf(false); private set
    var notificationRevision by mutableStateOf(0L); private set
    var notificationSettings by mutableStateOf(false); private set
    fun openNotificationSettings() { cancelNotificationNavigation(); notificationSettings = true }
    fun consumeNotificationSettings() { notificationSettings = false }
    var notificationChatList by mutableStateOf(false); private set
    fun openNotificationChatList() { cancelNotificationNavigation(); notificationChatList = true }
    fun consumeNotificationChatList() { notificationChatList = false }
    var notificationChat by mutableStateOf<String?>(null); private set
    var notificationQuestion by mutableStateOf<String?>(null); private set
    fun cancelNotificationNavigation() {
        notificationRequests.next(); notificationRead?.cancel(); notificationRead = null
        notificationSelecting = false; notificationChat = null; notificationQuestion = null
    }
    fun openNotificationChat(device: String?, chat: String?, question: String? = null) {
        if (device == null || chat == null || !dev.dshmobile.app.data.validId(chat)) return
        cancelNotificationNavigation()
        val revision = notificationRequests.next()
        viewModelScope.launch {
            val host = dev.dshmobile.app.data.EncryptedStateStore(getApplication()).read().host
            if (notificationRequests.isCurrent(revision) && host != null && host.deviceId == device) {
                dev.dshmobile.app.data.notificationTrace("target_validated restored=${state.value.draftsRestored}")
                notificationChat = chat; notificationQuestion = question?.takeIf { dev.dshmobile.app.data.validId(it) }
                notificationSelecting = true; notificationRevision = revision
            }
        }
    }
    fun selectNotificationChat(target: String) {
        if (notificationChat != target || notificationRead?.isActive == true) return
        val revision = notificationRevision
        notificationSelecting = true
        notificationRead = viewModelScope.launch {
            dev.dshmobile.app.data.notificationTrace("target_select_begin")
            try {
                // Only a presentation read/observer is retired. Admitted mutations and drafts
                // keep their existing owners and admission lock.
                repository.leaveSession()
                editorActions.withLock {
                    if (notificationRequests.isCurrent(revision)) repository.selectSession(target)
                }
            } finally {
                dev.dshmobile.app.data.notificationTrace("target_select_end selected=${notificationTargetSelected(target, state.value)}")
                if (notificationRequests.isCurrent(revision)) notificationSelecting = false
            }
        }
    }
    fun consumeNotificationChat(target: String) {
        if (notificationChat == target && !notificationSelecting && notificationTargetSelected(target, state.value)) notificationChat = null
    }
    private val notificationResume = NotificationResume(viewModelScope, allowed = {
        val context = getApplication<Application>()
        val local = dev.dshmobile.app.data.NotificationStore(context).read()
        val host = dev.dshmobile.app.data.EncryptedStateStore(context).read().host
        if (!local.enabled || (!local.needsWatchService(System.currentTimeMillis()) && local.outbox.isEmpty())) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            manager.cancel(1); manager.deleteNotificationChannel("connection")
        }
        local.enabled && (local.needsWatchService(System.currentTimeMillis()) || local.outbox.isNotEmpty()) &&
            host?.deviceId == local.deviceId && dev.dshmobile.app.data.watchAlertsAllowed(context)
    }, start = {
        val context = getApplication<Application>()
        androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, dev.dshmobile.app.data.NotificationService::class.java))
    }, failed = {})
    fun foreground(active: Boolean) {
        notificationResume.foreground(active)
        viewModelScope.launch { repository.setForeground(active) }
    }
    override fun onCleared() {
        cleared = true
        sendScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                repository.setForeground(false) // Retire presentation reads, never the admitted POST.
                admittedSend?.join()
            } finally {
                if (ownsRepository) repository.close()
                sendScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
        super.onCleared()
    }
}

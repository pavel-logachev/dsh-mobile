package dev.dshmobile.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dshmobile.app.data.createMobileRepository
import dev.dshmobile.app.ui.pairing.PairingInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MobileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = createMobileRepository(application, viewModelScope)
    val state = repository.state

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
    private var pendingDraftWrites = 0
    private val editorActions = Mutex()
    var interactionBlocked by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { repository.restore() }
        viewModelScope.launch {
            state.collect {
                if (it.paired) pairingInput.edit("")
                val sessionId = it.snapshot?.session?.id
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
        if (interactionBlocked || state.value.busy) return
        interactionBlocked = true // Close the tap-to-repository-launch race immediately.
        viewModelScope.launch {
            try {
                editorActions.withLock { repository.selectSession(id) }
                editorSessionId = state.value.snapshot?.session?.id
                editorDraft = state.value.draft
            } finally { interactionBlocked = false }
        }
    }
    fun createSession(workspaceId: String, presetId: String?) {
        if (interactionBlocked || state.value.busy) return
        interactionBlocked = true
        viewModelScope.launch {
            try { editorActions.withLock { repository.createSession(workspaceId, presetId) } }
            finally { interactionBlocked = false }
        }
    }
    fun updateDraft(text: String) {
        if (interactionBlocked || state.value.busy || state.value.pending != null) return
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
    fun sendMessage(onAccepted: () -> Unit = {}) {
        if (interactionBlocked || !dev.dshmobile.app.model.MobileReducer.canSend(state.value, editorDraft)) return
        val origin = state.value.snapshot?.session?.id ?: return
        if (origin != editorSessionId) return
        val text = editorDraft // Exact visible text, not a possibly delayed repository draft.
        interactionBlocked = true // Navigation cannot retarget an admitted send.
        viewModelScope.launch {
            try {
                editorActions.withLock {
                    if (state.value.snapshot?.session?.id == origin) {
                        val before = state.value
                        repository.sendMessage(text)
                        if (dev.dshmobile.app.ui.chat.sendWasAccepted(before, state.value, text)) onAccepted()
                    }
                }
            } finally { interactionBlocked = false }
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
    fun abandonPending() { viewModelScope.launch { repository.abandonPending() } }
    fun forget() {
        pairingInput.edit("")
        viewModelScope.launch {
            val context = getApplication<Application>()
            context.stopService(android.content.Intent(context, dev.dshmobile.app.data.NotificationService::class.java))
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            dev.dshmobile.app.data.NotificationStore(context).update { dev.dshmobile.app.data.NotificationLocal() }
            repository.forget()
        }
    }
    var notificationChat by mutableStateOf<String?>(null)
        private set
    fun openNotificationChat(device: String?, chat: String?) {
        viewModelScope.launch {
            val host = dev.dshmobile.app.data.EncryptedStateStore(getApplication()).read().host
            if (host != null && host.deviceId == device && chat != null && dev.dshmobile.app.data.validId(chat)) notificationChat = chat
        }
    }
    fun consumeNotificationChat() { notificationChat = null }
    fun foreground(active: Boolean) { viewModelScope.launch {
        repository.setForeground(active)
        if (active) {
            val context = getApplication<Application>()
            runCatching {
                val local = dev.dshmobile.app.data.syncNotificationPreferences(context)
                val host = dev.dshmobile.app.data.EncryptedStateStore(context).read().host
                if (local.enabled && host?.deviceId == local.deviceId && context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                    androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, dev.dshmobile.app.data.NotificationService::class.java))
            }
        }
    } }
    override fun onCleared() {
        repository.close() // Observation only. Never cancels a host task.
        super.onCleared()
    }
}

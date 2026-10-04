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
    fun sendMessage() {
        if (interactionBlocked || state.value.busy || state.value.pending != null) return
        val origin = state.value.snapshot?.session?.id ?: return
        if (origin != editorSessionId) return
        val text = editorDraft // Exact visible text, not a possibly delayed repository draft.
        interactionBlocked = true // Navigation cannot retarget an admitted send.
        viewModelScope.launch {
            try {
                editorActions.withLock {
                    if (state.value.snapshot?.session?.id == origin) repository.sendMessage(text)
                }
            } finally { interactionBlocked = false }
        }
    }
    fun cancelRun() { viewModelScope.launch { repository.cancelRun() } }
    fun resolvePending() { viewModelScope.launch { repository.resolvePending() } }
    fun abandonPending() { viewModelScope.launch { repository.abandonPending() } }
    fun forget() {
        pairingInput.edit("")
        viewModelScope.launch { repository.forget() }
    }
    fun foreground(active: Boolean) { viewModelScope.launch { repository.setForeground(active) } }
    override fun onCleared() {
        repository.close() // Observation only. Never cancels a host task.
        super.onCleared()
    }
}

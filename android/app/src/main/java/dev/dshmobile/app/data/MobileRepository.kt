package dev.dshmobile.app.data

import dev.dshmobile.app.model.MobileState
import kotlinx.coroutines.flow.StateFlow

data class CreateAcceptance(val requestId: String, val sessionId: String)

interface MobileRepository {
    val state: StateFlow<MobileState>
    /** Only a checked accepted receipt publishes this identity; snapshots are never create proof. */
    val acceptedCreate: StateFlow<CreateAcceptance?>
    suspend fun restore()
    suspend fun pair(invitationJson: String, deviceName: String)
    /** Pair only the immutable invitation which the user reviewed, not mutable input text. */
    suspend fun pair(invitation: Invitation, deviceName: String)
    suspend fun refresh()
    /** Explicit user retry, never an automatic selection effect. Opens a fresh bounded recovery window. */
    suspend fun manualReconnect(sessionId: String? = null) {
        refresh()
        if (sessionId != null) selectSession(sessionId)
    }
    suspend fun selectSession(sessionId: String)
    /** Leave local observation/loading only; never cancel desktop work. */
    suspend fun leaveSession() {}
    suspend fun createSession(workspaceId: String, presetId: String? = null, requestId: String = java.util.UUID.randomUUID().toString())
    suspend fun updateDraft(text: String)
    suspend fun sendMessage(text: String)
    /** Refused local tap/preparation only: no network dispatch has been admitted. */
    suspend fun markSendNotSent(sessionId: String, text: String) {}
    suspend fun sendMessage(text: String, delivery: String?, uploadIds: List<String>) { sendMessage(text) }
    suspend fun queueAction(itemId: String, action: String) {}
    suspend fun rename(title: String) {}
    suspend fun selectModel(selection: dev.dshmobile.app.model.ModelSelection) {}
    suspend fun answer(callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) {}
    suspend fun skipQuestion(callId: String) {}
    suspend fun file(sessionId: String, attachmentId: String): ByteArray = byteArrayOf()
    suspend fun models(): dev.dshmobile.app.model.ModelCatalog = dev.dshmobile.app.model.ModelCatalog(emptyList())
    suspend fun upload(sessionId: String, attachment: dev.dshmobile.app.model.DraftAttachment, open: () -> java.io.InputStream, progress: (Int) -> Unit): dev.dshmobile.app.model.DraftAttachment = attachment
    suspend fun image(sessionId: String, attachmentId: String): ByteArray = byteArrayOf()
    suspend fun saveAttachments(sessionId: String, items: List<dev.dshmobile.app.model.DraftAttachment>) {}
    /** Apply source operations to current state under the admission lock, never a captured editor list. */
    suspend fun editAttachments(sessionId: String, add: List<dev.dshmobile.app.model.DraftAttachment> = emptyList(), remove: Set<String> = emptySet(),
        replace: dev.dshmobile.app.model.DraftAttachment? = null, expectedUploadId: String? = null) {}
    suspend fun saveAnswerDraft(sessionId: String, callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) {}
    suspend fun cancelRun()
    suspend fun resolvePending()
    /** Confirmed local escape only: inspect the host first; never sends or cancels anything. */
    suspend fun abandonPending()
    /** Erases local state only; does not claim to revoke the device on the host. */
    suspend fun forget()
    suspend fun setForeground(active: Boolean)
    /** Activity/ViewModel owners must not stop another visible owner's shared transport. */
    suspend fun setForeground(active: Boolean, owner: Any) { setForeground(active) }
    fun close()
}

package dev.dshmobile.app.data

import dev.dshmobile.app.model.MobileState
import kotlinx.coroutines.flow.StateFlow

interface MobileRepository {
    val state: StateFlow<MobileState>
    suspend fun restore()
    suspend fun pair(invitationJson: String, deviceName: String)
    /** Pair only the immutable invitation which the user reviewed, not mutable input text. */
    suspend fun pair(invitation: Invitation, deviceName: String)
    suspend fun refresh()
    suspend fun selectSession(sessionId: String)
    suspend fun createSession(workspaceId: String, presetId: String? = null)
    suspend fun updateDraft(text: String)
    suspend fun sendMessage(text: String)
    suspend fun cancelRun()
    suspend fun resolvePending()
    /** Confirmed local escape only: inspect the host first; never sends or cancels anything. */
    suspend fun abandonPending()
    /** Erases local state only; does not claim to revoke the device on the host. */
    suspend fun forget()
    suspend fun setForeground(active: Boolean)
    fun close()
}

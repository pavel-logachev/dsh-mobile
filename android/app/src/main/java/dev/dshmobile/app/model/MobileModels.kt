package dev.dshmobile.app.model

import kotlinx.serialization.Serializable

/** The only presentation model shared with the native UI. No credentials or raw host paths. */
enum class ConnectionState { DISCONNECTED, CONNECTING, SYNCING, ONLINE, OFFLINE, REVOKED, INCOMPATIBLE }

@Serializable
data class Workspace(val id: String, val name: String, val canExecute: Boolean)
@Serializable
data class Preset(val id: String, val name: String)
@Serializable
data class SessionSummary(
    val id: String, val title: String, val workspaceId: String, val updatedAt: Long,
    val running: Boolean, val canExecute: Boolean,
)
@Serializable
data class ChatMessage(
    val id: String, val role: String, val text: String, val createdAt: Long,
    val requestId: String? = null, val provisional: Boolean = false,
)
@Serializable
data class SessionSnapshot(
    val session: SessionSummary, val messages: List<ChatMessage>, val cursor: Long,
    val hasMore: Boolean, val activity: String, val notice: String? = null,
)
data class PendingCommand(
    val requestId: String, val kind: String, val sessionId: String?, val text: String?, val status: String,
)
@Serializable
data class MobileCapabilities(
    val sessions: Boolean, val textPrompt: Boolean, val cancel: Boolean, val liveSnapshots: Boolean,
    val attachments: Boolean, val questions: Boolean, val approvals: Boolean, val push: Boolean,
)

data class MobileState(
    val paired: Boolean = false,
    val remoteMode: Boolean = false,
    val relayHost: String? = null,
    val hostName: String = "",
    val capabilities: MobileCapabilities? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val workspaces: List<Workspace> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsTruncated: Boolean = false,
    val snapshot: SessionSnapshot? = null,
    val draft: String = "",
    val pending: PendingCommand? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long? = null,
    val demo: Boolean = false,
)

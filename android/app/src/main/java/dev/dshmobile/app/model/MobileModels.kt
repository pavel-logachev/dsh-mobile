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
    @Serializable(with = RunningSubagentsSerializer::class) val runningSubagents: Int = 0,
    val model: SessionModel? = null,
    val blank: Boolean? = null,
    val waitingForAnswer: Boolean = false,
)
@Serializable
data class ChatMessage(
    val id: String, val role: String, val text: String, val createdAt: Long,
    val requestId: String? = null, val provisional: Boolean = false, val kind: String? = null,
    val serviceText: String? = null,
    val delivery: String? = null,
    @Serializable(with = AttachmentRefsSerializer::class) val attachments: List<AttachmentRef> = emptyList(),
)
@Serializable
data class TurnFailure(val message: String, val code: String? = null)

@Serializable
data class SessionSnapshot(
    val session: SessionSummary, val messages: List<ChatMessage>, val cursor: Long,
    val hasMore: Boolean, val activity: String, val notice: String? = null,
    val failure: TurnFailure? = null,
    val activityDetail: ActivityDetail? = null,
    val pendingQueue: List<QueueItem> = emptyList(),
    val pendingQuestions: List<PendingQuestion> = emptyList(),
    val model: SessionModel? = null,
    val resolvedQuestions: List<ResolvedQuestion>? = null,
    @Serializable(with = RunningSubagentsSerializer::class) val runningSubagents: Int = 0,
)
@Serializable
data class ActivityDetail(val turnStartedAt: Long? = null, val tool: String? = null)
/** Explicit local-bubble actions, owned and serialized by the repository admission lock. */
data class LocalPromptActions(val dismiss: suspend (String) -> Unit, val resend: suspend (String) -> Unit)

data class PendingCommand(
    val requestId: String, val kind: String, val sessionId: String?, val text: String?, val status: String,
    val attachmentCount: Int = 0, val delivery: String? = null,
    val attachmentNames: List<String> = emptyList(),
)
@Serializable
data class MobileCapabilities(
    val sessions: Boolean, val textPrompt: Boolean, val cancel: Boolean, val liveSnapshots: Boolean,
    val attachments: Boolean, val questions: Boolean, val approvals: Boolean, val push: Boolean,
    val notifications: Boolean = false,
    val questionSkip: Boolean = false, val fileDownload: Boolean = false,
    val steer: Boolean = false, val queue: Boolean = false, val rename: Boolean = false, val models: Boolean = false,
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
    /** Last-known history is readable during resync, but never authorizes a mutation. */
    val snapshotStale: Boolean = false,
    /** Chat-route ownership, not transport liveness; a retained snapshot alone is not observation. */
    val observingSession: Boolean = false,
    val draft: String = "",
    val pending: PendingCommand? = null,
    /** Durable known-not-sent outcome for this chat's retained draft, not an outbox. */
    val sendNotSent: Boolean = false,
    /** Host-admitted prompts awaiting a canonical USER record; these are not unresolved mutations. */
    val acceptedPrompts: List<PendingCommand> = emptyList(),
    val localPromptActions: LocalPromptActions? = null,
    val busy: Boolean = false,
    val manualRefreshing: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long? = null,
    val demo: Boolean = false,
    /** True only after the complete encrypted draft record has been restored. */
    val draftsRestored: Boolean = false,
    val attachmentDrafts: Map<String, List<DraftAttachment>> = emptyMap(),
    /** Keys encode the host identity (sessionId, callId), never a bare callId. */
    val answerDrafts: Map<String, QuestionAnswer> = emptyMap(),
    /** Sources retained only while a durable send may need explicit retry/reconciliation. */
    val retainedAttachmentSources: List<DraftAttachment> = emptyList(),
)

/** Length-prefixed pair avoids collisions even when host IDs contain punctuation. */
internal fun questionDraftKey(sessionId: String, callId: String): String = "${sessionId.length}:$sessionId$callId"

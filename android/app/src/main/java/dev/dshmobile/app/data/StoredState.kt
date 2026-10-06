package dev.dshmobile.app.data

import dev.dshmobile.app.model.PendingCommand
import kotlinx.serialization.Serializable

@Serializable
internal class PairedHost(
    val endpoint: HostEndpoint,
    val deviceId: String,
    val deviceToken: String,
    val hostName: String,
)

/** One encrypted atomic record: admission ID, exact payload and draft cannot tear apart. */
@Serializable
internal data class StoredState(
    val host: PairedHost? = null,
    val selectedSessionId: String? = null,
    val drafts: Map<String, String> = emptyMap(),
    val pending: StoredCommand? = null,
    val acceptedPrompts: List<StoredCommand> = emptyList(),
)

@Serializable
internal data class StoredCommand(
    val requestId: String,
    val kind: String,
    val sessionId: String? = null,
    val text: String? = null,
    val workspaceId: String? = null,
    val presetId: String? = null,
    val expectedCursor: Long? = null,
    val status: String = "sending",
    val queuedWhileRunning: Boolean = false,
    val acceptedAt: Long? = null,
    val idleSnapshots: Int = 0,
) {
    fun presentation() = PendingCommand(requestId, kind, sessionId, text, status)
}

/** Durable device storage boundary. Implementations must commit complete records atomically. */
internal interface SecureStateStore {
    suspend fun read(): StoredState
    suspend fun write(value: StoredState)
    suspend fun clear()
}

package dev.dshmobile.app.model

/** Authoritative snapshots replace (never append to) durable and provisional history alike. */
object MobileReducer {
    fun replaceSnapshot(state: MobileState, snapshot: SessionSnapshot, syncedAt: Long): MobileState =
        state.copy(
            snapshot = snapshot,
            sessions = state.sessions.map { if (it.id == snapshot.session.id) snapshot.session else it },
            lastSyncedAt = syncedAt,
        )

    /** A non-provisional USER message with this ID is proof of prompt admission, not completion. */
    fun reconcilesPrompt(command: PendingCommand, snapshot: SessionSnapshot): Boolean =
        command.kind == "send" && command.sessionId == snapshot.session.id &&
            snapshot.messages.any { it.role == "user" && !it.provisional && it.requestId == command.requestId }
}

package dev.dshmobile.app.model

/** Authoritative snapshots replace (never append to) durable and provisional history alike. */
object MobileReducer {
    /** At most three blocking admitted prompts per chat, never across unrelated chats. */
    const val MAX_ACCEPTED_PROMPTS = 3

    const val ACCEPTED_PROMPT_TIMEOUT_MS = 10 * 60 * 1_000L

    fun blockingPromptCount(state: MobileState, sessionId: String? = state.snapshot?.session?.id): Int =
        state.acceptedPrompts.count { it.sessionId == sessionId && it.status in setOf("accepted", "queued") }

    fun canAdmitPrompt(state: MobileState): Boolean =
        state.pending == null && blockingPromptCount(state) < MAX_ACCEPTED_PROMPTS

    fun canSend(state: MobileState, draft: String): Boolean =
        state.connection == ConnectionState.ONLINE && state.capabilities?.textPrompt == true &&
            !state.busy && canAdmitPrompt(state) && draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= 32_768 &&
            state.snapshot?.let { snapshot ->
                snapshot.activity in setOf("idle", "running") && snapshot.session.canExecute &&
                    state.workspaces.any { it.id == snapshot.session.workspaceId && it.canExecute }
            } == true

    fun replaceSnapshot(state: MobileState, snapshot: SessionSnapshot, syncedAt: Long): MobileState =
        state.copy(
            snapshot = snapshot,
            acceptedPrompts = state.acceptedPrompts.filterNot { reconcilesPrompt(it, snapshot) },
            sessions = state.sessions.map { if (it.id == snapshot.session.id) snapshot.session else it },
            lastSyncedAt = syncedAt,
        )

    /** A non-provisional USER message with this ID is proof of prompt admission, not completion. */
    fun reconcilesPrompt(command: PendingCommand, snapshot: SessionSnapshot): Boolean =
        command.kind == "send" && command.sessionId == snapshot.session.id &&
            snapshot.messages.any { it.role == "user" && !it.provisional && it.requestId == command.requestId }
}

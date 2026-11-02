package dev.dshmobile.app.data

/** Process-local presentation state, never restored from disk or inferred from repository selection. */
internal class VisibleChat {
    private var owner: Any? = null
    private var session: String? = null

    @Synchronized fun show(owner: Any, session: String?) { this.owner = owner; this.session = session }
    @Synchronized fun clear(owner: Any) { if (this.owner === owner) { this.owner = null; session = null } }
    // Posting and lifecycle updates use one lock: visibility is decided at platform dispatch,
    // not when an alert enters the paced queue. No suspension is allowed in post.
    @Synchronized fun postUnlessVisible(session: String, post: () -> Unit) {
        if (this.session != session) post()
    }
}

internal val visibleChat = VisibleChat()

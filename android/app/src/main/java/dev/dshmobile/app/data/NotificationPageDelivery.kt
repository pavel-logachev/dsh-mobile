package dev.dshmobile.app.data

/** A page is acknowledged only after every display operation completed (or intentionally suppressed).
 * Replayed posts must use stable Android (tag,id) and event identity; a crash before commit is safe.
 * Policy edits are merged at commit, never replaced with the pre-post snapshot.
 */
internal suspend fun deliverNotificationPage(
    page: NotificationPage,
    now: Long,
    read: suspend () -> NotificationLocal,
    update: suspend ((NotificationLocal) -> NotificationLocal) -> NotificationLocal,
    post: suspend (NotificationEvent, NotificationLocal, Boolean) -> Unit,
    reset: suspend (NotificationLocal) -> Unit = {},
): NotificationLocal {
    val before = read()
    val applied = before.applyPage(page, now)
    if (applied.reset) {
        reset(before)
        for (pending in page.pending) post(NotificationEvent(1, pending.attentionId, 0, 0, Long.MAX_VALUE,
            pending.workspaceId, pending.sessionId, "attention-needed", -1, attentionId = pending.attentionId), applied.state, true)
    }
    for (event in applied.display) post(event, applied.state, false)
    return update { current ->
        if (!current.enabled || current.deviceId != before.deviceId) throw MobileFailure("not_paired")
        current.copy(epoch = applied.state.epoch, cursor = applied.state.cursor, lastSequence = applied.state.lastSequence,
            processed = applied.state.processed, attention = applied.state.attention)
    }
}

package dev.dshmobile.app.data

import android.content.Context

internal const val WATCH_TURN_EXTRA = "dev.dshmobile.watchTurn"

internal fun NotificationLocal.questionResolved(session: String, turn: String?): Boolean {
    val states = watchTurns.filter { it.sessionId == session &&
        (turn == null || it.turnId == turn || it.turnId == "turn:$turn") }
    return states.isNotEmpty() && states.none { it.waiting }
}

/** Opening a chat acknowledges only its question, never a result/error or another chat. */
internal fun cancelChatQuestionAlert(context: Context, session: String) {
    NotificationPosting.cancelQuestion(context) { shown ->
        val tag = shown.tag.orEmpty()
        tag == watchNotificationTag(tag.substringBefore(":phone-chat:"), session)
    }
}

/** Durable outcome state survives restart. Unknown does not change waiting in reconciliation. */
internal fun cancelResolvedQuestionAlerts(context: Context, local: NotificationLocal) {
    val device = local.deviceId ?: return
    NotificationPosting.cancelQuestion(context) { shown ->
        val turn = shown.notification.extras.getString(WATCH_TURN_EXTRA)
        local.watchTurns.any { state -> shown.tag == watchNotificationTag(device, state.sessionId) &&
            local.questionResolved(state.sessionId, turn) }
    }
}

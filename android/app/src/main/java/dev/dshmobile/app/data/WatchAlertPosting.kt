package dev.dshmobile.app.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.dshmobile.app.R
import dev.dshmobile.app.locale.appLanguageContext

/** Stable per-turn identity is also the crash-replay identity, independent of feed epochs. */
internal suspend fun postWatchAlert(context: Context, device: String, alert: WatchAlert, replay: Boolean) {
    if (!watchAlertsAllowed(context)) throw MobileFailure("notifications_blocked")
    val local = NotificationStore(context).read()
    if (!local.enabled || local.deviceId != device) return
    val strings = appLanguageContext(context)
    val attention = alert.kind == "attention"
    val title = strings.getString(when (alert.kind) {
        "attention" -> R.string.notif_needs_decision
        "error" -> R.string.notif_task_failed
        else -> R.string.notif_answer_ready
    })
    val chat = alert.title.ifBlank { strings.getString(R.string.notif_unnamed_chat) }
    val body = if (alert.kind == "error") "$chat · ${alert.summary.orEmpty().ifBlank { strings.getString(R.string.mobile_turn_failed_unknown) }}" else chat
    val channel = if (attention) "attention_alert_v2" else "answers_alert_v2"
    val tag = "$device:turn:${alert.key}"
    val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(notificationChatIntent(context, device, alert.sessionId, null))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(strings.getString(R.string.app_name)).setContentText(title).build())
        .setAutoCancel(true).setOnlyAlertOnce(replay).addExtras(alertLanguageExtras(if (alert.kind == "answer") "answers" else alert.kind, body)).build()
    NotificationPosting.paced {
        // No suspension between permission/policy guard and platform notify.
        NotificationStore(context).postIfCurrent(device, alert.key) {
            if (watchAlertsAllowed(context)) context.getSystemService(NotificationManager::class.java).notify(tag, 2, notification)
            else throw MobileFailure("notifications_blocked")
        }
    }
}

package dev.dshmobile.app.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.dshmobile.app.R
import dev.dshmobile.app.locale.appLanguageContext

/** Stable chat slot replaces waiting with its final result; durable phase keys dedupe delivery. */
internal suspend fun postWatchAlert(context: Context, device: String, alert: WatchAlert, replay: Boolean,
    store: NotificationStore = NotificationStore(context)) {
    if (!watchAlertsAllowed(context)) throw MobileFailure("notifications_blocked")
    val local = store.read()
    if (!local.enabled || local.deviceId != device) return
    val strings = appLanguageContext(context)
    val attention = alert.kind == "attention"
    val title = strings.getString(when (alert.kind) {
        "attention" -> R.string.notif_needs_decision
        "error" -> R.string.notif_task_failed
        else -> R.string.notif_answer_ready
    })
    val body = watchAlertBody(strings, alert.kind, alert.title, alert.summary)
    val channel = if (attention) "attention_alert_v2" else "answers_alert_v2"
    val tag = watchNotificationTag(device, alert.sessionId)
    val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(notificationChatIntent(context, device, alert.sessionId, null))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(strings.getString(R.string.app_name)).setContentText(title).build())
        .setAutoCancel(true).setOnlyAlertOnce(replay).addExtras(alertLanguageExtras(if (alert.kind == "answer") "answers" else alert.kind, alert.title, alert.summary)).build()
    NotificationPosting.paced {
        // No suspension between permission/policy guard and platform notify.
        store.postIfCurrent(device, alert.key) {
            if (watchAlertsAllowed(context)) context.getSystemService(NotificationManager::class.java).notify(tag, 2, notification)
            else throw MobileFailure("notifications_blocked")
        }
    }
}

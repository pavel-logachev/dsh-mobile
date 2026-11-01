package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import dev.dshmobile.app.R
import dev.dshmobile.app.locale.appLanguageContext
import kotlinx.coroutines.*

private const val ALERT_KIND = "dev.dshmobile.alertKind"
private const val ALERT_NAMES = "dev.dshmobile.alertNames"
internal fun alertLanguageExtras(kind: String, names: String?) = Bundle().apply {
    putString(ALERT_KIND, kind); putString(ALERT_NAMES, names)
}
internal fun refreshNotificationLanguage(context: Context): Job = NotificationPosting.refresh(context)
internal suspend fun refreshNotificationLanguagePass(context: Context) {
    try {
        val manager = context.getSystemService(NotificationManager::class.java)
        refreshNotificationChannelNames(appLanguageContext(context))
        repeat(3) {
            var changed = false
            for (shown in manager.activeNotifications) {
                currentCoroutineContext().ensureActive()
                if (translatedNotification(context, shown.id, shown.tag, shown.notification) == null) continue
                val ticket = NotificationPosting.ticket(shown.tag, shown.id)
                try {
                    NotificationPosting.paced {
                        val current = manager.activeNotifications.find { it.key == shown.key }
                        if (current != null) translatedNotification(context, current.id, current.tag, current.notification)?.let { translated ->
                            NotificationPosting.commit(ticket) { manager.notify(current.tag, current.id, translated); changed = true }
                        }
                    }
                } finally { NotificationPosting.release(ticket) }
            }
            refreshNotificationChannelNames(appLanguageContext(context))
            if (!changed) return
            delay(1200)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { NotificationPosting.logFailure(failure) }
}

/** Translate only watch notifications; keep identity, privacy, channel and tap destination. */
internal fun translatedNotification(context: Context, id: Int, tag: String?, old: Notification): Notification? {
    val strings = appLanguageContext(context)
    val previousTitle = old.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val previousBody = old.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val builder = Notification.Builder.recoverBuilder(context, old)
    if (id == 1 && tag == null && old.channelId == NotificationService.CONNECTION) {
        builder.setContentTitle(strings.getString(R.string.notif_watch_title)).setContentText(strings.getString(R.string.notif_watch_body))
    } else if (id == 2 && old.channelId in notificationChannels.map { it.id }) {
        val kind = old.extras.getString(ALERT_KIND) ?: return null
        val title = strings.getString(when (kind) { "answers" -> R.string.notif_answer_ready; "error" -> R.string.notif_task_failed; else -> R.string.notif_needs_decision })
        val names = old.extras.getString(ALERT_NAMES) ?: strings.getString(R.string.notif_unnamed_chat)
        val public = old.publicVersion?.let { Notification.Builder.recoverBuilder(context, it) }
            ?: Notification.Builder(context, old.channelId).setSmallIcon(old.smallIcon)
        builder.setContentTitle(title).setContentText(names)
            .setPublicVersion(public.setContentTitle(strings.getString(R.string.app_name)).setContentText(title).build())
    } else return null
    @Suppress("DEPRECATION")
    val updated = builder.setOnlyAlertOnce(true).setSound(null).setVibrate(null).setDefaults(0).build()
    return updated.takeUnless { previousTitle == it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() &&
        previousBody == it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
}

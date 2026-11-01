package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import dev.dshmobile.app.R
import dev.dshmobile.app.locale.appLanguageContext
import kotlinx.coroutines.*
import java.util.Locale

private const val ALERT_KIND = "dev.dshmobile.alertKind"
private const val ALERT_NAMES = "dev.dshmobile.alertNames"
private const val CONNECTION_STATUS = "dev.dshmobile.connectionStatus"

/** Only presentation inputs already visible in the notification; never message text or credentials. */
internal fun alertLanguageExtras(kind: String, names: String?) = Bundle().apply {
    putString(ALERT_KIND, kind)
    putString(ALERT_NAMES, names)
}
internal fun connectionLanguageExtras(status: String) = Bundle().apply { putString(CONNECTION_STATUS, status) }
@Suppress("UNUSED_PARAMETER")
internal fun connectionTitleResource(status: String) = R.string.notif_watch_title
@Suppress("UNUSED_PARAMETER")
internal fun connectionBodyResource(status: String) = R.string.notif_watch_body
internal fun connectionBody(context: Context, status: String): String = context.getString(connectionBodyResource(status))

/** Nonblocking entry point. A new request cancels/coalesces the old pass, not ordinary alert posting. */
internal fun refreshNotificationLanguage(context: Context): Job = NotificationPosting.refresh(context)

/** Three bounded passes; re-read actual platform state after enqueues settle, retry only stale renderings. */
internal suspend fun refreshNotificationLanguagePass(context: Context) {
    try {
        refreshNotificationLanguagePassChecked(context)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { NotificationPosting.logFailure(failure) }
}

private suspend fun refreshNotificationLanguagePassChecked(context: Context) {
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
                    // Never resurrect dismissed alerts or overwrite a newer event with an old snapshot.
                    val current = manager.activeNotifications.find { it.key == shown.key }
                    if (current != null) {
                        val translated = translatedNotification(context, current.id, current.tag, current.notification)
                        if (translated != null) {
                            NotificationPosting.commit(ticket) { manager.notify(current.tag, current.id, translated); changed = true }
                        }
                    }
                }
            } finally { NotificationPosting.release(ticket) }
        }
        refreshNotificationChannelNames(appLanguageContext(context))
        if (!changed) return
        delay(1200) // Android notify is asynchronous; verify committed renderings, not local builders.
    }
}


/** null means an unrelated or already translated notification. Does not post or change identity/channel. */
internal fun translatedNotification(context: Context, id: Int, tag: String?, old: Notification, silent: Boolean = true): Notification? {
    if (old.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
        if (id != 3 || tag != SUMMARY_TAG || old.group != ALERT_GROUP) return null
        val localized = appLanguageContext(context)
        val model = summaryModel(old)
        val title = summaryTitle(localized, model); val body = summaryBody(localized, model)
        if (old.extras.getString(Notification.EXTRA_TITLE) == title && old.extras.getString(Notification.EXTRA_TEXT) == body) return null
        return Notification.Builder.recoverBuilder(context, old).setContentTitle(title).setContentText(body).setOnlyAlertOnce(true).build()
    }
    val localized = appLanguageContext(context)
    // recoverBuilder can share extras with its input: capture old rendering BEFORE any mutation.
    val previousTitle = old.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val previousBody = old.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val previousPublic = old.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val previousActions = old.actions.orEmpty().map { it.title.toString() }
    val builder = Notification.Builder.recoverBuilder(context, old)
    if (id == 1 && tag == null && old.channelId == NotificationService.CONNECTION) {
        val status = "watching"
        builder.addExtras(connectionLanguageExtras(status))
            .setContentTitle(localized.getString(connectionTitleResource(status)))
            .setContentText(connectionBody(localized, status))
        builder.setActions()
    } else if (id == 2 && old.channelId in notificationChannels.map { it.id }) {
        val kind = old.extras.getString(ALERT_KIND) ?: when {
            tag?.endsWith(":answers") == true -> "answers"
            tag?.endsWith(":attention") == true -> "attention"
            else -> return null
        }
        val title = localized.getString(when (kind) { "answers" -> R.string.notif_answer_ready; "error" -> R.string.notif_task_failed; else -> R.string.notif_needs_decision })
        val names = if (old.extras.containsKey(ALERT_KIND)) old.extras.getString(ALERT_NAMES) else {
            val generic = listOf("en", "ru").any { language ->
                context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                }).getString(R.string.notif_generic) == previousBody
            }
            previousBody.takeUnless { generic }
        }
        val public = old.publicVersion?.let { Notification.Builder.recoverBuilder(context, it) }
            ?: Notification.Builder(context, old.channelId).setSmallIcon(old.smallIcon)
        if (old.extras.getBoolean("dev.dshmobile.questionAlert", false)) {
            if (old.extras.getBoolean("dev.dshmobile.unnamedChat", false)) {
                builder.setContentTitle(localized.getString(R.string.notif_unnamed_chat))
                    .setPublicVersion(public.setContentTitle(localized.getString(R.string.notif_unnamed_chat)).build())
            }
            builder.setActions(*old.actions.orEmpty().map { action -> Notification.Action.Builder(action.getIcon(), localized.getString(R.string.notif_answer), action.actionIntent).build() }.toTypedArray())
        } else builder.setContentTitle(title).setContentText(names ?: localized.getString(R.string.notif_open_chat_fallback))
            .setPublicVersion(public.setContentTitle(localized.getString(R.string.app_name)).setContentText(title).build())
            .addExtras(alertLanguageExtras(kind, names))
    } else return null
    @Suppress("DEPRECATION")
    val updated = if (silent) builder.setOnlyAlertOnce(true).setSound(null).setVibrate(null).setDefaults(0).build() else builder.build()
    val sameText = previousTitle == updated.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() &&
        previousBody == updated.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val samePublic = previousPublic == updated.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val sameActions = previousActions == updated.actions.orEmpty().map { it.title.toString() }
    return updated.takeUnless { sameText && samePublic && sameActions }
}

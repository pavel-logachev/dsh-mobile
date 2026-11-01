package dev.dshmobile.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.dshmobile.app.R

internal data class AlertChannel(val id: String, val attention: Boolean)
internal val notificationChannels = listOf(AlertChannel("answers_alert_v2", false), AlertChannel("attention_alert_v2", true))

/** Existing channel IDs preserve user-owned sound/importance. The app has one local switch;
 * retired feed modes and server preferences are not a second notification policy. */
internal fun refreshNotificationChannelNames(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    for (spec in notificationChannels) manager.getNotificationChannel(spec.id)?.let { channel ->
        channel.name = context.getString(if (spec.attention) R.string.notif_attention_channel else R.string.notif_answers_channel)
        manager.createNotificationChannel(channel)
    }
    manager.getNotificationChannel(NotificationService.CONNECTION)?.let { channel ->
        channel.name = context.getString(R.string.notif_watch_channel)
        manager.createNotificationChannel(channel)
    }
}
internal fun ensureNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    for (spec in notificationChannels) if (manager.getNotificationChannel(spec.id) == null) {
        manager.createNotificationChannel(NotificationChannel(spec.id,
            context.getString(if (spec.attention) R.string.notif_attention_channel else R.string.notif_answers_channel),
            NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE })
    }
    // Retire unused feed channels on upgrade, without resetting current user-owned channels.
    listOf("answers", "attention", "answers_silent_v2", "attention_silent_v2", "chat_summary_v1").forEach(manager::deleteNotificationChannel)
    refreshNotificationChannelNames(context)
}

package dev.dshmobile.app.data

import android.content.Context
import dev.dshmobile.app.BuildConfig

/** Read/policy only; never shares command/draft repository or retries any agent mutation. */
internal suspend fun syncNotificationPreferences(context: Context): NotificationLocal {
    val store = NotificationStore(context)
    val local = store.read()
    val host = EncryptedStateStore(context).read().host ?: return local
    if (local.deviceId != host.deviceId || !local.dirty) return local
    EndpointPolicy.validate(host.endpoint, BuildConfig.DEBUG)
    val api = HostApi(host.endpoint, host.deviceToken)
    try {
        val remote = api.notificationSettings()
        val settings = api.putNotificationSettings(reconcileNotificationSettings(local.settings, remote, true))
        return store.update { if (it == local) it.copy(settings = settings, dirty = false) else it }
    } finally { api.close() }
}

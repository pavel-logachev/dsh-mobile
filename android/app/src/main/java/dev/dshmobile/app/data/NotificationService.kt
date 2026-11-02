package dev.dshmobile.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.R
import dev.dshmobile.app.locale.appLanguageContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** A bounded phone-task owner, never an always-on connection/status monitor. */
class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + NotificationPosting.errors)
    private val store by lazy { NotificationStore(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var running: Job? = null
    private var policy: Job? = null
    private val reconnect = NotificationReconnect()
    private val changes = NotificationNetworkChanges { reconnect.networkChanged() }
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private var registered = false
    private val callback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = changes.available(network)
        override fun onLost(network: android.net.Network) = changes.lost(network)
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = changes.capabilities(network,
            listOf(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN), caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        ensureWatchChannel(this)
        // Remove the retired always-on notification/channel on upgrade, not just its text.
        manager.cancel(1)
        manager.deleteNotificationChannel("connection")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val local = try { runBlocking(Dispatchers.IO) { store.read().pruneWatches(System.currentTimeMillis()) } }
            catch (failure: Exception) { NotificationPosting.logFailure(failure); stopSelf(); return START_NOT_STICKY }
        if (!local.enabled || (!local.needsWatchService(System.currentTimeMillis()) && local.outbox.isEmpty()) || !watchAlertsAllowed(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        try {
            val notification = watchForegroundNotification(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
        } catch (failure: Exception) { NotificationPosting.logFailure(failure); stopSelf(); return START_NOT_STICKY }
        if (!registered) { connectivity.registerDefaultNetworkCallback(callback); registered = true }
        if (running?.isActive != true) running = scope.launch {
            try { reconnect.run { monitor(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        if (policy?.isActive != true) policy = scope.launch {
            var sessions = local.watches.map { it.sessionId to it.requestId }.toSet()
            store.changes.collectLatest {
                val current = store.read().pruneWatches(System.currentTimeMillis())
                if (!current.enabled || (current.watches.isEmpty() && current.outbox.isEmpty())) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else {
                    val active = current.watches.map { it.sessionId to it.requestId }.toSet()
                    if ((active - sessions).isNotEmpty()) reconnect.networkChanged()
                    sessions = active
                }
            }
        }
        reconnect.networkChanged() // Explicit foreground/resume/start cancels any held poll.
        return START_STICKY // Ordinary OS process recreation restores the encrypted watch set.
    }
    private suspend fun monitor() {
        val local = store.update { it.pruneWatches(System.currentTimeMillis()) }
        val host = EncryptedStateStore(this).read().host ?: return
        if (host.deviceId != local.deviceId || !watchAlertsAllowed(this)) return
        EndpointPolicy.validate(host.endpoint, BuildConfig.DEBUG)
        val transport = HostApi(host.endpoint, host.deviceToken)
        try {
            ensureNotificationChannels(appLanguageContext(this))
            val coordinator = WatchCoordinator(store::read, store::update, transport::promptOutcomes,
                post = { alert, replay -> postWatchAlert(this, host.deviceId, alert, replay) },
                clearResolved = { current -> cancelResolvedQuestionAlerts(this, current) })
            WatchPollLoop(store::update, coordinator,
                supported = { transport.capabilities().capabilities.promptOutcomes },
                online = { connectivity.activeNetwork != null }, allowed = { watchAlertsAllowed(this) }).run()
        } finally {
            // Cancellation joins physical transport retirement before reconnect admits a new poll.
            withContext(NonCancellable) { transport.closeAndAwait() }
        }
    }
    override fun onDestroy() {
        running?.cancel(); policy?.cancel(); scope.cancel()
        if (registered) connectivity.unregisterNetworkCallback(callback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val CONNECTION = "phone_task_watch_v1"
    }
}

internal fun watchAlertsAllowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
internal fun ensureWatchChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(NotificationService.CONNECTION,
        appLanguageContext(context).getString(R.string.notif_watch_channel), NotificationManager.IMPORTANCE_MIN).apply {
        setShowBadge(false); setSound(null, null); enableVibration(false)
    })
}
internal fun watchForegroundNotification(context: Context): android.app.Notification = NotificationCompat.Builder(context, NotificationService.CONNECTION)
    .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(appLanguageContext(context).getString(R.string.notif_watch_title))
    .setContentText(appLanguageContext(context).getString(R.string.notif_watch_body)).setContentIntent(notificationChatListIntent(context))
    .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_MIN)
    .setShowWhen(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()

/** Every entry point, including boot/package replacement and foreground resume, uses this guard. */
internal suspend fun resumePhoneWatches(context: Context) {
    val local = NotificationStore(context).read().pruneWatches(System.currentTimeMillis())
    if (!local.enabled || (local.watches.isEmpty() && local.outbox.isEmpty())) {
        // Upgrade/resume must remove the legacy notification even when no new service is needed.
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(1); manager.deleteNotificationChannel("connection")
        return
    }
    if (!watchAlertsAllowed(context)) return
    val host = EncryptedStateStore(context).read().host ?: return
    if (host.deviceId != local.deviceId) return
    ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
}

internal suspend fun registerPhoneWatch(context: Context, device: String, session: String, request: String, title: String) {
    NotificationStore(context).update { it.registerWatch(device, session, request, System.currentTimeMillis(), title) }
    // Called at explicit prompt admission while UI is visible. A failed FGS start does not erase
    // durable watches or alter command delivery; a later permitted resume can recover them.
    try { resumePhoneWatches(context) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { NotificationPosting.logFailure(failure) }
}

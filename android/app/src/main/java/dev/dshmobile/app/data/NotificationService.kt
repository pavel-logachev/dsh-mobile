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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random

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
            try { reconnect.run { monitor() } }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        if (policy?.isActive != true) policy = scope.launch {
            var sessions = local.watches.map { it.sessionId }.toSet()
            store.changes.collectLatest {
                val current = store.read().pruneWatches(System.currentTimeMillis())
                if (!current.enabled || (current.watches.isEmpty() && current.outbox.isEmpty())) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else {
                    val active = current.watches.map { it.sessionId }.toSet()
                    if (sessions != active) reconnect.networkChanged()
                    sessions = active
                }
            }
        }
        return START_STICKY // Ordinary OS process recreation restores the encrypted watch set.
    }
    private suspend fun monitor() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val local = store.update { it.pruneWatches(System.currentTimeMillis()) }
            if (!local.enabled || (local.watches.isEmpty() && local.outbox.isEmpty()) || !watchAlertsAllowed(this)) break
            val host = EncryptedStateStore(this).read().host ?: break
            if (host.deviceId != local.deviceId) break
            EndpointPolicy.validate(host.endpoint, BuildConfig.DEBUG)
            val transport = HostApi(host.endpoint, host.deviceToken)
            val observers = mutableListOf<okhttp3.sse.EventSource>()
            try {
                ensureNotificationChannels(appLanguageContext(this))
                // Open each existing authoritative session stream before GET. No notification
                // feed subscription/settings or host continuity status participates in recovery.
                val incoming = Channel<Result<Unit>>(Channel.CONFLATED)
                for (session in local.watches.map { it.sessionId }.distinct()) {
                    observers += transport.observe(session, { incoming.trySend(Result.success(Unit)) },
                        { failure -> incoming.trySend(Result.failure(failure)) })
                }
                val coordinator = WatchCoordinator(store::read, store::update, transport::snapshot,
                    post = { alert, replay -> postWatchAlert(this, host.deviceId, alert, replay) })
                coordinator.drain()
                coordinator.resync()
                if (!store.read().needsWatchService(System.currentTimeMillis())) break
                attempt = 0
                val expiry = store.read().watches.minOf { it.expiresAt }
                // A deadline enforces the promised watch TTL even on an otherwise silent socket.
                withTimeout((expiry - System.currentTimeMillis()).coerceAtLeast(1)) {
                    for (hint in incoming) {
                        hint.getOrThrow()
                        coordinator.resync()
                        if (!store.read().needsWatchService(System.currentTimeMillis())) return@withTimeout
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                store.update { it.pruneWatches(System.currentTimeMillis()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is MobileFailure && !notificationRetryable(failure.key)) break
                delay(notificationRetryDelay(attempt++, 0, Random.nextDouble(0.5, 1.0)))
            } finally { observers.forEach { it.cancel() }; transport.close() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        running?.cancel(); policy?.cancel(); scope.cancel()
        if (registered) connectivity.unregisterNetworkCallback(callback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val CONNECTION = "phone_task_watch_v1"
        const val ANSWERS = "answers"
        const val ATTENTION = "attention"
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

internal suspend fun registerPhoneWatch(context: Context, device: String, session: String, request: String) {
    NotificationStore(context).update { it.registerWatch(device, session, request, System.currentTimeMillis()) }
    // Called at explicit prompt admission while UI is visible. A failed FGS start does not erase
    // durable watches or alter command delivery; a later permitted resume can recover them.
    try { resumePhoneWatches(context) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { NotificationPosting.logFailure(failure) }
}

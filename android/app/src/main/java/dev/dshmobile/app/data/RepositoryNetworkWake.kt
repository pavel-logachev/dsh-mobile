package dev.dshmobile.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Process-owned registration, enabled only while the UI repository is foreground. No mutations. */
internal fun repositoryNetworkWake(context: Context): ((() -> Unit) -> (() -> Unit)) = { wake ->
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val diagnostic = ConnectionDiagnostic(foreground = { true }, network = connectionNetwork(context))
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { diagnostic.record("network-callback", "available"); wake() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { diagnostic.record("network-callback", "capabilities"); wake() }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            diagnostic.blocked = blocked
            diagnostic.record("network-callback", if (blocked) "blocked" else "unblocked")
        }
        override fun onLost(network: Network) { diagnostic.record("network-callback", "lost") }
    }
    manager.registerDefaultNetworkCallback(callback)
    val unregister: () -> Unit = { manager.unregisterNetworkCallback(callback) }
    unregister
}

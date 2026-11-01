package dev.dshmobile.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Process-owned registration, enabled only while the UI repository is foreground. No mutations. */
internal fun repositoryNetworkWake(context: Context): ((() -> Unit) -> (() -> Unit)) = { wake ->
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = wake()
    }
    manager.registerDefaultNetworkCallback(callback)
    val unregister: () -> Unit = { manager.unregisterNetworkCallback(callback) }
    unregister
}

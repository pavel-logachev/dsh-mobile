package dev.dshmobile.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** collectLatest cancels AND joins the old monitor (including preflight/backoff) before restart. */
internal class NotificationReconnect {
    private val generation = MutableStateFlow(0L)
    @Synchronized fun networkChanged() { generation.value++ }
    suspend fun run(monitor: suspend () -> Unit) { generation.collectLatest { monitor() } }
}

/** A stable VPN ID can change its underlying transport without onLost/onAvailable. */
internal class NotificationNetworkChanges(private val changed: () -> Unit) {
    private var network: Any? = null
    private var signature: Any? = null
    @Synchronized fun available(value: Any) {
        if (network != value) { network = value; signature = null; changed() }
    }
    @Synchronized fun lost(value: Any) {
        if (network == value) { network = null; signature = null; changed() }
    }
    @Synchronized fun capabilities(value: Any, route: Any) {
        if (network != value) { network = value; signature = route; changed() }
        else if (signature != route) { val previous = signature; signature = route; if (previous != null) changed() }
    }
}

package dev.dshmobile.app.ui

import kotlinx.coroutines.*

/** Activity STARTED owns pending FGS admission; synchronization never gates admission. */
internal class NotificationResume(private val scope: CoroutineScope, private val allowed: suspend () -> Boolean,
    private val start: () -> Unit, private val failed: suspend () -> Unit, private val sync: suspend () -> Unit) {
    private var active = false
    private var generation = 0L
    private var job: Job? = null
    fun foreground(value: Boolean) {
        active = value; val ticket = ++generation; job?.cancel()
        if (!value) return
        job = scope.launch {
            try {
                val enabled = allowed()
                ensureActive()
                if (!active || generation != ticket) return@launch
                if (enabled) start() // Main-thread non-suspending admission immediately after guard.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { runCatching { failed() } }
            if (active && generation == ticket) runCatching { sync() }
        }
    }
}

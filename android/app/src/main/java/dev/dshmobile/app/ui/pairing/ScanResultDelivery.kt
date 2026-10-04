package dev.dshmobile.app.ui.pairing

import java.util.concurrent.atomic.AtomicBoolean

/** Worker-to-lifecycle boundary; owns no payload beyond a single queued callback. */
internal class ScanResultDelivery(
    private val isActive: () -> Boolean,
    private val postToMain: (() -> Unit) -> Unit,
    private val stopCamera: () -> Unit,
    private val onResult: (InvitationScanResult) -> Unit,
) {
    private val ended = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    // Main-thread only and non-secret: startup errors can arrive before the owner resumes.
    private var pendingStatus: InvitationScanResult? = null
    val isEnded: Boolean get() = ended.get()

    fun deliver(result: InvitationScanResult) {
        if (!ended.compareAndSet(false, true)) return
        postToMain {
            if (disposed.get()) {
                stopCamera()
            } else if (!isActive()) {
                // Lifecycle already stops frames. Discard this secret and allow a fresh decode
                // if this same session resumes; a rotated owner gets an entirely new session.
                if (result is InvitationScanResult.Scanned) ended.set(false)
                else pendingStatus = result
            } else {
                stopCamera() // Camera is stopped before even an invalid payload reaches review.
                onResult(result)
            }
        }
    }

    fun resumed() {
        if (disposed.get() || !isActive()) return
        val status = pendingStatus ?: return
        pendingStatus = null
        stopCamera()
        onResult(status)
    }

    fun close() {
        disposed.set(true)
        ended.set(true)
        pendingStatus = null
    }
}

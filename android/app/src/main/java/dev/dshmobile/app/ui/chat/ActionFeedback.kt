package dev.dshmobile.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import dev.dshmobile.app.model.MobileState

/** Called only from explicit action completion, never from snapshot/stream observation. */
internal fun sendWasAccepted(before: MobileState, after: MobileState, submittedText: String): Boolean {
    val known = before.acceptedPrompts.map { it.requestId }.toSet() + before.snapshot?.messages.orEmpty().mapNotNull { it.requestId }
    return after.acceptedPrompts.any { it.sessionId == before.snapshot?.session?.id && it.text == submittedText && it.requestId !in known && it.status in setOf("accepted", "queued") } ||
        after.snapshot?.takeIf { it.session.id == before.snapshot?.session?.id }?.messages.orEmpty().any { it.role == "user" && it.text == submittedText && it.requestId != null && it.requestId !in known }
}

@Composable
internal fun rememberActionHaptic(): () -> Unit {
    val view = LocalView.current
    // No IGNORE_GLOBAL_SETTING / IGNORE_VIEW_SETTING flags and no Vibrator permission.
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); Unit } }
}

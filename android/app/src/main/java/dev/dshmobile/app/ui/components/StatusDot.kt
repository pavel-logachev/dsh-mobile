package dev.dshmobile.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.ui.theme.LocalMotionEnabled

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val opacity = if (pulse && LocalMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "task activity")
        val value by transition.animateFloat(0.45f, 1f,
            infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "running dot")
        value
    } else 1f
    Box(modifier.size(8.dp).alpha(opacity).background(color, CircleShape))
}

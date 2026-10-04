package dev.dshmobile.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.dshmobile.app.ui.theme.*

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    val setting = rememberThemeSetting()
    val dark = when (setting.value) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val activity = LocalContext.current.activity()
    SideEffect { activity?.window?.let {
        WindowCompat.getInsetsController(it, it.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    } }
    CompositionLocalProvider(LocalThemeSetting provides setting, LocalDarkTheme provides dark,
        LocalMotionEnabled provides rememberMotionEnabled(),
        LocalMobileColors provides if (dark) MobileColors() else MobileColors(warning = Color(0xFF79530E), warningContainer = Color(0xFFF7E7C5))) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
            typography = MobileTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
            content = content)
    }
}

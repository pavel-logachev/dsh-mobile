package dev.dshmobile.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFC5F04A), onPrimary = Color(0xFF14180A),
    primaryContainer = Color(0xFF2E3A12), onPrimaryContainer = Color(0xFFDDF59A),
    secondary = Color(0xFFBBC5B5), onSecondary = Color(0xFF20271D),
    secondaryContainer = Color(0xFF22282C), onSecondaryContainer = Color(0xFFE6E9E4),
    tertiary = Color(0xFFE6BC70), onTertiary = Color(0xFF382A10),
    background = Color(0xFF0F1214), onBackground = Color(0xFFE6E9E4),
    surface = Color(0xFF161A1D), onSurface = Color(0xFFE6E9E4),
    surfaceVariant = Color(0xFF22282C), onSurfaceVariant = Color(0xFF9AA39E),
    surfaceContainerLowest = Color(0xFF0F1214), surfaceContainerLow = Color(0xFF161A1D),
    surfaceContainer = Color(0xFF1B2024), surfaceContainerHigh = Color(0xFF22282C), surfaceContainerHighest = Color(0xFF2B3236),
    outline = Color(0xFF707B75), outlineVariant = Color(0xFF2C3338),
    error = Color(0xFFFF8A80), onError = Color(0xFF4A1210), errorContainer = Color(0xFF492522), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFE6E9E4), inverseOnSurface = Color(0xFF252A27), inversePrimary = Color(0xFF4E6A00), surfaceTint = Color(0xFFC5F04A),
)
internal val LightColors = lightColorScheme(
    primary = Color(0xFF4E6A00), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EFC6), onPrimaryContainer = Color(0xFF293600),
    secondary = Color(0xFF53604D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E9DE), onSecondaryContainer = Color(0xFF252C22),
    tertiary = Color(0xFF79530E), onTertiary = Color.White,
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF1A201B),
    surface = Color(0xFFF6F7F2), onSurface = Color(0xFF1A201B),
    surfaceVariant = Color(0xFFE5E9DE), onSurfaceVariant = Color(0xFF566054),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2EA),
    surfaceContainer = Color(0xFFECEFE5), surfaceContainerHigh = Color(0xFFE5E9DE), surfaceContainerHighest = Color(0xFFDCE2D5),
    outline = Color(0xFF737D6E), outlineVariant = Color(0xFFC6CEBE),
    error = Color(0xFFA9322A), onError = Color.White, errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF47110E),
    inverseSurface = Color(0xFF293129), inverseOnSurface = Color(0xFFF0F2EA), inversePrimary = Color(0xFFC5F04A), surfaceTint = Color(0xFF4E6A00),
)
internal data class MobileColors(val action: Color = Color(0xFFC5F04A), val onAction: Color = Color(0xFF14180A), val warning: Color = Color(0xFFE6BC70), val warningContainer: Color = Color(0xFF382E1B))
internal val LocalMobileColors = staticCompositionLocalOf { MobileColors() }

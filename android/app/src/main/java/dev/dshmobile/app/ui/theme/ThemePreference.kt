package dev.dshmobile.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal enum class ThemePreference { DARK, LIGHT, SYSTEM }
internal data class ThemeSetting(val value: ThemePreference, val update: (ThemePreference) -> Unit)
internal val LocalThemeSetting = staticCompositionLocalOf { ThemeSetting(ThemePreference.DARK) {} }
internal val LocalDarkTheme = staticCompositionLocalOf { true }
internal val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Nonsecret display preference only; deliberately separate from encrypted pairing/drafts. */
internal const val THEME_PREFERENCES = "mobile_appearance"
internal const val THEME_KEY = "theme"
internal fun readThemePreference(preferences: SharedPreferences): ThemePreference =
    ThemePreference.entries.firstOrNull { it.name == preferences.getString(THEME_KEY, null) } ?: ThemePreference.DARK

@Composable
internal fun rememberThemeSetting(): ThemeSetting {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences(THEME_PREFERENCES, Context.MODE_PRIVATE) }
    var value by remember(preferences) { mutableStateOf(readThemePreference(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == THEME_KEY) value = readThemePreference(preferences) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ThemeSetting(value) { option -> preferences.edit { putString(THEME_KEY, option.name) }; value = option }
}

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var result by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { result = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return result
}

package com.mote.app.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class MoteColorTokens(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceSecondary: Color,
    val border: Color,
    val divider: Color,
    val brandDark: Color,
    val primaryActionBg: Color,
    val primaryActionFg: Color,
    val accentBlue: Color,
    val accentEmerald: Color,
    val accentBlueBg: Color,
    val accentEmeraldBg: Color,
    val errorBg: Color,
    val errorBorder: Color,
    val errorTitle: Color,
    val errorBody: Color,
    val navGlassTop: Color,
    val navGlassBottom: Color,
    val navPillBg: Color,
    val floatingPanelBg: Color,
    val switchOffTrack: Color
)

val LightMoteColors = MoteColorTokens(
    isDark = false,
    canvas = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF8FAFC),
    surfaceElevated = Color(0xFFF1F5F9),
    onSurface = Color(0xFF0B0D1D),
    onSurfaceSecondary = Color(0xFF64748B),
    border = Color(0x160F172A),
    divider = Color(0xFFE2E8F0),
    brandDark = Color(0xFF0B0D1D),
    primaryActionBg = Color(0xFF0B0D1D),
    primaryActionFg = Color(0xFFFFFFFF),
    accentBlue = Color(0xFF1D4ED8),
    accentEmerald = Color(0xFF059669),
    accentBlueBg = Color(0xFFEFF6FF),
    accentEmeraldBg = Color(0xFFECFDF5),
    errorBg = Color(0xFFFEF2F2),
    errorBorder = Color(0xFFFECACA),
    errorTitle = Color(0xFFB91C1C),
    errorBody = Color(0xFF7F1D1D),
    navGlassTop = Color(0xF5FFFFFF),
    navGlassBottom = Color(0xECF1F5F9),
    navPillBg = Color(0xFFFFFFFF),
    floatingPanelBg = Color(0xFAFFFFFF),
    switchOffTrack = Color(0xFFD1D5DB)
)

val DarkMoteColors = MoteColorTokens(
    isDark = true,
    canvas = Color(0xFF090D16),
    surface = Color(0xFF111827),
    surfaceSubtle = Color(0xFF182235),
    surfaceElevated = Color(0xFF1F2A40),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceSecondary = Color(0xFF94A3B8),
    border = Color(0x2AE2E8F0),
    divider = Color(0xFF283548),
    brandDark = Color(0xFF0B0D1D),
    primaryActionBg = Color(0xFF2563EB),
    primaryActionFg = Color(0xFFFFFFFF),
    accentBlue = Color(0xFF60A5FA),
    accentEmerald = Color(0xFF34D399),
    accentBlueBg = Color(0xFF172554),
    accentEmeraldBg = Color(0xFF064E3B),
    errorBg = Color(0xFF3B1319),
    errorBorder = Color(0xFF7F1D1D),
    errorTitle = Color(0xFFFCA5A5),
    errorBody = Color(0xFFFECACA),
    navGlassTop = Color(0xF2141E30),
    navGlassBottom = Color(0xEB0E1525),
    navPillBg = Color(0xFF22304A),
    floatingPanelBg = Color(0xF7111827),
    switchOffTrack = Color(0xFF334155)
)

private val MoteLightMaterialScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF0B0D1D),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFDF5),
    onSecondaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF0B0D1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0D1D),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0x330F172A),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val MoteDarkMaterialScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B0D1D),
    primaryContainer = Color(0xFF172554),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF090D16),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1F2A40),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0x3BE2E8F0),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

val LocalMoteColors = staticCompositionLocalOf { LightMoteColors }

@Composable
fun MoteTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val tokens = if (darkTheme) DarkMoteColors else LightMoteColors
    val m3Scheme = if (darkTheme) MoteDarkMaterialScheme else MoteLightMaterialScheme
    CompositionLocalProvider(LocalMoteColors provides tokens) {
        MaterialTheme(
            colorScheme = m3Scheme,
            content = content
        )
    }
}

/**
 * Lightweight process-wide synchronous theme store backed by SharedPreferences
 * (mirrored with Room's [com.mote.app.data.db.ModelStudioPrefsEntity.darkThemeEnabled])
 * so MainActivity, AuthScreen, FloatingAssistantView, and HybridInferenceActivity
 * all render in the selected theme immediately without startup flicker.
 */
object ThemePreferenceStore {
    private const val PREFS_NAME = "mote_theme_prefs"
    private const val KEY_DARK_THEME = "dark_theme_enabled"

    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context): Boolean {
        if (!initialized) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isDarkTheme.value = prefs.getBoolean(KEY_DARK_THEME, false)
            initialized = true
        }
        return _isDarkTheme.value
    }

    fun setDarkTheme(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _isDarkTheme.value = enabled
        initialized = true
    }
}

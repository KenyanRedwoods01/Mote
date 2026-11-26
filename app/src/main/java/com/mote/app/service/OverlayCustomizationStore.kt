package com.mote.app.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OverlayOrbTheme(
    val id: String,
    val label: String,
    val primaryHex: Long,
    val secondaryHex: Long,
    val accentHex: Long
) {
    AURORA("aurora", "Aurora", 0xFF38BDF8L, 0xFF818CF8L, 0xFFF472B6L),
    EMERALD("emerald", "Emerald", 0xFF10B981L, 0xFF34D399L, 0xFF2DD4BFL),
    CYBER("cyber", "Cyber", 0xFFF59E0BL, 0xFFEC4899L, 0xFF8B5CF6L),
    SUNSET("sunset", "Sunset", 0xFFFB7185L, 0xFFF97316L, 0xFFFACC15L),
    MINIMAL("minimal", "Minimal", 0xFFE2E8F0L, 0xFF94A3B8L, 0xFF38BDF8L);

    companion object {
        fun fromId(id: String?): OverlayOrbTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AURORA
    }
}

data class OverlayCustomizationConfig(
    val orbTheme: OverlayOrbTheme = OverlayOrbTheme.AURORA,
    val orbSizeDp: Int = 72,
    val orbOpacity: Float = 0.96f,
    val showCursorHover: Boolean = true,
    val cursorAccentHex: Long = 0xFF38BDF8L,
    val cursorGlideMs: Long = 320L,
    val showLiveQuickWidgets: Boolean = true,
    val showFloatingStatusPill: Boolean = true
)

/**
 * Shared persistent customization store for Mote's floating assistant overlay,
 * live cursor hover visualizer, and chat quick-control widgets.
 */
object OverlayCustomizationStore {
    private const val PREFS_NAME = "mote_overlay_customization"
    private const val KEY_THEME = "orb_theme"
    private const val KEY_SIZE_DP = "orb_size_dp"
    private const val KEY_OPACITY = "orb_opacity"
    private const val KEY_SHOW_CURSOR = "show_cursor_hover"
    private const val KEY_CURSOR_ACCENT = "cursor_accent_hex"
    private const val KEY_CURSOR_GLIDE_MS = "cursor_glide_ms"
    private const val KEY_SHOW_WIDGETS = "show_live_quick_widgets"
    private const val KEY_SHOW_PILL = "show_floating_status_pill"
    private const val KEY_OVERLAY_X = "overlay_pos_x"
    private const val KEY_OVERLAY_Y = "overlay_pos_y"

    private val _config = MutableStateFlow(OverlayCustomizationConfig())
    val config: StateFlow<OverlayCustomizationConfig> = _config.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _config.value = OverlayCustomizationConfig(
                orbTheme = OverlayOrbTheme.fromId(prefs.getString(KEY_THEME, OverlayOrbTheme.AURORA.id)),
                orbSizeDp = prefs.getInt(KEY_SIZE_DP, 72).coerceIn(56, 88),
                orbOpacity = prefs.getFloat(KEY_OPACITY, 0.96f).coerceIn(0.55f, 1.0f),
                showCursorHover = prefs.getBoolean(KEY_SHOW_CURSOR, true),
                cursorAccentHex = prefs.getLong(KEY_CURSOR_ACCENT, 0xFF38BDF8L),
                cursorGlideMs = prefs.getLong(KEY_CURSOR_GLIDE_MS, 320L).coerceIn(180L, 650L),
                showLiveQuickWidgets = prefs.getBoolean(KEY_SHOW_WIDGETS, true),
                showFloatingStatusPill = prefs.getBoolean(KEY_SHOW_PILL, true)
            )
            loaded = true
        }
    }

    fun update(context: Context, transform: (OverlayCustomizationConfig) -> OverlayCustomizationConfig) {
        ensureLoaded(context)
        val next = transform(_config.value).let {
            it.copy(
                orbSizeDp = it.orbSizeDp.coerceIn(56, 88),
                orbOpacity = it.orbOpacity.coerceIn(0.55f, 1.0f),
                cursorGlideMs = it.cursorGlideMs.coerceIn(180L, 650L)
            )
        }
        _config.value = next
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, next.orbTheme.id)
            .putInt(KEY_SIZE_DP, next.orbSizeDp)
            .putFloat(KEY_OPACITY, next.orbOpacity)
            .putBoolean(KEY_SHOW_CURSOR, next.showCursorHover)
            .putLong(KEY_CURSOR_ACCENT, next.cursorAccentHex)
            .putLong(KEY_CURSOR_GLIDE_MS, next.cursorGlideMs)
            .putBoolean(KEY_SHOW_WIDGETS, next.showLiveQuickWidgets)
            .putBoolean(KEY_SHOW_PILL, next.showFloatingStatusPill)
            .apply()
    }

    fun loadSavedPosition(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val x = prefs.getInt(KEY_OVERLAY_X, defaultX)
        val y = prefs.getInt(KEY_OVERLAY_Y, defaultY)
        return x to y
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }

    fun resetPosition(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_OVERLAY_X)
            .remove(KEY_OVERLAY_Y)
            .apply()
    }
}

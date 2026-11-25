package com.mote.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Centralized manager delivering distinct Android VibrationEffect haptic responses
 * for FloatingAssistantView expansion, collapse, drag, dismiss, and voice gestures.
 */
class HapticFeedbackManager(
    context: Context,
    private val fallbackHaptics: HapticFeedback,
    var enabled: Boolean = true
) {
    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    fun onExpand() {
        if (!enabled) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0L, 14L, 26L, 22L), intArrayOf(0, 125, 0, 205), -1)
        }
        vibrateOrFallback(effect, HapticFeedbackType.LongPress)
    }

    fun onCollapse() {
        if (!enabled) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(14L, 95)
        }
        vibrateOrFallback(effect, HapticFeedbackType.TextHandleMove)
    }

    fun onOrbGrab() {
        if (!enabled) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(24L, 185)
        }
        vibrateOrFallback(effect, HapticFeedbackType.LongPress)
    }

    fun onDismissZoneLock() {
        if (!enabled) return
        vibrateOrFallback(VibrationEffect.createOneShot(32L, 230), HapticFeedbackType.LongPress)
    }

    fun onVoiceStart() {
        if (!enabled) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(18L, 155)
        }
        vibrateOrFallback(effect, HapticFeedbackType.TextHandleMove)
    }

    fun onVoiceStop() {
        if (!enabled) return
        onCollapse()
    }

    private fun vibrateOrFallback(effect: VibrationEffect, fallbackType: HapticFeedbackType) {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            fallbackHaptics.performHapticFeedback(fallbackType)
            return
        }
        try {
            v.vibrate(effect)
        } catch (_: SecurityException) {
            fallbackHaptics.performHapticFeedback(fallbackType)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(appContext: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

@Composable
fun rememberHapticFeedbackManager(enabled: Boolean = true): HapticFeedbackManager {
    val context = LocalContext.current
    val fallback = LocalHapticFeedback.current
    return remember(context, fallback) {
        HapticFeedbackManager(context, fallback, enabled)
    }.apply {
        this.enabled = enabled
    }
}

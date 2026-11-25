package com.mote.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

enum class AssistantHapticCue {
    EXPAND,
    COLLAPSE,
    GRAB,
    DISMISS_LOCK,
    VOICE_TOGGLE
}

/**
 * Provides distinct tactile waveforms for FloatingAssistantView interactions
 * (expand, collapse, grab, dismiss lock, and voice toggle) with Compose fallback.
 */
fun triggerAssistantHaptic(
    context: Context,
    fallbackHaptics: HapticFeedback,
    cue: AssistantHapticCue
) {
    val vibrator = resolveVibrator(context)
    if (vibrator == null || !vibrator.hasVibrator()) {
        val type = when (cue) {
            AssistantHapticCue.EXPAND, AssistantHapticCue.GRAB, AssistantHapticCue.DISMISS_LOCK ->
                HapticFeedbackType.LongPress
            AssistantHapticCue.COLLAPSE, AssistantHapticCue.VOICE_TOGGLE ->
                HapticFeedbackType.TextHandleMove
        }
        fallbackHaptics.performHapticFeedback(type)
        return
    }

    val effect = when (cue) {
        AssistantHapticCue.EXPAND -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0L, 14L, 28L, 22L), intArrayOf(0, 120, 0, 200), -1)
            }
        }
        AssistantHapticCue.COLLAPSE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(14L, 95)
            }
        }
        AssistantHapticCue.GRAB -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(24L, 185)
            }
        }
        AssistantHapticCue.DISMISS_LOCK -> {
            VibrationEffect.createOneShot(32L, 230)
        }
        AssistantHapticCue.VOICE_TOGGLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(18L, 150)
            }
        }
    }

    try {
        vibrator.vibrate(effect)
    } catch (_: SecurityException) {
        fallbackHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

@Suppress("DEPRECATION")
private fun resolveVibrator(context: Context): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

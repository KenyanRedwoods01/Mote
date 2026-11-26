package com.mote.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mote.app.ai.ToolChainExecutionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceRelayPhase(val label: String) {
    IDLE("Idle"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    PAUSED("Paused")
}

data class VoiceRelayTurn(
    val id: String,
    val speaker: String, // "Caller / Mic" or "Mote Voice AI"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class LiveVoiceRelayUiState(
    val isActive: Boolean = false,
    val phase: VoiceRelayPhase = VoiceRelayPhase.IDLE,
    val targetLabel: String = "Voice Call Relay",
    val rmsLevel: Float = 0f,
    val partialTranscript: String = "",
    val lastSpokenReply: String = "",
    val statusNote: String = "Ready for real-time STT -> AI -> TTS voice relay",
    val errorMessage: String? = null,
    val turns: List<VoiceRelayTurn> = emptyList()
)

data class SystemOverlayHudState(
    val isServiceRunning: Boolean = false,
    val isWindowManagerOverlayActive: Boolean = false,
    val isAccessibilityOverlayActive: Boolean = false,
    val overlayHostMode: String = "Inactive",
    val foregroundPackage: String? = null,
    val isHiddenForPrivacy: Boolean = false,
    val activeTargetApp: String? = null,
    val activeStatusBanner: String? = null,
    val isExecutingChain: Boolean = false,
    val allVerified: Boolean = false,
    val activeChain: ToolChainExecutionSnapshot? = null,
    val voiceRelay: LiveVoiceRelayUiState = LiveVoiceRelayUiState(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Shared real-time state bus connecting the Tool Chain Orchestrator, Device Automation Tools,
 * Live Voice Call Relay, [BotOverlayService] (`TYPE_APPLICATION_OVERLAY`), and
 * [AccessibilityFloatingWindowManager] (`TYPE_ACCESSIBILITY_OVERLAY`).
 */
object SystemOverlayStateBus {

    private val _state = MutableStateFlow(SystemOverlayHudState())
    val state: StateFlow<SystemOverlayHudState> = _state.asStateFlow()

    @Volatile
    private var stopVoiceRelayCallback: (() -> Unit)? = null

    @Volatile
    private var togglePauseVoiceRelayCallback: (() -> Unit)? = null

    fun registerVoiceRelayControls(
        onStop: () -> Unit,
        onTogglePause: () -> Unit
    ) {
        stopVoiceRelayCallback = onStop
        togglePauseVoiceRelayCallback = onTogglePause
    }

    fun requestStopVoiceRelayFromOverlay() {
        stopVoiceRelayCallback?.invoke()
    }

    fun requestTogglePauseVoiceRelayFromOverlay() {
        togglePauseVoiceRelayCallback?.invoke()
    }

    fun markServiceRunning(running: Boolean) {
        _state.update { cur ->
            val anyActive = running || cur.isAccessibilityOverlayActive
            cur.copy(
                isWindowManagerOverlayActive = running,
                isServiceRunning = anyActive,
                overlayHostMode = resolveHostLabel(cur.isAccessibilityOverlayActive, running),
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun markAccessibilityOverlayRunning(running: Boolean) {
        _state.update { cur ->
            val anyActive = running || cur.isWindowManagerOverlayActive
            cur.copy(
                isAccessibilityOverlayActive = running,
                isServiceRunning = anyActive,
                overlayHostMode = resolveHostLabel(running, cur.isWindowManagerOverlayActive),
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun publishForegroundPackage(pkg: String?, hiddenForPrivacy: Boolean) {
        _state.update {
            it.copy(
                foregroundPackage = pkg,
                isHiddenForPrivacy = hiddenForPrivacy,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun publishAppContext(appLabel: String, statusBanner: String) {
        _state.update {
            it.copy(
                activeTargetApp = appLabel,
                activeStatusBanner = statusBanner,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun publishChainProgress(snapshot: ToolChainExecutionSnapshot) {
        val activeStep = snapshot.steps.firstOrNull {
            it.status == com.mote.app.ai.ToolStepStatus.RUNNING
        }
        val banner = when {
            activeStep != null -> "Step ${activeStep.stepIndex}/${snapshot.steps.size}: ${activeStep.operationLabel}"
            snapshot.allVerified -> "Verified (${snapshot.verifiedCount}/${snapshot.steps.size}) · ${snapshot.chainTitle}"
            snapshot.isComplete -> "Chain finished (${snapshot.verifiedCount}/${snapshot.steps.size} verified)"
            else -> snapshot.chainTitle
        }
        _state.update {
            it.copy(
                activeChain = snapshot,
                activeStatusBanner = banner,
                isExecutingChain = !snapshot.isComplete,
                allVerified = snapshot.allVerified,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun publishVoiceRelayState(relayState: LiveVoiceRelayUiState) {
        _state.update {
            it.copy(
                voiceRelay = relayState,
                activeStatusBanner = if (relayState.isActive) {
                    "${relayState.targetLabel} · ${relayState.phase.label}"
                } else {
                    it.activeStatusBanner
                },
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun clearBanner() {
        _state.update {
            it.copy(
                activeStatusBanner = null,
                isExecutingChain = false,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Ensures Mote's persistent Lottie-based floating voice interface is visible above other Android
     * apps and the system UI:
     * 1. Prefers [MoteAccessibilityService]'s `TYPE_ACCESSIBILITY_OVERLAY` window when connected
     *    (highest system UI persistence, renders over System UI & apps without `SYSTEM_ALERT_WINDOW`).
     * 2. Falls back to [BotOverlayService] (`WindowManager` + `TYPE_APPLICATION_OVERLAY`) when
     *    `Settings.canDrawOverlays` is granted.
     */
    fun ensureSystemOverlayRunning(context: Context): Boolean {
        val a11yService = MoteAccessibilityService.instance
        if (a11yService != null) {
            val shown = a11yService.showFloatingOverlay()
            if (shown) return true
        }
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return false
        return try {
            val intent = Intent(appCtx, BotOverlayService::class.java)
            ContextCompat.startForegroundService(appCtx, intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveHostLabel(a11yActive: Boolean, wmActive: Boolean): String = when {
        a11yActive -> "Accessibility Overlay (System UI)"
        wmActive -> "WindowManager Overlay"
        else -> "Inactive"
    }
}

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
 * Live Voice Call Relay, and the system-wide animated Siri-like [BotOverlayService].
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
        _state.update {
            it.copy(
                isServiceRunning = running,
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
     * Automatically starts [BotOverlayService] when the user has granted `SYSTEM_ALERT_WINDOW`
     * so the animated Siri-style assistant floats live on top of Chrome, YouTube, Phone, or any app.
     */
    fun ensureSystemOverlayRunning(context: Context): Boolean {
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
}

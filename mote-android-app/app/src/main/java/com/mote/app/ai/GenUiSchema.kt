package com.mote.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class EngineUsed {
    LOCAL_NANO,
    CLOUD_FLASH,
    OFFLINE_FALLBACK
}

@Serializable
data class GenUiCardSpec(
    val title: String,
    val subtitle: String = "",
    val widgets: List<GenUiWidgetSpec> = emptyList()
)

@Serializable
data class GenUiWidgetSpec(
    val type: String, // slider | toggle | action_chip | confirm_button | reply_pills | info_card
    val id: String,
    val label: String,
    val intValue: Int? = null,
    val boolValue: Boolean? = null,
    val options: List<String> = emptyList(),
    val actionType: String = "NONE", // SET_BRIGHTNESS | TOGGLE_DND | START_TIMER | COPY_TEXT | PASTE_REPLY | SEND_MESSAGE
    val requiresConfirm: Boolean = false
)

/**
 * Strict sealed allowlist preventing prompt injection from executing arbitrary actions.
 */
sealed interface MoteAction {
    data class SetBrightness(val percent: Int) : MoteAction
    data class ToggleDnd(val enabled: Boolean) : MoteAction
    data class StartTimer(val minutes: Int, val label: String) : MoteAction
    data class CopyText(val text: String) : MoteAction
    data class PasteReply(val text: String) : MoteAction
    data class ConfirmSendMessage(val text: String) : MoteAction
}

enum class ActionSafetyTier {
    TIER_1_AUTO_REVERSIBLE,
    TIER_2_ONE_TAP_CONFIRM,
    TIER_3_BIOMETRIC_CONFIRM
}

data class HybridGenUiResult(
    val spec: GenUiCardSpec,
    val engineUsed: EngineUsed,
    val latencyMs: Long,
    val rawJson: String
)

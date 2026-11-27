package com.mote.app.ai

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
    val themeAccent: String = "cobalt", // cobalt | emerald | amber | violet | slate
    val widgets: List<GenUiWidgetSpec> = emptyList()
)

@Serializable
data class GenUiKeyValue(
    val label: String,
    val value: String,
    val delta: String = ""
)

@Serializable
data class GenUiChartBar(
    val label: String,
    val value: Float,
    val unit: String = ""
)

@Serializable
data class GenUiTimelineStep(
    val time: String,
    val title: String,
    val detail: String = ""
)

@Serializable
data class GenUiComparisonRow(
    val feature: String,
    val leftValue: String,
    val rightValue: String
)

@Serializable
data class GenUiCalendarEvent(
    val dayLabel: String,
    val title: String,
    val timeRange: String = "",
    val location: String = ""
)

/**
 * Rich Generative UI component schema inspired by Google Generative UI, A2UI,
 * and Vercel AI SDK Generative User Interfaces.
 */
@Serializable
data class GenUiWidgetSpec(
    /**
     * Supported interactive widget types registered in GenUiComponentRegistry:
     * - slider | toggle | action_chip | confirm_button | reply_pills | info_card
     * - metric_grid | bar_chart | checklist | comparison_table | timeline | quiz_card | calendar
     */
    val type: String,
    val id: String,
    val label: String,
    val intValue: Int? = null,
    val boolValue: Boolean? = null,
    val options: List<String> = emptyList(),
    val metrics: List<GenUiKeyValue> = emptyList(),
    val chartBars: List<GenUiChartBar> = emptyList(),
    val timelineSteps: List<GenUiTimelineStep> = emptyList(),
    val calendarEvents: List<GenUiCalendarEvent> = emptyList(),
    val comparisonHeaders: List<String> = emptyList(),
    val comparisonRows: List<GenUiComparisonRow> = emptyList(),
    val correctOptionIndex: Int? = null,
    val explanation: String = "",
    val actionType: String = "NONE", // SET_BRIGHTNESS | SET_VOLUME | TOGGLE_DND | START_TIMER | COPY_TEXT | PASTE_REPLY | SEND_MESSAGE
    val requiresConfirm: Boolean = false
)

/**
 * Strict sealed allowlist preventing prompt injection from executing arbitrary actions.
 */
sealed interface MoteAction {
    data class SetBrightness(val percent: Int) : MoteAction
    data class SetVolume(val percent: Int, val stream: String = "media") : MoteAction
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
    val rawJson: String,
    val modelName: String = HybridAiRouter.DEFAULT_MODEL_NAME
)

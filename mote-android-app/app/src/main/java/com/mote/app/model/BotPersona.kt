package com.mote.app.model

import com.google.firebase.ai.type.InferenceMode
import com.google.firebase.ai.type.PublicPreviewAPI

data class BotPersona(
    val id: String = "focus",
    val name: String = "Focus",
    val role: String = "Settings & Timers",
    val colorHex: Long = 0xFF059669,
    val active: Boolean = true,
    val opacityPercent: Int = 90,
    val inferenceMode: String = "PREFER_ON_DEVICE",
    val systemPrompt: String = "Return ultra-compact Generative UI widgets for Android settings, timers, and focus controls.",
    val allowScreenRead: Boolean = false,
    val allowActions: Boolean = true
) {
    @OptIn(PublicPreviewAPI::class)
    fun toInferenceMode(): InferenceMode = when (inferenceMode) {
        "ONLY_ON_DEVICE" -> InferenceMode.ONLY_ON_DEVICE
        "PREFER_IN_CLOUD" -> InferenceMode.PREFER_IN_CLOUD
        "ONLY_IN_CLOUD" -> InferenceMode.ONLY_IN_CLOUD
        else -> InferenceMode.PREFER_ON_DEVICE
    }

    companion object {
        val defaultStarters = listOf(
            BotPersona(
                id = "focus",
                name = "Focus",
                role = "Settings & Timers",
                colorHex = 0xFF059669,
                active = true,
                inferenceMode = "PREFER_ON_DEVICE"
            ),
            BotPersona(
                id = "reply",
                name = "Reply",
                role = "Screen Chat Helper",
                colorHex = 0xFF1D4ED8,
                active = true,
                inferenceMode = "PREFER_ON_DEVICE",
                systemPrompt = "Draft 3 concise, natural reply pills based on the redacted screen context.",
                allowScreenRead = true
            ),
            BotPersona(
                id = "saver",
                name = "Saver",
                role = "Offline Battery",
                colorHex = 0xFF0B0D1D,
                active = false,
                inferenceMode = "ONLY_ON_DEVICE"
            )
        )
    }
}

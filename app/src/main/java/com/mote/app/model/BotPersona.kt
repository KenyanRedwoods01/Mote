package com.mote.app.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class MoteInferenceMode {
    PREFER_ON_DEVICE,
    ONLY_ON_DEVICE,
    PREFER_IN_CLOUD,
    ONLY_IN_CLOUD;

    companion object {
        fun fromString(raw: String?): MoteInferenceMode =
            entries.firstOrNull { it.name == raw } ?: PREFER_ON_DEVICE
    }
}

@Keep
@IgnoreExtraProperties
data class BotPersona(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "Focus",
    val role: String = "Settings & Timers",
    val colorHex: Long = 0xFF059669L,
    val active: Boolean = true,
    val opacityPercent: Int = 90,
    val inferenceMode: String = MoteInferenceMode.PREFER_ON_DEVICE.name,
    val systemPrompt: String = "Return ultra-compact Generative UI widgets for Android settings, timers, and focus controls.",
    val allowScreenRead: Boolean = false,
    val allowActions: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toInferenceMode(): MoteInferenceMode = MoteInferenceMode.fromString(inferenceMode)

    companion object {
        val defaultStarters = listOf(
            BotPersona(
                id = "focus",
                name = "Focus",
                role = "Settings & Timers",
                colorHex = 0xFF059669L,
                active = true,
                inferenceMode = MoteInferenceMode.PREFER_ON_DEVICE.name
            ),
            BotPersona(
                id = "reply",
                name = "Reply",
                role = "Screen Chat Helper",
                colorHex = 0xFF1D4ED8L,
                active = true,
                inferenceMode = MoteInferenceMode.PREFER_ON_DEVICE.name,
                systemPrompt = "Draft 3 concise, natural reply pills based on the redacted screen context.",
                allowScreenRead = true
            ),
            BotPersona(
                id = "saver",
                name = "Saver",
                role = "Offline Battery",
                colorHex = 0xFF0B0D1DL,
                active = false,
                inferenceMode = MoteInferenceMode.ONLY_ON_DEVICE.name
            )
        )
    }
}

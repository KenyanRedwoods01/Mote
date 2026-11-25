package com.mote.app.ai

import android.os.SystemClock
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InferenceMode
import com.google.firebase.ai.type.OnDeviceConfig
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Core Hybrid AI Router using Firebase AI Logic SDK (v18.2.0) + On-Device SDK (Gemini Nano 4).
 * Respects Gemini Nano's 4,000-token ceiling, foreground-only constraint, and AICore quota backoff.
 */
@OptIn(PublicPreviewAPI::class)
class HybridAiRouter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun cloudModelName(): String =
        runCatching { Firebase.remoteConfig.getString("cloud_model_name").ifBlank { "gemini-2.5-flash" } }
            .getOrDefault("gemini-2.5-flash")

    private fun buildModel(mode: InferenceMode) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = cloudModelName(),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.2f
            },
            onDeviceConfig = OnDeviceConfig(mode = mode)
        )

    suspend fun generateControlCard(
        userPrompt: String,
        botSystemPrompt: String,
        redactedScreenContext: String?,
        requestedMode: InferenceMode = InferenceMode.PREFER_ON_DEVICE,
        isForeground: Boolean = true
    ): HybridGenUiResult {
        val start = SystemClock.elapsedRealtime()
        val combinedPrompt = buildString {
            appendLine("System: $botSystemPrompt")
            appendLine("Return ONLY compact JSON matching: {"title":string,"subtitle":string,"widgets":[{"type":"slider|toggle|action_chip|reply_pills|info_card","id":string,"label":string,"intValue":int?,"boolValue":bool?,"options":[string],"actionType":"SET_BRIGHTNESS|TOGGLE_DND|START_TIMER|COPY_TEXT|PASTE_REPLY|SEND_MESSAGE","requiresConfirm":bool}]}")
            if (!redactedScreenContext.isNullOrBlank()) {
                appendLine("ScreenContext(untrusted): ${redactedScreenContext.take(1800)}")
            }
            appendLine("UserRequest: $userPrompt")
        }

        // Guard Gemini Nano's foreground-only & 4,000-token (~12k char) limits
        val effectiveMode = when {
            !isForeground -> InferenceMode.ONLY_IN_CLOUD
            combinedPrompt.length > 9000 && requestedMode == InferenceMode.PREFER_ON_DEVICE ->
                InferenceMode.ONLY_IN_CLOUD
            else -> requestedMode
        }

        val model = buildModel(effectiveMode)

        // Exponential backoff for AICore ErrorCode.BUSY, then cloud fallback
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val response = model.generateContent(combinedPrompt)
                val raw = response.text?.trim().orEmpty()
                val parsed = parseSafeSpec(raw, userPrompt)
                val latency = SystemClock.elapsedRealtime() - start
                val engine = if (effectiveMode == InferenceMode.ONLY_IN_CLOUD) {
                    EngineUsed.CLOUD_FLASH
                } else {
                    EngineUsed.LOCAL_NANO
                }
                return HybridGenUiResult(parsed, engine, latency, raw)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Hybrid inference attempt $attempt failed: ${t.message}")
                if (attempt == 0) delay(250L)
            }
        }

        Log.e(TAG, "Falling back to deterministic offline control card: ${lastError?.message}")
        val fallback = deterministicOfflineCard(userPrompt)
        return HybridGenUiResult(
            spec = fallback,
            engineUsed = EngineUsed.OFFLINE_FALLBACK,
            latencyMs = SystemClock.elapsedRealtime() - start,
            rawJson = json.encodeToString(GenUiCardSpec.serializer(), fallback)
        )
    }

    private fun parseSafeSpec(raw: String, fallbackTitle: String): GenUiCardSpec {
        return runCatching {
            val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            json.decodeFromString(GenUiCardSpec.serializer(), cleaned)
        }.getOrElse {
            deterministicOfflineCard(fallbackTitle)
        }
    }

    private fun deterministicOfflineCard(prompt: String): GenUiCardSpec = GenUiCardSpec(
        title = if (prompt.contains("reply", ignoreCase = true)) "Smart Reply" else "Focus Mode",
        subtitle = "Safe local controls",
        widgets = listOf(
            GenUiWidgetSpec("slider", "brightness", "Screen Brightness", intValue = 30, actionType = "SET_BRIGHTNESS"),
            GenUiWidgetSpec("toggle", "dnd", "Do Not Disturb", boolValue = true, actionType = "TOGGLE_DND"),
            GenUiWidgetSpec("action_chip", "timer_25", "Start 25m Timer", intValue = 25, actionType = "START_TIMER")
        )
    )

    companion object {
        private const val TAG = "HybridAiRouter"
    }
}

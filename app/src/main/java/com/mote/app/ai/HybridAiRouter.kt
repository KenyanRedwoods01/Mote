package com.mote.app.ai

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.model.MoteInferenceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

data class LlmModelOption(
    val id: String,
    val displayName: String,
    val badge: String,
    val description: String,
    val contextLabel: String
)

data class LlmConfig(
    val modelName: String = HybridAiRouter.DEFAULT_MODEL_NAME,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 1024,
    val contextWindowTurns: Int = 10
)

data class InferenceMetrics(
    val latencyMs: Long,
    val ttftMs: Long,
    val approxTokensPerSec: Float,
    val modelName: String
)

sealed interface ChatStreamEvent {
    data class Delta(val accumulatedText: String, val elapsedMs: Long) : ChatStreamEvent
    data class Completed(val fullText: String, val metrics: InferenceMetrics) : ChatStreamEvent
}

/**
 * Firebase AI Logic Router powering both:
 * 1. Streaming multi-turn + multimodal (vision) conversational LLM chat
 * 2. Rich Generative UI structured JSON runtime (11 interactive Compose components)
 */
class HybridAiRouter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val metricItemSchema = Schema.obj(
        properties = mapOf(
            "label" to Schema.string(description = "Metric name"),
            "value" to Schema.string(description = "Primary KPI value"),
            "delta" to Schema.string(description = "Optional short trend or unit note")
        ),
        optionalProperties = listOf("delta")
    )

    private val chartBarSchema = Schema.obj(
        properties = mapOf(
            "label" to Schema.string(description = "Bar label"),
            "value" to Schema.double(description = "Numeric bar value"),
            "unit" to Schema.string(description = "Optional suffix unit like %, ms, kcal, $")
        ),
        optionalProperties = listOf("unit")
    )

    private val timelineStepSchema = Schema.obj(
        properties = mapOf(
            "time" to Schema.string(description = "Step time or phase label (e.g. Day 1, 09:00)"),
            "title" to Schema.string(description = "Short milestone title"),
            "detail" to Schema.string(description = "Brief supporting detail")
        ),
        optionalProperties = listOf("detail")
    )

    private val comparisonRowSchema = Schema.obj(
        properties = mapOf(
            "feature" to Schema.string(description = "Comparison attribute"),
            "leftValue" to Schema.string(description = "Value for first option"),
            "rightValue" to Schema.string(description = "Value for second option")
        )
    )

    private val widgetSchema = Schema.obj(
        properties = mapOf(
            "type" to Schema.enumeration(
                values = listOf(
                    "metric_grid",
                    "bar_chart",
                    "checklist",
                    "comparison_table",
                    "timeline",
                    "quiz_card",
                    "slider",
                    "toggle",
                    "action_chip",
                    "reply_pills",
                    "info_card"
                ),
                description = "Interactive Generative UI widget type"
            ),
            "id" to Schema.string(description = "Unique widget identifier"),
            "label" to Schema.string(description = "Widget title, question, or control label"),
            "intValue" to Schema.integer(description = "Optional integer for sliders or timer minutes", nullable = true),
            "boolValue" to Schema.boolean(description = "Optional boolean for toggles", nullable = true),
            "options" to Schema.array(
                items = Schema.string(),
                description = "Options for checklist items, quiz choices, or reply_pills"
            ),
            "metrics" to Schema.array(
                items = metricItemSchema,
                description = "KPI tiles when type is metric_grid"
            ),
            "chartBars" to Schema.array(
                items = chartBarSchema,
                description = "Bars when type is bar_chart"
            ),
            "timelineSteps" to Schema.array(
                items = timelineStepSchema,
                description = "Steps when type is timeline"
            ),
            "comparisonHeaders" to Schema.array(
                items = Schema.string(),
                description = "2 column headers when type is comparison_table"
            ),
            "comparisonRows" to Schema.array(
                items = comparisonRowSchema,
                description = "Comparison rows when type is comparison_table"
            ),
            "correctOptionIndex" to Schema.integer(
                description = "0-based correct option index when type is quiz_card",
                nullable = true
            ),
            "explanation" to Schema.string(description = "Short explanation shown after answering quiz_card"),
            "actionType" to Schema.enumeration(
                values = listOf("NONE", "SET_BRIGHTNESS", "TOGGLE_DND", "START_TIMER", "COPY_TEXT", "PASTE_REPLY", "SEND_MESSAGE"),
                description = "Allowlisted Android device action"
            ),
            "requiresConfirm" to Schema.boolean(description = "Whether explicit confirmation is highlighted")
        ),
        optionalProperties = listOf(
            "intValue",
            "boolValue",
            "options",
            "metrics",
            "chartBars",
            "timelineSteps",
            "comparisonHeaders",
            "comparisonRows",
            "correctOptionIndex",
            "explanation",
            "actionType",
            "requiresConfirm"
        )
    )

    private val cardSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Short bespoke UI card title"),
            "subtitle" to Schema.string(description = "Brief contextual subtitle"),
            "themeAccent" to Schema.enumeration(
                values = listOf("cobalt", "emerald", "amber", "violet", "slate"),
                description = "Visual accent matching the topic"
            ),
            "widgets" to Schema.array(
                items = widgetSchema,
                description = "2 to 5 rich interactive Generative UI components tailored to the user request"
            )
        ),
        optionalProperties = listOf("subtitle", "themeAccent")
    )

    /**
     * Streams a real multi-turn conversational response (with optional attached image)
     * through Firebase AI Logic while emitting real-time text deltas and performance telemetry.
     */
    fun streamChatMessage(
        userPrompt: String,
        botSystemPrompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        attachedBitmap: Bitmap? = null
    ): Flow<ChatStreamEvent> = flow {
        val startMs = SystemClock.elapsedRealtime()
        var ttftMs = 0L
        val accumulated = StringBuilder()

        val model = Firebase.ai.generativeModel(
            modelName = config.modelName,
            generationConfig = generationConfig {
                temperature = config.temperature.coerceIn(0.0f, 1.5f)
                topP = config.topP.coerceIn(0.1f, 1.0f)
                maxOutputTokens = config.maxOutputTokens.coerceIn(128, 4096)
            },
            systemInstruction = content {
                text(
                    buildString {
                        appendLine(botSystemPrompt)
                        appendLine("Be concise, clear, and helpful for mobile reading.")
                    }
                )
            }
        )

        val recentTurns: List<Content> = history
            .takeLast(config.contextWindowTurns.coerceIn(2, 20))
            .map { msg ->
                content(role = if (msg.chatRole() == ChatRole.USER) "user" else "model") {
                    text(msg.text)
                }
            }

        val currentTurn = content(role = "user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(userPrompt)
        }

        val payload = recentTurns + currentTurn

        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                model.generateContentStream(payload).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        val now = SystemClock.elapsedRealtime()
                        if (ttftMs == 0L) {
                            ttftMs = (now - startMs).coerceAtLeast(1L)
                        }
                        accumulated.append(piece)
                        emit(ChatStreamEvent.Delta(accumulated.toString(), now - startMs))
                    }
                }
            }
            val fullText = accumulated.toString().trim()
            if (fullText.isBlank()) {
                throw IllegalStateException("Gemini returned an empty response.")
            }
            val totalMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val approxTokens = (fullText.length / 4f).coerceAtLeast(1f)
            val tokensPerSec = approxTokens / (totalMs / 1000f)
            emit(
                ChatStreamEvent.Completed(
                    fullText = fullText,
                    metrics = InferenceMetrics(
                        latencyMs = totalMs,
                        ttftMs = if (ttftMs > 0L) ttftMs else totalMs,
                        approxTokensPerSec = tokensPerSec,
                        modelName = config.modelName
                    )
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming chat timed out", e)
            throw IllegalStateException("AI stream timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming chat failed", e)
            throw e
        }
    }

    suspend fun generateControlCard(
        userPrompt: String,
        botSystemPrompt: String,
        redactedScreenContext: String?,
        requestedMode: MoteInferenceMode = MoteInferenceMode.PREFER_ON_DEVICE,
        isForeground: Boolean = true,
        modelName: String = DEFAULT_MODEL_NAME
    ): Result<HybridGenUiResult> {
        val start = SystemClock.elapsedRealtime()
        val combinedPrompt = buildString {
            appendLine("System: $botSystemPrompt")
            appendLine(
                "You are a Generative UI architect. Instead of plain walls of text, compose a bespoke interactive Android interface using 2 to 5 rich widgets from the schema: metric_grid, bar_chart, checklist, comparison_table, timeline, quiz_card, slider, toggle, action_chip, reply_pills, info_card."
            )
            appendLine("Choose the best visual widgets for the user's topic (e.g., timeline for trips/schedules, comparison_table for vs queries, bar_chart & metric_grid for budgets/benchmarks/fitness, quiz_card for learning, checklist for plans, and slider/toggle/action_chip for device focus controls).")
            if (!redactedScreenContext.isNullOrBlank()) {
                appendLine("ScreenContext(untrusted): ${redactedScreenContext.take(1800)}")
            }
            appendLine("UserRequest: $userPrompt")
        }

        val genUiModel = Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = cardSchema
                temperature = 0.25f
            }
        )

        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                genUiModel.generateContent(combinedPrompt)
            }
            val raw = response.text?.trim()
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))

            val spec = json.decodeFromString(GenUiCardSpec.serializer(), raw)
            val latency = SystemClock.elapsedRealtime() - start
            val engine = if (requestedMode == MoteInferenceMode.ONLY_IN_CLOUD || !isForeground) {
                EngineUsed.CLOUD_FLASH
            } else {
                EngineUsed.CLOUD_FLASH
            }
            Result.success(HybridGenUiResult(spec, engine, latency, raw, modelName))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini Generative UI timed out", e)
            Result.failure(
                IllegalStateException("AI request timed out after 60 seconds. Please try again.", e)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI Generative UI failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "HybridAiRouter"
        const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
        const val LITE_MODEL_NAME = "gemini-3.5-flash-lite"
        private const val INFERENCE_TIMEOUT_MS = 60_000L

        val supportedModels = listOf(
            LlmModelOption(
                id = DEFAULT_MODEL_NAME,
                displayName = "Gemini 3.8 Flash",
                badge = "Default · Multimodal & GenUI",
                description = "Frontier-class reasoning, fast streaming, vision input, and rich Generative UI.",
                contextLabel = "1M ctx"
            ),
            LlmModelOption(
                id = LITE_MODEL_NAME,
                displayName = "Gemini 3.5 Flash-Lite",
                badge = "Ultra Fast",
                description = "Lowest latency for rapid mobile replies and compact interactive UI cards.",
                contextLabel = "1M ctx"
            )
        )
    }
}

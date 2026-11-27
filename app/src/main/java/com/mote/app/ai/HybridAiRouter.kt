package com.mote.app.ai

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.model.MoteInferenceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
    val modelName: String,
    val inferenceSourceLabel: String = "Cloud",
    val nativeFunctionCallCount: Int = 0
)

sealed interface ChatStreamEvent {
    data class Delta(
        val accumulatedText: String,
        val elapsedMs: Long,
        val pendingToolHint: String? = null,
        val completedToolJsonBlocks: List<String> = emptyList(),
        val newlyCompletedToolJsonBlocks: List<String> = emptyList(),
        val partialJsonBufferedBytes: Int = 0
    ) : ChatStreamEvent

    data class Completed(
        val fullText: String,
        val metrics: InferenceMetrics,
        val rawFullText: String = fullText,
        val completedToolJsonBlocks: List<String> = emptyList(),
        val engineUsed: EngineUsed = EngineUsed.CLOUD_FLASH
    ) : ChatStreamEvent
}

/**
 * Firebase AI Logic Hybrid Router powering:
 * 1. Official SDK-managed hybrid inference via [OnDeviceConfig] & [InferenceMode]
 *    (`PREFER_ON_DEVICE`, `ONLY_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_IN_CLOUD`) with automatic
 *    Gemini Nano (AICore) -> Cloud Gemini fallback and real [InferenceSource] reporting.
 * 2. Official native Firebase Function Calling (`Tool.functionDeclarations`) seamlessly unified
 *    with `<<<TOOL_CALL>>>` streamed JSON blocks via [IncrementalJsonStreamParser].
 * 3. Rich Generative UI structured JSON runtime (11 interactive Compose components) with
 *    streaming partial JSON chunk assembly via [JsonDocumentStreamAccumulator].
 */
class HybridAiRouter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val aiClient by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    private val modelCacheLock = Any()
    private val modelCache = object : LinkedHashMap<String, GenerativeModel>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GenerativeModel>?): Boolean {
            return size > 10
        }
    }

    @OptIn(PublicPreviewAPI::class)
    private fun getOrCreateGenerativeModel(
        cacheKey: String,
        modelName: String,
        genConfig: com.google.firebase.ai.type.GenerationConfig,
        systemInstruction: Content? = null,
        tools: List<Tool>? = null,
        onDeviceConfig: OnDeviceConfig? = null
    ): GenerativeModel {
        synchronized(modelCacheLock) {
            modelCache[cacheKey]?.let { return it }
            val created = if (onDeviceConfig != null) {
                aiClient.generativeModel(
                    modelName = modelName,
                    generationConfig = genConfig,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    onDeviceConfig = onDeviceConfig
                )
            } else {
                aiClient.generativeModel(
                    modelName = modelName,
                    generationConfig = genConfig,
                    systemInstruction = systemInstruction,
                    tools = tools
                )
            }
            modelCache[cacheKey] = created
            return created
        }
    }

    @OptIn(PublicPreviewAPI::class)
    private fun mapToSdkInferenceMode(mode: MoteInferenceMode): InferenceMode = when (mode) {
        MoteInferenceMode.PREFER_ON_DEVICE -> InferenceMode.PREFER_ON_DEVICE
        MoteInferenceMode.ONLY_ON_DEVICE -> InferenceMode.ONLY_ON_DEVICE
        MoteInferenceMode.PREFER_IN_CLOUD -> InferenceMode.PREFER_IN_CLOUD
        MoteInferenceMode.ONLY_IN_CLOUD -> InferenceMode.ONLY_IN_CLOUD
    }

    @OptIn(PublicPreviewAPI::class)
    private fun mapInferenceSourceToEngine(
        source: InferenceSource?,
        requestedMode: MoteInferenceMode
    ): Pair<EngineUsed, String> = when (source) {
        InferenceSource.ON_DEVICE -> EngineUsed.LOCAL_NANO to "On-Device (Gemini Nano)"
        InferenceSource.IN_CLOUD -> EngineUsed.CLOUD_FLASH to "Cloud (Firebase AI Logic)"
        else -> if (requestedMode == MoteInferenceMode.ONLY_ON_DEVICE) {
            EngineUsed.LOCAL_NANO to "On-Device (Gemini Nano)"
        } else {
            EngineUsed.CLOUD_FLASH to "Cloud (Firebase AI Logic)"
        }
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

    private val calendarEventSchema = Schema.obj(
        properties = mapOf(
            "dayLabel" to Schema.string(description = "Short day/date label like Mon 24 or Today"),
            "title" to Schema.string(description = "Event title"),
            "timeRange" to Schema.string(description = "Optional time window like 10:00 - 11:00"),
            "location" to Schema.string(description = "Optional room or location")
        ),
        optionalProperties = listOf("timeRange", "location")
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
                    "calendar",
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
            "calendarEvents" to Schema.array(
                items = calendarEventSchema,
                description = "Schedule items when type is calendar"
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
                values = listOf("NONE", "SET_BRIGHTNESS", "SET_VOLUME", "TOGGLE_DND", "START_TIMER", "COPY_TEXT", "PASTE_REPLY", "SEND_MESSAGE"),
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
            "calendarEvents",
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

    private fun buildCandidateModels(primaryModelId: String): List<String> {
        val fallbackOrder = listOf(
            primaryModelId,
            LITE_MODEL_NAME,
            DEFAULT_MODEL_NAME,
            ARGON_MODEL_NAME
        )
        return fallbackOrder.distinct()
    }

    private fun isTransientHighDemandError(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return msg.contains("high demand") ||
            msg.contains("overloaded") ||
            msg.contains("503") ||
            msg.contains("429") ||
            msg.contains("unavailable") ||
            msg.contains("resource exhausted") ||
            msg.contains("rate limit") ||
            msg.contains("try again later")
    }

    private fun isHybridOrToolConfigRejection(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return msg.contains("on-device") ||
            msg.contains("ondevice") ||
            msg.contains("aicore") ||
            msg.contains("function_declarations") ||
            msg.contains("functiondeclaration") ||
            msg.contains("function calling") ||
            msg.contains("tools") ||
            msg.contains("invalid_argument") ||
            msg.contains("invalid argument") ||
            msg.contains("schema") ||
            msg.contains("properties") ||
            msg.contains("unsupported")
    }

    private fun displayNameForModel(modelId: String): String =
        supportedModels.firstOrNull { it.id == modelId }?.displayName ?: modelId

    /**
     * Streams a real multi-turn conversational response (with optional attached image) through
     * Firebase AI Logic's official hybrid SDK ([OnDeviceConfig] + [InferenceMode]) and native
     * [FirebaseNativeToolDeclarations] function calling, while routing text chunks through
     * [IncrementalJsonStreamParser] so both native `FunctionCallPart`s and `<<<TOOL_CALL>>>`
     * blocks feed cleanly into [SequentialToolOrchestrator].
     */
    @OptIn(PublicPreviewAPI::class)
    fun streamChatMessage(
        userPrompt: String,
        botSystemPrompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        attachedBitmap: Bitmap? = null,
        clockContext: String = "",
        enabledToolIds: Set<String> = GenUiToolRegistry.ALL_TOOL_IDS,
        inferenceMode: MoteInferenceMode = MoteInferenceMode.PREFER_ON_DEVICE,
        enableNativeFunctionCalling: Boolean = true
    ): Flow<ChatStreamEvent> = flow {
        val startMs = SystemClock.elapsedRealtime()
        val systemInstructionContent = content {
            text(
                buildString {
                    appendLine(botSystemPrompt)
                    appendLine("Be concise, clear, and helpful for mobile reading.")
                    appendLine()
                    appendLine(ChatGenUiToolProtocol.buildToolSystemInstructions(clockContext, enabledToolIds))
                }
            )
        }

        val recentTurns: List<Content> = history
            .takeLast(config.contextWindowTurns.coerceIn(2, 20))
            .map { msg ->
                val isUser = msg.chatRole() == ChatRole.USER
                content(role = if (isUser) "user" else "model") {
                    text(
                        if (isUser) {
                            msg.text
                        } else {
                            "[Prior turn summary — tool calls omitted; issue fresh function calls or <<<TOOL_CALL>>> tags for any new request] ${msg.text}"
                        }
                    )
                }
            }

        val currentTurn = content(role = "user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(userPrompt)
        }

        val payload = recentTurns + currentTurn
        val candidates = buildCandidateModels(config.modelName)
        val nativeTools = if (enableNativeFunctionCalling &&
            enabledToolIds.isNotEmpty() &&
            inferenceMode != MoteInferenceMode.ONLY_ON_DEVICE
        ) {
            FirebaseNativeToolDeclarations.buildTools(enabledToolIds)
        } else {
            emptyList()
        }
        val sdkHybridMode = mapToSdkInferenceMode(inferenceMode)
        var lastError: Exception? = null

        for ((index, candidateModelId) in candidates.withIndex()) {
            // Try official SDK hybrid + native tools first; if rejected by a specific endpoint/mode
            // (and not ONLY_ON_DEVICE), gracefully degrade to cloud-only without breaking chat.
            val attemptConfigs = buildList {
                add(Pair(OnDeviceConfig(mode = sdkHybridMode), nativeTools))
                if (inferenceMode != MoteInferenceMode.ONLY_ON_DEVICE) {
                    if (nativeTools.isNotEmpty()) {
                        add(Pair(null, nativeTools))
                    }
                    add(Pair(null, emptyList()))
                }
            }

            if (index > 0) {
                emit(
                    ChatStreamEvent.Delta(
                        accumulatedText = "",
                        elapsedMs = SystemClock.elapsedRealtime() - startMs,
                        pendingToolHint = "High demand on ${displayNameForModel(candidates[index - 1])} — retrying with ${displayNameForModel(candidateModelId)}..."
                    )
                )
            }

            for ((subIdx, pair) in attemptConfigs.withIndex()) {
                val (onDeviceCfg, toolsList) = pair
                val attemptStartMs = SystemClock.elapsedRealtime()
                var ttftMs = 0L
                val rawAccumulated = StringBuilder()
                val nativeCallJsonBlocks = mutableListOf<String>()
                var observedInferenceSource: InferenceSource? = null
                val streamParser = IncrementalJsonStreamParser()
                StreamParserTelemetry.recordStreamStart(candidateModelId)

                val genConfig = generationConfig {
                    temperature = config.temperature.coerceIn(0.0f, 1.5f)
                    topP = config.topP.coerceIn(0.1f, 1.0f)
                    maxOutputTokens = config.maxOutputTokens.coerceIn(128, 4096)
                }

                val sysHash = (botSystemPrompt.hashCode() * 31) + clockContext.hashCode() + enabledToolIds.hashCode()
                val cacheKey = "chat|$candidateModelId|${onDeviceCfg?.let { inferenceMode.name } ?: "CLOUD"}|" +
                    "${toolsList.size}|${config.temperature}|${config.topP}|${config.maxOutputTokens}|$sysHash"
                val model = getOrCreateGenerativeModel(
                    cacheKey = cacheKey,
                    modelName = candidateModelId,
                    genConfig = genConfig,
                    systemInstruction = systemInstructionContent,
                    tools = toolsList.ifEmpty { null },
                    onDeviceConfig = onDeviceCfg
                )

                try {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        model.generateContentStream(payload).collect { chunk ->
                            observedInferenceSource = chunk.inferenceSource
                            val now = SystemClock.elapsedRealtime()

                            // 1. Collect official native Firebase FunctionCallParts
                            val chunkFnCalls = chunk.functionCalls
                            if (chunkFnCalls.isNotEmpty()) {
                                if (ttftMs == 0L) {
                                    ttftMs = (now - attemptStartMs).coerceAtLeast(1L)
                                }
                                val newlyAdded = mutableListOf<String>()
                                chunkFnCalls.forEach { fnCall ->
                                    val canonical = FirebaseNativeToolDeclarations.toCanonicalToolJson(fnCall)
                                    if (canonical !in nativeCallJsonBlocks) {
                                        nativeCallJsonBlocks += canonical
                                        newlyAdded += canonical
                                    }
                                }
                                if (newlyAdded.isNotEmpty()) {
                                    val fnNames = chunkFnCalls.joinToString(", ") { it.name }
                                    emit(
                                        ChatStreamEvent.Delta(
                                            accumulatedText = streamParser.currentVisibleText,
                                            elapsedMs = now - startMs,
                                            pendingToolHint = "Firebase Native Function Call: $fnNames...",
                                            completedToolJsonBlocks = (nativeCallJsonBlocks + streamParser.feedChunk("").completedToolJsonBlocks).distinct(),
                                            newlyCompletedToolJsonBlocks = newlyAdded
                                        )
                                    )
                                }
                            }

                            // 2. Stream text chunks through IncrementalJsonStreamParser (dual-mode <<<TOOL_CALL>>> support)
                            val piece = chunk.text.orEmpty()
                            if (piece.isNotEmpty()) {
                                if (ttftMs == 0L) {
                                    ttftMs = (now - attemptStartMs).coerceAtLeast(1L)
                                }
                                rawAccumulated.append(piece)
                                val parsed = streamParser.feedChunk(piece)
                                emit(
                                    ChatStreamEvent.Delta(
                                        accumulatedText = parsed.visibleText,
                                        elapsedMs = now - startMs,
                                        pendingToolHint = parsed.pendingToolHint,
                                        completedToolJsonBlocks = (nativeCallJsonBlocks + parsed.completedToolJsonBlocks).distinct(),
                                        newlyCompletedToolJsonBlocks = parsed.newlyCompletedToolJsonBlocks,
                                        partialJsonBufferedBytes = parsed.partialJsonBufferedBytes
                                    )
                                )
                            }
                        }
                    }

                    val finalSnapshot = streamParser.finish()
                    val allToolBlocks = (nativeCallJsonBlocks + finalSnapshot.completedToolJsonBlocks).distinct()
                    val rawFullText = rawAccumulated.toString().trim()
                    if (rawFullText.isBlank() && allToolBlocks.isEmpty()) {
                        throw IllegalStateException("Gemini returned an empty response.")
                    }

                    val (resolvedEngine, sourceLabel) = mapInferenceSourceToEngine(observedInferenceSource, inferenceMode)
                    val totalMs = (SystemClock.elapsedRealtime() - attemptStartMs).coerceAtLeast(1L)
                    val approxTokens = (rawFullText.length / 4f).coerceAtLeast(1f)
                    val tokensPerSec = approxTokens / (totalMs / 1000f)
                    emit(
                        ChatStreamEvent.Completed(
                            fullText = finalSnapshot.visibleText.trim(),
                            rawFullText = rawFullText,
                            completedToolJsonBlocks = allToolBlocks,
                            engineUsed = resolvedEngine,
                            metrics = InferenceMetrics(
                                latencyMs = totalMs,
                                ttftMs = if (ttftMs > 0L) ttftMs else totalMs,
                                approxTokensPerSec = tokensPerSec,
                                modelName = if (resolvedEngine == EngineUsed.LOCAL_NANO) "gemini-nano (AICore)" else candidateModelId,
                                inferenceSourceLabel = sourceLabel,
                                nativeFunctionCallCount = nativeCallJsonBlocks.size
                            )
                        )
                    )
                    return@flow
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Gemini streaming chat timed out on $candidateModelId", e)
                    throw IllegalStateException("AI stream timed out after 60 seconds. Please try again.", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini streaming chat failed on $candidateModelId (subAttempt=$subIdx): ${e.message}", e)
                    lastError = e
                    val canTrySubFallback = rawAccumulated.isEmpty() &&
                        nativeCallJsonBlocks.isEmpty() &&
                        subIdx < attemptConfigs.lastIndex &&
                        isHybridOrToolConfigRejection(e)
                    if (canTrySubFallback) {
                        continue
                    }
                    val canFallbackModel = rawAccumulated.isEmpty() &&
                        nativeCallJsonBlocks.isEmpty() &&
                        isTransientHighDemandError(e) &&
                        index < candidates.lastIndex
                    if (canFallbackModel) {
                        delay(450L)
                        break // Advance to next candidateModelId
                    }
                    throw e
                }
            }
        }

        throw lastError ?: IllegalStateException("All Gemini models are currently busy. Please try again shortly.")
    }

    /**
     * Generates a structured Generative UI card through Firebase AI Logic's official hybrid SDK
     * ([OnDeviceConfig] + [InferenceMode]), streaming partial JSON chunks through
     * [JsonDocumentStreamAccumulator] and reporting the real SDK [InferenceSource].
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun generateControlCard(
        userPrompt: String,
        botSystemPrompt: String,
        redactedScreenContext: String?,
        requestedMode: MoteInferenceMode = MoteInferenceMode.PREFER_ON_DEVICE,
        isForeground: Boolean = true,
        modelName: String = DEFAULT_MODEL_NAME
    ): Result<HybridGenUiResult> {
        val start = SystemClock.elapsedRealtime()
        val effectiveMode = if (!isForeground && requestedMode == MoteInferenceMode.PREFER_ON_DEVICE) {
            MoteInferenceMode.PREFER_IN_CLOUD
        } else {
            requestedMode
        }
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

        val candidates = buildCandidateModels(modelName)
        val sdkMode = mapToSdkInferenceMode(effectiveMode)
        var lastError: Throwable? = null

        for ((index, candidateModelId) in candidates.withIndex()) {
            val useOnDeviceConfigs = buildList {
                add(OnDeviceConfig(mode = sdkMode))
                if (effectiveMode != MoteInferenceMode.ONLY_ON_DEVICE) {
                    add(null)
                }
            }

            for ((subIdx, onDeviceCfg) in useOnDeviceConfigs.withIndex()) {
                val genCfg = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = cardSchema
                    temperature = 0.25f
                }
                val genUiCacheKey = "genui|$candidateModelId|${onDeviceCfg?.let { effectiveMode.name } ?: "CLOUD"}"
                val genUiModel = getOrCreateGenerativeModel(
                    cacheKey = genUiCacheKey,
                    modelName = candidateModelId,
                    genConfig = genCfg,
                    onDeviceConfig = onDeviceCfg
                )

                try {
                    StreamParserTelemetry.recordStreamStart("$candidateModelId (GenUI)")
                    val accumulator = JsonDocumentStreamAccumulator()
                    var observedSource: InferenceSource? = null
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        genUiModel.generateContentStream(combinedPrompt).collect { chunk ->
                            observedSource = chunk.inferenceSource
                            val piece = chunk.text.orEmpty()
                            if (piece.isNotEmpty()) {
                                accumulator.appendChunk(piece)
                                StreamParserTelemetry.recordChunk(
                                    wasPartialJsonBuffered = !accumulator.isStructurallyComplete,
                                    newlyDecodedBlocks = if (accumulator.isStructurallyComplete) 1 else 0
                                )
                            }
                        }
                    }
                    val raw = accumulator.extractCompleteJson()
                    if (raw.isBlank()) {
                        lastError = IllegalStateException("Gemini returned an empty JSON response.")
                        if (subIdx < useOnDeviceConfigs.lastIndex) continue
                        if (index < candidates.lastIndex) {
                            delay(350L)
                            break
                        }
                        return Result.failure(lastError)
                    }

                    val spec = json.decodeFromString(GenUiCardSpec.serializer(), raw)
                    val latency = SystemClock.elapsedRealtime() - start
                    val (engine, _) = mapInferenceSourceToEngine(observedSource, effectiveMode)
                    val reportedModel = if (engine == EngineUsed.LOCAL_NANO) "gemini-nano (AICore)" else candidateModelId
                    return Result.success(HybridGenUiResult(spec, engine, latency, raw, reportedModel))
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Gemini Generative UI timed out on $candidateModelId", e)
                    return Result.failure(
                        IllegalStateException("AI request timed out after 60 seconds. Please try again.", e)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase AI Generative UI failed on $candidateModelId (subIdx=$subIdx): ${e.message}", e)
                    lastError = e
                    if (subIdx < useOnDeviceConfigs.lastIndex && isHybridOrToolConfigRejection(e)) {
                        continue
                    }
                    if (isTransientHighDemandError(e) && index < candidates.lastIndex) {
                        delay(450L)
                        break
                    }
                    return Result.failure(e)
                }
            }
        }

        return Result.failure(
            lastError ?: IllegalStateException("All Gemini models are currently busy. Please try again shortly.")
        )
    }

    /**
     * Performs real multimodal OCR & document text extraction on a captured screenshot Bitmap
     * using Firebase AI Logic Gemini Vision so downstream tools (e.g. Save to Notes) receive
     * verified extracted text.
     */
    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        modelName: String = DEFAULT_MODEL_NAME
    ): Result<String> {
        val candidates = buildList {
            add(modelName.ifBlank { DEFAULT_MODEL_NAME })
            if (LITE_MODEL_NAME !in this) add(LITE_MODEL_NAME)
        }
        var lastError: Exception? = null
        for ((idx, candidateId) in candidates.withIndex()) {
            try {
                val model = Firebase.ai.generativeModel(
                    modelName = candidateId,
                    generationConfig = generationConfig {
                        temperature = 0.1f
                        maxOutputTokens = 1024
                    },
                    systemInstruction = content {
                        text(
                            "You are an accurate Android OCR engine. Extract all visible text, headings, " +
                                "key values, dates, numbers, and actionable details from the screenshot cleanly. " +
                                "Output plain structured text without preamble."
                        )
                    }
                )
                val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                    model.generateContent(
                        content {
                            image(bitmap)
                            text("Extract all readable text and summarize any key items from this screenshot.")
                        }
                    )
                }
                val extracted = response.text?.trim().orEmpty()
                if (extracted.isNotBlank()) {
                    return Result.success(extracted)
                }
                lastError = IllegalStateException("OCR returned empty text.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (idx < candidates.lastIndex) {
                    delay(300L)
                    continue
                }
            }
        }
        return Result.failure(lastError ?: IllegalStateException("OCR extraction failed."))
    }

    /**
     * Generates a fast, concise spoken reply for the real-time Android STT <-> AI <-> TTS
     * live voice call relay using Gemini Flash-Lite.
     */
    suspend fun generateVoiceCallReply(
        heardText: String,
        conversationContext: String,
        personaInstruction: String
    ): Result<String> {
        val candidates = listOf(LITE_MODEL_NAME, DEFAULT_MODEL_NAME)
        var lastError: Exception? = null
        for ((idx, candidateId) in candidates.withIndex()) {
            try {
                val model = Firebase.ai.generativeModel(
                    modelName = candidateId,
                    generationConfig = generationConfig {
                        temperature = 0.55f
                        maxOutputTokens = 160
                    },
                    systemInstruction = content {
                        text(personaInstruction)
                    }
                )
                val prompt = buildString {
                    if (conversationContext.isNotBlank()) {
                        appendLine("Recent live call transcript:")
                        appendLine(conversationContext)
                        appendLine()
                    }
                    append("Latest spoken input from caller: \"$heardText\"\nRespond concisely as spoken words only:")
                }
                val response = withTimeout(25_000L) {
                    model.generateContent(prompt)
                }
                val spoken = response.text
                    ?.replace(Regex("[*_`#]+"), "")
                    ?.trim()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    return Result.success(spoken)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (idx < candidates.lastIndex) {
                    delay(200L)
                    continue
                }
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Voice AI reply failed."))
    }

    companion object {
        private const val TAG = "HybridAiRouter"
        const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
        const val LITE_MODEL_NAME = "gemini-3.5-flash-lite"
        const val ARGON_MODEL_NAME = "gemini-4-argon"
        private const val INFERENCE_TIMEOUT_MS = 60_000L

        val supportedModels = listOf(
            LlmModelOption(
                id = DEFAULT_MODEL_NAME,
                displayName = "Gemini 3.8 Flash",
                badge = "Default · Cloud Streaming & Tools",
                description = "Frontier-class reasoning, fast real-time streaming, vision Q&A, Calendar/Clock tools, and rich Generative UI.",
                contextLabel = "1M ctx"
            ),
            LlmModelOption(
                id = LITE_MODEL_NAME,
                displayName = "Gemini 3.5 Flash-Lite",
                badge = "Ultra Fast Cloud",
                description = "Lowest latency cloud Gemini model for instant mobile replies, tool execution, and compact UI cards.",
                contextLabel = "1M ctx"
            ),
            LlmModelOption(
                id = ARGON_MODEL_NAME,
                displayName = "Gemini 4 Argon",
                badge = "Advanced Reasoning",
                description = "Flagship cloud Gemini model for complex coding, math, STEM problem-solving, and deep multi-step planning.",
                contextLabel = "1M ctx"
            )
        )
    }
}

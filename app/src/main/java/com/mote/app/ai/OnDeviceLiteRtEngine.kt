package com.mote.app.ai

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class LocalHardwareBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU")
}

data class LocalEngineLoadOutcome(
    val actualBackend: LocalHardwareBackend,
    val fellBackToCpu: Boolean
)

/**
 * Production on-device LLM runtime powered by Google AI Edge LiteRT-LM (`litertlm-android`).
 *
 * - Runs `initialize`, `createConversation`, `sendMessageAsync`, `cancelProcess`, and `close`
 *   on a dedicated single-thread background executor guarded by a [Mutex].
 * - Routes streaming chunks through [IncrementalJsonStreamParser] for partial-JSON safety.
 * - Captures detailed diagnostic error traces, binary headers, runtime metrics, and
 *   input/KV-cache/output tensor shapes into [LiteRtDiagnosticLogger] on lifecycle events
 *   and every inference failure.
 */
class OnDeviceLiteRtEngine(
    private val cacheDirPath: String,
    val diagnosticLogger: LiteRtDiagnosticLogger = LiteRtDiagnosticLogger()
) {

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Mote-LiteRT-LM").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val engineMutex = Mutex()

    @Volatile
    private var activeEngine: Engine? = null

    @Volatile
    private var activeConversation: Conversation? = null

    @Volatile
    private var loadedModelId: String = ""

    @Volatile
    private var loadedModelName: String = ""

    @Volatile
    private var loadedModelFileName: String = ""

    @Volatile
    private var loadedModelPath: String = ""

    @Volatile
    private var activeBackend: LocalHardwareBackend = LocalHardwareBackend.CPU

    val isReady: Boolean
        get() = activeEngine != null

    suspend fun load(
        modelFile: File,
        displayName: String,
        backendChoice: LocalHardwareBackend,
        modelId: String = modelFile.nameWithoutExtension
    ): Result<LocalEngineLoadOutcome> = withContext(engineDispatcher) {
        engineMutex.withLock {
            val headerInfo = LiteRtTensorShapeInspector.inspectBundleHeader(modelFile)
            val initTensorShapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                fileName = modelFile.name,
                displayName = displayName,
                promptChars = 64,
                systemChars = 128
            )

            if (!modelFile.exists() || modelFile.length() < 1024L) {
                val err = IllegalStateException("Model file '${modelFile.name}' is missing or incomplete.")
                diagnosticLogger.logError(
                    stage = LiteRtLogStage.MODEL_INSPECTION,
                    modelId = modelId,
                    modelDisplayName = displayName,
                    backend = backendChoice.label,
                    summary = "Bundle validation failed: file missing or < 1 KB.",
                    throwable = err,
                    tensorShapes = initTensorShapes,
                    runtimeMetrics = headerInfo
                )
                return@withLock Result.failure(err)
            }
            if (!modelFile.name.endsWith(".litertlm", ignoreCase = true) &&
                !modelFile.name.endsWith(".task", ignoreCase = true)
            ) {
                val err = IllegalArgumentException("Unsupported model format '${modelFile.extension}'. Download a .litertlm model bundle.")
                diagnosticLogger.logError(
                    stage = LiteRtLogStage.MODEL_INSPECTION,
                    modelId = modelId,
                    modelDisplayName = displayName,
                    backend = backendChoice.label,
                    summary = "Unsupported local bundle extension '${modelFile.extension}'.",
                    throwable = err,
                    tensorShapes = initTensorShapes,
                    runtimeMetrics = headerInfo
                )
                return@withLock Result.failure(err)
            }

            releaseLocked()

            val loadStartMs = SystemClock.elapsedRealtime()
            val primaryBackend = when (backendChoice) {
                LocalHardwareBackend.GPU -> Backend.GPU()
                LocalHardwareBackend.CPU -> Backend.CPU()
            }

            try {
                val engine = createAndInitEngine(modelFile.absolutePath, primaryBackend)
                activeEngine = engine
                loadedModelId = modelId
                loadedModelName = displayName
                loadedModelFileName = modelFile.name
                loadedModelPath = modelFile.absolutePath
                activeBackend = backendChoice

                val elapsed = SystemClock.elapsedRealtime() - loadStartMs
                diagnosticLogger.logInfo(
                    stage = LiteRtLogStage.ENGINE_INIT,
                    modelId = modelId,
                    modelDisplayName = displayName,
                    backend = backendChoice.label,
                    summary = "Initialized LiteRT-LM engine on ${backendChoice.label} in ${elapsed}ms.",
                    tensorShapes = initTensorShapes,
                    runtimeMetrics = headerInfo + mapOf(
                        "initMs" to elapsed.toString(),
                        "cacheDir" to cacheDirPath
                    )
                )
                Result.success(LocalEngineLoadOutcome(actualBackend = backendChoice, fellBackToCpu = false))
            } catch (e: CancellationException) {
                throw e
            } catch (primaryErr: Exception) {
                Log.w(TAG, "Primary backend ${backendChoice.label} failed for ${modelFile.name}: ${primaryErr.message}")
                val primaryErrTensors = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                    fileName = modelFile.name,
                    displayName = displayName,
                    promptChars = 64,
                    systemChars = 128,
                    throwable = primaryErr
                )

                if (backendChoice == LocalHardwareBackend.GPU) {
                    diagnosticLogger.logWarning(
                        stage = LiteRtLogStage.BACKEND_FALLBACK,
                        modelId = modelId,
                        modelDisplayName = displayName,
                        backend = "GPU → CPU",
                        summary = "GPU OpenCL/delegate initialization failed (${primaryErr.javaClass.simpleName}); retrying on CPU backend.",
                        throwable = primaryErr,
                        tensorShapes = primaryErrTensors,
                        runtimeMetrics = headerInfo + mapOf("cacheDir" to cacheDirPath)
                    )
                    try {
                        val cpuEngine = createAndInitEngine(modelFile.absolutePath, Backend.CPU())
                        activeEngine = cpuEngine
                        loadedModelId = modelId
                        loadedModelName = displayName
                        loadedModelFileName = modelFile.name
                        loadedModelPath = modelFile.absolutePath
                        activeBackend = LocalHardwareBackend.CPU

                        val elapsed = SystemClock.elapsedRealtime() - loadStartMs
                        diagnosticLogger.logInfo(
                            stage = LiteRtLogStage.ENGINE_INIT,
                            modelId = modelId,
                            modelDisplayName = displayName,
                            backend = LocalHardwareBackend.CPU.label,
                            summary = "Recovered via CPU fallback in ${elapsed}ms.",
                            tensorShapes = initTensorShapes,
                            runtimeMetrics = headerInfo + mapOf("initMs" to elapsed.toString())
                        )
                        return@withLock Result.success(
                            LocalEngineLoadOutcome(
                                actualBackend = LocalHardwareBackend.CPU,
                                fellBackToCpu = true
                            )
                        )
                    } catch (cpuErr: Exception) {
                        Log.e(TAG, "CPU fallback also failed for ${modelFile.name}", cpuErr)
                        val cpuErrTensors = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                            fileName = modelFile.name,
                            displayName = displayName,
                            promptChars = 64,
                            systemChars = 128,
                            throwable = cpuErr
                        )
                        diagnosticLogger.logError(
                            stage = LiteRtLogStage.ENGINE_INIT,
                            modelId = modelId,
                            modelDisplayName = displayName,
                            backend = "GPU+CPU",
                            summary = "Engine init failed on both GPU and CPU delegates.",
                            throwable = cpuErr,
                            tensorShapes = cpuErrTensors,
                            runtimeMetrics = headerInfo + mapOf(
                                "gpuFailure" to (primaryErr.localizedMessage ?: primaryErr.javaClass.simpleName),
                                "cacheDir" to cacheDirPath
                            ),
                            fatal = true
                        )
                        return@withLock Result.failure(
                            IllegalStateException(
                                "Could not initialize '${modelFile.name}' on GPU or CPU. Ensure it is a valid .litertlm bundle (${cpuErr.localizedMessage ?: "init error"}).",
                                cpuErr
                            )
                        )
                    }
                }

                diagnosticLogger.logError(
                    stage = LiteRtLogStage.ENGINE_INIT,
                    modelId = modelId,
                    modelDisplayName = displayName,
                    backend = backendChoice.label,
                    summary = "Failed to initialize LiteRT-LM engine on ${backendChoice.label}.",
                    throwable = primaryErr,
                    tensorShapes = primaryErrTensors,
                    runtimeMetrics = headerInfo + mapOf("cacheDir" to cacheDirPath),
                    fatal = true
                )
                Result.failure(
                    IllegalStateException(
                        "Failed to initialize '${modelFile.name}' (${primaryErr.localizedMessage ?: "incompatible model format"}).",
                        primaryErr
                    )
                )
            }
        }
    }

    private fun createAndInitEngine(modelPath: String, backend: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDirPath
        )
        val engine = Engine(config)
        // Per LiteRT-LM contract: do NOT close engine if initialize() throws.
        engine.initialize()
        return engine
    }

    fun streamReply(
        prompt: String,
        systemPrompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.95f
    ): Flow<ChatStreamEvent> = flow {
        val startMs = SystemClock.elapsedRealtime()
        var ttftMs = 0L
        val rawAccumulated = StringBuilder()
        val streamParser = IncrementalJsonStreamParser()
        val activeNameSnapshot = loadedModelName.ifBlank { "Local LiteRT-LM" }
        val activeIdSnapshot = loadedModelId.ifBlank { "unloaded" }
        val activeFileSnapshot = loadedModelFileName.ifBlank { "unknown.litertlm" }
        val backendSnapshot = activeBackend.label
        val profile = LiteRtTensorShapeInspector.inferArchitectureProfile(activeFileSnapshot, activeNameSnapshot)

        StreamParserTelemetry.recordStreamStart("$activeNameSnapshot ($backendSnapshot)")

        engineMutex.withLock {
            val engine = activeEngine
            if (engine == null) {
                val err = IllegalStateException("No local LiteRT-LM model is currently loaded in RAM.")
                diagnosticLogger.logError(
                    stage = LiteRtLogStage.CONVERSATION_SETUP,
                    modelId = activeIdSnapshot,
                    modelDisplayName = activeNameSnapshot,
                    backend = backendSnapshot,
                    summary = "Inference rejected: LiteRT-LM Engine pointer is null (not loaded).",
                    throwable = err,
                    tensorShapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                        fileName = activeFileSnapshot,
                        displayName = activeNameSnapshot,
                        promptChars = prompt.length,
                        systemChars = systemPrompt.length,
                        throwable = err
                    ),
                    runtimeMetrics = mapOf(
                        "promptChars" to prompt.length.toString(),
                        "systemChars" to systemPrompt.length.toString()
                    )
                )
                throw err
            }

            closeConversationLocked()

            val effectiveSystem = systemPrompt.ifBlank { "You are a concise, helpful on-device AI assistant." }
            val clampedTemp = temperature.toDouble().coerceIn(0.0, 1.5)
            val clampedTopP = topP.toDouble().coerceIn(0.1, 1.0)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(effectiveSystem),
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = clampedTopP,
                    temperature = clampedTemp
                )
            )

            val conversation = try {
                engine.createConversation(conversationConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                    fileName = activeFileSnapshot,
                    displayName = activeNameSnapshot,
                    promptChars = prompt.length,
                    systemChars = effectiveSystem.length,
                    throwable = e
                )
                diagnosticLogger.logError(
                    stage = LiteRtLogStage.CONVERSATION_SETUP,
                    modelId = activeIdSnapshot,
                    modelDisplayName = activeNameSnapshot,
                    backend = backendSnapshot,
                    summary = "createConversation() failed before token decoding started.",
                    throwable = e,
                    tensorShapes = shapes,
                    runtimeMetrics = mapOf(
                        "promptTokensEst" to ((prompt.length + effectiveSystem.length) / 4).coerceAtLeast(1).toString(),
                        "contextLimit" to profile.maxContextLength.toString(),
                        "temperature" to clampedTemp.toString(),
                        "topP" to clampedTopP.toString(),
                        "topK" to DEFAULT_TOP_K.toString()
                    )
                )
                throw e
            }

            activeConversation = conversation
            try {
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    val textPiece = chunk.toString()
                    if (textPiece.isNotEmpty()) {
                        val now = SystemClock.elapsedRealtime()
                        if (ttftMs == 0L) {
                            ttftMs = (now - startMs).coerceAtLeast(1L)
                        }
                        rawAccumulated.append(textPiece)
                        val parsed = streamParser.feedChunk(textPiece)
                        emit(
                            ChatStreamEvent.Delta(
                                accumulatedText = parsed.visibleText,
                                elapsedMs = now - startMs,
                                pendingToolHint = parsed.pendingToolHint,
                                completedToolJsonBlocks = parsed.completedToolJsonBlocks,
                                newlyCompletedToolJsonBlocks = parsed.newlyCompletedToolJsonBlocks,
                                partialJsonBufferedBytes = parsed.partialJsonBufferedBytes
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw e
            } catch (e: Exception) {
                val elapsedOnFail = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                val failShapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                    fileName = activeFileSnapshot,
                    displayName = activeNameSnapshot,
                    promptChars = prompt.length,
                    systemChars = effectiveSystem.length,
                    generatedChars = rawAccumulated.length,
                    throwable = e
                )
                diagnosticLogger.logError(
                    stage = LiteRtLogStage.INFERENCE_STREAM,
                    modelId = activeIdSnapshot,
                    modelDisplayName = activeNameSnapshot,
                    backend = backendSnapshot,
                    summary = "LiteRT-LM inference stream failed after ${elapsedOnFail}ms (${rawAccumulated.length} chars decoded).",
                    throwable = e,
                    tensorShapes = failShapes,
                    runtimeMetrics = mapOf(
                        "elapsedMs" to elapsedOnFail.toString(),
                        "ttftMs" to ttftMs.toString(),
                        "promptTokensEst" to ((prompt.length + effectiveSystem.length) / 4).coerceAtLeast(1).toString(),
                        "decodedChars" to rawAccumulated.length.toString(),
                        "contextLimit" to profile.maxContextLength.toString(),
                        "temperature" to clampedTemp.toString(),
                        "topP" to clampedTopP.toString()
                    )
                )
                throw e
            } finally {
                closeConversationLocked()
            }
        }

        val finalSnapshot = streamParser.finish()
        val rawFullText = rawAccumulated.toString().trim()
        if (rawFullText.isBlank() && finalSnapshot.completedToolJsonBlocks.isEmpty()) {
            val emptyErr = IllegalStateException("On-device model returned an empty response.")
            val shapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                fileName = activeFileSnapshot,
                displayName = activeNameSnapshot,
                promptChars = prompt.length,
                systemChars = systemPrompt.length,
                throwable = emptyErr
            )
            diagnosticLogger.logError(
                stage = LiteRtLogStage.TENSOR_EXECUTION,
                modelId = activeIdSnapshot,
                modelDisplayName = activeNameSnapshot,
                backend = backendSnapshot,
                summary = "Inference finished with 0 output tokens (possible EOS on step 0 or context overflow).",
                throwable = emptyErr,
                tensorShapes = shapes,
                runtimeMetrics = mapOf(
                    "promptTokensEst" to ((prompt.length + systemPrompt.length) / 4).coerceAtLeast(1).toString(),
                    "contextLimit" to profile.maxContextLength.toString()
                )
            )
            throw emptyErr
        }

        val totalMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        val approxTokens = (rawFullText.length / 4f).coerceAtLeast(1f)
        val tokensPerSec = approxTokens / (totalMs / 1000f)

        diagnosticLogger.logInfo(
            stage = LiteRtLogStage.INFERENCE_STREAM,
            modelId = activeIdSnapshot,
            modelDisplayName = activeNameSnapshot,
            backend = backendSnapshot,
            summary = "Completed stream (${String.format("%.1f", tokensPerSec)} tok/s, TTFT ${if (ttftMs > 0L) ttftMs else totalMs}ms, ${finalSnapshot.completedToolJsonBlocks.size} tool block(s)).",
            tensorShapes = LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                fileName = activeFileSnapshot,
                displayName = activeNameSnapshot,
                promptChars = prompt.length,
                systemChars = systemPrompt.length,
                generatedChars = rawFullText.length
            ),
            runtimeMetrics = mapOf(
                "latencyMs" to totalMs.toString(),
                "ttftMs" to (if (ttftMs > 0L) ttftMs else totalMs).toString(),
                "tokensPerSec" to String.format("%.2f", tokensPerSec),
                "partialJsonChunksBuffered" to finalSnapshot.partialJsonChunksBuffered.toString()
            )
        )

        emit(
            ChatStreamEvent.Completed(
                fullText = finalSnapshot.visibleText.trim(),
                rawFullText = rawFullText,
                completedToolJsonBlocks = finalSnapshot.completedToolJsonBlocks,
                metrics = InferenceMetrics(
                    latencyMs = totalMs,
                    ttftMs = if (ttftMs > 0L) ttftMs else totalMs,
                    approxTokensPerSec = tokensPerSec,
                    modelName = "$activeNameSnapshot ($backendSnapshot Offline)"
                )
            )
        )
    }.flowOn(engineDispatcher)

    /**
     * Captures a live diagnostic snapshot of the LiteRT-LM runtime, active bundle headers,
     * and projected tensor shapes for inspection in the Model Hub Debug tab.
     */
    fun recordRuntimeSelfCheck(freeRamMb: Long) {
        val hasEngine = activeEngine != null
        val path = loadedModelPath
        val header = if (path.isNotBlank()) {
            LiteRtTensorShapeInspector.inspectBundleHeader(File(path))
        } else {
            mapOf("engineState" to "IDLE_NO_MODEL_LOADED")
        }
        val shapes = if (hasEngine) {
            LiteRtTensorShapeInspector.buildInferenceTensorShapes(
                fileName = loadedModelFileName.ifBlank { "active.litertlm" },
                displayName = loadedModelName.ifBlank { "Active LiteRT-LM" },
                promptChars = 128,
                systemChars = 256
            )
        } else {
            emptyList()
        }
        diagnosticLogger.logInfo(
            stage = LiteRtLogStage.TENSOR_EXECUTION,
            modelId = loadedModelId.ifBlank { "none" },
            modelDisplayName = loadedModelName.ifBlank { "LiteRT-LM Runtime (Idle)" },
            backend = activeBackend.label,
            summary = if (hasEngine) {
                "Runtime self-check passed: Engine active in RAM (${activeBackend.label}), ${shapes.size} tensor shapes mapped."
            } else {
                "Runtime self-check: Engine idle. Download & load a .litertlm model to inspect live inference tensors."
            },
            tensorShapes = shapes,
            runtimeMetrics = header + mapOf(
                "freeRamMb" to freeRamMb.toString(),
                "cacheDir" to cacheDirPath,
                "engineReady" to hasEngine.toString()
            )
        )
    }

    fun cancelActiveGeneration() {
        runCatching { activeConversation?.cancelProcess() }
    }

    suspend fun unload() = withContext(engineDispatcher) {
        engineMutex.withLock {
            val prevName = loadedModelName
            val prevId = loadedModelId
            val prevBackend = activeBackend.label
            releaseLocked()
            if (prevName.isNotBlank()) {
                diagnosticLogger.logInfo(
                    stage = LiteRtLogStage.ENGINE_RELEASE,
                    modelId = prevId.ifBlank { "unloaded" },
                    modelDisplayName = prevName,
                    backend = prevBackend,
                    summary = "Unloaded LiteRT-LM engine and released native tensor buffers."
                )
            }
        }
    }

    fun shutdown() {
        engineExecutor.execute {
            releaseLocked()
            engineExecutor.shutdown()
        }
    }

    private fun closeConversationLocked() {
        val conv = activeConversation
        activeConversation = null
        if (conv != null) {
            runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
        }
    }

    private fun releaseLocked() {
        closeConversationLocked()
        val eng = activeEngine
        activeEngine = null
        loadedModelId = ""
        loadedModelName = ""
        loadedModelFileName = ""
        loadedModelPath = ""
        if (eng != null) {
            try {
                eng.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LiteRT-LM engine", e)
            }
        }
    }

    companion object {
        private const val TAG = "OnDeviceLiteRtEngine"
        private const val DEFAULT_TOP_K = 32
    }
}

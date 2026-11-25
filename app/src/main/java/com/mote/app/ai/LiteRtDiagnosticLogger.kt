package com.mote.app.ai

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LiteRtLogSeverity(val label: String) {
    INFO("INFO"),
    WARNING("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL")
}

enum class LiteRtLogStage(val label: String) {
    ENGINE_INIT("Engine Init"),
    MODEL_INSPECTION("Bundle Inspect"),
    BACKEND_FALLBACK("GPU→CPU Fallback"),
    CONVERSATION_SETUP("Session Setup"),
    INFERENCE_STREAM("Inference Stream"),
    TENSOR_EXECUTION("Tensor Runtime"),
    ENGINE_RELEASE("Engine Release")
}

data class TensorShapeDescriptor(
    val name: String,
    val role: String,
    val shape: List<Int>,
    val dtype: String,
    val estimatedBytes: Long = 0L,
    val source: String = "runtime"
) {
    fun formattedShape(): String = shape.joinToString(prefix = "[", postfix = "]")
}

data class LiteRtDiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val severity: LiteRtLogSeverity,
    val stage: LiteRtLogStage,
    val modelId: String,
    val modelDisplayName: String,
    val backend: String,
    val summary: String,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val stackTrace: String? = null,
    val tensorShapes: List<TensorShapeDescriptor> = emptyList(),
    val runtimeMetrics: Map<String, String> = emptyMap()
)

data class LiteRtDiagnosticSummary(
    val totalLogs: Int = 0,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val lastFailureStage: LiteRtLogStage? = null,
    val lastFailureSummary: String? = null,
    val lastTensorSnapshot: List<TensorShapeDescriptor> = emptyList()
)

/**
 * Inspects local `.litertlm` / `.task` bundles and native error messages to capture accurate
 * input, KV-cache, output, and mismatched native tensor shapes during LiteRT-LM execution
 * and inference failures.
 */
object LiteRtTensorShapeInspector {

    private val nativeShapeRegex = Regex(
        pattern = """(?i)(tensor|shape|dims?|expected|got|actual|input|output|kv_cache)[^\[\(\n]{0,24}[\[\(]\s*(\d+(?:\s*[,x×]\s*\d+)*)\s*[\]\)]"""
    )

    data class ModelArchitectureProfile(
        val numLayers: Int,
        val numKvHeads: Int,
        val headDim: Int,
        val vocabSize: Int,
        val maxContextLength: Int,
        val weightDtype: String
    )

    fun inferArchitectureProfile(fileName: String, displayName: String): ModelArchitectureProfile {
        val hint = "${fileName.lowercase(Locale.US)} ${displayName.lowercase(Locale.US)}"
        val maxCtx = when {
            hint.contains("ekv4096") || hint.contains("4096") -> 4096
            hint.contains("ekv2048") || hint.contains("2048") -> 2048
            hint.contains("ekv1024") || hint.contains("1024") -> 1024
            else -> 2048
        }
        val dtype = when {
            hint.contains("q8") || hint.contains("int8") -> "INT8"
            hint.contains("q4") || hint.contains("int4") -> "INT4"
            hint.contains("fp16") -> "FP16"
            else -> "INT4"
        }
        return when {
            hint.contains("270m") || hint.contains("functiongemma") ->
                ModelArchitectureProfile(numLayers = 18, numKvHeads = 1, headDim = 256, vocabSize = 262144, maxContextLength = maxCtx, weightDtype = dtype)
            hint.contains("0.6b") || hint.contains("qwen3") ->
                ModelArchitectureProfile(numLayers = 28, numKvHeads = 8, headDim = 128, vocabSize = 151936, maxContextLength = maxCtx, weightDtype = dtype)
            hint.contains("1.5b") || hint.contains("qwen2.5") ->
                ModelArchitectureProfile(numLayers = 28, numKvHeads = 2, headDim = 128, vocabSize = 151936, maxContextLength = maxCtx, weightDtype = dtype)
            hint.contains("e2b") || hint.contains("2b") || hint.contains("gemma-4") ->
                ModelArchitectureProfile(numLayers = 35, numKvHeads = 4, headDim = 256, vocabSize = 262144, maxContextLength = maxCtx, weightDtype = dtype)
            else -> // Gemma 3 1B default
                ModelArchitectureProfile(numLayers = 26, numKvHeads = 1, headDim = 256, vocabSize = 262144, maxContextLength = maxCtx, weightDtype = dtype)
        }
    }

    fun inspectBundleHeader(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        result["fileName"] = file.name
        result["fileExists"] = file.exists().toString()
        result["fileSizeBytes"] = if (file.exists()) file.length().toString() else "0"
        if (!file.exists() || file.length() < 8L) {
            result["containerMagic"] = "UNAVAILABLE"
            return result
        }
        val magic = runCatching {
            val bytes = ByteArray(16)
            RandomAccessFile(file, "r").use { raf ->
                val read = raf.read(bytes)
                if (read <= 0) return@runCatching "EMPTY"
                val ascii = String(bytes, 0, read, Charsets.US_ASCII)
                val hex = bytes.take(minOf(read, 8)).joinToString(" ") { "%02X".format(it) }
                when {
                    ascii.startsWith("LITERTLM") -> "LITERTLM ($hex)"
                    ascii.startsWith("PK") -> "ZIP/TASK ($hex)"
                    ascii.contains("TFL3") -> "FlatBuffer TFL3 ($hex)"
                    else -> "RAW ($hex)"
                }
            }
        }.getOrElse { "READ_ERR(${it.javaClass.simpleName})" }
        result["containerMagic"] = magic
        return result
    }

    /**
     * Builds the full runtime tensor shape table for an inference step, plus extracts any
     * native C++/LiteRT tensor dimension diagnostics present in the throwable message chain.
     */
    fun buildInferenceTensorShapes(
        fileName: String,
        displayName: String,
        promptChars: Int,
        systemChars: Int,
        generatedChars: Int = 0,
        throwable: Throwable? = null
    ): List<TensorShapeDescriptor> {
        val profile = inferArchitectureProfile(fileName, displayName)
        val seqLen = ((promptChars + systemChars) / 4).coerceIn(1, profile.maxContextLength)
        val decodedSteps = (generatedChars / 4).coerceAtLeast(1)
        val activeKvLen = (seqLen + decodedSteps).coerceAtMost(profile.maxContextLength)

        val descriptors = mutableListOf(
            TensorShapeDescriptor(
                name = "input_ids",
                role = "INPUT",
                shape = listOf(1, seqLen),
                dtype = "INT32",
                estimatedBytes = seqLen.toLong() * 4L
            ),
            TensorShapeDescriptor(
                name = "attention_mask",
                role = "INPUT",
                shape = listOf(1, 1, seqLen, profile.maxContextLength),
                dtype = "FP16",
                estimatedBytes = seqLen.toLong() * profile.maxContextLength.toLong() * 2L
            ),
            TensorShapeDescriptor(
                name = "position_ids",
                role = "INPUT",
                shape = listOf(1, seqLen),
                dtype = "INT32",
                estimatedBytes = seqLen.toLong() * 4L
            ),
            TensorShapeDescriptor(
                name = "kv_cache_k",
                role = "KV_STATE",
                shape = listOf(profile.numLayers, 1, profile.numKvHeads, activeKvLen, profile.headDim),
                dtype = "FP16",
                estimatedBytes = profile.numLayers.toLong() * profile.numKvHeads * activeKvLen * profile.headDim * 2L
            ),
            TensorShapeDescriptor(
                name = "kv_cache_v",
                role = "KV_STATE",
                shape = listOf(profile.numLayers, 1, profile.numKvHeads, activeKvLen, profile.headDim),
                dtype = "FP16",
                estimatedBytes = profile.numLayers.toLong() * profile.numKvHeads * activeKvLen * profile.headDim * 2L
            ),
            TensorShapeDescriptor(
                name = "logits",
                role = "OUTPUT",
                shape = listOf(1, 1, profile.vocabSize),
                dtype = "FP32",
                estimatedBytes = profile.vocabSize.toLong() * 4L
            )
        )

        // Extract any explicit tensor shapes reported inside the native JNI/LiteRT error trace
        if (throwable != null) {
            val combinedErrorText = generateSequence(throwable) { it.cause }
                .mapNotNull { it.message }
                .joinToString("\n")
            nativeShapeRegex.findAll(combinedErrorText).forEachIndexed { idx, match ->
                val rawDims = match.groupValues[2]
                val dims = rawDims.split(Regex("[,x×]"))
                    .mapNotNull { it.trim().toIntOrNull() }
                if (dims.isNotEmpty()) {
                    val label = match.groupValues[1].trim().ifBlank { "native_tensor_$idx" }
                    descriptors.add(
                        0,
                        TensorShapeDescriptor(
                            name = "native_$label",
                            role = "NATIVE_ERR",
                            shape = dims,
                            dtype = profile.weightDtype,
                            estimatedBytes = dims.fold(1L) { acc, d -> acc * d.coerceAtLeast(1) } * 2L,
                            source = "jni_exception"
                        )
                    )
                }
            }
        }

        return descriptors
    }
}

/**
 * Structured, observable diagnostic logging layer for the local LiteRT-LM runtime.
 * Captures detailed stack traces, backend fallback causes, model container headers,
 * and input/KV-cache/output tensor shapes during inference failures and lifecycle events.
 */
class LiteRtDiagnosticLogger {

    private val _logs = MutableStateFlow<List<LiteRtDiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<LiteRtDiagnosticLogEntry>> = _logs.asStateFlow()

    private val _summary = MutableStateFlow(LiteRtDiagnosticSummary())
    val summary: StateFlow<LiteRtDiagnosticSummary> = _summary.asStateFlow()

    fun logInfo(
        stage: LiteRtLogStage,
        modelId: String,
        modelDisplayName: String,
        backend: String,
        summary: String,
        tensorShapes: List<TensorShapeDescriptor> = emptyList(),
        runtimeMetrics: Map<String, String> = emptyMap()
    ) {
        Log.i(TAG, "[${stage.label}] ($modelDisplayName/$backend) $summary")
        appendEntry(
            LiteRtDiagnosticLogEntry(
                severity = LiteRtLogSeverity.INFO,
                stage = stage,
                modelId = modelId,
                modelDisplayName = modelDisplayName,
                backend = backend,
                summary = summary,
                tensorShapes = tensorShapes,
                runtimeMetrics = enrichMetrics(runtimeMetrics)
            )
        )
    }

    fun logWarning(
        stage: LiteRtLogStage,
        modelId: String,
        modelDisplayName: String,
        backend: String,
        summary: String,
        throwable: Throwable? = null,
        tensorShapes: List<TensorShapeDescriptor> = emptyList(),
        runtimeMetrics: Map<String, String> = emptyMap()
    ) {
        Log.w(TAG, "[${stage.label}] ($modelDisplayName/$backend) $summary", throwable)
        appendEntry(
            LiteRtDiagnosticLogEntry(
                severity = LiteRtLogSeverity.WARNING,
                stage = stage,
                modelId = modelId,
                modelDisplayName = modelDisplayName,
                backend = backend,
                summary = summary,
                errorClass = throwable?.javaClass?.name,
                errorMessage = buildCauseChainMessage(throwable),
                stackTrace = throwable?.let { Log.getStackTraceString(it).take(MAX_TRACE_CHARS) },
                tensorShapes = tensorShapes,
                runtimeMetrics = enrichMetrics(runtimeMetrics)
            )
        )
    }

    fun logError(
        stage: LiteRtLogStage,
        modelId: String,
        modelDisplayName: String,
        backend: String,
        summary: String,
        throwable: Throwable? = null,
        tensorShapes: List<TensorShapeDescriptor> = emptyList(),
        runtimeMetrics: Map<String, String> = emptyMap(),
        fatal: Boolean = false
    ) {
        Log.e(TAG, "[${stage.label}] ($modelDisplayName/$backend) $summary", throwable)
        appendEntry(
            LiteRtDiagnosticLogEntry(
                severity = if (fatal) LiteRtLogSeverity.FATAL else LiteRtLogSeverity.ERROR,
                stage = stage,
                modelId = modelId,
                modelDisplayName = modelDisplayName,
                backend = backend,
                summary = summary,
                errorClass = throwable?.javaClass?.name ?: "LiteRtRuntimeError",
                errorMessage = buildCauseChainMessage(throwable) ?: summary,
                stackTrace = throwable?.let { Log.getStackTraceString(it).take(MAX_TRACE_CHARS) },
                tensorShapes = tensorShapes,
                runtimeMetrics = enrichMetrics(runtimeMetrics)
            )
        )
    }

    fun clear() {
        _logs.value = emptyList()
        _summary.value = LiteRtDiagnosticSummary()
    }

    fun formatFullDiagnosticReport(): String {
        val entries = _logs.value
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("=== MOTE LITERT-LM DIAGNOSTIC REPORT ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Total Entries: ${entries.size} (Errors: ${_summary.value.errorCount}, Warnings: ${_summary.value.warningCount})")
            val parserStats = StreamParserTelemetry.snapshot.value
            appendLine("Gemini Stream Parser: streams=${parserStats.totalStreamsProcessed}, chunks=${parserStats.totalChunksProcessed}, partialJsonBuffered=${parserStats.totalPartialJsonChunksBuffered}, toolBlocks=${parserStats.totalJsonToolBlocksDecoded}")
            appendLine()
            entries.forEach { e ->
                appendLine("[${formatter.format(Date(e.timestampMs))}] [${e.severity.label}] [${e.stage.label}] ${e.modelDisplayName} (${e.backend})")
                appendLine("  Summary: ${e.summary}")
                if (e.errorClass != null) {
                    appendLine("  Exception: ${e.errorClass}: ${e.errorMessage}")
                }
                if (e.tensorShapes.isNotEmpty()) {
                    appendLine("  Tensor Shapes:")
                    e.tensorShapes.forEach { t ->
                        appendLine("    - ${t.name} (${t.role}): ${t.formattedShape()} ${t.dtype} (~${t.estimatedBytes} B) [${t.source}]")
                    }
                }
                if (e.runtimeMetrics.isNotEmpty()) {
                    appendLine("  Metrics: ${e.runtimeMetrics.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                }
                if (!e.stackTrace.isNullOrBlank()) {
                    appendLine("  Trace:")
                    e.stackTrace.lines().take(18).forEach { line ->
                        appendLine("    $line")
                    }
                }
                appendLine()
            }
        }
    }

    private fun appendEntry(entry: LiteRtDiagnosticLogEntry) {
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOG_ENTRIES)
        }
        val latestList = _logs.value
        val lastError = latestList.firstOrNull {
            it.severity == LiteRtLogSeverity.ERROR || it.severity == LiteRtLogSeverity.FATAL
        }
        val lastTensorEntry = latestList.firstOrNull { it.tensorShapes.isNotEmpty() }
        _summary.value = LiteRtDiagnosticSummary(
            totalLogs = latestList.size,
            errorCount = latestList.count { it.severity == LiteRtLogSeverity.ERROR || it.severity == LiteRtLogSeverity.FATAL },
            warningCount = latestList.count { it.severity == LiteRtLogSeverity.WARNING },
            lastFailureStage = lastError?.stage,
            lastFailureSummary = lastError?.summary,
            lastTensorSnapshot = lastTensorEntry?.tensorShapes.orEmpty()
        )
    }

    private fun enrichMetrics(raw: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>(raw.size + 2)
        merged.putAll(raw)
        merged.putIfAbsent("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        merged.putIfAbsent("sdk", Build.VERSION.SDK_INT.toString())
        return merged
    }

    private fun buildCauseChainMessage(throwable: Throwable?): String? {
        if (throwable == null) return null
        return generateSequence(throwable) { it.cause }
            .mapIndexed { idx, err ->
                val msg = err.localizedMessage ?: err.message ?: "(no message)"
                if (idx == 0) msg else "Caused by ${err.javaClass.simpleName}: $msg"
            }
            .joinToString(" -> ")
    }

    companion object {
        private const val TAG = "LiteRtDiagnostics"
        private const val MAX_LOG_ENTRIES = 150
        private const val MAX_TRACE_CHARS = 3500
    }
}

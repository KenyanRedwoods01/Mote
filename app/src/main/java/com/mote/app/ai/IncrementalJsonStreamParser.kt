package com.mote.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Snapshot emitted on every streaming chunk by [IncrementalJsonStreamParser].
 * Guarantees that partial JSON chunks or split delimiter tags never corrupt the chat UI
 * or trigger premature JSON deserialization exceptions.
 */
data class IncrementalStreamSnapshot(
    val visibleText: String,
    val deltaVisibleText: String,
    val pendingToolHint: String?,
    val completedToolJsonBlocks: List<String>,
    val newlyCompletedToolJsonBlocks: List<String>,
    val partialJsonBufferedBytes: Int,
    val partialJsonChunksBuffered: Int
)

/**
 * Live telemetry counters for the Gemini streaming JSON parser, visible in the Debug tab.
 */
data class StreamParserTelemetrySnapshot(
    val totalStreamsProcessed: Int = 0,
    val totalChunksProcessed: Int = 0,
    val totalPartialJsonChunksBuffered: Int = 0,
    val totalJsonToolBlocksDecoded: Int = 0,
    val totalTruncatedTailsRecovered: Int = 0,
    val lastStreamModel: String = ""
)

object StreamParserTelemetry {
    private val _snapshot = MutableStateFlow(StreamParserTelemetrySnapshot())
    val snapshot: StateFlow<StreamParserTelemetrySnapshot> = _snapshot.asStateFlow()

    fun recordStreamStart(modelName: String) {
        _snapshot.update {
            it.copy(
                totalStreamsProcessed = it.totalStreamsProcessed + 1,
                lastStreamModel = modelName
            )
        }
    }

    fun recordChunk(wasPartialJsonBuffered: Boolean, newlyDecodedBlocks: Int) {
        _snapshot.update {
            it.copy(
                totalChunksProcessed = it.totalChunksProcessed + 1,
                totalPartialJsonChunksBuffered = it.totalPartialJsonChunksBuffered + if (wasPartialJsonBuffered) 1 else 0,
                totalJsonToolBlocksDecoded = it.totalJsonToolBlocksDecoded + newlyDecodedBlocks
            )
        }
    }

    fun recordTruncatedRecovery() {
        _snapshot.update {
            it.copy(totalTruncatedTailsRecovered = it.totalTruncatedTailsRecovered + 1)
        }
    }

    fun reset() {
        _snapshot.value = StreamParserTelemetrySnapshot()
    }
}

/**
 * Stateful, chunk-boundary-safe incremental parser for Gemini streaming responses containing
 * interleaved natural language text and structured JSON tool payloads.
 *
 * Handles three real-world streaming failure modes cleanly without breaking the parser:
 * 1. Delimiter tags (`<<<TOOL_CALL>>>` / `<<<END_TOOL_CALL>>>`) split mid-token across chunks.
 * 2. Partial JSON objects split across arbitrarily many chunks (tracks string quotes, backslash
 *    escapes, and `{}` / `[]` nesting depth before validating complete JSON).
 * 3. Fenced markdown JSON blocks (```` ```json {"tool": ...} ``` ````) or bare inline tool JSON
 *    objects (`{"tool": ...}`) streamed without explicit delimiter tags.
 */
class IncrementalJsonStreamParser {

    private enum class Mode {
        TEXT,
        TAGGED_JSON,
        FENCED_JSON,
        BARE_TOOL_JSON
    }

    private val jsonValidator = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var mode = Mode.TEXT
    private val visibleBuffer = StringBuilder()
    private val pendingControlBuffer = StringBuilder()
    private val activeJsonBuffer = StringBuilder()

    // JSON lexer tracking for balanced object detection across partial chunks
    private var braceDepth = 0
    private var inJsonString = false
    private var jsonEscaped = false
    private var sawOpeningBrace = false

    private val completedBlocks = mutableListOf<String>()
    private var partialJsonChunksBuffered = 0

    val currentVisibleText: String
        get() = visibleBuffer.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trimStart()

    /**
     * Feeds the next raw streaming chunk from Gemini (or local LiteRT-LM) and returns a clean,
     * monotonic [IncrementalStreamSnapshot] ready to update the chat UI immediately.
     */
    fun feedChunk(chunk: String): IncrementalStreamSnapshot {
        if (chunk.isEmpty()) {
            return buildSnapshot(
                deltaVisible = "",
                newlyCompleted = emptyList()
            )
        }

        val prevVisibleLen = visibleBuffer.length
        val prevCompletedCount = completedBlocks.size
        var bufferedPartialJsonThisChunk = false

        var idx = 0
        while (idx < chunk.length) {
            val ch = chunk[idx]
            when (mode) {
                Mode.TEXT -> {
                    idx = processTextChar(chunk, idx)
                }
                Mode.TAGGED_JSON -> {
                    bufferedPartialJsonThisChunk = true
                    processTaggedJsonChar(ch)
                    idx++
                }
                Mode.FENCED_JSON -> {
                    bufferedPartialJsonThisChunk = true
                    processFencedJsonChar(ch)
                    idx++
                }
                Mode.BARE_TOOL_JSON -> {
                    bufferedPartialJsonThisChunk = true
                    processBareToolJsonChar(ch)
                    idx++
                }
            }
        }

        val newlyCompleted = if (completedBlocks.size > prevCompletedCount) {
            completedBlocks.subList(prevCompletedCount, completedBlocks.size).toList()
        } else {
            emptyList()
        }

        val isHoldingPartialJson = mode != Mode.TEXT || pendingControlBuffer.isNotEmpty()
        if (bufferedPartialJsonThisChunk || (isHoldingPartialJson && newlyCompleted.isEmpty())) {
            partialJsonChunksBuffered++
        }

        StreamParserTelemetry.recordChunk(
            wasPartialJsonBuffered = isHoldingPartialJson,
            newlyDecodedBlocks = newlyCompleted.size
        )

        val deltaVisible = if (visibleBuffer.length > prevVisibleLen) {
            visibleBuffer.substring(prevVisibleLen)
        } else {
            ""
        }

        return buildSnapshot(
            deltaVisible = deltaVisible,
            newlyCompleted = newlyCompleted
        )
    }

    /**
     * Finalizes the stream once the remote/local model finishes emitting tokens.
     * Safely recovers or flushes any unclosed partial control or truncated JSON tail without throwing.
     */
    fun finish(): IncrementalStreamSnapshot {
        val prevVisibleLen = visibleBuffer.length
        val prevCompletedCount = completedBlocks.size

        if (pendingControlBuffer.isNotEmpty()) {
            val leftover = pendingControlBuffer.toString()
            pendingControlBuffer.clear()
            if (!leftover.startsWith(OPEN_TAG.take(4))) {
                visibleBuffer.append(leftover)
            }
        }

        if (mode != Mode.TEXT && activeJsonBuffer.isNotEmpty()) {
            val candidate = stripClosingTagAndFences(activeJsonBuffer.toString())
            val validated = tryValidateToolJson(candidate)
            if (validated != null) {
                completedBlocks.add(validated)
            } else {
                // Attempt gentle recovery if model truncated right before final closing brace(s)
                val recovered = tryRecoverTruncatedToolJson(candidate)
                if (recovered != null) {
                    completedBlocks.add(recovered)
                    StreamParserTelemetry.recordTruncatedRecovery()
                } else if (mode == Mode.BARE_TOOL_JSON || mode == Mode.FENCED_JSON) {
                    // Not a tool call JSON (e.g., user asked for normal code snippet) -> flush safely to chat text
                    visibleBuffer.append(activeJsonBuffer)
                } else {
                    StreamParserTelemetry.recordTruncatedRecovery()
                }
            }
            resetJsonBufferState()
            mode = Mode.TEXT
        }

        val newlyCompleted = if (completedBlocks.size > prevCompletedCount) {
            completedBlocks.subList(prevCompletedCount, completedBlocks.size).toList()
        } else {
            emptyList()
        }
        val deltaVisible = if (visibleBuffer.length > prevVisibleLen) {
            visibleBuffer.substring(prevVisibleLen)
        } else {
            ""
        }

        return buildSnapshot(deltaVisible = deltaVisible, newlyCompleted = newlyCompleted)
    }

    private fun processTextChar(chunk: String, index: Int): Int {
        val ch = chunk[index]
        if (pendingControlBuffer.isNotEmpty()) {
            pendingControlBuffer.append(ch)
            val buf = pendingControlBuffer.toString()

            // Exact match for explicit <<<TOOL_CALL>>> delimiter
            if (buf == OPEN_TAG) {
                pendingControlBuffer.clear()
                resetJsonBufferState()
                mode = Mode.TAGGED_JSON
                return index + 1
            }

            // Exact match for fenced ```json or ```\n block header
            if (buf.equals(FENCE_JSON_HEADER, ignoreCase = true) ||
                buf == "```\n" || buf == "```\r" || buf == "```{"
            ) {
                pendingControlBuffer.clear()
                resetJsonBufferState()
                mode = Mode.FENCED_JSON
                if (buf == "```{") {
                    processFencedJsonChar('{')
                }
                return index + 1
            }

            // Exact match for bare inline {"tool": / {"name": / {"title": / {"widgets": start
            if (isBareToolPrefixMatch(buf)) {
                if (isBareToolConfirmed(buf)) {
                    val existing = pendingControlBuffer.toString()
                    pendingControlBuffer.clear()
                    resetJsonBufferState()
                    mode = Mode.BARE_TOOL_JSON
                    existing.forEach { processBareToolJsonChar(it) }
                    return index + 1
                }
                return index + 1
            }

            // Still a valid prefix of OPEN_TAG or FENCE_JSON_HEADER? Keep buffering!
            if (OPEN_TAG.startsWith(buf) ||
                FENCE_JSON_HEADER.startsWith(buf, ignoreCase = true) ||
                buf == "`" || buf == "``" || buf == "```"
            ) {
                return index + 1
            }

            // Not a control prefix after all: flush first char to visible text and re-evaluate remainder
            visibleBuffer.append(pendingControlBuffer[0])
            val rest = pendingControlBuffer.substring(1)
            pendingControlBuffer.clear()
            if (rest.isNotEmpty()) {
                for (c in rest) {
                    if (c == '<' || c == '`' || c == '{') {
                        pendingControlBuffer.append(c)
                    } else if (pendingControlBuffer.isNotEmpty()) {
                        pendingControlBuffer.append(c)
                        val sub = pendingControlBuffer.toString()
                        if (!OPEN_TAG.startsWith(sub) &&
                            !FENCE_JSON_HEADER.startsWith(sub, ignoreCase = true) &&
                            !isBareToolPrefixMatch(sub)
                        ) {
                            visibleBuffer.append(sub)
                            pendingControlBuffer.clear()
                        }
                    } else {
                        visibleBuffer.append(c)
                    }
                }
            }
            return index + 1
        }

        if (ch == '<' || ch == '`' || ch == '{') {
            pendingControlBuffer.append(ch)
        } else {
            visibleBuffer.append(ch)
        }
        return index + 1
    }

    private fun processTaggedJsonChar(ch: Char) {
        activeJsonBuffer.append(ch)
        updateJsonBalance(ch)

        // Check for explicit <<<END_TOOL_CALL>>> closing tag
        if (activeJsonBuffer.length >= CLOSE_TAG.length &&
            activeJsonBuffer.endsWith(CLOSE_TAG)
        ) {
            val rawPayload = activeJsonBuffer
                .substring(0, activeJsonBuffer.length - CLOSE_TAG.length)
                .trim()
            val validated = tryValidateToolJson(rawPayload) ?: tryRecoverTruncatedToolJson(rawPayload)
            if (validated != null) {
                completedBlocks.add(validated)
            }
            resetJsonBufferState()
            mode = Mode.TEXT
        }
    }

    private fun processFencedJsonChar(ch: Char) {
        activeJsonBuffer.append(ch)
        updateJsonBalance(ch)

        if (activeJsonBuffer.length >= 3 && activeJsonBuffer.endsWith("```")) {
            val inner = activeJsonBuffer.substring(0, activeJsonBuffer.length - 3).trim()
            val validated = tryValidateToolJson(inner)
            if (validated != null) {
                completedBlocks.add(validated)
            } else {
                // Regular JSON code snippet (not a tool invocation) — keep visible cleanly
                visibleBuffer.append("```json\n").append(inner).append("\n```")
            }
            resetJsonBufferState()
            mode = Mode.TEXT
        }
    }

    private fun processBareToolJsonChar(ch: Char) {
        activeJsonBuffer.append(ch)
        updateJsonBalance(ch)

        if (sawOpeningBrace && braceDepth == 0 && !inJsonString) {
            val candidate = activeJsonBuffer.toString().trim()
            val validated = tryValidateToolJson(candidate)
            if (validated != null) {
                completedBlocks.add(validated)
            } else {
                visibleBuffer.append(candidate)
            }
            resetJsonBufferState()
            mode = Mode.TEXT
        }
    }

    private fun updateJsonBalance(ch: Char) {
        if (inJsonString) {
            if (jsonEscaped) {
                jsonEscaped = false
            } else if (ch == '\\') {
                jsonEscaped = true
            } else if (ch == '"') {
                inJsonString = false
            }
            return
        }

        when (ch) {
            '"' -> inJsonString = true
            '{' -> {
                sawOpeningBrace = true
                braceDepth++
            }
            '}' -> {
                if (braceDepth > 0) braceDepth--
            }
        }
    }

    private fun resetJsonBufferState() {
        activeJsonBuffer.clear()
        braceDepth = 0
        inJsonString = false
        jsonEscaped = false
        sawOpeningBrace = false
    }

    private fun isBareToolPrefixMatch(buf: String): Boolean {
        val compact = buf.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return false
        return BARE_JSON_MARKERS.any { marker ->
            marker.startsWith(compact, ignoreCase = true) ||
                compact.startsWith(marker, ignoreCase = true)
        }
    }

    private fun isBareToolConfirmed(buf: String): Boolean {
        val compact = buf.filterNot { it.isWhitespace() }
        return BARE_JSON_MARKERS.any { marker ->
            compact.startsWith(marker, ignoreCase = true)
        }
    }

    private fun stripClosingTagAndFences(raw: String): String {
        return raw
            .removePrefix(OPEN_TAG)
            .removeSuffix(CLOSE_TAG)
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun tryValidateToolJson(candidate: String): String? {
        if (candidate.isBlank() || !candidate.startsWith("{")) return null
        return try {
            val obj: JsonObject = jsonValidator.parseToJsonElement(candidate).jsonObject
            if (obj.containsKey("tool") || obj.containsKey("name") || obj.containsKey("function") ||
                (obj.containsKey("title") && obj.containsKey("widgets"))
            ) {
                ChatGenUiToolProtocol.normalizeToolOrGenUiJson(candidate)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryRecoverTruncatedToolJson(candidate: String): String? {
        val trimmed = candidate.trim()
        if (!trimmed.startsWith("{")) return null
        val looksLikeToolOrGenUi = trimmed.contains("\"tool\"") ||
            trimmed.contains("\"name\"") ||
            (trimmed.contains("\"title\"") && trimmed.contains("\"widgets\""))
        if (!looksLikeToolOrGenUi) return null
        var attempt = trimmed
        repeat(4) {
            attempt = "$attempt}"
            val valid = tryValidateToolJson(attempt)
            if (valid != null) return valid
        }
        return null
    }

    private fun inferPendingHint(): String? {
        if (mode == Mode.TEXT) {
            if (pendingControlBuffer.length >= 3) {
                return "Preparing interactive tool..."
            }
            return null
        }
        val partial = activeJsonBuffer.toString()
        val baseHint = when {
            partial.contains("addAlarm", true) || partial.contains("clock_create_alarm", true) ->
                "Scheduling alarm via AlarmManager..."
            partial.contains("updateAlarm", true) || partial.contains("clock_update_alarm", true) ->
                "Updating alarm in AlarmManager..."
            partial.contains("deleteAlarm", true) || partial.contains("clock_delete_alarm", true) ->
                "Cancelling alarm in AlarmManager..."
            partial.contains("toggleAlarm", true) || partial.contains("clock_toggle_alarm", true) ->
                "Updating alarm switch..."
            partial.contains("listAlarms", true) || partial.contains("clock_list_alarms", true) ->
                "Reading AlarmManager schedule..."
            partial.contains("startTimer", true) || partial.contains("clock_start_timer", true) ->
                "Starting live countdown timer..."
            partial.contains("getTimerStatus", true) ->
                "Reading live timer remaining..."
            partial.contains("Timer", true) || partial.contains("clock_control_timer", true) ->
                "Updating countdown timer..."
            partial.contains("getStopwatchStatus", true) ->
                "Reading live stopwatch elapsed..."
            partial.contains("Stopwatch", true) || partial.contains("clock_stopwatch", true) ->
                "Controlling live stopwatch..."
            partial.contains("addEvent", true) || partial.contains("calendar_create", true) ->
                "Scheduling Android Calendar event..."
            partial.contains("updateEvent", true) || partial.contains("calendar_update", true) ->
                "Updating Android Calendar event..."
            partial.contains("deleteEvent", true) || partial.contains("calendar_delete", true) ->
                "Deleting Android Calendar event..."
            partial.contains("listEvents", true) || partial.contains("calendar_list", true) ->
                "Reading Android Calendar Provider..."
            partial.contains("render_gen_ui", true) ->
                "Streaming Generative UI widget..."
            else -> "Buffering structured JSON chunk..."
        }
        val bufferedBytes = activeJsonBuffer.length
        return if (bufferedBytes > 0) "$baseHint ($bufferedBytes B)" else baseHint
    }

    private fun buildSnapshot(
        deltaVisible: String,
        newlyCompleted: List<String>
    ): IncrementalStreamSnapshot {
        val cleanedVisible = visibleBuffer.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trimStart()
        return IncrementalStreamSnapshot(
            visibleText = cleanedVisible,
            deltaVisibleText = deltaVisible,
            pendingToolHint = inferPendingHint(),
            completedToolJsonBlocks = completedBlocks.toList(),
            newlyCompletedToolJsonBlocks = newlyCompleted,
            partialJsonBufferedBytes = activeJsonBuffer.length + pendingControlBuffer.length,
            partialJsonChunksBuffered = partialJsonChunksBuffered
        )
    }

    companion object {
        private const val OPEN_TAG = ChatGenUiToolProtocol.OPEN_TAG
        private const val CLOSE_TAG = ChatGenUiToolProtocol.CLOSE_TAG
        private const val FENCE_JSON_HEADER = "```json"
        private val BARE_JSON_MARKERS = listOf(
            "{\"tool\":",
            "{\"name\":",
            "{\"function\":",
            "{\"title\":",
            "{\"widgets\":"
        )
    }
}

/**
 * Accumulator for structured JSON-only Gemini streams (e.g., Generative UI control cards)
 * that buffers arbitrary partial JSON chunks and validates balanced JSON completion before
 * deserialization.
 */
class JsonDocumentStreamAccumulator {
    private val buffer = StringBuilder()
    private var braceDepth = 0
    private var inString = false
    private var escaped = false
    private var sawOpeningBrace = false
    var chunksReceived: Int = 0
        private set

    val isStructurallyComplete: Boolean
        get() = sawOpeningBrace && braceDepth == 0 && !inString

    fun appendChunk(chunk: String) {
        if (chunk.isEmpty()) return
        chunksReceived++
        for (ch in chunk) {
            buffer.append(ch)
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> {
                        sawOpeningBrace = true
                        braceDepth++
                    }
                    '}' -> if (braceDepth > 0) braceDepth--
                }
            }
        }
    }

    fun extractCompleteJson(): String {
        val raw = buffer.toString().trim()
        val firstBrace = raw.indexOf('{')
        val lastBrace = raw.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return raw.substring(firstBrace, lastBrace + 1)
        }
        return raw
    }
}

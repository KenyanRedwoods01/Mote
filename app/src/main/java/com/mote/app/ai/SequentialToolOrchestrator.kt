package com.mote.app.ai

import com.mote.app.data.db.LocalModelDao
import com.mote.app.data.db.ToolChainRunEntity
import com.mote.app.service.SystemOverlayStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

@Serializable
enum class ToolStepStatus(val label: String) {
    QUEUED("Pending"),
    RUNNING("Executing..."),
    EXECUTION_VERIFIED("Verified"),
    BLOCKED("Blocked"),
    FAILED("Failed")
}

@Serializable
data class ToolChainStepRecord(
    val stepIndex: Int,
    val toolName: String,
    val operationLabel: String,
    val status: ToolStepStatus,
    val engineLabel: String,
    val receiptId: String? = null,
    val latencyMs: Long = 0L,
    val summary: String = "",
    val usedIntermediateState: String? = null,
    val verificationDetail: String? = null
)

data class ToolChainExecutionSnapshot(
    val chainId: String,
    val chainTitle: String,
    val steps: List<ToolChainStepRecord>,
    val isComplete: Boolean,
    val allVerified: Boolean,
    val verifiedCount: Int,
    val failedCount: Int,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val completedAtEpochMs: Long = System.currentTimeMillis(),
    val promptSummary: String = ""
)

/**
 * Shared intermediate state passed sequentially from Step N to Step N+1 in a multi-step tool chain:
 * - `takeScreenshot` -> stores `lastCaptureId` & `lastSavedUri`
 * - `ocrAnalysis` -> consumes `lastCaptureId`, stores `lastExtractedText`
 * - `saveNote` -> consumes `lastExtractedText`, stores `lastNoteId`
 * - `shareToApp` -> consumes `lastSavedUri` or `lastExtractedText`
 * - `dialPhone` -> stores `lastDialedNumber`, piped into `startVoiceCallRelay`
 * - `openApp` -> stores `lastOpenedApp`, piped into `searchInApp`
 */
class ToolChainContext {
    var lastCaptureId: String? = null
    var lastSavedUri: String? = null
    var lastExtractedText: String? = null
    var lastNoteId: String? = null
    var lastOpenedApp: String? = null
    var lastDialedNumber: String? = null
    var lastSearchQuery: String? = null
    var lastAlarmId: String? = null
    var lastEventId: String? = null
    val stepOutputs: MutableMap<String, String> = linkedMapOf()
}

data class ToolChainRunResult(
    val snapshot: ToolChainExecutionSnapshot,
    val uiParts: List<InChatUiPart>,
    val context: ToolChainContext
)

/**
 * Sequential Tool Chain Orchestrator & Background Execution Verifier.
 *
 * Capabilities:
 * 1. Parses complex natural language instructions into atomic sequential tool chains
 *    (e.g., `Take screenshot -> OCR analysis -> Save to Notes`, `Open Chrome and search ...`,
 *    `Open phone and call <number> then start real-time STT/TTS voice relay`).
 * 2. Manages inter-dependent intermediate state (`ToolChainContext`) between atomic steps.
 * 3. Runs independent post-execution background verification via [GenUiToolRegistry.verifyExecutionInBackground]
 *    before awarding an `EV-XXXXXX` execution receipt and `Execution Verified` badge.
 * 4. Broadcasts every step in real time to [SystemOverlayStateBus] so the animated Siri-style
 *    system overlay floats live over Chrome, YouTube, Phone, and all external apps.
 * 5. Persists every triggered chain into Room (`tool_chain_runs`) for the chronological
 *    'Tool Execution Log' inside Chat History.
 */
class SequentialToolOrchestrator(
    private val toolRegistry: GenUiToolRegistry,
    private val dao: LocalModelDao,
    private val scope: CoroutineScope
) {

    private val serializerJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _activeChain = MutableStateFlow<ToolChainExecutionSnapshot?>(null)
    val activeChain: StateFlow<ToolChainExecutionSnapshot?> = _activeChain.asStateFlow()

    val chainHistoryLog: StateFlow<List<ToolChainExecutionSnapshot>> = dao.observeToolChainRuns()
        .map { rows ->
            rows.map { row ->
                val decodedSteps = runCatching {
                    serializerJson.decodeFromString<List<ToolChainStepRecord>>(row.stepsJson)
                }.getOrDefault(emptyList())
                ToolChainExecutionSnapshot(
                    chainId = row.chainId,
                    chainTitle = row.chainTitle,
                    steps = decodedSteps,
                    isComplete = true,
                    allVerified = row.allVerified,
                    verifiedCount = row.verifiedSteps,
                    failedCount = row.failedSteps,
                    startedAtEpochMs = row.startedAtEpochMs,
                    completedAtEpochMs = row.completedAtEpochMs,
                    promptSummary = row.promptSummary
                )
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun clearChainHistory() {
        scope.launch { dao.clearToolChainRuns() }
    }

    /**
     * Unwraps any `runToolChain` meta-invocation into an ordered list of concrete [ChatToolInvocation]s.
     */
    fun expandInvocations(rawInvocations: List<ChatToolInvocation>): List<ChatToolInvocation> {
        val expanded = mutableListOf<ChatToolInvocation>()
        for (inv in rawInvocations) {
            val op = inv.tool.trim().lowercase(Locale.US)
            if (op == "runtoolchain" || op == "toolchain" || op == "executechain") {
                val stepsArr = inv.args["steps"]?.let { runCatching { it.jsonArray }.getOrNull() }
                if (stepsArr != null) {
                    for (el in stepsArr) {
                        val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
                        val stepTool = obj["tool"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (stepTool.isBlank()) continue
                        val stepArgs = obj["args"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: JsonObject(emptyMap())
                        expanded.add(ChatToolInvocation(tool = stepTool, args = stepArgs))
                    }
                }
            } else {
                expanded.add(inv)
            }
        }
        return expanded
    }

    /**
     * Executes a sequence of atomic tool calls one-by-one while piping intermediate state across
     * dependent steps, performing independent post-execution background verification, updating the
     * system-wide animated Siri overlay, and persisting the chronological execution log to Room.
     */
    suspend fun executeChain(
        rawInvocations: List<ChatToolInvocation>,
        chainTitle: String = "Tool Execution Chain",
        promptSummary: String = "",
        onProgress: (ToolChainExecutionSnapshot) -> Unit = {}
    ): ToolChainRunResult {
        val invocations = expandInvocations(rawInvocations)
        val chainId = "chain_${UUID.randomUUID().toString().take(8)}"
        val startedAtMs = System.currentTimeMillis()
        val context = ToolChainContext()
        val producedUiParts = mutableListOf<InChatUiPart>()

        val steps = invocations.mapIndexed { idx, inv ->
            ToolChainStepRecord(
                stepIndex = idx + 1,
                toolName = inv.tool,
                operationLabel = formatOperationLabel(inv),
                status = ToolStepStatus.QUEUED,
                engineLabel = resolveEngineLabel(inv.tool)
            )
        }.toMutableList()

        fun publishSnapshot(complete: Boolean): ToolChainExecutionSnapshot {
            val verified = steps.count { it.status == ToolStepStatus.EXECUTION_VERIFIED }
            val failed = steps.count { it.status == ToolStepStatus.FAILED || it.status == ToolStepStatus.BLOCKED }
            val snap = ToolChainExecutionSnapshot(
                chainId = chainId,
                chainTitle = if (invocations.size > 1) {
                    "$chainTitle (${invocations.size} steps)"
                } else {
                    chainTitle
                },
                steps = steps.toList(),
                isComplete = complete,
                allVerified = complete && steps.isNotEmpty() && verified == steps.size,
                verifiedCount = verified,
                failedCount = failed,
                startedAtEpochMs = startedAtMs,
                completedAtEpochMs = System.currentTimeMillis(),
                promptSummary = promptSummary
            )
            _activeChain.value = snap
            SystemOverlayStateBus.publishChainProgress(snap)
            onProgress(snap)
            return snap
        }

        publishSnapshot(complete = false)

        for (i in invocations.indices) {
            val originalInv = invocations[i]
            val (resolvedInv, dependencyNote, missingDependencyError) = injectIntermediateState(originalInv, context)

            if (missingDependencyError != null) {
                steps[i] = steps[i].copy(
                    status = ToolStepStatus.FAILED,
                    summary = missingDependencyError,
                    usedIntermediateState = dependencyNote,
                    verificationDetail = "Dependency precondition failed"
                )
                publishSnapshot(complete = false)
                continue
            }

            steps[i] = steps[i].copy(
                status = ToolStepStatus.RUNNING,
                usedIntermediateState = dependencyNote
            )
            publishSnapshot(complete = false)

            val stepStartMs = System.currentTimeMillis()
            val result = toolRegistry.dispatch(resolvedInv)

            if (result == null) {
                val durationMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
                steps[i] = steps[i].copy(
                    status = ToolStepStatus.FAILED,
                    latencyMs = durationMs,
                    summary = "Unregistered tool operation '${resolvedInv.tool}'.",
                    verificationDetail = "Tool not found in GenUiToolRegistry"
                )
            } else {
                // Independent post-execution background verification against Room / Android APIs
                val bgVerification = toolRegistry.verifyExecutionInBackground(result)
                val durationMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)

                val isBlocked = !result.success && result.feedback.contains("unauthorized", ignoreCase = true)
                val verifiedSuccess = result.success && bgVerification.verified
                val status = when {
                    verifiedSuccess -> ToolStepStatus.EXECUTION_VERIFIED
                    isBlocked -> ToolStepStatus.BLOCKED
                    else -> ToolStepStatus.FAILED
                }
                val receipt = if (verifiedSuccess) {
                    "EV-${UUID.randomUUID().toString().take(6).uppercase(Locale.US)}"
                } else {
                    null
                }

                // Store intermediate outputs in ToolChainContext for Step i+1
                captureIntermediateState(resolvedInv, result, context)

                steps[i] = steps[i].copy(
                    status = status,
                    receiptId = receipt,
                    latencyMs = durationMs,
                    summary = result.feedback,
                    verificationDetail = bgVerification.detail
                )
                result.uiPart?.let { producedUiParts.add(it) }
            }

            publishSnapshot(complete = false)
            // Small inter-step settle window when launching an app before a subsequent chained step
            if (i < invocations.lastIndex && isAppLaunchStep(resolvedInv.tool)) {
                delay(280L)
            }
        }

        val finalSnapshot = publishSnapshot(complete = true)

        // Persist verifiable chronological Tool Execution Log entry into Room
        runCatching {
            dao.upsertToolChainRun(
                ToolChainRunEntity(
                    chainId = finalSnapshot.chainId,
                    chainTitle = finalSnapshot.chainTitle,
                    promptSummary = finalSnapshot.promptSummary.take(180),
                    startedAtEpochMs = finalSnapshot.startedAtEpochMs,
                    completedAtEpochMs = finalSnapshot.completedAtEpochMs,
                    totalSteps = finalSnapshot.steps.size,
                    verifiedSteps = finalSnapshot.verifiedCount,
                    failedSteps = finalSnapshot.failedCount,
                    allVerified = finalSnapshot.allVerified,
                    stepsJson = serializerJson.encodeToString(finalSnapshot.steps)
                )
            )
        }

        val statusPart = InChatUiPart.ToolChainStatusPanel(
            partId = "chain_ui_${System.nanoTime()}",
            snapshot = finalSnapshot
        )
        val orderedParts = listOf(statusPart) + producedUiParts
        return ToolChainRunResult(
            snapshot = finalSnapshot,
            uiParts = orderedParts,
            context = context
        )
    }

    /**
     * Enforces truth-in-execution: ensures the final assistant response explicitly signals
     * 'Execution Verified' with verifiable receipt IDs ONLY after every Android API call
     * AND background verification check succeeded.
     */
    fun verifyAssistantClaim(
        assistantText: String,
        snapshot: ToolChainExecutionSnapshot?
    ): String {
        if (snapshot == null || snapshot.steps.isEmpty()) return assistantText

        val trimmed = assistantText.trim()
        if (snapshot.allVerified) {
            val receipts = snapshot.steps.mapNotNull { it.receiptId }.joinToString(", ")
            val badgeLine = if (snapshot.steps.size == 1) {
                "Execution Verified ($receipts) — ${snapshot.steps.first().summary}"
            } else {
                "Execution Verified (${snapshot.verifiedCount}/${snapshot.steps.size} steps · $receipts)."
            }
            return when {
                trimmed.isBlank() -> badgeLine
                trimmed.contains("Execution Verified", ignoreCase = true) -> trimmed
                else -> "$trimmed\n\n$badgeLine"
            }
        }

        val failedSteps = snapshot.steps.filter { it.status != ToolStepStatus.EXECUTION_VERIFIED }
        val failureDetails = failedSteps.joinToString("; ") { "${it.operationLabel}: ${it.summary}" }
        return if (snapshot.verifiedCount > 0) {
            "Partial Execution (${snapshot.verifiedCount}/${snapshot.steps.size} verified). Unfinished: $failureDetails"
        } else {
            "Action Not Completed — Execution Unverified: $failureDetails"
        }
    }

    /**
     * Natural Language -> Atomic Tool Chain Parser.
     * Maps complex multi-step natural language commands into a verified sequence of atomic tool calls,
     * covering:
     * - `'Take screenshot' -> 'OCR analysis' -> 'Save to Notes'`
     * - `'Open Chrome and search <query>'` / `'Open YouTube and search <query>'`
     * - `'Open phone and call <number> then listen and reply with AI voice'`
     * - Compound Clock, Calendar, Camera, App Launcher, Sharing, Navigation, and Notes chains.
     */
    fun planFallbackChainIfUnverified(
        userPrompt: String,
        enabledToolIds: Set<String>
    ): List<ChatToolInvocation> {
        val raw = userPrompt.trim()
        if (raw.isBlank()) return emptyList()

        // Pre-check direct "open <app> and search <query>" combined intent so it doesn't split awkwardly
        val fullLower = raw.lowercase(Locale.US)
        val openAndSearchMatch = Regex(
            """(?:open|launch)\s+(chrome|browser|youtube|yt|maps|google maps|play store|spotify|amazon)\s+(?:and\s+)?(?:search(?:\s+for)?|look\s+up|find)\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(raw)
        if (openAndSearchMatch != null && GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
            val appTarget = openAndSearchMatch.groupValues[1].trim()
            val remainder = openAndSearchMatch.groupValues[2].trim()
            val firstSplit = remainder.split(Regex("""\s+(?:and\s+then|then|->|→)\s+|;\s*""", RegexOption.IGNORE_CASE))
            val searchQuery = firstSplit.first().trim()
            val chain = mutableListOf(
                ChatToolInvocation(
                    tool = "openApp",
                    args = buildJsonObject { put("appName", JsonPrimitive(appTarget)) }
                ),
                ChatToolInvocation(
                    tool = "searchInApp",
                    args = buildJsonObject {
                        put("appName", JsonPrimitive(appTarget))
                        put("query", JsonPrimitive(searchQuery))
                    }
                )
            )
            if (firstSplit.size > 1) {
                val tailPrompt = firstSplit.drop(1).joinToString(" then ")
                chain.addAll(planFallbackChainIfUnverified(tailPrompt, enabledToolIds))
            }
            return chain
        }

        // Split multi-step prompts on arrows (->, →), "and then", "then", "after that", "and", ";"
        val clauses = raw
            .split(Regex("""\s*(?:->|→)\s*|\s+(?:and\s+then|after\s+that|then|and)\s+|\s*;\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val planned = mutableListOf<ChatToolInvocation>()
        var hasScreenshotStep = false
        var hasOcrStep = false
        var lastMentionedApp: String? = null
        var lastDialedNum: String? = null

        for (clause in clauses) {
            val lower = clause.lowercase(Locale.US)

            // 1. Screenshot / Screen Capture
            if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds &&
                (lower.contains("screenshot") || lower.contains("screen shot") ||
                    lower.contains("capture screen") || lower.contains("capture my screen"))
            ) {
                val explicitOcrInSameClause = lower.contains("ocr") || lower.contains("extract text")
                val wantsAnalysis = !explicitOcrInSameClause && (lower.contains("analyze") || lower.contains("analyse") ||
                    lower.contains("what is on") || lower.contains("describe"))
                planned.add(
                    ChatToolInvocation(
                        tool = if (wantsAnalysis) "captureAndAnalyzeScreen" else "takeScreenshot",
                        args = buildJsonObject {
                            put("label", JsonPrimitive("Screen Capture"))
                            if (wantsAnalysis) put("analyze", JsonPrimitive(true))
                        }
                    )
                )
                hasScreenshotStep = true
                if (explicitOcrInSameClause) {
                    planned.add(
                        ChatToolInvocation(
                            tool = "ocrAnalysis",
                            args = buildJsonObject { put("captureId", JsonPrimitive("\$lastCaptureId")) }
                        )
                    )
                    hasOcrStep = true
                }
                continue
            }

            // 2. OCR Analysis / Text Extraction
            if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds &&
                (lower.contains("ocr") || lower.contains("extract text") || lower.contains("read text") ||
                    (lower.contains("read") && lower.contains("screen")))
            ) {
                planned.add(
                    ChatToolInvocation(
                        tool = "ocrAnalysis",
                        args = buildJsonObject { put("captureId", JsonPrimitive("\$lastCaptureId")) }
                    )
                )
                hasOcrStep = true
                continue
            }

            // 3. Save to Notes / List Notes / Delete Note
            if (lower.contains("note")) {
                when {
                    lower.contains("list") || lower.contains("show") -> {
                        planned.add(ChatToolInvocation("listNotes", JsonObject(emptyMap())))
                    }
                    lower.contains("delete") || lower.contains("remove") -> {
                        planned.add(
                            ChatToolInvocation(
                                tool = "deleteNote",
                                args = buildJsonObject { put("query", JsonPrimitive(clause)) }
                            )
                        )
                    }
                    else -> {
                        val explicitBody = Regex("""(?:save|add|create|write)\s+(?:a\s+)?note(?:\s+(?:saying|with|about|called|:))?\s+(.+)""", RegexOption.IGNORE_CASE)
                            .find(clause)?.groupValues?.get(1)?.trim()
                        val useOcrContent = hasOcrStep || explicitBody.isNullOrBlank() || lower.contains("to notes") || lower.contains("to my notes")
                        planned.add(
                            ChatToolInvocation(
                                tool = "saveNote",
                                args = buildJsonObject {
                                    put("title", JsonPrimitive(if (hasOcrStep) "Screenshot OCR Note" else "Quick Note"))
                                    put("content", JsonPrimitive(if (useOcrContent) "\$lastExtractedText" else explicitBody!!))
                                    put("source", JsonPrimitive(if (hasOcrStep) "Screenshot -> OCR Chain" else "Mote Voice/Chat"))
                                }
                            )
                        )
                    }
                }
                continue
            }

            // 4. Phone Call / Dial Number
            val phoneMatch = Regex("""(?:call|dial|ring|phone)\s+([+0-9][0-9\-\s()]{2,18})""", RegexOption.IGNORE_CASE).find(clause)
            if (phoneMatch != null) {
                val number = phoneMatch.groupValues[1].trim()
                lastDialedNum = number
                planned.add(
                    ChatToolInvocation(
                        tool = "dialPhone",
                        args = buildJsonObject { put("phoneNumber", JsonPrimitive(number)) }
                    )
                )
                // If same clause also mentions listening/voice/TTS/STT, chain the live voice relay
                if (lower.contains("listen") || lower.contains("tts") || lower.contains("speech") ||
                    lower.contains("respond") || lower.contains("talk") || lower.contains("voice")
                ) {
                    planned.add(
                        ChatToolInvocation(
                            tool = "startVoiceCallRelay",
                            args = buildJsonObject {
                                put("targetLabel", JsonPrimitive("Call $number"))
                                put("openingGreeting", JsonPrimitive("Hello, this is Mote AI assistant speaking on behalf of my user."))
                            }
                        )
                    )
                }
                continue
            }

            // 5. Real-Time STT <-> AI <-> TTS Voice Call Relay / Speak Text
            if (lower.contains("stop voice") || lower.contains("end voice") || lower.contains("stop relay") || lower.contains("stop listening")) {
                planned.add(ChatToolInvocation("stopVoiceCallRelay", JsonObject(emptyMap())))
                continue
            }
            if (lower.contains("voice relay") || lower.contains("speech to text") || lower.contains("text to speech") ||
                (lower.contains("listen") && (lower.contains("call") || lower.contains("respond") || lower.contains("reply") || lower.contains("audio"))) ||
                (lower.contains("convert") && lower.contains("audio") && lower.contains("text"))
            ) {
                planned.add(
                    ChatToolInvocation(
                        tool = "startVoiceCallRelay",
                        args = buildJsonObject {
                            put("targetLabel", JsonPrimitive(lastDialedNum?.let { "Call $it" } ?: "Live Voice Call Relay"))
                            put("openingGreeting", JsonPrimitive("Hello, Mote real-time voice relay is active and listening."))
                        }
                    )
                )
                continue
            }
            val speakMatch = Regex("""(?:speak|say aloud|read aloud)\s+(.+)""", RegexOption.IGNORE_CASE).find(clause)
            if (speakMatch != null) {
                planned.add(
                    ChatToolInvocation(
                        tool = "speakText",
                        args = buildJsonObject { put("text", JsonPrimitive(speakMatch.groupValues[1].trim())) }
                    )
                )
                continue
            }

            // 6. Share step (especially chained after screenshot or OCR: "share it on WhatsApp")
            if (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds &&
                (lower.contains("share") || lower.contains("send it"))
            ) {
                val targetApp = extractShareTargetApp(lower)
                planned.add(
                    ChatToolInvocation(
                        tool = "shareToApp",
                        args = buildJsonObject {
                            if (targetApp != null) put("appName", JsonPrimitive(targetApp))
                            put("useLastScreenshot", JsonPrimitive(hasScreenshotStep || lower.contains("screenshot") || lower.contains("it")))
                            put("text", JsonPrimitive(if (hasOcrStep) "\$lastExtractedText" else "Shared from Mote"))
                        }
                    )
                )
                continue
            }

            // 7. Search in Chrome / YouTube / Web (works standalone OR chained after "open Chrome")
            val searchMatch = Regex("""(?:search(?:\s+for)?|google|look\s+up)\s+(.+)""", RegexOption.IGNORE_CASE).find(clause)
            if (searchMatch != null && (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds || GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds)) {
                val query = searchMatch.groupValues[1].trim()
                val targetApp = lastMentionedApp ?: "Chrome"
                planned.add(
                    ChatToolInvocation(
                        tool = "searchInApp",
                        args = buildJsonObject {
                            put("appName", JsonPrimitive(targetApp))
                            put("query", JsonPrimitive(query))
                        }
                    )
                )
                continue
            }

            // 8. Alarm creation / deletion / listing
            if (GenUiToolRegistry.TOOL_ALARM_MANAGER in enabledToolIds && lower.contains("alarm")) {
                when {
                    lower.contains("delete") || lower.contains("cancel") || lower.contains("remove") -> {
                        val query = if (lower.contains("all")) "all" else extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = "deleteAlarm",
                                args = buildJsonObject { put("query", JsonPrimitive(query)) }
                            )
                        )
                    }
                    lower.contains("list") || lower.contains("show") || lower.contains("what alarm") -> {
                        planned.add(ChatToolInvocation("listAlarms", JsonObject(emptyMap())))
                    }
                    else -> {
                        val parsedTime = parseHourMinute(lower)
                        if (parsedTime != null) {
                            val label = extractAlarmLabel(clause) ?: "Alarm"
                            planned.add(
                                ChatToolInvocation(
                                    tool = "addAlarm",
                                    args = buildJsonObject {
                                        put("hour", JsonPrimitive(parsedTime.first))
                                        put("minute", JsonPrimitive(parsedTime.second))
                                        put("label", JsonPrimitive(label))
                                        put("enabled", JsonPrimitive(true))
                                    }
                                )
                            )
                        }
                    }
                }
                continue
            }

            // 9. Countdown Timer
            if (GenUiToolRegistry.TOOL_COUNTDOWN_TIMER in enabledToolIds && lower.contains("timer")) {
                when {
                    lower.contains("pause") -> planned.add(ChatToolInvocation("pauseTimer", JsonObject(emptyMap())))
                    lower.contains("resume") -> planned.add(ChatToolInvocation("resumeTimer", JsonObject(emptyMap())))
                    lower.contains("reset") -> planned.add(ChatToolInvocation("resetTimer", JsonObject(emptyMap())))
                    lower.contains("delete") || lower.contains("cancel") || lower.contains("stop") ->
                        planned.add(ChatToolInvocation("deleteTimer", JsonObject(emptyMap())))
                    lower.contains("status") || lower.contains("left") || lower.contains("remaining") ->
                        planned.add(ChatToolInvocation("getTimerStatus", JsonObject(emptyMap())))
                    else -> {
                        val secs = parseDurationSeconds(lower) ?: 300
                        planned.add(
                            ChatToolInvocation(
                                tool = "startTimer",
                                args = buildJsonObject {
                                    put("durationSeconds", JsonPrimitive(secs))
                                    put("label", JsonPrimitive("Timer"))
                                }
                            )
                        )
                    }
                }
                continue
            }

            // 10. Stopwatch
            if (GenUiToolRegistry.TOOL_STOPWATCH in enabledToolIds && lower.contains("stopwatch")) {
                val op = when {
                    lower.contains("pause") || lower.contains("stop") -> "pauseStopwatch"
                    lower.contains("lap") -> "lapStopwatch"
                    lower.contains("reset") -> "resetStopwatch"
                    lower.contains("status") -> "getStopwatchStatus"
                    else -> "startStopwatch"
                }
                planned.add(ChatToolInvocation(op, JsonObject(emptyMap())))
                continue
            }

            // 11. Flashlight / Camera
            if (GenUiToolRegistry.TOOL_CAMERA in enabledToolIds) {
                if (lower.contains("flashlight") || lower.contains("torch")) {
                    val state = when {
                        lower.contains("off") || lower.contains("disable") -> false
                        lower.contains("on") || lower.contains("enable") -> true
                        else -> null
                    }
                    planned.add(
                        ChatToolInvocation(
                            tool = "toggleFlashlight",
                            args = buildJsonObject {
                                if (state != null) put("enabled", JsonPrimitive(state))
                            }
                        )
                    )
                    continue
                }
                if (lower.contains("camera") || lower.contains("take a photo") || lower.contains("selfie")) {
                    val isVideo = lower.contains("video") || lower.contains("record")
                    val facing = if (lower.contains("front") || lower.contains("selfie")) "front" else "back"
                    planned.add(
                        ChatToolInvocation(
                            tool = if (isVideo) "openVideoCamera" else "openCamera",
                            args = buildJsonObject {
                                put("mode", JsonPrimitive(if (isVideo) "video" else "photo"))
                                put("facing", JsonPrimitive(facing))
                            }
                        )
                    )
                    continue
                }
            }

            // 12. Recent Apps / Installed Apps / Open App (Chrome, YouTube, Phone, WhatsApp, etc.)
            if (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
                if (lower.contains("recent app") || lower.contains("usage stat")) {
                    val op = if (lower.contains("open") || lower.contains("switch")) "openRecentApp" else "getRecentApps"
                    planned.add(ChatToolInvocation(op, JsonObject(emptyMap())))
                    continue
                }
                if (lower.contains("list") && lower.contains("app")) {
                    planned.add(ChatToolInvocation("listInstalledApps", JsonObject(emptyMap())))
                    continue
                }
                val openMatch = Regex("""(?:open|launch|start)\s+(?:the\s+)?([a-zA-Z0-9 .+\-]+?)(?:\s+app|\s+for me|\s+please|$)""", RegexOption.IGNORE_CASE)
                    .find(clause)
                if (openMatch != null) {
                    val target = openMatch.groupValues[1].trim()
                    if (target.isNotBlank() && !target.equals("a", true) && !target.equals("the", true)) {
                        lastMentionedApp = target
                        if (target.equals("phone", ignoreCase = true) || target.equals("dialer", ignoreCase = true)) {
                            planned.add(
                                ChatToolInvocation(
                                    tool = "dialPhone",
                                    args = buildJsonObject { put("phoneNumber", JsonPrimitive("")) }
                                )
                            )
                        } else {
                            planned.add(
                                ChatToolInvocation(
                                    tool = "openApp",
                                    args = buildJsonObject { put("appName", JsonPrimitive(target)) }
                                )
                            )
                        }
                        continue
                    }
                }
            }

            // 13. Maps Navigation
            if (GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds) {
                val navMatch = Regex("""(?:navigate|directions)\s+to\s+(.+)""", RegexOption.IGNORE_CASE).find(clause)
                if (navMatch != null) {
                    planned.add(
                        ChatToolInvocation(
                            tool = "navigateMaps",
                            args = buildJsonObject { put("destination", JsonPrimitive(navMatch.groupValues[1].trim())) }
                        )
                    )
                    continue
                }
            }
        }

        // If full prompt mentioned calling + voice relay but only phone dial was planned, append voice relay
        if (lastDialedNum != null && planned.none { it.tool.equals("startVoiceCallRelay", true) } &&
            (fullLower.contains("speech") || fullLower.contains("tts") || fullLower.contains("stt") ||
                fullLower.contains("listen") || fullLower.contains("voice"))
        ) {
            planned.add(
                ChatToolInvocation(
                    tool = "startVoiceCallRelay",
                    args = buildJsonObject {
                        put("targetLabel", JsonPrimitive("Call $lastDialedNum"))
                        put("openingGreeting", JsonPrimitive("Hello, Mote AI assistant is listening and ready to respond."))
                    }
                )
            )
        }

        return planned
    }

    private fun injectIntermediateState(
        invocation: ChatToolInvocation,
        context: ToolChainContext
    ): Triple<ChatToolInvocation, String?, String?> {
        val op = invocation.tool.trim().lowercase(Locale.US)

        // Pipe screenshot captureId -> OCR Analysis
        if (op in setOf("ocranalysis", "ocr", "extracttext", "extract_text", "readscreenshot", "read_screenshot_text")) {
            val rawCap = invocation.args["captureId"]?.jsonPrimitive?.contentOrNull
            if ((rawCap.isNullOrBlank() || rawCap.startsWith("$")) && !context.lastCaptureId.isNullOrBlank()) {
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("captureId", JsonPrimitive(context.lastCaptureId!!))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Piped screenshot (${context.lastCaptureId}) from previous step",
                    null
                )
            }
        }

        // Pipe OCR extracted text -> Save to Notes
        if (op in setOf("savenote", "savetonotes", "save_note", "save_to_notes", "createnote", "addnote")) {
            val rawContent = invocation.args["content"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["text"]?.jsonPrimitive?.contentOrNull
            if (rawContent.isNullOrBlank() || rawContent.contains("\$lastExtractedText")) {
                val extracted = context.lastExtractedText
                if (extracted.isNullOrBlank()) {
                    return Triple(
                        invocation,
                        null,
                        "Skipped Save to Notes — dependent OCR analysis step did not produce extracted text."
                    )
                }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("content", JsonPrimitive(extracted))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Piped OCR text (${extracted.length} chars) from Step ${context.stepOutputs.size}",
                    null
                )
            }
        }

        // Pipe screenshot URI / OCR text -> Share to App
        if (op in setOf("sharetoapp", "sharecontent", "share_screenshot", "sharescreenshot")) {
            val explicitUri = invocation.args["mediaUri"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["uri"]?.jsonPrimitive?.contentOrNull
            val wantsScreenshot = invocation.args["useLastScreenshot"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            if ((explicitUri.isNullOrBlank() || explicitUri.startsWith("$")) && wantsScreenshot) {
                val savedUri = context.lastSavedUri
                if (savedUri.isNullOrBlank()) {
                    return Triple(
                        invocation,
                        null,
                        "Skipped share — dependent screenshot capture did not produce a saved image URI."
                    )
                }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("mediaUri", JsonPrimitive(savedUri))
                    context.lastCaptureId?.let { put("captureId", JsonPrimitive(it)) }
                    val rawText = invocation.args["text"]?.jsonPrimitive?.contentOrNull
                    if (rawText == "\$lastExtractedText" && !context.lastExtractedText.isNullOrBlank()) {
                        put("text", JsonPrimitive(context.lastExtractedText!!))
                    }
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Piped screenshot URI from capture step",
                    null
                )
            }
        }

        // Pipe opened app -> searchInApp
        if (op in setOf("searchinapp", "searchchrome", "openchromeandsearch", "searchyoutube")) {
            val appArg = invocation.args["appName"]?.jsonPrimitive?.contentOrNull
            if ((appArg.isNullOrBlank() || appArg.startsWith("$")) && !context.lastOpenedApp.isNullOrBlank()) {
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("appName", JsonPrimitive(context.lastOpenedApp!!))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Piped target app '${context.lastOpenedApp}' from previous step",
                    null
                )
            }
        }

        // Pipe dialed phone number -> startVoiceCallRelay
        if (op in setOf("startvoicecallrelay", "startvoicerelay", "voicecallrelay", "livevoicerelay")) {
            val targetArg = invocation.args["targetLabel"]?.jsonPrimitive?.contentOrNull
            if ((targetArg.isNullOrBlank() || targetArg.startsWith("$")) && !context.lastDialedNumber.isNullOrBlank()) {
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("targetLabel", JsonPrimitive("Call ${context.lastDialedNumber}"))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Piped dialed number (${context.lastDialedNumber}) from Phone step",
                    null
                )
            }
        }

        return Triple(invocation, null, null)
    }

    private fun captureIntermediateState(
        invocation: ChatToolInvocation,
        result: GenUiToolResult,
        context: ToolChainContext
    ) {
        context.stepOutputs[result.operation] = result.feedback
        if (!result.extractedText.isNullOrBlank()) {
            context.lastExtractedText = result.extractedText
        }
        if (!result.createdRecordId.isNullOrBlank() && result.toolId == GenUiToolRegistry.TOOL_NOTES) {
            context.lastNoteId = result.createdRecordId
        }
        if (result.operation.equals("dialPhone", ignoreCase = true) && result.success) {
            val num = invocation.args["phoneNumber"]?.jsonPrimitive?.contentOrNull
            if (!num.isNullOrBlank()) context.lastDialedNumber = num
        }
        if (result.operation.equals("searchInApp", ignoreCase = true) && result.success) {
            invocation.args["query"]?.jsonPrimitive?.contentOrNull?.let { context.lastSearchQuery = it }
        }
        when (val part = result.uiPart) {
            is InChatUiPart.ScreenCapturePanel -> {
                if (part.success) {
                    if (!part.captureId.isNullOrBlank()) context.lastCaptureId = part.captureId
                    if (!part.savedUriString.isNullOrBlank()) context.lastSavedUri = part.savedUriString
                }
            }
            is InChatUiPart.OcrResultPanel -> {
                if (part.success && part.extractedText.isNotBlank()) {
                    context.lastExtractedText = part.extractedText
                }
            }
            is InChatUiPart.NotesPanel -> {
                if (part.success && !part.highlightNoteId.isNullOrBlank()) {
                    context.lastNoteId = part.highlightNoteId
                }
            }
            is InChatUiPart.ClockAlarmsPanel -> {
                if (part.success && !part.highlightAlarmId.isNullOrBlank()) {
                    context.lastAlarmId = part.highlightAlarmId
                }
            }
            is InChatUiPart.CalendarEventsPanel -> {
                if (part.success && !part.highlightEventId.isNullOrBlank()) {
                    context.lastEventId = part.highlightEventId
                }
            }
            is InChatUiPart.DeviceActionPanel -> {
                if (part.success && part.category.equals("APPS", ignoreCase = true)) {
                    context.lastOpenedApp = part.title.removePrefix("Opened ").trim()
                }
            }
            else -> Unit
        }
    }

    private fun isAppLaunchStep(toolName: String): Boolean {
        val op = toolName.lowercase(Locale.US)
        return op in setOf("openapp", "launchapp", "startapp", "dialphone", "callnumber")
    }

    private fun formatOperationLabel(inv: ChatToolInvocation): String {
        val op = inv.tool.trim()
        val detail = inv.args["appName"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["phoneNumber"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["targetLabel"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["query"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["label"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["title"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["destination"]?.jsonPrimitive?.contentOrNull
        return if (!detail.isNullOrBlank() && !detail.startsWith("$")) "$op($detail)" else "$op()"
    }

    private fun resolveEngineLabel(tool: String): String {
        val op = tool.lowercase(Locale.US)
        return when {
            op.contains("alarm") -> "Android AlarmManager"
            op.contains("timer") -> "Live Countdown Engine"
            op.contains("stopwatch") -> "Live Stopwatch Engine"
            op.contains("event") || op.contains("calendar") -> "CalendarContract + Room"
            op.contains("ocr") || op.contains("extract") -> "Gemini Vision OCR"
            op.contains("note") -> "Mote Notes Room DB"
            op.contains("dial") || op.contains("call") && !op.contains("relay") -> "Android ACTION_DIAL"
            op.contains("relay") || op.contains("speak") || op.contains("tts") -> "Google STT ↔ AI ↔ TTS"
            op.contains("screenshot") || op.contains("screen") -> "MediaProjection API"
            op.contains("share") -> "Android ACTION_SEND"
            op.contains("search") -> "Chrome / App Search + Overlay"
            op.contains("recentapp") || op.contains("usagestats") -> "UsageStatsManager"
            op.contains("app") -> "PackageManager Intent"
            op.contains("flashlight") || op.contains("torch") -> "Camera2 Torch API"
            op.contains("camera") || op.contains("photo") -> "Android Camera Intent"
            op.contains("maps") -> "Google Maps Navigation"
            op.contains("render_gen_ui") -> "Compose GenUI Runtime"
            else -> "Android Device Engine"
        }
    }

    private fun extractShareTargetApp(lower: String): String? {
        val targets = listOf(
            "whatsapp", "telegram", "gmail", "messages", "instagram",
            "discord", "slack", "messenger", "twitter", "snapchat"
        )
        return targets.firstOrNull { lower.contains(it) }
    }

    private fun parseHourMinute(lower: String): Pair<Int, Int>? {
        val match = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\b""").find(lower)
            ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].lowercase(Locale.US).replace(".", "")
        if (hour > 23 || minute > 59) return null
        if (meridiem == "pm" && hour in 1..11) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        return hour to minute
    }

    private fun parseDurationSeconds(lower: String): Int? {
        val hours = Regex("""(\d+)\s*(?:hr|hrs|hour|hours)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("""(\d+)\s*(?:m|min|mins|minute|minutes)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val secs = Regex("""(\d+)\s*(?:s|sec|secs|second|seconds)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 3600 + mins * 60 + secs
        return total.takeIf { it > 0 }
    }

    private fun extractAlarmLabel(clause: String): String? {
        val match = Regex("""(?:for|labeled|called|named)\s+([a-zA-Z0-9 ]{2,28})$""", RegexOption.IGNORE_CASE).find(clause.trim())
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}

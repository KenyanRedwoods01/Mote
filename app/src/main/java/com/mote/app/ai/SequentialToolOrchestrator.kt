package com.mote.app.ai

import android.content.Context
import android.os.Build
import com.mote.app.data.db.LocalModelDao
import com.mote.app.data.db.ToolChainRunEntity
import com.mote.app.service.MediaProjectionConsentBridge
import com.mote.app.service.MoteAccessibilityService
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
    var lastClipboardText: String? = null
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
 *    (e.g., `Take screenshot -> OCR analysis -> Save to Notes`, `Open Chrome, search <query>, and take screenshot`,
 *    `Read clipboard -> Save to Notes`, `Open phone and call <number> then start real-time STT/TTS voice relay`).
 * 2. Manages inter-dependent intermediate state (`ToolChainContext` + Room `ToolChainStateManager`) between atomic steps.
 * 3. Handles pre-flight MediaProjection consent & external-app render settle waits so multi-app workflows
 *    (e.g. Chrome search -> Screenshot) execute reliably without backgrounded dialog stalls.
 * 4. Runs independent post-execution background verification via [GenUiToolRegistry.verifyExecutionInBackground]
 *    before awarding an `EV-XXXXXX` execution receipt and `Execution Verified` badge.
 * 5. Broadcasts every step in real time to [SystemOverlayStateBus] so the animated Siri-style
 *    system overlay floats live over Chrome, YouTube, Phone, and all external apps.
 * 6. Persists every triggered chain into Room (`tool_chain_runs`) for the chronological
 *    'Tool Execution Log' inside Chat History.
 */
class SequentialToolOrchestrator(
    private val toolRegistry: GenUiToolRegistry,
    private val dao: LocalModelDao,
    private val scope: CoroutineScope,
    val stateManager: ToolChainStateManager = ToolChainStateManager(dao, scope),
    private val appContext: Context? = null,
    private val toolExecutionSync: com.mote.app.control.ToolExecutionSyncService? = null
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

    val persistedStates = stateManager.recentStates

    fun clearChainHistory() {
        scope.launch {
            dao.clearToolChainRuns()
            dao.clearToolChainStates()
        }
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

        // Pre-Flight MediaProjection Authorization for Multi-App Chains:
        // If this workflow launches an external app (e.g., Chrome search) AND captures a screenshot
        // afterward, launching Chrome immediately backgrounds Mote and detaches our Activity launcher.
        // Acquiring & caching the MediaProjection token BEFORE Step 1 ensures the downstream screenshot
        // over Chrome runs seamlessly without stalling on a backgrounded consent dialog!
        val firstExternalIdx = invocations.indexOfFirst { isExternalAppTransitionStep(it.tool) }
        val captureAfterExternal = firstExternalIdx >= 0 && invocations.drop(firstExternalIdx + 1)
            .any { isScreenCaptureStep(it.tool) }
        val canUseAccessibilityCapture = MoteAccessibilityService.instance != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (captureAfterExternal &&
            appContext != null &&
            !canUseAccessibilityCapture &&
            !MediaProjectionConsentBridge.hasCachedConsent()
        ) {
            SystemOverlayStateBus.publishAppContext(
                appLabel = "Mote Chain",
                statusBanner = "Authorize screen capture once before opening target app..."
            )
            MediaProjectionConsentBridge.preAuthorizeIfNeeded(appContext)
        }

        for (i in invocations.indices) {
            val stepNumber = i + 1
            // Hydrate context from Room ToolChainStateEntity rows so state survives external app handoffs
            runCatching { stateManager.hydrateContextForChain(chainId, context) }

            val originalInv = invocations[i]
            val (resolvedInv, dependencyNote, missingDependencyError) = injectIntermediateState(
                chainId = chainId,
                stepIndex = stepNumber,
                invocation = originalInv,
                context = context
            )

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
            val preSnapshot = toolExecutionSync?.capturePreExecutionSnapshot()
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
                // Independent post-execution background verification via ToolExecutionSyncService
                // (observes real AlarmManager PendingIntents, Stopwatch live ticks, Dialer/App transitions, etc.)
                val bgVerification = if (toolExecutionSync != null && preSnapshot != null) {
                    toolExecutionSync.verifySystemExecution(result, preSnapshot)
                } else {
                    toolRegistry.verifyExecutionInBackground(result)
                }
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

                // Persist intermediate outputs into Room ToolChainStateEntity + in-memory context for Step i+1
                captureIntermediateState(
                    chainId = chainId,
                    stepIndex = stepNumber,
                    invocation = resolvedInv,
                    result = result,
                    context = context,
                    dependencyKey = dependencyNote
                )

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
            // Smart Inter-Step External-App Transition & Render Settle Window:
            // When Step i launches Chrome/YouTube/Maps/external app and Step i+1 captures the screen
            // or reads OCR, wait for the external app to enter foreground and finish rendering!
            if (i < invocations.lastIndex) {
                val nextInv = invocations[i + 1]
                val launchedExternal = isExternalAppTransitionStep(resolvedInv.tool)
                val nextOpLower = nextInv.tool.lowercase(Locale.US)
                val nextNeedsScreen = isScreenCaptureStep(nextInv.tool) ||
                    nextOpLower in setOf(
                        "pastetoscreen", "pastetext", "pastefocused",
                        "clickcallbutton", "clickcall", "presscall",
                        "presscamerashutter", "camerashutter",
                        "hoverandclick", "hoverelement", "clickelement", "tapelement",
                        "swipescreen", "scrollscreen", "typeinfield", "inspectscreenelements"
                    )

                if (launchedExternal && nextNeedsScreen) {
                    val targetLabel = context.lastOpenedApp ?: resolvedInv.args["appName"]?.jsonPrimitive?.contentOrNull ?: "target app"
                    SystemOverlayStateBus.publishAppContext(
                        appLabel = targetLabel,
                        statusBanner = "Waiting for $targetLabel to load before ${nextInv.tool}..."
                    )
                    // Poll until Android switches foreground away from Mote (if Accessibility is connected)
                    val motePkg = appContext?.packageName
                    if (MoteAccessibilityService.instance != null && !motePkg.isNullOrBlank()) {
                        val deadline = System.currentTimeMillis() + 1_600L
                        while (System.currentTimeMillis() < deadline &&
                            MoteAccessibilityService.currentPackageName == motePkg
                        ) {
                            delay(140L)
                        }
                    }
                    // Allow web search pages (Chrome/YouTube) enough time to paint results before snapping
                    val isWebLoad = isWebSearchOrUrlStep(resolvedInv.tool)
                    delay(if (isWebLoad) 2_200L else 1_100L)
                } else if (launchedExternal) {
                    delay(450L)
                }
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

        // Pre-check direct "open <app> [and|,] search <query> [and|,|then|->] <downstream actions>"
        // Handles natural punctuation like "open chrome,, searching something and taking the screenshot"
        val fullLower = raw.lowercase(Locale.US)
        val openAndSearchMatch = Regex(
            """(?:open|launch|go\s+to)\s+(chrome|browser|youtube|yt|maps|google maps|play store|spotify|amazon)(?:[\s,]+(?:and\s+)?)(?:search(?:ing)?(?:\s+for)?|look(?:ing)?\s+up|find(?:ing)?)\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(raw)
        if (openAndSearchMatch != null && GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
            val appTarget = openAndSearchMatch.groupValues[1].trim()
            val remainder = openAndSearchMatch.groupValues[2].trim()
            val (searchQuery, tailPrompt) = splitSearchQueryAndTrailingActions(remainder)
            // Single atomic searchInApp opens Chrome/YouTube directly to search results without double-launch flicker
            val chain = mutableListOf(
                ChatToolInvocation(
                    tool = "searchInApp",
                    args = buildJsonObject {
                        put("appName", JsonPrimitive(appTarget))
                        put("query", JsonPrimitive(searchQuery))
                    }
                )
            )
            if (!tailPrompt.isNullOrBlank()) {
                chain.addAll(planFallbackChainIfUnverified(tailPrompt, enabledToolIds))
            }
            return chain
        }

        // Split multi-step prompts on arrows (->, →), commas (including ",,"), "and then", "then", "after that", "and", ";"
        val clauses = raw
            .replace(Regex(",{2,}"), ",")
            .split(
                Regex(
                    """\s*(?:->|→)\s*|\s*,\s*(?:and\s+)?|\s+(?:and\s+then|after\s+that|then)\s+|\s+and\s+(?=(?:take|taking|capture|capturing|screenshot|ocr|extract|read|save|saving|copy|copying|write|paste|pasting|clear|share|sharing|send|open|launch|search|searching|google|call|dial|listen|speak|set|start|pause|stop|add|create|delete|list|show|toggle|turn|navigate)\b)|\s*;\s*""",
                    RegexOption.IGNORE_CASE
                )
            )
            .map { it.trim().trimStart(',', ';').trim() }
            .filter { it.isNotBlank() }

        val planned = mutableListOf<ChatToolInvocation>()
        var hasScreenshotStep = false
        var hasOcrStep = false
        var hasClipboardReadStep = false
        var lastMentionedApp: String? = null
        var lastDialedNum: String? = null

        for (clause in clauses) {
            val lower = clause.lowercase(Locale.US)

            // 0. System Clipboard Manager operations (read / write / clear / paste)
            if (GenUiToolRegistry.TOOL_CLIPBOARD in enabledToolIds &&
                (lower.contains("clipboard") || lower.contains("copy to clip") ||
                    Regex("""^copy\s+(.+)""", RegexOption.IGNORE_CASE).containsMatchIn(clause) ||
                    (lower.contains("paste") && (lower.contains("screen") || lower.contains("field") || lower.contains("input") || lower.contains("it") || lower.contains("clipboard"))))
            ) {
                when {
                    lower.contains("clear") || lower.contains("empty") || lower.contains("wipe") -> {
                        planned.add(ChatToolInvocation("clearClipboard", JsonObject(emptyMap())))
                        continue
                    }
                    lower.contains("paste") -> {
                        planned.add(
                            ChatToolInvocation(
                                tool = "pasteToScreen",
                                args = buildJsonObject {
                                    if (hasClipboardReadStep) put("text", JsonPrimitive("\$lastClipboardText"))
                                }
                            )
                        )
                        continue
                    }
                    lower.contains("read") || lower.contains("what") || lower.contains("check") ||
                        lower.contains("get") || lower.contains("show") || lower.contains("inspect") -> {
                        planned.add(ChatToolInvocation("readClipboard", JsonObject(emptyMap())))
                        hasClipboardReadStep = true
                        continue
                    }
                    else -> {
                        val explicitCopy = Regex(
                            """(?:copy|write|put|set)\s+(?:this\s+|text\s+)?(.+?)(?:\s+(?:to|into|in)\s+(?:my\s+|the\s+)?clipboard|$)""",
                            RegexOption.IGNORE_CASE
                        ).find(clause)?.groupValues?.get(1)?.trim()
                        val copyText = when {
                            hasOcrStep && (explicitCopy.isNullOrBlank() || explicitCopy.equals("it", true) || explicitCopy.equals("that", true)) -> "\$lastExtractedText"
                            !explicitCopy.isNullOrBlank() -> explicitCopy.trim('"', '\'')
                            else -> "\$lastExtractedText"
                        }
                        planned.add(
                            ChatToolInvocation(
                                tool = "writeClipboard",
                                args = buildJsonObject {
                                    put("text", JsonPrimitive(copyText))
                                    put("label", JsonPrimitive("Mote AI"))
                                }
                            )
                        )
                        continue
                    }
                }
            }

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
                        val useClipboardContent = hasClipboardReadStep && !hasOcrStep
                        val useOcrContent = hasOcrStep || explicitBody.isNullOrBlank() || lower.contains("to notes") || lower.contains("to my notes")
                        val resolvedContent = when {
                            useClipboardContent -> "\$lastClipboardText"
                            useOcrContent -> "\$lastExtractedText"
                            else -> explicitBody!!
                        }
                        planned.add(
                            ChatToolInvocation(
                                tool = "saveNote",
                                args = buildJsonObject {
                                    put(
                                        "title",
                                        JsonPrimitive(
                                            when {
                                                hasOcrStep -> "Screenshot OCR Note"
                                                useClipboardContent -> "Clipboard Note"
                                                else -> "Quick Note"
                                            }
                                        )
                                    )
                                    put("content", JsonPrimitive(resolvedContent))
                                    put(
                                        "source",
                                        JsonPrimitive(
                                            when {
                                                hasOcrStep -> "Screenshot -> OCR Chain"
                                                useClipboardContent -> "Clipboard Chain"
                                                else -> "Mote Voice/Chat"
                                            }
                                        )
                                    )
                                }
                            )
                        )
                    }
                }
                continue
            }

            // 4. Phone Call / Dial Number / Click Call Button / Send SMS
            if (lower.contains("click call") || lower.contains("press call") || lower.contains("tap call")) {
                planned.add(
                    ChatToolInvocation(
                        tool = "clickCallButton",
                        args = buildJsonObject {
                            lastDialedNum?.let { put("phoneNumber", JsonPrimitive(it)) }
                        }
                    )
                )
                continue
            }
            val smsMatch = Regex("""(?:send\s+(?:an?\s+)?(?:sms|text)|text)\s+(?:to\s+)?([+0-9][0-9\-\s()]{2,18})(?:\s+(?:saying|that|:)\s+(.+))?""", RegexOption.IGNORE_CASE).find(clause)
            if (smsMatch != null) {
                val number = smsMatch.groupValues[1].trim()
                val body = smsMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                planned.add(
                    ChatToolInvocation(
                        tool = "sendSms",
                        args = buildJsonObject {
                            put("phoneNumber", JsonPrimitive(number))
                            if (body.isNotBlank()) put("message", JsonPrimitive(body))
                        }
                    )
                )
                continue
            }
            val phoneMatch = Regex("""(?:call|dial|ring|phone)\s+([+0-9*#][0-9*#\-\s()]{2,18})""", RegexOption.IGNORE_CASE).find(clause)
            if (phoneMatch != null) {
                val number = phoneMatch.groupValues[1].trim()
                lastDialedNum = number
                planned.add(
                    ChatToolInvocation(
                        tool = "dialPhone",
                        args = buildJsonObject { put("phoneNumber", JsonPrimitive(number)) }
                    )
                )
                // If Accessibility live cursor is active or user asked to place/click call, chain clickCallButton!
                if (MoteAccessibilityService.instance != null &&
                    (lower.contains("call") || lower.contains("ring") || lower.contains("click") || lower.contains("place"))
                ) {
                    planned.add(
                        ChatToolInvocation(
                            tool = "clickCallButton",
                            args = buildJsonObject { put("phoneNumber", JsonPrimitive(number)) }
                        )
                    )
                }
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
            val searchMatch = Regex("""(?:search(?:ing)?(?:\s+for)?|google|look(?:ing)?\s+up)\s+(.+)""", RegexOption.IGNORE_CASE).find(clause)
            if (searchMatch != null && (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds || GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds)) {
                val rawQuery = searchMatch.groupValues[1].trim()
                val query = if (hasClipboardReadStep && (rawQuery.equals("it", true) || rawQuery.contains("clipboard", true))) {
                    "\$lastClipboardText"
                } else {
                    rawQuery
                }
                val targetApp = lastMentionedApp ?: "Chrome"
                // Collapse redundant preceding openApp(targetApp) so Chrome/YouTube launches straight to results without double-launch flicker
                if (planned.lastOrNull()?.tool.equals("openApp", ignoreCase = true) &&
                    planned.lastOrNull()?.args?.get("appName")?.jsonPrimitive?.contentOrNull.equals(targetApp, ignoreCase = true)
                ) {
                    planned.removeAt(planned.lastIndex)
                }
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

            // 8. Alarm creation / update / toggle / deletion / listing
            if (GenUiToolRegistry.TOOL_ALARM_MANAGER in enabledToolIds &&
                (lower.contains("alarm") || lower.contains("wake me"))
            ) {
                when {
                    lower.contains("snooze") || lower.contains("delay") || lower.contains("push") || lower.contains("shift") -> {
                        val deltaMatch = Regex("""(-?\d+)\s*(?:minutes?|mins?|m)\b""").find(lower)
                        val mins = deltaMatch?.groupValues?.get(1)?.toIntOrNull() ?: 10
                        val query = extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = if (lower.contains("snooze")) "snoozeAlarm" else "shiftAlarm",
                                args = buildJsonObject {
                                    put("query", JsonPrimitive(query))
                                    put("minutes", JsonPrimitive(mins))
                                }
                            )
                        )
                    }
                    lower.contains("sync") -> {
                        val query = extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = "syncAlarm",
                                args = buildJsonObject { put("query", JsonPrimitive(query)) }
                            )
                        )
                    }
                    lower.contains("delete") || lower.contains("cancel") || lower.contains("remove") -> {
                        val query = if (lower.contains("all")) "all" else extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = "deleteAlarm",
                                args = buildJsonObject { put("query", JsonPrimitive(query)) }
                            )
                        )
                    }
                    lower.contains("turn off") || lower.contains("disable") || lower.contains("unset") -> {
                        val query = if (lower.contains("all")) "all" else extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = "toggleAlarm",
                                args = buildJsonObject {
                                    put("query", JsonPrimitive(query))
                                    put("enabled", JsonPrimitive(false))
                                }
                            )
                        )
                    }
                    lower.contains("turn on") || lower.contains("enable") -> {
                        val query = if (lower.contains("all")) "all" else extractAlarmLabel(clause) ?: ""
                        planned.add(
                            ChatToolInvocation(
                                tool = "toggleAlarm",
                                args = buildJsonObject {
                                    put("query", JsonPrimitive(query))
                                    put("enabled", JsonPrimitive(true))
                                }
                            )
                        )
                    }
                    lower.contains("list") || lower.contains("show") || lower.contains("what alarm") || lower.contains("my alarm") -> {
                        planned.add(ChatToolInvocation("listAlarms", JsonObject(emptyMap())))
                    }
                    else -> {
                        val relativeMatch = Regex("""in\s+(\d+)\s*(minutes?|mins?|m|hours?|hrs?|h)\b""").find(lower)
                        val parsedTime = parseHourMinute(lower)
                        val label = extractAlarmLabel(clause) ?: "Alarm"
                        if (relativeMatch != null && parsedTime == null) {
                            val amount = relativeMatch.groupValues[1].toIntOrNull() ?: 5
                            val isHours = relativeMatch.groupValues[2].startsWith("h")
                            val mins = if (isHours) amount * 60 else amount
                            planned.add(
                                ChatToolInvocation(
                                    tool = "addAlarm",
                                    args = buildJsonObject {
                                        put("inMinutes", JsonPrimitive(mins))
                                        put("label", JsonPrimitive(label))
                                        put("enabled", JsonPrimitive(true))
                                    }
                                )
                            )
                        } else if (parsedTime != null) {
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
                        } else {
                            planned.add(ChatToolInvocation("listAlarms", JsonObject(emptyMap())))
                        }
                    }
                }
                continue
            }

            // 9. Countdown Timer (supports "timer" and "countdown")
            if (GenUiToolRegistry.TOOL_COUNTDOWN_TIMER in enabledToolIds &&
                (lower.contains("timer") || lower.contains("countdown"))
            ) {
                when {
                    lower.contains("pause") -> planned.add(ChatToolInvocation("pauseTimer", JsonObject(emptyMap())))
                    lower.contains("resume") -> planned.add(ChatToolInvocation("resumeTimer", JsonObject(emptyMap())))
                    lower.contains("reset") -> planned.add(ChatToolInvocation("resetTimer", JsonObject(emptyMap())))
                    lower.contains("delete") || lower.contains("cancel") || lower.contains("stop") || lower.contains("clear") ->
                        planned.add(ChatToolInvocation("deleteTimer", JsonObject(emptyMap())))
                    lower.contains("status") || lower.contains("left") || lower.contains("remaining") || lower.contains("how much") ->
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

            // 10. Stopwatch (supports "stopwatch", "stop watch", "record lap")
            if (GenUiToolRegistry.TOOL_STOPWATCH in enabledToolIds &&
                (lower.contains("stopwatch") || lower.contains("stop watch") || Regex("""\b(lap|split)\b""").containsMatchIn(lower))
            ) {
                val op = when {
                    lower.contains("clear lap") || lower.contains("delete lap") -> "clearLaps"
                    lower.contains("list lap") || lower.contains("show lap") -> "listLaps"
                    lower.contains("pause") || Regex("""\bstop\s+(the\s+)?stop\s*watch\b""").containsMatchIn(lower) ||
                        (lower.contains("stop") && !lower.contains("start") && !lower.contains("stop watch")) -> "pauseStopwatch"
                    lower.contains("lap") || lower.contains("split") -> "lapStopwatch"
                    lower.contains("reset") || lower.contains("clear") -> "resetStopwatch"
                    lower.contains("status") || lower.contains("elapsed") || lower.contains("how long") -> "getStopwatchStatus"
                    else -> "startStopwatch"
                }
                planned.add(ChatToolInvocation(op, JsonObject(emptyMap())))
                continue
            }

            // 10b. Wi-Fi & Network Tools
            if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("network") ||
                lower.contains("internet") || lower.contains("ping") || lower.contains("airplane mode") ||
                lower.contains("mobile data")
            ) {
                val op = when {
                    lower.contains("ping") || lower.contains("latency") || lower.contains("test internet") || lower.contains("speed") ->
                        "testConnectivity"
                    lower.contains("airplane") || lower.contains("flight mode") -> "airplaneMode"
                    lower.contains("mobile data") || lower.contains("cellular") -> "mobileData"
                    lower.contains("internet panel") || lower.contains("connectivity") -> "openInternetPanel"
                    lower.contains("open wifi") || lower.contains("wifi panel") || lower.contains("turn on wifi") ||
                        lower.contains("turn off wifi") || lower.contains("toggle wifi") || lower.contains("connect wifi") ->
                        "openWifiPanel"
                    else -> "getNetworkStatus"
                }
                planned.add(ChatToolInvocation(op, JsonObject(emptyMap())))
                continue
            }

            // 10c. Application Control & Real-Time Cursor Hover Tools
            if (lower.contains("inspect screen") || lower.contains("screen elements") || lower.contains("list buttons") || lower.contains("ui elements")) {
                planned.add(ChatToolInvocation("inspectScreenElements", JsonObject(emptyMap())))
                continue
            }
            val swipeMatch = Regex("""(?:swipe|scroll)\s+(up|down|left|right)""", RegexOption.IGNORE_CASE).find(lower)
            if (swipeMatch != null) {
                planned.add(
                    ChatToolInvocation(
                        tool = "swipeScreen",
                        args = buildJsonObject { put("direction", JsonPrimitive(swipeMatch.groupValues[1])) }
                    )
                )
                continue
            }
            val hoverClickMatch = Regex("""(?:hover(?:\s+and)?\s+(?:click|tap)|hover\s+(?:over|on)|cursor\s+(?:click|tap|hover)|click\s+(?:on\s+)?(?:the\s+)?|tap\s+(?:on\s+)?(?:the\s+)?)(.+)""", RegexOption.IGNORE_CASE).find(clause)
            if (hoverClickMatch != null) {
                val target = hoverClickMatch.groupValues[1].trim().trimEnd('.', '!')
                val hoverOnly = lower.contains("hover") && !lower.contains("click") && !lower.contains("tap")
                if (target.isNotBlank()) {
                    planned.add(
                        ChatToolInvocation(
                            tool = "hoverAndClick",
                            args = buildJsonObject {
                                put("target", JsonPrimitive(target))
                                put("hoverOnly", JsonPrimitive(hoverOnly))
                            }
                        )
                    )
                    continue
                }
            }

            // 11. Flashlight / Camera (full capabilities + linked shutter trigger)
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
                if (lower.contains("camera spec") || lower.contains("camera info") || lower.contains("camera lens") || lower.contains("inspect camera")) {
                    planned.add(ChatToolInvocation("inspectCamera", JsonObject(emptyMap())))
                    continue
                }
                if (lower.contains("shutter") || lower.contains("snap photo") || lower.contains("capture now")) {
                    planned.add(ChatToolInvocation("pressCameraShutter", JsonObject(emptyMap())))
                    continue
                }
                if (lower.contains("camera") || lower.contains("take a photo") || lower.contains("take photo") ||
                    lower.contains("selfie") || lower.contains("record video")
                ) {
                    val isVideo = lower.contains("video") || lower.contains("record")
                    val facing = if (lower.contains("front") || lower.contains("selfie")) "front" else "back"
                    val timerMatch = Regex("""(\d+)\s*(?:s|sec|second)""").find(lower)
                    val timerSec = timerMatch?.groupValues?.get(1)?.toIntOrNull()
                    planned.add(
                        ChatToolInvocation(
                            tool = when {
                                isVideo -> "openVideoCamera"
                                facing == "front" -> "captureSelfie"
                                else -> "openCamera"
                            },
                            args = buildJsonObject {
                                put("mode", JsonPrimitive(if (isVideo) "video" else "photo"))
                                put("facing", JsonPrimitive(facing))
                                if (timerSec != null) put("timerSeconds", JsonPrimitive(timerSec))
                            }
                        )
                    )
                    if ((lower.contains("take a photo") || lower.contains("take photo") || lower.contains("snap")) &&
                        MoteAccessibilityService.instance != null
                    ) {
                        planned.add(ChatToolInvocation("pressCameraShutter", JsonObject(emptyMap())))
                    }
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

    private suspend fun injectIntermediateState(
        chainId: String,
        stepIndex: Int,
        invocation: ChatToolInvocation,
        context: ToolChainContext
    ): Triple<ChatToolInvocation, String?, String?> {
        val op = invocation.tool.trim().lowercase(Locale.US)

        // Pipe screenshot captureId -> OCR Analysis (hydrated via Room ToolChainState)
        if (op in setOf("ocranalysis", "ocr", "extracttext", "extract_text", "readscreenshot", "read_screenshot_text")) {
            val rawCap = invocation.args["captureId"]?.jsonPrimitive?.contentOrNull
            val persistedCap = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_CAPTURE_ID)
            val resolvedCapId = context.lastCaptureId ?: persistedCap?.outputValue
            if ((rawCap.isNullOrBlank() || rawCap.startsWith("$")) && !resolvedCapId.isNullOrBlank()) {
                persistedCap?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("captureId", JsonPrimitive(resolvedCapId))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: lastCaptureId=$resolvedCapId",
                    null
                )
            }
        }

        // Pipe OCR extracted text OR clipboard text -> Save to Notes (hydrated via Room ToolChainState)
        if (op in setOf("savenote", "savetonotes", "save_note", "save_to_notes", "createnote", "addnote")) {
            val rawContent = invocation.args["content"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["text"]?.jsonPrimitive?.contentOrNull
            if (rawContent?.contains("\$lastClipboardText") == true) {
                val persistedClip = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_CLIPBOARD_TEXT)
                val clipText = context.lastClipboardText ?: persistedClip?.outputValue
                if (clipText.isNullOrBlank()) {
                    return Triple(
                        invocation,
                        null,
                        "Skipped Save to Notes — system clipboard did not contain readable text."
                    )
                }
                persistedClip?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("content", JsonPrimitive(clipText))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: lastClipboardText (${clipText.length} chars)",
                    null
                )
            }
            if (rawContent.isNullOrBlank() || rawContent.contains("\$lastExtractedText")) {
                val persistedText = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_EXTRACTED_TEXT)
                val persistedClip = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_CLIPBOARD_TEXT)
                val extracted = context.lastExtractedText ?: persistedText?.outputValue ?: context.lastClipboardText ?: persistedClip?.outputValue
                if (extracted.isNullOrBlank()) {
                    return Triple(
                        invocation,
                        null,
                        "Skipped Save to Notes — dependent OCR/clipboard step did not produce text."
                    )
                }
                persistedText?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("content", JsonPrimitive(extracted))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: lastExtractedText (${extracted.length} chars)",
                    null
                )
            }
        }

        // Pipe OCR / Clipboard text -> writeClipboard / pasteToScreen
        if (op in setOf("writeclipboard", "copytoclipboard", "setclipboard", "clipboardwrite", "copytext", "pastetoscreen", "pastetext", "pastefocused")) {
            val rawText = invocation.args["text"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["content"]?.jsonPrimitive?.contentOrNull
            if (rawText.isNullOrBlank() || rawText.startsWith("$")) {
                val prefersClipboard = rawText?.contains("Clipboard", ignoreCase = true) == true || op.startsWith("paste")
                val resolved = if (prefersClipboard) {
                    val row = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_CLIPBOARD_TEXT)
                    row?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                    context.lastClipboardText ?: row?.outputValue
                } else {
                    val row = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_EXTRACTED_TEXT)
                    row?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                    context.lastExtractedText ?: row?.outputValue ?: context.lastClipboardText
                }
                if (!resolved.isNullOrBlank()) {
                    val merged = buildJsonObject {
                        invocation.args.forEach { (k, v) -> put(k, v) }
                        put("text", JsonPrimitive(resolved))
                    }
                    return Triple(
                        invocation.copy(args = merged),
                        "Room state piped: ${if (prefersClipboard) "lastClipboardText" else "lastExtractedText"} (${resolved.length} chars)",
                        null
                    )
                }
            }
        }

        // Pipe screenshot URI / OCR text / Clipboard text -> Share to App (hydrated via Room ToolChainState)
        if (op in setOf("sharetoapp", "sharecontent", "share_screenshot", "sharescreenshot")) {
            val explicitUri = invocation.args["mediaUri"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["uri"]?.jsonPrimitive?.contentOrNull
            val wantsScreenshot = invocation.args["useLastScreenshot"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            if ((explicitUri.isNullOrBlank() || explicitUri.startsWith("$")) && wantsScreenshot) {
                val persistedUri = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_SAVED_URI)
                val savedUri = context.lastSavedUri ?: persistedUri?.outputValue
                if (savedUri.isNullOrBlank()) {
                    return Triple(
                        invocation,
                        null,
                        "Skipped share — dependent screenshot capture did not produce a saved image URI."
                    )
                }
                persistedUri?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("mediaUri", JsonPrimitive(savedUri))
                    context.lastCaptureId?.let { put("captureId", JsonPrimitive(it)) }
                    val rawText = invocation.args["text"]?.jsonPrimitive?.contentOrNull
                    if (rawText == "\$lastExtractedText" && !context.lastExtractedText.isNullOrBlank()) {
                        put("text", JsonPrimitive(context.lastExtractedText!!))
                    } else if (rawText == "\$lastClipboardText" && !context.lastClipboardText.isNullOrBlank()) {
                        put("text", JsonPrimitive(context.lastClipboardText!!))
                    }
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: lastSavedUri",
                    null
                )
            }
        }

        // Pipe opened app AND/OR clipboard query -> searchInApp
        if (op in setOf("searchinapp", "searchchrome", "openchromeandsearch", "searchyoutube")) {
            val appArg = invocation.args["appName"]?.jsonPrimitive?.contentOrNull
            val queryArg = invocation.args["query"]?.jsonPrimitive?.contentOrNull
            val persistedApp = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_OPENED_APP)
            val resolvedApp = context.lastOpenedApp ?: persistedApp?.outputValue
            val needsApp = (appArg.isNullOrBlank() || appArg.startsWith("$")) && !resolvedApp.isNullOrBlank()
            val needsClipQuery = queryArg?.contains("\$lastClipboardText") == true
            if (needsApp || needsClipQuery) {
                val notes = mutableListOf<String>()
                var resolvedClip: String? = null
                if (needsClipQuery) {
                    val clipRow = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_CLIPBOARD_TEXT)
                    resolvedClip = context.lastClipboardText ?: clipRow?.outputValue
                    if (resolvedClip.isNullOrBlank()) {
                        return Triple(invocation, null, "Skipped search — clipboard had no text to search.")
                    }
                    clipRow?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                    notes.add("lastClipboardText")
                }
                if (needsApp) {
                    persistedApp?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                    notes.add("lastOpenedApp=$resolvedApp")
                }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    if (needsApp && !resolvedApp.isNullOrBlank()) put("appName", JsonPrimitive(resolvedApp))
                    if (needsClipQuery && !resolvedClip.isNullOrBlank()) put("query", JsonPrimitive(resolvedClip))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: ${notes.joinToString(", ")}",
                    null
                )
            }
        }

        // Pipe dialed phone number -> startVoiceCallRelay
        if (op in setOf("startvoicecallrelay", "startvoicerelay", "voicecallrelay", "livevoicerelay")) {
            val targetArg = invocation.args["targetLabel"]?.jsonPrimitive?.contentOrNull
            val persistedNum = stateManager.resolveDependency(chainId, ToolChainStateManager.KEY_LAST_DIALED_NUMBER)
            val resolvedNum = context.lastDialedNumber ?: persistedNum?.outputValue
            if ((targetArg.isNullOrBlank() || targetArg.startsWith("$")) && !resolvedNum.isNullOrBlank()) {
                persistedNum?.let { stateManager.markConsumed(it.stateId, stepIndex) }
                val merged = buildJsonObject {
                    invocation.args.forEach { (k, v) -> put(k, v) }
                    put("targetLabel", JsonPrimitive("Call $resolvedNum"))
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Room state piped: lastDialedNumber=$resolvedNum",
                    null
                )
            }
        }

        return Triple(invocation, null, null)
    }

    private suspend fun captureIntermediateState(
        chainId: String,
        stepIndex: Int,
        invocation: ChatToolInvocation,
        result: GenUiToolResult,
        context: ToolChainContext,
        dependencyKey: String?
    ) {
        context.stepOutputs[result.operation] = result.feedback
        runCatching {
            stateManager.recordStepOutput(
                chainId = chainId,
                stepIndex = stepIndex,
                toolName = result.operation,
                outputKey = "step_${stepIndex}_output",
                outputValue = result.feedback.take(240),
                dependencyKey = dependencyKey
            )
        }

        if (!result.extractedText.isNullOrBlank()) {
            context.lastExtractedText = result.extractedText
            runCatching {
                stateManager.recordStepOutput(
                    chainId = chainId,
                    stepIndex = stepIndex,
                    toolName = result.operation,
                    outputKey = ToolChainStateManager.KEY_LAST_EXTRACTED_TEXT,
                    outputValue = result.extractedText,
                    dependencyKey = dependencyKey
                )
            }
        }
        if (!result.createdRecordId.isNullOrBlank() && result.toolId == GenUiToolRegistry.TOOL_NOTES) {
            context.lastNoteId = result.createdRecordId
            runCatching {
                stateManager.recordStepOutput(
                    chainId = chainId,
                    stepIndex = stepIndex,
                    toolName = result.operation,
                    outputKey = ToolChainStateManager.KEY_LAST_NOTE_ID,
                    outputValue = result.createdRecordId,
                    dependencyKey = dependencyKey
                )
            }
        }
        if (result.operation.equals("dialPhone", ignoreCase = true) && result.success) {
            val num = invocation.args["phoneNumber"]?.jsonPrimitive?.contentOrNull
            if (!num.isNullOrBlank()) {
                context.lastDialedNumber = num
                runCatching {
                    stateManager.recordStepOutput(
                        chainId = chainId,
                        stepIndex = stepIndex,
                        toolName = result.operation,
                        outputKey = ToolChainStateManager.KEY_LAST_DIALED_NUMBER,
                        outputValue = num,
                        dependencyKey = dependencyKey
                    )
                }
            }
        }
        if (result.operation.equals("searchInApp", ignoreCase = true) && result.success) {
            invocation.args["query"]?.jsonPrimitive?.contentOrNull?.let { q ->
                context.lastSearchQuery = q
                runCatching {
                    stateManager.recordStepOutput(
                        chainId = chainId,
                        stepIndex = stepIndex,
                        toolName = result.operation,
                        outputKey = ToolChainStateManager.KEY_LAST_SEARCH_QUERY,
                        outputValue = q,
                        dependencyKey = dependencyKey
                    )
                }
            }
        }
        when (val part = result.uiPart) {
            is InChatUiPart.ScreenCapturePanel -> {
                if (part.success) {
                    if (!part.captureId.isNullOrBlank()) {
                        context.lastCaptureId = part.captureId
                        runCatching {
                            stateManager.recordStepOutput(
                                chainId = chainId,
                                stepIndex = stepIndex,
                                toolName = result.operation,
                                outputKey = ToolChainStateManager.KEY_LAST_CAPTURE_ID,
                                outputValue = part.captureId,
                                dependencyKey = dependencyKey
                            )
                        }
                    }
                    if (!part.savedUriString.isNullOrBlank()) {
                        context.lastSavedUri = part.savedUriString
                        runCatching {
                            stateManager.recordStepOutput(
                                chainId = chainId,
                                stepIndex = stepIndex,
                                toolName = result.operation,
                                outputKey = ToolChainStateManager.KEY_LAST_SAVED_URI,
                                outputValue = part.savedUriString,
                                dependencyKey = dependencyKey
                            )
                        }
                    }
                }
            }
            is InChatUiPart.OcrResultPanel -> {
                if (part.success && part.extractedText.isNotBlank()) {
                    context.lastExtractedText = part.extractedText
                    runCatching {
                        stateManager.recordStepOutput(
                            chainId = chainId,
                            stepIndex = stepIndex,
                            toolName = result.operation,
                            outputKey = ToolChainStateManager.KEY_LAST_EXTRACTED_TEXT,
                            outputValue = part.extractedText,
                            dependencyKey = dependencyKey
                        )
                    }
                }
            }
            is InChatUiPart.NotesPanel -> {
                if (part.success && !part.highlightNoteId.isNullOrBlank()) {
                    context.lastNoteId = part.highlightNoteId
                    runCatching {
                        stateManager.recordStepOutput(
                            chainId = chainId,
                            stepIndex = stepIndex,
                            toolName = result.operation,
                            outputKey = ToolChainStateManager.KEY_LAST_NOTE_ID,
                            outputValue = part.highlightNoteId,
                            dependencyKey = dependencyKey
                        )
                    }
                }
            }
            is InChatUiPart.ClockAlarmsPanel -> {
                if (part.success && !part.highlightAlarmId.isNullOrBlank()) {
                    context.lastAlarmId = part.highlightAlarmId
                    runCatching {
                        stateManager.recordStepOutput(
                            chainId = chainId,
                            stepIndex = stepIndex,
                            toolName = result.operation,
                            outputKey = ToolChainStateManager.KEY_LAST_ALARM_ID,
                            outputValue = part.highlightAlarmId,
                            dependencyKey = dependencyKey
                        )
                    }
                }
            }
            is InChatUiPart.CalendarEventsPanel -> {
                if (part.success && !part.highlightEventId.isNullOrBlank()) {
                    context.lastEventId = part.highlightEventId
                    runCatching {
                        stateManager.recordStepOutput(
                            chainId = chainId,
                            stepIndex = stepIndex,
                            toolName = result.operation,
                            outputKey = ToolChainStateManager.KEY_LAST_EVENT_ID,
                            outputValue = part.highlightEventId,
                            dependencyKey = dependencyKey
                        )
                    }
                }
            }
            is InChatUiPart.ClipboardPanel -> {
                if (part.success && !part.clipboardText.isNullOrBlank()) {
                    context.lastClipboardText = part.clipboardText
                    runCatching {
                        stateManager.recordStepOutput(
                            chainId = chainId,
                            stepIndex = stepIndex,
                            toolName = result.operation,
                            outputKey = ToolChainStateManager.KEY_LAST_CLIPBOARD_TEXT,
                            outputValue = part.clipboardText,
                            dependencyKey = dependencyKey
                        )
                    }
                }
            }
            is InChatUiPart.DeviceActionPanel -> {
                if (part.success && part.category.equals("APPS", ignoreCase = true)) {
                    val launchedApp = part.title
                        .removePrefix("Opened ")
                        .removePrefix("Searched ")
                        .trim()
                    if (launchedApp.isNotBlank()) {
                        context.lastOpenedApp = launchedApp
                        runCatching {
                            stateManager.recordStepOutput(
                                chainId = chainId,
                                stepIndex = stepIndex,
                                toolName = result.operation,
                                outputKey = ToolChainStateManager.KEY_LAST_OPENED_APP,
                                outputValue = launchedApp,
                                dependencyKey = dependencyKey
                            )
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun splitSearchQueryAndTrailingActions(remainder: String): Pair<String, String?> {
        // First check explicit separators: "->", ";", "and then", "then", or comma before another action
        val explicitMatch = Regex(
            """(.*?)(?:\s*(?:->|→|;)\s*|\s+(?:and\s+then|after\s+that|then)\s+|\s*,+\s*(?:and\s+)?|\s+and\s+(?=(?:take|taking|capture|capturing|screenshot|ocr|extract|read|save|saving|copy|copying|paste|share|sharing|send|open|launch|call|dial|set|start)\b))(.+)""",
            RegexOption.IGNORE_CASE
        ).find(remainder)
        return if (explicitMatch != null) {
            val q = explicitMatch.groupValues[1].trim().trimEnd(',', ';').trim()
            val tail = explicitMatch.groupValues[2].trim()
            (q.ifBlank { remainder.trim() }) to tail.takeIf { it.isNotBlank() }
        } else {
            remainder.trim() to null
        }
    }

    private fun isExternalAppTransitionStep(toolName: String): Boolean {
        val op = toolName.lowercase(Locale.US)
        return op in setOf(
            "openapp", "launchapp", "startapp", "app_open", "navigateapp", "open_application",
            "searchinapp", "searchchrome", "openchromeandsearch", "searchyoutube", "chrome_search",
            "searchweb", "websearch", "openurl", "openwebsite", "browse",
            "navigatemaps", "directions", "openmaps",
            "dialphone", "dial_phone", "callnumber", "callphone", "openphone", "dial", "phonecall", "placecall",
            "opencamera", "camera", "launchcamera", "takephoto", "captureselfie", "openvideocamera",
            "openrecentapp", "switchlastapp"
        )
    }

    private fun isWebSearchOrUrlStep(toolName: String): Boolean {
        val op = toolName.lowercase(Locale.US)
        return op in setOf(
            "searchinapp", "searchchrome", "openchromeandsearch", "searchyoutube", "chrome_search",
            "searchweb", "websearch", "openurl", "openwebsite", "browse"
        )
    }

    private fun isScreenCaptureStep(toolName: String): Boolean {
        val op = toolName.lowercase(Locale.US)
        return op in setOf(
            "takescreenshot", "screenshot", "capturescreen", "screen_capture",
            "captureandanalyzescreen", "analyzescreen", "capturescreenforanalysis",
            "capturescreencontext", "readscreen", "screen_context",
            "ocranalysis", "ocr", "extracttext", "extract_text"
        )
    }

    private fun formatOperationLabel(inv: ChatToolInvocation): String {
        val op = inv.tool.trim()
        val detail = inv.args["appName"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["phoneNumber"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["targetLabel"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["target"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["direction"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["query"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["label"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["title"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["destination"]?.jsonPrimitive?.contentOrNull
        return if (!detail.isNullOrBlank() && !detail.startsWith("$")) "$op($detail)" else "$op()"
    }

    private fun resolveEngineLabel(tool: String): String {
        val op = tool.lowercase(Locale.US)
        return when {
            op.contains("clipboard") || op.contains("paste") || op.contains("copy") -> "Android ClipboardManager"
            op.contains("alarm") -> "Android AlarmManager"
            op.contains("timer") || op.contains("countdown") -> "Live Countdown Engine"
            op.contains("stopwatch") || op.contains("lap") -> "Live Stopwatch Engine"
            op.contains("event") || op.contains("calendar") -> "CalendarContract + Room"
            op.contains("ocr") || op.contains("extract") -> "Gemini Vision OCR"
            op.contains("note") -> "Mote Notes Room DB"
            op.contains("clickcall") || op.contains("presscall") -> "Live Cursor · Dialer Call"
            op.contains("sms") || op.contains("sendtext") -> "Android ACTION_SENDTO (SMS)"
            op.contains("dial") || (op.contains("call") && !op.contains("relay")) -> "Android ACTION_DIAL"
            op.contains("relay") || op.contains("speak") || op.contains("tts") -> "Google STT ↔ AI ↔ TTS"
            op.contains("wifi") || op.contains("network") || op.contains("internet") || op.contains("ping") || op.contains("connectivity") || op.contains("airplane") -> "ConnectivityManager / Wi-Fi"
            op.contains("hover") || op.contains("cursor") || op.contains("swipe") || op.contains("scroll") || op.contains("inspectscreen") || op.contains("typeinfield") -> "Accessibility Live Cursor"
            op.contains("screenshot") || op.contains("screen") -> "MediaProjection / Display Capture"
            op.contains("share") -> "Android ACTION_SEND"
            op.contains("search") -> "Chrome / App Search + Overlay"
            op.contains("recentapp") || op.contains("usagestats") -> "UsageStatsManager"
            op.contains("app") -> "PackageManager Intent"
            op.contains("flashlight") || op.contains("torch") -> "Camera2 Torch API"
            op.contains("shutter") -> "Live Cursor · Camera Shutter"
            op.contains("camera") || op.contains("photo") || op.contains("selfie") -> "Android Camera2 / Intent"
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

package com.mote.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

enum class ToolStepStatus(val label: String) {
    QUEUED("Queued"),
    RUNNING("Executing..."),
    EXECUTION_VERIFIED("Execution Verified"),
    BLOCKED("Blocked by Policy"),
    FAILED("Execution Failed")
}

data class ToolChainStepRecord(
    val stepIndex: Int,
    val toolName: String,
    val operationLabel: String,
    val status: ToolStepStatus,
    val engineLabel: String,
    val receiptId: String? = null,
    val latencyMs: Long = 0L,
    val summary: String = "",
    val usedIntermediateState: String? = null
)

data class ToolChainExecutionSnapshot(
    val chainId: String,
    val chainTitle: String,
    val steps: List<ToolChainStepRecord>,
    val isComplete: Boolean,
    val allVerified: Boolean,
    val verifiedCount: Int,
    val failedCount: Int
)

/**
 * Shared intermediate state passed sequentially from Step N to Step N+1 in a multi-step tool chain
 * (e.g. Step 1 `takeScreenshot` stores `lastSavedUri` + `lastCaptureId`, which Step 2 `shareToApp`
 * consumes automatically to share the screenshot on WhatsApp).
 */
class ToolChainContext {
    var lastCaptureId: String? = null
    var lastSavedUri: String? = null
    var lastOpenedApp: String? = null
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
 * Sequential Tool Execution Orchestrator & Engine Execution Verifier.
 *
 * Responsibilities:
 * 1. Expands both standalone tool calls and `runToolChain` multi-step arrays into sequential steps.
 * 2. Manages intermediate state storage (`ToolChainContext`) and dependency checks between steps.
 * 3. Emits real-time `ToolChainExecutionSnapshot` progress updates for the Generative UI status widget.
 * 4. Issues verifiable execution receipts (`EV-XXXX`) ONLY after the underlying Android API returns success.
 * 5. Deterministically plans and executes multi-step chains if the LLM forgets to emit `<<<TOOL_CALL>>>`
 *    on follow-up turns, preventing false "Done!" claims without real execution.
 */
class SequentialToolOrchestrator(
    private val toolRegistry: GenUiToolRegistry
) {

    private val _activeChain = MutableStateFlow<ToolChainExecutionSnapshot?>(null)
    val activeChain: StateFlow<ToolChainExecutionSnapshot?> = _activeChain.asStateFlow()

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
     * Executes a sequence of tool invocations one-by-one while threading intermediate state
     * across dependent steps and verifying every Android API outcome.
     */
    suspend fun executeChain(
        rawInvocations: List<ChatToolInvocation>,
        chainTitle: String = "Tool Execution Chain",
        onProgress: (ToolChainExecutionSnapshot) -> Unit = {}
    ): ToolChainRunResult {
        val invocations = expandInvocations(rawInvocations)
        val chainId = "chain_${UUID.randomUUID().toString().take(8)}"
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
                failedCount = failed
            )
            _activeChain.value = snap
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
                    usedIntermediateState = dependencyNote
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
            val durationMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)

            if (result == null) {
                steps[i] = steps[i].copy(
                    status = ToolStepStatus.FAILED,
                    latencyMs = durationMs,
                    summary = "Unregistered tool operation '${resolvedInv.tool}'."
                )
            } else {
                val isBlocked = !result.success && result.feedback.contains("unauthorized", ignoreCase = true)
                val status = when {
                    result.success -> ToolStepStatus.EXECUTION_VERIFIED
                    isBlocked -> ToolStepStatus.BLOCKED
                    else -> ToolStepStatus.FAILED
                }
                val receipt = if (result.success) {
                    "EV-${UUID.randomUUID().toString().take(6).uppercase(Locale.US)}"
                } else {
                    null
                }

                // Store intermediate state for downstream dependent steps!
                captureIntermediateState(result, context)

                steps[i] = steps[i].copy(
                    status = status,
                    receiptId = receipt,
                    latencyMs = durationMs,
                    summary = result.feedback
                )
                result.uiPart?.let { producedUiParts.add(it) }
            }

            publishSnapshot(complete = false)
        }

        val finalSnapshot = publishSnapshot(complete = true)
        val statusPart = InChatUiPart.ToolChainStatusPanel(
            partId = "chain_ui_${System.nanoTime()}",
            snapshot = finalSnapshot
        )
        // Put the Execution Verification status card first so the user sees proof of execution immediately
        val orderedParts = listOf(statusPart) + producedUiParts
        return ToolChainRunResult(
            snapshot = finalSnapshot,
            uiParts = orderedParts,
            context = context
        )
    }

    /**
     * Enforces truth-in-execution: ensures the final assistant response explicitly reflects
     * whether the Android API execution was verified or failed, preventing hallucinated "Done!" replies.
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
     * Deterministic multi-step chain planner invoked ONLY when the user asks for an explicit
     * device action (or multi-step chain) and the LLM fails to emit any `<<<TOOL_CALL>>>` block
     * (for instance, on follow-up turns where the LLM tries to say "Done!" without calling the tool).
     */
    fun planFallbackChainIfUnverified(
        userPrompt: String,
        enabledToolIds: Set<String>
    ): List<ChatToolInvocation> {
        val raw = userPrompt.trim()
        if (raw.isBlank()) return emptyList()

        // Split multi-step prompts on " and then ", " then ", " and ", ";"
        val clauses = raw
            .split(Regex("\\s+(?:and\\s+then|then|and)\\s+|\\s*;\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val planned = mutableListOf<ChatToolInvocation>()
        var hasScreenshotStep = false

        for (clause in clauses) {
            val lower = clause.lowercase(Locale.US)

            // 1. Screenshot / Screen Capture
            if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds &&
                (lower.contains("screenshot") || lower.contains("screen shot") ||
                    lower.contains("capture screen") || lower.contains("capture my screen"))
            ) {
                val wantsAnalysis = lower.contains("analyze") || lower.contains("analyse") ||
                    lower.contains("what is on") || lower.contains("describe") || lower.contains("read")
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
                continue
            }

            // 2. Share step (especially chained after screenshot: "share it on WhatsApp")
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
                            put("text", JsonPrimitive("Shared from Mote"))
                        }
                    )
                )
                continue
            }

            // 3. Alarm creation / deletion / listing
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

            // 4. Countdown Timer
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

            // 5. Stopwatch
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

            // 6. Flashlight / Camera
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

            // 7. Recent Apps / Installed Apps / Open App (Chrome, YouTube, WhatsApp, etc.)
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
                        planned.add(
                            ChatToolInvocation(
                                tool = "openApp",
                                args = buildJsonObject { put("appName", JsonPrimitive(target)) }
                            )
                        )
                        continue
                    }
                }
            }

            // 8. Maps Navigation
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

        return planned
    }

    private fun injectIntermediateState(
        invocation: ChatToolInvocation,
        context: ToolChainContext
    ): Triple<ChatToolInvocation, String?, String?> {
        val op = invocation.tool.trim().lowercase(Locale.US)
        if (op in setOf("sharetoapp", "sharecontent", "share_screenshot", "sharescreenshot")) {
            val explicitUri = invocation.args["mediaUri"]?.jsonPrimitive?.contentOrNull
                ?: invocation.args["uri"]?.jsonPrimitive?.contentOrNull
            val wantsScreenshot = invocation.args["useLastScreenshot"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            if (explicitUri.isNullOrBlank() && wantsScreenshot) {
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
                }
                return Triple(
                    invocation.copy(args = merged),
                    "Used screenshot URI from Step 1",
                    null
                )
            }
        }
        return Triple(invocation, null, null)
    }

    private fun captureIntermediateState(result: GenUiToolResult, context: ToolChainContext) {
        context.stepOutputs[result.operation] = result.feedback
        when (val part = result.uiPart) {
            is InChatUiPart.ScreenCapturePanel -> {
                if (part.success) {
                    if (!part.captureId.isNullOrBlank()) context.lastCaptureId = part.captureId
                    if (!part.savedUriString.isNullOrBlank()) context.lastSavedUri = part.savedUriString
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
                    context.lastOpenedApp = part.title
                }
            }
            else -> Unit
        }
    }

    private fun formatOperationLabel(inv: ChatToolInvocation): String {
        val op = inv.tool.trim()
        val detail = inv.args["appName"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["label"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["title"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["destination"]?.jsonPrimitive?.contentOrNull
            ?: inv.args["query"]?.jsonPrimitive?.contentOrNull
        return if (!detail.isNullOrBlank()) "$op($detail)" else "$op()"
    }

    private fun resolveEngineLabel(tool: String): String {
        val op = tool.lowercase(Locale.US)
        return when {
            op.contains("alarm") -> "Android AlarmManager"
            op.contains("timer") -> "Live Countdown Engine"
            op.contains("stopwatch") -> "Live Stopwatch Engine"
            op.contains("event") || op.contains("calendar") -> "CalendarContract + Room"
            op.contains("screenshot") || op.contains("screen") -> "MediaProjection API"
            op.contains("share") -> "Android ACTION_SEND"
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

package com.mote.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.ChatGenUiToolProtocol
import com.mote.app.ai.ChatStreamEvent
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.LlmConfig
import com.mote.app.ai.ToolChainExecutionSnapshot
import com.mote.app.ai.ToolStepStatus
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.control.DeviceAutomationToolsManager
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelManager
import com.mote.app.security.configureAppCheck
import com.mote.app.service.MediaProjectionConsentBridge
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import com.mote.app.ui.theme.MoteTheme
import com.mote.app.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Transparent Foreground Overlay Sheet launched when any floating system bot orb is tapped
 * over a third-party Android app.
 *
 * Plugged directly into:
 * - Real-time Cloud Gemini API (Firebase AI Logic: Gemini 3.8 Flash, 3.5 Flash-Lite, 4 Argon)
 * - Downloaded On-Device LiteRT-LM phone models
 * - Live GenUiToolRegistry (AlarmManager, Countdown Timer, Stopwatch, Android Calendar Provider, GenUI widgets)
 */
class HybridInferenceActivity : ComponentActivity() {

    private val router by lazy { HybridAiRouter() }
    private lateinit var localModelManager: LocalModelManager
    private var pendingMediaProjectionCallback: ((Int, Intent?) -> Unit)? = null

    private val mediaProjectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingMediaProjectionCallback
        pendingMediaProjectionCallback = null
        cb?.invoke(result.resultCode, result.data)
    }

    private val mediaProjectionRequester: (Intent, (Int, Intent?) -> Unit) -> Unit = { intent, callback ->
        pendingMediaProjectionCallback = callback
        mediaProjectionConsentLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)

        ThemePreferenceStore.init(applicationContext)
        localModelManager = LocalModelManager(applicationContext)
        val executor = DeviceActionExecutor(this)

        val screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)
        val botName = intent.getStringExtra(EXTRA_BOT_NAME) ?: "Focus"
        val botColorHex = intent.getLongExtra(EXTRA_BOT_COLOR, 0xFF10B981L)
        val botSystemPrompt = intent.getStringExtra(EXTRA_BOT_SYSTEM_PROMPT)
            ?: "You are Mote, a real-time floating Android assistant with AlarmManager, Timer, Stopwatch, Calendar Provider, and Generative UI tools."
        val initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)
            ?: "Start a 5 minute focus timer"

        setContent {
            val isDarkTheme by ThemePreferenceStore.isDarkTheme.collectAsStateWithLifecycle()
            MoteTheme(darkTheme = isDarkTheme) {
                val tokens = LocalMoteColors.current
                val prefs by localModelManager.prefs.collectAsStateWithLifecycle()
                val localModels by localModelManager.models.collectAsStateWithLifecycle()
                val loadedLocalModel by localModelManager.loadedModel.collectAsStateWithLifecycle()
                val loadingLocalModelId by localModelManager.loadingModelId.collectAsStateWithLifecycle()
                val clockAlarms by localModelManager.clockTools.alarms.collectAsStateWithLifecycle()
                val clockTimers by localModelManager.clockTools.timers.collectAsStateWithLifecycle()
                val clockStopwatch by localModelManager.clockTools.stopwatch.collectAsStateWithLifecycle()
                val calendarEvents by localModelManager.calendarTools.events.collectAsStateWithLifecycle()
                val capturedScreenshots by localModelManager.deviceTools.capturedScreenshots.collectAsStateWithLifecycle()

                val llmConfig = remember(prefs) {
                    LlmConfig(
                        modelName = prefs.selectedCloudModelId,
                        temperature = prefs.temperature,
                        topP = prefs.topP,
                        maxOutputTokens = prefs.maxOutputTokens,
                        contextWindowTurns = prefs.contextWindowTurns
                    )
                }
                val preferLocal = prefs.preferLocalOfflineChat && loadedLocalModel?.supportsOnDeviceInference == true
                val activeCloudMeta = HybridAiRouter.supportedModels.firstOrNull { it.id == llmConfig.modelName }
                    ?: HybridAiRouter.supportedModels.first()
                val activeModelLabel = if (preferLocal) {
                    loadedLocalModel?.displayName ?: activeCloudMeta.displayName
                } else {
                    activeCloudMeta.displayName
                }

                var prompt by remember { mutableStateOf(initialPrompt) }
                var useLiveToolChat by remember { mutableStateOf(true) }
                var showModelSwitcher by remember { mutableStateOf(false) }

                var isStreaming by remember { mutableStateOf(false) }
                var streamedText by remember { mutableStateOf("") }
                var pendingToolHint by remember { mutableStateOf<String?>(null) }
                var liveParts by remember { mutableStateOf<List<InChatUiPart>>(emptyList()) }
                var controlCardResult by remember { mutableStateOf<HybridGenUiResult?>(null) }
                var cardLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var activeJob by remember { mutableStateOf<Job?>(null) }
                val scope = rememberCoroutineScope()

                fun stopActiveStream() {
                    activeJob?.cancel()
                    activeJob = null
                    localModelManager.cancelActiveLocalGeneration()
                    isStreaming = false
                    pendingToolHint = null
                }

                fun runLiveToolStream(inputPrompt: String, attachedBitmap: Bitmap? = null) {
                    val trimmed = inputPrompt.trim()
                    if (trimmed.isEmpty() && attachedBitmap == null) return
                    stopActiveStream()
                    errorMessage = null
                    controlCardResult = null
                    streamedText = ""
                    if (attachedBitmap == null) {
                        liveParts = emptyList()
                    }
                    pendingToolHint = if (attachedBitmap != null) "Analyzing screenshot visually..." else "Connecting..."
                    isStreaming = true

                    activeJob = scope.launch {
                        try {
                            val currentPrefs = localModelManager.prefs.value
                            val enabledToolIds = localModelManager.toolRegistry.enabledToolIds(currentPrefs)
                            val liveContext = buildString {
                                appendLine(localModelManager.clockTools.buildClockContextForAi())
                                appendLine(localModelManager.calendarTools.buildCalendarContextForAi())
                                appendLine(localModelManager.deviceTools.buildDeviceContextForAi())
                                if (!screenContext.isNullOrBlank()) {
                                    appendLine("REDACTED SCREEN CONTEXT: $screenContext")
                                }
                            }
                            val fullSystemPrompt = buildString {
                                appendLine(botSystemPrompt)
                                appendLine()
                                append(ChatGenUiToolProtocol.buildToolSystemInstructions(liveContext, enabledToolIds))
                            }

                            // Adaptive Battery/Thermal Route Resolution via SystemHealthManager
                            var activeLocal = localModelManager.loadedModel.value
                            val savedId = currentPrefs.activeLocalModelId
                            val hasSavedDownloaded = !savedId.isNullOrBlank() &&
                                localModelManager.models.value.any {
                                    it.id == savedId && it.status == LocalModelDownloadStatus.DOWNLOADED
                                }

                            val routeDecision = localModelManager.systemHealth.resolveInferenceRoute(
                                adaptiveEnabled = currentPrefs.adaptiveThermalBatteryRouting,
                                userPrefersLocal = currentPrefs.preferLocalOfflineChat,
                                hasLocalModelReady = (activeLocal?.supportsOnDeviceInference == true) || hasSavedDownloaded,
                                hasAttachedImage = attachedBitmap != null
                            )

                            var useLocal = routeDecision.useLocalEngine
                            if (useLocal && activeLocal == null && !savedId.isNullOrBlank()) {
                                pendingToolHint = "Adaptive Health: Loading on-device LiteRT-LM model..."
                                activeLocal = localModelManager.loadModel(savedId).getOrNull()
                                useLocal = activeLocal?.supportsOnDeviceInference == true
                            }

                            val streamFlow = if (useLocal && activeLocal != null && activeLocal.supportsOnDeviceInference) {
                                localModelManager.streamLoadedLocalModel(
                                    prompt = trimmed,
                                    systemPrompt = fullSystemPrompt,
                                    temperature = llmConfig.temperature,
                                    topP = llmConfig.topP
                                )
                            } else {
                                router.streamChatMessage(
                                    userPrompt = trimmed,
                                    botSystemPrompt = botSystemPrompt,
                                    history = emptyList(),
                                    config = llmConfig,
                                    attachedBitmap = attachedBitmap,
                                    clockContext = liveContext,
                                    enabledToolIds = enabledToolIds
                                )
                            }

                            var finalRawText = ""
                            val completedBlocks = mutableListOf<String>()
                            val existingParts = liveParts.toList()

                            streamFlow.collect { event ->
                                when (event) {
                                    is ChatStreamEvent.Delta -> {
                                        streamedText = event.accumulatedText
                                        pendingToolHint = event.pendingToolHint
                                            ?: if (routeDecision.switchedAutomatically) routeDecision.engineReason else null
                                    }
                                    is ChatStreamEvent.Completed -> {
                                        finalRawText = event.fullText
                                        completedBlocks.clear()
                                        completedBlocks.addAll(event.completedToolJsonBlocks)
                                    }
                                }
                            }

                            // Sequential Tool Chain Execution + Proof-of-Execution Verification
                            val decodedInvocations = completedBlocks.mapNotNull {
                                ChatGenUiToolProtocol.decodeToolInvocation(it)
                            }
                            val plannedInvocations = if (decodedInvocations.isEmpty() && attachedBitmap == null) {
                                localModelManager.toolOrchestrator.planFallbackChainIfUnverified(
                                    userPrompt = trimmed,
                                    enabledToolIds = enabledToolIds
                                )
                            } else {
                                decodedInvocations
                            }

                            var chainSnapshot: ToolChainExecutionSnapshot? = null
                            val newParts = mutableListOf<InChatUiPart>()

                            if (plannedInvocations.isNotEmpty()) {
                                val runResult = localModelManager.toolOrchestrator.executeChain(
                                    rawInvocations = plannedInvocations,
                                    chainTitle = if (plannedInvocations.size > 1) "Sequential Tool Chain" else "Overlay Action Verification",
                                    onProgress = { progressSnap ->
                                        val activeStep = progressSnap.steps.firstOrNull { it.status == ToolStepStatus.RUNNING }
                                        pendingToolHint = if (activeStep != null) {
                                            "Step ${activeStep.stepIndex}/${progressSnap.steps.size}: ${activeStep.operationLabel}..."
                                        } else if (progressSnap.allVerified) {
                                            "Execution Verified (${progressSnap.verifiedCount}/${progressSnap.steps.size})"
                                        } else {
                                            "Verifying tool execution..."
                                        }
                                        liveParts = existingParts + listOf(
                                            InChatUiPart.ToolChainStatusPanel(
                                                partId = "overlay_chain_${progressSnap.chainId}",
                                                snapshot = progressSnap
                                            )
                                        )
                                    }
                                )
                                chainSnapshot = runResult.snapshot
                                newParts.addAll(runResult.uiParts)
                                liveParts = existingParts + newParts
                            }

                            val baseAnswer = finalRawText.ifBlank {
                                if (chainSnapshot?.allVerified == true) {
                                    "Execution Verified (${chainSnapshot.verifiedCount}/${chainSnapshot.steps.size})."
                                } else if (newParts.isNotEmpty()) {
                                    "Action completed below."
                                } else {
                                    ""
                                }
                            }
                            streamedText = localModelManager.toolOrchestrator.verifyAssistantClaim(
                                assistantText = baseAnswer,
                                snapshot = chainSnapshot
                            )
                            pendingToolHint = null
                            isStreaming = false
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage ?: "Failed to stream AI response."
                            pendingToolHint = null
                            isStreaming = false
                        }
                    }
                }

                fun runControlCard(inputPrompt: String) {
                    val trimmed = inputPrompt.trim()
                    if (trimmed.isEmpty()) return
                    stopActiveStream()
                    errorMessage = null
                    cardLoading = true
                    scope.launch {
                        router.generateControlCard(
                            userPrompt = trimmed,
                            botSystemPrompt = botSystemPrompt,
                            redactedScreenContext = screenContext,
                            isForeground = true,
                            modelName = llmConfig.modelName
                        ).onSuccess { res ->
                            controlCardResult = res
                        }.onFailure { err ->
                            errorMessage = err.localizedMessage ?: "Failed to generate control card."
                        }
                        cardLoading = false
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x730B0D1D))
                        .clickable { finish() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                            .background(IosCanvas)
                            .clickable(enabled = false) {}
                            .navigationBarsPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Top Header: Active Bot + Real-Time Cloud/Local Model Switcher
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(botColorHex).copy(alpha = 0.16f))
                                        .border(1.5.dp, Color(botColorHex), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = Color(botColorHex),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$botName Overlay Bot",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MoteNavy,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(tokens.surface)
                                            .border(1.dp, tokens.border, RoundedCornerShape(999.dp))
                                            .clickable { showModelSwitcher = true }
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                            .testTag("overlay_model_switcher_pill"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (preferLocal) LocalNanoEmerald else CloudFlashCobalt)
                                        )
                                        Text(
                                            text = if (preferLocal) "Local · $activeModelLabel" else "Cloud · $activeModelLabel",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MoteNavy,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Outlined.SwapHoriz,
                                            contentDescription = "Switch Model",
                                            tint = CloudFlashCobalt,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { finish() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close overlay", tint = SlateMuted)
                            }
                        }

                        // Mode Toggle + Quick Real-Time Tool Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = useLiveToolChat,
                                onClick = { useLiveToolChat = true },
                                label = { Text("Live AI + Tools", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = !useLiveToolChat,
                                onClick = { useLiveToolChat = false },
                                label = { Text("Control Card", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "Capture & analyze screen",
                                "Open YouTube",
                                "Open Chrome",
                                "Show recent apps",
                                "Start 5m Focus Timer",
                                "Set 7:30 AM Alarm"
                            ).forEach { chip ->
                                AssistChip(
                                    onClick = {
                                        prompt = chip
                                        useLiveToolChat = true
                                        runLiveToolStream(chip)
                                    },
                                    label = { Text(chip, fontSize = 11.sp, color = MoteNavy) }
                                )
                            }
                        }

                        // Input Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hybrid_prompt_input"),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                placeholder = { Text("Ask Mote or trigger Alarm/Timer/Calendar...", fontSize = 12.5.sp) }
                            )
                            if (isStreaming) {
                                FilledIconButton(
                                    onClick = { stopActiveStream() },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFDC2626)),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = "Stop", tint = Color.White)
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        if (useLiveToolChat) runLiveToolStream(prompt) else runControlCard(prompt)
                                    },
                                    enabled = prompt.isNotBlank() && !cardLoading,
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = tokens.primaryActionBg),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("generate_control_card_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                                }
                            }
                        }

                        // Scrollable Live Output & Inline Tool Widgets
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isStreaming || streamedText.isNotBlank()) {
                                MoteCard(background = Color.White) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pendingToolHint ?: if (isStreaming) "Streaming ($activeModelLabel)..." else "$botName · $activeModelLabel",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CloudFlashCobalt
                                        )
                                        if (isStreaming) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CloudFlashCobalt)
                                        }
                                    }
                                    if (streamedText.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = streamedText,
                                            fontSize = 13.sp,
                                            color = MoteNavy,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }

                            liveParts.forEach { part ->
                                InChatUiPartRenderer(
                                    part = part,
                                    alarms = clockAlarms,
                                    timers = clockTimers,
                                    stopwatch = clockStopwatch,
                                    calendarEvents = calendarEvents,
                                    capturedScreenshots = capturedScreenshots,
                                    onToggleAlarm = { id, enabled ->
                                        scope.launch { localModelManager.clockTools.toggleAlarmById(id, enabled) }
                                    },
                                    onShiftAlarmMinutes = { id, delta ->
                                        scope.launch { localModelManager.clockTools.shiftAlarmMinutes(id, delta) }
                                    },
                                    onDeleteAlarm = { id ->
                                        scope.launch { localModelManager.clockTools.deleteAlarmById(id) }
                                    },
                                    onControlTimer = { action, timerId ->
                                        localModelManager.clockTools.controlTimer(action, timerId)
                                    },
                                    onAddTimerSeconds = { timerId, extra ->
                                        localModelManager.clockTools.addSecondsToTimer(timerId, extra)
                                    },
                                    onControlStopwatch = { action ->
                                        localModelManager.clockTools.controlStopwatch(action)
                                    },
                                    onDeleteCalendarEvent = { id ->
                                        scope.launch { localModelManager.calendarTools.deleteEventById(id) }
                                    },
                                    onExecuteGenUiAction = { action -> executor.execute(action) },
                                    onAnalyzeScreenshot = { capId, analysisPrompt ->
                                        val bmp = localModelManager.deviceTools.capturedScreenshots.value[capId]
                                        if (bmp != null && !bmp.isRecycled) {
                                            runLiveToolStream(analysisPrompt, attachedBitmap = bmp)
                                        }
                                    }
                                )
                            }

                            if (cardLoading) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CloudFlashCobalt)
                                    Text("Generating interactive control card...", fontSize = 12.sp, color = CloudFlashCobalt)
                                }
                            }

                            errorMessage?.let { msg ->
                                MoteCard(background = Color(0xFFFEF2F2)) {
                                    Text(text = msg, color = Color(0xFFB91C1C), fontSize = 12.5.sp)
                                }
                            }

                            controlCardResult?.let { res ->
                                GenUiCardRenderer(result = res) { action ->
                                    executor.execute(action)
                                }
                            }
                        }
                    }
                }

                if (showModelSwitcher) {
                    ChatModelSwitcherBottomSheet(
                        llmConfig = llmConfig,
                        localModels = localModels,
                        loadedLocalModel = loadedLocalModel,
                        loadingLocalModelId = loadingLocalModelId,
                        preferLoadedLocalChat = preferLocal,
                        onSelectCloudModel = { modelId ->
                            scope.launch {
                                localModelManager.updatePrefs {
                                    it.copy(selectedCloudModelId = modelId, preferLocalOfflineChat = false)
                                }
                            }
                        },
                        onSelectLocalModel = { modelId ->
                            scope.launch {
                                localModelManager.loadModel(modelId)
                                localModelManager.updatePrefs {
                                    it.copy(activeLocalModelId = modelId, preferLocalOfflineChat = true)
                                }
                            }
                        },
                        onOpenModelHub = {
                            showModelSwitcher = false
                            finish()
                        },
                        onDismiss = { showModelSwitcher = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DeviceAutomationToolsManager.attachForegroundActivity(this)
        MediaProjectionConsentBridge.attachConsentLauncher(mediaProjectionRequester)
    }

    override fun onPause() {
        DeviceAutomationToolsManager.detachForegroundActivity(this)
        MediaProjectionConsentBridge.detachConsentLauncher(mediaProjectionRequester)
        super.onPause()
    }

    override fun onDestroy() {
        if (::localModelManager.isInitialized) {
            localModelManager.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCREEN_CONTEXT = "extra_screen_context"
        const val EXTRA_BOT_ID = "extra_bot_id"
        const val EXTRA_BOT_NAME = "extra_bot_name"
        const val EXTRA_BOT_COLOR = "extra_bot_color"
        const val EXTRA_BOT_SYSTEM_PROMPT = "extra_bot_system_prompt"
        const val EXTRA_INITIAL_PROMPT = "extra_initial_prompt"
    }
}

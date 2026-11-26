package com.mote.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.InferenceMetrics
import com.mote.app.control.ClockToolsManager
import com.mote.app.control.CountdownTimerItem
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.control.StopwatchUiState
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.service.SystemOverlayStateBus
import com.mote.app.ui.AiCardUiState
import com.mote.app.ui.BotsUiState
import com.mote.app.ui.ChatHistoryUiState
import com.mote.app.ui.ChatStreamUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class StarterPrompt(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val useGenUi: Boolean = false
)

private val starterPrompts = listOf(
    StarterPrompt(
        title = "Screenshot → OCR → Notes",
        subtitle = "3-step atomic chain: MediaProjection -> Vision OCR -> Save to Room Notes",
        prompt = "Take a screenshot -> OCR analysis -> Save to Notes"
    ),
    StarterPrompt(
        title = "Open Chrome & Search",
        subtitle = "Launches Chrome search with live animated Siri system overlay",
        prompt = "Open Chrome and search latest Android AI news"
    ),
    StarterPrompt(
        title = "Phone + Live STT/TTS Relay",
        subtitle = "Opens Phone dialer then runs real-time Speech-to-Text <-> AI <-> TTS",
        prompt = "Open phone and call 555-0199 then listen and reply with real-time text to speech"
    ),
    StarterPrompt(
        title = "Set 7:30 AM Alarm",
        subtitle = "Real-time AI alarm creation with background AlarmManager verification",
        prompt = "Create an alarm for 7:30 AM on weekdays labeled Morning Standup"
    ),
    StarterPrompt(
        title = "5m Focus Timer",
        subtitle = "Live ticking countdown widget inside chat",
        prompt = "Start a 5 minute countdown timer labeled Deep Focus"
    ),
    StarterPrompt(
        title = "Schedule Event",
        subtitle = "Create a real-time calendar event & sync to Android",
        prompt = "Add a calendar event for tomorrow from 2:00 PM to 3:00 PM titled Product Sync in Room 4B"
    ),
    StarterPrompt(
        title = "Screenshot & Share",
        subtitle = "Multi-step chain: MediaProjection capture -> WhatsApp share",
        prompt = "Take a screenshot and share it on WhatsApp"
    ),
    StarterPrompt(
        title = "Weekly Budget Chart",
        subtitle = "Stream an interactive Generative UI chart in chat",
        prompt = "Render an interactive bar chart and metric summary for a $1,200 weekly budget split across Housing $550, Food $280, Transit $140, and Savings $230."
    )
)

@Composable
fun ChatHubScreen(
    viewModel: MoteViewModel,
    isGuest: Boolean,
    isImeVisible: Boolean,
    floatingAssistantVisible: Boolean,
    onToggleFloatingAssistant: () -> Unit,
    onOpenModelsTab: () -> Unit,
    onOpenToolsTab: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val botsState by viewModel.botsState.collectAsStateWithLifecycle()
    val chatHistoryState by viewModel.chatHistoryState.collectAsStateWithLifecycle()
    val chatStreamState by viewModel.chatStreamState.collectAsStateWithLifecycle()
    val aiCardState by viewModel.aiState.collectAsStateWithLifecycle()
    val llmConfig by viewModel.llmConfig.collectAsStateWithLifecycle()
    val selectedBotId by viewModel.selectedBotId.collectAsStateWithLifecycle()
    val lastMetrics by viewModel.lastMetrics.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val loadedLocalModel by viewModel.loadedLocalModel.collectAsStateWithLifecycle()
    val loadingLocalModelId by viewModel.loadingLocalModelId.collectAsStateWithLifecycle()
    val localModels by viewModel.localModels.collectAsStateWithLifecycle()
    val preferLoadedLocalChat by viewModel.preferLoadedLocalChat.collectAsStateWithLifecycle()
    val clockAlarms by viewModel.clockAlarms.collectAsStateWithLifecycle()
    val clockTimers by viewModel.clockTimers.collectAsStateWithLifecycle()
    val clockStopwatch by viewModel.clockStopwatch.collectAsStateWithLifecycle()
    val calendarEvents by viewModel.calendarEvents.collectAsStateWithLifecycle()
    val savedNotes by viewModel.savedNotes.collectAsStateWithLifecycle()
    val voiceRelayState by viewModel.voiceRelayState.collectAsStateWithLifecycle()
    val systemOverlayState by viewModel.systemOverlayState.collectAsStateWithLifecycle()
    val activeToolChain by viewModel.activeToolChain.collectAsStateWithLifecycle()
    val toolChainHistory by viewModel.toolChainHistory.collectAsStateWithLifecycle()
    val capturedScreenshots by viewModel.capturedScreenshots.collectAsStateWithLifecycle()
    val inChatUiPartsByText by viewModel.inChatUiPartsByText.collectAsStateWithLifecycle()

    val executor = remember(context) { DeviceActionExecutor(context) }
    val bots = (botsState as? BotsUiState.Success)?.bots.orEmpty()
    val selectedBot = bots.firstOrNull { it.id == selectedBotId }
        ?: bots.firstOrNull { it.active }
        ?: bots.firstOrNull()

    val messages = (chatHistoryState as? ChatHistoryUiState.Success)?.messages.orEmpty()
    val isStreaming = chatStreamState is ChatStreamUiState.Streaming
    val isCardLoading = aiCardState is AiCardUiState.Loading
    val listState = rememberLazyListState()

    var composerText by remember { mutableStateOf("") }
    var genUiMode by remember { mutableStateOf(false) }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showModelSwitcher by remember { mutableStateOf(false) }
    var showVoiceOverlay by remember { mutableStateOf(false) }
    var speechState by remember { mutableStateOf(SpeechRecognitionState()) }

    val speechController = remember(context) {
        GoogleSpeechInputController(
            context = context,
            onPartialText = { /* streamed directly inside speechState.partialText */ },
            onFinalText = { finalText ->
                showVoiceOverlay = false
                if (finalText.isNotBlank()) {
                    viewModel.sendStreamingChatMessage(
                        prompt = finalText,
                        bot = selectedBot
                    )
                }
            },
            onStateChanged = { next ->
                speechState = next
            }
        )
    }

    DisposableEffect(speechController) {
        onDispose { speechController.release() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showVoiceOverlay = true
            speechController.startListening()
        } else {
            speechState = SpeechRecognitionState(errorText = "Microphone permission is required for voice commands.")
            Toast.makeText(context, "Microphone permission is needed for voice commands", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchVoiceAssistant() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showVoiceOverlay = true
            if (!speechState.isListening) {
                speechController.startListening()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val decoded = decodeSampledBitmap(context, uri, maxDimension = 1024)
                if (decoded != null) {
                    attachedBitmap = decoded
                } else {
                    Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Smooth auto-scroll whenever messages grow, streaming chunks arrive, or live tool widgets appear
    val streamTextLen = (chatStreamState as? ChatStreamUiState.Streaming)?.partialText?.length ?: 0
    val livePartsCount = (chatStreamState as? ChatStreamUiState.Streaming)?.liveParts?.size ?: 0
    LaunchedEffect(messages.size, streamTextLen, livePartsCount, aiCardState is AiCardUiState.Success) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    val activeModelMeta = HybridAiRouter.supportedModels.firstOrNull { it.id == llmConfig.modelName }
        ?: HybridAiRouter.supportedModels.first()
    val activeLocalChat = loadedLocalModel?.takeIf { preferLoadedLocalChat && it.supportsOnDeviceInference }
    val activeHeaderModelName = activeLocalChat?.displayName ?: activeModelMeta.displayName

    fun handleSend() {
        val prompt = composerText.trim()
        if (prompt.isEmpty() && attachedBitmap == null) return
        val imageToSend = attachedBitmap
        composerText = ""
        attachedBitmap = null
        if (genUiMode && imageToSend == null) {
            viewModel.generateFocusControlCard(
                userPrompt = prompt,
                botSystemPrompt = selectedBot?.systemPrompt ?: "Return compact Android controls.",
                botId = selectedBot?.id ?: "focus",
                botName = selectedBot?.name ?: "Focus"
            )
        } else {
            viewModel.sendStreamingChatMessage(
                prompt = prompt,
                bot = selectedBot,
                attachedBitmap = imageToSend
            )
        }
    }

    val tokens = LocalMoteColors.current
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
            .imePadding()
    ) {
        // LLM Client Top Bar (Model selector pill + Sync badge + Floating Orb + Clear)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas.copy(alpha = 0.95f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.surface)
                    .border(1.dp, tokens.border, RoundedCornerShape(999.dp))
                    .clickable { showModelSwitcher = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("chat_model_selector_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeLocalChat != null) {
                    LobeModelAvatar(brand = activeLocalChat.brand, size = 30.dp, circular = true)
                } else {
                    LobeModelAvatar(modelId = activeModelMeta.id, size = 30.dp, circular = true)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeHeaderModelName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (activeLocalChat != null) {
                            "Offline · LiteRT-LM ${activeLocalChat.backend.label} · ${selectedBot?.name ?: "Mote"}"
                        } else {
                            "${selectedBot?.name ?: "Mote"} · ${if (isGuest) "Local Guest" else "Firestore Sync"}"
                        },
                        fontSize = 10.5.sp,
                        color = if (activeLocalChat != null) LocalNanoEmerald else SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = "Switch model",
                    tint = if (activeLocalChat != null) LocalNanoEmerald else CloudFlashCobalt,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        val started = SystemOverlayStateBus.ensureSystemOverlayRunning(context)
                        Toast.makeText(
                            context,
                            if (started) "System Siri Overlay active over all apps" else "Could not start system overlay",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Grant 'Display over other apps' so Mote floats over Chrome & all apps",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_system_overlay_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Layers,
                    contentDescription = if (systemOverlayState.isServiceRunning) {
                        "System overlay active over other apps"
                    } else {
                        "Launch system-wide Siri overlay over other apps"
                    },
                    tint = if (systemOverlayState.isServiceRunning) LocalNanoEmerald else CloudFlashCobalt,
                    modifier = Modifier.size(21.dp)
                )
            }

            IconButton(
                onClick = onOpenToolsTab,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_open_tools_permissions_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = "Manage model tools and permissions",
                    tint = CloudFlashCobalt,
                    modifier = Modifier.size(21.dp)
                )
            }

            IconButton(
                onClick = onToggleFloatingAssistant,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_floating_assistant_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.BubbleChart,
                    contentDescription = if (floatingAssistantVisible) "Hide floating orb" else "Show floating orb",
                    tint = if (floatingAssistantVisible) LocalNanoEmerald else SlateMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (messages.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.clearChatHistory() },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "New chat",
                        tint = SlateMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Persona quick-switcher row (inspired by multi-persona LLM clients)
        if (bots.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bots.forEach { bot ->
                    val isSelected = bot.id == selectedBot?.id
                    val accent = Color(bot.colorHex)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isSelected) tokens.primaryActionBg else tokens.surface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) tokens.primaryActionBg else tokens.border,
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { viewModel.selectBot(bot.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("chat_persona_chip_${bot.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) LocalNanoEmerald else accent)
                        )
                        Text(
                            text = bot.name,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else MoteNavy
                        )
                    }
                }
            }
        }

        // Live Clock & Calendar Status Pill Bar whenever alarms, timers, stopwatch, or events are active
        val activeTimers = clockTimers.filter { it.isRunning || (!it.isFinished && it.remainingSeconds < it.totalSeconds) }
        val activeAlarmCount = clockAlarms.count { it.enabled }
        val showLiveClockBar = activeTimers.isNotEmpty() || clockStopwatch.isRunning || clockStopwatch.elapsedMs > 0L || activeAlarmCount > 0 || calendarEvents.isNotEmpty()
        if (showLiveClockBar) {
            LiveClockStatusPillBar(
                activeAlarmCount = activeAlarmCount,
                activeCalendarCount = calendarEvents.size,
                timers = activeTimers,
                stopwatch = clockStopwatch,
                onToggleTimer = { timer ->
                    viewModel.controlClockTimer(if (timer.isRunning) "pause" else "resume", timer.id)
                },
                onToggleStopwatch = {
                    viewModel.controlClockStopwatch(if (clockStopwatch.isRunning) "pause" else "start")
                },
                onAskShowAlarms = {
                    viewModel.sendStreamingChatMessage("Show all my current alarms", selectedBot)
                },
                onAskShowCalendar = {
                    viewModel.sendStreamingChatMessage("Show all my upcoming calendar events", selectedBot)
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        // Main Chat Message Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actionError?.let { msg ->
                item(key = "action_error") {
                    MoteCard(background = Color(0xFFFEF2F2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, fontSize = 12.sp, color = Color(0xFFB91C1C), modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearActionError() }) {
                                Text("Dismiss", color = MoteNavy, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (chatHistoryState is ChatHistoryUiState.Loading) {
                item(key = "history_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MoteNavy, strokeWidth = 2.5.dp)
                    }
                }
            } else if (messages.isEmpty() && !isStreaming && aiCardState !is AiCardUiState.Success) {
                item(key = "empty_welcome") {
                    ChatWelcomeHero(
                        modelName = activeHeaderModelName,
                        personaName = selectedBot?.name ?: "Mote",
                        onStarterClick = { starter ->
                            if (starter.useGenUi) {
                                viewModel.generateFocusControlCard(
                                    userPrompt = starter.prompt,
                                    botSystemPrompt = selectedBot?.systemPrompt
                                        ?: "Return compact Android Focus controls.",
                                    botId = selectedBot?.id ?: "focus",
                                    botName = selectedBot?.name ?: "Focus"
                                )
                            } else {
                                viewModel.sendStreamingChatMessage(
                                    prompt = starter.prompt,
                                    bot = selectedBot
                                )
                            }
                        }
                    )
                }
            }

            if (toolChainHistory.isNotEmpty() || activeToolChain != null) {
                item(key = "chat_tool_execution_log_panel") {
                    ToolExecutionLogHistoryPanel(
                        chains = toolChainHistory,
                        activeChain = activeToolChain,
                        onClearLog = { viewModel.clearToolChainHistory() }
                    )
                }
            }

            items(messages, key = { it.id }) { msg ->
                val isAssistant = msg.chatRole() != ChatRole.USER
                val attachedParts = if (isAssistant) inChatUiPartsByText[msg.text].orEmpty() else emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatMessageBubble(
                        message = msg,
                        onCopy = { text -> copyToClipboard(context, text) },
                        onReplay = { prompt -> composerText = prompt }
                    )
                    attachedParts.forEach { part ->
                        InChatUiPartRenderer(
                            part = part,
                            alarms = clockAlarms,
                            timers = clockTimers,
                            stopwatch = clockStopwatch,
                            calendarEvents = calendarEvents,
                            capturedScreenshots = capturedScreenshots,
                            savedNotes = savedNotes,
                            voiceRelayState = voiceRelayState,
                            onToggleAlarm = { id, enabled -> viewModel.toggleClockAlarm(id, enabled) },
                            onShiftAlarmMinutes = { id, delta -> viewModel.shiftClockAlarmMinutes(id, delta) },
                            onDeleteAlarm = { id -> viewModel.deleteClockAlarm(id) },
                            onControlTimer = { action, timerId -> viewModel.controlClockTimer(action, timerId) },
                            onAddTimerSeconds = { timerId, extra -> viewModel.addSecondsToClockTimer(timerId, extra) },
                            onControlStopwatch = { action -> viewModel.controlClockStopwatch(action) },
                            onDeleteCalendarEvent = { id -> viewModel.deleteCalendarEvent(id) },
                            onExecuteGenUiAction = { action -> executor.execute(action) },
                            onAnalyzeScreenshot = { capId, prompt ->
                                viewModel.analyzeCapturedScreenshot(capId, prompt, selectedBot)
                            },
                            onDeleteNote = { id -> viewModel.deleteSavedNote(id) },
                            onSaveOcrToNotes = { title, text -> viewModel.saveOcrTextAsNote(title, text) },
                            onToggleVoiceRelayPause = { viewModel.toggleVoiceRelayPause() },
                            onStopVoiceRelay = { viewModel.stopVoiceRelay() },
                            onSubmitVoiceRelayText = { heard -> viewModel.submitVoiceRelayUtterance(heard) }
                        )
                    }
                }
            }

            // Live streaming response bubble + live mid-stream Clock/Calendar/GenUI widgets
            if (chatStreamState is ChatStreamUiState.Streaming) {
                val streaming = chatStreamState as ChatStreamUiState.Streaming
                item(key = "live_streaming_bubble") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamingAssistantBubble(
                            botName = streaming.botName,
                            partialText = streaming.partialText,
                            elapsedMs = streaming.elapsedMs,
                            pendingToolHint = streaming.pendingToolHint,
                            onStop = { viewModel.stopStreaming() }
                        )
                        streaming.liveParts.forEach { part ->
                            InChatUiPartRenderer(
                                part = part,
                                alarms = clockAlarms,
                                timers = clockTimers,
                                stopwatch = clockStopwatch,
                                calendarEvents = calendarEvents,
                                capturedScreenshots = capturedScreenshots,
                                savedNotes = savedNotes,
                                voiceRelayState = voiceRelayState,
                                onToggleAlarm = { id, enabled -> viewModel.toggleClockAlarm(id, enabled) },
                                onShiftAlarmMinutes = { id, delta -> viewModel.shiftClockAlarmMinutes(id, delta) },
                                onDeleteAlarm = { id -> viewModel.deleteClockAlarm(id) },
                                onControlTimer = { action, timerId -> viewModel.controlClockTimer(action, timerId) },
                                onAddTimerSeconds = { timerId, extra -> viewModel.addSecondsToClockTimer(timerId, extra) },
                                onControlStopwatch = { action -> viewModel.controlClockStopwatch(action) },
                                onDeleteCalendarEvent = { id -> viewModel.deleteCalendarEvent(id) },
                                onExecuteGenUiAction = { action -> executor.execute(action) },
                                onAnalyzeScreenshot = { capId, prompt ->
                                    viewModel.analyzeCapturedScreenshot(capId, prompt, selectedBot)
                                },
                                onDeleteNote = { id -> viewModel.deleteSavedNote(id) },
                                onSaveOcrToNotes = { title, text -> viewModel.saveOcrTextAsNote(title, text) },
                                onToggleVoiceRelayPause = { viewModel.toggleVoiceRelayPause() },
                                onStopVoiceRelay = { viewModel.stopVoiceRelay() },
                                onSubmitVoiceRelayText = { heard -> viewModel.submitVoiceRelayUtterance(heard) }
                            )
                        }
                    }
                }
            }

            // Stream error card with 1-tap Retry & Switch to Gemini 3.5 Flash-Lite recovery
            if (chatStreamState is ChatStreamUiState.Error) {
                val err = chatStreamState as ChatStreamUiState.Error
                item(key = "stream_error_card") {
                    MoteCard(background = tokens.errorBg) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Response Error",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = tokens.errorTitle
                                )
                                TextButton(
                                    onClick = { viewModel.clearChatStreamError() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Dismiss", color = MoteNavy, fontSize = 12.sp)
                                }
                            }
                            Text(err.message, fontSize = 12.sp, color = tokens.errorBody, lineHeight = 17.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.retryLastChatMessage(switchToLiteModel = false) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryActionBg),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("chat_error_retry_button")
                                ) {
                                    Text("Retry Now", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.retryLastChatMessage(switchToLiteModel = true) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("chat_error_switch_lite_button")
                                ) {
                                    Text("Use Flash-Lite", color = MoteNavy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // Inline Generative UI Control Card (when generated)
            when (val cardState = aiCardState) {
                is AiCardUiState.Loading -> {
                    item(key = "genui_loading") {
                        MoteCard(background = Color.White) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = CloudFlashCobalt,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Building interactive Android controls...",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                            }
                        }
                    }
                }
                is AiCardUiState.Success -> {
                    item(key = "genui_card_result") {
                        GenUiCardRenderer(result = cardState.result) { action ->
                            executor.execute(action)
                        }
                    }
                }
                is AiCardUiState.Error -> {
                    item(key = "genui_error") {
                        MoteCard(background = tokens.errorBg) {
                            Text("Control Card Error", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = tokens.errorTitle)
                            Spacer(Modifier.height(4.dp))
                            Text(cardState.message, fontSize = 12.sp, color = tokens.errorBody)
                        }
                    }
                }
                else -> Unit
            }

            // Latest inference telemetry strip (tokens/sec & TTFT like local-llms-on-android)
            lastMetrics?.let { metrics ->
                if (!isStreaming) {
                    item(key = "inference_metrics_strip") {
                        InferenceMetricsChip(metrics = metrics)
                    }
                }
            }
        }

        // Composer dock (sits cleanly above the floating bottom nav when keyboard is closed)
        val bottomDockClearance = if (isImeVisible) 8.dp else 82.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas)
                .then(if (!isImeVisible) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomDockClearance),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Optional attached image thumbnail preview
            AnimatedVisibility(
                visible = attachedBitmap != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                attachedBitmap?.let { bmp ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.surface)
                            .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Attached image preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Text(
                            text = "Image ready for vision",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MoteNavy,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { attachedBitmap = null },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove image",
                                tint = SlateMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Sleek pill composer bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x1F000000))
                    .clip(RoundedCornerShape(26.dp))
                    .background(tokens.surface)
                    .border(1.dp, tokens.border, RoundedCornerShape(26.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Multimodal Photo Picker button (zero storage permissions!)
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chat_attach_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = "Attach image",
                        tint = if (attachedBitmap != null) LocalNanoEmerald else SlateMuted,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Toggle between Conversational Chat vs Generative UI Control Card mode
                IconButton(
                    onClick = { genUiMode = !genUiMode },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chat_genui_mode_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = if (genUiMode) "GenUI Card mode active" else "Switch to GenUI Card mode",
                        tint = if (genUiMode) CloudFlashCobalt else SlateMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    if (composerText.isEmpty()) {
                        Text(
                            text = if (genUiMode) "Ask for Android controls..." else "Message ${selectedBot?.name ?: "Mote"}...",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        textStyle = TextStyle(
                            color = MoteNavy,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(CloudFlashCobalt),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { handleSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_composer_input")
                    )
                }

                // Siri/Alexa Animated Voice Command Overlay trigger
                IconButton(
                    onClick = { launchVoiceAssistant() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))
                            )
                        )
                        .testTag("chat_voice_overlay_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = "Launch animated voice command overlay",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isStreaming) {
                    FilledIconButton(
                        onClick = { viewModel.stopStreaming() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop generating",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    val canSend = (composerText.isNotBlank() || attachedBitmap != null) && !isCardLoading
                    FilledIconButton(
                        onClick = { handleSend() },
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (genUiMode) CloudFlashCobalt else tokens.primaryActionBg,
                            contentColor = Color.White,
                            disabledContainerColor = tokens.divider,
                            disabledContentColor = SlateMuted
                        ),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send message",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    VoiceAssistantOverlay(
        visible = showVoiceOverlay,
        speechState = speechState,
        activeBotName = selectedBot?.name ?: "Mote",
        activeRouteLabel = activeHeaderModelName,
        onToggleListening = {
            if (speechState.isListening) {
                speechController.stopListening()
            } else {
                launchVoiceAssistant()
            }
        },
        onQuickVoiceCommand = { cmd ->
            speechController.stopListening()
            showVoiceOverlay = false
            viewModel.sendStreamingChatMessage(
                prompt = cmd,
                bot = selectedBot
            )
        },
        onDismiss = {
            speechController.stopListening()
            showVoiceOverlay = false
        }
    )
    }

    if (showModelSwitcher) {
        ChatModelSwitcherBottomSheet(
            llmConfig = llmConfig,
            localModels = localModels,
            loadedLocalModel = loadedLocalModel,
            loadingLocalModelId = loadingLocalModelId,
            preferLoadedLocalChat = preferLoadedLocalChat,
            onSelectCloudModel = { modelId ->
                viewModel.updateModel(modelId)
            },
            onSelectLocalModel = { modelId ->
                viewModel.selectDownloadedModelForChat(modelId)
            },
            onOpenModelHub = onOpenModelsTab,
            onDismiss = { showModelSwitcher = false }
        )
    }
}

@Composable
private fun ChatWelcomeHero(
    modelName: String,
    personaName: String,
    onStarterClick: (StarterPrompt) -> Unit
) {
    val tokens = LocalMoteColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(MoteBrandNavy, Color(0xFF172042))
                    )
                )
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = LocalNanoEmerald,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "$modelName · $personaName",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "What would you like to build or automate?",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Stream multimodal answers, attach photos for vision Q&A, or switch to Controls mode for instant Android widgets.",
                fontSize = 12.5.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 18.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starterPrompts.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { starter ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tokens.surface)
                                .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
                                .clickable { onStarterClick(starter) }
                                .padding(12.dp)
                        ) {
                            Text(
                                text = starter.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (starter.useGenUi) tokens.accentBlue else MoteNavy
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = starter.subtitle,
                                fontSize = 11.sp,
                                color = SlateMuted,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onReplay: (String) -> Unit
) {
    val tokens = LocalMoteColors.current
    val isUser = message.chatRole() == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 18.dp
                    )
                )
                .background(if (isUser) tokens.primaryActionBg else tokens.surface)
                .border(
                    width = if (isUser) 0.dp else 1.dp,
                    color = if (isUser) Color.Transparent else tokens.border,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 18.dp
                    )
                )
                .clickable(enabled = isUser) { onReplay(message.text) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("chat_bubble_${message.id}")
        ) {
            if (!isUser) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.botName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentBlue
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy response",
                        tint = SlateMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onCopy(message.text) }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = message.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isUser) Color.White else MoteNavy
            )
        }
    }
}

@Composable
private fun LiveClockStatusPillBar(
    activeAlarmCount: Int,
    activeCalendarCount: Int,
    timers: List<CountdownTimerItem>,
    stopwatch: StopwatchUiState,
    onToggleTimer: (CountdownTimerItem) -> Unit,
    onToggleStopwatch: () -> Unit,
    onAskShowAlarms: () -> Unit,
    onAskShowCalendar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("chat_live_clock_status_bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (activeAlarmCount > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFEFF6FF))
                    .border(1.dp, CloudFlashCobalt.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onAskShowAlarms)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Alarm, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(14.dp))
                Text(
                    text = "$activeAlarmCount Alarm${if (activeAlarmCount == 1) "" else "s"} On",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CloudFlashCobalt
                )
            }
        }

        if (activeCalendarCount > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFECFDF5))
                    .border(1.dp, LocalNanoEmerald.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onAskShowCalendar)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("live_calendar_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Event, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(14.dp))
                Text(
                    text = "$activeCalendarCount Event${if (activeCalendarCount == 1) "" else "s"}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalNanoEmerald
                )
            }
        }

        timers.forEach { timer ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MoteBrandNavy)
                    .clickable { onToggleTimer(timer) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("live_timer_pill_${timer.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Timer, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(14.dp))
                Text(
                    text = "${timer.label} ${ClockToolsManager.formatDurationSeconds(timer.remainingSeconds)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                Icon(
                    imageVector = if (timer.isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (timer.isRunning) "Pause timer" else "Resume timer",
                    tint = LocalNanoEmerald,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        if (stopwatch.isRunning || stopwatch.elapsedMs > 0L) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onToggleStopwatch)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("live_stopwatch_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.AvTimer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                Text(
                    text = ClockToolsManager.formatStopwatchMs(stopwatch.elapsedMs),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                Icon(
                    imageVector = if (stopwatch.isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (stopwatch.isRunning) "Pause stopwatch" else "Start stopwatch",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun StreamingAssistantBubble(
    botName: String,
    partialText: String,
    elapsedMs: Long,
    pendingToolHint: String?,
    onStop: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val pulse = rememberInfiniteTransition(label = "streamCursor")
    val cursorAlpha by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                .background(tokens.surface)
                .border(1.dp, tokens.accentBlue.copy(alpha = 0.35f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(cursorAlpha)
                            .clip(CircleShape)
                            .background(LocalNanoEmerald)
                    )
                    Text(
                        text = "$botName · streaming ${(elapsedMs / 1000f).let { String.format(Locale.US, "%.1fs", it) }}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.accentBlue
                    )
                }
                Text(
                    text = "Stop",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.errorTitle,
                    modifier = Modifier.clickable(onClick = onStop)
                )
            }
            if (pendingToolHint != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.accentBlueBg)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                        .testTag("streaming_tool_hint_pill"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        color = tokens.accentBlue,
                        strokeWidth = 1.8.dp
                    )
                    Text(
                        text = pendingToolHint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accentBlue
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (partialText.isBlank()) {
                    if (pendingToolHint != null) "Executing live tool..." else "Thinking..."
                } else {
                    "$partialText▍"
                },
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (partialText.isBlank()) SlateMuted else MoteNavy
            )
        }
    }
}

@Composable
private fun InferenceMetricsChip(metrics: InferenceMetrics) {
    val tokens = LocalMoteColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.surfaceElevated)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = String.format(
                Locale.US,
                "%.1f tok/s · TTFT %dms · %dms · %s",
                metrics.approxTokensPerSec,
                metrics.ttftMs,
                metrics.latencyMs,
                metrics.modelName
            ),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = SlateMuted
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Mote response", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private suspend fun decodeSampledBitmap(
    context: Context,
    uri: Uri,
    maxDimension: Int
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return@withContext null

        var sampleSize = 1
        while (rawW / sampleSize > maxDimension || rawH / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    } catch (_: Exception) {
        null
    }
}

package com.mote.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import com.mote.app.R
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.LlmConfig
import com.mote.app.ai.MoteAction
import com.mote.app.control.ClockToolsManager
import com.mote.app.control.CountdownTimerItem
import com.mote.app.control.StopwatchUiState
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.data.LocalModelEntry
import com.mote.app.data.db.CalendarEventEntity
import com.mote.app.data.db.ClockAlarmEntity
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.ui.AiCardUiState
import com.mote.app.ui.ChatHistoryUiState
import com.mote.app.ui.ChatStreamUiState
import com.mote.app.ui.theme.LocalMoteColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Real-time draggable, edge-snapping, multi-bot floating AI overlay surface for Mote.
 *
 * Features:
 * 1. Minimized Multi-Bot Floating Dock: draggable main orb with magnetic dismiss zone, live
 *    floating mini-orbs for every active bot persona on screen, and a live ticking telemetry HUD
 *    showing active timers, stopwatch, or streaming AI status in real time.
 * 2. Expanded Floating Command Panel: spring-morphs smoothly out of the orb into a movable
 *    frosted-glass real-time chat & tool surface supporting:
 *    - Real-time Cloud Gemini API & Downloaded LiteRT-LM local model switching
 *    - Live streaming chat + inline AlarmManager, Countdown Timer, Stopwatch, Calendar Provider,
 *      and Generative UI widgets
 *    - Direct Control Card generation mode
 *    - Google Speech Recognition voice input with haptic feedback
 */
@Composable
fun FloatingAssistantView(
    bots: List<BotPersona>,
    aiState: AiCardUiState,
    onSubmitPrompt: (prompt: String, bot: BotPersona?) -> Unit,
    onExecuteAction: (MoteAction) -> Unit,
    onDismissOverlay: () -> Unit,
    modifier: Modifier = Modifier,
    chatHistoryState: ChatHistoryUiState = ChatHistoryUiState.Success(emptyList()),
    isCloudSynced: Boolean = false,
    onClearHistory: () -> Unit = {},
    initiallyExpanded: Boolean = false,
    chatStreamState: ChatStreamUiState = ChatStreamUiState.Idle,
    inChatUiPartsByText: Map<String, List<InChatUiPart>> = emptyMap(),
    clockAlarms: List<ClockAlarmEntity> = emptyList(),
    clockTimers: List<CountdownTimerItem> = emptyList(),
    clockStopwatch: StopwatchUiState = StopwatchUiState(),
    calendarEvents: List<CalendarEventEntity> = emptyList(),
    capturedScreenshots: Map<String, Bitmap> = emptyMap(),
    llmConfig: LlmConfig = LlmConfig(),
    localModels: List<LocalModelEntry> = emptyList(),
    loadedLocalModel: LoadedLocalModelState? = null,
    loadingLocalModelId: String? = null,
    preferLoadedLocalChat: Boolean = false,
    onSendStreamingChat: ((prompt: String, bot: BotPersona?) -> Unit)? = null,
    onStopStreaming: () -> Unit = {},
    onSelectCloudModel: (String) -> Unit = {},
    onSelectLocalModel: (String) -> Unit = {},
    onOpenModelHub: () -> Unit = {},
    onToggleAlarm: (String, Boolean) -> Unit = { _, _ -> },
    onShiftAlarmMinutes: (String, Int) -> Unit = { _, _ -> },
    onDeleteAlarm: (String) -> Unit = {},
    onSyncAlarmToSystem: (ClockAlarmEntity) -> Unit = {},
    onControlTimer: (String, String) -> Unit = { _, _ -> },
    onAddTimerSeconds: (String, Int) -> Unit = { _, _ -> },
    onControlStopwatch: (String) -> Unit = {},
    onDeleteCalendarEvent: (String) -> Unit = {},
    onSyncCalendarEventToSystem: (CalendarEventEntity) -> Unit = {},
    onAnalyzeScreenshot: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tokens = LocalMoteColors.current
    val hapticManager = rememberHapticFeedbackManager()
    val scope = rememberCoroutineScope()

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    var showBotDockStack by remember { mutableStateOf(true) }
    var isDraggingOrb by remember { mutableStateOf(false) }
    var isOverDismissZone by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var promptPrefixBeforeSpeech by remember { mutableStateOf("") }
    var speechState by remember { mutableStateOf(SpeechRecognitionState()) }
    var useLiveStreamMode by remember { mutableStateOf(onSendStreamingChat != null) }
    var showModelSwitcher by remember { mutableStateOf(false) }
    var showVoiceOverlay by remember { mutableStateOf(false) }
    var pendingVoiceCommandDispatch by remember { mutableStateOf<String?>(null) }

    val speechController = remember(context) {
        GoogleSpeechInputController(
            context = context,
            onPartialText = { partial ->
                promptText = if (promptPrefixBeforeSpeech.isBlank()) partial else "$promptPrefixBeforeSpeech $partial"
            },
            onFinalText = { finalText ->
                val combined = if (promptPrefixBeforeSpeech.isBlank()) finalText else "$promptPrefixBeforeSpeech $finalText"
                promptText = combined
                promptPrefixBeforeSpeech = ""
                if (showVoiceOverlay && combined.isNotBlank()) {
                    showVoiceOverlay = false
                    pendingVoiceCommandDispatch = combined
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

    LaunchedEffect(isExpanded) {
        if (!isExpanded && speechState.isListening && !showVoiceOverlay) {
            speechController.stopListening()
        }
    }

    val fallbackSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                promptText = if (promptText.isBlank()) spoken else "${promptText.trim()} $spoken"
                speechState = SpeechRecognitionState()
            }
        }
    }

    fun startOrStopVoiceInput() {
        if (speechState.isListening) {
            hapticManager.onVoiceStop()
            speechController.stopListening()
            showVoiceOverlay = false
            return
        }
        hapticManager.onVoiceStart()
        promptPrefixBeforeSpeech = promptText.trim()
        if (speechController.isOnDeviceRecognizerAvailable()) {
            showVoiceOverlay = true
            speechController.startListening()
        } else {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your Mote prompt...")
            }
            try {
                fallbackSpeechLauncher.launch(speechIntent)
            } catch (_: Exception) {
                speechState = SpeechRecognitionState(errorText = "Google Speech Recognition is unavailable on this device.")
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startOrStopVoiceInput()
        } else {
            speechState = SpeechRecognitionState(errorText = "Microphone permission is needed for voice input.")
        }
    }

    fun requestVoiceInput() {
        if (speechState.isListening) {
            startOrStopVoiceInput()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startOrStopVoiceInput()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(enabled = isExpanded) {
        hapticManager.onCollapse()
        isExpanded = false
    }

    val availableBots = remember(bots) { bots.ifEmpty { BotPersona.defaultStarters } }
    val activeFloatingBots = remember(availableBots) {
        availableBots.filter { it.active }.ifEmpty { availableBots }.take(4)
    }
    var selectedBotId by remember(availableBots) {
        mutableStateOf(availableBots.firstOrNull { it.active }?.id ?: availableBots.first().id)
    }
    val selectedBot = remember(availableBots, selectedBotId) {
        availableBots.firstOrNull { it.id == selectedBotId } ?: availableBots.first()
    }
    val accentColor = Color(selectedBot.colorHex)

    val activeCloudMeta = HybridAiRouter.supportedModels.firstOrNull { it.id == llmConfig.modelName }
        ?: HybridAiRouter.supportedModels.first()
    val activeLocalModel = if (preferLoadedLocalChat) loadedLocalModel else null
    val activeModelLabel = activeLocalModel?.displayName ?: activeCloudMeta.displayName
    val isStreaming = chatStreamState is ChatStreamUiState.Streaming
    val isBusy = isStreaming || aiState is AiCardUiState.Loading

    val runningTimer = clockTimers.firstOrNull { it.isRunning }
    val liveHudLabel = remember(isStreaming, runningTimer?.remainingSeconds, clockStopwatch.isRunning, clockStopwatch.elapsedMs) {
        when {
            isStreaming -> "AI Live..."
            runningTimer != null -> "Timer ${ClockToolsManager.formatDurationSeconds(runningTimer.remainingSeconds)}"
            clockStopwatch.isRunning -> "SW ${ClockToolsManager.formatStopwatchMs(clockStopwatch.elapsedMs)}"
            else -> null
        }
    }

    val quickSuggestions = remember(selectedBot.id, useLiveStreamMode) {
        if (useLiveStreamMode && onSendStreamingChat != null) {
            when (selectedBot.id) {
                "reply" -> listOf(
                    "Show my upcoming calendar events",
                    "Add event tomorrow 2 PM Product Sync",
                    "Draft 3 concise replies"
                )
                "saver" -> listOf(
                    "Start a 15 minute focus timer",
                    "Set a 7:30 AM weekday alarm",
                    "Start stopwatch for battery run"
                )
                else -> listOf(
                    "Start 5m Focus Timer",
                    "Set 7:30 AM Morning Alarm",
                    "Show my calendar events",
                    "Start the stopwatch"
                )
            }
        } else {
            when (selectedBot.id) {
                "reply" -> listOf("Draft 3 polite replies", "Summarize key points", "Quick confirm reply")
                "saver" -> listOf("Dim screen to 20%", "Enable DND for night", "Battery saver controls")
                else -> listOf("Focus Mode (25m)", "Dim screen & DND", "Start 10m break timer")
            }
        }
    }

    fun dispatchPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (useLiveStreamMode && onSendStreamingChat != null) {
            onSendStreamingChat(trimmed, selectedBot)
        } else {
            onSubmitPrompt(trimmed, selectedBot)
        }
        promptText = ""
    }

    LaunchedEffect(pendingVoiceCommandDispatch) {
        val cmd = pendingVoiceCommandDispatch
        if (!cmd.isNullOrBlank()) {
            pendingVoiceCommandDispatch = null
            isExpanded = true
            dispatchPrompt(cmd)
        }
    }

    // Unified physics-driven spring transition for morphing minimized icon -> full input interface
    val morphTransition = updateTransition(
        targetState = isExpanded,
        label = "floatingAssistantExpansionMorph"
    )
    val expandProgress by morphTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow
                )
            } else {
                spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMedium
                )
            }
        },
        label = "expandProgress"
    ) { expanded -> if (expanded) 1f else 0f }

    val clampedProgress = expandProgress.coerceIn(0f, 1f)
    val showExpandedContent = expandProgress >= 0.28f
    val orbContentAlpha = (1f - (clampedProgress / 0.28f)).coerceIn(0f, 1f)
    val expandedContentAlpha = ((clampedProgress - 0.28f) / 0.72f).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("floating_assistant_container")
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val edgeMarginPx = with(density) { 14.dp.toPx() }
        val topSafePx = with(density) { 64.dp.toPx() }
        val bottomSafePx = with(density) { 96.dp.toPx() }
        val orbSizeDp = 62.dp
        val orbSizePx = with(density) { orbSizeDp.toPx() }

        val minOrbX = edgeMarginPx
        val maxOrbX = (containerWidthPx - orbSizePx - edgeMarginPx).coerceAtLeast(minOrbX)
        val minOrbY = topSafePx
        val maxOrbY = (containerHeightPx - orbSizePx - bottomSafePx).coerceAtLeast(minOrbY)

        // Dock initially on the right edge, vertically mid-lower for thumb reachability
        val orbOffsetX = remember { Animatable(maxOrbX) }
        val orbOffsetY = remember { Animatable((maxOrbY * 0.72f).coerceIn(minOrbY, maxOrbY)) }
        var hasInitializedOrb by remember { mutableStateOf(false) }

        LaunchedEffect(maxOrbX, maxOrbY) {
            if (!hasInitializedOrb && maxOrbX > 0f) {
                orbOffsetX.snapTo(maxOrbX)
                orbOffsetY.snapTo((maxOrbY * 0.72f).coerceIn(minOrbY, maxOrbY))
                hasInitializedOrb = true
            } else {
                orbOffsetX.snapTo(orbOffsetX.value.coerceIn(minOrbX, maxOrbX))
                orbOffsetY.snapTo(orbOffsetY.value.coerceIn(minOrbY, maxOrbY))
            }
        }

        // Expanded floating card draggable position
        val cardWidthDp = minOf(maxWidth - 20.dp, 376.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val cardMaxHeightDp = (maxHeight - 156.dp).coerceIn(300.dp, 580.dp)
        val maxCardX = (containerWidthPx - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val maxCardY = (containerHeightPx - with(density) { 240.dp.toPx() }).coerceAtLeast(topSafePx)
        val cardOffsetX = remember { Animatable(((containerWidthPx - cardWidthPx) / 2f).coerceIn(edgeMarginPx, maxCardX)) }
        val cardOffsetY = remember { Animatable(topSafePx) }
        var hasInitializedCard by remember { mutableStateOf(false) }

        LaunchedEffect(containerWidthPx, containerHeightPx, isExpanded) {
            val centeredX = ((containerWidthPx - cardWidthPx) / 2f).coerceIn(edgeMarginPx, maxCardX)
            if (!hasInitializedCard && containerWidthPx > 0f) {
                cardOffsetX.snapTo(centeredX)
                cardOffsetY.snapTo((topSafePx + with(density) { 10.dp.toPx() }).coerceAtMost(maxCardY))
                hasInitializedCard = true
            } else {
                cardOffsetX.snapTo(cardOffsetX.value.coerceIn(edgeMarginPx, maxCardX))
                cardOffsetY.snapTo(cardOffsetY.value.coerceIn(topSafePx, maxCardY))
            }
        }

        // Subtle dimmed backdrop scrim tied to the spring expansion progress; tapping outside collapses back to orb
        if (clampedProgress > 0.02f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0D1D).copy(alpha = 0.24f * clampedProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = isExpanded
                    ) {
                        hapticManager.onCollapse()
                        isExpanded = false
                    }
                    .testTag("floating_assistant_backdrop")
            )
        }

        // Real-time Floating Multi-Bot Stack + Live Telemetry Mini-HUD anchored above the minimized orb
        if (!isExpanded && !isDraggingOrb && clampedProgress < 0.05f) {
            val secondaryBots = activeFloatingBots.filter { it.id != selectedBot.id }.take(3)
            val dockedOnRight = orbOffsetX.value + orbSizePx / 2f >= containerWidthPx / 2f
            val stackItemPx = with(density) { 44.dp.toPx() }
            val stackGapPx = with(density) { 8.dp.toPx() }

            // Floating mini-bot orbs stacked vertically above the primary orb
            if (showBotDockStack && secondaryBots.isNotEmpty()) {
                secondaryBots.forEachIndexed { idx, bot ->
                    val miniY = (orbOffsetY.value - (idx + 1) * (stackItemPx + stackGapPx))
                        .coerceAtLeast(topSafePx)
                    val miniX = (orbOffsetX.value + (orbSizePx - stackItemPx) / 2f)
                        .coerceIn(edgeMarginPx, (containerWidthPx - stackItemPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                    FloatingMiniBotOrb(
                        bot = bot,
                        onTap = {
                            selectedBotId = bot.id
                            hapticManager.onExpand()
                            isExpanded = true
                        },
                        modifier = Modifier
                            .offset { IntOffset(miniX.roundToInt(), miniY.roundToInt()) }
                            .testTag("floating_mini_bot_${bot.id}")
                    )
                }
            }

            // Live ticking Telemetry Mini-HUD Pill (countdown timer, stopwatch, or live AI stream)
            liveHudLabel?.let { hudText ->
                val pillWidthPx = with(density) { 126.dp.toPx() }
                val pillX = if (dockedOnRight) {
                    (orbOffsetX.value - pillWidthPx - with(density) { 8.dp.toPx() }).coerceAtLeast(edgeMarginPx)
                } else {
                    (orbOffsetX.value + orbSizePx + with(density) { 8.dp.toPx() })
                        .coerceAtMost((containerWidthPx - pillWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                }
                val pillY = (orbOffsetY.value + with(density) { 14.dp.toPx() }).coerceIn(topSafePx, maxOrbY)

                Row(
                    modifier = Modifier
                        .offset { IntOffset(pillX.roundToInt(), pillY.roundToInt()) }
                        .shadow(8.dp, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .background(MoteBrandNavy)
                        .border(1.dp, if (isStreaming) CloudFlashCobalt else LocalNanoEmerald, RoundedCornerShape(999.dp))
                        .clickable {
                            hapticManager.onExpand()
                            isExpanded = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("floating_live_hud_pill"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) CloudFlashCobalt else LocalNanoEmerald)
                    )
                    Text(
                        text = hudText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        // Bottom-center magnetic dismiss zone while dragging the minimized orb
        AnimatedVisibility(
            visible = isDraggingOrb && !isExpanded,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
        ) {
            val zoneBg by animateColorAsState(
                targetValue = if (isOverDismissZone) Color(0xFFDC2626) else Color(0xE60B0D1D),
                label = "dismissZoneColor"
            )
            val zoneScale by animateFloatAsState(
                targetValue = if (isOverDismissZone) 1.12f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "dismissZoneScale"
            )
            Row(
                modifier = Modifier
                    .scale(zoneScale)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(zoneBg)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .testTag("floating_dismiss_target"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.floating_dismiss_desc),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isOverDismissZone) "Release to Hide" else "Drag here to hide",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Unified spring-morphing floating container: minimized orb <-> full real-time AI surface
        val morphedX = lerp(orbOffsetX.value, cardOffsetX.value, expandProgress)
            .coerceIn(0f, (containerWidthPx - orbSizePx).coerceAtLeast(0f))
        val morphedY = lerp(orbOffsetY.value, cardOffsetY.value, expandProgress)
            .coerceIn(0f, (containerHeightPx - orbSizePx).coerceAtLeast(0f))
        val morphedWidth = lerp(orbSizeDp, cardWidthDp, expandProgress.coerceAtLeast(0f))
            .coerceAtLeast(orbSizeDp)
        val morphedCornerRadius = lerp(31.dp, 20.dp, clampedProgress)
        val morphedShape = RoundedCornerShape(morphedCornerRadius)
        val surfaceVisualAlpha = ((clampedProgress - 0.12f) / 0.35f).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .offset { IntOffset(morphedX.roundToInt(), morphedY.roundToInt()) }
                .width(morphedWidth)
                .heightIn(min = orbSizeDp, max = cardMaxHeightDp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        ) {
            if (!showExpandedContent) {
                Box(
                    modifier = Modifier
                        .size(orbSizeDp)
                        .graphicsLayer {
                            val shrinkScale = lerp(1f, 0.88f, 1f - orbContentAlpha)
                            scaleX = shrinkScale
                            scaleY = shrinkScale
                            alpha = orbContentAlpha
                        },
                    contentAlignment = Alignment.Center
                ) {
                    FloatingAssistantOrb(
                        accentColor = accentColor,
                        isDragging = isDraggingOrb,
                        isBusy = isBusy,
                        idleAlpha = (selectedBot.opacityPercent.coerceIn(60, 100) / 100f),
                        onTap = {
                            hapticManager.onExpand()
                            isExpanded = true
                        },
                        onDragStart = {
                            isDraggingOrb = true
                            hapticManager.onOrbGrab()
                        },
                        onDragDelta = { dx, dy ->
                            scope.launch {
                                val nextX = (orbOffsetX.value + dx).coerceIn(minOrbX, maxOrbX)
                                val nextY = (orbOffsetY.value + dy).coerceIn(minOrbY, maxOrbY)
                                orbOffsetX.snapTo(nextX)
                                orbOffsetY.snapTo(nextY)

                                val orbCenterX = nextX + orbSizePx / 2f
                                val orbCenterY = nextY + orbSizePx / 2f
                                val dismissCenterX = containerWidthPx / 2f
                                val dismissCenterY = containerHeightPx - with(density) { 116.dp.toPx() }
                                val nearDismiss = kotlin.math.hypot(
                                    (orbCenterX - dismissCenterX).toDouble(),
                                    (orbCenterY - dismissCenterY).toDouble()
                                ) < with(density) { 78.dp.toPx() }
                                if (nearDismiss && !isOverDismissZone) {
                                    hapticManager.onDismissZoneLock()
                                }
                                isOverDismissZone = nearDismiss
                            }
                        },
                        onDragEnd = {
                            val shouldDismiss = isOverDismissZone
                            isDraggingOrb = false
                            isOverDismissZone = false
                            if (shouldDismiss) {
                                onDismissOverlay()
                            } else {
                                val snapTargetX = if (orbOffsetX.value + orbSizePx / 2f < containerWidthPx / 2f) {
                                    minOrbX
                                } else {
                                    maxOrbX
                                }
                                scope.launch {
                                    orbOffsetX.animateTo(
                                        targetValue = snapTargetX,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("floating_assistant_orb")
                    )
                }
            } else {
                val shadowDp = lerp(8.dp, 22.dp, clampedProgress)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = shadowDp,
                            shape = morphedShape,
                            ambientColor = Color(0x330B0D1D),
                            spotColor = Color(0x400B0D1D)
                        )
                        .testTag("floating_assistant_panel"),
                    shape = morphedShape,
                    color = tokens.floatingPanelBg.copy(alpha = 0.96f + 0.03f * surfaceVisualAlpha),
                    border = BorderStroke(1.dp, tokens.border),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = expandedContentAlpha
                                translationY = (1f - expandedContentAlpha) * with(density) { 14.dp.toPx() }
                            }
                    ) {
                        // Draggable Surface Header with Real-Time Model Switcher Pill
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(MoteBrandNavy, Color(0xFF161B38))
                                    )
                                )
                                .pointerInput(maxCardX, maxCardY) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            cardOffsetX.snapTo(
                                                (cardOffsetX.value + dragAmount.x).coerceIn(edgeMarginPx, maxCardX)
                                            )
                                            cardOffsetY.snapTo(
                                                (cardOffsetY.value + dragAmount.y).coerceIn(topSafePx, maxCardY)
                                            )
                                        }
                                    }
                                }
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)
                                .testTag("floating_assistant_drag_header")
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .width(34.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x4DFFFFFF))
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(accentColor.copy(alpha = 0.24f))
                                                .border(1.5.dp, accentColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${selectedBot.name} Floating Bot",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(Color(0x26FFFFFF))
                                                    .clickable { showModelSwitcher = true }
                                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                                                    .testTag("floating_model_switcher_pill"),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (activeLocalModel != null) LocalNanoEmerald else Color(0xFF60A5FA))
                                                )
                                                Text(
                                                    text = if (activeLocalModel != null) "Local · $activeModelLabel" else "Cloud · $activeModelLabel",
                                                    color = Color(0xFFE0E7FF),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Icon(
                                                    imageVector = Icons.Outlined.SwapHoriz,
                                                    contentDescription = "Switch AI Model",
                                                    tint = Color(0xFF93C5FD),
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { showBotDockStack = !showBotDockStack },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("floating_toggle_dock_stack_button")
                                        ) {
                                            Icon(
                                                imageVector = if (showBotDockStack) Icons.Outlined.Layers else Icons.Outlined.LayersClear,
                                                contentDescription = "Toggle multi-bot floating stack",
                                                tint = if (showBotDockStack) LocalNanoEmerald else Color(0xFF94A3B8),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                hapticManager.onCollapse()
                                                isExpanded = false
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("floating_minimize_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Minimize,
                                                contentDescription = stringResource(R.string.floating_minimize_desc),
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                isExpanded = false
                                                onDismissOverlay()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("floating_close_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.floating_dismiss_desc),
                                                tint = Color(0xFFCBD5E1),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Scrollable Body inside Floating Surface
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Mode Toggle (Real-Time Tools & Chat vs Quick Control Card)
                            if (onSendStreamingChat != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = useLiveStreamMode,
                                        onClick = { useLiveStreamMode = true },
                                        label = { Text("Live AI + Tools", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CloudFlashCobalt,
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("floating_mode_live_tools")
                                    )
                                    FilterChip(
                                        selected = !useLiveStreamMode,
                                        onClick = { useLiveStreamMode = false },
                                        label = { Text("Control Card", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = tokens.primaryActionBg,
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White,
                                            containerColor = tokens.surfaceSubtle,
                                            labelColor = MoteNavy
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("floating_mode_control_card")
                                    )
                                }
                            }

                            // Persona Switcher Strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableBots.forEach { bot ->
                                    val selected = bot.id == selectedBot.id
                                    val botColor = Color(bot.colorHex)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedBotId = bot.id },
                                        label = {
                                            Text(
                                                text = bot.name,
                                                fontSize = 12.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        },
                                        leadingIcon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(if (selected) Color.White else botColor)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = tokens.primaryActionBg,
                                            selectedLabelColor = Color.White,
                                            containerColor = tokens.surfaceSubtle,
                                            labelColor = MoteNavy
                                        ),
                                        modifier = Modifier.testTag("floating_persona_chip_${bot.id}")
                                    )
                                }
                            }

                            // Context-Aware Suggested Prompt & Tool Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                quickSuggestions.forEachIndexed { index, suggestion ->
                                    AssistChip(
                                        onClick = { dispatchPrompt(suggestion) },
                                        label = {
                                            Text(
                                                text = suggestion,
                                                fontSize = 11.5.sp,
                                                color = MoteNavy
                                            )
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = tokens.surfaceElevated
                                        ),
                                        border = BorderStroke(1.dp, tokens.border),
                                        modifier = Modifier.testTag("floating_suggestion_chip_$index")
                                    )
                                }
                            }

                            // Prompt Input Row with Speech-to-Text Mic & Stop/Send Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = promptText,
                                    onValueChange = { promptText = it },
                                    placeholder = {
                                        Text(
                                            text = if (useLiveStreamMode) "Ask ${selectedBot.name} (alarms, timer, calendar)..." else "Speak or ask ${selectedBot.name}...",
                                            fontSize = 12.5.sp,
                                            color = SlateMuted
                                        )
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = tokens.accentBlue,
                                        unfocusedBorderColor = tokens.border,
                                        focusedContainerColor = tokens.surface,
                                        unfocusedContainerColor = tokens.surfaceSubtle,
                                        focusedTextColor = MoteNavy,
                                        unfocusedTextColor = MoteNavy
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("floating_prompt_input")
                                )

                                FilledTonalIconButton(
                                    onClick = { requestVoiceInput() },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (speechState.isListening) tokens.errorBg else tokens.accentBlueBg,
                                        contentColor = if (speechState.isListening) tokens.errorTitle else tokens.accentBlue
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .scale(if (speechState.isListening) 1f + speechState.rmsLevel * 0.12f else 1f)
                                        .testTag("floating_mic_button")
                                ) {
                                    Icon(
                                        imageVector = if (speechState.isListening) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                        contentDescription = if (speechState.isListening) "Stop listening" else "Voice input",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (isStreaming) {
                                    FilledIconButton(
                                        onClick = onStopStreaming,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = Color(0xFFDC2626),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("floating_stop_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Stop,
                                            contentDescription = "Stop streaming",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else {
                                    FilledIconButton(
                                        onClick = {
                                            if (speechState.isListening) speechController.stopListening()
                                            dispatchPrompt(promptText)
                                        },
                                        enabled = promptText.isNotBlank() && aiState !is AiCardUiState.Loading,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = LocalNanoEmerald,
                                            contentColor = Color.White,
                                            disabledContainerColor = tokens.divider,
                                            disabledContentColor = SlateMuted
                                        ),
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("floating_send_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.floating_send_desc),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            if (speechState.isListening) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tokens.errorBg)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("floating_voice_listening_banner"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFDC2626))
                                    )
                                    Text(
                                        text = "Listening via Google Speech Recognition...",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tokens.errorTitle,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            speechState.errorText?.let { status ->
                                Text(
                                    text = status,
                                    fontSize = 11.5.sp,
                                    color = tokens.errorTitle,
                                    modifier = Modifier.testTag("floating_voice_status")
                                )
                            }

                            // Real-time Streaming Response + Live Tool Widgets inside Floating Overlay
                            when (chatStreamState) {
                                is ChatStreamUiState.Streaming -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(tokens.accentBlueBg)
                                            .border(1.dp, tokens.accentBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                            .padding(10.dp)
                                            .testTag("floating_live_stream_card"),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(13.dp),
                                                    strokeWidth = 2.dp,
                                                    color = tokens.accentBlue
                                                )
                                                Text(
                                                    text = chatStreamState.pendingToolHint ?: "${chatStreamState.botName} streaming...",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = tokens.accentBlue
                                                )
                                            }
                                            Text(
                                                text = String.format("%.1fs", chatStreamState.elapsedMs / 1000.0),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = SlateMuted
                                            )
                                        }
                                        if (chatStreamState.partialText.isNotBlank()) {
                                            Text(
                                                text = chatStreamState.partialText,
                                                fontSize = 12.5.sp,
                                                color = MoteNavy,
                                                lineHeight = 17.sp
                                            )
                                        }
                                        chatStreamState.liveParts.forEach { part ->
                                            InChatUiPartRenderer(
                                                part = part,
                                                alarms = clockAlarms,
                                                timers = clockTimers,
                                                stopwatch = clockStopwatch,
                                                calendarEvents = calendarEvents,
                                                capturedScreenshots = capturedScreenshots,
                                                onToggleAlarm = onToggleAlarm,
                                                onShiftAlarmMinutes = onShiftAlarmMinutes,
                                                onDeleteAlarm = onDeleteAlarm,
                                                onSyncAlarmToSystem = onSyncAlarmToSystem,
                                                onControlTimer = onControlTimer,
                                                onAddTimerSeconds = onAddTimerSeconds,
                                                onControlStopwatch = onControlStopwatch,
                                                onDeleteCalendarEvent = onDeleteCalendarEvent,
                                                onSyncCalendarEventToSystem = onSyncCalendarEventToSystem,
                                                onExecuteGenUiAction = onExecuteAction,
                                                onAnalyzeScreenshot = onAnalyzeScreenshot
                                            )
                                        }
                                    }
                                }
                                is ChatStreamUiState.Error -> {
                                    Text(
                                        text = chatStreamState.message,
                                        fontSize = 11.5.sp,
                                        color = tokens.errorTitle,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(tokens.errorBg)
                                            .padding(10.dp)
                                    )
                                }
                                else -> Unit
                            }

                            // Control Card Output Area (when Control Card mode is triggered or active)
                            when (aiState) {
                                is AiCardUiState.Loading -> FloatingShimmerLoadingCard(modelName = activeModelLabel)
                                is AiCardUiState.Success -> {
                                    GenUiCardRenderer(
                                        result = aiState.result,
                                        onExecuteAction = onExecuteAction
                                    )
                                }
                                is AiCardUiState.Error -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(tokens.errorBg)
                                            .border(1.dp, tokens.errorBorder, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Couldn't generate controls",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = tokens.errorTitle
                                        )
                                        Text(
                                            text = aiState.message,
                                            fontSize = 12.sp,
                                            color = tokens.errorBody
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                val retryPrompt = promptText.trim().ifEmpty { quickSuggestions.first() }
                                                onSubmitPrompt(retryPrompt, selectedBot)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("floating_retry_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Refresh,
                                                contentDescription = null,
                                                tint = MoteNavy,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Retry", color = MoteNavy, fontSize = 12.sp)
                                        }
                                    }
                                }
                                is AiCardUiState.Idle -> {
                                    if (!isStreaming && ((chatHistoryState as? ChatHistoryUiState.Success)?.messages.isNullOrEmpty())) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(tokens.surfaceSubtle)
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = tokens.accentBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "Tap any chip or speak to run live Cloud Gemini / Local AI with real Alarm, Timer, Stopwatch, Calendar & GenUI widgets.",
                                                fontSize = 12.sp,
                                                color = SlateMuted
                                            )
                                        }
                                    }
                                }
                            }

                            // Live Conversation History + Inline Tool Widgets
                            FloatingChatHistorySection(
                                state = chatHistoryState,
                                isCloudSynced = isCloudSynced,
                                inChatUiPartsByText = inChatUiPartsByText,
                                clockAlarms = clockAlarms,
                                clockTimers = clockTimers,
                                clockStopwatch = clockStopwatch,
                                calendarEvents = calendarEvents,
                                capturedScreenshots = capturedScreenshots,
                                onToggleAlarm = onToggleAlarm,
                                onShiftAlarmMinutes = onShiftAlarmMinutes,
                                onDeleteAlarm = onDeleteAlarm,
                                onSyncAlarmToSystem = onSyncAlarmToSystem,
                                onControlTimer = onControlTimer,
                                onAddTimerSeconds = onAddTimerSeconds,
                                onControlStopwatch = onControlStopwatch,
                                onDeleteCalendarEvent = onDeleteCalendarEvent,
                                onSyncCalendarEventToSystem = onSyncCalendarEventToSystem,
                                onExecuteGenUiAction = onExecuteAction,
                                onAnalyzeScreenshot = onAnalyzeScreenshot,
                                onReplayPrompt = { pastPrompt -> promptText = pastPrompt },
                                onClearHistory = onClearHistory
                            )
                        }
                    }
                }
            }
        }

        VoiceAssistantOverlay(
            visible = showVoiceOverlay,
            speechState = speechState,
            activeBotName = selectedBot.name,
            activeRouteLabel = activeModelLabel,
            onToggleListening = { requestVoiceInput() },
            onQuickVoiceCommand = { cmd ->
                speechController.stopListening()
                showVoiceOverlay = false
                isExpanded = true
                dispatchPrompt(cmd)
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
            onSelectCloudModel = onSelectCloudModel,
            onSelectLocalModel = onSelectLocalModel,
            onOpenModelHub = {
                showModelSwitcher = false
                isExpanded = false
                onOpenModelHub()
            },
            onDismiss = { showModelSwitcher = false }
        )
    }
}

@Composable
private fun FloatingMiniBotOrb(
    bot: BotPersona,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val botColor = Color(bot.colorHex)
    val initials = bot.name.trim().take(2).uppercase()
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(8.dp, CircleShape, ambientColor = botColor, spotColor = Color(0x440B0D1D))
            .clip(CircleShape)
            .background(MoteBrandNavy.copy(alpha = 0.94f))
            .border(1.5.dp, botColor, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(botColor)
                .border(1.dp, MoteBrandNavy, CircleShape)
        )
    }
}

@Composable
private fun FloatingChatHistorySection(
    state: ChatHistoryUiState,
    isCloudSynced: Boolean,
    inChatUiPartsByText: Map<String, List<InChatUiPart>>,
    clockAlarms: List<ClockAlarmEntity>,
    clockTimers: List<CountdownTimerItem>,
    clockStopwatch: StopwatchUiState,
    calendarEvents: List<CalendarEventEntity>,
    capturedScreenshots: Map<String, Bitmap>,
    onToggleAlarm: (String, Boolean) -> Unit,
    onShiftAlarmMinutes: (String, Int) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onSyncAlarmToSystem: (ClockAlarmEntity) -> Unit,
    onControlTimer: (String, String) -> Unit,
    onAddTimerSeconds: (String, Int) -> Unit,
    onControlStopwatch: (String) -> Unit,
    onDeleteCalendarEvent: (String) -> Unit,
    onSyncCalendarEventToSystem: (CalendarEventEntity) -> Unit,
    onExecuteGenUiAction: (MoteAction) -> Unit,
    onAnalyzeScreenshot: (String, String) -> Unit,
    onReplayPrompt: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val messages = (state as? ChatHistoryUiState.Success)?.messages.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSubtle)
            .border(1.dp, tokens.border, RoundedCornerShape(12.dp))
            .padding(10.dp)
            .testTag("floating_chat_history_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MoteNavy,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "LIVE CHAT & TOOLS",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isCloudSynced) tokens.accentEmeraldBg else tokens.accentBlueBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isCloudSynced) "Firestore" else "Local Guest",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCloudSynced) tokens.accentEmerald else tokens.accentBlue
                    )
                }
            }

            if (messages.isNotEmpty()) {
                IconButton(
                    onClick = onClearHistory,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("floating_clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Clear chat history",
                        tint = SlateMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        when (state) {
            is ChatHistoryUiState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MoteNavy,
                        strokeWidth = 2.dp
                    )
                    Text("Loading history...", fontSize = 11.5.sp, color = Color(0xFF64748B))
                }
            }

            is ChatHistoryUiState.Error -> {
                Text(
                    text = state.message,
                    fontSize = 11.5.sp,
                    color = Color(0xFFB91C1C)
                )
            }

            is ChatHistoryUiState.Success -> {
                if (messages.isEmpty()) {
                    Text(
                        text = "No messages yet. Tap a tool chip or ask anything above.",
                        fontSize = 11.5.sp,
                        color = Color(0xFF64748B)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        messages.takeLast(6).forEach { msg ->
                            FloatingChatBubble(
                                message = msg,
                                onReplayPrompt = onReplayPrompt
                            )
                            if (msg.chatRole() == ChatRole.ASSISTANT) {
                                val parts = inChatUiPartsByText[msg.text.trim()].orEmpty()
                                parts.forEach { part ->
                                    InChatUiPartRenderer(
                                        part = part,
                                        alarms = clockAlarms,
                                        timers = clockTimers,
                                        stopwatch = clockStopwatch,
                                        calendarEvents = calendarEvents,
                                        capturedScreenshots = capturedScreenshots,
                                        onToggleAlarm = onToggleAlarm,
                                        onShiftAlarmMinutes = onShiftAlarmMinutes,
                                        onDeleteAlarm = onDeleteAlarm,
                                        onSyncAlarmToSystem = onSyncAlarmToSystem,
                                        onControlTimer = onControlTimer,
                                        onAddTimerSeconds = onAddTimerSeconds,
                                        onControlStopwatch = onControlStopwatch,
                                        onDeleteCalendarEvent = onDeleteCalendarEvent,
                                        onSyncCalendarEventToSystem = onSyncCalendarEventToSystem,
                                        onExecuteGenUiAction = onExecuteGenUiAction,
                                        onAnalyzeScreenshot = onAnalyzeScreenshot
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingChatBubble(
    message: ChatMessage,
    onReplayPrompt: (String) -> Unit
) {
    val tokens = LocalMoteColors.current
    val isUser = message.chatRole() == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 270.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    )
                )
                .background(if (isUser) tokens.primaryActionBg else tokens.surface)
                .border(
                    width = if (isUser) 0.dp else 1.dp,
                    color = if (isUser) Color.Transparent else tokens.border,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    )
                )
                .clickable(enabled = isUser) { onReplayPrompt(message.text) }
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("chat_history_item_${message.id}")
        ) {
            if (!isUser) {
                Text(
                    text = message.botName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.accentBlue
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = message.text,
                fontSize = 12.sp,
                color = if (isUser) Color.White else MoteNavy
            )
        }
    }
}

/**
 * Reusable draggable floating orb shared by both the in-app [FloatingAssistantView] and the
 * system-wide [com.mote.app.service.BotOverlayService] WindowManager overlay.
 */
@Composable
fun FloatingAssistantOrb(
    accentColor: Color,
    isDragging: Boolean,
    isBusy: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    idleAlpha: Float = 0.90f
) {
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.10f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "orbDragScale"
    )
    val orbAlpha by animateFloatAsState(
        targetValue = if (isDragging || isBusy) 1.0f else idleAlpha,
        animationSpec = tween(180),
        label = "orbIdleAlpha"
    )

    val pulseRadius = if (isBusy || isDragging) {
        val pulseTransition = rememberInfiniteTransition(label = "orbPulse")
        val animated by pulseTransition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (isBusy) 650 else 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orbPulseRadius"
        )
        animated
    } else {
        0.96f
    }

    Box(
        modifier = modifier
            .size(62.dp)
            .scale(scale)
            .alpha(orbAlpha)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (isDragging || isBusy) 0.42f else 0.24f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = (size.minDimension / 2f) * pulseRadius
                    )
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .shadow(
                    elevation = if (isDragging) 16.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = accentColor,
                    spotColor = Color(0x550B0D1D)
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(MoteBrandNavy, Color(0xFF1A2040))
                    )
                )
                .border(
                    width = if (isDragging) 2.dp else 1.5.dp,
                    color = accentColor.copy(alpha = 0.9f),
                    shape = CircleShape
                )
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mote_launcher),
                contentDescription = stringResource(R.string.floating_orb_desc),
                tint = Color.Unspecified,
                modifier = Modifier.size(38.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (isBusy) CloudFlashCobalt else LocalNanoEmerald)
                    .border(1.5.dp, MoteBrandNavy, CircleShape)
            )
        }
    }
}

@Composable
private fun FloatingShimmerLoadingCard(modelName: String = "Gemini 3.8 Flash") {
    val shimmerTransition = rememberInfiniteTransition(label = "genUiShimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    val tokens = LocalMoteColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSubtle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = tokens.accentBlue,
                strokeWidth = 2.dp
            )
            Text(
                text = "Generating controls with $modelName...",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.accentBlue
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.divider.copy(alpha = shimmerAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.divider.copy(alpha = shimmerAlpha * 0.85f))
        )
    }
}

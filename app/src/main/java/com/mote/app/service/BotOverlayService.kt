package com.mote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mote.app.R
import com.mote.app.ui.HybridInferenceActivity
import com.mote.app.ui.components.CloudFlashCobalt
import com.mote.app.ui.components.LocalNanoEmerald
import com.mote.app.ui.components.MoteBrandNavy
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private data class OverlayQuickBot(
    val id: String,
    val name: String,
    val colorHex: Long,
    val icon: ImageVector,
    val systemPrompt: String,
    val defaultPrompt: String
)

private val systemOverlayBots = listOf(
    OverlayQuickBot(
        id = "focus",
        name = "Chain Agent",
        colorHex = 0xFF38BDF8L,
        icon = Icons.Outlined.AutoAwesome,
        systemPrompt = "You are Mote, a system-wide Android AI agent with sequential tool chains, OCR, Notes, Chrome search, Clock, Calendar, and Live Voice Call Relay.",
        defaultPrompt = "Take a screenshot, run OCR analysis, and save to notes"
    ),
    OverlayQuickBot(
        id = "search",
        name = "Chrome Search",
        colorHex = 0xFF6366F1L,
        icon = Icons.Outlined.Search,
        systemPrompt = "You are Mote Web Agent. Open Chrome and search the user's request while keeping the floating overlay active.",
        defaultPrompt = "Open Chrome and search latest Android AI news"
    ),
    OverlayQuickBot(
        id = "voice_relay",
        name = "Voice Call AI",
        colorHex = 0xFF10B981L,
        icon = Icons.Outlined.PhoneInTalk,
        systemPrompt = "You are Mote Live Call Assistant. Dial numbers with ACTION_DIAL and run real-time Google Speech-to-Text and Text-to-Speech voice relay.",
        defaultPrompt = "Start real-time voice call relay to listen and respond aloud"
    ),
    OverlayQuickBot(
        id = "clock",
        name = "Clock",
        colorHex = 0xFFF59E0BL,
        icon = Icons.Outlined.Alarm,
        systemPrompt = "You are Clock Bot. Manage real alarms via AlarmManager, countdown timers, and stopwatch operations.",
        defaultPrompt = "Set an alarm for 7:30 AM labeled Morning Standup"
    ),
    OverlayQuickBot(
        id = "calendar",
        name = "Calendar",
        colorHex = 0xFF2563EBL,
        icon = Icons.Outlined.CalendarMonth,
        systemPrompt = "You are Calendar Bot. Manage events using Android Calendar Provider tools (listEvents, addEvent, updateEvent, deleteEvent).",
        defaultPrompt = "Show my upcoming calendar events"
    )
)

/**
 * System-level Siri-like Animated Overlay Foreground Service (`TYPE_APPLICATION_OVERLAY`).
 *
 * Floats cleanly on top of Chrome, YouTube, Phone, WhatsApp, Maps, and any external Android app
 * without requiring the user to stay inside Mote:
 * - Animated neon plasma Siri-style orb reacting to active tool chains & live microphone RMS audio
 * - Live Tool Chain Verification HUD pill showing real-time step progress & Verified badges
 * - Live Voice Call Relay HUD card showing real-time STT <-> AI <-> TTS transcript + Pause/End controls
 */
class BotOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        SystemOverlayStateBus.markServiceRunning(true)
        startForegroundNotification()
        attachSiriAgentOverlay()
    }

    private fun startForegroundNotification() {
        val channelId = "mote_overlay_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Mote Floating Agent", NotificationManager.IMPORTANCE_MIN)
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Mote AI Overlay Active")
            .setContentText("Floating over your apps for chained actions, OCR, Chrome search & live voice relay.")
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    private fun launchOverlayBot(bot: OverlayQuickBot) {
        val screenCtx = MoteAccessibilityService.instance?.captureRedactedScreenSummary()
        val intent = Intent(this, HybridInferenceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(HybridInferenceActivity.EXTRA_SCREEN_CONTEXT, screenCtx)
            putExtra(HybridInferenceActivity.EXTRA_BOT_ID, bot.id)
            putExtra(HybridInferenceActivity.EXTRA_BOT_NAME, bot.name)
            putExtra(HybridInferenceActivity.EXTRA_BOT_COLOR, bot.colorHex)
            putExtra(HybridInferenceActivity.EXTRA_BOT_SYSTEM_PROMPT, bot.systemPrompt)
            putExtra(HybridInferenceActivity.EXTRA_INITIAL_PROMPT, bot.defaultPrompt)
        }
        startActivity(intent)
    }

    private fun attachSiriAgentOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val edgeMarginPx = (10f * metrics.density).roundToInt()
        val orbApproxPx = (72f * metrics.density).roundToInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            y = (screenHeightPx * 0.36f).roundToInt()
        }
        overlayLayoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BotOverlayService)
            setViewTreeViewModelStoreOwner(this@BotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BotOverlayService)
            setContent {
                val hudState by SystemOverlayStateBus.state.collectAsState()
                var isDragging by remember { mutableStateOf(false) }
                var dockExpanded by remember { mutableStateOf(false) }

                val voiceRelay = hudState.voiceRelay
                val isSiriActive = hudState.isExecutingChain || voiceRelay.isActive || dockExpanded

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    // 1. Live Voice Call Relay Mini-Card floating right over Phone / any external app
                    AnimatedVisibility(
                        visible = voiceRelay.isActive && !isDragging,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SystemVoiceRelayHudCard(
                            relay = voiceRelay,
                            onTogglePause = { SystemOverlayStateBus.requestTogglePauseVoiceRelayFromOverlay() },
                            onStop = { SystemOverlayStateBus.requestStopVoiceRelayFromOverlay() }
                        )
                    }

                    // 2. Live Tool Chain Progress & Verification HUD Pill over Chrome / YouTube / any app
                    val bannerText = hudState.activeStatusBanner
                    AnimatedVisibility(
                        visible = !bannerText.isNullOrBlank() && !voiceRelay.isActive && !isDragging,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SystemChainStatusHudPill(
                            bannerText = bannerText.orEmpty(),
                            isExecuting = hudState.isExecutingChain,
                            allVerified = hudState.allVerified,
                            onTap = { launchOverlayBot(systemOverlayBots.first()) },
                            onDismiss = { SystemOverlayStateBus.clearBanner() }
                        )
                    }

                    // 3. Expanded Quick-Action Multi-Agent Dock
                    AnimatedVisibility(
                        visible = dockExpanded && !isDragging,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            systemOverlayBots.forEach { bot ->
                                SystemFloatingBotPill(
                                    bot = bot,
                                    onClick = {
                                        dockExpanded = false
                                        launchOverlayBot(bot)
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .shadow(6.dp, RoundedCornerShape(999.dp))
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xE6334155))
                                    .clickable { dockExpanded = false }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Collapse floating agent dock",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Hide",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // 4. Siri-Style Animated Plasma Agent Orb (draggable + edge-snapping)
                    SiriStyleSystemAgentOrb(
                        isActive = isSiriActive,
                        isExecuting = hudState.isExecutingChain,
                        allVerified = hudState.allVerified && !hudState.isExecutingChain,
                        rmsLevel = if (voiceRelay.isActive) voiceRelay.rmsLevel else if (hudState.isExecutingChain) 0.55f else 0.15f,
                        onTap = {
                            if (dockExpanded) {
                                dockExpanded = false
                                launchOverlayBot(systemOverlayBots.first())
                            } else {
                                dockExpanded = true
                            }
                        },
                        onDragStart = {
                            isDragging = true
                            dockExpanded = false
                        },
                        onDragDelta = { dx, dy ->
                            val p = overlayLayoutParams ?: return@SiriStyleSystemAgentOrb
                            val viewW = overlayView?.width?.takeIf { it > 0 } ?: orbApproxPx
                            val viewH = overlayView?.height?.takeIf { it > 0 } ?: orbApproxPx
                            p.x = (p.x + dx.roundToInt()).coerceIn(
                                edgeMarginPx,
                                (screenWidthPx - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            )
                            p.y = (p.y + dy.roundToInt()).coerceIn(
                                edgeMarginPx,
                                (screenHeightPx - viewH - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            )
                            overlayView?.let { v ->
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            val p = overlayLayoutParams ?: return@SiriStyleSystemAgentOrb
                            val viewW = overlayView?.width?.takeIf { it > 0 } ?: orbApproxPx
                            val snapRight = (screenWidthPx - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            p.x = if (p.x + viewW / 2 < screenWidthPx / 2) edgeMarginPx else snapRight
                            overlayView?.let { v ->
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        }
                    )
                }
            }
        }
        overlayView = view
        wm.addView(view, params)
    }

    override fun onDestroy() {
        SystemOverlayStateBus.markServiceRunning(false)
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        overlayLayoutParams = null
        viewModelStore.clear()
        super.onDestroy()
    }
}

@Composable
private fun SiriStyleSystemAgentOrb(
    isActive: Boolean,
    isExecuting: Boolean,
    allVerified: Boolean,
    rmsLevel: Float,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "siriOverlayOrb")
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isExecuting) 1800 else 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "siriSpin"
    )
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "siriWave"
    )
    val smoothRms by animateFloatAsState(
        targetValue = rmsLevel.coerceIn(0.08f, 1f),
        animationSpec = tween(110),
        label = "siriRms"
    )

    Box(
        modifier = Modifier
            .size(66.dp)
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
            }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(66.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.36f
            val pulseRadius = baseRadius + (size.minDimension * 0.12f * smoothRms)

            // Outer ambient Siri plasma glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (allVerified) Color(0x6610B981) else Color(0x7738BDF8),
                        Color(0x448B5CF6),
                        Color.Transparent
                    ),
                    center = center,
                    radius = pulseRadius * 1.35f
                ),
                radius = pulseRadius * 1.35f,
                center = center
            )

            // Dark iridescent core
            drawCircle(
                color = Color(0xEE0B1024),
                radius = baseRadius * 1.06f,
                center = center
            )

            // Rotating Siri multi-hue neon sweep ring
            rotate(degrees = spinAngle, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFF06B6D4),
                            Color(0xFF3B82F6),
                            Color(0xFF8B5CF6),
                            Color(0xFFEC4899),
                            Color(0xFF10B981),
                            Color(0xFF06B6D4)
                        ),
                        center = center
                    ),
                    radius = baseRadius * (1.02f + 0.08f * sin(wavePhase)),
                    center = center,
                    style = Stroke(width = if (isActive) 4.2.dp.toPx() else 2.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner harmonic plasma highlight
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x9938BDF8),
                        Color(0x558B5CF6),
                        Color.Transparent
                    ),
                    center = Offset(center.x - 4f, center.y - 4f),
                    radius = baseRadius * 0.78f
                ),
                radius = baseRadius * 0.78f,
                center = center
            )
        }

        if (isExecuting) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.4.dp,
                color = Color(0xFF38BDF8)
            )
        } else {
            Icon(
                imageVector = if (allVerified) Icons.Outlined.CheckCircle else Icons.Outlined.GraphicEq,
                contentDescription = "Open Mote AI Overlay",
                tint = if (allVerified) Color(0xFF34D399) else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun SystemChainStatusHudPill(
    bannerText: String,
    isExecuting: Boolean,
    allVerified: Boolean,
    onTap: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .widthIn(max = 264.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF20B1024))
            .border(
                width = 1.2.dp,
                color = when {
                    isExecuting -> Color(0xFF38BDF8)
                    allVerified -> LocalNanoEmerald
                    else -> Color(0xFF6366F1)
                },
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (isExecuting) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF38BDF8)
            )
        } else {
            Icon(
                imageVector = if (allVerified) Icons.Outlined.CheckCircle else Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = if (allVerified) LocalNanoEmerald else Color(0xFF38BDF8),
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = bannerText,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Dismiss overlay status",
            tint = Color(0xFF94A3B8),
            modifier = Modifier
                .size(15.dp)
                .clickable(onClick = onDismiss)
        )
    }
}

@Composable
private fun SystemVoiceRelayHudCard(
    relay: LiveVoiceRelayUiState,
    onTogglePause: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 210.dp, max = 274.dp)
            .shadow(14.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF50B1024))
            .border(
                width = 1.3.dp,
                color = when (relay.phase) {
                    VoiceRelayPhase.LISTENING -> Color(0xFF38BDF8)
                    VoiceRelayPhase.SPEAKING -> LocalNanoEmerald
                    VoiceRelayPhase.THINKING -> Color(0xFFA855F7)
                    else -> Color(0xFF64748B)
                },
                shape = RoundedCornerShape(18.dp)
            )
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneInTalk,
                contentDescription = null,
                tint = LocalNanoEmerald,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "${relay.targetLabel} · ${relay.phase.label}",
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Live micro waveform strip driven by real-time mic RMS
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.height(18.dp)
        ) {
            val weights = listOf(0.45f, 0.8f, 1f, 0.7f, 0.92f, 0.6f, 0.4f)
            weights.forEachIndexed { idx, w ->
                val barH = (4f + 14f * (relay.rmsLevel.coerceIn(0.1f, 1f) * w)).dp
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(barH)
                        .clip(CircleShape)
                        .background(
                            if (idx % 2 == 0) Color(0xFF38BDF8) else Color(0xFF34D399)
                        )
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = relay.partialTranscript.ifBlank {
                    relay.lastSpokenReply.ifBlank { relay.statusNote }
                },
                color = Color(0xFFCBD5E1),
                fontSize = 10.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF1E293B))
                    .clickable(onClick = onTogglePause)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (relay.phase == VoiceRelayPhase.PAUSED) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = "Pause or resume live voice relay",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (relay.phase == VoiceRelayPhase.PAUSED) "Resume" else "Pause",
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF991B1B))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.StopCircle,
                    contentDescription = "End live voice call relay",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "End Relay",
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SystemFloatingBotPill(
    bot: OverlayQuickBot,
    onClick: () -> Unit
) {
    val accent = Color(bot.colorHex)
    Row(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(MoteBrandNavy.copy(alpha = 0.95f))
            .border(1.5.dp, accent, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = bot.name,
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = bot.icon,
                contentDescription = "Open ${bot.name} Bot",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

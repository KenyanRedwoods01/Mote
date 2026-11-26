package com.mote.app.service

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mote.app.R
import com.mote.app.ui.components.LocalNanoEmerald
import com.mote.app.ui.components.MoteBrandNavy
import kotlin.math.PI
import kotlin.math.sin

internal data class OverlayQuickBot(
    val id: String,
    val name: String,
    val colorHex: Long,
    val icon: ImageVector,
    val systemPrompt: String,
    val defaultPrompt: String
)

internal val systemOverlayBots = listOf(
    OverlayQuickBot(
        id = "focus",
        name = "Chain Agent",
        colorHex = 0xFF38BDF8L,
        icon = Icons.Outlined.AutoAwesome,
        systemPrompt = "You are Mote, a system-wide Android AI agent with sequential tool chains, Room-persisted ToolChainState, OCR, Notes, Chrome search, Clock, Calendar, and Live Voice Call Relay.",
        defaultPrompt = "Take a screenshot -> OCR analysis -> Save to Notes"
    ),
    OverlayQuickBot(
        id = "search",
        name = "Chrome Search",
        colorHex = 0xFF6366F1L,
        icon = Icons.Outlined.Search,
        systemPrompt = "You are Mote Web Agent. Open Chrome and search the user's request while keeping the floating Lottie overlay active.",
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
 * Shared Lottie-based animated system voice assistant interface rendered identically inside both:
 * 1. [AccessibilityFloatingWindowManager] (`TYPE_ACCESSIBILITY_OVERLAY`, persistent over System UI & all apps)
 * 2. [BotOverlayService] (`WindowManager` + `TYPE_APPLICATION_OVERLAY`)
 */
@Composable
internal fun SystemAssistantOverlayContent(
    hostBadgeLabel: String,
    onLaunchBot: (OverlayQuickBot) -> Unit
) {
    val hudState by SystemOverlayStateBus.state.collectAsState()
    val customConfig by OverlayCustomizationStore.config.collectAsState()
    val isDragging = hudState.isDraggingOverlay
    var dockExpanded by remember { mutableStateOf(false) }

    if (isDragging && dockExpanded) {
        dockExpanded = false
    }

    val voiceRelay = hudState.voiceRelay
    val isSiriActive = hudState.isExecutingChain || voiceRelay.isActive || dockExpanded
    val liveRms = when {
        voiceRelay.isActive -> voiceRelay.rmsLevel.coerceIn(0.18f, 1f)
        hudState.isExecutingChain -> 0.62f
        dockExpanded -> 0.42f
        else -> 0.16f
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(4.dp)
            .graphicsLayer { alpha = if (isDragging) 1f else customConfig.orbOpacity }
            .testTag("system_overlay_root")
    ) {
        // 1. Live Lottie Voice Call Relay Mini-Card floating over Phone / any external app
        AnimatedVisibility(
            visible = voiceRelay.isActive && !isDragging,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SystemLottieVoiceRelayHudCard(
                relay = voiceRelay,
                hostBadgeLabel = hostBadgeLabel,
                onTogglePause = { SystemOverlayStateBus.requestTogglePauseVoiceRelayFromOverlay() },
                onStop = { SystemOverlayStateBus.requestStopVoiceRelayFromOverlay() }
            )
        }

        // 2. Live Tool Chain Progress & Verification HUD Pill over Chrome / YouTube / any app
        val bannerText = hudState.activeStatusBanner
        AnimatedVisibility(
            visible = customConfig.showFloatingStatusPill && !bannerText.isNullOrBlank() && !voiceRelay.isActive && !isDragging,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SystemChainStatusHudPill(
                bannerText = bannerText.orEmpty(),
                isExecuting = hudState.isExecutingChain,
                allVerified = hudState.allVerified,
                onTap = { onLaunchBot(systemOverlayBots.first()) },
                onDismiss = { SystemOverlayStateBus.clearBanner() }
            )
        }

        // 3. Expanded Quick-Action Multi-Agent Dock with Live Lottie Waveform Header
        AnimatedVisibility(
            visible = dockExpanded && !isDragging,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                SystemOverlayLottieVoiceBar(
                    hostBadgeLabel = hostBadgeLabel,
                    rmsLevel = liveRms,
                    isExecuting = hudState.isExecutingChain,
                    theme = customConfig.orbTheme,
                    onTapVoice = {
                        dockExpanded = false
                        onLaunchBot(systemOverlayBots.first { it.id == "voice_relay" })
                    }
                )
                systemOverlayBots.forEach { bot ->
                    SystemFloatingBotPill(
                        bot = bot,
                        onClick = {
                            dockExpanded = false
                            onLaunchBot(bot)
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xE6334155))
                        .clickable { dockExpanded = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("system_overlay_hide_dock"),
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

        // 4. Lottie-Powered Siri-Style Animated Voice Orb (hardware-smooth free-position drag)
        LottieSystemVoiceOrb(
            hostBadgeLabel = hostBadgeLabel,
            orbTheme = customConfig.orbTheme,
            orbSizeDp = customConfig.orbSizeDp,
            isActive = isSiriActive,
            isDragging = isDragging,
            isExecuting = hudState.isExecutingChain,
            allVerified = hudState.allVerified && !hudState.isExecutingChain,
            rmsLevel = liveRms,
            onTap = {
                if (dockExpanded) {
                    dockExpanded = false
                    onLaunchBot(systemOverlayBots.first())
                } else {
                    dockExpanded = true
                }
            }
        )
    }
}

@Composable
private fun SystemOverlayLottieVoiceBar(
    hostBadgeLabel: String,
    rmsLevel: Float,
    isExecuting: Boolean,
    theme: OverlayOrbTheme,
    onTapVoice: () -> Unit
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.voice_waveform_lottie)
    )
    val speed = (0.7f + rmsLevel * 2.1f).coerceIn(0.6f, 2.8f)
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = true,
        iterations = LottieConstants.IterateForever,
        speed = speed
    )
    val borderAccent = Color(theme.primaryHex)

    Row(
        modifier = Modifier
            .widthIn(min = 196.dp, max = 248.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF50B1024))
            .border(1.2.dp, borderAccent, RoundedCornerShape(16.dp))
            .clickable(onClick = onTapVoice)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("system_overlay_lottie_voice_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .width(56.dp)
                .height(26.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isExecuting) "Running Tool Chain" else "Mote Voice AI",
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$hostBadgeLabel · Drag anywhere · Tap to speak",
                color = borderAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LottieSystemVoiceOrb(
    hostBadgeLabel: String,
    orbTheme: OverlayOrbTheme,
    orbSizeDp: Int,
    isActive: Boolean,
    isDragging: Boolean,
    isExecuting: Boolean,
    allVerified: Boolean,
    rmsLevel: Float,
    onTap: () -> Unit
) {
    val primaryColor = Color(orbTheme.primaryHex)
    val secondaryColor = Color(orbTheme.secondaryHex)
    val accentColor = Color(orbTheme.accentHex)

    val infinite = rememberInfiniteTransition(label = "siriOverlayOrb")
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isExecuting || isDragging) 1400 else 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "siriSpin"
    )
    val wavePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "siriWave"
    )
    val smoothRms by animateFloatAsState(
        targetValue = if (isDragging) 0.85f else rmsLevel.coerceIn(0.10f, 1f),
        animationSpec = tween(110),
        label = "siriRms"
    )
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.12f else 1f,
        animationSpec = tween(130),
        label = "orbDragScale"
    )

    val lottieComposition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.voice_waveform_lottie)
    )
    val lottieSpeed = if (isActive || isExecuting || isDragging) {
        (0.85f + smoothRms * 2.2f).coerceIn(0.85f, 3.0f)
    } else {
        0.55f
    }
    val lottieProgress by animateLottieCompositionAsState(
        composition = lottieComposition,
        isPlaying = true,
        iterations = LottieConstants.IterateForever,
        speed = lottieSpeed
    )

    val outerSize = orbSizeDp.coerceIn(56, 88).dp
    val canvasSize = (orbSizeDp - 4).coerceAtLeast(52).dp

    Box(
        modifier = Modifier
            .size(outerSize)
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            }
            .clickable(onClick = onTap)
            .testTag("system_overlay_lottie_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(canvasSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.36f
            val pulseRadius = baseRadius + (size.minDimension * 0.12f * smoothRms)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        when {
                            isDragging -> primaryColor.copy(alpha = 0.72f)
                            allVerified -> Color(0x6610B981)
                            else -> primaryColor.copy(alpha = 0.46f)
                        },
                        secondaryColor.copy(alpha = 0.34f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = pulseRadius * 1.38f
                ),
                radius = pulseRadius * 1.38f,
                center = center
            )

            drawCircle(
                color = Color(0xF00B1024),
                radius = baseRadius * 1.08f,
                center = center
            )

            rotate(degrees = spinAngle, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            primaryColor,
                            secondaryColor,
                            accentColor,
                            primaryColor
                        ),
                        center = center
                    ),
                    radius = baseRadius * (1.03f + 0.08f * sin(wavePhase)),
                    center = center,
                    style = Stroke(
                        width = if (isActive || isDragging) 4.2.dp.toPx() else 2.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }
        }

        if (isExecuting) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.4.dp,
                color = primaryColor
            )
        } else if (allVerified && !isDragging) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = "Execution verified - open Mote AI Overlay",
                tint = Color(0xFF34D399),
                modifier = Modifier.size(24.dp)
            )
        } else {
            LottieAnimation(
                composition = lottieComposition,
                progress = { lottieProgress },
                modifier = Modifier
                    .size((orbSizeDp * 0.46f).dp)
                    .graphicsLayer {
                        val s = (0.85f + smoothRms * 0.35f).coerceIn(0.85f, 1.25f)
                        scaleX = s
                        scaleY = s
                    }
            )
        }

        // Tiny host/move badge ("MOVE" while dragging, otherwise "A11Y" or "WM")
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(999.dp))
                .background(if (isDragging) primaryColor else Color(0xE60F172A))
                .border(0.75.dp, primaryColor.copy(alpha = 0.85f), RoundedCornerShape(999.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = if (isDragging) "MOVE" else hostBadgeLabel,
                color = if (isDragging) Color(0xFF0F172A) else primaryColor,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.ExtraBold
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
private fun SystemLottieVoiceRelayHudCard(
    relay: LiveVoiceRelayUiState,
    hostBadgeLabel: String,
    onTogglePause: () -> Unit,
    onStop: () -> Unit
) {
    val lottieComposition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.voice_waveform_lottie)
    )
    val speed = when (relay.phase) {
        VoiceRelayPhase.LISTENING -> (0.85f + relay.rmsLevel.coerceIn(0.1f, 1f) * 2.2f)
        VoiceRelayPhase.SPEAKING -> 1.85f
        VoiceRelayPhase.THINKING -> 1.2f
        else -> 0.25f
    }
    val lottieProgress by animateLottieCompositionAsState(
        composition = lottieComposition,
        isPlaying = relay.phase != VoiceRelayPhase.PAUSED,
        iterations = LottieConstants.IterateForever,
        speed = speed
    )

    Column(
        modifier = Modifier
            .widthIn(min = 216.dp, max = 278.dp)
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
            Text(
                text = hostBadgeLabel,
                color = Color(0xFF7DD3FC),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Lottie-powered real-time audio waveform driven by mic RMS & TTS playback state
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LottieAnimation(
                composition = lottieComposition,
                progress = { lottieProgress },
                modifier = Modifier
                    .width(64.dp)
                    .height(26.dp)
            )
            Text(
                text = relay.partialTranscript.ifBlank {
                    relay.lastSpokenReply.ifBlank { relay.statusNote }
                },
                color = Color(0xFFCBD5E1),
                fontSize = 10.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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

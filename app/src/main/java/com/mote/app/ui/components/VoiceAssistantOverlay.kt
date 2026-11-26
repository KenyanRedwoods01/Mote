package com.mote.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mote.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated Siri & Alexa-inspired Voice Command Overlay combining:
 * 1. Real-time Lottie-Compose audio waveform (`R.raw.voice_waveform_lottie`) dynamically driven
 *    by live microphone RMS amplitude (`speechState.rmsLevel`).
 * 2. Siri-style multi-layered luminous plasma orb + harmonic fluid sine waves drawn on Compose Canvas.
 * 3. Alexa-style glowing cyan/cobalt/violet/emerald bottom lightbar sweeping across the screen edge.
 * 4. Real-time partial speech transcript preview & one-tap multi-step voice command suggestions.
 */
@Composable
fun VoiceAssistantOverlay(
    visible: Boolean,
    speechState: SpeechRecognitionState,
    activeBotName: String,
    activeRouteLabel: String,
    onToggleListening: () -> Unit,
    onQuickVoiceCommand: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val smoothedRms by animateFloatAsState(
        targetValue = if (speechState.isListening) speechState.rmsLevel.coerceIn(0.08f, 1f) else 0.05f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "voice_overlay_rms"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "voice_overlay_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    val orbPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    // Lottie-Compose audio waveform bound to real-time microphone RMS speed & vertical amplitude!
    val lottieComposition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.voice_waveform_lottie)
    )
    val lottieSpeed = if (speechState.isListening) {
        (0.75f + smoothedRms * 2.4f).coerceIn(0.75f, 3.2f)
    } else {
        0.25f
    }
    val lottieProgress by animateLottieCompositionAsState(
        composition = lottieComposition,
        isPlaying = visible,
        iterations = LottieConstants.IterateForever,
        speed = lottieSpeed
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB8060B18))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .testTag("voice_assistant_overlay_scrim"),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Alexa-inspired luminous edge glow running along the bottom bezel
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .align(Alignment.BottomCenter)
            ) {
                val centerShift = (sin(wavePhase.toDouble()).toFloat() * 0.25f + 0.5f) * size.width
                val glowRadius = size.width * (0.45f + smoothedRms * 0.35f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF06B6D4).copy(alpha = 0.45f + smoothedRms * 0.35f),
                            Color(0xFF2563EB).copy(alpha = 0.28f + smoothedRms * 0.22f),
                            Color(0xFF7C3AED).copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(centerShift, size.height),
                        radius = glowRadius.coerceAtLeast(120f)
                    )
                )
                // Crisp Alexa bottom lightbar ribbon
                val barHeight = (4.dp.toPx() + smoothedRms * 6.dp.toPx())
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF10B981),
                            Color(0xFF06B6D4),
                            Color(0xFF3B82F6),
                            Color(0xFF8B5CF6),
                            Color(0xFFEC4899),
                            Color(0xFF06B6D4)
                        ),
                        startX = -size.width * 0.2f + (wavePhase / (2f * PI.toFloat())) * size.width * 0.4f,
                        endX = size.width * 1.2f
                    ),
                    topLeft = Offset(0f, size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size(size.width, barHeight)
                )
            }

            // Frosted Dark Command Sheet floating cleanly above the Alexa lightbar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xF50F172A), Color(0xFA090E1D))
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF06B6D4).copy(alpha = 0.6f),
                                Color(0xFF8B5CF6).copy(alpha = 0.5f),
                                Color(0xFF10B981).copy(alpha = 0.5f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .testTag("voice_assistant_overlay_card"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (speechState.isListening) Color(0xFF10B981) else Color(0xFF38BDF8))
                        )
                        Column {
                            Text(
                                text = "$activeBotName · Voice Command",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = activeRouteLabel,
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .testTag("voice_overlay_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close voice overlay",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Siri Luminous Plasma Orb + Multi-Harmonic Live Audio Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(142.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cy = size.height / 2f
                        val width = size.width
                        val amplitudePx = (10.dp.toPx() + smoothedRms * 34.dp.toPx())

                        // 3 overlapping Siri-style luminous harmonic audio ribbons
                        val ribbonColors = listOf(
                            Color(0xFF06B6D4).copy(alpha = 0.85f),
                            Color(0xFF8B5CF6).copy(alpha = 0.78f),
                            Color(0xFFEC4899).copy(alpha = 0.65f)
                        )
                        ribbonColors.forEachIndexed { idx, color ->
                            val path = Path()
                            val freq = 2.2f + idx * 0.75f
                            val phaseOffset = wavePhase * (1.2f + idx * 0.35f) + idx * 1.1f
                            val step = 6f
                            var x = 0f
                            while (x <= width) {
                                val normX = x / width
                                // Hanning window envelope so wave tapers smoothly at both edges
                                val envelope = sin(normX * PI).toFloat()
                                val y = cy + sin((normX * freq * 2f * PI + phaseOffset).toDouble()).toFloat() *
                                    amplitudePx * envelope * (1f - idx * 0.14f)
                                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                                x += step
                            }
                            drawPath(
                                path = path,
                                color = color,
                                style = Stroke(width = (3.2f - idx * 0.6f).dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        // Central Siri Plasma Orb Halo
                        val orbCenter = Offset(width / 2f, cy)
                        val baseRadius = (36.dp.toPx() + smoothedRms * 22.dp.toPx()) * orbPulse
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.55f),
                                    Color(0xFF7C3AED).copy(alpha = 0.38f),
                                    Color(0xFFEC4899).copy(alpha = 0.18f),
                                    Color.Transparent
                                ),
                                center = orbCenter,
                                radius = baseRadius * 1.55f
                            ),
                            radius = baseRadius * 1.55f,
                            center = orbCenter
                        )

                        // Rotating plasma lobe inside the Siri orb
                        val lobeOffset = Offset(
                            orbCenter.x + cos(wavePhase.toDouble()).toFloat() * 10.dp.toPx(),
                            orbCenter.y + sin(wavePhase.toDouble()).toFloat() * 10.dp.toPx()
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF10B981).copy(alpha = 0.5f),
                                    Color(0xFF2563EB).copy(alpha = 0.4f),
                                    Color.Transparent
                                ),
                                center = lobeOffset,
                                radius = baseRadius * 0.95f
                            ),
                            radius = baseRadius * 0.95f,
                            center = lobeOffset
                        )
                    }

                    // Real-time Lottie-Compose Audio Waveform layered cleanly across the center
                    LottieAnimation(
                        composition = lottieComposition,
                        progress = { lottieProgress },
                        modifier = Modifier
                            .width(190.dp)
                            .height(74.dp)
                            .graphicsLayer {
                                scaleY = (0.55f + smoothedRms * 0.85f).coerceIn(0.55f, 1.45f)
                                scaleX = (0.92f + smoothedRms * 0.14f)
                            }
                            .testTag("voice_lottie_waveform")
                    )
                }

                // Live Audio Level Meter Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (speechState.isListening) {
                            "LIVE MIC · ${(smoothedRms * 100).toInt()}% INPUT"
                        } else {
                            "TAP MIC TO SPEAK"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFBAE6FD)
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Real-time Spoken Transcript Preview
                val voiceError = speechState.errorMessage
                Text(
                    text = when {
                        !voiceError.isNullOrBlank() -> voiceError
                        speechState.partialText.isNotBlank() -> "\"${speechState.partialText}\""
                        speechState.isListening -> "Listening… say a command or multi-step chain"
                        else -> "Ask me to open apps, set alarms, or capture & share your screen"
                    },
                    fontSize = 15.sp,
                    fontWeight = if (speechState.partialText.isNotBlank()) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (!voiceError.isNullOrBlank()) Color(0xFFFCA5A5) else Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )

                Spacer(Modifier.height(14.dp))

                // Quick Voice Chain Examples
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Take a screenshot and share it on WhatsApp",
                        "Set an alarm for 7:30 AM and start a 5 min timer",
                        "Open YouTube",
                        "Open Chrome"
                    ).forEach { sample ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.09f))
                                .border(0.75.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                                .clickable { onQuickVoiceCommand(sample) }
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = sample,
                                    fontSize = 11.sp,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Primary Mic Toggle Button
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = if (speechState.isListening) {
                                    listOf(Color(0xFFEF4444), Color(0xFFEC4899))
                                } else {
                                    listOf(Color(0xFF06B6D4), Color(0xFF2563EB), Color(0xFF7C3AED))
                                }
                            )
                        )
                        .clickable(onClick = onToggleListening)
                        .testTag("voice_overlay_mic_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (speechState.isListening) Icons.Rounded.Stop else Icons.Outlined.Mic,
                        contentDescription = if (speechState.isListening) "Stop listening" else "Start voice command",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

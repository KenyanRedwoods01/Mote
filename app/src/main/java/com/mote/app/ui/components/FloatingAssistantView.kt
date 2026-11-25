package com.mote.app.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mote.app.R
import com.mote.app.ai.MoteAction
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.ui.AiCardUiState
import com.mote.app.ui.ChatHistoryUiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draggable, edge-snapping, overlay-capable Jetpack Compose surface for the Mote AI interface.
 *
 * Uses a unified physics-driven spring transition ([updateTransition] + [spring]) to morph
 * continuously between:
 * 1. Minimized floating orb: drags smoothly at 120 Hz, scales up with haptic feedback, shows a
 *    magnetic bottom-center dismiss zone, and spring-snaps to the nearest screen edge.
 * 2. Full expanded input interface: springs out directly from the orb's position, width, corner
 *    radius, and elevation into a movable frosted-glass AI control card with persona switching,
 *    quick suggestion chips, prompt input, shimmer loading, inline Generative UI controls, and
 *    live conversation history.
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
    initiallyExpanded: Boolean = false
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    var isDraggingOrb by remember { mutableStateOf(false) }
    var isOverDismissZone by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }

    BackHandler(enabled = isExpanded) {
        isExpanded = false
    }

    val availableBots = remember(bots) { bots.ifEmpty { BotPersona.defaultStarters } }
    var selectedBotId by remember(availableBots) {
        mutableStateOf(availableBots.firstOrNull { it.active }?.id ?: availableBots.first().id)
    }
    val selectedBot = remember(availableBots, selectedBotId) {
        availableBots.firstOrNull { it.id == selectedBotId } ?: availableBots.first()
    }
    val accentColor = Color(selectedBot.colorHex)

    val quickSuggestions = remember(selectedBot.id) {
        when (selectedBot.id) {
            "reply" -> listOf("Draft 3 polite replies", "Summarize key points", "Quick confirm reply")
            "saver" -> listOf("Dim screen to 20%", "Enable DND for night", "Battery saver controls")
            else -> listOf("Focus Mode (25m)", "Dim screen & DND", "Start 10m break timer")
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
        val cardWidthDp = minOf(maxWidth - 24.dp, 368.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val cardMaxHeightDp = (maxHeight - 172.dp).coerceIn(280.dp, 520.dp)
        val maxCardX = (containerWidthPx - cardWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val maxCardY = (containerHeightPx - with(density) { 240.dp.toPx() }).coerceAtLeast(topSafePx)
        val cardOffsetX = remember { Animatable(((containerWidthPx - cardWidthPx) / 2f).coerceIn(edgeMarginPx, maxCardX)) }
        val cardOffsetY = remember { Animatable(topSafePx) }
        var hasInitializedCard by remember { mutableStateOf(false) }

        LaunchedEffect(containerWidthPx, containerHeightPx, isExpanded) {
            val centeredX = ((containerWidthPx - cardWidthPx) / 2f).coerceIn(edgeMarginPx, maxCardX)
            if (!hasInitializedCard && containerWidthPx > 0f) {
                cardOffsetX.snapTo(centeredX)
                cardOffsetY.snapTo((topSafePx + with(density) { 12.dp.toPx() }).coerceAtMost(maxCardY))
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
                    .background(Color(0xFF0B0D1D).copy(alpha = 0.22f * clampedProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = isExpanded
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isExpanded = false
                    }
                    .testTag("floating_assistant_backdrop")
            )
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

        // Unified spring-morphing floating container: smoothly interpolates position, width,
        // corner radius, elevation, and border from minimized orb -> full AI input surface
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
                // Minimized floating orb state (and early expansion / late collapse morph phase)
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
                        isBusy = aiState is AiCardUiState.Loading,
                        idleAlpha = (selectedBot.opacityPercent.coerceIn(55, 100) / 100f),
                        onTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isExpanded = true
                        },
                        onDragStart = {
                            isDraggingOrb = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                // Expanded full input surface morphing out of the orb via spring physics
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
                    color = Color(0xFAFFFFFF).copy(alpha = 0.94f + 0.04f * surfaceVisualAlpha),
                    border = BorderStroke(1.dp, Color(0x1F0B0D1D)),
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
                        // Draggable Surface Header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(MoteNavy, Color(0xFF161B38))
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
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(accentColor.copy(alpha = 0.22f))
                                                .border(1.dp, accentColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${selectedBot.name} Assistant",
                                                color = Color.White,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${selectedBot.role} · Drag bar to move",
                                                color = Color(0xFFA5B4FC),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                            selectedContainerColor = MoteNavy,
                                            selectedLabelColor = Color.White,
                                            containerColor = IosCanvas,
                                            labelColor = MoteNavy
                                        ),
                                        modifier = Modifier.testTag("floating_persona_chip_${bot.id}")
                                    )
                                }
                            }

                            // Context-Aware Suggested Prompt Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                quickSuggestions.forEachIndexed { index, suggestion ->
                                    AssistChip(
                                        onClick = {
                                            promptText = suggestion
                                            onSubmitPrompt(suggestion, selectedBot)
                                        },
                                        label = {
                                            Text(
                                                text = suggestion,
                                                fontSize = 11.5.sp,
                                                color = MoteNavy
                                            )
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = Color(0xFFF1F5F9)
                                        ),
                                        border = BorderStroke(1.dp, Color(0x1A0B0D1D)),
                                        modifier = Modifier.testTag("floating_suggestion_chip_$index")
                                    )
                                }
                            }

                            // Prompt Input Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = promptText,
                                    onValueChange = { promptText = it },
                                    placeholder = {
                                        Text(
                                            text = "Ask ${selectedBot.name} for controls...",
                                            fontSize = 13.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MoteNavy,
                                        unfocusedBorderColor = Color(0x330F172A),
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = IosCanvas
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("floating_prompt_input")
                                )

                                FilledIconButton(
                                    onClick = {
                                        val trimmed = promptText.trim()
                                        if (trimmed.isNotEmpty()) {
                                            onSubmitPrompt(trimmed, selectedBot)
                                        }
                                    },
                                    enabled = promptText.isNotBlank() && aiState !is AiCardUiState.Loading,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = LocalNanoEmerald,
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFFE2E8F0),
                                        disabledContentColor = Color(0xFF94A3B8)
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

                            // AI Output Area (Idle / Shimmer Loading / GenUI Card / Error)
                            when (aiState) {
                                is AiCardUiState.Idle -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(IosCanvas)
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DragHandle,
                                            contentDescription = null,
                                            tint = CloudFlashCobalt,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Tap a quick chip or type a request to render native Android controls inline.",
                                            fontSize = 12.sp,
                                            color = Color(0xFF475569)
                                        )
                                    }
                                }

                                is AiCardUiState.Loading -> {
                                    FloatingShimmerLoadingCard()
                                }

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
                                            .background(Color(0xFFFEF2F2))
                                            .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Couldn't generate controls",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFFB91C1C)
                                        )
                                        Text(
                                            text = aiState.message,
                                            fontSize = 12.sp,
                                            color = Color(0xFF7F1D1D)
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
                            }

                            // Conversation History Section (Firestore-synced or Local Guest)
                            FloatingChatHistorySection(
                                state = chatHistoryState,
                                isCloudSynced = isCloudSynced,
                                onReplayPrompt = { pastPrompt ->
                                    promptText = pastPrompt
                                },
                                onClearHistory = onClearHistory
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingChatHistorySection(
    state: ChatHistoryUiState,
    isCloudSynced: Boolean,
    onReplayPrompt: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val messages = (state as? ChatHistoryUiState.Success)?.messages.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0x140F172A), RoundedCornerShape(12.dp))
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
                    text = "CHAT HISTORY",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isCloudSynced) Color(0xFFECFDF5) else Color(0xFFEFF6FF))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isCloudSynced) "Firestore" else "Local Guest",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCloudSynced) LocalNanoEmerald else CloudFlashCobalt
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
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        when (state) {
            is ChatHistoryUiState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                        text = "No conversation history yet. Send a prompt or tap a suggestion chip to start.",
                        fontSize = 11.5.sp,
                        color = Color(0xFF64748B)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        messages.takeLast(8).forEach { msg ->
                            FloatingChatBubble(
                                message = msg,
                                onReplayPrompt = onReplayPrompt
                            )
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
    val isUser = message.chatRole() == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    )
                )
                .background(if (isUser) MoteNavy else Color.White)
                .border(
                    width = if (isUser) 0.dp else 1.dp,
                    color = if (isUser) Color.Transparent else Color(0x1A0F172A),
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
                    color = CloudFlashCobalt
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

    val pulseTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 650 else 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulseRadius"
    )

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
                        colors = listOf(MoteNavy, Color(0xFF1A2040))
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
                    .border(1.5.dp, MoteNavy, CircleShape)
            )
        }
    }
}

@Composable
private fun FloatingShimmerLoadingCard() {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(IosCanvas)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = CloudFlashCobalt,
                strokeWidth = 2.dp
            )
            Text(
                text = "Generating controls with Gemini 3.8 Flash...",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = CloudFlashCobalt
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFCBD5E1).copy(alpha = shimmerAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFCBD5E1).copy(alpha = shimmerAlpha * 0.85f))
        )
    }
}

package com.mote.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

val MoteNavy = Color(0xFF0B0D1D)
val IosCanvas = Color(0xFFF2F2F7)
val LocalNanoEmerald = Color(0xFF059669)
val CloudFlashCobalt = Color(0xFF1D4ED8)
val IosGreen = Color(0xFF34C759)
val SlateMuted = Color(0xFF64748B)

/**
 * Crisp grouped card matching Mote's clean LLM client design language.
 */
@Composable
fun MoteCard(
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x120B0D1D))
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(0.5.dp, Color(0x140F172A), RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

/**
 * Spring-animated iPhone-style toggle switch.
 */
@Composable
fun IosSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) IosGreen else Color(0xFFD1D5DB),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "switchTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchThumb"
    )

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(CircleShape)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset, y = 2.dp)
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * Translucent top navigation bar with clean spacing and optional trailing actions.
 */
@Composable
fun IosTopNavBar(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosCanvas.copy(alpha = 0.94f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MoteNavy,
                letterSpacing = (-0.3).sp
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = SlateMuted
                )
            }
        }
        trailing()
    }
}

data class FloatingTab(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector
)

val moteFloatingTabs = listOf(
    FloatingTab("chat", "Chat", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    FloatingTab("models", "Models", Icons.Outlined.Memory, Icons.Rounded.Memory),
    FloatingTab("privacy", "Privacy", Icons.Outlined.Shield, Icons.Rounded.Shield),
    FloatingTab("settings", "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
)

/**
 * GPU-accelerated, liquid-smooth floating glass capsule bottom navigation bar.
 *
 * Continuously tracks [pagerProgress] (`pagerState.currentPage + pagerState.currentPageOffsetFraction`)
 * via `graphicsLayer` so swipes and spring-driven tab taps glide at 60/120fps with zero relayout jitter,
 * subtle elastic pill stretch during transit, smooth icon color/scale interpolation, and safe navigation-bar insets.
 */
@Composable
fun IosFloatingBottomNav(
    selectedIndex: Int,
    pagerProgress: Float = selectedIndex.toFloat(),
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val clampedProgress = pagerProgress.coerceIn(0f, (moteFloatingTabs.size - 1).toFloat())
    val transitFraction = (abs(clampedProgress - clampedProgress.roundToInt()) * 2f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x240B0D1D),
                    spotColor = Color(0x280B0D1D)
                )
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xF5FFFFFF),
                            Color(0xECF1F5F9)
                        )
                    )
                )
                .border(1.dp, Color(0x1C0F172A), CircleShape)
                .padding(5.dp)
                .testTag("floating_bottom_nav")
        ) {
            val tabWidthDp = maxWidth / moteFloatingTabs.size
            val tabWidthPx = with(density) { tabWidthDp.toPx() }
            val liftPx = with(density) { 1.5.dp.toPx() }

            // GPU-accelerated gliding pill — zero layout invalidation during swipe or spring transit
            Box(
                modifier = Modifier
                    .width(tabWidthDp)
                    .height(50.dp)
                    .graphicsLayer {
                        translationX = clampedProgress * tabWidthPx
                        scaleX = 1f + transitFraction * 0.10f
                        scaleY = 1f - transitFraction * 0.04f
                    }
                    .shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x1E0B0D1D),
                        spotColor = Color(0x240B0D1D)
                    )
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.75.dp, Color(0x120F172A), CircleShape)
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                moteFloatingTabs.forEachIndexed { idx, tab ->
                    val selectionFraction = (1f - abs(clampedProgress - idx)).coerceIn(0f, 1f)
                    val contentColor = lerp(SlateMuted, MoteNavy, selectionFraction)
                    val isDominant = selectionFraction >= 0.5f

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (idx != selectedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(idx)
                                }
                            }
                            .testTag("nav_tab_${tab.route}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isDominant) tab.filledIcon else tab.outlinedIcon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier
                                .size(21.dp)
                                .graphicsLayer {
                                    val s = 1f + selectionFraction * 0.10f
                                    scaleX = s
                                    scaleY = s
                                    translationY = -selectionFraction * liftPx
                                }
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

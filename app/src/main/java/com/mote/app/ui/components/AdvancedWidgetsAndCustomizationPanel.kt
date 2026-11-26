package com.mote.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.control.ClockToolsManager
import com.mote.app.control.CountdownTimerItem
import com.mote.app.control.NetworkStatusSnapshot
import com.mote.app.control.StopwatchUiState
import com.mote.app.data.db.ClockAlarmEntity
import com.mote.app.service.OverlayCustomizationConfig
import com.mote.app.service.OverlayOrbTheme
import com.mote.app.ui.theme.LocalMoteColors
import kotlin.math.roundToInt

/**
 * Persistent customization studio for Mote's floating overlay orb, live cursor hover visualizer,
 * and chat quick-control widget deck.
 */
@Composable
fun OverlayCustomizationCard(
    config: OverlayCustomizationConfig,
    onUpdateConfig: ((OverlayCustomizationConfig) -> OverlayCustomizationConfig) -> Unit,
    onTestLiveCursor: () -> Unit,
    onResetOverlayPosition: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    val activePrimary = Color(config.orbTheme.primaryHex)
    val activeSecondary = Color(config.orbTheme.secondaryHex)

    MoteCard(
        background = tokens.surface,
        modifier = modifier
            .border(1.dp, activePrimary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .testTag("overlay_customization_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(activePrimary, activeSecondary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Overlay and Cursor Customization",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OVERLAY & CURSOR STUDIO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = activePrimary
                )
                Text(
                    text = "Advanced Widgets & Customizations",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                Text(
                    text = "Personalize the draggable assistant orb, real-time cursor glide, and live chat widgets.",
                    fontSize = 11.sp,
                    color = SlateMuted,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 1. Orb Color Theme Selector
        Text(
            text = "FLOATING ORB THEME",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = SlateMuted
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverlayOrbTheme.entries.forEach { theme ->
                val selected = config.orbTheme == theme
                val c1 = Color(theme.primaryHex)
                val c2 = Color(theme.secondaryHex)
                Row(
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) c1.copy(alpha = 0.16f) else MoteSurfaceSubtle)
                        .border(
                            width = if (selected) 1.5.dp else 0.75.dp,
                            color = if (selected) c1 else MoteBorder,
                            shape = RoundedCornerShape(999.dp)
                        )
                        .clickable {
                            onUpdateConfig {
                                it.copy(
                                    orbTheme = theme,
                                    cursorAccentHex = theme.primaryHex
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("overlay_theme_chip_${theme.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c1, c2)))
                    )
                    Text(
                        text = theme.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MoteNavy else SlateMuted
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2. Orb Size Slider (56..88 dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Floating Orb Size", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(
                text = "${config.orbSizeDp} dp",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = activePrimary
            )
        }
        Slider(
            value = config.orbSizeDp.toFloat(),
            onValueChange = { value ->
                onUpdateConfig { it.copy(orbSizeDp = value.roundToInt().coerceIn(56, 88)) }
            },
            valueRange = 56f..88f,
            steps = 7,
            modifier = Modifier.testTag("overlay_orb_size_slider")
        )

        // 3. Orb Opacity Slider (55%..100%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Overlay Opacity", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(
                text = "${(config.orbOpacity * 100f).roundToInt()}%",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = activePrimary
            )
        }
        Slider(
            value = config.orbOpacity,
            onValueChange = { value ->
                onUpdateConfig { it.copy(orbOpacity = value.coerceIn(0.55f, 1.0f)) }
            },
            valueRange = 0.55f..1.0f,
            modifier = Modifier.testTag("overlay_orb_opacity_slider")
        )

        // 4. Cursor Glide Speed Slider (180..650 ms)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Live Cursor Glide Speed", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(
                text = "${config.cursorGlideMs} ms",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = activePrimary
            )
        }
        Slider(
            value = config.cursorGlideMs.toFloat(),
            onValueChange = { value ->
                onUpdateConfig { it.copy(cursorGlideMs = value.roundToInt().toLong().coerceIn(180L, 650L)) }
            },
            valueRange = 180f..650f,
            modifier = Modifier.testTag("overlay_cursor_glide_slider")
        )

        Spacer(Modifier.height(6.dp))

        // 5. Customization Switches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CustomizationToggleRow(
                title = "Real-Time Cursor Hover Visualizer",
                subtitle = "Shows animated glowing pointer & click ripple over target buttons",
                checked = config.showCursorHover,
                tag = "overlay_toggle_cursor_hover",
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(showCursorHover = enabled) } }
            )
            CustomizationToggleRow(
                title = "Floating Status Pill",
                subtitle = "Displays active chain step & drag hints next to the overlay orb",
                checked = config.showFloatingStatusPill,
                tag = "overlay_toggle_status_pill",
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(showFloatingStatusPill = enabled) } }
            )
            CustomizationToggleRow(
                title = "Live Quick-Control Widgets Deck",
                subtitle = "Pin Clock, Wi-Fi/Network, Camera & Cursor quick widgets in Chat Hub",
                checked = config.showLiveQuickWidgets,
                tag = "overlay_toggle_quick_widgets",
                onCheckedChange = { enabled -> onUpdateConfig { it.copy(showLiveQuickWidgets = enabled) } }
            )
        }

        Spacer(Modifier.height(12.dp))

        // 6. Action Buttons (48.dp touch targets)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onTestLiveCursor,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = activePrimary),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("overlay_test_cursor_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.AdsClick,
                    contentDescription = "Test live cursor hover",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Test Cursor", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            OutlinedButton(
                onClick = onResetOverlayPosition,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("overlay_reset_position_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.MyLocation,
                    contentDescription = "Reset floating overlay position",
                    tint = MoteNavy,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Reset Position", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }
    }
}

@Composable
private fun CustomizationToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(subtitle, fontSize = 10.5.sp, color = SlateMuted, lineHeight = 14.sp)
        }
        IosSpringSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

/**
 * Collapsible Advanced Live Widgets Deck for Chat Hub & Tools:
 * Provides instant, real-time Android controls for Clock (Alarms, Timer, Stopwatch),
 * Wi-Fi & Network telemetry, Camera & Hardware Torch, and Real-Time App Control Cursor.
 */
@Composable
fun AdvancedLiveWidgetsDeck(
    alarms: List<ClockAlarmEntity>,
    timers: List<CountdownTimerItem>,
    stopwatch: StopwatchUiState,
    network: NetworkStatusSnapshot,
    accessibilityReady: Boolean,
    onStartStopwatch: () -> Unit,
    onLapStopwatch: () -> Unit,
    onStartQuickTimer5m: () -> Unit,
    onQuickAlarm10m: () -> Unit,
    onOpenWifiPanel: () -> Unit,
    onRunNetworkPing: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenCameraPhoto: () -> Unit,
    onOpenCameraSelfie: () -> Unit,
    onOpenCameraVideo: () -> Unit,
    onToggleTorch: () -> Unit,
    onTriggerCameraShutter: () -> Unit,
    onPreviewCursor: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    val tokens = LocalMoteColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val enabledAlarms = alarms.count { it.enabled }
    val runningTimers = timers.count { it.isRunning }

    MoteCard(
        background = tokens.surface,
        modifier = modifier
            .border(1.dp, tokens.border, RoundedCornerShape(16.dp))
            .testTag("advanced_live_widgets_deck")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp)
                .testTag("advanced_live_widgets_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tokens.accentBlueBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Widgets,
                    contentDescription = "Live Device Widgets",
                    tint = tokens.accentBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Live Command Widgets",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (network.isConnected) tokens.accentEmeraldBg else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = network.transportLabel.uppercase(),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (network.isConnected) LocalNanoEmerald else Color(0xFFDC2626)
                        )
                    }
                }
                Text(
                    text = "${enabledAlarms} alarms · ${runningTimers} timers · ${ClockToolsManager.formatStopwatchMs(stopwatch.elapsedMs)} · Cursor ${if (accessibilityReady) "Active" else "Preview"}",
                    fontSize = 10.5.sp,
                    color = SlateMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse live widgets" else "Expand live widgets",
                tint = SlateMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Tile 1: Live Clock & Stopwatch
                QuickWidgetTile(
                    icon = Icons.Outlined.AvTimer,
                    accent = CloudFlashCobalt,
                    category = "CLOCK SUITE",
                    headline = ClockToolsManager.formatStopwatchMs(stopwatch.elapsedMs),
                    subtitle = "$enabledAlarms alarm(s) · $runningTimers timer(s)",
                    tag = "quick_widget_clock"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = if (stopwatch.isRunning) "Pause" else "Start",
                            accent = if (stopwatch.isRunning) Color(0xFFF59E0B) else LocalNanoEmerald,
                            filled = true,
                            onClick = onStartStopwatch,
                            tag = "quick_clock_stopwatch_toggle"
                        )
                        QuickMiniPill(
                            label = "Lap",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onLapStopwatch,
                            tag = "quick_clock_stopwatch_lap"
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "5m Timer",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onStartQuickTimer5m,
                            tag = "quick_clock_timer_5m"
                        )
                        QuickMiniPill(
                            label = "+10m Alarm",
                            accent = Color(0xFF7C3AED),
                            filled = false,
                            onClick = onQuickAlarm10m,
                            tag = "quick_clock_alarm_10m"
                        )
                    }
                }

                // Tile 2: Wi-Fi & Network Telemetry
                QuickWidgetTile(
                    icon = if (network.isWifi) Icons.Outlined.Wifi else Icons.Outlined.NetworkCheck,
                    accent = LocalNanoEmerald,
                    category = "WI-FI & NETWORK",
                    headline = network.wifiSsid ?: network.transportLabel,
                    subtitle = buildString {
                        append(network.localIpv4 ?: "No IP")
                        network.lastLatencyMs?.let { append(" · ${it}ms") }
                    },
                    tag = "quick_widget_network"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Wi-Fi Panel",
                            accent = LocalNanoEmerald,
                            filled = true,
                            onClick = onOpenWifiPanel,
                            tag = "quick_network_wifi_panel"
                        )
                        QuickMiniPill(
                            label = "Ping",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onRunNetworkPing,
                            tag = "quick_network_ping"
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Refresh Telemetry",
                            accent = SlateMuted,
                            filled = false,
                            onClick = onRefreshNetwork,
                            tag = "quick_network_refresh"
                        )
                    }
                }

                // Tile 3: Camera & Torch
                QuickWidgetTile(
                    icon = Icons.Outlined.PhotoCamera,
                    accent = Color(0xFFEC4899),
                    category = "CAMERA & TORCH",
                    headline = "Photo · Video · Flash",
                    subtitle = "Linked cursor shutter ready",
                    tag = "quick_widget_camera"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Photo",
                            accent = Color(0xFFEC4899),
                            filled = true,
                            onClick = onOpenCameraPhoto,
                            tag = "quick_camera_photo"
                        )
                        QuickMiniPill(
                            label = "Selfie",
                            accent = Color(0xFFEC4899),
                            filled = false,
                            onClick = onOpenCameraSelfie,
                            tag = "quick_camera_selfie"
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Video",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onOpenCameraVideo,
                            tag = "quick_camera_video"
                        )
                        QuickMiniPill(
                            label = "Torch",
                            accent = Color(0xFFF59E0B),
                            filled = false,
                            onClick = onToggleTorch,
                            tag = "quick_camera_torch"
                        )
                        QuickMiniPill(
                            label = "Snap",
                            accent = LocalNanoEmerald,
                            filled = false,
                            onClick = onTriggerCameraShutter,
                            tag = "quick_camera_shutter"
                        )
                    }
                }

                // Tile 4: Live App Control Cursor
                QuickWidgetTile(
                    icon = Icons.Outlined.AdsClick,
                    accent = Color(0xFF0EA5E9),
                    category = "LIVE CURSOR",
                    headline = if (accessibilityReady) "Accessibility Armed" else "Visual Preview Mode",
                    subtitle = "Hover & click any foreground app",
                    tag = "quick_widget_cursor"
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Preview Hover",
                            accent = Color(0xFF0EA5E9),
                            filled = true,
                            onClick = onPreviewCursor,
                            tag = "quick_cursor_preview"
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickMiniPill(
                            label = "Scroll Down",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onSwipeDown,
                            tag = "quick_cursor_scroll_down"
                        )
                        QuickMiniPill(
                            label = "Scroll Up",
                            accent = CloudFlashCobalt,
                            filled = false,
                            onClick = onSwipeUp,
                            tag = "quick_cursor_scroll_up"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickWidgetTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    category: String,
    headline: String,
    subtitle: String,
    tag: String,
    actions: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalMoteColors.current
    Column(
        modifier = Modifier
            .width(214.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MoteSurfaceSubtle)
            .border(0.85.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(11.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = category,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = category,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = headline,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MoteNavy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            fontSize = 10.5.sp,
            color = SlateMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.surface.copy(alpha = 0.6f))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = actions
        )
    }
}

@Composable
private fun QuickMiniPill(
    label: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) accent else accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.White else accent,
            maxLines = 1
        )
    }
}

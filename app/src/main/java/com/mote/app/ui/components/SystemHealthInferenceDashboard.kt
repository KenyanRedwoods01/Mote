package com.mote.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.mote.app.ai.AiCoreAvailabilityState
import com.mote.app.ai.AiCoreStatusSnapshot
import com.mote.app.control.AdaptiveInferenceTarget
import com.mote.app.control.BatteryTelemetrySnapshot
import com.mote.app.control.ObservedSystemEvent
import com.mote.app.control.SystemHealthSnapshot
import com.mote.app.control.ThermalHealthLevel
import com.mote.app.model.MoteInferenceMode
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified 'System Health & Inference' Dashboard visualizing live Android battery temperature,
 * PowerManager thermal throttling status, battery/power draw, Firebase AI Logic OnDeviceConfig
 * provider routing (On-Device Gemini Nano vs Cloud Gemini), adaptive decision rationale, and
 * live [ToolExecutionSyncService] EventObserver OS callbacks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemHealthInferenceDashboard(
    health: SystemHealthSnapshot,
    batteryTelemetry: BatteryTelemetrySnapshot,
    selectedHybridMode: MoteInferenceMode,
    aiCoreStatus: AiCoreStatusSnapshot,
    adaptiveEnabled: Boolean,
    lastRouteNote: String?,
    observedEvents: List<ObservedSystemEvent>,
    onToggleAdaptive: (Boolean) -> Unit,
    onSelectHybridMode: (MoteInferenceMode) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val isCloudRecommended = health.recommendedTarget == AdaptiveInferenceTarget.CLOUD_GEMINI ||
        selectedHybridMode == MoteInferenceMode.ONLY_IN_CLOUD ||
        selectedHybridMode == MoteInferenceMode.PREFER_IN_CLOUD ||
        (selectedHybridMode == MoteInferenceMode.PREFER_ON_DEVICE && aiCoreStatus.state != AiCoreAvailabilityState.AVAILABLE)

    val thermalColor by animateColorAsState(
        targetValue = when (health.thermalLevel) {
            ThermalHealthLevel.COOL -> Color(0xFF059669)
            ThermalHealthLevel.WARM -> Color(0xFFD97706)
            ThermalHealthLevel.MODERATE -> Color(0xFFEA580C)
            ThermalHealthLevel.HOT, ThermalHealthLevel.CRITICAL -> Color(0xFFDC2626)
        },
        label = "thermalAccentColor"
    )

    val providerAccent = if (isCloudRecommended) CloudFlashCobalt else LocalNanoEmerald
    val activeProviderLabel = when {
        selectedHybridMode == MoteInferenceMode.ONLY_IN_CLOUD -> "Cloud Only"
        selectedHybridMode == MoteInferenceMode.ONLY_ON_DEVICE -> "On-Device Only"
        adaptiveEnabled && health.recommendedTarget == AdaptiveInferenceTarget.CLOUD_GEMINI -> "Cloud (Adaptive)"
        aiCoreStatus.state == AiCoreAvailabilityState.AVAILABLE &&
            selectedHybridMode == MoteInferenceMode.PREFER_ON_DEVICE -> "On-Device Nano"
        else -> "Hybrid -> Cloud"
    }

    // Normalize 20°C..50°C onto 0f..1f for the visual thermal gauge
    val tempFraction = ((health.temperatureC - 20f) / 30f).coerceIn(0.06f, 1f)
    val tempDisplay = String.format(Locale.US, "%.1f°C", health.temperatureC)
    val throttlingLabel = when (health.thermalLevel) {
        ThermalHealthLevel.COOL -> "None (Nominal)"
        ThermalHealthLevel.WARM -> "Light Warmth"
        ThermalHealthLevel.MODERATE -> "Throttling (Cloud)"
        ThermalHealthLevel.HOT -> "High Throttle"
        ThermalHealthLevel.CRITICAL -> "Critical Throttle"
    }

    MoteCard(
        background = tokens.surface,
        modifier = modifier
            .border(1.dp, providerAccent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .testTag("system_health_inference_dashboard")
    ) {
        // 1. Header Row with Active Provider Pill & Refresh Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(providerAccent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCloudRecommended) Icons.Outlined.Cloud else Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = providerAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "System Health & Inference",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Adaptive thermal, battery & hybrid provider monitor",
                        fontSize = 11.sp,
                        color = tokens.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(providerAccent.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = activeProviderLabel,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = providerAccent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("refresh_health_dashboard_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh system health",
                        tint = tokens.onSurfaceSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. Three-Tile Real-Time Telemetry Strip (Battery Temp, Thermal Throttling, Battery/Power)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HealthMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.DeviceThermostat,
                accent = thermalColor,
                label = "BATTERY TEMP",
                value = tempDisplay,
                sublabel = if (health.temperatureC >= 40f) "≥40°C Offload" else "Limit 40.0°C"
            )
            HealthMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Speed,
                accent = thermalColor,
                label = "THROTTLING",
                value = health.thermalLevel.label,
                sublabel = throttlingLabel
            )
            HealthMetricTile(
                modifier = Modifier.weight(1f),
                icon = if (health.isCharging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd,
                accent = when {
                    health.isCharging -> LocalNanoEmerald
                    health.batteryPct <= 25 || health.isPowerSaveMode -> Color(0xFFDC2626)
                    else -> CloudFlashCobalt
                },
                label = "POWER",
                value = "${health.batteryPct}%",
                sublabel = when {
                    health.isPowerSaveMode -> "Power Saver"
                    health.isCharging -> "Charging"
                    batteryTelemetry.dischargeMa > 0 -> "${batteryTelemetry.dischargeMa} mA"
                    else -> "Unplugged"
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // 3. Visual Thermal Headroom Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(tokens.canvas)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Thermal Headroom Gauge",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface
                )
                Text(
                    text = "$tempDisplay · ${health.thermalLevel.label}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = thermalColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
            LinearProgressIndicator(
                progress = { tempFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = thermalColor,
                trackColor = tokens.border
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("20°C Cool", fontSize = 9.5.sp, color = tokens.onSurfaceSecondary)
                Text("40°C Cloud Offload", fontSize = 9.5.sp, color = Color(0xFFD97706), fontWeight = FontWeight.SemiBold)
                Text("50°C", fontSize = 9.5.sp, color = tokens.onSurfaceSecondary)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 4. Adaptive Switching Explanation Box (WHY the adaptive engine chose On-Device vs Cloud)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(providerAccent.copy(alpha = 0.08f))
                .border(0.75.dp, providerAccent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ADAPTIVE ENGINE DECISION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = providerAccent
                )
                Text(
                    text = if (isCloudRecommended) "Provider: Cloud Gemini" else "Provider: On-Device Nano",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = providerAccent,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                text = lastRouteNote?.takeIf { it.isNotBlank() } ?: health.recommendationReason,
                fontSize = 11.5.sp,
                color = tokens.onSurface,
                lineHeight = 16.sp
            )
            Text(
                text = "Policy: On-device Gemini Nano runs when cool (<40°C) & >25% battery; auto-offloads to Cloud Gemini on thermal throttling, Power Saver, low battery, or unsupported hardware.",
                fontSize = 10.5.sp,
                color = tokens.onSurfaceSecondary,
                lineHeight = 14.5.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        // 5. Adaptive Switch + Quick Inference Mode Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "Auto Thermal & Battery Offload",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface
                )
                Text(
                    text = if (adaptiveEnabled) {
                        "Dynamically adjusts Firebase OnDeviceConfig under heat or low battery"
                    } else {
                        "Manual override — strictly follows selected InferenceMode below"
                    },
                    fontSize = 10.5.sp,
                    color = tokens.onSurfaceSecondary,
                    lineHeight = 14.sp
                )
            }
            Switch(
                checked = adaptiveEnabled,
                onCheckedChange = onToggleAdaptive,
                modifier = Modifier.testTag("dashboard_adaptive_routing_switch")
            )
        }

        Spacer(Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                MoteInferenceMode.PREFER_ON_DEVICE to "Prefer On-Device",
                MoteInferenceMode.PREFER_IN_CLOUD to "Prefer Cloud",
                MoteInferenceMode.ONLY_IN_CLOUD to "Only Cloud",
                MoteInferenceMode.ONLY_ON_DEVICE to "Only On-Device"
            ).forEach { (mode, label) ->
                val active = selectedHybridMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) tokens.primaryActionBg else tokens.canvas)
                        .border(
                            1.dp,
                            if (active) tokens.primaryActionBg else tokens.border,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onSelectHybridMode(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("health_dash_mode_${mode.name.lowercase()}")
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) Color.White else tokens.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 6. Live EventObserver OS Verification Feed (BroadcastReceiver & ContentObserver)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(tokens.canvas)
                .border(0.75.dp, tokens.border, RoundedCornerShape(11.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sensors,
                        contentDescription = null,
                        tint = LocalNanoEmerald,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "ToolSync EventObserver",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF059669).copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE HOOKS",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (observedEvents.isEmpty()) {
                Text(
                    text = "Listening to AlarmManager, AudioManager, NotificationManager (DND), Settings.System (Brightness), CalendarContract & ClipboardManager to confirm OS actions before updating the GenUI log.",
                    fontSize = 10.5.sp,
                    color = tokens.onSurfaceSecondary,
                    lineHeight = 14.5.sp
                )
            } else {
                observedEvents.take(3).forEach { ev ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = null,
                            tint = LocalNanoEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "[${ev.mechanism}] ${ev.detail}",
                            fontSize = 10.5.sp,
                            color = tokens.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = timeFormatter.format(Date(ev.timestampMs)),
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = tokens.onSurfaceSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthMetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    label: String,
    value: String,
    sublabel: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.canvas)
            .border(0.75.dp, tokens.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = tokens.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sublabel,
            fontSize = 9.5.sp,
            color = accent,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

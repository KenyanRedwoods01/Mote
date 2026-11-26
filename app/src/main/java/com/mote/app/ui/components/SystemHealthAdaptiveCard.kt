package com.mote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.control.AdaptiveInferenceTarget
import com.mote.app.control.SystemHealthSnapshot
import com.mote.app.control.ThermalHealthLevel
import com.mote.app.ui.theme.LocalMoteColors
import java.util.Locale

/**
 * Surfaces live Android SystemHealthManager telemetry (Battery %, Battery Temp °C,
 * Charging / Power Saver state, Android Thermal Headroom) alongside the Battery- &
 * Thermal-Aware Adaptive Inference toggle that automatically switches between
 * Local On-Device LiteRT-LM and Cloud Gemini.
 */
@Composable
fun SystemHealthAdaptiveCard(
    health: SystemHealthSnapshot,
    adaptiveEnabled: Boolean,
    lastRouteNote: String?,
    onToggleAdaptive: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    val thermalAccent = when (health.thermalLevel) {
        ThermalHealthLevel.COOL -> LocalNanoEmerald
        ThermalHealthLevel.WARM -> CloudFlashCobalt
        ThermalHealthLevel.MODERATE -> Color(0xFFD97706)
        ThermalHealthLevel.HOT, ThermalHealthLevel.CRITICAL -> Color(0xFFDC2626)
    }
    val batteryAccent = when {
        health.isCharging -> LocalNanoEmerald
        health.batteryPct <= 25 -> Color(0xFFDC2626)
        health.batteryPct <= 45 -> Color(0xFFD97706)
        else -> LocalNanoEmerald
    }
    val routeAccent = if (health.recommendedTarget == AdaptiveInferenceTarget.LOCAL_ON_DEVICE) {
        LocalNanoEmerald
    } else {
        CloudFlashCobalt
    }

    MoteCard(
        background = tokens.surface,
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (adaptiveEnabled) routeAccent.copy(alpha = 0.35f) else tokens.border,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("system_health_adaptive_card")
    ) {
        // Header + Adaptive Inference Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(routeAccent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BatterySaver,
                        contentDescription = null,
                        tint = routeAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "SYSTEM HEALTH · ADAPTIVE AI",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = routeAccent
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (adaptiveEnabled) routeAccent.copy(alpha = 0.16f) else tokens.surfaceSubtle
                                )
                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = if (adaptiveEnabled) "AUTO" else "MANUAL",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (adaptiveEnabled) routeAccent else SlateMuted
                            )
                        }
                    }
                    Text(
                        text = "Battery & Thermal Adaptive Inference",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy
                    )
                }
            }
            IosSpringSwitch(
                checked = adaptiveEnabled,
                onCheckedChange = onToggleAdaptive,
                modifier = Modifier.testTag("adaptive_inference_switch")
            )
        }

        Spacer(Modifier.height(10.dp))

        // Live 3-Column Android Telemetry Tiles: Battery %, Battery Temp °C, Active Route
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HealthMetricTile(
                icon = if (health.isCharging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd,
                iconTint = batteryAccent,
                label = "Battery",
                value = "${health.batteryPct}%",
                sub = when {
                    health.isCharging -> "Charging"
                    health.isPowerSaveMode -> "Power Saver"
                    else -> "Unplugged"
                },
                modifier = Modifier.weight(1f)
            )
            HealthMetricTile(
                icon = Icons.Outlined.DeviceThermostat,
                iconTint = thermalAccent,
                label = "Thermal",
                value = String.format(Locale.US, "%.1f°C", health.temperatureC),
                sub = health.thermalLevel.label,
                modifier = Modifier.weight(1f)
            )
            HealthMetricTile(
                icon = if (health.recommendedTarget == AdaptiveInferenceTarget.LOCAL_ON_DEVICE) {
                    Icons.Outlined.Memory
                } else {
                    Icons.Outlined.Cloud
                },
                iconTint = routeAccent,
                label = "Auto Route",
                value = if (health.recommendedTarget == AdaptiveInferenceTarget.LOCAL_ON_DEVICE) "Local" else "Cloud",
                sub = if (health.recommendedTarget == AdaptiveInferenceTarget.LOCAL_ON_DEVICE) "LiteRT-LM" else "Gemini",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Recommendation / Last Routing Explanation Strip + Refresh Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MoteSurfaceSubtle)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = lastRouteNote?.takeIf { adaptiveEnabled } ?: health.recommendationReason,
                fontSize = 11.sp,
                color = SlateMuted,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("refresh_system_health_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh battery and thermal status",
                    tint = routeAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HealthMetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(MoteSurfaceSubtle)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = SlateMuted
            )
        }
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = MoteNavy
        )
        Text(
            text = sub,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = iconTint,
            maxLines = 1
        )
    }
}

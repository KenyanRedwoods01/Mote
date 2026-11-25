package com.mote.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.LlmConfig
import com.mote.app.ai.LocalHardwareBackend
import com.mote.app.control.BatteryAlertSeverity
import com.mote.app.control.BatteryTelemetrySnapshot
import com.mote.app.control.ThermalStateLevel
import com.mote.app.data.DeviceHardwareSnapshot
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelEntry
import com.mote.app.data.db.ModelBenchmarkEntity
import com.mote.app.data.db.ModelCapability
import com.mote.app.data.db.ModelStudioPrefsEntity
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import java.util.Locale
import kotlin.math.roundToInt

enum class ModelHubScope(val label: String) {
    ON_DEVICE("On-Device Phone"),
    DOWNLOADED("Downloaded"),
    CLOUD_API("Cloud API")
}

enum class ModelSizeFilter(val label: String) {
    ANY("Any Size"),
    UNDER_600MB("≤ 600 MB"),
    FITS_MY_RAM("Fits My RAM")
}

enum class ModelSortOrder(val label: String) {
    SMALLEST("Smallest"),
    LARGEST("Largest"),
    NAME("Name A–Z"),
    RECENT("Recent")
}

@Composable
fun ActiveRuntimeStatusCard(
    loadedLocalModel: LoadedLocalModelState?,
    preferLoadedLocalChat: Boolean,
    selectedCloudModelName: String,
    isBenchmarking: Boolean,
    onToggleOfflineChat: (Boolean) -> Unit,
    onBenchmark: () -> Unit,
    onUnload: () -> Unit
) {
    if (loadedLocalModel != null) {
        MoteCard(background = MoteBrandNavy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LobeModelAvatar(brand = loadedLocalModel.brand, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE ON-DEVICE · RUNNING IN RAM (${loadedLocalModel.backend.label})",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = LocalNanoEmerald
                    )
                    Text(
                        text = loadedLocalModel.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val shaSuffix = loadedLocalModel.sha256Prefix?.let { " · SHA $it" }.orEmpty()
                    Text(
                        text = "${formatModelBytes(loadedLocalModel.fileSizeBytes)}$shaSuffix · Room verified",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                TextButton(
                    onClick = onUnload,
                    modifier = Modifier.testTag("unload_local_model_button")
                ) {
                    Text("Unload", color = Color(0xFFFCA5A5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Route Chat Offline (On-Device)",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (preferLoadedLocalChat) "Active in Chat — zero cloud tokens" else "Paused — Chat currently uses Cloud API",
                        fontSize = 10.5.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                IosSpringSwitch(
                    checked = preferLoadedLocalChat,
                    onCheckedChange = onToggleOfflineChat,
                    modifier = Modifier.testTag("toggle_offline_local_chat_switch")
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBenchmark,
                enabled = !isBenchmarking,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .testTag("benchmark_loaded_model_button")
            ) {
                if (isBenchmarking) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = LocalNanoEmerald, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running on-device tok/s benchmark...", fontSize = 12.sp, color = Color.White)
                } else {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Benchmark Loaded Model (Live TTFT & tok/s)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    } else {
        MoteCard(background = Color(0xFF1E293B)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(CloudFlashCobalt.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = Color(0xFF93C5FD), modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NO PHONE MODEL IN RAM · CLOUD API ACTIVE",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF93C5FD)
                    )
                    Text(
                        text = selectedCloudModelName,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Cloud models stream via Firebase AI Logic (no download). Download & load a LiteRT-LM model below to run offline on your phone.",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun BatteryResourceMonitorCard(
    telemetry: BatteryTelemetrySnapshot,
    currentBackend: LocalHardwareBackend,
    onRefresh: () -> Unit,
    onSwitchToCpu: () -> Unit,
    onUnloadModel: () -> Unit
) {
    val isExcessive = telemetry.alertSeverity == BatteryAlertSeverity.EXCESSIVE
    val isElevated = telemetry.alertSeverity == BatteryAlertSeverity.ELEVATED
    val borderColor = when (telemetry.alertSeverity) {
        BatteryAlertSeverity.EXCESSIVE -> Color(0xFFDC2626)
        BatteryAlertSeverity.ELEVATED -> Color(0xFFD97706)
        BatteryAlertSeverity.NORMAL -> Color(0x160F172A)
    }
    val cardBg = when (telemetry.alertSeverity) {
        BatteryAlertSeverity.EXCESSIVE -> Color(0xFFFEF2F2)
        BatteryAlertSeverity.ELEVATED -> Color(0xFFFFFBEB)
        BatteryAlertSeverity.NORMAL -> Color.White
    }
    val thermalColor = when (telemetry.thermalLevel) {
        ThermalStateLevel.NORMAL -> LocalNanoEmerald
        ThermalStateLevel.LIGHT -> CloudFlashCobalt
        ThermalStateLevel.MODERATE -> Color(0xFFD97706)
        ThermalStateLevel.SEVERE, ThermalStateLevel.CRITICAL -> Color(0xFFDC2626)
    }

    MoteCard(
        background = cardBg,
        modifier = Modifier
            .border(
                width = if (isExcessive || isElevated) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("battery_resource_monitor_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isExcessive || isElevated) Icons.Outlined.WarningAmber else Icons.Outlined.BatteryChargingFull,
                    contentDescription = null,
                    tint = if (isExcessive) Color(0xFFDC2626) else if (isElevated) Color(0xFFD97706) else LocalNanoEmerald,
                    modifier = Modifier.size(19.dp)
                )
                Column {
                    Text(
                        text = "BATTERY & THERMAL MONITOR",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isExcessive) Color(0xFFB91C1C) else MoteNavy
                    )
                    val activeLine = when {
                        telemetry.activeModelName != null && telemetry.isInferenceActive ->
                            "Active Inference: ${telemetry.activeModelName}"
                        telemetry.activeModelName != null ->
                            "Loaded Idle: ${telemetry.activeModelName}"
                        else -> "No local model running · Standby"
                    }
                    Text(
                        text = activeLine,
                        fontSize = 11.sp,
                        fontWeight = if (telemetry.isInferenceActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (telemetry.isInferenceActive) CloudFlashCobalt else SlateMuted
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp).testTag("refresh_battery_monitor_button")
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh battery telemetry", tint = SlateMuted, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryStatTile(
                label = "BATTERY",
                value = "${telemetry.batteryPercent}%",
                sub = if (telemetry.isCharging) "Charging" else if (telemetry.sessionBatteryDropPct > 0) "-${telemetry.sessionBatteryDropPct}% session" else "Discharging",
                modifier = Modifier.weight(1f)
            )
            TelemetryStatTile(
                label = "POWER DRAW",
                value = if (telemetry.isCharging) {
                    "Plugged"
                } else if (telemetry.hasCurrentSensor && telemetry.dischargeMa > 0) {
                    "${telemetry.dischargeMa} mA"
                } else {
                    "Idle"
                },
                sub = if (telemetry.powerWatts > 0.05f) String.format(Locale.US, "%.1f W draw", telemetry.powerWatts) else "${telemetry.voltageMv} mV",
                modifier = Modifier.weight(1f)
            )
            TelemetryStatTile(
                label = "THERMAL",
                value = if (telemetry.temperatureC > 0f) String.format(Locale.US, "%.1f°C", telemetry.temperatureC) else "N/A",
                sub = telemetry.thermalLevel.label,
                modifier = Modifier.weight(1f),
                subColor = thermalColor
            )
        }

        if ((isExcessive || isElevated) && telemetry.alertTitle != null && telemetry.alertReason != null) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isExcessive) Color(0xFFFEE2E2) else Color(0xFFFEF3C7))
                    .padding(10.dp)
                    .testTag("battery_excessive_resource_alert"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = telemetry.alertTitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isExcessive) Color(0xFF991B1B) else Color(0xFF92400E)
                )
                Text(
                    text = telemetry.alertReason,
                    fontSize = 11.sp,
                    color = if (isExcessive) Color(0xFF7F1D1D) else Color(0xFF78350F),
                    lineHeight = 15.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (currentBackend == LocalHardwareBackend.GPU) {
                        OutlinedButton(
                            onClick = onSwitchToCpu,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("alert_switch_cpu_button")
                        ) {
                            Text("Switch to CPU", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                        }
                    }
                    Button(
                        onClick = onUnloadModel,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("alert_unload_model_button")
                    ) {
                        Text("Unload Model", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EcoInferenceCard(
    ecoInferenceEnabled: Boolean,
    ecoInferenceRestricted: Boolean,
    telemetry: BatteryTelemetrySnapshot,
    onToggleEcoInference: (Boolean) -> Unit
) {
    val cardBg = when {
        ecoInferenceRestricted -> Color(0xFFFFFBEB)
        ecoInferenceEnabled -> Color(0xFFF0FDF4)
        else -> Color.White
    }
    val borderColor = when {
        ecoInferenceRestricted -> Color(0xFFD97706)
        ecoInferenceEnabled -> LocalNanoEmerald.copy(alpha = 0.4f)
        else -> Color(0x160F172A)
    }
    val statusBadgeText = when {
        ecoInferenceRestricted -> "GUARD ACTIVE · ${telemetry.batteryPercent}% BATTERY"
        ecoInferenceEnabled && telemetry.isCharging -> "STANDBY · PLUGGED IN (${telemetry.batteryPercent}%)"
        ecoInferenceEnabled -> "ARMED · ${telemetry.batteryPercent}% BATTERY"
        else -> "DISABLED"
    }
    val statusBadgeBg = when {
        ecoInferenceRestricted -> Color(0xFFFEF3C7)
        ecoInferenceEnabled -> Color(0xFFDCFCE7)
        else -> Color(0xFFF1F5F9)
    }
    val statusBadgeFg = when {
        ecoInferenceRestricted -> Color(0xFFB45309)
        ecoInferenceEnabled -> Color(0xFF15803D)
        else -> SlateMuted
    }

    MoteCard(
        background = cardBg,
        modifier = Modifier
            .border(
                width = if (ecoInferenceRestricted) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("eco_inference_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (ecoInferenceRestricted) Color(0xFFFEF3C7)
                            else if (ecoInferenceEnabled) Color(0xFFDCFCE7)
                            else Color(0xFFF1F5F9)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EnergySavingsLeaf,
                        contentDescription = null,
                        tint = if (ecoInferenceRestricted) Color(0xFFD97706) else if (ecoInferenceEnabled) LocalNanoEmerald else SlateMuted,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Eco-Inference",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MoteNavy
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(statusBadgeBg)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = statusBadgeText,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusBadgeFg
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (ecoInferenceRestricted) {
                            "Battery is below 20% while unplugged. Background WorkManager downloads, benchmarks, and idle local RAM models are restricted until plugged in or above 20%."
                        } else if (ecoInferenceEnabled) {
                            "Automatically pauses background downloads, blocks benchmarks, and unloads idle phone models when battery drops below 20%."
                        } else {
                            "Off — background downloads and local inference can continue below 20% battery."
                        },
                        fontSize = 11.sp,
                        color = if (ecoInferenceRestricted) Color(0xFF92400E) else SlateMuted,
                        lineHeight = 15.sp
                    )
                }
            }
            IosSpringSwitch(
                checked = ecoInferenceEnabled,
                onCheckedChange = onToggleEcoInference,
                modifier = Modifier.testTag("eco_inference_toggle_switch")
            )
        }
    }
}

@Composable
fun LobeModelMetadataGalleryCard(
    models: List<LocalModelEntry>,
    loadedLocalModel: LoadedLocalModelState?,
    benchmarksByModel: Map<String, List<ModelBenchmarkEntity>>,
    onSelectModel: (String) -> Unit
) {
    if (models.isEmpty()) return

    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.dp, Color(0x140F172A), RoundedCornerShape(14.dp))
            .testTag("lobe_model_metadata_gallery")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LOCAL MODEL DIRECTORY · LOBE PROFILES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CloudFlashCobalt
                )
                Text(
                    text = "Visual metadata & hardware footprint across ${models.size} phone models",
                    fontSize = 11.5.sp,
                    color = SlateMuted
                )
            }
            Icon(
                imageVector = Icons.Outlined.AutoAwesomeMosaic,
                contentDescription = null,
                tint = CloudFlashCobalt,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            models.forEach { entry ->
                val isLoaded = loadedLocalModel?.modelId == entry.id
                val latestBench = benchmarksByModel[entry.id]?.firstOrNull()
                val accent = when (entry.providerBrand) {
                    LobeModelBrand.GEMMA -> Color(0xFF0284C7)
                    LobeModelBrand.QWEN -> Color(0xFF6D28D9)
                    LobeModelBrand.DEEPSEEK -> Color(0xFF1D4ED8)
                    LobeModelBrand.HUGGINGFACE -> Color(0xFFD97706)
                    LobeModelBrand.LLAMA -> Color(0xFF0668E1)
                    LobeModelBrand.MISTRAL -> Color(0xFFEA580C)
                    else -> MoteNavy
                }
                val (dotColor, statusShort) = when {
                    isLoaded -> LocalNanoEmerald to "In RAM"
                    entry.status == LocalModelDownloadStatus.DOWNLOADED -> CloudFlashCobalt to "On Disk"
                    entry.status == LocalModelDownloadStatus.DOWNLOADING -> Color(0xFF2563EB) to "${(entry.progressFraction * 100f).roundToInt()}%"
                    entry.status == LocalModelDownloadStatus.QUEUED -> Color(0xFFD97706) to "Queued"
                    else -> Color(0xFF94A3B8) to "Catalog"
                }

                Column(
                    modifier = Modifier
                        .width(188.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isLoaded) Color(0xFFECFDF5) else Color(0xFFF8FAFC))
                        .border(
                            width = if (isLoaded) 1.5.dp else 1.dp,
                            color = if (isLoaded) LocalNanoEmerald else accent.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectModel(entry.id) }
                        .padding(11.dp)
                        .testTag("lobe_metadata_tile_${entry.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LobeModelAvatar(brand = entry.providerBrand, size = 44.dp)
                        Column(horizontalAlignment = Alignment.End) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(accent.copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = entry.providerBrand.label,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Text(
                                    text = statusShort,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = dotColor
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = entry.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.family} · ${entry.parameterCount} · ${entry.quantLabel}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        SpecPill(text = formatApproxSize(entry.approxSizeMb), bg = Color.White, fg = MoteNavy)
                        SpecPill(text = "${entry.minRamMb / 1024}GB RAM", bg = Color.White, fg = SlateMuted)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${entry.contextLengthTokens} ctx",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateMuted
                        )
                        Text(
                            text = if (latestBench != null) {
                                String.format(Locale.US, "⚡ %.1f tok/s", latestBench.tokensPerSec)
                            } else {
                                "Tap specs →"
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (latestBench != null) LocalNanoEmerald else accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveWorkManagerDownloadsCard(
    activeDownloads: List<LocalModelEntry>,
    onCancel: (String) -> Unit
) {
    if (activeDownloads.isEmpty()) return
    MoteCard(
        background = Color(0xFFEFF6FF),
        modifier = Modifier
            .border(1.dp, CloudFlashCobalt.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .testTag("workmanager_active_downloads_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = CloudFlashCobalt,
                strokeWidth = 2.dp
            )
            Text(
                text = "BACKGROUND DOWNLOADS (WORKMANAGER · ${activeDownloads.size})",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = CloudFlashCobalt
            )
        }
        Spacer(Modifier.height(8.dp))
        activeDownloads.forEach { dl ->
            val pct = (dl.progressFraction * 100f).roundToInt()
            val speedMb = dl.speedBytesPerSec / (1024f * 1024f)
            val etaStr = dl.etaSeconds?.let { " · ETA ${formatEta(it)}" }.orEmpty()
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dl.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onCancel(dl.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text("Cancel", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDC2626))
                    }
                }
                if (dl.status == LocalModelDownloadStatus.QUEUED) {
                    LinearProgressIndicator(
                        color = CloudFlashCobalt,
                        trackColor = Color(0xFFDBEAFE),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text("Queued in WorkManager (waiting for network/battery constraints)...", fontSize = 11.sp, color = SlateMuted)
                } else {
                    LinearProgressIndicator(
                        progress = { dl.progressFraction },
                        color = CloudFlashCobalt,
                        trackColor = Color(0xFFDBEAFE),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "%d%% · %s / %s · %.1f MB/s%s",
                            pct,
                            formatModelBytes(dl.downloadedBytes),
                            formatModelBytes(dl.totalBytes),
                            speedMb,
                            etaStr
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CloudFlashCobalt
                    )
                }
            }
        }
    }
}

@Composable
fun ModelAdvancedFilterPanel(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedScope: ModelHubScope,
    onScopeChange: (ModelHubScope) -> Unit,
    downloadedCount: Int,
    selectedCapability: ModelCapability?,
    onCapabilityChange: (ModelCapability?) -> Unit,
    sizeFilter: ModelSizeFilter,
    onSizeFilterChange: (ModelSizeFilter) -> Unit,
    sortOrder: ModelSortOrder,
    onSortOrderChange: (ModelSortOrder) -> Unit
) {
    MoteCard {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search phone models, families, capabilities...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = SlateMuted) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_hub_search_input")
        )

        Spacer(Modifier.height(10.dp))

        // Primary scope chips (On-Device Phone vs Downloaded vs Cloud API)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelHubScope.entries.forEach { scope ->
                val label = if (scope == ModelHubScope.DOWNLOADED) "${scope.label} ($downloadedCount)" else scope.label
                FilterChip(
                    selected = selectedScope == scope,
                    onClick = { onScopeChange(scope) },
                    label = { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.testTag("model_scope_${scope.name.lowercase()}")
                )
            }
        }

        if (selectedScope != ModelHubScope.CLOUD_API) {
            Spacer(Modifier.height(6.dp))
            Text("CAPABILITY FILTER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCapability == null,
                    onClick = { onCapabilityChange(null) },
                    label = { Text("All", fontSize = 11.sp) }
                )
                listOf(
                    ModelCapability.ULTRA_FAST,
                    ModelCapability.CODING,
                    ModelCapability.REASONING,
                    ModelCapability.MULTILINGUAL,
                    ModelCapability.MOBILE_ACTIONS,
                    ModelCapability.LONG_CONTEXT
                ).forEach { cap ->
                    FilterChip(
                        selected = selectedCapability == cap,
                        onClick = { onCapabilityChange(if (selectedCapability == cap) null else cap) },
                        label = { Text(cap.label, fontSize = 11.sp) },
                        modifier = Modifier.testTag("cap_filter_${cap.name.lowercase()}")
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModelSizeFilter.entries.forEach { sf ->
                    FilterChip(
                        selected = sizeFilter == sf,
                        onClick = { onSizeFilterChange(sf) },
                        label = { Text(sf.label, fontSize = 11.sp) },
                        modifier = Modifier.testTag("size_filter_${sf.name.lowercase()}")
                    )
                }
                VerticalDivider(modifier = Modifier.height(20.dp))
                ModelSortOrder.entries.forEach { sort ->
                    FilterChip(
                        selected = sortOrder == sort,
                        onClick = { onSortOrderChange(sort) },
                        label = { Text("Sort: ${sort.label}", fontSize = 11.sp) },
                        modifier = Modifier.testTag("sort_${sort.name.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneModelCard(
    entry: LocalModelEntry,
    hardware: DeviceHardwareSnapshot,
    latestBenchmark: ModelBenchmarkEntity?,
    isLoaded: Boolean,
    isLoading: Boolean,
    onOpenDetails: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    onChatWithModel: () -> Unit = onLoad,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    val fitsRam = hardware.totalRamMb == 0L || hardware.totalRamMb >= entry.minRamMb

    val (statusLabel, statusBg, statusFg) = when {
        isLoaded -> Triple("ACTIVE IN RAM", Color(0xFFDCFCE7), Color(0xFF15803D))
        entry.status == LocalModelDownloadStatus.DOWNLOADING -> Triple(
            "DOWNLOADING ${(entry.progressFraction * 100f).roundToInt()}%",
            Color(0xFFDBEAFE),
            CloudFlashCobalt
        )
        entry.status == LocalModelDownloadStatus.QUEUED -> Triple("QUEUED", Color(0xFFFEF3C7), Color(0xFFB45309))
        entry.status == LocalModelDownloadStatus.DOWNLOADED -> Triple("DOWNLOADED · INACTIVE", Color(0xFFEFF6FF), CloudFlashCobalt)
        entry.status == LocalModelDownloadStatus.ERROR -> Triple("DOWNLOAD FAILED", Color(0xFFFEE2E2), Color(0xFFB91C1C))
        else -> Triple("NOT DOWNLOADED", Color(0xFFF1F5F9), SlateMuted)
    }

    MoteCard(
        modifier = Modifier
            .border(
                width = if (isLoaded) 1.5.dp else 0.dp,
                color = if (isLoaded) LocalNanoEmerald else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onOpenDetails)
            .testTag("local_model_card_${entry.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            LobeModelAvatar(brand = entry.providerBrand, size = 42.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.displayName,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(statusLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = statusFg)
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Size, RAM requirement, Quantization, Context metadata row
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpecPill(text = formatApproxSize(entry.approxSizeMb), bg = Color(0xFFF1F5F9), fg = MoteNavy)
                    SpecPill(text = "${entry.parameterCount} · ${entry.quantLabel}", bg = Color(0xFFF1F5F9), fg = SlateMuted)
                    SpecPill(
                        text = if (fitsRam) "Min ${entry.minRamMb / 1024}GB RAM · Fits" else "Needs ${entry.minRamMb / 1024}GB RAM",
                        bg = if (fitsRam) Color(0xFFECFDF5) else Color(0xFFFFFBEB),
                        fg = if (fitsRam) LocalNanoEmerald else Color(0xFFB45309)
                    )
                    SpecPill(text = "${entry.contextLengthTokens} ctx", bg = Color(0xFFF8FAFC), fg = SlateMuted)
                }

                Spacer(Modifier.height(5.dp))
                Text(
                    text = entry.summary,
                    fontSize = 11.5.sp,
                    color = SlateMuted,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Capabilities chips
                if (entry.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        entry.capabilities.forEach { cap ->
                            SpecPill(text = cap.label, bg = Color(0xFFEFF6FF), fg = CloudFlashCobalt)
                        }
                    }
                }

                // Verified on-disk metadata & latest hardware benchmark badge
                if (entry.status == LocalModelDownloadStatus.DOWNLOADED && entry.localFilePath != null) {
                    Spacer(Modifier.height(6.dp))
                    val shaText = entry.sha256Prefix?.let { " · SHA $it" }.orEmpty()
                    Text(
                        text = "Verified in Room · ${formatModelBytes(entry.downloadedBytes)}$shaText",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalNanoEmerald
                    )
                    if (entry.updateAvailable || !entry.updateStatusNote.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = entry.updateStatusNote ?: "Newer model version available",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (entry.updateAvailable) Color(0xFFD97706) else SlateMuted
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (latestBenchmark != null) {
                        SpecPill(
                            text = String.format(
                                Locale.US,
                                "⚡ %.1f tok/s · TTFT %dms (%s)",
                                latestBenchmark.tokensPerSec,
                                latestBenchmark.ttftMs,
                                latestBenchmark.backendUsed
                            ),
                            bg = Color(0xFFECFDF5),
                            fg = Color(0xFF047857)
                        )
                    } else {
                        Text(
                            text = "Tap for specs & tok/s benchmark",
                            fontSize = 10.5.sp,
                            color = SlateMuted
                        )
                    }
                    TextButton(
                        onClick = onOpenDetails,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 34.dp).testTag("open_model_details_${entry.id}")
                    ) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(14.dp), tint = CloudFlashCobalt)
                        Spacer(Modifier.width(4.dp))
                        Text("Details", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = CloudFlashCobalt)
                    }
                }
            }
        }

        // WorkManager live download progress indicator
        if (entry.status == LocalModelDownloadStatus.DOWNLOADING || entry.status == LocalModelDownloadStatus.QUEUED) {
            Spacer(Modifier.height(10.dp))
            if (entry.status == LocalModelDownloadStatus.QUEUED) {
                LinearProgressIndicator(
                    color = CloudFlashCobalt,
                    trackColor = Color(0xFFE2E8F0),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
            } else {
                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    color = CloudFlashCobalt,
                    trackColor = Color(0xFFE2E8F0),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pct = (entry.progressFraction * 100f).roundToInt()
                val speedMb = entry.speedBytesPerSec / (1024f * 1024f)
                val etaStr = entry.etaSeconds?.let { " · ${formatEta(it)}" }.orEmpty()
                Text(
                    text = if (entry.status == LocalModelDownloadStatus.QUEUED) {
                        "Queued in WorkManager..."
                    } else {
                        String.format(
                            Locale.US,
                            "%d%% · %s / %s · %.1f MB/s%s",
                            pct,
                            formatModelBytes(entry.downloadedBytes),
                            formatModelBytes(entry.totalBytes),
                            speedMb,
                            etaStr
                        )
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CloudFlashCobalt
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel_model_${entry.id}")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFDC2626))
                    Spacer(Modifier.width(3.dp))
                    Text("Cancel", fontSize = 11.5.sp, color = Color(0xFFDC2626))
                }
            }
        } else {
            entry.errorMessage?.let { err ->
                Spacer(Modifier.height(6.dp))
                Text(err, fontSize = 11.sp, color = Color(0xFFB91C1C))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (entry.status) {
                    LocalModelDownloadStatus.DOWNLOADED -> {
                        Button(
                            onClick = onChatWithModel,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLoaded) LocalNanoEmerald else LocalMoteColors.current.primaryActionBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("load_model_${entry.id}")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Loading into RAM...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            } else {
                                Icon(
                                    imageVector = if (isLoaded) Icons.Outlined.Chat else Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isLoaded) "Active · Chat Now" else "Load & Chat Offline",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                        if (isLoaded) {
                            OutlinedButton(
                                onClick = onUnload,
                                enabled = !isLoading,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("unload_model_${entry.id}")
                            ) {
                                Text("Unload", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                            }
                        }
                        IconButton(
                            onClick = onDelete,
                            enabled = !isLoading,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_model_${entry.id}")
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete model", tint = Color(0xFFDC2626))
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onDownload,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .testTag("download_model_${entry.id}")
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = MoteNavy)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (entry.status == LocalModelDownloadStatus.ERROR) {
                                    "Retry Background Download (${formatApproxSize(entry.approxSizeMb)})"
                                } else {
                                    "Download for Phone (${formatApproxSize(entry.approxSizeMb)})"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MoteNavy
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelUtilitiesCard(
    hardware: DeviceHardwareSnapshot,
    utilityStatus: String?,
    unusedDownloadedCount: Int = 0,
    unusedDiskBytes: Long = 0L,
    isCheckingUpdates: Boolean = false,
    lastUpdateSummary: String? = null,
    onDismissStatus: () -> Unit,
    onVerifyFiles: () -> Unit,
    onCheckUpdatesNow: () -> Unit = {},
    onClearCache: () -> Unit,
    onClearModelCache: () -> Unit = {},
    onRefreshHardware: () -> Unit
) {
    val tokens = LocalMoteColors.current
    var showClearModelCacheDialog by remember { mutableStateOf(false) }

    MoteCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Build, contentDescription = null, tint = tokens.accentBlue, modifier = Modifier.size(18.dp))
                Text("STORAGE & DIAGNOSTIC UTILITIES", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
            }
            IconButton(onClick = onRefreshHardware, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh telemetry", tint = SlateMuted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryStatTile(
                label = "FREE RAM",
                value = "${hardware.availableRamMb} MB",
                sub = "of ${hardware.totalRamMb} MB",
                modifier = Modifier.weight(1f)
            )
            TelemetryStatTile(
                label = "MODELS DISK",
                value = formatModelBytes(hardware.modelsDiskUsageBytes),
                sub = "${hardware.freeStorageMb / 1024}GB free",
                modifier = Modifier.weight(1f)
            )
            TelemetryStatTile(
                label = "CPU / TEMP",
                value = "${hardware.cpuCores} cores",
                sub = "${formatModelBytes(hardware.tempCacheBytes)} temp",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onVerifyFiles,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("verify_room_storage_button")
            ) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp), tint = MoteNavy)
                Spacer(Modifier.width(4.dp))
                Text("Verify Room", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
            OutlinedButton(
                onClick = onClearCache,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("clear_temp_cache_button")
            ) {
                Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(15.dp), tint = MoteNavy)
                Spacer(Modifier.width(4.dp))
                Text("Clear Temp", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCheckUpdatesNow,
            enabled = !isCheckingUpdates,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("check_model_updates_button")
        ) {
            if (isCheckingUpdates) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = CloudFlashCobalt,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Checking Remote Model Metadata...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
            } else {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp), tint = CloudFlashCobalt)
                Spacer(Modifier.width(6.dp))
                Text("Check Downloaded Models for Updates", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
            }
        }
        lastUpdateSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Update Service: $summary",
                fontSize = 10.5.sp,
                color = SlateMuted,
                lineHeight = 14.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showClearModelCacheDialog = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.errorBg,
                contentColor = tokens.errorTitle
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(1.dp, tokens.errorBorder, RoundedCornerShape(10.dp))
                .testTag("clear_model_cache_button")
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = null,
                tint = tokens.errorTitle,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (unusedDownloadedCount > 0 || unusedDiskBytes > 0L) {
                    "Clear Model Cache ($unusedDownloadedCount unused · ${formatModelBytes(unusedDiskBytes)})"
                } else {
                    "Clear Model Cache (Unused Files & Room Metadata)"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.errorTitle
            )
        }

        utilityStatus?.let { statusMsg ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.accentEmeraldBg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = statusMsg,
                    fontSize = 11.5.sp,
                    color = if (tokens.isDark) Color(0xFFA7F3D0) else Color(0xFF065F46),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissStatus, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = if (tokens.isDark) Color(0xFFA7F3D0) else Color(0xFF065F46),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }

    if (showClearModelCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearModelCacheDialog = false },
            containerColor = tokens.surface,
            titleContentColor = MoteNavy,
            textContentColor = SlateMuted,
            title = {
                Text("Clear Unused Model Cache?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MoteNavy)
            },
            text = {
                Text(
                    text = buildString {
                        append("This will delete unused local model weight files, orphaned/temp download caches, and clear their Room metadata & benchmark records to reclaim device storage.")
                        if (unusedDownloadedCount > 0) {
                            append("\n\nReclaimable right now: $unusedDownloadedCount unused model(s) (~${formatModelBytes(unusedDiskBytes)}).")
                        }
                        append("\n\nAny model currently loaded in RAM or actively downloading will be preserved.")
                    },
                    fontSize = 13.sp,
                    color = SlateMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearModelCacheDialog = false
                        onClearModelCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_clear_model_cache_button")
                ) {
                    Text("Clear Cache", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearModelCacheDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = MoteNavy)
                }
            }
        )
    }
}

@Composable
fun ModelConfigurationsCard(
    prefs: ModelStudioPrefsEntity,
    llmConfig: LlmConfig,
    localBackendChoice: LocalHardwareBackend,
    onSelectBackend: (LocalHardwareBackend) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onBatterySafeChange: (Boolean) -> Unit,
    onAutoLoadChange: (Boolean) -> Unit,
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onContextTurnsChange: (Int) -> Unit
) {
    MoteCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(18.dp))
            Text("ENGINE & DOWNLOAD CONFIGURATIONS (ROOM PERSISTED)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        }
        Spacer(Modifier.height(10.dp))

        // Hardware Accelerator Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LiteRT-LM Hardware Backend", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                Text("Choose CPU (XNNPack) or GPU delegate", fontSize = 11.sp, color = SlateMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LocalHardwareBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = localBackendChoice == backend,
                        onClick = { onSelectBackend(backend) },
                        label = { Text(backend.label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.testTag("local_backend_${backend.name.lowercase()}")
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFFF1F5F9))

        ConfigToggleRow(
            title = "Wi-Fi Only Background Downloads",
            subtitle = "WorkManager waits for unmetered Wi-Fi before fetching weights",
            checked = prefs.wifiOnlyDownloads,
            onCheckedChange = onWifiOnlyChange,
            tag = "config_wifi_only_switch"
        )
        Spacer(Modifier.height(8.dp))
        ConfigToggleRow(
            title = "Battery-Safe Fetching",
            subtitle = "Pause WorkManager downloads when phone battery is low",
            checked = prefs.requireBatteryNotLow,
            onCheckedChange = onBatterySafeChange,
            tag = "config_battery_safe_switch"
        )
        Spacer(Modifier.height(8.dp))
        ConfigToggleRow(
            title = "Restore Last Local Model on Launch",
            subtitle = "Automatically reload your last active phone model from Room",
            checked = prefs.autoLoadLastModel,
            onCheckedChange = onAutoLoadChange,
            tag = "config_autoload_switch"
        )
        Spacer(Modifier.height(8.dp))
        ConfigToggleRow(
            title = "Auto-Update Local Models (WorkManager)",
            subtitle = "Background worker checks Room model metadata every 12h & downloads newer weights",
            checked = prefs.autoCheckModelUpdates,
            onCheckedChange = onAutoCheckUpdatesChange,
            tag = "config_auto_update_models_switch"
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFF1F5F9))

        StudioSliderRow(
            label = "Temperature",
            valueText = String.format(Locale.US, "%.2f", llmConfig.temperature),
            value = llmConfig.temperature,
            range = 0f..1.5f,
            onValueChange = onTemperatureChange
        )
        Spacer(Modifier.height(4.dp))
        StudioSliderRow(
            label = "Top-P Nucleus",
            valueText = String.format(Locale.US, "%.2f", llmConfig.topP),
            value = llmConfig.topP,
            range = 0.1f..1.0f,
            onValueChange = onTopPChange
        )
        Spacer(Modifier.height(4.dp))
        StudioSliderRow(
            label = "Max Output Tokens",
            valueText = "${llmConfig.maxOutputTokens}",
            value = llmConfig.maxOutputTokens.toFloat(),
            range = 256f..4096f,
            onValueChange = { onMaxTokensChange((it / 128f).roundToInt() * 128) }
        )
        Spacer(Modifier.height(4.dp))
        StudioSliderRow(
            label = "Context Memory",
            valueText = "${llmConfig.contextWindowTurns} msgs",
            value = llmConfig.contextWindowTurns.toFloat(),
            range = 2f..20f,
            onValueChange = { onContextTurnsChange(it.roundToInt()) }
        )
    }
}

@Composable
private fun ConfigToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
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

@Composable
private fun TelemetryStatTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    subColor: Color = SlateMuted
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MoteNavy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SpecPill(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text = text, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun StudioSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(valueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CloudFlashCobalt)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MoteNavy,
                activeTrackColor = CloudFlashCobalt,
                inactiveTrackColor = Color(0xFFE2E8F0)
            )
        )
    }
}

fun formatModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

fun formatApproxSize(sizeMb: Long): String =
    if (sizeMb >= 1024L) String.format(Locale.US, "%.2f GB", sizeMb / 1024.0) else "$sizeMb MB"

fun formatEta(seconds: Long): String = when {
    seconds < 60L -> "${seconds}s"
    seconds < 3600L -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

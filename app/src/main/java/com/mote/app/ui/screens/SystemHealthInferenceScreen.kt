package com.mote.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.control.SystemEventCategory
import com.mote.app.model.MoteInferenceMode
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated 'System Health & Inference' Dashboard Screen.
 *
 * Retrieves real-time battery temperature, thermal throttling status, battery level/power
 * draw, and hybrid model provider status (On-Device vs Cloud) from SystemHealthManager,
 * alongside the live EventObserver stream (`ContentObserver` + `BroadcastReceiver`) and
 * verified ToolExecutionSyncService records.
 */
@Composable
fun SystemHealthInferenceScreen(
    viewModel: MoteViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val tokens = LocalMoteColors.current
    val systemHealth by viewModel.systemHealth.collectAsStateWithLifecycle()
    val batteryTelemetry by viewModel.batteryTelemetry.collectAsStateWithLifecycle()
    val studioPrefs by viewModel.studioPrefs.collectAsStateWithLifecycle()
    val aiCoreStatus by viewModel.aiCoreStatus.collectAsStateWithLifecycle()
    val lastAdaptiveRouteNote by viewModel.lastAdaptiveRouteNote.collectAsStateWithLifecycle()
    val ecoInferenceRestricted by viewModel.ecoInferenceRestricted.collectAsStateWithLifecycle()
    val observedEvents by viewModel.observedSystemEvents.collectAsStateWithLifecycle()
    val syncRecords by viewModel.toolSyncVerifications.collectAsStateWithLifecycle()

    val selectedHybridMode = MoteInferenceMode.fromString(studioPrefs.hybridInferenceMode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
            .testTag("system_health_inference_screen")
    ) {
        // Top navigation bar with back button and live refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("health_dashboard_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MoteNavy
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "System Health & Inference",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Thermal telemetry · Hybrid provider · EventObserver",
                    fontSize = 11.5.sp,
                    color = SlateMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = {
                    viewModel.refreshSystemHealth()
                    viewModel.refreshBatteryTelemetry()
                    viewModel.refreshAiCoreStatus()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("health_dashboard_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh telemetry",
                    tint = CloudFlashCobalt
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp)
        ) {
            // 1. Primary System Health & Hybrid Provider Dashboard Card
            item(key = "health_screen_primary_dashboard") {
                SystemHealthInferenceDashboard(
                    health = systemHealth,
                    batteryTelemetry = batteryTelemetry,
                    selectedHybridMode = selectedHybridMode,
                    aiCoreStatus = aiCoreStatus,
                    adaptiveEnabled = studioPrefs.adaptiveThermalBatteryRouting,
                    lastRouteNote = lastAdaptiveRouteNote,
                    observedEvents = observedEvents,
                    onToggleAdaptive = { viewModel.setAdaptiveThermalBatteryRouting(it) },
                    onSelectHybridMode = { viewModel.setHybridInferenceMode(it) },
                    onRefresh = {
                        viewModel.refreshSystemHealth()
                        viewModel.refreshBatteryTelemetry()
                    }
                )
            }

            // 2. Firebase AI Logic Hybrid SDK & AICore Card
            item(key = "health_screen_firebase_hybrid") {
                FirebaseHybridStatusCard(
                    selectedMode = selectedHybridMode,
                    aiCoreStatus = aiCoreStatus,
                    nativeFunctionCallingEnabled = studioPrefs.nativeFunctionCallingEnabled,
                    recentSyncRecords = syncRecords,
                    onSelectMode = { viewModel.setHybridInferenceMode(it) },
                    onToggleNativeFunctionCalling = { viewModel.setNativeFunctionCallingEnabled(it) },
                    onRefreshAiCore = { viewModel.refreshAiCoreStatus() },
                    onDownloadGeminiNano = { viewModel.downloadGeminiNanoViaAiCore() }
                )
            }

            // 3. Eco-Inference Guard Card
            item(key = "health_screen_eco_guard") {
                EcoInferenceCard(
                    ecoInferenceEnabled = studioPrefs.ecoInferenceEnabled,
                    ecoInferenceRestricted = ecoInferenceRestricted,
                    telemetry = batteryTelemetry,
                    onToggleEcoInference = { viewModel.updateEcoInferenceEnabled(it) }
                )
            }

            // 4. Live EventObserver Feed (ContentObserver & BroadcastReceiver)
            item(key = "health_screen_event_observer_header") {
                MoteCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(tokens.accentEmeraldBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sensors,
                                    contentDescription = null,
                                    tint = LocalNanoEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "LIVE OS EVENT OBSERVER",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalNanoEmerald
                                )
                                Text(
                                    text = "ContentObserver & BroadcastReceiver",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy
                                )
                            }
                        }
                        Surface(
                            color = tokens.accentBlueBg,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "${observedEvents.size} events",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.accentBlue,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Monitors AudioManager volume/ringer broadcasts, AlarmManager clock broadcasts, NotificationManager DND changes, Settings.System brightness ContentObserver, CalendarContract ContentObserver, and ClipboardManager updates before confirming GenUI execution.",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = SlateMuted
                    )

                    if (observedEvents.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(IosCanvas)
                                .border(1.dp, tokens.border, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HourglassEmpty,
                                contentDescription = null,
                                tint = SlateMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Listening for system broadcasts & ContentObserver updates. Trigger a volume change, alarm, timer, brightness slider, or clipboard copy to see live confirmation.",
                                fontSize = 11.5.sp,
                                color = SlateMuted,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            if (observedEvents.isNotEmpty()) {
                items(observedEvents.take(15), key = { it.id }) { ev ->
                    val accent = when (ev.category) {
                        SystemEventCategory.AUDIO_VOLUME -> CloudFlashCobalt
                        SystemEventCategory.ALARM_CLOCK -> Color(0xFFD97706)
                        SystemEventCategory.SCREEN_BRIGHTNESS -> Color(0xFF0284C7)
                        SystemEventCategory.DND_POLICY -> Color(0xFF7C3AED)
                        SystemEventCategory.CALENDAR_PROVIDER -> LocalNanoEmerald
                        SystemEventCategory.CLIPBOARD -> Color(0xFF0D9488)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.surface)
                            .border(1.dp, tokens.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${ev.category.label} · ${ev.mechanism}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatEventTime(ev.timestampMs),
                                    fontSize = 10.sp,
                                    color = SlateMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Text(
                                text = ev.detail,
                                fontSize = 11.5.sp,
                                color = SlateMuted,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatEventTime(timestampMs: Long): String {
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
    }.getOrDefault("")
}

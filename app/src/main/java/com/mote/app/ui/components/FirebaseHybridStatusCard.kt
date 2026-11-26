package com.mote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.AiCoreAvailabilityState
import com.mote.app.ai.AiCoreStatusSnapshot
import com.mote.app.control.ToolSyncVerificationRecord
import com.mote.app.model.MoteInferenceMode
import com.mote.app.ui.theme.LocalMoteColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirebaseHybridStatusCard(
    selectedMode: MoteInferenceMode,
    aiCoreStatus: AiCoreStatusSnapshot,
    nativeFunctionCallingEnabled: Boolean,
    recentSyncRecords: List<ToolSyncVerificationRecord>,
    onSelectMode: (MoteInferenceMode) -> Unit,
    onToggleNativeFunctionCalling: (Boolean) -> Unit,
    onRefreshAiCore: () -> Unit,
    onDownloadGeminiNano: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    val statusAccent = when (aiCoreStatus.state) {
        AiCoreAvailabilityState.AVAILABLE -> Color(0xFF059669)
        AiCoreAvailabilityState.DOWNLOADABLE -> Color(0xFF1D4ED8)
        AiCoreAvailabilityState.DOWNLOADING -> Color(0xFFD97706)
        AiCoreAvailabilityState.CHECKING -> Color(0xFF6366F1)
        AiCoreAvailabilityState.UNAVAILABLE -> Color(0xFF64748B)
        AiCoreAvailabilityState.ERROR -> Color(0xFFDC2626)
    }
    val compactStatusLabel = when (aiCoreStatus.state) {
        AiCoreAvailabilityState.AVAILABLE -> "Nano Ready"
        AiCoreAvailabilityState.DOWNLOADABLE -> "Downloadable"
        AiCoreAvailabilityState.DOWNLOADING -> "Downloading"
        AiCoreAvailabilityState.CHECKING -> "Checking"
        AiCoreAvailabilityState.UNAVAILABLE -> "Cloud Fallback"
        AiCoreAvailabilityState.ERROR -> "Check Error"
    }

    val verifiedSyncCount = recentSyncRecords.count { it.verified }
    val latestSync = recentSyncRecords.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.surface)
            .border(1.dp, tokens.border, RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("firebase_hybrid_status_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                        .background(statusAccent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = statusAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Firebase AI Logic Hybrid SDK",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "OnDeviceConfig · Gemini Nano · Native Tools",
                        fontSize = 11.sp,
                        color = tokens.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(statusAccent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusAccent)
                    )
                    Text(
                        text = compactStatusLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusAccent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Official Firebase OnDeviceConfig InferenceMode Selector
        Text(
            text = "SDK INFERENCE MODE (OnDeviceConfig)",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = tokens.onSurfaceSecondary
        )
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
                val isSelected = selectedMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (isSelected) tokens.primaryActionBg else tokens.canvas
                        )
                        .border(
                            1.dp,
                            if (isSelected) tokens.primaryActionBg else tokens.border,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                        .testTag("hybrid_mode_${mode.name.lowercase()}")
                ) {
                    Text(
                        text = label,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else tokens.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // AICore / Gemini Nano Live Status Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.canvas)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = statusAccent,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Android AICore / Gemini Nano",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface
                )
            }
            Text(
                text = aiCoreStatus.detail,
                fontSize = 11.sp,
                color = tokens.onSurfaceSecondary,
                lineHeight = 15.sp
            )
            if (aiCoreStatus.state == AiCoreAvailabilityState.DOWNLOADING) {
                if (aiCoreStatus.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { aiCoreStatus.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = statusAccent
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = statusAccent
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRefreshAiCore,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("refresh_aicore_status_button")
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Check AICore", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
                if (aiCoreStatus.state == AiCoreAvailabilityState.DOWNLOADABLE ||
                    aiCoreStatus.state == AiCoreAvailabilityState.ERROR
                ) {
                    FilledTonalButton(
                        onClick = onDownloadGeminiNano,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF1D4ED8),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .testTag("download_gemini_nano_button")
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download Nano", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Native Firebase Function Calling Toggle + ToolExecutionSync Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Native Firebase Function Calling",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface
                )
                Text(
                    text = "Uses official Tool.functionDeclarations + ToolExecutionSync OS verification",
                    fontSize = 11.sp,
                    color = tokens.onSurfaceSecondary
                )
            }
            Switch(
                checked = nativeFunctionCallingEnabled,
                onCheckedChange = onToggleNativeFunctionCalling,
                modifier = Modifier.testTag("native_function_calling_switch")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF059669).copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Verified,
                contentDescription = null,
                tint = Color(0xFF059669),
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (latestSync != null) {
                    "ToolExecutionSync: $verifiedSyncCount/${recentSyncRecords.size} verified · Last: ${latestSync.observerSource} (${latestSync.checkDurationMs}ms)"
                } else {
                    "ToolExecutionSync active: verifies AlarmManager, Stopwatch ticks, Dialer & OS transitions before 'Execution Verified'"
                },
                fontSize = 10.8.sp,
                color = tokens.onSurface,
                lineHeight = 14.sp
            )
        }
    }
}

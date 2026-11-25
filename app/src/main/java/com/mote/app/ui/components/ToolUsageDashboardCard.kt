package com.mote.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.mote.app.ai.GenUiToolRegistry
import com.mote.app.ai.ToolExecutionAuditEntry
import com.mote.app.ai.ToolUsageStatsSnapshot

/**
 * Tool Usage Statistics Dashboard displayed inside the Model Management screen (`ModelsStudioScreen`).
 * Visualizes AI tool invocation frequency across Alarms, Timers, Stopwatch, Calendar, and GenUI Widgets,
 * and allows clearing the persisted Room tool execution log history.
 */
@Composable
fun ToolUsageStatsDashboardCard(
    stats: ToolUsageStatsSnapshot,
    recentLogs: List<ToolExecutionAuditEntry>,
    onClearHistory: () -> Unit
) {
    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.dp, CloudFlashCobalt.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .testTag("tool_usage_stats_dashboard_card")
    ) {
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
                        .background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BarChart,
                        contentDescription = null,
                        tint = CloudFlashCobalt,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI TOOL USAGE ANALYTICS",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = CloudFlashCobalt
                    )
                    Text(
                        text = if (stats.totalCalls > 0) {
                            "${stats.totalCalls} logged calls · Top: ${stats.topToolName ?: "—"}"
                        } else {
                            "Tracks AlarmManager, Timers, Stopwatch & Calendar usage"
                        },
                        fontSize = 11.sp,
                        color = SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = onClearHistory,
                enabled = stats.totalCalls > 0,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("clear_tool_history_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    tint = if (stats.totalCalls > 0) Color(0xFFDC2626) else SlateMuted,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Clear History",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (stats.totalCalls > 0) Color(0xFFDC2626) else SlateMuted
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // KPI Summary Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnalyticsMiniTile(
                label = "TOTAL CALLS",
                value = "${stats.totalCalls}",
                sub = "Persisted in Room",
                modifier = Modifier.weight(1f)
            )
            AnalyticsMiniTile(
                label = "SUCCESS RATE",
                value = if (stats.totalCalls > 0) "${stats.successRatePct}%" else "—",
                sub = "${stats.successfulCalls} ok",
                subColor = LocalNanoEmerald,
                modifier = Modifier.weight(1f)
            )
            AnalyticsMiniTile(
                label = "BLOCKED",
                value = "${stats.blockedCalls}",
                sub = "Auth guard",
                subColor = if (stats.blockedCalls > 0) Color(0xFFD97706) else SlateMuted,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Per-Tool Bar Chart Visualization
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.buckets.forEach { bucket ->
                val barColor = when (bucket.toolId) {
                    GenUiToolRegistry.TOOL_ALARM_MANAGER -> CloudFlashCobalt
                    GenUiToolRegistry.TOOL_COUNTDOWN_TIMER -> LocalNanoEmerald
                    GenUiToolRegistry.TOOL_STOPWATCH -> Color(0xFF0284C7)
                    GenUiToolRegistry.TOOL_CALENDAR -> Color(0xFFD97706)
                    GenUiToolRegistry.TOOL_CAMERA -> Color(0xFFEC4899)
                    GenUiToolRegistry.TOOL_SCREEN_CAPTURE -> Color(0xFF0EA5E9)
                    GenUiToolRegistry.TOOL_APP_LAUNCHER -> Color(0xFF6366F1)
                    GenUiToolRegistry.TOOL_NAVIGATION -> Color(0xFF14B8A6)
                    else -> Color(0xFF7C3AED)
                }
                val animatedWidth by animateFloatAsState(
                    targetValue = bucket.shareFraction,
                    animationSpec = tween(durationMillis = 360),
                    label = "toolBar_${bucket.toolId}"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tool_usage_bar_${bucket.toolId}"),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bucket.displayName,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoteNavy
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bucket.blockedCalls > 0) {
                                Text(
                                    text = "${bucket.blockedCalls} blocked",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFD97706)
                                )
                            }
                            Text(
                                text = "${bucket.totalCalls} call${if (bucket.totalCalls == 1) "" else "s"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (bucket.totalCalls > 0) barColor else SlateMuted
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF1F5F9))
                    ) {
                        if (animatedWidth > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedWidth)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(barColor)
                            )
                        }
                    }
                }
            }
        }

        // Latest executions quick peek
        if (recentLogs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFFF1F5F9))
            Spacer(Modifier.height(8.dp))
            Text(
                text = "RECENT EXECUTIONS",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = SlateMuted
            )
            Spacer(Modifier.height(4.dp))
            recentLogs.take(3).forEach { log ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${log.operation}() · ${log.feedback}",
                        fontSize = 10.5.sp,
                        color = Color(0xFF475569),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        text = if (log.blocked) "BLOCKED" else if (log.success) "OK" else "ERR",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            log.blocked -> Color(0xFFD97706)
                            log.success -> LocalNanoEmerald
                            else -> Color(0xFFDC2626)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMiniTile(
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
        Text(label, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MoteNavy)
        Text(sub, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

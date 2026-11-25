package com.mote.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.EngineUsed
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.MoteAction
import com.mote.app.control.ClockToolsManager
import com.mote.app.control.CountdownTimerItem
import com.mote.app.control.StopwatchUiState
import com.mote.app.data.db.CalendarEventEntity
import com.mote.app.data.db.ClockAlarmEntity
import com.mote.app.ui.theme.LocalMoteColors

/**
 * Renders any AI SDK-style inline Generative UI or live Clock/Calendar/Device Tool widget
 * directly inside the Chat Hub conversation stream.
 */
@Composable
fun InChatUiPartRenderer(
    part: InChatUiPart,
    alarms: List<ClockAlarmEntity>,
    timers: List<CountdownTimerItem>,
    stopwatch: StopwatchUiState,
    calendarEvents: List<CalendarEventEntity> = emptyList(),
    capturedScreenshots: Map<String, Bitmap> = emptyMap(),
    onToggleAlarm: (String, Boolean) -> Unit,
    onShiftAlarmMinutes: (String, Int) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onSyncAlarmToSystem: (ClockAlarmEntity) -> Unit = {},
    onControlTimer: (action: String, timerId: String) -> Unit,
    onAddTimerSeconds: (timerId: String, extraSeconds: Int) -> Unit,
    onControlStopwatch: (action: String) -> Unit,
    onDeleteCalendarEvent: (String) -> Unit = {},
    onSyncCalendarEventToSystem: (CalendarEventEntity) -> Unit = {},
    onExecuteGenUiAction: (MoteAction) -> Unit,
    onAnalyzeScreenshot: (captureId: String, prompt: String) -> Unit = { _, _ -> }
) {
    when (part) {
        is InChatUiPart.ClockAlarmsPanel -> {
            InChatClockAlarmsWidget(
                actionSummary = part.actionSummary,
                highlightAlarmId = part.highlightAlarmId,
                success = part.success,
                alarmManagerMode = part.alarmManagerMode,
                alarms = alarms,
                onToggleAlarm = onToggleAlarm,
                onShiftAlarmMinutes = onShiftAlarmMinutes,
                onDeleteAlarm = onDeleteAlarm,
                onSyncAlarmToSystem = onSyncAlarmToSystem
            )
        }
        is InChatUiPart.ClockTimerPanel -> {
            InChatCountdownTimerWidget(
                actionSummary = part.actionSummary,
                highlightTimerId = part.highlightTimerId,
                success = part.success,
                timers = timers,
                onControlTimer = onControlTimer,
                onAddTimerSeconds = onAddTimerSeconds
            )
        }
        is InChatUiPart.ClockStopwatchPanel -> {
            InChatStopwatchWidget(
                actionSummary = part.actionSummary,
                success = part.success,
                stopwatch = stopwatch,
                onControlStopwatch = onControlStopwatch
            )
        }
        is InChatUiPart.CalendarEventsPanel -> {
            InChatCalendarEventsWidget(
                actionSummary = part.actionSummary,
                highlightEventId = part.highlightEventId,
                success = part.success,
                calendarSyncMode = part.calendarSyncMode,
                events = calendarEvents,
                onDeleteEvent = onDeleteCalendarEvent,
                onSyncEventToSystem = onSyncCalendarEventToSystem
            )
        }
        is InChatUiPart.GenUiCardPart -> {
            GenUiCardRenderer(
                result = HybridGenUiResult(
                    spec = part.spec,
                    engineUsed = EngineUsed.CLOUD_FLASH,
                    latencyMs = 0L,
                    rawJson = "",
                    modelName = "In-Chat GenUI"
                ),
                onExecuteAction = onExecuteGenUiAction
            )
        }
        is InChatUiPart.ToolStatusBanner -> {
            InChatToolStatusBanner(
                toolName = part.toolName,
                operation = part.operation,
                success = part.success,
                message = part.message
            )
        }
        is InChatUiPart.DeviceActionPanel -> {
            InChatDeviceActionWidget(part = part)
        }
        is InChatUiPart.ScreenCapturePanel -> {
            InChatScreenCaptureWidget(
                part = part,
                previewBitmap = part.captureId?.let { capturedScreenshots[it] },
                onAnalyzeScreenshot = onAnalyzeScreenshot
            )
        }
    }
}

@Composable
private fun InChatClockAlarmsWidget(
    actionSummary: String,
    highlightAlarmId: String?,
    success: Boolean,
    alarmManagerMode: String?,
    alarms: List<ClockAlarmEntity>,
    onToggleAlarm: (String, Boolean) -> Unit,
    onShiftAlarmMinutes: (String, Int) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onSyncAlarmToSystem: (ClockAlarmEntity) -> Unit
) {
    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (success) LocalNanoEmerald.copy(alpha = 0.4f) else Color(0xFFDC2626).copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_clock_alarms_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (success) Color(0xFFECFDF5) else Color(0xFFFEF2F2)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Alarm,
                    contentDescription = null,
                    tint = if (success) LocalNanoEmerald else Color(0xFFDC2626),
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ALARMMANAGER · ${alarms.size}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CloudFlashCobalt
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = if (success) (alarmManagerMode ?: "Success") else "Notice",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(actionSummary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (alarms.isEmpty()) {
            Text(
                text = "No alarms currently scheduled.",
                fontSize = 12.sp,
                color = SlateMuted,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tokens = LocalMoteColors.current
                alarms.forEach { alarm ->
                    val highlighted = alarm.id == highlightAlarmId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (highlighted) {
                                    if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
                                } else {
                                    MoteSurfaceSubtle
                                }
                            )
                            .border(
                                width = if (highlighted) 1.dp else 0.5.dp,
                                color = if (highlighted) LocalNanoEmerald else MoteBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                            .testTag("inchat_alarm_row_${alarm.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ClockToolsManager.formatAlarmTime(alarm.hour, alarm.minute),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (alarm.enabled) MoteNavy else SlateMuted
                                )
                                val daysSuffix = alarm.daysCsv.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                Text(
                                    text = "${alarm.label}$daysSuffix",
                                    fontSize = 11.5.sp,
                                    color = SlateMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IosSpringSwitch(
                                checked = alarm.enabled,
                                onCheckedChange = { onToggleAlarm(alarm.id, it) },
                                modifier = Modifier.testTag("inchat_alarm_switch_${alarm.id}")
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallClockChip("-5m", onClick = { onShiftAlarmMinutes(alarm.id, -5) })
                            SmallClockChip("+5m", onClick = { onShiftAlarmMinutes(alarm.id, 5) })
                            Text(
                                text = if (alarm.enabled) "✓ Auto-active in AlarmManager" else "Paused",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (alarm.enabled) LocalNanoEmerald else SlateMuted
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { onDeleteAlarm(alarm.id) },
                                modifier = Modifier.size(34.dp).testTag("inchat_alarm_delete_${alarm.id}")
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete alarm", tint = Color(0xFFDC2626), modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InChatCountdownTimerWidget(
    actionSummary: String,
    highlightTimerId: String?,
    success: Boolean,
    timers: List<CountdownTimerItem>,
    onControlTimer: (action: String, timerId: String) -> Unit,
    onAddTimerSeconds: (timerId: String, extraSeconds: Int) -> Unit
) {
    val activeRunning = timers.firstOrNull { it.isRunning } ?: timers.firstOrNull()
    MoteCard(
        background = MoteBrandNavy,
        modifier = Modifier.testTag("inchat_clock_timer_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Timer, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("LIVE COUNTDOWN STREAM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LocalNanoEmerald)
                    if (activeRunning != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = "${ClockToolsManager.formatDurationSeconds(activeRunning.remainingSeconds)} left",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (success) LocalNanoEmerald else Color(0xFFFCA5A5)
                            )
                        }
                    }
                }
                Text(actionSummary, fontSize = 12.sp, color = Color(0xFFCBD5E1))
            }
        }

        Spacer(Modifier.height(10.dp))

        if (timers.isEmpty()) {
            Text("No active timers.", fontSize = 12.sp, color = Color(0xFF94A3B8))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                timers.forEach { timer ->
                    val highlighted = timer.id == highlightTimerId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (highlighted) 0.12f else 0.07f))
                            .padding(12.dp)
                            .testTag("inchat_timer_row_${timer.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(timer.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFCBD5E1))
                                Text(
                                    text = if (timer.isFinished) "DONE · 00:00" else ClockToolsManager.formatDurationSeconds(timer.remainingSeconds),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (timer.isFinished) Color(0xFFFCA5A5) else Color.White
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalIconButton(
                                    onClick = {
                                        onControlTimer(if (timer.isRunning) "pause" else "resume", timer.id)
                                    },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (timer.isRunning) Color(0xFFF59E0B) else LocalNanoEmerald,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(40.dp).testTag("inchat_timer_toggle_${timer.id}")
                                ) {
                                    Icon(
                                        imageVector = if (timer.isRunning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = if (timer.isRunning) "Pause timer" else "Resume timer",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onControlTimer("reset", timer.id) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Outlined.Replay, contentDescription = "Reset timer", tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onControlTimer("delete", timer.id) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Delete timer", tint = Color(0xFFFCA5A5), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { timer.progressFraction },
                            color = if (timer.isFinished) Color(0xFFEF4444) else LocalNanoEmerald,
                            trackColor = Color.White.copy(alpha = 0.16f),
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onAddTimerSeconds(timer.id, 60) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Text("+1 min", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LocalNanoEmerald)
                            }
                            TextButton(
                                onClick = { onAddTimerSeconds(timer.id, 300) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Text("+5 min", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InChatStopwatchWidget(
    actionSummary: String,
    success: Boolean,
    stopwatch: StopwatchUiState,
    onControlStopwatch: (String) -> Unit
) {
    MoteCard(
        background = Color(0xFF0F172A),
        modifier = Modifier.testTag("inchat_clock_stopwatch_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.AvTimer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("LIVE STOPWATCH STREAM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = if (stopwatch.isRunning) "RUNNING" else if (stopwatch.elapsedMs > 0L) "PAUSED" else "READY",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (success && stopwatch.isRunning) LocalNanoEmerald else Color(0xFF93C5FD)
                        )
                    }
                }
                Text(actionSummary, fontSize = 11.5.sp, color = Color(0xFF94A3B8))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = ClockToolsManager.formatStopwatchMs(stopwatch.elapsedMs),
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.testTag("inchat_stopwatch_readout")
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onControlStopwatch(if (stopwatch.isRunning) "pause" else "start") },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stopwatch.isRunning) Color(0xFFF59E0B) else LocalNanoEmerald
                ),
                modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("inchat_stopwatch_start_pause")
            ) {
                Text(
                    text = if (stopwatch.isRunning) "Pause" else "Start",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            OutlinedButton(
                onClick = { onControlStopwatch("lap") },
                enabled = stopwatch.elapsedMs > 0L || stopwatch.isRunning,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("inchat_stopwatch_lap")
            ) {
                Text("Lap", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            OutlinedButton(
                onClick = { onControlStopwatch("reset") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 42.dp).testTag("inchat_stopwatch_reset")
            ) {
                Text("Reset", fontSize = 12.sp, color = Color(0xFFFCA5A5))
            }
        }

        if (stopwatch.laps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Spacer(Modifier.height(6.dp))
            stopwatch.laps.take(5).forEach { lap ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Lap #${lap.lapNumber}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8))
                    Text(
                        text = "+${ClockToolsManager.formatStopwatchMs(lap.splitMs)}  ·  ${ClockToolsManager.formatStopwatchMs(lap.totalMs)}",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun InChatCalendarEventsWidget(
    actionSummary: String,
    highlightEventId: String?,
    success: Boolean,
    calendarSyncMode: String?,
    events: List<CalendarEventEntity>,
    onDeleteEvent: (String) -> Unit,
    onSyncEventToSystem: (CalendarEventEntity) -> Unit
) {
    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (success) Color(0xFFD97706).copy(alpha = 0.35f) else Color(0xFFDC2626).copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_calendar_events_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFFBEB)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CALENDAR · ${events.size}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB45309)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = if (success) (calendarSyncMode ?: "Scheduled") else "Notice",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(actionSummary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (events.isEmpty()) {
            Text(
                text = "No calendar events currently scheduled.",
                fontSize = 12.sp,
                color = SlateMuted,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tokens = LocalMoteColors.current
                events.forEach { ev ->
                    val highlighted = ev.id == highlightEventId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (highlighted) {
                                    if (tokens.isDark) Color(0xFF451A03) else Color(0xFFFFFBEB)
                                } else {
                                    MoteSurfaceSubtle
                                }
                            )
                            .border(
                                width = if (highlighted) 1.dp else 0.5.dp,
                                color = if (highlighted) Color(0xFFD97706) else MoteBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp)
                            .testTag("inchat_calendar_row_${ev.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ev.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${ev.dateIso} · ${ev.startTime} – ${ev.endTime}",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                                if (ev.location.isNotBlank()) {
                                    Text(
                                        text = "Location: ${ev.location}",
                                        fontSize = 11.sp,
                                        color = SlateMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (ev.notes.isNotBlank()) {
                                    Text(
                                        text = ev.notes,
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteEvent(ev.id) },
                                modifier = Modifier.size(36.dp).testTag("inchat_calendar_delete_${ev.id}")
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete event", tint = Color(0xFFDC2626), modifier = Modifier.size(17.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (ev.syncedToSystemCalendar) Icons.Outlined.CheckCircle else Icons.Outlined.EventAvailable,
                                contentDescription = null,
                                tint = if (ev.syncedToSystemCalendar) LocalNanoEmerald else CloudFlashCobalt,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (ev.syncedToSystemCalendar) {
                                    "Auto-synced directly to Android Calendar"
                                } else {
                                    "Live in Mote Schedule (grant Calendar permission for system sync)"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (ev.syncedToSystemCalendar) LocalNanoEmerald else SlateMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InChatToolStatusBanner(
    toolName: String,
    operation: String,
    success: Boolean,
    message: String
) {
    val tokens = LocalMoteColors.current
    MoteCard(
        background = if (success) {
            if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
        } else {
            if (tokens.isDark) Color(0xFF451A03) else Color(0xFFFFFBEB)
        },
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (success) LocalNanoEmerald else Color(0xFFD97706),
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("inchat_tool_status_banner")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
                contentDescription = null,
                tint = if (success) LocalNanoEmerald else Color(0xFFD97706),
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$toolName · $operation",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (success) {
                        if (tokens.isDark) Color(0xFF6EE7B7) else Color(0xFF15803D)
                    } else {
                        if (tokens.isDark) Color(0xFFFCD34D) else Color(0xFFB45309)
                    }
                )
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = if (success) MoteNavy else if (tokens.isDark) Color(0xFFFDE68A) else Color(0xFF92400E)
                )
            }
        }
    }
}

@Composable
private fun InChatDeviceActionWidget(part: InChatUiPart.DeviceActionPanel) {
    val tokens = LocalMoteColors.current
    val (icon, accent) = when (part.category.uppercase()) {
        "CAMERA" -> Icons.Outlined.PhotoCamera to Color(0xFFEC4899)
        "APPS" -> Icons.Outlined.Apps to CloudFlashCobalt
        "NAVIGATION" -> Icons.Outlined.Navigation to LocalNanoEmerald
        else -> Icons.Outlined.Bolt to CloudFlashCobalt
    }
    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) accent.copy(alpha = 0.35f) else Color(0xFFDC2626).copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_device_action_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (part.success) accent else Color(0xFFDC2626),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = part.category,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }
        if (part.detailLines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoteSurfaceSubtle)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                part.detailLines.take(8).forEach { line ->
                    Text(
                        text = "• $line",
                        fontSize = 11.sp,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InChatScreenCaptureWidget(
    part: InChatUiPart.ScreenCapturePanel,
    previewBitmap: Bitmap?,
    onAnalyzeScreenshot: (captureId: String, prompt: String) -> Unit
) {
    val tokens = LocalMoteColors.current
    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFFDC2626).copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_screen_capture_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0284C7).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Screenshot,
                    contentDescription = null,
                    tint = Color(0xFF0284C7),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SCREEN CAPTURE",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0284C7)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        if (previewBitmap != null && !previewBitmap.isRecycled) {
            Spacer(Modifier.height(10.dp))
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = part.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MoteSurfaceSubtle)
                    .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            )

            val capId = part.captureId
            if (capId != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onAnalyzeScreenshot(
                                capId,
                                "Visually analyze this captured screenshot: describe what is shown, highlight key information, and suggest helpful next actions."
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp)
                            .testTag("inchat_analyze_screenshot_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Analyze Visually",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onAnalyzeScreenshot(
                                capId,
                                "Read and extract all visible text, numbers, and UI labels from this screenshot accurately."
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 42.dp)
                            .testTag("inchat_extract_text_screenshot_button")
                    ) {
                        Text(
                            text = "Read Text",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoteNavy
                        )
                    }
                }
            }
        }

        if (part.detailLines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoteSurfaceSubtle)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                part.detailLines.take(6).forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        color = MoteNavy,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallClockChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 32.dp)
    ) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
    }
}

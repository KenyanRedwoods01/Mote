package com.mote.app.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import com.mote.app.MainActivity
import com.mote.app.data.db.ClockAlarmEntity
import com.mote.app.data.db.LocalModelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class CountdownTimerItem(
    val id: String,
    val label: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isRunning: Boolean,
    val isFinished: Boolean = false
) {
    val progressFraction: Float
        get() = if (totalSeconds <= 0) 0f else (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
}

data class StopwatchLap(
    val lapNumber: Int,
    val splitMs: Long,
    val totalMs: Long
)

data class StopwatchUiState(
    val elapsedMs: Long = 0L,
    val isRunning: Boolean = false,
    val laps: List<StopwatchLap> = emptyList()
)

data class ClockToolExecutionOutcome(
    val summary: String,
    val success: Boolean = true,
    val targetAlarmId: String? = null,
    val targetTimerId: String? = null,
    val alarmManagerMode: String? = null
)

/**
 * Full real-time Android Clock Tools controller backed by:
 * - Room + Android AlarmManager (addAlarm, updateAlarm, deleteAlarm, set/unset, exact/inexact scheduling)
 * - Live 1-second ticking Countdown Timers (start, pause, resume, reset, +time, delete, real-time remaining status)
 * - Live centisecond Stopwatch (start, pause, lap, reset, real-time elapsed status)
 */
class ClockToolsManager(context: Context) {

    private val appContext = context.applicationContext
    private val dao = LocalModelDatabase.getInstance(appContext).localModelDao()
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val alarms: StateFlow<List<ClockAlarmEntity>> = dao.observeAllAlarms()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _timers = MutableStateFlow<List<CountdownTimerItem>>(emptyList())
    val timers: StateFlow<List<CountdownTimerItem>> = _timers.asStateFlow()

    private val _stopwatch = MutableStateFlow(StopwatchUiState())
    val stopwatch: StateFlow<StopwatchUiState> = _stopwatch.asStateFlow()

    private var timerTickerJob: Job? = null
    private var stopwatchTickerJob: Job? = null
    private var stopwatchBaseElapsedMs: Long = 0L
    private var stopwatchStartedAtMs: Long = 0L

    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    // --- ALARMS (Room + Android AlarmManager) ---

    suspend fun createAlarm(
        hour: Int,
        minute: Int,
        label: String,
        days: List<String> = emptyList(),
        enabled: Boolean = true
    ): ClockToolExecutionOutcome {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val cleanLabel = label.trim().ifBlank { "Alarm" }.take(60)
        val id = "alarm_${UUID.randomUUID().toString().take(8)}"
        val entity = ClockAlarmEntity(
            id = id,
            hour = h,
            minute = m,
            label = cleanLabel,
            daysCsv = days.joinToString(",") { it.trim() },
            enabled = enabled,
            createdAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertAlarm(entity)
        val mode = if (enabled) scheduleWithAlarmManager(entity) else "Saved Disabled"
        return ClockToolExecutionOutcome(
            summary = "Added alarm '${entity.label}' for ${formatAlarmTime(h, m)} ($mode)",
            success = true,
            targetAlarmId = id,
            alarmManagerMode = mode
        )
    }

    suspend fun createRelativeAlarm(offsetMinutes: Int, label: String): ClockToolExecutionOutcome {
        val safeOffset = offsetMinutes.coerceIn(1, 24 * 60)
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, safeOffset) }
        return createAlarm(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            label = label.ifBlank { "Alarm (+${safeOffset}m)" },
            enabled = true
        )
    }

    suspend fun setAlarmEnabled(queryOrId: String?, enabled: Boolean): ClockToolExecutionOutcome {
        val current = dao.getAllAlarmsOnce()
        if (current.isEmpty()) {
            return ClockToolExecutionOutcome(
                summary = "No alarms found to ${if (enabled) "enable" else "disable"}.",
                success = false
            )
        }
        if (queryOrId.isNullOrBlank() || queryOrId.equals("all", ignoreCase = true)) {
            var mode = "AlarmManager Cancelled"
            current.forEach { alarm ->
                val updated = alarm.copy(enabled = enabled)
                dao.upsertAlarm(updated)
                if (enabled) {
                    mode = scheduleWithAlarmManager(updated)
                } else {
                    cancelInAlarmManager(updated.id)
                }
            }
            return ClockToolExecutionOutcome(
                summary = "${if (enabled) "Enabled" else "Disabled"} all ${current.size} alarm(s) ($mode)",
                success = true,
                alarmManagerMode = mode
            )
        }
        val target = findMatchingAlarm(current, queryOrId) ?: current.first()
        val updated = target.copy(enabled = enabled)
        dao.upsertAlarm(updated)
        val mode = if (enabled) {
            scheduleWithAlarmManager(updated)
        } else {
            cancelInAlarmManager(updated.id)
            "Cancelled in AlarmManager"
        }
        return ClockToolExecutionOutcome(
            summary = "${if (enabled) "Set ON" else "Unset OFF"} '${updated.label}' (${formatAlarmTime(updated.hour, updated.minute)})",
            success = true,
            targetAlarmId = updated.id,
            alarmManagerMode = mode
        )
    }

    suspend fun updateAlarm(
        queryOrId: String?,
        newHour: Int?,
        newMinute: Int?,
        newLabel: String?,
        newEnabled: Boolean? = null
    ): ClockToolExecutionOutcome {
        val current = dao.getAllAlarmsOnce()
        if (current.isEmpty()) {
            return if (newHour != null) {
                createAlarm(newHour, newMinute ?: 0, newLabel ?: "Alarm", enabled = newEnabled ?: true)
            } else {
                ClockToolExecutionOutcome(
                    summary = "No existing alarm found to update.",
                    success = false
                )
            }
        }
        val target = findMatchingAlarm(current, queryOrId) ?: current.last()
        val updated = target.copy(
            hour = (newHour ?: target.hour).coerceIn(0, 23),
            minute = (newMinute ?: target.minute).coerceIn(0, 59),
            label = newLabel?.trim()?.takeIf { it.isNotBlank() }?.take(60) ?: target.label,
            enabled = newEnabled ?: target.enabled
        )
        dao.upsertAlarm(updated)
        cancelInAlarmManager(target.id)
        val mode = if (updated.enabled) {
            scheduleWithAlarmManager(updated)
        } else {
            "Cancelled in AlarmManager"
        }
        return ClockToolExecutionOutcome(
            summary = "Updated '${updated.label}' to ${formatAlarmTime(updated.hour, updated.minute)} ($mode)",
            success = true,
            targetAlarmId = updated.id,
            alarmManagerMode = mode
        )
    }

    fun toggleAlarmById(alarmId: String, enabled: Boolean) {
        scope.launch {
            val target = dao.getAllAlarmsOnce().firstOrNull { it.id == alarmId } ?: return@launch
            val updated = target.copy(enabled = enabled)
            dao.upsertAlarm(updated)
            if (enabled) {
                scheduleWithAlarmManager(updated)
            } else {
                cancelInAlarmManager(updated.id)
            }
        }
    }

    fun shiftAlarmMinutes(alarmId: String, deltaMinutes: Int) {
        scope.launch {
            val target = dao.getAllAlarmsOnce().firstOrNull { it.id == alarmId } ?: return@launch
            val totalMins = ((target.hour * 60 + target.minute + deltaMinutes) % (24 * 60) + (24 * 60)) % (24 * 60)
            val updated = target.copy(hour = totalMins / 60, minute = totalMins % 60)
            dao.upsertAlarm(updated)
            if (updated.enabled) {
                scheduleWithAlarmManager(updated)
            }
        }
    }

    suspend fun deleteAlarm(queryOrId: String?): ClockToolExecutionOutcome {
        val current = dao.getAllAlarmsOnce()
        if (current.isEmpty()) {
            return ClockToolExecutionOutcome(summary = "No alarms to delete.", success = false)
        }
        if (queryOrId.equals("all", ignoreCase = true)) {
            current.forEach { cancelInAlarmManager(it.id) }
            dao.deleteAllAlarms()
            return ClockToolExecutionOutcome(
                summary = "Deleted all ${current.size} alarm(s) from AlarmManager",
                success = true,
                alarmManagerMode = "Cancelled in AlarmManager"
            )
        }
        val target = findMatchingAlarm(current, queryOrId) ?: current.last()
        cancelInAlarmManager(target.id)
        dao.deleteAlarmById(target.id)
        return ClockToolExecutionOutcome(
            summary = "Deleted alarm '${target.label}' (${formatAlarmTime(target.hour, target.minute)})",
            success = true,
            alarmManagerMode = "Cancelled in AlarmManager"
        )
    }

    fun deleteAlarmById(alarmId: String) {
        scope.launch {
            cancelInAlarmManager(alarmId)
            dao.deleteAlarmById(alarmId)
        }
    }

    suspend fun snoozeAlarm(queryOrId: String?, snoozeMinutes: Int = 10): ClockToolExecutionOutcome {
        val safeSnooze = snoozeMinutes.coerceIn(1, 180)
        return shiftAlarmByQuery(queryOrId, safeSnooze, verb = "Snoozed")
    }

    suspend fun shiftAlarmByQuery(
        queryOrId: String?,
        deltaMinutes: Int,
        verb: String = "Shifted"
    ): ClockToolExecutionOutcome {
        if (deltaMinutes == 0) {
            return ClockToolExecutionOutcome(
                summary = "Shift amount must be non-zero minutes.",
                success = false
            )
        }
        val current = dao.getAllAlarmsOnce()
        if (current.isEmpty()) {
            return ClockToolExecutionOutcome(
                summary = "No scheduled alarms found to ${verb.lowercase(Locale.US)}.",
                success = false
            )
        }
        val target = findMatchingAlarm(current, queryOrId)
            ?: if (queryOrId.isNullOrBlank()) current.firstOrNull { it.enabled } ?: current.last() else null
        if (target == null) {
            return ClockToolExecutionOutcome(
                summary = "No alarm matching '$queryOrId' was found.",
                success = false
            )
        }
        val totalMins = ((target.hour * 60 + target.minute + deltaMinutes) % (24 * 60) + (24 * 60)) % (24 * 60)
        val updated = target.copy(
            hour = totalMins / 60,
            minute = totalMins % 60,
            enabled = true
        )
        dao.upsertAlarm(updated)
        cancelInAlarmManager(target.id)
        val mode = scheduleWithAlarmManager(updated)
        val sign = if (deltaMinutes > 0) "+${deltaMinutes}m" else "${deltaMinutes}m"
        return ClockToolExecutionOutcome(
            summary = "$verb '${updated.label}' ($sign) to ${formatAlarmTime(updated.hour, updated.minute)} ($mode)",
            success = true,
            targetAlarmId = updated.id,
            alarmManagerMode = mode
        )
    }

    suspend fun syncAlarmByQuery(queryOrId: String?): ClockToolExecutionOutcome {
        val current = dao.getAllAlarmsOnce()
        if (current.isEmpty()) {
            return ClockToolExecutionOutcome(
                summary = "No alarms exist yet to sync to the Android Clock app.",
                success = false
            )
        }
        val target = findMatchingAlarm(current, queryOrId) ?: current.last()
        val launched = syncAlarmToAndroidClock(target)
        return ClockToolExecutionOutcome(
            summary = if (launched) {
                "Opened Android Clock to sync '${target.label}' (${formatAlarmTime(target.hour, target.minute)})"
            } else {
                "Android Clock app is not available on this device to receive AlarmClock sync."
            },
            success = launched,
            targetAlarmId = target.id,
            alarmManagerMode = if (launched) "Android Clock Synced" else "Sync Unavailable"
        )
    }

    /**
     * Checks whether Android's [AlarmManager] currently holds an active [PendingIntent] for [alarmId].
     * Used by [ToolExecutionSyncService] to confirm real OS-level scheduling before marking verified.
     */
    fun isAlarmScheduledInSystem(alarmId: String): Boolean {
        return try {
            val intent = Intent(appContext, MoteAlarmReceiver::class.java).apply {
                action = "com.mote.app.ALARM_$alarmId"
            }
            PendingIntent.getBroadcast(
                appContext,
                alarmId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) != null
        } catch (_: Exception) {
            false
        }
    }

    fun syncAlarmToAndroidClock(alarm: ClockAlarmEntity): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, alarm.label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleWithAlarmManager(alarm: ClockAlarmEntity): String {
        val am = alarmManager ?: return "Room Only (No AlarmManager)"
        val triggerAtMillis = computeNextTriggerMillis(alarm.hour, alarm.minute)
        val broadcastPi = buildAlarmPendingIntent(alarm)
        return try {
            if (canScheduleExactAlarms()) {
                val showIntent = PendingIntent.getActivity(
                    appContext,
                    alarm.id.hashCode() + 17,
                    Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), broadcastPi)
                "Exact AlarmManager"
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, broadcastPi)
                "Inexact AlarmManager"
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, broadcastPi)
                "Inexact AlarmManager"
            } catch (_: Exception) {
                "Room Saved"
            }
        } catch (_: Exception) {
            "Room Saved"
        }
    }

    private fun cancelInAlarmManager(alarmId: String) {
        val am = alarmManager ?: return
        try {
            val intent = Intent(appContext, MoteAlarmReceiver::class.java).apply {
                action = "com.mote.app.ALARM_$alarmId"
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                alarmId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        } catch (_: Exception) {
            // Ignore cancellation errors if PendingIntent was already removed
        }
    }

    private fun buildAlarmPendingIntent(alarm: ClockAlarmEntity): PendingIntent {
        val intent = Intent(appContext, MoteAlarmReceiver::class.java).apply {
            action = "com.mote.app.ALARM_${alarm.id}"
            putExtra(MoteAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            putExtra(MoteAlarmReceiver.EXTRA_ALARM_LABEL, alarm.label)
            putExtra(MoteAlarmReceiver.EXTRA_ALARM_TIME, formatAlarmTime(alarm.hour, alarm.minute))
        }
        return PendingIntent.getBroadcast(
            appContext,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun computeNextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return target.timeInMillis
    }

    // --- COUNTDOWN TIMERS (Real-Time Ticking) ---

    fun startTimer(durationSeconds: Int, label: String): ClockToolExecutionOutcome {
        val safeSeconds = durationSeconds.coerceIn(1, 24 * 3600)
        val cleanLabel = label.trim().ifBlank { "Countdown" }.take(50)
        val id = "timer_${UUID.randomUUID().toString().take(8)}"
        val item = CountdownTimerItem(
            id = id,
            label = cleanLabel,
            totalSeconds = safeSeconds,
            remainingSeconds = safeSeconds,
            isRunning = true,
            isFinished = false
        )
        _timers.update { listOf(item) + it }
        ensureTimerTickerRunning()
        return ClockToolExecutionOutcome(
            summary = "Started ${formatDurationSeconds(safeSeconds)} timer '$cleanLabel' (${formatDurationSeconds(safeSeconds)} remaining)",
            success = true,
            targetTimerId = id
        )
    }

    fun getCountdownStatusSummary(queryOrId: String? = null): ClockToolExecutionOutcome {
        val list = _timers.value
        if (list.isEmpty()) {
            return ClockToolExecutionOutcome(
                summary = "No active countdown timers running right now.",
                success = true
            )
        }
        val target = findMatchingTimer(list, queryOrId) ?: list.first()
        val stateLabel = when {
            target.isFinished -> "finished"
            target.isRunning -> "running"
            else -> "paused"
        }
        return ClockToolExecutionOutcome(
            summary = "'${target.label}': ${formatDurationSeconds(target.remainingSeconds)} remaining of ${formatDurationSeconds(target.totalSeconds)} ($stateLabel)",
            success = true,
            targetTimerId = target.id
        )
    }

    fun controlTimer(action: String, queryOrId: String? = null): ClockToolExecutionOutcome {
        val list = _timers.value
        if (list.isEmpty()) {
            return ClockToolExecutionOutcome(summary = "No active countdown timers.", success = false)
        }
        val target = findMatchingTimer(list, queryOrId) ?: list.first()
        return when (action.lowercase(Locale.US)) {
            "pause", "stop" -> {
                _timers.update { items -> items.map { if (it.id == target.id) it.copy(isRunning = false) else it } }
                ClockToolExecutionOutcome(
                    summary = "Paused '${target.label}' with ${formatDurationSeconds(target.remainingSeconds)} remaining",
                    success = true,
                    targetTimerId = target.id
                )
            }
            "resume", "start" -> {
                _timers.update { items ->
                    items.map {
                        if (it.id == target.id && it.remainingSeconds > 0) it.copy(isRunning = true, isFinished = false) else it
                    }
                }
                ensureTimerTickerRunning()
                ClockToolExecutionOutcome(
                    summary = "Resumed '${target.label}' (${formatDurationSeconds(target.remainingSeconds)} remaining)",
                    success = true,
                    targetTimerId = target.id
                )
            }
            "reset" -> {
                _timers.update { items ->
                    items.map {
                        if (it.id == target.id) it.copy(remainingSeconds = it.totalSeconds, isRunning = false, isFinished = false) else it
                    }
                }
                ClockToolExecutionOutcome(
                    summary = "Reset '${target.label}' to ${formatDurationSeconds(target.totalSeconds)}",
                    success = true,
                    targetTimerId = target.id
                )
            }
            "delete", "remove", "cancel" -> {
                _timers.update { items -> items.filterNot { it.id == target.id } }
                ClockToolExecutionOutcome(
                    summary = "Deleted timer '${target.label}'",
                    success = true
                )
            }
            else -> getCountdownStatusSummary(target.id)
        }
    }

    fun addSecondsToTimer(timerId: String, extraSeconds: Int) {
        var shouldTick = false
        _timers.update { items ->
            items.map {
                if (it.id == timerId) {
                    val newRem = (it.remainingSeconds + extraSeconds).coerceAtLeast(0)
                    val newTotal = maxOf(it.totalSeconds, newRem)
                    val resume = newRem > 0 && (it.isRunning || it.isFinished)
                    if (resume) shouldTick = true
                    it.copy(
                        totalSeconds = newTotal,
                        remainingSeconds = newRem,
                        isRunning = resume,
                        isFinished = newRem == 0
                    )
                } else it
            }
        }
        if (shouldTick) {
            ensureTimerTickerRunning()
        }
    }

    private fun ensureTimerTickerRunning() {
        if (timerTickerJob?.isActive == true) return
        timerTickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val current = _timers.value
                if (current.none { it.isRunning }) {
                    break
                }
                var anyJustFinished = false
                _timers.update { items ->
                    items.map { item ->
                        if (!item.isRunning) {
                            item
                        } else if (item.remainingSeconds <= 1) {
                            anyJustFinished = true
                            item.copy(remainingSeconds = 0, isRunning = false, isFinished = true)
                        } else {
                            item.copy(remainingSeconds = item.remainingSeconds - 1)
                        }
                    }
                }
                if (anyJustFinished) {
                    vibrateTimerDone()
                }
            }
            timerTickerJob = null
        }
    }

    // --- STOPWATCH (Real-Time Centisecond Ticking) ---

    fun getStopwatchStatusSummary(): ClockToolExecutionOutcome {
        val sw = _stopwatch.value
        val stateLabel = if (sw.isRunning) "running" else if (sw.elapsedMs > 0L) "paused" else "idle"
        return ClockToolExecutionOutcome(
            summary = "Stopwatch $stateLabel: ${formatStopwatchMs(sw.elapsedMs)} elapsed (${sw.laps.size} lap${if (sw.laps.size == 1) "" else "s"})",
            success = true
        )
    }

    fun controlStopwatch(action: String): ClockToolExecutionOutcome {
        return when (action.trim().lowercase(Locale.US)) {
            "start", "resume" -> {
                val wasRunning = _stopwatch.value.isRunning
                if (!wasRunning) {
                    stopwatchBaseElapsedMs = _stopwatch.value.elapsedMs
                    stopwatchStartedAtMs = SystemClock.elapsedRealtime()
                    _stopwatch.update { it.copy(isRunning = true) }
                    ensureStopwatchTickerRunning()
                }
                ClockToolExecutionOutcome(
                    summary = if (wasRunning) {
                        "Stopwatch already running (${formatStopwatchMs(_stopwatch.value.elapsedMs)} elapsed)"
                    } else if (_stopwatch.value.elapsedMs > 0L) {
                        "Stopwatch resumed from ${formatStopwatchMs(_stopwatch.value.elapsedMs)}"
                    } else {
                        "Stopwatch started from 00:00.00"
                    },
                    success = true
                )
            }
            "pause", "stop" -> {
                if (!_stopwatch.value.isRunning && _stopwatch.value.elapsedMs == 0L) {
                    return ClockToolExecutionOutcome(
                        summary = "Stopwatch is already stopped at 00:00.00.",
                        success = true
                    )
                }
                if (_stopwatch.value.isRunning) {
                    val finalElapsed = stopwatchBaseElapsedMs + (SystemClock.elapsedRealtime() - stopwatchStartedAtMs)
                    stopwatchBaseElapsedMs = finalElapsed
                    stopwatchTickerJob?.cancel()
                    stopwatchTickerJob = null
                    _stopwatch.update { it.copy(elapsedMs = finalElapsed, isRunning = false) }
                }
                ClockToolExecutionOutcome(
                    summary = "Stopwatch paused at ${formatStopwatchMs(_stopwatch.value.elapsedMs)} elapsed",
                    success = true
                )
            }
            "lap", "split" -> {
                val nowElapsed = if (_stopwatch.value.isRunning) {
                    stopwatchBaseElapsedMs + (SystemClock.elapsedRealtime() - stopwatchStartedAtMs)
                } else {
                    _stopwatch.value.elapsedMs
                }
                if (!_stopwatch.value.isRunning && nowElapsed == 0L) {
                    return ClockToolExecutionOutcome(
                        summary = "Start the stopwatch first before recording a lap split.",
                        success = false
                    )
                }
                val prevTotal = _stopwatch.value.laps.firstOrNull()?.totalMs ?: 0L
                val lapNum = _stopwatch.value.laps.size + 1
                val lap = StopwatchLap(
                    lapNumber = lapNum,
                    splitMs = (nowElapsed - prevTotal).coerceAtLeast(0L),
                    totalMs = nowElapsed
                )
                _stopwatch.update { it.copy(elapsedMs = nowElapsed, laps = listOf(lap) + it.laps) }
                ClockToolExecutionOutcome(
                    summary = "Recorded Lap #$lapNum (+${formatStopwatchMs(lap.splitMs)} · total ${formatStopwatchMs(lap.totalMs)})",
                    success = true
                )
            }
            "clearlaps", "clear_laps" -> {
                val prevCount = _stopwatch.value.laps.size
                _stopwatch.update { it.copy(laps = emptyList()) }
                ClockToolExecutionOutcome(
                    summary = "Cleared $prevCount stopwatch lap${if (prevCount == 1) "" else "s"} (elapsed ${formatStopwatchMs(_stopwatch.value.elapsedMs)} preserved)",
                    success = true
                )
            }
            "listlaps", "list_laps", "laps" -> {
                val laps = _stopwatch.value.laps
                if (laps.isEmpty()) {
                    ClockToolExecutionOutcome(
                        summary = "No stopwatch laps recorded yet (${formatStopwatchMs(_stopwatch.value.elapsedMs)} elapsed).",
                        success = true
                    )
                } else {
                    val detail = laps.take(5).joinToString(" | ") { l ->
                        "Lap #${l.lapNumber}: +${formatStopwatchMs(l.splitMs)} (${formatStopwatchMs(l.totalMs)})"
                    }
                    ClockToolExecutionOutcome(
                        summary = "${laps.size} lap(s): $detail",
                        success = true
                    )
                }
            }
            "reset", "clear" -> {
                stopwatchTickerJob?.cancel()
                stopwatchTickerJob = null
                stopwatchBaseElapsedMs = 0L
                _stopwatch.value = StopwatchUiState()
                ClockToolExecutionOutcome(
                    summary = "Stopwatch reset to 00:00.00",
                    success = true
                )
            }
            "status", "elapsed", "get" -> getStopwatchStatusSummary()
            else -> ClockToolExecutionOutcome(
                summary = "Unsupported stopwatch action '$action'. Supported: start, pause, resume, lap, clearLaps, listLaps, reset, status.",
                success = false
            )
        }
    }

    private fun ensureStopwatchTickerRunning() {
        if (stopwatchTickerJob?.isActive == true) return
        stopwatchTickerJob = scope.launch {
            while (isActive && _stopwatch.value.isRunning) {
                val nowElapsed = stopwatchBaseElapsedMs + (SystemClock.elapsedRealtime() - stopwatchStartedAtMs)
                _stopwatch.update { it.copy(elapsedMs = nowElapsed) }
                delay(50L)
            }
            stopwatchTickerJob = null
        }
    }

    fun buildClockContextForAi(): String {
        val sb = StringBuilder()
        sb.appendLine("AlarmManager Exact Permission: ${if (canScheduleExactAlarms()) "GRANTED" else "INEXACT_FALLBACK"}")
        val alarmList = alarms.value
        if (alarmList.isEmpty()) {
            sb.appendLine("Alarms: none set.")
        } else {
            sb.appendLine("Current Alarms (${alarmList.size}):")
            alarmList.forEach { a ->
                sb.appendLine("- id=${a.id}, time=${formatAlarmTime(a.hour, a.minute)}, label=\"${a.label}\", enabled=${a.enabled}")
            }
        }
        val timerList = timers.value
        if (timerList.isEmpty()) {
            sb.appendLine("Countdown Timers: none active.")
        } else {
            sb.appendLine("Live Countdown Timers (exact remaining right now):")
            timerList.forEach { t ->
                val st = when {
                    t.isFinished -> "finished"
                    t.isRunning -> "running"
                    else -> "paused"
                }
                sb.appendLine("- id=${t.id}, label=\"${t.label}\", remaining=${formatDurationSeconds(t.remainingSeconds)} (${t.remainingSeconds}s / ${t.totalSeconds}s), status=$st")
            }
        }
        val sw = stopwatch.value
        sb.appendLine(
            "Live Stopwatch: elapsed=${formatStopwatchMs(sw.elapsedMs)}, running=${sw.isRunning}, laps=${sw.laps.size}"
        )
        return sb.toString().trim()
    }

    fun stopAllTickers() {
        timerTickerJob?.cancel()
        stopwatchTickerJob?.cancel()
        timerTickerJob = null
        stopwatchTickerJob = null
    }

    private fun findMatchingAlarm(list: List<ClockAlarmEntity>, query: String?): ClockAlarmEntity? {
        val q = query?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        return list.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: list.firstOrNull { it.label.lowercase(Locale.US).contains(q) }
            ?: list.firstOrNull { formatAlarmTime(it.hour, it.minute).lowercase(Locale.US).contains(q) }
            ?: list.firstOrNull { String.format(Locale.US, "%02d:%02d", it.hour, it.minute).contains(q) }
    }

    private fun findMatchingTimer(list: List<CountdownTimerItem>, query: String?): CountdownTimerItem? {
        val q = query?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        return list.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: list.firstOrNull { it.label.lowercase(Locale.US).contains(q) }
    }

    private fun vibrateTimerDone() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 180), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(250L)
            }
        } catch (_: Exception) {
            // Device may lack vibrator hardware
        }
    }

    companion object {
        fun formatAlarmTime(hour: Int, minute: Int): String {
            val amPm = if (hour >= 12) "PM" else "AM"
            val hour12 = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return String.format(Locale.US, "%d:%02d %s", hour12, minute, amPm)
        }

        fun formatDurationSeconds(totalSec: Int): String {
            val s = totalSec.coerceAtLeast(0)
            val hrs = s / 3600
            val mins = (s % 3600) / 60
            val secs = s % 60
            return if (hrs > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
        }

        fun formatStopwatchMs(elapsedMs: Long): String {
            val safe = elapsedMs.coerceAtLeast(0L)
            val totalSeconds = safe / 1000L
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            val centis = (safe % 1000L) / 10L
            return String.format(Locale.US, "%02d:%02d.%02d", mins, secs, centis)
        }

        /**
         * Parses flexible time strings such as "7:30 AM", "19:45", "6 am", "07:05 pm", "8.15pm".
         */
        fun parseFlexibleTime(raw: String?): Pair<Int, Int>? {
            val text = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val regex = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\b""")
            val match = regex.find(text) ?: return null
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val amPm = match.groupValues[3].replace(".", "")
            if (amPm.isEmpty() && match.groupValues[2].isEmpty() && hour > 23) return null
            if (amPm == "pm" && hour in 1..11) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
        }

        /**
         * Parses flexible duration strings such as "5m", "1h 30m", "90 seconds", "02:30".
         */
        fun parseFlexibleDurationSeconds(raw: String?): Int? {
            val text = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            // HH:MM:SS or MM:SS
            Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""").matchEntire(text)?.let { m ->
                val a = m.groupValues[1].toIntOrNull() ?: 0
                val b = m.groupValues[2].toIntOrNull() ?: 0
                val c = m.groupValues[3].toIntOrNull()
                return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
            }
            var total = 0
            Regex("""(\d+)\s*(hours?|hrs?|h)\b""").findAll(text).forEach {
                total += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
            }
            Regex("""(\d+)\s*(minutes?|mins?|m)\b""").findAll(text).forEach {
                total += (it.groupValues[1].toIntOrNull() ?: 0) * 60
            }
            Regex("""(\d+)\s*(seconds?|secs?|s)\b""").findAll(text).forEach {
                total += (it.groupValues[1].toIntOrNull() ?: 0)
            }
            if (total > 0) return total
            text.toIntOrNull()?.let { return it }
            return null
        }
    }
}

package com.mote.app.control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ThermalStateLevel(val label: String) {
    NORMAL("Normal"),
    LIGHT("Warm"),
    MODERATE("Hot"),
    SEVERE("Throttling"),
    CRITICAL("Critical")
}

enum class BatteryAlertSeverity {
    NORMAL,
    ELEVATED,
    EXCESSIVE
}

data class BatteryTelemetrySnapshot(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val temperatureC: Float = 0f,
    val voltageMv: Int = 0,
    val dischargeMa: Int = 0,
    val powerWatts: Float = 0f,
    val hasCurrentSensor: Boolean = false,
    val thermalLevel: ThermalStateLevel = ThermalStateLevel.NORMAL,
    val activeModelName: String? = null,
    val isInferenceActive: Boolean = false,
    val sessionBatteryDropPct: Int = 0,
    val sessionPeakTempC: Float = 0f,
    val sessionPeakDrawMa: Int = 0,
    val alertSeverity: BatteryAlertSeverity = BatteryAlertSeverity.NORMAL,
    val alertTitle: String? = null,
    val alertReason: String? = null,
    val sampledAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Real Android battery, instantaneous current draw (mA / W), and thermal status monitor.
 * Uses Android `BatteryManager`, sticky `ACTION_BATTERY_CHANGED`, and `PowerManager.currentThermalStatus`
 * (zero dangerous permissions required) to detect excessive resource consumption during active AI inference.
 */
class BatteryResourceMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _telemetry = MutableStateFlow(readHardwareSample(null, false))
    val telemetry: StateFlow<BatteryTelemetrySnapshot> = _telemetry.asStateFlow()

    @Volatile
    private var trackedModelName: String? = null

    @Volatile
    private var trackedInferenceActive: Boolean = false

    @Volatile
    private var sessionStartBatteryPct: Int? = null

    @Volatile
    private var sessionPeakTempC: Float = 0f

    @Volatile
    private var sessionPeakDrawMa: Int = 0

    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    fun updateActiveModelState(modelName: String?, isInferenceActive: Boolean) {
        val modelChanged = trackedModelName != modelName
        val inferenceStarted = isInferenceActive && !trackedInferenceActive
        trackedModelName = modelName
        trackedInferenceActive = isInferenceActive

        if (modelName == null) {
            sessionStartBatteryPct = null
            sessionPeakTempC = 0f
            sessionPeakDrawMa = 0
        } else if (modelChanged || inferenceStarted || sessionStartBatteryPct == null) {
            val current = readHardwareSample(modelName, isInferenceActive)
            if (modelChanged || sessionStartBatteryPct == null) {
                sessionStartBatteryPct = current.batteryPercent
                sessionPeakTempC = current.temperatureC
                sessionPeakDrawMa = current.dischargeMa
            }
        }
        refreshNow()
    }

    fun refreshNow(): BatteryTelemetrySnapshot {
        val sample = readHardwareSample(trackedModelName, trackedInferenceActive)
        emitIfChanged(sample)
        return sample
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                emitIfChanged(readHardwareSample(trackedModelName, trackedInferenceActive))
                delay(if (trackedInferenceActive) 4_000L else 15_000L)
            }
        }
    }

    private fun emitIfChanged(next: BatteryTelemetrySnapshot) {
        val prev = _telemetry.value
        val changed = prev.batteryPercent != next.batteryPercent ||
            prev.isCharging != next.isCharging ||
            kotlin.math.abs(prev.temperatureC - next.temperatureC) >= 0.4f ||
            kotlin.math.abs(prev.dischargeMa - next.dischargeMa) >= 60 ||
            prev.thermalLevel != next.thermalLevel ||
            prev.activeModelName != next.activeModelName ||
            prev.isInferenceActive != next.isInferenceActive ||
            prev.sessionBatteryDropPct != next.sessionBatteryDropPct ||
            prev.alertSeverity != next.alertSeverity ||
            prev.alertTitle != next.alertTitle
        if (changed) {
            _telemetry.value = next
        }
    }

    private fun readHardwareSample(
        activeModelName: String?,
        isInferenceActive: Boolean
    ): BatteryTelemetrySnapshot {
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val intentPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val propPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryPct = when {
            propPct in 0..100 -> propPct
            intentPct in 0..100 -> intentPct
            else -> 100
        }

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val rawTempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = if (rawTempTenths > 0) rawTempTenths / 10f else 0f
        val voltageMv = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).coerceAtLeast(0)

        val rawCurrentUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: Int.MIN_VALUE
        val hasCurrentSensor = rawCurrentUa != Int.MIN_VALUE && rawCurrentUa != 0
        // Normalize OEM sign differences: report positive discharge mA when discharging, 0 when charging
        val dischargeMa = if (!hasCurrentSensor || isCharging) {
            0
        } else {
            val ma = abs(rawCurrentUa) / 1000
            if (ma == 0 && abs(rawCurrentUa) in 1..999) abs(rawCurrentUa) else ma
        }
        val powerWatts = if (dischargeMa > 0 && voltageMv > 0) {
            (dischargeMa.toFloat() * voltageMv.toFloat()) / 1_000_000f
        } else {
            0f
        }

        val thermalLevel = readThermalLevel(tempC)

        if (activeModelName != null) {
            if (sessionStartBatteryPct == null) sessionStartBatteryPct = batteryPct
            if (tempC > sessionPeakTempC) sessionPeakTempC = tempC
            if (dischargeMa > sessionPeakDrawMa) sessionPeakDrawMa = dischargeMa
        }
        val dropPct = if (activeModelName != null && !isCharging) {
            ((sessionStartBatteryPct ?: batteryPct) - batteryPct).coerceAtLeast(0)
        } else {
            0
        }

        val (severity, title, reason) = evaluateResourceAlert(
            activeModelName = activeModelName,
            isInferenceActive = isInferenceActive,
            batteryPct = batteryPct,
            isCharging = isCharging,
            tempC = tempC,
            dischargeMa = dischargeMa,
            powerWatts = powerWatts,
            thermalLevel = thermalLevel,
            sessionDropPct = dropPct
        )

        return BatteryTelemetrySnapshot(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            temperatureC = tempC,
            voltageMv = voltageMv,
            dischargeMa = dischargeMa,
            powerWatts = powerWatts,
            hasCurrentSensor = hasCurrentSensor,
            thermalLevel = thermalLevel,
            activeModelName = activeModelName,
            isInferenceActive = isInferenceActive,
            sessionBatteryDropPct = dropPct,
            sessionPeakTempC = sessionPeakTempC.coerceAtLeast(tempC),
            sessionPeakDrawMa = sessionPeakDrawMa.coerceAtLeast(dischargeMa),
            alertSeverity = severity,
            alertTitle = title,
            alertReason = reason,
            sampledAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun readThermalLevel(tempC: Float): ThermalStateLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            return when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> if (tempC >= 39f) ThermalStateLevel.LIGHT else ThermalStateLevel.NORMAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStateLevel.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStateLevel.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStateLevel.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStateLevel.CRITICAL
                else -> ThermalStateLevel.NORMAL
            }
        }
        return when {
            tempC >= 45f -> ThermalStateLevel.SEVERE
            tempC >= 41f -> ThermalStateLevel.MODERATE
            tempC >= 38f -> ThermalStateLevel.LIGHT
            else -> ThermalStateLevel.NORMAL
        }
    }

    private fun evaluateResourceAlert(
        activeModelName: String?,
        isInferenceActive: Boolean,
        batteryPct: Int,
        isCharging: Boolean,
        tempC: Float,
        dischargeMa: Int,
        powerWatts: Float,
        thermalLevel: ThermalStateLevel,
        sessionDropPct: Int
    ): Triple<BatteryAlertSeverity, String?, String?> {
        if (activeModelName == null) {
            return Triple(BatteryAlertSeverity.NORMAL, null, null)
        }
        val phaseLabel = if (isInferenceActive) "during active inference" else "while loaded in RAM"
        val reasons = mutableListOf<String>()

        var excessive = false
        var elevated = false

        if (thermalLevel == ThermalStateLevel.SEVERE || thermalLevel == ThermalStateLevel.CRITICAL || tempC >= 42.0f) {
            excessive = true
            reasons += String.format("battery temperature reached %.1f°C (%s)", tempC, thermalLevel.label)
        } else if (thermalLevel == ThermalStateLevel.MODERATE || tempC >= 39.5f) {
            if (isInferenceActive) excessive = true else elevated = true
            reasons += String.format("thermal load elevated at %.1f°C", tempC)
        }

        if (!isCharging && (dischargeMa >= 1200 || powerWatts >= 4.5f)) {
            excessive = true
            reasons += String.format("high discharge rate (%d mA / %.1f W)", dischargeMa, powerWatts)
        } else if (!isCharging && isInferenceActive && (dischargeMa >= 800 || powerWatts >= 3.0f)) {
            elevated = true
            reasons += String.format("elevated power draw (%d mA / %.1f W)", dischargeMa, powerWatts)
        }

        if (!isCharging && sessionDropPct >= 2) {
            excessive = true
            reasons += "battery dropped $sessionDropPct% in this session"
        }

        if (!isCharging && batteryPct <= 20) {
            if (isInferenceActive) excessive = true else elevated = true
            reasons += "battery is low ($batteryPct%)"
        }

        return when {
            excessive -> Triple(
                BatteryAlertSeverity.EXCESSIVE,
                "Excessive Resource Usage: $activeModelName",
                "$activeModelName is consuming heavy resources $phaseLabel: ${reasons.joinToString("; ")}. Consider switching to CPU, unloading from RAM, or using Cloud API."
            )
            elevated -> Triple(
                BatteryAlertSeverity.ELEVATED,
                "Elevated Resource Draw: $activeModelName",
                "$activeModelName has elevated power or thermal draw $phaseLabel: ${reasons.joinToString("; ")}."
            )
            else -> Triple(BatteryAlertSeverity.NORMAL, null, null)
        }
    }
}

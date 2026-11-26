package com.mote.app.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalHealthLevel(val label: String, val isElevated: Boolean) {
    COOL("Cool", false),
    WARM("Warm", false),
    MODERATE("Moderate Thermal", true),
    HOT("High Thermal", true),
    CRITICAL("Critical Thermal", true)
}

enum class AdaptiveInferenceTarget(val label: String) {
    LOCAL_ON_DEVICE("Local On-Device"),
    CLOUD_GEMINI("Cloud Gemini")
}

data class SystemHealthSnapshot(
    val batteryPct: Int = 100,
    val temperatureC: Float = 28.0f,
    val isCharging: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val thermalLevel: ThermalHealthLevel = ThermalHealthLevel.COOL,
    val recommendedTarget: AdaptiveInferenceTarget = AdaptiveInferenceTarget.LOCAL_ON_DEVICE,
    val recommendationReason: String = "Device battery & thermals are optimal for local AI.",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class AdaptiveRoutingDecision(
    val useLocalEngine: Boolean,
    val engineReason: String,
    val switchedAutomatically: Boolean
)

/**
 * Real-time Android SystemHealthManager service tracking live battery percentage,
 * battery temperature (°C), charging state, Power Saver mode, and Android PowerManager
 * thermal headroom to drive battery- & thermal-aware adaptive inference switching
 * between on-device LiteRT-LM and Cloud Gemini.
 */
class SystemHealthManager(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val _health = MutableStateFlow(readCurrentHealth(null))
    val health: StateFlow<SystemHealthSnapshot> = _health.asStateFlow()

    @Volatile
    private var receiverRegistered = false

    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    emitHealthIfChanged(
                        readCurrentHealth(
                            if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent else null
                        )
                    )
                }
            }
        }
    }

    init {
        register()
    }

    private fun register() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val sticky = ContextCompat.registerReceiver(
                appContext,
                healthReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
            emitHealthIfChanged(readCurrentHealth(sticky))
        } catch (_: Exception) {
            emitHealthIfChanged(readCurrentHealth(null))
        }
    }

    private fun emitHealthIfChanged(next: SystemHealthSnapshot) {
        val prev = _health.value
        val changed = prev.batteryPct != next.batteryPct ||
            prev.isCharging != next.isCharging ||
            kotlin.math.abs(prev.temperatureC - next.temperatureC) >= 0.4f ||
            prev.thermalLevel != next.thermalLevel ||
            prev.isPowerSaveMode != next.isPowerSaveMode ||
            prev.recommendedTarget != next.recommendedTarget ||
            prev.updatedAtEpochMs == 0L
        if (changed) {
            _health.value = next
        }
    }

    fun refreshNow(): SystemHealthSnapshot {
        val sticky = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        return readCurrentHealth(sticky).also { emitHealthIfChanged(it) }
    }

    /**
     * Resolves whether a given chat turn should run on the local on-device LiteRT-LM model
     * or offload to Cloud Gemini based on real battery percentage, battery temperature,
     * Power Saver status, and Android Thermal Status.
     */
    fun resolveInferenceRoute(
        adaptiveEnabled: Boolean,
        userPrefersLocal: Boolean,
        hasLocalModelReady: Boolean,
        hasAttachedImage: Boolean
    ): AdaptiveRoutingDecision {
        if (hasAttachedImage) {
            return AdaptiveRoutingDecision(
                useLocalEngine = false,
                engineReason = "Multimodal vision analysis routed to Cloud Gemini.",
                switchedAutomatically = false
            )
        }

        val snap = refreshNow()
        if (!adaptiveEnabled) {
            val useLocal = userPrefersLocal && hasLocalModelReady
            return AdaptiveRoutingDecision(
                useLocalEngine = useLocal,
                engineReason = if (useLocal) "Manual local preference active." else "Manual cloud preference active.",
                switchedAutomatically = false
            )
        }

        // Guard 1: Elevated thermal state or hot battery (>= 40.0°C) -> protect hardware via Cloud offload
        if (snap.thermalLevel.isElevated || snap.temperatureC >= CLOUD_OFFLOAD_TEMP_C) {
            return AdaptiveRoutingDecision(
                useLocalEngine = false,
                engineReason = "Adaptive Health: Offloaded to Cloud Gemini (${String.format("%.1f", snap.temperatureC)}°C · ${snap.thermalLevel.label}).",
                switchedAutomatically = userPrefersLocal
            )
        }

        // Guard 2: Low battery (<= 25% unplugged) or Power Saver ON -> offload to Cloud to preserve battery
        if ((!snap.isCharging && snap.batteryPct <= CLOUD_OFFLOAD_BATTERY_PCT) || snap.isPowerSaveMode) {
            val cause = if (snap.isPowerSaveMode) "Power Saver active" else "${snap.batteryPct}% unplugged"
            return AdaptiveRoutingDecision(
                useLocalEngine = false,
                engineReason = "Adaptive Health: Offloaded to Cloud Gemini ($cause).",
                switchedAutomatically = userPrefersLocal
            )
        }

        // Optimal thermals & power: run on local on-device model if one is downloaded/loaded!
        if (hasLocalModelReady) {
            return AdaptiveRoutingDecision(
                useLocalEngine = true,
                engineReason = "Adaptive Health: Running On-Device AI (${snap.batteryPct}% · ${String.format("%.1f", snap.temperatureC)}°C ${snap.thermalLevel.label}).",
                switchedAutomatically = !userPrefersLocal
            )
        }

        return AdaptiveRoutingDecision(
            useLocalEngine = false,
            engineReason = "Adaptive Health: Optimal hardware (${snap.batteryPct}% · ${String.format("%.1f", snap.temperatureC)}°C), using Cloud Gemini (no local model loaded).",
            switchedAutomatically = false
        )
    }

    private fun readCurrentHealth(batteryIntent: Intent?): SystemHealthSnapshot {
        val intent = batteryIntent ?: try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pctFromIntent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val pctFromMgr = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryPct = (if (pctFromIntent in 0..100) pctFromIntent else if (pctFromMgr in 0..100) pctFromMgr else 100)

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 290) ?: 290
        val tempC = (tempTenths / 10f).coerceIn(10f, 80f)

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val powerSave = powerManager?.isPowerSaveMode == true

        val osThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            } catch (_: Exception) {
                PowerManager.THERMAL_STATUS_NONE
            }
        } else {
            0
        }

        val thermalLevel = when {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && osThermal >= PowerManager.THERMAL_STATUS_CRITICAL) || tempC >= 45.0f ->
                ThermalHealthLevel.CRITICAL
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && osThermal >= PowerManager.THERMAL_STATUS_SEVERE) || tempC >= 42.0f ->
                ThermalHealthLevel.HOT
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && osThermal >= PowerManager.THERMAL_STATUS_MODERATE) || tempC >= CLOUD_OFFLOAD_TEMP_C ->
                ThermalHealthLevel.MODERATE
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && osThermal == PowerManager.THERMAL_STATUS_LIGHT) || tempC >= 36.5f ->
                ThermalHealthLevel.WARM
            else -> ThermalHealthLevel.COOL
        }

        val shouldOffloadCloud = thermalLevel.isElevated ||
            tempC >= CLOUD_OFFLOAD_TEMP_C ||
            powerSave ||
            (!isCharging && batteryPct <= CLOUD_OFFLOAD_BATTERY_PCT)

        val target = if (shouldOffloadCloud) {
            AdaptiveInferenceTarget.CLOUD_GEMINI
        } else {
            AdaptiveInferenceTarget.LOCAL_ON_DEVICE
        }

        val reason = when {
            thermalLevel.isElevated || tempC >= CLOUD_OFFLOAD_TEMP_C ->
                "High temp (${String.format("%.1f", tempC)}°C · ${thermalLevel.label}) — Cloud offload recommended"
            powerSave ->
                "Android Power Saver active — Cloud offload recommended"
            !isCharging && batteryPct <= CLOUD_OFFLOAD_BATTERY_PCT ->
                "Low battery ($batteryPct% unplugged) — Cloud offload recommended"
            isCharging ->
                "Charging ($batteryPct% · ${String.format("%.1f", tempC)}°C) — Local AI ready"
            else ->
                "Healthy ($batteryPct% · ${String.format("%.1f", tempC)}°C) — Local AI optimal"
        }

        return SystemHealthSnapshot(
            batteryPct = batteryPct,
            temperatureC = tempC,
            isCharging = isCharging,
            isPowerSaveMode = powerSave,
            thermalLevel = thermalLevel,
            recommendedTarget = target,
            recommendationReason = reason,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun shutdown() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(healthReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    companion object {
        const val CLOUD_OFFLOAD_BATTERY_PCT = 25
        const val CLOUD_OFFLOAD_TEMP_C = 40.0f
    }
}

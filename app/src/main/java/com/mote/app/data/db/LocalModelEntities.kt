package com.mote.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ModelCapability(val label: String) {
    OFFLINE_CHAT("Offline Chat"),
    ULTRA_FAST("Ultra Fast"),
    MULTILINGUAL("Multilingual"),
    CODING("Coding"),
    REASONING("Reasoning"),
    MOBILE_ACTIONS("Mobile Actions"),
    TOOL_CALLING("Tool Calling"),
    LONG_CONTEXT("Long Context");

    companion object {
        fun parseCsv(csv: String): List<ModelCapability> =
            csv.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }

        fun toCsv(list: List<ModelCapability>): String = list.joinToString(",") { it.name }
    }
}

@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val providerBrandName: String,
    val family: String,
    val quantLabel: String,
    val parameterCount: String,
    val approxSizeMb: Long,
    val minRamMb: Long,
    val contextLengthTokens: Int,
    val capabilitiesCsv: String,
    val fileName: String,
    val downloadUrl: String,
    val summary: String,
    val localFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val sha256Prefix: String? = null,
    val downloadedAtEpochMs: Long? = null,
    val lastUsedAtEpochMs: Long? = null,
    val isImported: Boolean = false,
    val lastErrorMessage: String? = null,
    val remoteEtag: String? = null,
    val remoteLastModified: String? = null,
    val remoteContentLength: Long = 0L,
    val lastUpdateCheckedEpochMs: Long? = null,
    val updateAvailable: Boolean = false,
    val updateStatusNote: String? = null
)

@Entity(tableName = "model_studio_prefs")
data class ModelStudioPrefsEntity(
    @PrimaryKey val id: Int = 1,
    val selectedCloudModelId: String = "gemini-3.8-flash",
    val activeLocalModelId: String? = null,
    val preferLocalOfflineChat: Boolean = false,
    val preferredBackend: String = "CPU",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 1024,
    val contextWindowTurns: Int = 8,
    val wifiOnlyDownloads: Boolean = false,
    val requireBatteryNotLow: Boolean = true,
    val autoLoadLastModel: Boolean = true,
    val ecoInferenceEnabled: Boolean = true,
    val autoCheckModelUpdates: Boolean = true,
    val lastModelUpdateCheckEpochMs: Long? = null,
    val lastModelUpdateSummary: String? = null,
    val toolAlarmEnabled: Boolean = true,
    val toolTimerEnabled: Boolean = true,
    val toolStopwatchEnabled: Boolean = true,
    val toolCalendarEnabled: Boolean = true,
    val toolGenUiEnabled: Boolean = true,
    val toolCameraEnabled: Boolean = true,
    val toolScreenCaptureEnabled: Boolean = true,
    val toolAppLauncherEnabled: Boolean = true,
    val toolNavigationEnabled: Boolean = true,
    val darkThemeEnabled: Boolean = false
)

@Entity(tableName = "model_benchmarks")
data class ModelBenchmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val modelId: String,
    val modelDisplayName: String,
    val backendUsed: String,
    val promptProfile: String,
    val tokensPerSec: Float,
    val ttftMs: Long,
    val totalLatencyMs: Long,
    val generatedTokens: Int,
    val deviceModel: String,
    val socLabel: String,
    val cpuCores: Int,
    val peakTempC: Float,
    val peakDrawMa: Int,
    val benchmarkedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "clock_alarms")
data class ClockAlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val daysCsv: String = "",
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dateIso: String,
    val startTime: String,
    val endTime: String,
    val location: String = "",
    val notes: String = "",
    val syncedToSystemCalendar: Boolean = false,
    val androidProviderEventId: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "tool_execution_logs")
data class ToolExecutionLogEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val toolId: String,
    val operation: String,
    val success: Boolean,
    val blocked: Boolean,
    val feedback: String
)

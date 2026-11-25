package com.mote.app.data

import android.content.Context
import android.net.Uri
import com.mote.app.data.db.LocalModelEntity
import com.mote.app.data.db.ModelStudioPrefsEntity
import com.mote.app.model.BotPersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MoteSettingsBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val app: String = "Mote AI",
    val preferences: BackupPreferencesDto,
    val modelConfigs: List<BackupModelConfigDto> = emptyList(),
    val botPersonas: List<BackupBotPersonaDto> = emptyList()
)

@Serializable
data class BackupPreferencesDto(
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

@Serializable
data class BackupModelConfigDto(
    val id: String,
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
    val isImported: Boolean = false
)

@Serializable
data class BackupBotPersonaDto(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val colorHex: Long,
    val active: Boolean
)

data class BackupOperationResult(
    val success: Boolean,
    val message: String,
    val preferencesRestored: Boolean = false,
    val modelsCount: Int = 0,
    val botsCount: Int = 0
)

/**
 * Zero-permission Storage Access Framework (SAF) JSON exporter and importer for
 * Mote user settings, model configurations, tool permissions, and bot personas.
 */
object SettingsBackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun buildPayload(
        prefs: ModelStudioPrefsEntity,
        models: List<LocalModelEntity>,
        bots: List<BotPersona>
    ): MoteSettingsBackupPayload {
        return MoteSettingsBackupPayload(
            schemaVersion = 1,
            exportedAtEpochMs = System.currentTimeMillis(),
            preferences = BackupPreferencesDto(
                selectedCloudModelId = prefs.selectedCloudModelId,
                activeLocalModelId = prefs.activeLocalModelId,
                preferLocalOfflineChat = prefs.preferLocalOfflineChat,
                preferredBackend = prefs.preferredBackend,
                temperature = prefs.temperature,
                topP = prefs.topP,
                maxOutputTokens = prefs.maxOutputTokens,
                contextWindowTurns = prefs.contextWindowTurns,
                wifiOnlyDownloads = prefs.wifiOnlyDownloads,
                requireBatteryNotLow = prefs.requireBatteryNotLow,
                autoLoadLastModel = prefs.autoLoadLastModel,
                ecoInferenceEnabled = prefs.ecoInferenceEnabled,
                autoCheckModelUpdates = prefs.autoCheckModelUpdates,
                toolAlarmEnabled = prefs.toolAlarmEnabled,
                toolTimerEnabled = prefs.toolTimerEnabled,
                toolStopwatchEnabled = prefs.toolStopwatchEnabled,
                toolCalendarEnabled = prefs.toolCalendarEnabled,
                toolGenUiEnabled = prefs.toolGenUiEnabled,
                toolCameraEnabled = prefs.toolCameraEnabled,
                toolScreenCaptureEnabled = prefs.toolScreenCaptureEnabled,
                toolAppLauncherEnabled = prefs.toolAppLauncherEnabled,
                toolNavigationEnabled = prefs.toolNavigationEnabled,
                darkThemeEnabled = prefs.darkThemeEnabled
            ),
            modelConfigs = models.map { m ->
                BackupModelConfigDto(
                    id = m.id,
                    displayName = m.displayName,
                    providerBrandName = m.providerBrandName,
                    family = m.family,
                    quantLabel = m.quantLabel,
                    parameterCount = m.parameterCount,
                    approxSizeMb = m.approxSizeMb,
                    minRamMb = m.minRamMb,
                    contextLengthTokens = m.contextLengthTokens,
                    capabilitiesCsv = m.capabilitiesCsv,
                    fileName = m.fileName,
                    downloadUrl = m.downloadUrl,
                    summary = m.summary,
                    isImported = m.isImported
                )
            },
            botPersonas = bots.map { b ->
                BackupBotPersonaDto(
                    id = b.id,
                    name = b.name,
                    systemPrompt = b.systemPrompt,
                    colorHex = b.colorHex,
                    active = b.active
                )
            }
        )
    }

    suspend fun writeBackupToUri(
        context: Context,
        uri: Uri,
        payload: MoteSettingsBackupPayload
    ): BackupOperationResult = withContext(Dispatchers.IO) {
        try {
            val serialized = json.encodeToString(payload)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext BackupOperationResult(
                    success = false,
                    message = "Unable to open destination file for writing."
                )
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(serialized)
            }
            BackupOperationResult(
                success = true,
                message = "Exported settings, ${payload.modelConfigs.size} model config(s), and ${payload.botPersonas.size} persona(s) to JSON.",
                modelsCount = payload.modelConfigs.size,
                botsCount = payload.botPersonas.size
            )
        } catch (e: Exception) {
            BackupOperationResult(
                success = false,
                message = "Export failed: ${e.localizedMessage ?: "I/O error"}"
            )
        }
    }

    suspend fun readBackupFromUri(
        context: Context,
        uri: Uri
    ): Result<MoteSettingsBackupPayload> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Could not open selected JSON file."))
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (raw.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Selected JSON file is empty."))
            }
            val parsed = json.decodeFromString<MoteSettingsBackupPayload>(raw)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid Mote backup JSON: ${e.localizedMessage ?: "parse error"}"))
        }
    }

    fun toPrefsEntity(
        dto: BackupPreferencesDto,
        existing: ModelStudioPrefsEntity
    ): ModelStudioPrefsEntity {
        return existing.copy(
            selectedCloudModelId = dto.selectedCloudModelId.ifBlank { existing.selectedCloudModelId },
            activeLocalModelId = dto.activeLocalModelId,
            preferLocalOfflineChat = dto.preferLocalOfflineChat,
            preferredBackend = dto.preferredBackend.ifBlank { "CPU" },
            temperature = dto.temperature.coerceIn(0f, 1.5f),
            topP = dto.topP.coerceIn(0.1f, 1.0f),
            maxOutputTokens = dto.maxOutputTokens.coerceIn(256, 4096),
            contextWindowTurns = dto.contextWindowTurns.coerceIn(2, 20),
            wifiOnlyDownloads = dto.wifiOnlyDownloads,
            requireBatteryNotLow = dto.requireBatteryNotLow,
            autoLoadLastModel = dto.autoLoadLastModel,
            ecoInferenceEnabled = dto.ecoInferenceEnabled,
            autoCheckModelUpdates = dto.autoCheckModelUpdates,
            toolAlarmEnabled = dto.toolAlarmEnabled,
            toolTimerEnabled = dto.toolTimerEnabled,
            toolStopwatchEnabled = dto.toolStopwatchEnabled,
            toolCalendarEnabled = dto.toolCalendarEnabled,
            toolGenUiEnabled = dto.toolGenUiEnabled,
            toolCameraEnabled = dto.toolCameraEnabled,
            toolScreenCaptureEnabled = dto.toolScreenCaptureEnabled,
            toolAppLauncherEnabled = dto.toolAppLauncherEnabled,
            toolNavigationEnabled = dto.toolNavigationEnabled,
            darkThemeEnabled = dto.darkThemeEnabled
        )
    }
}

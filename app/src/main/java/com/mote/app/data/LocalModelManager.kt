package com.mote.app.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mote.app.ai.ChatStreamEvent
import com.mote.app.ai.GenUiToolRegistry
import com.mote.app.ai.InferenceMetrics
import com.mote.app.ai.LiteRtDiagnosticLogEntry
import com.mote.app.ai.LiteRtDiagnosticLogger
import com.mote.app.ai.LiteRtDiagnosticSummary
import com.mote.app.ai.LocalHardwareBackend
import com.mote.app.ai.OnDeviceLiteRtEngine
import com.mote.app.control.BatteryResourceMonitor
import com.mote.app.control.BatteryTelemetrySnapshot
import com.mote.app.control.CalendarToolsManager
import com.mote.app.control.ClockToolsManager
import com.mote.app.control.DeviceAutomationToolsManager
import com.mote.app.control.ToolPermissionInspector
import com.mote.app.control.ToolPermissionsSnapshot
import com.mote.app.data.db.LocalModelDatabase
import com.mote.app.data.db.LocalModelEntity
import com.mote.app.data.db.ModelBenchmarkEntity
import com.mote.app.data.db.ModelCapability
import com.mote.app.data.db.ModelStudioPrefsEntity
import com.mote.app.model.BotPersona
import com.mote.app.ui.components.LobeModelBrand
import com.mote.app.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class LocalModelDownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

enum class LocalModelRuntimeFormat(val label: String) {
    LITERT_LM("LiteRT-LM")
}

data class LocalModelEntry(
    val id: String,
    val displayName: String,
    val providerBrand: LobeModelBrand,
    val family: String,
    val runtimeFormat: LocalModelRuntimeFormat = LocalModelRuntimeFormat.LITERT_LM,
    val quantLabel: String,
    val parameterCount: String,
    val approxSizeMb: Long,
    val minRamMb: Long,
    val contextLengthTokens: Int,
    val capabilities: List<ModelCapability>,
    val fileName: String,
    val downloadUrl: String,
    val summary: String,
    val localFilePath: String? = null,
    val sha256Prefix: String? = null,
    val downloadedAtEpochMs: Long? = null,
    val lastUsedAtEpochMs: Long? = null,
    val status: LocalModelDownloadStatus = LocalModelDownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val isImported: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateStatusNote: String? = null,
    val lastUpdateCheckedEpochMs: Long? = null
) {
    val progressFraction: Float
        get() = when {
            status == LocalModelDownloadStatus.DOWNLOADED -> 1f
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
            approxSizeMb > 0L -> (downloadedBytes.toFloat() / (approxSizeMb * 1024f * 1024f)).coerceIn(0f, 0.99f)
            else -> 0f
        }

    val etaSeconds: Long?
        get() {
            if (status != LocalModelDownloadStatus.DOWNLOADING || speedBytesPerSec <= 0L) return null
            val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0L)
            return remaining / speedBytesPerSec
        }
}

data class LoadedLocalModelState(
    val modelId: String,
    val displayName: String,
    val brand: LobeModelBrand,
    val family: String,
    val backend: LocalHardwareBackend,
    val filePath: String,
    val fileSizeBytes: Long,
    val sha256Prefix: String?,
    val capabilities: List<ModelCapability>,
    val loadedAtEpochMs: Long
) {
    val supportsOnDeviceInference: Boolean = true
}

data class DeviceHardwareSnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val modelsDiskUsageBytes: Long,
    val tempCacheBytes: Long,
    val freeStorageMb: Long,
    val deviceModel: String,
    val socLabel: String,
    val primaryAbi: String
)

enum class BenchmarkPromptPreset(
    val label: String,
    val prompt: String,
    val systemPrompt: String
) {
    QUICK_QA(
        label = "Quick Q&A",
        prompt = "Explain in two clear sentences how on-device mobile AI protects user privacy.",
        systemPrompt = "You are a concise, accurate mobile AI assistant."
    ),
    REASONING(
        label = "Reasoning",
        prompt = "A phone battery loses 3% every 12 minutes of heavy gaming. How much battery is left after 48 minutes starting from 85%? Show brief steps.",
        systemPrompt = "Show short, clear logical steps and the final answer."
    ),
    CODING(
        label = "Code Gen",
        prompt = "Write a short Kotlin function that checks if a string is a palindrome ignoring case and spaces.",
        systemPrompt = "Return clean, concise Kotlin code with one sentence of explanation."
    )
}

data class LiveBenchmarkProgress(
    val modelId: String,
    val preset: BenchmarkPromptPreset,
    val elapsedMs: Long,
    val approxTokens: Int,
    val liveTokensPerSec: Float,
    val partialPreview: String
)

data class StorageVerificationSummary(
    val verifiedCount: Int,
    val repairedStaleRecords: Int,
    val clearedTempBytes: Long
)

data class ModelCacheClearSummary(
    val clearedModelCount: Int,
    val deletedOrphanFiles: Int,
    val reclaimedBytes: Long,
    val preservedActiveModelName: String?
)

/**
 * Room + WorkManager + LiteRT-LM repository for real on-device phone models.
 * - Tracks verified file paths, SHA-256 fingerprints, capabilities, and studio preferences in Room.
 * - Uses WorkManager for reliable background fetching with live progress reporting.
 * - Never fakes downloaded or active state: every status is reconciled against actual files & engine memory.
 */
class LocalModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val db = LocalModelDatabase.getInstance(appContext)
    private val dao = db.localModelDao()
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liteRtEngine = OnDeviceLiteRtEngine(appContext.cacheDir.absolutePath)
    val diagnosticLogger: LiteRtDiagnosticLogger = liteRtEngine.diagnosticLogger
    val diagnosticLogs: StateFlow<List<LiteRtDiagnosticLogEntry>> = diagnosticLogger.logs
    val diagnosticSummary: StateFlow<LiteRtDiagnosticSummary> = diagnosticLogger.summary
    private val batteryMonitor = BatteryResourceMonitor(appContext)
    val clockTools = ClockToolsManager(appContext)
    val calendarTools = CalendarToolsManager(appContext)
    val deviceTools = DeviceAutomationToolsManager(appContext)

    private val _loadedModel = MutableStateFlow<LoadedLocalModelState?>(null)
    val loadedModel: StateFlow<LoadedLocalModelState?> = _loadedModel.asStateFlow()

    private val _loadingModelId = MutableStateFlow<String?>(null)
    val loadingModelId: StateFlow<String?> = _loadingModelId.asStateFlow()

    private val _liveBenchmark = MutableStateFlow<LiveBenchmarkProgress?>(null)
    val liveBenchmark: StateFlow<LiveBenchmarkProgress?> = _liveBenchmark.asStateFlow()

    private val _hardware = MutableStateFlow(readHardwareSnapshot())
    val hardware: StateFlow<DeviceHardwareSnapshot> = _hardware.asStateFlow()

    val batteryTelemetry: StateFlow<BatteryTelemetrySnapshot> = batteryMonitor.telemetry

    val allBenchmarks: StateFlow<List<ModelBenchmarkEntity>> = dao.observeAllBenchmarks()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val prefs: StateFlow<ModelStudioPrefsEntity> = dao.observePrefs()
        .map { it ?: ModelStudioPrefsEntity() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ModelStudioPrefsEntity()
        )

    val isCheckingModelUpdates: StateFlow<Boolean> =
        workManager.getWorkInfosByTagFlow(ModelUpdateCheckWorker.TAG_MODEL_UPDATE_CHECK)
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    val toolRegistry = GenUiToolRegistry(
        clockTools = clockTools,
        calendarTools = calendarTools,
        deviceTools = deviceTools,
        dao = dao,
        scope = scope,
        isToolEnabledProvider = { toolId ->
            val p = prefs.value
            when (toolId) {
                GenUiToolRegistry.TOOL_ALARM_MANAGER -> p.toolAlarmEnabled
                GenUiToolRegistry.TOOL_COUNTDOWN_TIMER -> p.toolTimerEnabled
                GenUiToolRegistry.TOOL_STOPWATCH -> p.toolStopwatchEnabled
                GenUiToolRegistry.TOOL_CALENDAR -> p.toolCalendarEnabled
                GenUiToolRegistry.TOOL_CAMERA -> p.toolCameraEnabled
                GenUiToolRegistry.TOOL_SCREEN_CAPTURE -> p.toolScreenCaptureEnabled
                GenUiToolRegistry.TOOL_APP_LAUNCHER -> p.toolAppLauncherEnabled
                GenUiToolRegistry.TOOL_NAVIGATION -> p.toolNavigationEnabled
                GenUiToolRegistry.TOOL_GEN_UI_WIDGETS -> p.toolGenUiEnabled
                else -> true
            }
        }
    )

    private val _toolPermissions = MutableStateFlow(ToolPermissionInspector.inspect(appContext))
    val toolPermissions: StateFlow<ToolPermissionsSnapshot> = _toolPermissions.asStateFlow()

    val ecoInferenceRestricted: StateFlow<Boolean> = combine(
        prefs,
        batteryTelemetry
    ) { p, b ->
        p.ecoInferenceEnabled && !b.isCharging && b.batteryPercent < ECO_BATTERY_THRESHOLD_PCT
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    val backendChoice: StateFlow<LocalHardwareBackend> = prefs
        .map { entity ->
            LocalHardwareBackend.entries.firstOrNull { it.name == entity.preferredBackend }
                ?: LocalHardwareBackend.CPU
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = LocalHardwareBackend.CPU
        )

    private val activeWorkMapFlow: Flow<Map<String, WorkInfo>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG_MODEL_DOWNLOAD)
            .map { infos ->
                buildMap {
                    for (info in infos) {
                        val modelIdTag = info.tags
                            .firstOrNull { it.startsWith(MODEL_ID_TAG_PREFIX) }
                            ?.removePrefix(MODEL_ID_TAG_PREFIX)
                            ?: info.progress.getString(ModelDownloadWorker.KEY_MODEL_ID)
                            ?: continue
                        val existing = this[modelIdTag]
                        if (existing == null || workPriority(info.state) > workPriority(existing.state)) {
                            this[modelIdTag] = info
                        }
                    }
                }
            }

    val models: StateFlow<List<LocalModelEntry>> = combine(
        dao.observeAllModels(),
        activeWorkMapFlow
    ) { entities, workByModelId ->
        entities.map { entity -> mapEntityToEntry(entity, workByModelId[entity.id]) }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    init {
        scope.launch {
            seedAndReconcileCatalog()
            val savedPrefs = dao.getPrefsOnce() ?: ModelStudioPrefsEntity(
                darkThemeEnabled = ThemePreferenceStore.init(appContext)
            ).also { dao.upsertPrefs(it) }
            ThemePreferenceStore.setDarkTheme(appContext, savedPrefs.darkThemeEnabled)
            ModelUpdateCheckWorker.schedulePeriodic(
                context = appContext,
                enabled = savedPrefs.autoCheckModelUpdates,
                wifiOnly = savedPrefs.wifiOnlyDownloads
            )
            val currentBattery = batteryMonitor.refreshNow()
            val ecoBlocksAutoLoad = savedPrefs.ecoInferenceEnabled &&
                !currentBattery.isCharging &&
                currentBattery.batteryPercent < ECO_BATTERY_THRESHOLD_PCT
            val lastActiveId = savedPrefs.activeLocalModelId
            if (savedPrefs.autoLoadLastModel && !ecoBlocksAutoLoad && !lastActiveId.isNullOrBlank()) {
                val record = dao.getModelById(lastActiveId)
                val file = record?.localFilePath?.let { File(it) }
                if (file != null && file.exists() && file.length() > 1024L) {
                    loadModel(lastActiveId)
                } else {
                    updatePrefs { it.copy(activeLocalModelId = null, preferLocalOfflineChat = false) }
                }
            }
        }

        // Automatically restrict background downloads & idle local RAM processing when battery drops below 20%
        scope.launch {
            ecoInferenceRestricted.collect { restricted ->
                if (restricted) {
                    workManager.cancelAllWorkByTag(ModelDownloadWorker.TAG_MODEL_DOWNLOAD)
                    if (_loadedModel.value != null && !batteryTelemetry.value.isInferenceActive) {
                        unloadModel()
                    }
                }
            }
        }
    }

    suspend fun updatePrefs(transform: (ModelStudioPrefsEntity) -> ModelStudioPrefsEntity) {
        withContext(Dispatchers.IO) {
            val current = dao.getPrefsOnce() ?: ModelStudioPrefsEntity()
            val updated = transform(current)
            dao.upsertPrefs(updated)
            ThemePreferenceStore.setDarkTheme(appContext, updated.darkThemeEnabled)
            if (updated.autoCheckModelUpdates != current.autoCheckModelUpdates ||
                updated.wifiOnlyDownloads != current.wifiOnlyDownloads
            ) {
                ModelUpdateCheckWorker.schedulePeriodic(
                    context = appContext,
                    enabled = updated.autoCheckModelUpdates,
                    wifiOnly = updated.wifiOnlyDownloads
                )
            }
        }
    }

    fun checkForModelUpdatesNow(autoDownload: Boolean = true) {
        ModelUpdateCheckWorker.enqueueImmediateCheck(appContext, autoDownload = autoDownload)
    }

    suspend fun exportSettingsBackup(uri: Uri, bots: List<BotPersona>): BackupOperationResult {
        return withContext(Dispatchers.IO) {
            val currentPrefs = dao.getPrefsOnce() ?: ModelStudioPrefsEntity()
            val allModels = dao.getAllModelsOnce()
            val payload = SettingsBackupManager.buildPayload(currentPrefs, allModels, bots)
            SettingsBackupManager.writeBackupToUri(appContext, uri, payload)
        }
    }

    suspend fun importSettingsBackup(uri: Uri): Result<Pair<BackupOperationResult, List<BotPersona>>> {
        return withContext(Dispatchers.IO) {
            val parsed = SettingsBackupManager.readBackupFromUri(appContext, uri)
                .getOrElse { return@withContext Result.failure(it) }

            val existingPrefs = dao.getPrefsOnce() ?: ModelStudioPrefsEntity()
            val mergedPrefs = SettingsBackupManager.toPrefsEntity(parsed.preferences, existingPrefs)
            dao.upsertPrefs(mergedPrefs)
            ThemePreferenceStore.setDarkTheme(appContext, mergedPrefs.darkThemeEnabled)
            ModelUpdateCheckWorker.schedulePeriodic(
                context = appContext,
                enabled = mergedPrefs.autoCheckModelUpdates,
                wifiOnly = mergedPrefs.wifiOnlyDownloads
            )

            var importedModels = 0
            for (cfg in parsed.modelConfigs) {
                val existing = dao.getModelById(cfg.id)
                if (existing == null) {
                    dao.upsertModel(
                        LocalModelEntity(
                            id = cfg.id,
                            displayName = cfg.displayName,
                            providerBrandName = cfg.providerBrandName,
                            family = cfg.family,
                            quantLabel = cfg.quantLabel,
                            parameterCount = cfg.parameterCount,
                            approxSizeMb = cfg.approxSizeMb,
                            minRamMb = cfg.minRamMb,
                            contextLengthTokens = cfg.contextLengthTokens,
                            capabilitiesCsv = cfg.capabilitiesCsv,
                            fileName = cfg.fileName,
                            downloadUrl = cfg.downloadUrl,
                            summary = cfg.summary,
                            isImported = cfg.isImported
                        )
                    )
                    importedModels++
                } else {
                    // Preserve on-device verified local file path while restoring any custom metadata
                    dao.upsertModel(
                        existing.copy(
                            displayName = cfg.displayName,
                            downloadUrl = cfg.downloadUrl.ifBlank { existing.downloadUrl },
                            summary = cfg.summary.ifBlank { existing.summary }
                        )
                    )
                    importedModels++
                }
            }

            val restoredBots = parsed.botPersonas.map { dto ->
                BotPersona(
                    id = dto.id,
                    name = dto.name,
                    systemPrompt = dto.systemPrompt,
                    colorHex = dto.colorHex,
                    active = dto.active
                )
            }

            val result = BackupOperationResult(
                success = true,
                message = "Restored settings, $importedModels model config(s), and ${restoredBots.size} persona(s) from JSON.",
                preferencesRestored = true,
                modelsCount = importedModels,
                botsCount = restoredBots.size
            )
            Result.success(result to restoredBots)
        }
    }

    fun selectBackend(backend: LocalHardwareBackend) {
        scope.launch {
            updatePrefs { it.copy(preferredBackend = backend.name) }
        }
    }

    fun startDownload(modelId: String) {
        scope.launch {
            val entity = dao.getModelById(modelId) ?: return@launch
            if (entity.downloadUrl.isBlank()) return@launch
            val currentPrefs = dao.getPrefsOnce() ?: ModelStudioPrefsEntity()
            val battery = batteryMonitor.refreshNow()

            if (currentPrefs.ecoInferenceEnabled && !battery.isCharging && battery.batteryPercent < ECO_BATTERY_THRESHOLD_PCT) {
                dao.markDownloadError(
                    modelId,
                    "Eco-Inference restricted background download (battery at ${battery.batteryPercent}% < 20%). Plug in or toggle Eco-Inference off."
                )
                return@launch
            }

            dao.markDownloadError(modelId, null)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (currentPrefs.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresBatteryNotLow(currentPrefs.requireBatteryNotLow || currentPrefs.ecoInferenceEnabled)
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .addTag(ModelDownloadWorker.TAG_MODEL_DOWNLOAD)
                .addTag("$MODEL_ID_TAG_PREFIX$modelId")
                .setInputData(
                    workDataOf(
                        ModelDownloadWorker.KEY_MODEL_ID to entity.id,
                        ModelDownloadWorker.KEY_DOWNLOAD_URL to entity.downloadUrl,
                        ModelDownloadWorker.KEY_FILE_NAME to entity.fileName,
                        ModelDownloadWorker.KEY_APPROX_SIZE_MB to entity.approxSizeMb
                    )
                )
                .build()

            workManager.enqueueUniqueWork(
                ModelDownloadWorker.uniqueWorkName(modelId),
                ExistingWorkPolicy.KEEP,
                request
            )
            _hardware.value = readHardwareSnapshot()
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueWorkName(modelId))
        scope.launch {
            val entity = dao.getModelById(modelId)
            if (entity != null) {
                File(modelsDir, "${entity.fileName}.part").delete()
                dao.markDownloadError(modelId, null)
            }
            _hardware.value = readHardwareSnapshot()
        }
    }

    fun addCustomUrlAndDownload(displayName: String, url: String) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) return
        val rawName = cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank {
            "custom_model_${System.currentTimeMillis()}.litertlm"
        }
        val safeName = rawName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val id = "custom_${safeName.hashCode().toUInt()}"
        scope.launch {
            val existing = dao.getModelById(id)
            if (existing == null) {
                val brand = LobeModelBrand.fromModelId(safeName)
                dao.upsertModel(
                    LocalModelEntity(
                        id = id,
                        displayName = displayName.trim().ifBlank { safeName.substringBeforeLast('.') },
                        providerBrandName = brand.name,
                        family = "Custom LiteRT",
                        quantLabel = safeName.substringAfterLast('.', "LITERT").uppercase(),
                        parameterCount = "Custom",
                        approxSizeMb = 250L,
                        minRamMb = 2048L,
                        contextLengthTokens = 2048,
                        capabilitiesCsv = ModelCapability.toCsv(
                            listOf(ModelCapability.OFFLINE_CHAT, ModelCapability.ULTRA_FAST)
                        ),
                        fileName = safeName,
                        downloadUrl = cleanUrl,
                        summary = "Custom on-device LiteRT-LM bundle fetched via WorkManager.",
                        isImported = true
                    )
                )
            }
            startDownload(id)
        }
    }

    fun deleteModel(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueWorkName(modelId))
        scope.launch {
            val entity = dao.getModelById(modelId) ?: return@launch
            if (_loadedModel.value?.modelId == modelId) {
                unloadModel()
            }
            entity.localFilePath?.let { File(it).delete() }
            File(modelsDir, entity.fileName).delete()
            File(modelsDir, "${entity.fileName}.part").delete()

            val currentPrefs = dao.getPrefsOnce()
            if (currentPrefs?.activeLocalModelId == modelId) {
                updatePrefs { it.copy(activeLocalModelId = null, preferLocalOfflineChat = false) }
            }

            if (entity.isImported) {
                dao.deleteModelById(modelId)
            } else {
                dao.clearDownloadRecord(modelId)
            }
            _hardware.value = readHardwareSnapshot()
        }
    }

    suspend fun importModelFromUri(uri: Uri): Result<LocalModelEntry> = withContext(Dispatchers.IO) {
        try {
            val rawName = resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.litertlm"
            val lower = rawName.lowercase()
            if (!lower.endsWith(".task") && !lower.endsWith(".litertlm") && !lower.endsWith(".tflite")) {
                return@withContext Result.failure(
                    IllegalArgumentException("Please select a phone-runnable .task or .litertlm bundle.")
                )
            }
            val safeName = rawName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val dest = File(modelsDir, safeName)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open selected model file."))

            if (!dest.exists() || dest.length() < 1024L) {
                dest.delete()
                return@withContext Result.failure(IllegalStateException("Selected model file is empty."))
            }

            val id = "imported_${safeName.hashCode().toUInt()}"
            val brand = LobeModelBrand.fromModelId(safeName)
            val sizeBytes = dest.length()
            val shaPrefix = ModelDownloadWorker.computeSha256Prefix(dest)
            val entity = LocalModelEntity(
                id = id,
                displayName = dest.nameWithoutExtension,
                providerBrandName = brand.name,
                family = "Imported",
                quantLabel = dest.extension.uppercase(),
                parameterCount = "Local",
                approxSizeMb = (sizeBytes / (1024L * 1024L)).coerceAtLeast(1L),
                minRamMb = 2048L,
                contextLengthTokens = 2048,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(ModelCapability.OFFLINE_CHAT, ModelCapability.ULTRA_FAST)
                ),
                fileName = safeName,
                downloadUrl = "",
                summary = "Imported on-device LiteRT-LM bundle verified in local storage.",
                localFilePath = dest.absolutePath,
                downloadedBytes = sizeBytes,
                totalBytes = sizeBytes,
                sha256Prefix = shaPrefix,
                downloadedAtEpochMs = System.currentTimeMillis(),
                isImported = true
            )
            dao.upsertModel(entity)
            _hardware.value = readHardwareSnapshot()
            Result.success(mapEntityToEntry(entity, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Actually loads a verified downloaded LiteRT-LM file into device RAM.
     * Fails cleanly if the file is missing or incompatible — never fakes an active model.
     */
    suspend fun loadModel(modelId: String): Result<LoadedLocalModelState> = withContext(Dispatchers.IO) {
        val entity = dao.getModelById(modelId)
            ?: return@withContext Result.failure(IllegalArgumentException("Model not found in local Room registry."))

        val file = entity.localFilePath?.let { File(it) } ?: File(modelsDir, entity.fileName)
        if (!file.exists() || file.length() < 1024L) {
            dao.clearDownloadRecord(modelId)
            return@withContext Result.failure(
                IllegalStateException("Model weights are not on disk yet. Download first via WorkManager.")
            )
        }

        _loadingModelId.value = modelId
        try {
            val requestedBackend = backendChoice.value
            val initResult = liteRtEngine.load(
                modelFile = file,
                displayName = entity.displayName,
                backendChoice = requestedBackend,
                modelId = entity.id
            )
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    initResult.exceptionOrNull()
                        ?: IllegalStateException("Failed to initialize LiteRT-LM runtime.")
                )
            }
            val loadOutcome = initResult.getOrThrow()
            val actualBackend = loadOutcome.actualBackend

            val now = System.currentTimeMillis()
            dao.touchLastUsed(modelId, now)
            updatePrefs {
                it.copy(
                    activeLocalModelId = modelId,
                    preferLocalOfflineChat = true,
                    preferredBackend = actualBackend.name
                )
            }

            val loaded = LoadedLocalModelState(
                modelId = entity.id,
                displayName = entity.displayName,
                brand = LobeModelBrand.entries.firstOrNull { it.name == entity.providerBrandName }
                    ?: LobeModelBrand.fromModelId(entity.fileName),
                family = entity.family,
                backend = actualBackend,
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                sha256Prefix = entity.sha256Prefix,
                capabilities = ModelCapability.parseCsv(entity.capabilitiesCsv),
                loadedAtEpochMs = now
            )
            _loadedModel.value = loaded
            batteryMonitor.updateActiveModelState(loaded.displayName, isInferenceActive = false)
            _hardware.value = readHardwareSnapshot()
            Result.success(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _loadingModelId.value = null
        }
    }

    fun streamLoadedLocalModel(
        prompt: String,
        systemPrompt: String,
        temperature: Float = prefs.value.temperature,
        topP: Float = prefs.value.topP
    ): Flow<ChatStreamEvent> {
        val activeName = _loadedModel.value?.displayName
        return liteRtEngine.streamReply(
            prompt = prompt,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP
        )
            .onStart {
                batteryMonitor.updateActiveModelState(activeName, isInferenceActive = true)
            }
            .onCompletion {
                batteryMonitor.updateActiveModelState(_loadedModel.value?.displayName, isInferenceActive = false)
            }
    }

    fun cancelActiveLocalGeneration() {
        liteRtEngine.cancelActiveGeneration()
    }

    fun runDiagnosticSelfCheck() {
        val hw = readHardwareSnapshot()
        _hardware.value = hw
        liteRtEngine.recordRuntimeSelfCheck(freeRamMb = hw.availableRamMb)
    }

    fun clearDiagnosticLogs() {
        diagnosticLogger.clear()
    }

    fun formatDiagnosticReport(): String = diagnosticLogger.formatFullDiagnosticReport()

    suspend fun runHardwareBenchmark(
        modelId: String,
        preset: BenchmarkPromptPreset = BenchmarkPromptPreset.QUICK_QA
    ): Result<ModelBenchmarkEntity> = withContext(Dispatchers.IO) {
        if (ecoInferenceRestricted.value) {
            return@withContext Result.failure(
                IllegalStateException("Eco-Inference paused benchmarking while battery is below 20%. Plug in or disable Eco-Inference.")
            )
        }
        val currentLoaded = _loadedModel.value
        val activeState = if (currentLoaded != null && currentLoaded.modelId == modelId && liteRtEngine.isReady) {
            currentLoaded
        } else {
            val loadedResult = loadModel(modelId)
            if (loadedResult.isFailure) {
                return@withContext Result.failure(
                    loadedResult.exceptionOrNull()
                        ?: IllegalStateException("Download and load this model before running hardware benchmarks.")
                )
            }
            loadedResult.getOrThrow()
        }

        batteryMonitor.updateActiveModelState(activeState.displayName, isInferenceActive = true)
        var peakTemp = batteryMonitor.refreshNow().temperatureC
        var peakDraw = batteryMonitor.telemetry.value.dischargeMa
        var completedMetrics: InferenceMetrics? = null
        var finalOutput = ""

        try {
            _liveBenchmark.value = LiveBenchmarkProgress(
                modelId = activeState.modelId,
                preset = preset,
                elapsedMs = 0L,
                approxTokens = 0,
                liveTokensPerSec = 0f,
                partialPreview = ""
            )

            liteRtEngine.streamReply(
                prompt = preset.prompt,
                systemPrompt = preset.systemPrompt
            ).collect { event ->
                val sample = batteryMonitor.refreshNow()
                if (sample.temperatureC > peakTemp) peakTemp = sample.temperatureC
                if (sample.dischargeMa > peakDraw) peakDraw = sample.dischargeMa

                when (event) {
                    is ChatStreamEvent.Delta -> {
                        val approxTokens = (event.accumulatedText.length / 4).coerceAtLeast(1)
                        val elapsedSec = (event.elapsedMs.coerceAtLeast(1L)) / 1000f
                        _liveBenchmark.value = LiveBenchmarkProgress(
                            modelId = activeState.modelId,
                            preset = preset,
                            elapsedMs = event.elapsedMs,
                            approxTokens = approxTokens,
                            liveTokensPerSec = approxTokens / elapsedSec,
                            partialPreview = event.accumulatedText.takeLast(220)
                        )
                    }
                    is ChatStreamEvent.Completed -> {
                        completedMetrics = event.metrics
                        finalOutput = event.fullText
                    }
                }
            }

            val metrics = completedMetrics
                ?: return@withContext Result.failure(IllegalStateException("Benchmark did not produce output."))
            val hw = readHardwareSnapshot()
            val generatedTokens = (finalOutput.length / 4).coerceAtLeast(1)
            val entity = ModelBenchmarkEntity(
                modelId = activeState.modelId,
                modelDisplayName = activeState.displayName,
                backendUsed = activeState.backend.label,
                promptProfile = preset.label,
                tokensPerSec = metrics.approxTokensPerSec,
                ttftMs = metrics.ttftMs,
                totalLatencyMs = metrics.latencyMs,
                generatedTokens = generatedTokens,
                deviceModel = hw.deviceModel,
                socLabel = hw.socLabel,
                cpuCores = hw.cpuCores,
                peakTempC = peakTemp,
                peakDrawMa = peakDraw,
                benchmarkedAtEpochMs = System.currentTimeMillis()
            )
            dao.insertBenchmark(entity)
            Result.success(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _liveBenchmark.value = null
            batteryMonitor.updateActiveModelState(_loadedModel.value?.displayName, isInferenceActive = false)
            _hardware.value = readHardwareSnapshot()
        }
    }

    suspend fun runQuickBenchmark(): Result<InferenceMetrics> {
        val loaded = _loadedModel.value
            ?: return Result.failure(IllegalStateException("Load a downloaded on-device model into RAM first to benchmark it."))
        return runHardwareBenchmark(loaded.modelId, BenchmarkPromptPreset.QUICK_QA).map { record ->
            InferenceMetrics(
                latencyMs = record.totalLatencyMs,
                ttftMs = record.ttftMs,
                approxTokensPerSec = record.tokensPerSec,
                modelName = "${record.modelDisplayName} (${record.backendUsed})"
            )
        }
    }

    fun unloadModel() {
        _loadedModel.value = null
        batteryMonitor.updateActiveModelState(null, isInferenceActive = false)
        scope.launch {
            liteRtEngine.unload()
            updatePrefs { it.copy(activeLocalModelId = null, preferLocalOfflineChat = false) }
            _hardware.value = readHardwareSnapshot()
        }
    }

    fun refreshBatteryTelemetry() {
        batteryMonitor.refreshNow()
    }

    fun refreshToolPermissions() {
        _toolPermissions.value = ToolPermissionInspector.inspect(appContext)
    }

    fun shutdown() {
        liteRtEngine.shutdown()
        _loadedModel.value = null
        batteryMonitor.stop()
        clockTools.stopAllTickers()
    }

    suspend fun verifyStorageAndRoom(): StorageVerificationSummary = withContext(Dispatchers.IO) {
        var verified = 0
        var repaired = 0
        val all = dao.getAllModelsOnce()
        for (entity in all) {
            val file = entity.localFilePath?.let { File(it) } ?: File(modelsDir, entity.fileName)
            val actuallyPresent = file.exists() && file.length() > 1024L
            if (actuallyPresent) {
                verified++
                if (entity.localFilePath != file.absolutePath || entity.sha256Prefix.isNullOrBlank()) {
                    dao.markDownloadCompleted(
                        modelId = entity.id,
                        localFilePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        sha256Prefix = entity.sha256Prefix ?: ModelDownloadWorker.computeSha256Prefix(file),
                        downloadedAtEpochMs = entity.downloadedAtEpochMs ?: file.lastModified()
                    )
                }
            } else if (!entity.localFilePath.isNullOrBlank() || entity.downloadedBytes > 0L) {
                repaired++
                if (_loadedModel.value?.modelId == entity.id) {
                    liteRtEngine.unload()
                    _loadedModel.value = null
                }
                dao.clearDownloadRecord(entity.id)
            }
        }
        val cleared = clearPartialCacheInternal()
        _hardware.value = readHardwareSnapshot()
        StorageVerificationSummary(verified, repaired, cleared)
    }

    suspend fun clearPartialCache(): Long = withContext(Dispatchers.IO) {
        val freed = clearPartialCacheInternal()
        _hardware.value = readHardwareSnapshot()
        freed
    }

    /**
     * Deletes all unused local model weight files (preserving any model currently loaded in RAM
     * or actively downloading), removes orphaned files & partial `.part`/`.tmp` cache files,
     * and clears associated Room download & benchmark metadata to reclaim device storage space.
     */
    suspend fun clearUnusedModelCache(): ModelCacheClearSummary = withContext(Dispatchers.IO) {
        val activeLoaded = _loadedModel.value
        val activeLoadedId = activeLoaded?.modelId
        val activeDownloadIds = models.value
            .filter {
                it.status == LocalModelDownloadStatus.DOWNLOADING ||
                    it.status == LocalModelDownloadStatus.QUEUED
            }
            .map { it.id }
            .toSet()

        val protectedIds = buildSet {
            if (activeLoadedId != null) add(activeLoadedId)
            addAll(activeDownloadIds)
        }

        val allEntities = dao.getAllModelsOnce()
        val protectedFilePaths = mutableSetOf<String>()
        var clearedModels = 0
        var reclaimedBytes = 0L

        for (entity in allEntities) {
            val file = entity.localFilePath?.let { File(it) } ?: File(modelsDir, entity.fileName)
            if (entity.id in protectedIds) {
                if (file.exists()) protectedFilePaths.add(file.absolutePath)
                continue
            }

            val hadDiskFile = file.exists()
            val hadRoomMetadata = !entity.localFilePath.isNullOrBlank() ||
                entity.downloadedBytes > 0L ||
                entity.downloadedAtEpochMs != null ||
                !entity.sha256Prefix.isNullOrBlank() ||
                !entity.lastErrorMessage.isNullOrBlank()

            if (hadDiskFile) {
                val len = file.length()
                if (file.delete()) {
                    reclaimedBytes += len
                }
            }
            val partFile = File(modelsDir, "${entity.fileName}.part")
            if (partFile.exists()) {
                val len = partFile.length()
                if (partFile.delete()) reclaimedBytes += len
            }

            if (hadDiskFile || hadRoomMetadata || entity.isImported) {
                if (hadDiskFile || hadRoomMetadata) {
                    clearedModels++
                }
                dao.deleteBenchmarksForModel(entity.id)
                if (entity.isImported) {
                    dao.deleteModelById(entity.id)
                } else {
                    dao.clearDownloadRecord(entity.id)
                }
            }
        }

        // Remove any orphaned files or temp fragments left in filesDir/models/
        var deletedOrphans = 0
        modelsDir.listFiles().orEmpty().forEach { candidate ->
            if (candidate.isFile && candidate.absolutePath !in protectedFilePaths) {
                val isActivePart = activeDownloadIds.any { id ->
                    allEntities.firstOrNull { it.id == id }?.let { "${it.fileName}.part" == candidate.name } == true
                }
                if (!isActivePart) {
                    val len = candidate.length()
                    if (candidate.delete()) {
                        reclaimedBytes += len
                        deletedOrphans++
                    }
                }
            }
        }

        // Also clear temporary LiteRT cache artifacts inside app cacheDir
        appContext.cacheDir.listFiles().orEmpty()
            .filter {
                it.isFile && (
                    it.name.endsWith(".part") ||
                        it.name.endsWith(".tmp") ||
                        it.name.contains("litert", ignoreCase = true) ||
                        it.name.contains("xnnpack", ignoreCase = true)
                    )
            }
            .forEach { cacheFile ->
                val len = cacheFile.length()
                if (cacheFile.delete()) {
                    reclaimedBytes += len
                    deletedOrphans++
                }
            }

        // Reset saved activeLocalModelId if that model was cleared
        val currentPrefs = dao.getPrefsOnce()
        if (currentPrefs != null &&
            !currentPrefs.activeLocalModelId.isNullOrBlank() &&
            currentPrefs.activeLocalModelId !in protectedIds
        ) {
            dao.upsertPrefs(
                currentPrefs.copy(
                    activeLocalModelId = null,
                    preferLocalOfflineChat = false
                )
            )
        }

        _hardware.value = readHardwareSnapshot()
        ModelCacheClearSummary(
            clearedModelCount = clearedModels,
            deletedOrphanFiles = deletedOrphans,
            reclaimedBytes = reclaimedBytes,
            preservedActiveModelName = activeLoaded?.displayName
        )
    }

    fun refreshHardware() {
        _hardware.value = readHardwareSnapshot()
    }

    private fun clearPartialCacheInternal(): Long {
        var freed = 0L
        modelsDir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) }
            .forEach { part ->
                val len = part.length()
                if (part.delete()) freed += len
            }
        return freed
    }

    private suspend fun seedAndReconcileCatalog() {
        dao.insertMissingModels(curatedPhoneEntities)
        val existing = dao.getAllModelsOnce()
        val knownFiles = existing.map { it.fileName }.toSet()

        // Reconcile Room rows with actual files on disk (never fake downloaded state!)
        for (entity in existing) {
            val target = entity.localFilePath?.let { File(it) } ?: File(modelsDir, entity.fileName)
            if (target.exists() && target.length() > 1024L) {
                if (entity.localFilePath != target.absolutePath || entity.downloadedBytes != target.length()) {
                    dao.markDownloadCompleted(
                        modelId = entity.id,
                        localFilePath = target.absolutePath,
                        fileSizeBytes = target.length(),
                        sha256Prefix = entity.sha256Prefix ?: ModelDownloadWorker.computeSha256Prefix(target),
                        downloadedAtEpochMs = entity.downloadedAtEpochMs ?: target.lastModified()
                    )
                }
            } else if (!entity.localFilePath.isNullOrBlank() || entity.downloadedBytes > 0L) {
                dao.clearDownloadRecord(entity.id)
            }
        }

        // Discover any unmanaged LiteRT-LM files present in filesDir/models/
        modelsDir.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                    file.length() > 1024L &&
                    file.name !in knownFiles &&
                    !file.name.endsWith(".part") &&
                    (file.name.endsWith(".task", true) || file.name.endsWith(".litertlm", true))
            }
            .forEach { file ->
                val id = "imported_${file.name.hashCode().toUInt()}"
                val brand = LobeModelBrand.fromModelId(file.name)
                dao.upsertModel(
                    LocalModelEntity(
                        id = id,
                        displayName = file.nameWithoutExtension,
                        providerBrandName = brand.name,
                        family = "Imported",
                        quantLabel = file.extension.uppercase(),
                        parameterCount = "Local",
                        approxSizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(1L),
                        minRamMb = 2048L,
                        contextLengthTokens = 2048,
                        capabilitiesCsv = ModelCapability.toCsv(listOf(ModelCapability.OFFLINE_CHAT)),
                        fileName = file.name,
                        downloadUrl = "",
                        summary = "Local LiteRT-LM model bundle found in app storage.",
                        localFilePath = file.absolutePath,
                        downloadedBytes = file.length(),
                        totalBytes = file.length(),
                        sha256Prefix = ModelDownloadWorker.computeSha256Prefix(file),
                        downloadedAtEpochMs = file.lastModified(),
                        isImported = true
                    )
                )
            }
        _hardware.value = readHardwareSnapshot()
    }

    private fun mapEntityToEntry(entity: LocalModelEntity, workInfo: WorkInfo?): LocalModelEntry {
        val verifiedFile = entity.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 1024L }
        val brand = LobeModelBrand.entries.firstOrNull { it.name == entity.providerBrandName }
            ?: LobeModelBrand.fromModelId(entity.fileName)
        val caps = ModelCapability.parseCsv(entity.capabilitiesCsv)

        val status: LocalModelDownloadStatus
        val downloadedBytes: Long
        val totalBytes: Long
        val speedBps: Long
        val errorMessage: String?

        when {
            workInfo?.state == WorkInfo.State.RUNNING -> {
                status = LocalModelDownloadStatus.DOWNLOADING
                downloadedBytes = workInfo.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                totalBytes = workInfo.progress.getLong(
                    ModelDownloadWorker.KEY_TOTAL_BYTES,
                    entity.approxSizeMb * 1024L * 1024L
                )
                speedBps = workInfo.progress.getLong(ModelDownloadWorker.KEY_SPEED_BPS, 0L)
                errorMessage = null
            }
            workInfo?.state == WorkInfo.State.ENQUEUED || workInfo?.state == WorkInfo.State.BLOCKED -> {
                status = LocalModelDownloadStatus.QUEUED
                downloadedBytes = 0L
                totalBytes = entity.approxSizeMb * 1024L * 1024L
                speedBps = 0L
                errorMessage = null
            }
            verifiedFile != null -> {
                status = LocalModelDownloadStatus.DOWNLOADED
                downloadedBytes = verifiedFile.length()
                totalBytes = verifiedFile.length()
                speedBps = 0L
                errorMessage = null
            }
            !entity.lastErrorMessage.isNullOrBlank() -> {
                status = LocalModelDownloadStatus.ERROR
                downloadedBytes = 0L
                totalBytes = entity.approxSizeMb * 1024L * 1024L
                speedBps = 0L
                errorMessage = entity.lastErrorMessage
            }
            else -> {
                status = LocalModelDownloadStatus.NOT_DOWNLOADED
                downloadedBytes = 0L
                totalBytes = entity.approxSizeMb * 1024L * 1024L
                speedBps = 0L
                errorMessage = null
            }
        }

        return LocalModelEntry(
            id = entity.id,
            displayName = entity.displayName,
            providerBrand = brand,
            family = entity.family,
            runtimeFormat = LocalModelRuntimeFormat.LITERT_LM,
            quantLabel = entity.quantLabel,
            parameterCount = entity.parameterCount,
            approxSizeMb = entity.approxSizeMb,
            minRamMb = entity.minRamMb,
            contextLengthTokens = entity.contextLengthTokens,
            capabilities = caps,
            fileName = entity.fileName,
            downloadUrl = entity.downloadUrl,
            summary = entity.summary,
            localFilePath = verifiedFile?.absolutePath,
            sha256Prefix = entity.sha256Prefix,
            downloadedAtEpochMs = entity.downloadedAtEpochMs,
            lastUsedAtEpochMs = entity.lastUsedAtEpochMs,
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBps,
            errorMessage = errorMessage,
            isImported = entity.isImported,
            updateAvailable = entity.updateAvailable,
            updateStatusNote = entity.updateStatusNote,
            lastUpdateCheckedEpochMs = entity.lastUpdateCheckedEpochMs
        )
    }

    private fun workPriority(state: WorkInfo.State): Int = when (state) {
        WorkInfo.State.RUNNING -> 5
        WorkInfo.State.ENQUEUED -> 4
        WorkInfo.State.BLOCKED -> 3
        WorkInfo.State.FAILED -> 2
        WorkInfo.State.SUCCEEDED -> 1
        WorkInfo.State.CANCELLED -> 0
    }

    private fun resolveDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun readHardwareSnapshot(): DeviceHardwareSnapshot {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val files = modelsDir.listFiles().orEmpty().filter { it.isFile }
        val diskBytes = files.filterNot { it.name.endsWith(".part") }.sumOf { it.length() }
        val tempBytes = files.filter { it.name.endsWith(".part") }.sumOf { it.length() }
        val freeStorageMb = try {
            StatFs(modelsDir.absolutePath).availableBytes / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val deviceModel = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
        val socLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Build.SOC_MODEL.isNullOrBlank()) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE.orEmpty().ifBlank { "Android SoC" }
        }
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        return DeviceHardwareSnapshot(
            totalRamMb = memInfo.totalMem / (1024L * 1024L),
            availableRamMb = memInfo.availMem / (1024L * 1024L),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            modelsDiskUsageBytes = diskBytes,
            tempCacheBytes = tempBytes,
            freeStorageMb = freeStorageMb,
            deviceModel = deviceModel.ifBlank { "Android Device" },
            socLabel = socLabel,
            primaryAbi = primaryAbi
        )
    }

    companion object {
        private const val MODEL_ID_TAG_PREFIX = "model_id:"
        const val ECO_BATTERY_THRESHOLD_PCT = 20

        /**
         * Curated catalog of REAL, ungated, phone-runnable LiteRT-LM models (`.litertlm`)
         * from Hugging Face `litert-community` verified to execute natively on Android via `OnDeviceLiteRtEngine`.
         */
        val curatedPhoneEntities: List<LocalModelEntity> = listOf(
            LocalModelEntity(
                id = "functiongemma-270m-litert",
                displayName = "FunctionGemma 270M Mobile",
                providerBrandName = LobeModelBrand.GEMMA.name,
                family = "Gemma",
                quantLabel = "INT8 · .litertlm",
                parameterCount = "270M",
                approxSizeMb = 285L,
                minRamMb = 2048L,
                contextLengthTokens = 2048,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(
                        ModelCapability.OFFLINE_CHAT,
                        ModelCapability.MOBILE_ACTIONS,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.ULTRA_FAST
                    )
                ),
                fileName = "functiongemma-270m-ft-mobile-actions_q8_ekv2048.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/functiongemma-270m-ft-mobile-actions_q8_ekv2048.litertlm",
                summary = "Ultra-light 270M Gemma fine-tuned specifically for Android mobile actions, tool calls, and instant offline chat."
            ),
            LocalModelEntity(
                id = "qwen3-0.6b-official-litert",
                displayName = "Qwen 3 0.6B Instruct",
                providerBrandName = LobeModelBrand.QWEN.name,
                family = "Qwen",
                quantLabel = "INT8 · .litertlm",
                parameterCount = "0.6B",
                approxSizeMb = 614L,
                minRamMb = 3072L,
                contextLengthTokens = 4096,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(
                        ModelCapability.OFFLINE_CHAT,
                        ModelCapability.REASONING,
                        ModelCapability.MULTILINGUAL,
                        ModelCapability.ULTRA_FAST,
                        ModelCapability.LONG_CONTEXT
                    )
                ),
                fileName = "Qwen3-0.6B.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                summary = "Official Google LiteRT-LM recommended Qwen 3 0.6B bundle — runs smoothly on both phone CPU & GPU offline."
            ),
            LocalModelEntity(
                id = "gemma-3-1b-litert",
                displayName = "Gemma 3 1B Instruct",
                providerBrandName = LobeModelBrand.GEMMA.name,
                family = "Gemma",
                quantLabel = "INT4 · .litertlm",
                parameterCount = "1B",
                approxSizeMb = 554L,
                minRamMb = 3072L,
                contextLengthTokens = 4096,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(
                        ModelCapability.OFFLINE_CHAT,
                        ModelCapability.ULTRA_FAST,
                        ModelCapability.MULTILINGUAL,
                        ModelCapability.LONG_CONTEXT
                    )
                ),
                fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
                summary = "Compact 4-bit Gemma 3 1B model with 4K context window — fast, high-quality everyday offline conversation."
            ),
            LocalModelEntity(
                id = "qwen2.5-1.5b-litertlm",
                displayName = "Qwen 2.5 1.5B Instruct",
                providerBrandName = LobeModelBrand.QWEN.name,
                family = "Qwen",
                quantLabel = "INT8 · .litertlm",
                parameterCount = "1.5B",
                approxSizeMb = 1520L,
                minRamMb = 4096L,
                contextLengthTokens = 4096,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(
                        ModelCapability.OFFLINE_CHAT,
                        ModelCapability.CODING,
                        ModelCapability.MULTILINGUAL,
                        ModelCapability.REASONING
                    )
                ),
                fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                summary = "Strong 1.5B phone model for offline coding, structured tool calls, summaries, and multi-turn Q&A."
            ),
            LocalModelEntity(
                id = "gemma-4-e2b-litert",
                displayName = "Gemma 4 E2B Instruct",
                providerBrandName = LobeModelBrand.GEMMA.name,
                family = "Gemma",
                quantLabel = "INT4 · .litertlm",
                parameterCount = "2B",
                approxSizeMb = 1950L,
                minRamMb = 6144L,
                contextLengthTokens = 4096,
                capabilitiesCsv = ModelCapability.toCsv(
                    listOf(
                        ModelCapability.OFFLINE_CHAT,
                        ModelCapability.REASONING,
                        ModelCapability.CODING,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.LONG_CONTEXT
                    )
                ),
                fileName = "gemma-4-E2B-it.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                summary = "Official Google LiteRT-LM Gemma 4 E2B flagship phone model for deep offline reasoning, coding, and tool calling."
            )
        )
    }
}

package com.mote.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.mote.app.ai.ChatGenUiToolProtocol
import com.mote.app.ai.ChatStreamEvent
import com.mote.app.ai.EngineUsed
import com.mote.app.ai.GenUiToolRegistry
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.InferenceMetrics
import com.mote.app.ai.LiteRtDiagnosticLogEntry
import com.mote.app.ai.LiteRtDiagnosticSummary
import com.mote.app.ai.LlmConfig
import com.mote.app.ai.LocalHardwareBackend
import com.mote.app.ai.RegisteredToolDescriptor
import com.mote.app.ai.StreamParserTelemetry
import com.mote.app.ai.StreamParserTelemetrySnapshot
import com.mote.app.ai.ToolChainExecutionSnapshot
import com.mote.app.ai.ToolExecutionAuditEntry
import com.mote.app.ai.ToolUsageStatsSnapshot
import com.mote.app.control.BatteryTelemetrySnapshot
import com.mote.app.control.CountdownTimerItem
import com.mote.app.control.LanServerUiState
import com.mote.app.control.LocalLanApiServer
import com.mote.app.control.StopwatchUiState
import com.mote.app.control.SystemHealthSnapshot
import com.mote.app.control.ToolPermissionsSnapshot
import com.mote.app.data.BenchmarkPromptPreset
import com.mote.app.data.BotStore
import com.mote.app.data.ChatHistoryStore
import com.mote.app.data.DeviceHardwareSnapshot
import com.mote.app.data.LiveBenchmarkProgress
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelEntry
import com.mote.app.data.LocalModelManager
import com.mote.app.data.db.CalendarEventEntity
import com.mote.app.data.db.ClockAlarmEntity
import com.mote.app.data.db.ModelBenchmarkEntity
import com.mote.app.data.db.ModelStudioPrefsEntity
import com.mote.app.data.db.SavedNoteEntity
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.service.LiveVoiceRelayUiState
import com.mote.app.service.SystemOverlayHudState
import com.mote.app.service.SystemOverlayStateBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface BotsUiState {
    data object Loading : BotsUiState
    data class Success(val bots: List<BotPersona>) : BotsUiState
    data class Error(val message: String) : BotsUiState
}

sealed interface ChatHistoryUiState {
    data object Loading : ChatHistoryUiState
    data class Success(val messages: List<ChatMessage>) : ChatHistoryUiState
    data class Error(val message: String) : ChatHistoryUiState
}

sealed interface AiCardUiState {
    data object Idle : AiCardUiState
    data object Loading : AiCardUiState
    data class Success(val result: HybridGenUiResult) : AiCardUiState
    data class Error(val message: String) : AiCardUiState
}

sealed interface ChatStreamUiState {
    data object Idle : ChatStreamUiState
    data class Streaming(
        val partialText: String,
        val botName: String,
        val elapsedMs: Long,
        val pendingToolHint: String? = null,
        val liveParts: List<InChatUiPart> = emptyList()
    ) : ChatStreamUiState
    data class Error(val message: String) : ChatStreamUiState
}

class MoteViewModel(
    private val repo: BotStore,
    private val chatStore: ChatHistoryStore,
    private val router: HybridAiRouter,
    private val localModelManager: LocalModelManager
) : ViewModel() {

    val botsState: StateFlow<BotsUiState> = repo.observeBots()
        .map<List<BotPersona>, BotsUiState> { BotsUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(BotsUiState.Error(rootError.message ?: "Failed to load bot personas."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = BotsUiState.Loading
        )

    val chatHistoryState: StateFlow<ChatHistoryUiState> = chatStore.observeHistory()
        .map<List<ChatMessage>, ChatHistoryUiState> { ChatHistoryUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(ChatHistoryUiState.Error(rootError.message ?: "Failed to load chat history."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ChatHistoryUiState.Loading
        )

    val localModels: StateFlow<List<LocalModelEntry>> = localModelManager.models
    val loadedLocalModel: StateFlow<LoadedLocalModelState?> = localModelManager.loadedModel
    val loadingLocalModelId: StateFlow<String?> = localModelManager.loadingModelId
    val localBackendChoice: StateFlow<LocalHardwareBackend> = localModelManager.backendChoice
    val deviceHardware: StateFlow<DeviceHardwareSnapshot> = localModelManager.hardware
    val studioPrefs: StateFlow<ModelStudioPrefsEntity> = localModelManager.prefs
    val ecoInferenceRestricted: StateFlow<Boolean> = localModelManager.ecoInferenceRestricted
    val batteryTelemetry: StateFlow<BatteryTelemetrySnapshot> = localModelManager.batteryTelemetry
    val systemHealth: StateFlow<SystemHealthSnapshot> = localModelManager.systemHealth.health
    val activeToolChain: StateFlow<ToolChainExecutionSnapshot?> = localModelManager.toolOrchestrator.activeChain
    val toolChainHistory: StateFlow<List<ToolChainExecutionSnapshot>> = localModelManager.toolOrchestrator.chainHistoryLog
    val toolChainStates: StateFlow<List<com.mote.app.data.db.ToolChainStateEntity>> = localModelManager.toolChainStateManager.recentStates
    val allBenchmarks: StateFlow<List<ModelBenchmarkEntity>> = localModelManager.allBenchmarks
    val liveBenchmark: StateFlow<LiveBenchmarkProgress?> = localModelManager.liveBenchmark

    // Live Clock, Calendar, Notes, Voice Call Relay & Generative UI Tool Registry state
    val clockAlarms: StateFlow<List<ClockAlarmEntity>> = localModelManager.clockTools.alarms
    val clockTimers: StateFlow<List<CountdownTimerItem>> = localModelManager.clockTools.timers
    val clockStopwatch: StateFlow<StopwatchUiState> = localModelManager.clockTools.stopwatch
    val calendarEvents: StateFlow<List<CalendarEventEntity>> = localModelManager.calendarTools.events
    val savedNotes: StateFlow<List<SavedNoteEntity>> = localModelManager.notesTools.notes
    val voiceRelayState: StateFlow<LiveVoiceRelayUiState> = localModelManager.voiceCallBridge.state
    val systemOverlayState: StateFlow<SystemOverlayHudState> = SystemOverlayStateBus.state
    val capturedScreenshots: StateFlow<Map<String, Bitmap>> = localModelManager.deviceTools.capturedScreenshots
    val isCheckingModelUpdates: StateFlow<Boolean> = localModelManager.isCheckingModelUpdates
    val registeredTools: List<RegisteredToolDescriptor> = localModelManager.toolRegistry.registeredDescriptors()
    val toolAuditLog: StateFlow<List<ToolExecutionAuditEntry>> = localModelManager.toolRegistry.auditLog
    val toolUsageStats: StateFlow<ToolUsageStatsSnapshot> = localModelManager.toolRegistry.usageStats
    val toolPermissions: StateFlow<ToolPermissionsSnapshot> = localModelManager.toolPermissions
    val liteRtDiagnosticLogs: StateFlow<List<LiteRtDiagnosticLogEntry>> = localModelManager.diagnosticLogs
    val liteRtDiagnosticSummary: StateFlow<LiteRtDiagnosticSummary> = localModelManager.diagnosticSummary
    val streamParserTelemetry: StateFlow<StreamParserTelemetrySnapshot> = StreamParserTelemetry.snapshot

    // Inline Generative UI & Clock widgets attached to assistant chat messages by normalized text key
    private val _inChatUiPartsByText = MutableStateFlow<Map<String, List<InChatUiPart>>>(emptyMap())
    val inChatUiPartsByText: StateFlow<Map<String, List<InChatUiPart>>> = _inChatUiPartsByText.asStateFlow()

    val llmConfig: StateFlow<LlmConfig> = studioPrefs
        .map { p ->
            LlmConfig(
                modelName = p.selectedCloudModelId,
                temperature = p.temperature,
                topP = p.topP,
                maxOutputTokens = p.maxOutputTokens,
                contextWindowTurns = p.contextWindowTurns
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LlmConfig()
        )

    // True ONLY when the user enabled offline chat AND an actual LiteRT-LM model is active in RAM
    val preferLoadedLocalChat: StateFlow<Boolean> = combine(
        studioPrefs,
        loadedLocalModel
    ) { p, loaded ->
        p.preferLocalOfflineChat && loaded != null && loaded.supportsOnDeviceInference
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private val _aiState = MutableStateFlow<AiCardUiState>(AiCardUiState.Idle)
    val aiState: StateFlow<AiCardUiState> = _aiState.asStateFlow()

    private val _chatStreamState = MutableStateFlow<ChatStreamUiState>(ChatStreamUiState.Idle)
    val chatStreamState: StateFlow<ChatStreamUiState> = _chatStreamState.asStateFlow()

    private val _lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
    val lastMetrics: StateFlow<InferenceMetrics?> = _lastMetrics.asStateFlow()

    private val _utilityStatus = MutableStateFlow<String?>(null)
    val utilityStatus: StateFlow<String?> = _utilityStatus.asStateFlow()

    private val _lastAdaptiveRouteNote = MutableStateFlow<String?>(null)
    val lastAdaptiveRouteNote: StateFlow<String?> = _lastAdaptiveRouteNote.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    private val _selectedBotId = MutableStateFlow("focus")
    val selectedBotId: StateFlow<String> = _selectedBotId.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val lanServer = LocalLanApiServer {
        val list = mutableListOf(llmConfig.value.modelName)
        localModelManager.loadedModel.value?.let { list.add("local/${it.modelId}") }
        localModelManager.models.value
            .filter { it.status == LocalModelDownloadStatus.DOWNLOADED }
            .forEach { entry ->
                val tag = "local/${entry.id}"
                if (tag !in list) list.add(tag)
            }
        list
    }
    val lanServerState: StateFlow<LanServerUiState> = lanServer.state

    private var activeStreamJob: Job? = null
    private var lastChatPrompt: String? = null
    private var lastChatBot: BotPersona? = null

    init {
        viewModelScope.launch {
            repo.seedDefaultBotsIfEmpty().onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to initialize starter bots."
            }
        }
    }

    fun selectBot(botId: String) {
        _selectedBotId.value = botId
    }

    fun updateModel(modelName: String) {
        viewModelScope.launch {
            localModelManager.updatePrefs {
                it.copy(
                    selectedCloudModelId = modelName,
                    preferLocalOfflineChat = false
                )
            }
            val label = HybridAiRouter.supportedModels.firstOrNull { it.id == modelName }?.displayName ?: modelName
            _utilityStatus.value = "Switched real-time chat to Cloud $label"
        }
    }

    fun selectDownloadedModelForChat(modelId: String, onReady: () -> Unit = {}) {
        viewModelScope.launch {
            val currentLoaded = localModelManager.loadedModel.value
            if (currentLoaded != null && currentLoaded.modelId == modelId) {
                localModelManager.updatePrefs {
                    it.copy(activeLocalModelId = modelId, preferLocalOfflineChat = true)
                }
                _utilityStatus.value = "Switched real-time chat to On-Device ${currentLoaded.displayName}"
                onReady()
            } else {
                localModelManager.loadModel(modelId)
                    .onSuccess { loaded ->
                        _utilityStatus.value = "Loaded ${loaded.displayName} (${loaded.backend.label}) for offline chat"
                        onReady()
                    }
                    .onFailure { err ->
                        _actionError.value = err.localizedMessage ?: "Failed to load local model into RAM."
                    }
            }
        }
    }

    fun updateTemperature(temperature: Float) {
        val clamped = temperature.coerceIn(0f, 1.5f)
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(temperature = clamped) }
        }
    }

    fun updateTopP(topP: Float) {
        val clamped = topP.coerceIn(0.1f, 1.0f)
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(topP = clamped) }
        }
    }

    fun updateMaxOutputTokens(tokens: Int) {
        val clamped = tokens.coerceIn(128, 4096)
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(maxOutputTokens = clamped) }
        }
    }

    fun updateContextWindowTurns(turns: Int) {
        val clamped = turns.coerceIn(2, 20)
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(contextWindowTurns = clamped) }
        }
    }

    fun updateWifiOnlyDownloads(wifiOnly: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(wifiOnlyDownloads = wifiOnly) }
        }
    }

    fun updateRequireBatteryNotLow(batterySafe: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(requireBatteryNotLow = batterySafe) }
        }
    }

    fun updateAutoLoadLastModel(autoLoad: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(autoLoadLastModel = autoLoad) }
        }
    }

    fun toggleBot(bot: BotPersona) {
        viewModelScope.launch {
            repo.toggleBot(bot.id, bot.active).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to update bot status."
            }
        }
    }

    fun saveBotPersona(bot: BotPersona, isExisting: Boolean) {
        viewModelScope.launch {
            repo.upsertBot(bot, isExisting).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save bot persona."
            }
        }
    }

    fun selectLocalBackend(backend: LocalHardwareBackend) = localModelManager.selectBackend(backend)

    fun setPreferLoadedLocalChat(prefer: Boolean) {
        val canUseLocal = prefer && (localModelManager.loadedModel.value?.supportsOnDeviceInference == true)
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(preferLocalOfflineChat = canUseLocal) }
        }
    }

    fun addCustomModelUrl(displayName: String, url: String) = localModelManager.addCustomUrlAndDownload(displayName, url)

    fun startModelDownload(modelId: String) = localModelManager.startDownload(modelId)

    fun cancelModelDownload(modelId: String) = localModelManager.cancelDownload(modelId)

    fun deleteLocalModel(modelId: String) = localModelManager.deleteModel(modelId)

    fun loadLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelManager.loadModel(modelId)
                .onSuccess { loaded ->
                    _utilityStatus.value = "Loaded ${loaded.displayName} into RAM (${loaded.backend.label})"
                }
                .onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Failed to load local model."
                }
        }
    }

    fun unloadLocalModel() {
        localModelManager.unloadModel()
        _utilityStatus.value = "Unloaded local model from RAM"
    }

    fun verifyLocalModelStorage() {
        viewModelScope.launch {
            val summary = localModelManager.verifyStorageAndRoom()
            val clearedMb = summary.clearedTempBytes / (1024.0 * 1024.0)
            _utilityStatus.value = String.format(
                Locale.US,
                "Verified %d model(s) on disk · %d stale fixed · %.1f MB temp cleared",
                summary.verifiedCount,
                summary.repairedStaleRecords,
                clearedMb
            )
        }
    }

    fun clearDownloadTempCache() {
        viewModelScope.launch {
            val freed = localModelManager.clearPartialCache()
            val freedMb = freed / (1024.0 * 1024.0)
            _utilityStatus.value = String.format(Locale.US, "Cleared %.1f MB of partial download cache", freedMb)
        }
    }

    fun clearUnusedModelCache() {
        viewModelScope.launch {
            val summary = localModelManager.clearUnusedModelCache()
            val reclaimedMb = summary.reclaimedBytes / (1024.0 * 1024.0)
            _utilityStatus.value = buildString {
                if (summary.clearedModelCount == 0 && summary.deletedOrphanFiles == 0 && summary.reclaimedBytes == 0L) {
                    append("Model cache is already clean (0 unused local models found)")
                } else {
                    append(
                        String.format(
                            Locale.US,
                            "Reclaimed %.1f MB · Cleared %d unused model(s)",
                            reclaimedMb,
                            summary.clearedModelCount
                        )
                    )
                    if (summary.deletedOrphanFiles > 0) {
                        append(" & ${summary.deletedOrphanFiles} temp/orphan file(s)")
                    }
                    append(" + Room metadata")
                }
                if (!summary.preservedActiveModelName.isNullOrBlank()) {
                    append(" (kept active: ${summary.preservedActiveModelName})")
                }
            }
        }
    }

    fun updateDarkThemeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(darkThemeEnabled = enabled) }
        }
    }

    fun updateAutoCheckModelUpdates(enabled: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(autoCheckModelUpdates = enabled) }
            _utilityStatus.value = if (enabled) {
                "Background model update checks enabled (every 12h via WorkManager)"
            } else {
                "Background model update checks paused"
            }
        }
    }

    fun checkForModelUpdatesNow(autoDownload: Boolean = true) {
        localModelManager.checkForModelUpdatesNow(autoDownload = autoDownload)
        _utilityStatus.value = "Checking downloaded local models for updates in background..."
    }

    fun exportSettingsBackup(uri: Uri) {
        viewModelScope.launch {
            val currentBots = (botsState.value as? BotsUiState.Success)?.bots.orEmpty()
            val result = localModelManager.exportSettingsBackup(uri, currentBots)
            if (result.success) {
                _utilityStatus.value = result.message
            } else {
                _actionError.value = result.message
            }
        }
    }

    fun importSettingsBackup(uri: Uri) {
        viewModelScope.launch {
            localModelManager.importSettingsBackup(uri)
                .onSuccess { (result, restoredBots) ->
                    val existingIds = (botsState.value as? BotsUiState.Success)?.bots?.map { it.id }?.toSet().orEmpty()
                    restoredBots.forEach { bot ->
                        repo.upsertBot(bot, isExisting = bot.id in existingIds)
                    }
                    _utilityStatus.value = result.message
                }
                .onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Failed to import JSON settings backup."
                }
        }
    }

    fun benchmarkLoadedLocalModel() {
        val activeId = localModelManager.loadedModel.value?.modelId
        if (activeId == null) {
            _actionError.value = "Load a downloaded phone model into RAM first to run a benchmark."
            return
        }
        runModelBenchmark(activeId, BenchmarkPromptPreset.QUICK_QA)
    }

    fun runModelBenchmark(modelId: String, preset: BenchmarkPromptPreset) {
        if (_isBenchmarking.value) return
        viewModelScope.launch {
            _isBenchmarking.value = true
            localModelManager.runHardwareBenchmark(modelId, preset)
                .onSuccess { record ->
                    _lastMetrics.value = InferenceMetrics(
                        latencyMs = record.totalLatencyMs,
                        ttftMs = record.ttftMs,
                        approxTokensPerSec = record.tokensPerSec,
                        modelName = "${record.modelDisplayName} (${record.backendUsed})"
                    )
                    _utilityStatus.value = String.format(
                        Locale.US,
                        "Benchmark (%s · %s): %.1f tok/s · TTFT %dms · %.1f°C",
                        record.promptProfile,
                        record.backendUsed,
                        record.tokensPerSec,
                        record.ttftMs,
                        record.peakTempC
                    )
                }
                .onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Hardware benchmark failed."
                }
            _isBenchmarking.value = false
        }
    }

    fun updateEcoInferenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { it.copy(ecoInferenceEnabled = enabled) }
        }
    }

    fun setAdaptiveThermalBatteryRouting(enabled: Boolean) {
        localModelManager.setAdaptiveThermalBatteryRouting(enabled)
        _utilityStatus.value = if (enabled) {
            "Battery & Thermal Adaptive Inference enabled (auto-switches Local ↔ Cloud)"
        } else {
            "Adaptive Inference paused — using manual model selection"
        }
    }

    fun refreshSystemHealth() {
        localModelManager.systemHealth.refreshNow()
        localModelManager.refreshBatteryTelemetry()
    }

    // Direct Clock Tool UI Controls
    fun toggleClockAlarm(alarmId: String, enabled: Boolean) =
        localModelManager.clockTools.toggleAlarmById(alarmId, enabled)

    fun shiftClockAlarmMinutes(alarmId: String, deltaMinutes: Int) =
        localModelManager.clockTools.shiftAlarmMinutes(alarmId, deltaMinutes)

    fun deleteClockAlarm(alarmId: String) =
        localModelManager.clockTools.deleteAlarmById(alarmId)

    fun syncClockAlarmToAndroid(alarm: ClockAlarmEntity): Boolean =
        localModelManager.clockTools.syncAlarmToAndroidClock(alarm)

    fun controlClockTimer(action: String, timerId: String) {
        localModelManager.clockTools.controlTimer(action, timerId)
    }

    fun addSecondsToClockTimer(timerId: String, extraSeconds: Int) {
        localModelManager.clockTools.addSecondsToTimer(timerId, extraSeconds)
    }

    fun controlClockStopwatch(action: String) {
        localModelManager.clockTools.controlStopwatch(action)
    }

    fun deleteCalendarEvent(eventId: String) =
        localModelManager.calendarTools.deleteEventById(eventId)

    fun syncCalendarEventToAndroid(event: CalendarEventEntity): Boolean =
        localModelManager.calendarTools.syncEventToAndroidCalendar(event)

    fun syncDeviceCalendarNow() {
        viewModelScope.launch {
            val count = localModelManager.calendarTools.syncFromAndroidCalendarProvider()
            localModelManager.refreshToolPermissions()
            if (count > 0) {
                _utilityStatus.value = "Synced $count event(s) from Android Calendar Provider"
            }
        }
    }

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            localModelManager.updatePrefs { current ->
                when (toolId) {
                    GenUiToolRegistry.TOOL_ALARM_MANAGER -> current.copy(toolAlarmEnabled = enabled)
                    GenUiToolRegistry.TOOL_COUNTDOWN_TIMER -> current.copy(toolTimerEnabled = enabled)
                    GenUiToolRegistry.TOOL_STOPWATCH -> current.copy(toolStopwatchEnabled = enabled)
                    GenUiToolRegistry.TOOL_CALENDAR -> current.copy(toolCalendarEnabled = enabled)
                    GenUiToolRegistry.TOOL_CAMERA -> current.copy(toolCameraEnabled = enabled)
                    GenUiToolRegistry.TOOL_SCREEN_CAPTURE -> current.copy(toolScreenCaptureEnabled = enabled)
                    GenUiToolRegistry.TOOL_APP_LAUNCHER -> current.copy(toolAppLauncherEnabled = enabled)
                    GenUiToolRegistry.TOOL_NAVIGATION -> current.copy(toolNavigationEnabled = enabled)
                    GenUiToolRegistry.TOOL_GEN_UI_WIDGETS -> current.copy(toolGenUiEnabled = enabled)
                    else -> current
                }
            }
        }
    }

    fun refreshToolPermissions() = localModelManager.refreshToolPermissions()

    fun clearToolAuditLog() = localModelManager.toolRegistry.clearAuditLog()

    fun runLiteRtSelfCheck() = localModelManager.runDiagnosticSelfCheck()

    fun clearLiteRtDiagnosticLogs() {
        localModelManager.clearDiagnosticLogs()
        StreamParserTelemetry.reset()
    }

    fun formatLiteRtDiagnosticReport(): String = localModelManager.formatDiagnosticReport()

    fun refreshBatteryTelemetry() = localModelManager.refreshBatteryTelemetry()

    fun clearUtilityStatus() {
        _utilityStatus.value = null
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            localModelManager.importModelFromUri(uri)
                .onSuccess { imported ->
                    _utilityStatus.value = "Imported ${imported.displayName} into Room registry"
                }
                .onFailure { err ->
                    _actionError.value = err.localizedMessage ?: "Failed to import model file."
                }
        }
    }

    fun refreshHardware() = localModelManager.refreshHardware()

    fun toggleLanServer() = lanServer.toggleServer()

    fun updateLanPort(port: Int) = lanServer.updatePort(port)

    fun toggleLanBearerToken(requireToken: Boolean) = lanServer.toggleRequireToken(requireToken)

    fun stopStreaming() {
        localModelManager.cancelActiveLocalGeneration()
        activeStreamJob?.cancel()
        activeStreamJob = null
        _chatStreamState.value = ChatStreamUiState.Idle
    }

    fun sendStreamingChatMessage(
        prompt: String,
        bot: BotPersona?,
        attachedBitmap: Bitmap? = null
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() && attachedBitmap == null) return
        val effectivePrompt = cleanPrompt.ifEmpty { "Describe and analyze this image." }
        val botId = bot?.id?.takeIf { it.isNotBlank() } ?: "focus"
        val botName = bot?.name?.takeIf { it.isNotBlank() } ?: "Mote"
        val systemPrompt = bot?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: "You are Mote, a crisp, helpful Android AI assistant."
        val priorHistory = (chatHistoryState.value as? ChatHistoryUiState.Success)?.messages.orEmpty()
        val displayPrompt = if (attachedBitmap != null) "[Image attached] $effectivePrompt" else effectivePrompt

        activeStreamJob?.cancel()
        lastChatPrompt = effectivePrompt
        lastChatBot = bot
        activeStreamJob = viewModelScope.launch {
            _chatStreamState.value = ChatStreamUiState.Streaming(
                partialText = "",
                botName = botName,
                elapsedMs = 0L
            )
            chatStore.appendMessage(
                role = ChatRole.USER,
                botId = botId,
                botName = botName,
                text = displayPrompt,
                engineUsed = "NONE"
            ).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save prompt to chat history."
            }

            try {
                val clockContext = buildString {
                    appendLine(localModelManager.clockTools.buildClockContextForAi())
                    appendLine()
                    appendLine(localModelManager.calendarTools.buildCalendarContextForAi())
                    appendLine()
                    appendLine(localModelManager.notesTools.buildNotesContextForAi())
                    appendLine()
                    appendLine(localModelManager.clipboardTools.buildClipboardContextForAi())
                    appendLine()
                    appendLine(localModelManager.deviceTools.buildDeviceContextForAi())
                }.trim()
                val enabledToolIds = localModelManager.toolRegistry.enabledToolIds(studioPrefs.value)

                // Step 1: Battery & Thermal Adaptive Inference Routing via SystemHealthManager
                val currentPrefs = studioPrefs.value
                var loadedLocal = localModelManager.loadedModel.value
                val savedLocalId = currentPrefs.activeLocalModelId
                val hasDownloadedSavedLocal = !savedLocalId.isNullOrBlank() &&
                    localModelManager.models.value.any {
                        it.id == savedLocalId && it.status == LocalModelDownloadStatus.DOWNLOADED
                    }

                val preRoute = localModelManager.systemHealth.resolveInferenceRoute(
                    adaptiveEnabled = currentPrefs.adaptiveThermalBatteryRouting,
                    userPrefersLocal = preferLoadedLocalChat.value || currentPrefs.preferLocalOfflineChat,
                    hasLocalModelReady = (loadedLocal?.supportsOnDeviceInference == true) || hasDownloadedSavedLocal,
                    hasAttachedImage = attachedBitmap != null
                )

                var useLocalLiteRt = preRoute.useLocalEngine
                if (useLocalLiteRt && loadedLocal == null && !savedLocalId.isNullOrBlank()) {
                    _chatStreamState.value = ChatStreamUiState.Streaming(
                        partialText = "",
                        botName = botName,
                        elapsedMs = 0L,
                        pendingToolHint = "Adaptive Health: Loading local LiteRT-LM model into RAM..."
                    )
                    val loadedResult = localModelManager.loadModel(savedLocalId)
                    loadedLocal = loadedResult.getOrNull()
                    useLocalLiteRt = loadedLocal?.supportsOnDeviceInference == true
                }
                _lastAdaptiveRouteNote.value = preRoute.engineReason

                val streamFlow = if (useLocalLiteRt) {
                    val localSystemInstruction = buildString {
                        appendLine(systemPrompt)
                        appendLine("Be concise, direct, and helpful.")
                        appendLine()
                        appendLine(ChatGenUiToolProtocol.buildToolSystemInstructions(clockContext, enabledToolIds))
                    }
                    val recentContextPrompt = buildString {
                        val recent = priorHistory.takeLast(4)
                        if (recent.isNotEmpty()) {
                            appendLine("Recent conversation:")
                            recent.forEach { msg ->
                                val speaker = if (msg.chatRole() == ChatRole.USER) "User" else "Assistant"
                                appendLine("$speaker: ${msg.text.take(220)}")
                            }
                            appendLine()
                        }
                        append("User: $effectivePrompt")
                    }
                    localModelManager.streamLoadedLocalModel(
                        prompt = recentContextPrompt,
                        systemPrompt = localSystemInstruction,
                        temperature = llmConfig.value.temperature,
                        topP = llmConfig.value.topP
                    )
                } else {
                    router.streamChatMessage(
                        userPrompt = effectivePrompt,
                        botSystemPrompt = systemPrompt,
                        history = priorHistory,
                        config = llmConfig.value,
                        attachedBitmap = attachedBitmap,
                        clockContext = clockContext,
                        enabledToolIds = enabledToolIds
                    )
                }

                var cleanFinalAnswer = ""
                var latestElapsedMs = 0L
                val finalToolBlocks = mutableListOf<String>()

                streamFlow.collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            latestElapsedMs = event.elapsedMs
                            _chatStreamState.value = ChatStreamUiState.Streaming(
                                partialText = event.accumulatedText,
                                botName = botName,
                                elapsedMs = event.elapsedMs,
                                pendingToolHint = event.pendingToolHint ?: if (preRoute.switchedAutomatically) preRoute.engineReason else null,
                                liveParts = emptyList()
                            )
                        }
                        is ChatStreamEvent.Completed -> {
                            cleanFinalAnswer = event.fullText
                            finalToolBlocks.clear()
                            finalToolBlocks.addAll(event.completedToolJsonBlocks)
                            _lastMetrics.value = event.metrics
                        }
                    }
                }

                // Step 2: Sequential Tool Chain Execution + Proof-of-Execution Verification
                val decodedInvocations = finalToolBlocks.mapNotNull { rawJson ->
                    ChatGenUiToolProtocol.decodeToolInvocation(rawJson)
                }

                // If the user explicitly asked for a device action / multi-step chain (e.g. follow-up alarm
                // or "take a screenshot and share it on WhatsApp") and the LLM emitted zero tool calls,
                // deterministically plan & execute the chain so the assistant never hallucinates "Done!".
                val plannedInvocations = if (decodedInvocations.isEmpty() && attachedBitmap == null) {
                    localModelManager.toolOrchestrator.planFallbackChainIfUnverified(
                        userPrompt = effectivePrompt,
                        enabledToolIds = enabledToolIds
                    )
                } else {
                    decodedInvocations
                }

                val liveUiParts = mutableListOf<InChatUiPart>()
                var chainSnapshot: ToolChainExecutionSnapshot? = null

                if (plannedInvocations.isNotEmpty()) {
                    val chainTitle = if (plannedInvocations.size > 1) {
                        "Sequential Tool Chain"
                    } else {
                        "Device Action Verification"
                    }
                    val runResult = localModelManager.toolOrchestrator.executeChain(
                        rawInvocations = plannedInvocations,
                        chainTitle = chainTitle,
                        promptSummary = effectivePrompt,
                        onProgress = { progressSnap ->
                            val activeStep = progressSnap.steps.firstOrNull {
                                it.status == com.mote.app.ai.ToolStepStatus.RUNNING
                            }
                            val progressHint = if (activeStep != null) {
                                "Step ${activeStep.stepIndex}/${progressSnap.steps.size}: ${activeStep.operationLabel} (${activeStep.engineLabel})..."
                            } else if (progressSnap.allVerified) {
                                "Execution Verified (${progressSnap.verifiedCount}/${progressSnap.steps.size})"
                            } else {
                                "Verifying tool execution..."
                            }
                            _chatStreamState.value = ChatStreamUiState.Streaming(
                                partialText = cleanFinalAnswer,
                                botName = botName,
                                elapsedMs = latestElapsedMs,
                                pendingToolHint = progressHint,
                                liveParts = listOf(
                                    InChatUiPart.ToolChainStatusPanel(
                                        partId = "live_chain_${progressSnap.chainId}",
                                        snapshot = progressSnap
                                    )
                                )
                            )
                        }
                    )
                    chainSnapshot = runResult.snapshot
                    liveUiParts.addAll(runResult.uiParts)
                }

                val baseAnswer = cleanFinalAnswer.ifBlank {
                    when (val firstPart = liveUiParts.firstOrNull()) {
                        is InChatUiPart.ToolChainStatusPanel -> {
                            val snap = firstPart.snapshot
                            if (snap.allVerified) {
                                "Execution Verified (${snap.verifiedCount}/${snap.steps.size})."
                            } else {
                                snap.steps.firstOrNull()?.summary ?: "Tool chain finished."
                            }
                        }
                        is InChatUiPart.ClockAlarmsPanel -> firstPart.actionSummary
                        is InChatUiPart.ClockTimerPanel -> firstPart.actionSummary
                        is InChatUiPart.ClockStopwatchPanel -> firstPart.actionSummary
                        is InChatUiPart.CalendarEventsPanel -> firstPart.actionSummary
                        is InChatUiPart.DeviceActionPanel -> firstPart.summary
                        is InChatUiPart.ScreenCapturePanel -> firstPart.summary
                        is InChatUiPart.OcrResultPanel -> firstPart.title
                        is InChatUiPart.NotesPanel -> firstPart.actionSummary
                        is InChatUiPart.VoiceCallRelayPanel -> firstPart.summary
                        is InChatUiPart.ClipboardPanel -> firstPart.summary
                        is InChatUiPart.GenUiCardPart -> firstPart.spec.title
                        is InChatUiPart.ToolStatusBanner -> firstPart.message
                        null -> ""
                    }
                }

                val cleanAnswer = localModelManager.toolOrchestrator.verifyAssistantClaim(
                    assistantText = baseAnswer,
                    snapshot = chainSnapshot
                )

                if (cleanAnswer.isNotBlank()) {
                    if (liveUiParts.isNotEmpty()) {
                        _inChatUiPartsByText.value = _inChatUiPartsByText.value + (cleanAnswer to liveUiParts.toList())
                    }
                    chatStore.appendMessage(
                        role = ChatRole.ASSISTANT,
                        botId = botId,
                        botName = botName,
                        text = cleanAnswer,
                        engineUsed = if (useLocalLiteRt) EngineUsed.LOCAL_NANO.name else EngineUsed.CLOUD_FLASH.name
                    ).onFailure { err ->
                        _actionError.value = err.localizedMessage ?: "Failed to save assistant reply."
                    }
                }
                _chatStreamState.value = ChatStreamUiState.Idle
                refreshToolPermissions()

                // If the AI invoked captureAndAnalyzeScreen (or takeScreenshot with analyze=true),
                // automatically stream multimodal visual analysis on the captured MediaProjection Bitmap!
                val pendingVisualCapture = liveUiParts
                    .filterIsInstance<InChatUiPart.ScreenCapturePanel>()
                    .firstOrNull { it.success && it.captureId != null && !it.autoAnalyzePrompt.isNullOrBlank() }
                if (pendingVisualCapture != null) {
                    val capId = pendingVisualCapture.captureId!!
                    val analysisPrompt = pendingVisualCapture.autoAnalyzePrompt!!
                    analyzeCapturedScreenshot(captureId = capId, customPrompt = analysisPrompt, bot = bot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chatStreamState.value = ChatStreamUiState.Error(
                    e.localizedMessage ?: "Failed to generate response."
                )
            }
        }
    }

    /**
     * Feeds a captured MediaProjection screenshot Bitmap directly into multimodal vision chat
     * so the AI visually inspects the screen and streams analysis inline.
     */
    fun analyzeCapturedScreenshot(
        captureId: String,
        customPrompt: String? = null,
        bot: BotPersona? = null
    ) {
        val bmp = localModelManager.deviceTools.capturedScreenshots.value[captureId]
        if (bmp == null || bmp.isRecycled) {
            _actionError.value = "Captured screenshot is no longer in memory. Capture a fresh screenshot to analyze."
            return
        }
        val activeBots = (botsState.value as? BotsUiState.Success)?.bots.orEmpty()
        val chosenBot = bot
            ?: activeBots.firstOrNull { it.id == _selectedBotId.value }
            ?: activeBots.firstOrNull { it.active }
            ?: activeBots.firstOrNull()
        val prompt = customPrompt?.trim()?.takeIf { it.isNotBlank() }
            ?: "Visually analyze this captured screenshot: describe what is shown, read key text, and suggest useful next actions."
        sendStreamingChatMessage(
            prompt = prompt,
            bot = chosenBot,
            attachedBitmap = bmp
        )
    }

    fun retryLastChatMessage(switchToLiteModel: Boolean = false) {
        val prompt = lastChatPrompt ?: return
        val bot = lastChatBot
        _chatStreamState.value = ChatStreamUiState.Idle
        viewModelScope.launch {
            if (switchToLiteModel) {
                localModelManager.updatePrefs {
                    it.copy(
                        selectedCloudModelId = HybridAiRouter.LITE_MODEL_NAME,
                        preferLocalOfflineChat = false
                    )
                }
            }
            sendStreamingChatMessage(prompt = prompt, bot = bot)
        }
    }

    private suspend fun executeChatToolJson(rawJson: String): InChatUiPart? {
        val invocation = ChatGenUiToolProtocol.decodeToolInvocation(rawJson) ?: return null
        val result = localModelManager.toolRegistry.dispatch(invocation) ?: return null
        return result.uiPart
    }

    fun generateFocusControlCard(
        userPrompt: String = "Focus mode: dim screen to 30%, enable DND, start 25 minute timer",
        botSystemPrompt: String = "Return compact Android Generative UI controls.",
        redactedScreenContext: String? = null,
        botId: String = "focus",
        botName: String = "Focus"
    ) {
        viewModelScope.launch {
            _aiState.value = AiCardUiState.Loading
            chatStore.appendMessage(
                role = ChatRole.USER,
                botId = botId,
                botName = botName,
                text = userPrompt,
                engineUsed = "NONE"
            ).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save prompt to chat history."
            }

            router.generateControlCard(
                userPrompt = userPrompt,
                botSystemPrompt = botSystemPrompt,
                redactedScreenContext = redactedScreenContext,
                isForeground = true,
                modelName = llmConfig.value.modelName
            ).onSuccess { result ->
                _aiState.value = AiCardUiState.Success(result)
                val widgetNames = result.spec.widgets.joinToString(", ") { it.label }
                val summary = if (widgetNames.isNotBlank()) {
                    "${result.spec.title} — $widgetNames"
                } else {
                    result.spec.title
                }
                chatStore.appendMessage(
                    role = ChatRole.ASSISTANT,
                    botId = botId,
                    botName = botName,
                    text = summary,
                    engineUsed = result.engineUsed.name
                )
            }.onFailure { err ->
                _aiState.value = AiCardUiState.Error(
                    err.localizedMessage ?: "Failed to generate interactive UI."
                )
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            chatStore.clearHistory().onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to clear chat history."
            }
            _aiState.value = AiCardUiState.Idle
            _chatStreamState.value = ChatStreamUiState.Idle
            _lastMetrics.value = null
        }
    }

    fun clearToolChainHistory() {
        localModelManager.toolOrchestrator.clearChainHistory()
    }

    fun deleteSavedNote(noteId: String) {
        viewModelScope.launch {
            localModelManager.notesTools.deleteNoteById(noteId)
        }
    }

    fun saveOcrTextAsNote(title: String, content: String) {
        viewModelScope.launch {
            val res = localModelManager.notesTools.saveNote(
                title = title,
                content = content,
                sourceTag = "OCR Quick Save"
            )
            _utilityStatus.value = res.summary
        }
    }

    fun writeToSystemClipboard(text: String) {
        viewModelScope.launch {
            val res = localModelManager.clipboardTools.writeClipboard(text)
            _utilityStatus.value = res.summary
        }
    }

    fun pasteClipboardToFocusedField(text: String) {
        viewModelScope.launch {
            val res = localModelManager.clipboardTools.pasteToFocusedField(text)
            _utilityStatus.value = res.summary
        }
    }

    fun startVoiceCallRelay(targetLabel: String = "Live Voice Relay") {
        localModelManager.voiceCallBridge.startRelay(targetLabel = targetLabel)
    }

    fun toggleVoiceRelayPause() {
        localModelManager.voiceCallBridge.togglePause()
    }

    fun stopVoiceRelay() {
        localModelManager.voiceCallBridge.stopRelay()
    }

    fun submitVoiceRelayUtterance(heardText: String) {
        localModelManager.voiceCallBridge.submitHeardUtterance(heardText)
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun clearChatStreamError() {
        if (_chatStreamState.value is ChatStreamUiState.Error) {
            _chatStreamState.value = ChatStreamUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        localModelManager.shutdown()
        lanServer.stop()
    }

    class Factory(
        private val repo: BotStore,
        private val chatStore: ChatHistoryStore,
        private val router: HybridAiRouter,
        private val localModelManager: LocalModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MoteViewModel(repo, chatStore, router, localModelManager) as T
        }
    }
}

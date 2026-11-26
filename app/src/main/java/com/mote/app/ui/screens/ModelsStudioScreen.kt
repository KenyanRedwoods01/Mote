package com.mote.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.LocalHardwareBackend
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.db.ModelCapability
import com.mote.app.model.BotPersona
import com.mote.app.model.MoteInferenceMode
import com.mote.app.ui.BotsUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import java.util.Locale

private val personaAccentPalette = listOf(
    0xFF059669L,
    0xFF1D4ED8L,
    0xFF0B0D1DL,
    0xFFD97706L,
    0xFF7C3AEDL
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsStudioScreen(
    viewModel: MoteViewModel,
    isGuest: Boolean,
    onSelectPersonaForChat: (String) -> Unit,
    onOpenChatTab: () -> Unit = {}
) {
    val botsState by viewModel.botsState.collectAsStateWithLifecycle()
    val llmConfig by viewModel.llmConfig.collectAsStateWithLifecycle()
    val studioPrefs by viewModel.studioPrefs.collectAsStateWithLifecycle()
    val ecoInferenceRestricted by viewModel.ecoInferenceRestricted.collectAsStateWithLifecycle()
    val selectedBotId by viewModel.selectedBotId.collectAsStateWithLifecycle()
    val localModels by viewModel.localModels.collectAsStateWithLifecycle()
    val loadedLocalModel by viewModel.loadedLocalModel.collectAsStateWithLifecycle()
    val loadingLocalModelId by viewModel.loadingLocalModelId.collectAsStateWithLifecycle()
    val localBackendChoice by viewModel.localBackendChoice.collectAsStateWithLifecycle()
    val preferLoadedLocalChat by viewModel.preferLoadedLocalChat.collectAsStateWithLifecycle()
    val hardware by viewModel.deviceHardware.collectAsStateWithLifecycle()
    val batteryTelemetry by viewModel.batteryTelemetry.collectAsStateWithLifecycle()
    val allBenchmarks by viewModel.allBenchmarks.collectAsStateWithLifecycle()
    val liveBenchmark by viewModel.liveBenchmark.collectAsStateWithLifecycle()
    val utilityStatus by viewModel.utilityStatus.collectAsStateWithLifecycle()
    val isCheckingModelUpdates by viewModel.isCheckingModelUpdates.collectAsStateWithLifecycle()
    val isBenchmarking by viewModel.isBenchmarking.collectAsStateWithLifecycle()
    val toolUsageStats by viewModel.toolUsageStats.collectAsStateWithLifecycle()
    val toolAuditLog by viewModel.toolAuditLog.collectAsStateWithLifecycle()
    val liteRtLogs by viewModel.liteRtDiagnosticLogs.collectAsStateWithLifecycle()
    val liteRtSummary by viewModel.liteRtDiagnosticSummary.collectAsStateWithLifecycle()
    val parserTelemetry by viewModel.streamParserTelemetry.collectAsStateWithLifecycle()
    val systemHealth by viewModel.systemHealth.collectAsStateWithLifecycle()
    val lastAdaptiveRouteNote by viewModel.lastAdaptiveRouteNote.collectAsStateWithLifecycle()
    val aiCoreStatus by viewModel.aiCoreStatus.collectAsStateWithLifecycle()
    val toolSyncVerifications by viewModel.toolSyncVerifications.collectAsStateWithLifecycle()

    var selectedStudioTab by remember { mutableIntStateOf(0) } // 0 = Models, 1 = Debug
    var showCreateSheet by remember { mutableStateOf(false) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var detailsModelId by remember { mutableStateOf<String?>(null) }

    val benchmarksByModel = remember(allBenchmarks) {
        allBenchmarks.groupBy { it.modelId }
    }

    // Advanced filter & sort states
    var searchQuery by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf(ModelHubScope.ON_DEVICE) }
    var selectedCapability by remember { mutableStateOf<ModelCapability?>(null) }
    var sizeFilter by remember { mutableStateOf(ModelSizeFilter.ANY) }
    var sortOrder by remember { mutableStateOf(ModelSortOrder.SMALLEST) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalModel(uri)
        }
    }

    val activeDownloads = remember(localModels) {
        localModels.filter {
            it.status == LocalModelDownloadStatus.DOWNLOADING || it.status == LocalModelDownloadStatus.QUEUED
        }
    }

    val downloadedCount = remember(localModels) {
        localModels.count { it.status == LocalModelDownloadStatus.DOWNLOADED }
    }

    val filteredPhoneModels = remember(
        localModels,
        searchQuery,
        selectedScope,
        selectedCapability,
        sizeFilter,
        sortOrder,
        hardware.totalRamMb
    ) {
        val q = searchQuery.trim().lowercase(Locale.US)
        localModels
            .filter { entry ->
                when (selectedScope) {
                    ModelHubScope.DOWNLOADED -> entry.status == LocalModelDownloadStatus.DOWNLOADED
                    ModelHubScope.ON_DEVICE -> true
                    ModelHubScope.CLOUD_API -> false
                }
            }
            .filter { entry ->
                if (q.isBlank()) true else {
                    entry.displayName.lowercase(Locale.US).contains(q) ||
                        entry.family.lowercase(Locale.US).contains(q) ||
                        entry.summary.lowercase(Locale.US).contains(q) ||
                        entry.capabilities.any { it.label.lowercase(Locale.US).contains(q) }
                }
            }
            .filter { entry ->
                selectedCapability == null || selectedCapability in entry.capabilities
            }
            .filter { entry ->
                when (sizeFilter) {
                    ModelSizeFilter.ANY -> true
                    ModelSizeFilter.UNDER_600MB -> entry.approxSizeMb <= 600L
                    ModelSizeFilter.FITS_MY_RAM -> hardware.totalRamMb == 0L || hardware.totalRamMb >= entry.minRamMb
                }
            }
            .let { list ->
                when (sortOrder) {
                    ModelSortOrder.SMALLEST -> list.sortedBy { it.approxSizeMb }
                    ModelSortOrder.LARGEST -> list.sortedByDescending { it.approxSizeMb }
                    ModelSortOrder.NAME -> list.sortedBy { it.displayName.lowercase(Locale.US) }
                    ModelSortOrder.RECENT -> list.sortedByDescending { it.lastUsedAtEpochMs ?: it.downloadedAtEpochMs ?: 0L }
                }
            }
    }

    val filteredCloudModels = remember(searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        if (q.isBlank()) HybridAiRouter.supportedModels
        else HybridAiRouter.supportedModels.filter {
            it.displayName.lowercase(Locale.US).contains(q) ||
                it.description.lowercase(Locale.US).contains(q) ||
                it.badge.lowercase(Locale.US).contains(q)
        }
    }

    val activeCloudModelName = remember(llmConfig.modelName) {
        HybridAiRouter.supportedModels.firstOrNull { it.id == llmConfig.modelName }?.displayName
            ?: llmConfig.modelName
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Model Hub",
            subtitle = "RAM ${hardware.availableRamMb}MB free · ${formatModelBytes(hardware.modelsDiskUsageBytes)} in Room"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showCustomUrlDialog = true },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("add_custom_model_url_button")
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(15.dp), tint = MoteNavy)
                    Spacer(Modifier.width(3.dp))
                    Text("URL", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("import_local_model_button")
                ) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp), tint = MoteNavy)
                    Spacer(Modifier.width(3.dp))
                    Text("Import", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                }
                FilledTonalButton(
                    onClick = { showCreateSheet = true },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = LocalMoteColors.current.primaryActionBg,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("new_persona_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Bot", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Segmented Studio Tab Switcher: Models | Debug (LiteRT-LM Traces & Tensor Shapes)
        val tokens = LocalMoteColors.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.divider)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                0 to "Models",
                1 to "Debug"
            ).forEach { (tabIdx, title) ->
                val selected = selectedStudioTab == tabIdx
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) tokens.surface else Color.Transparent)
                        .clickable { selectedStudioTab = tabIdx }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(if (tabIdx == 0) "models_tab_catalog" else "models_tab_debug"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (tabIdx == 0) Icons.Outlined.Memory else Icons.Outlined.BugReport,
                        contentDescription = null,
                        tint = if (selected) MoteNavy else SlateMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) MoteNavy else SlateMuted
                    )
                    if (tabIdx == 1 && (liteRtSummary.errorCount > 0 || liteRtLogs.isNotEmpty())) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (liteRtSummary.errorCount > 0) Color(0xFFDC2626) else CloudFlashCobalt
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (liteRtSummary.errorCount > 0) {
                                    "${liteRtSummary.errorCount}"
                                } else {
                                    "${liteRtLogs.size}"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        if (selectedStudioTab == 1) {
            LiteRtDebugTabContent(
                logs = liteRtLogs,
                summary = liteRtSummary,
                parserTelemetry = parserTelemetry,
                loadedLocalModel = loadedLocalModel,
                hardware = hardware,
                onRunSelfCheck = { viewModel.runLiteRtSelfCheck() },
                onClearLogs = { viewModel.clearLiteRtDiagnosticLogs() },
                onBuildReportText = { viewModel.formatLiteRtDiagnosticReport() },
                modifier = Modifier.weight(1f)
            )
        } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            // 1. Truthful Active Runtime Status Card (On-Device RAM vs Cloud API)
            item(key = "active_runtime_status") {
                ActiveRuntimeStatusCard(
                    loadedLocalModel = loadedLocalModel,
                    preferLoadedLocalChat = preferLoadedLocalChat,
                    selectedCloudModelName = activeCloudModelName,
                    isBenchmarking = isBenchmarking,
                    onToggleOfflineChat = { viewModel.setPreferLoadedLocalChat(it) },
                    onBenchmark = { viewModel.benchmarkLoadedLocalModel() },
                    onUnload = { viewModel.unloadLocalModel() }
                )
            }

            // 1a. Official Firebase AI Logic Hybrid SDK (OnDeviceConfig + AICore Gemini Nano + Native Tools)
            item(key = "firebase_hybrid_sdk_card") {
                FirebaseHybridStatusCard(
                    selectedMode = MoteInferenceMode.fromString(studioPrefs.hybridInferenceMode),
                    aiCoreStatus = aiCoreStatus,
                    nativeFunctionCallingEnabled = studioPrefs.nativeFunctionCallingEnabled,
                    recentSyncRecords = toolSyncVerifications,
                    onSelectMode = { viewModel.setHybridInferenceMode(it) },
                    onToggleNativeFunctionCalling = { viewModel.setNativeFunctionCallingEnabled(it) },
                    onRefreshAiCore = { viewModel.refreshAiCoreStatus() },
                    onDownloadGeminiNano = { viewModel.downloadGeminiNanoViaAiCore() }
                )
            }

            // 1b. SystemHealthManager Battery/Thermal Adaptive Inference Toggle (Local vs Cloud)
            item(key = "system_health_adaptive_card") {
                SystemHealthAdaptiveCard(
                    health = systemHealth,
                    adaptiveEnabled = studioPrefs.adaptiveThermalBatteryRouting,
                    lastRouteNote = lastAdaptiveRouteNote,
                    onToggleAdaptive = { viewModel.setAdaptiveThermalBatteryRouting(it) },
                    onRefresh = { viewModel.refreshSystemHealth() }
                )
            }

            // 2. Real-Time Battery & Thermal Resource Monitor (alerts on excessive draw during active inference)
            item(key = "battery_resource_monitor") {
                BatteryResourceMonitorCard(
                    telemetry = batteryTelemetry,
                    currentBackend = localBackendChoice,
                    onRefresh = { viewModel.refreshBatteryTelemetry() },
                    onSwitchToCpu = {
                        viewModel.selectLocalBackend(LocalHardwareBackend.CPU)
                        loadedLocalModel?.let { viewModel.loadLocalModel(it.modelId) }
                    },
                    onUnloadModel = { viewModel.unloadLocalModel() }
                )
            }

            // 2b. Eco-Inference Battery Guard (< 20% auto-restrict background model processing)
            item(key = "eco_inference_guard") {
                EcoInferenceCard(
                    ecoInferenceEnabled = studioPrefs.ecoInferenceEnabled,
                    ecoInferenceRestricted = ecoInferenceRestricted,
                    telemetry = batteryTelemetry,
                    onToggleEcoInference = { viewModel.updateEcoInferenceEnabled(it) }
                )
            }

            // 2c. AI Tool Usage Statistics Dashboard (frequency chart + Clear History)
            item(key = "tool_usage_stats_dashboard") {
                ToolUsageStatsDashboardCard(
                    stats = toolUsageStats,
                    recentLogs = toolAuditLog,
                    onClearHistory = { viewModel.clearToolAuditLog() }
                )
            }

            // 2c. Active WorkManager Background Downloads Strip
            if (activeDownloads.isNotEmpty()) {
                item(key = "active_workmanager_downloads") {
                    ActiveWorkManagerDownloadsCard(
                        activeDownloads = activeDownloads,
                        onCancel = { viewModel.cancelModelDownload(it) }
                    )
                }
            }

            // 2d. Lobe-Style Local Model Visual Metadata Gallery
            item(key = "lobe_model_metadata_gallery") {
                LobeModelMetadataGalleryCard(
                    models = localModels,
                    loadedLocalModel = loadedLocalModel,
                    benchmarksByModel = benchmarksByModel,
                    onSelectModel = { detailsModelId = it }
                )
            }

            // 3. Search, Scope, Capability, Size/RAM, and Sort Filters
            item(key = "advanced_filters") {
                ModelAdvancedFilterPanel(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    selectedScope = selectedScope,
                    onScopeChange = { selectedScope = it },
                    downloadedCount = downloadedCount,
                    selectedCapability = selectedCapability,
                    onCapabilityChange = { selectedCapability = it },
                    sizeFilter = sizeFilter,
                    onSizeFilterChange = { sizeFilter = it },
                    sortOrder = sortOrder,
                    onSortOrderChange = { sortOrder = it }
                )
            }

            // 4. Filtered Model Catalog (Phone-Runnable LiteRT-LM vs Cloud API)
            if (selectedScope == ModelHubScope.CLOUD_API) {
                item(key = "cloud_section_header") {
                    Text(
                        text = "CLOUD MODELS · FIREBASE AI LOGIC (NO DOWNLOAD REQUIRED)",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateMuted,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                    )
                }
                items(filteredCloudModels, key = { it.id }) { model ->
                    val isSelected = llmConfig.modelName == model.id && !preferLoadedLocalChat
                    MoteCard(
                        background = if (isSelected) Color.White else Color(0xFFF8FAFC),
                        modifier = Modifier
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) CloudFlashCobalt else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                viewModel.updateModel(model.id)
                                onOpenChatTab()
                            }
                            .testTag("model_option_${model.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LobeModelAvatar(modelId = model.id, size = 40.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = model.displayName,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MoteNavy,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(tokens.accentBlueBg)
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Cloud API · ${model.contextLabel}",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = tokens.accentBlue
                                        )
                                    }
                                }
                                Text(model.badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LocalNanoEmerald)
                                Spacer(Modifier.height(2.dp))
                                Text(model.description, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
                            }
                            Icon(
                                imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = if (isSelected) "Active Cloud Model" else "Use Cloud Model",
                                tint = if (isSelected) CloudFlashCobalt else SlateMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            } else {
                item(key = "phone_models_header") {
                    Text(
                        text = "PHONE-RUNNABLE MODELS (${filteredPhoneModels.size}) · LITERT-LM ON-DEVICE",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateMuted,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                    )
                }
                if (filteredPhoneModels.isEmpty()) {
                    item(key = "empty_filtered_models") {
                        MoteCard {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Outlined.FilterListOff, contentDescription = null, tint = SlateMuted, modifier = Modifier.size(28.dp))
                                Text(
                                    text = if (selectedScope == ModelHubScope.DOWNLOADED) {
                                        "No verified models downloaded on disk yet"
                                    } else {
                                        "No phone models match your active filters"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy
                                )
                                TextButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedScope = ModelHubScope.ON_DEVICE
                                        selectedCapability = null
                                        sizeFilter = ModelSizeFilter.ANY
                                    }
                                ) {
                                    Text("Reset Filters", color = CloudFlashCobalt, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    items(filteredPhoneModels, key = { it.id }) { entry ->
                        PhoneModelCard(
                            entry = entry,
                            hardware = hardware,
                            latestBenchmark = benchmarksByModel[entry.id]?.firstOrNull(),
                            isLoaded = loadedLocalModel?.modelId == entry.id,
                            isLoading = loadingLocalModelId == entry.id,
                            onOpenDetails = { detailsModelId = entry.id },
                            onDownload = { viewModel.startModelDownload(entry.id) },
                            onCancel = { viewModel.cancelModelDownload(entry.id) },
                            onLoad = { viewModel.selectDownloadedModelForChat(entry.id) },
                            onChatWithModel = {
                                viewModel.selectDownloadedModelForChat(entry.id) {
                                    onOpenChatTab()
                                }
                            },
                            onUnload = { viewModel.unloadLocalModel() },
                            onDelete = { viewModel.deleteLocalModel(entry.id) }
                        )
                    }
                }
            }

            // 5. Storage & Diagnostic Utilities Card (including Clear Model Cache)
            item(key = "storage_utilities_card") {
                val unusedDownloaded = localModels.filter {
                    it.status == LocalModelDownloadStatus.DOWNLOADED &&
                        it.id != loadedLocalModel?.modelId
                }
                val unusedDiskBytes = unusedDownloaded.sumOf { it.downloadedBytes } + hardware.tempCacheBytes
                ModelUtilitiesCard(
                    hardware = hardware,
                    utilityStatus = utilityStatus,
                    unusedDownloadedCount = unusedDownloaded.size,
                    unusedDiskBytes = unusedDiskBytes,
                    isCheckingUpdates = isCheckingModelUpdates,
                    lastUpdateSummary = studioPrefs.lastModelUpdateSummary,
                    onDismissStatus = { viewModel.clearUtilityStatus() },
                    onVerifyFiles = { viewModel.verifyLocalModelStorage() },
                    onCheckUpdatesNow = { viewModel.checkForModelUpdatesNow(autoDownload = true) },
                    onClearCache = { viewModel.clearDownloadTempCache() },
                    onClearModelCache = { viewModel.clearUnusedModelCache() },
                    onRefreshHardware = { viewModel.refreshHardware() }
                )
            }

            // 6. Room-Persisted Engine, Download Policy & Sampling Configurations
            item(key = "configurations_card") {
                ModelConfigurationsCard(
                    prefs = studioPrefs,
                    llmConfig = llmConfig,
                    localBackendChoice = localBackendChoice,
                    onSelectBackend = { viewModel.selectLocalBackend(it) },
                    onWifiOnlyChange = { viewModel.updateWifiOnlyDownloads(it) },
                    onBatterySafeChange = { viewModel.updateRequireBatteryNotLow(it) },
                    onAutoLoadChange = { viewModel.updateAutoLoadLastModel(it) },
                    onAutoCheckUpdatesChange = { viewModel.updateAutoCheckModelUpdates(it) },
                    onTemperatureChange = { viewModel.updateTemperature(it) },
                    onTopPChange = { viewModel.updateTopP(it) },
                    onMaxTokensChange = { viewModel.updateMaxOutputTokens(it) },
                    onContextTurnsChange = { viewModel.updateContextWindowTurns(it) }
                )
            }

            // 7. Bot Personas
            item(key = "personas_section_header") {
                Text(
                    text = if (isGuest) "BOT PERSONAS (LOCAL GUEST)" else "BOT PERSONAS (FIRESTORE SYNCED)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateMuted,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            when (val state = botsState) {
                is BotsUiState.Loading -> {
                    item(key = "bots_loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MoteNavy, strokeWidth = 2.5.dp)
                        }
                    }
                }
                is BotsUiState.Error -> {
                    item(key = "bots_error") {
                        MoteCard(background = Color(0xFFFEF2F2)) {
                            Text(state.message, fontSize = 12.5.sp, color = Color(0xFFB91C1C))
                        }
                    }
                }
                is BotsUiState.Success -> {
                    items(state.bots, key = { it.id }) { bot ->
                        val isCurrentChatBot = bot.id == selectedBotId
                        MoteCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(bot.colorHex))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bot.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = MoteNavy)
                                        Text(bot.role, fontSize = 11.5.sp, color = SlateMuted)
                                    }
                                }
                                IosSpringSwitch(
                                    checked = bot.active,
                                    onCheckedChange = { viewModel.toggleBot(bot) },
                                    modifier = Modifier.testTag("bot_toggle_${bot.id}")
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(bot.systemPrompt, fontSize = 12.sp, color = SlateMuted, lineHeight = 17.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Mode: ${bot.inferenceMode}", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
                                TextButton(
                                    onClick = { onSelectPersonaForChat(bot.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text(
                                        text = if (isCurrentChatBot) "Active in Chat" else "Use in Chat",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrentChatBot) LocalNanoEmerald else MoteNavy
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showCreateSheet) {
        CreatePersonaBottomSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { newBot ->
                viewModel.saveBotPersona(newBot, isExisting = false)
                showCreateSheet = false
            }
        )
    }

    if (showCustomUrlDialog) {
        CustomModelUrlDialog(
            onDismiss = { showCustomUrlDialog = false },
            onDownloadUrl = { name, url ->
                viewModel.addCustomModelUrl(name, url)
                showCustomUrlDialog = false
            }
        )
    }

    detailsModelId?.let { targetId ->
        val selectedEntry = localModels.firstOrNull { it.id == targetId }
        if (selectedEntry != null) {
            ModelDetailsSheet(
                entry = selectedEntry,
                hardware = hardware,
                isLoaded = loadedLocalModel?.modelId == selectedEntry.id,
                isLoading = loadingLocalModelId == selectedEntry.id,
                localBackendChoice = localBackendChoice,
                isBenchmarking = isBenchmarking,
                liveBenchmark = liveBenchmark,
                modelBenchmarks = benchmarksByModel[selectedEntry.id].orEmpty(),
                onSelectBackend = { viewModel.selectLocalBackend(it) },
                onDownload = { viewModel.startModelDownload(selectedEntry.id) },
                onLoad = { viewModel.loadLocalModel(selectedEntry.id) },
                onUnload = { viewModel.unloadLocalModel() },
                onRunBenchmark = { preset -> viewModel.runModelBenchmark(selectedEntry.id, preset) },
                onDismiss = { detailsModelId = null }
            )
        }
    }
}

@Composable
private fun CustomModelUrlDialog(
    onDismiss: () -> Unit,
    onDownloadUrl: (String, String) -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var modelUrl by remember { mutableStateOf("") }
    val validUrl = modelUrl.trim().startsWith("https://") || modelUrl.trim().startsWith("http://")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MoteSurface,
        title = {
            Text("Background Fetch Model URL", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a direct Hugging Face or HTTPS link to a phone-runnable .task or .litertlm bundle. WorkManager will fetch and verify it in the background.",
                    fontSize = 12.sp,
                    color = SlateMuted
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(60) },
                    label = { Text("Model Name (optional)") },
                    placeholder = { Text("e.g., SmolLM 135M") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_model_name_input")
                )
                OutlinedTextField(
                    value = modelUrl,
                    onValueChange = { modelUrl = it },
                    label = { Text("Direct .task / .litertlm URL") },
                    placeholder = { Text("https://huggingface.co/.../model.litertlm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_model_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownloadUrl(displayName, modelUrl) },
                enabled = validUrl,
                colors = ButtonDefaults.buttonColors(containerColor = LocalMoteColors.current.primaryActionBg),
                modifier = Modifier.testTag("confirm_custom_model_url_button")
            ) {
                Text("Queue Download", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SlateMuted)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePersonaBottomSheet(
    onDismiss: () -> Unit,
    onCreate: (BotPersona) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(personaAccentPalette.first()) }
    var selectedMode by remember { mutableStateOf(MoteInferenceMode.PREFER_ON_DEVICE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MoteSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Create Bot Persona", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MoteNavy)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Persona Name") },
                placeholder = { Text("e.g., Code Coach") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("persona_name_input")
            )

            OutlinedTextField(
                value = role,
                onValueChange = { role = it.take(80) },
                label = { Text("Short Role") },
                placeholder = { Text("e.g., Kotlin & Architecture Reviewer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("persona_role_input")
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it.take(1000) },
                label = { Text("System Instruction") },
                placeholder = { Text("Describe how this assistant should respond...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("persona_prompt_input")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Accent:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = SlateMuted)
                personaAccentPalette.forEach { hex ->
                    val chosen = hex == selectedColor
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(
                                width = if (chosen) 2.5.dp else 0.dp,
                                color = if (chosen) CloudFlashCobalt else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { selectedColor = hex }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    MoteInferenceMode.PREFER_ON_DEVICE to "Hybrid",
                    MoteInferenceMode.PREFER_IN_CLOUD to "Cloud"
                ).forEach { (mode, title) ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            val canSave = name.isNotBlank() && role.isNotBlank() && systemPrompt.isNotBlank()
            Button(
                onClick = {
                    val cleanId = name.trim().lowercase(Locale.US)
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                        .take(40)
                        .ifBlank { "persona_${System.currentTimeMillis()}" }
                    onCreate(
                        BotPersona(
                            id = "${cleanId}_${(System.currentTimeMillis() % 10000)}",
                            name = name.trim(),
                            role = role.trim(),
                            colorHex = selectedColor,
                            active = true,
                            inferenceMode = selectedMode.name,
                            systemPrompt = systemPrompt.trim()
                        )
                    )
                },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalMoteColors.current.primaryActionBg),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_persona_button")
            ) {
                Text("Save Persona", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

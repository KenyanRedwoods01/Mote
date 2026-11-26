package com.mote.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.LlmConfig
import com.mote.app.service.BotOverlayService
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.auth.GoogleSignInButton
import com.mote.app.ui.auth.signOut
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import com.mote.app.ui.theme.ThemePreferenceStore
import java.util.Locale

@Composable
fun SecurityCenterScreen(
    viewModel: MoteViewModel,
    floatingAssistantVisible: Boolean = true,
    onToggleFloatingAssistant: () -> Unit = {}
) {
    val context = LocalContext.current
    val hardware by viewModel.deviceHardware.collectAsStateWithLifecycle()
    val lanState by viewModel.lanServerState.collectAsStateWithLifecycle()
    val loadedLocal by viewModel.loadedLocalModel.collectAsStateWithLifecycle()
    val studioPrefs by viewModel.studioPrefs.collectAsStateWithLifecycle()
    val toolPermissions by viewModel.toolPermissions.collectAsStateWithLifecycle()
    val toolAuditLog by viewModel.toolAuditLog.collectAsStateWithLifecycle()
    val clockAlarms by viewModel.clockAlarms.collectAsStateWithLifecycle()
    val clockTimers by viewModel.clockTimers.collectAsStateWithLifecycle()
    val clockStopwatch by viewModel.clockStopwatch.collectAsStateWithLifecycle()
    val calendarEvents by viewModel.calendarEvents.collectAsStateWithLifecycle()

    var piiEnabled by remember { mutableStateOf(true) }
    var blockBanking by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Tools & Permissions",
            subtitle = "AI tool registry, Android permissions & guards"
        ) {
            Text(
                text = "${toolPermissions.grantedCount}/${toolPermissions.totalCount} Perms",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalNanoEmerald
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            // 1. Model Tools Registry Control Center (enable/disable AlarmManager, Timer, Stopwatch, Calendar, GenUI)
            item(key = "model_tools_registry_card") {
                ModelToolsRegistryCard(
                    tools = viewModel.registeredTools,
                    prefs = studioPrefs,
                    permissions = toolPermissions,
                    activeAlarmCount = clockAlarms.count { it.enabled },
                    activeTimerCount = clockTimers.count { it.isRunning },
                    stopwatchRunning = clockStopwatch.isRunning,
                    activeCalendarCount = calendarEvents.size,
                    onToggleTool = { toolId, enabled -> viewModel.setToolEnabled(toolId, enabled) }
                )
            }

            // 2. Android Permissions Manager (Exact Alarms, Notifications, Microphone, Write Settings, DND, Overlay)
            item(key = "tool_permissions_manager_card") {
                ToolPermissionsManagerCard(
                    permissions = toolPermissions,
                    onRefreshPermissions = { viewModel.refreshToolPermissions() }
                )
            }

            // 3. Live Tool Execution Audit Log
            item(key = "tool_execution_audit_card") {
                ToolExecutionAuditCard(
                    entries = toolAuditLog,
                    onClear = { viewModel.clearToolAuditLog() }
                )
            }

            // Device Hardware & Memory Inspector (inspired by local-llms-on-android)
            item(key = "hardware_telemetry_card") {
                MoteCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Memory, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(18.dp))
                            Text("DEVICE TELEMETRY", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                        }
                        IconButton(
                            onClick = { viewModel.refreshHardware() },
                            modifier = Modifier.size(40.dp).testTag("refresh_hardware_button")
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh telemetry", tint = SlateMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareMetricTile(
                            label = "Free RAM",
                            value = "${hardware.availableRamMb} MB",
                            sub = "of ${hardware.totalRamMb} MB",
                            modifier = Modifier.weight(1f)
                        )
                        HardwareMetricTile(
                            label = "CPU / ABI",
                            value = "${hardware.cpuCores} cores",
                            sub = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val diskMb = hardware.modelsDiskUsageBytes / (1024.0 * 1024.0)
                    Text(
                        text = String.format(
                            Locale.US,
                            "Model Storage: %.1f MB · Active Local: %s",
                            diskMb,
                            loadedLocal?.displayName ?: "None loaded"
                        ),
                        fontSize = 11.5.sp,
                        color = SlateMuted
                    )
                }
            }

            // Local OpenAI-Compatible LAN Endpoint (inspired by local-llms-on-android)
            item(key = "lan_api_server_card") {
                val baseUrl = "http://${lanState.hostIp}:${lanState.port}/v1/models"
                MoteCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Dns, contentDescription = null, tint = if (lanState.isRunning) LocalNanoEmerald else SlateMuted, modifier = Modifier.size(18.dp))
                            Column {
                                Text("LAN OPENAI-COMPATIBLE API", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                                Text(
                                    text = if (lanState.isRunning) "Listening · ${lanState.requestCount} requests" else "Stopped · Tap to serve /v1/models",
                                    fontSize = 11.sp,
                                    color = if (lanState.isRunning) LocalNanoEmerald else SlateMuted
                                )
                            }
                        }
                        IosSpringSwitch(
                            checked = lanState.isRunning,
                            onCheckedChange = { viewModel.toggleLanServer() },
                            modifier = Modifier.testTag("lan_server_toggle")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IosCanvas)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = baseUrl,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MoteNavy,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Mote LAN Endpoint", baseUrl))
                                Toast.makeText(context, "Endpoint copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy endpoint", tint = CloudFlashCobalt, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Require Bearer Token (${lanState.bearerToken})", fontSize = 11.5.sp, color = SlateMuted, modifier = Modifier.weight(1f))
                        IosSpringSwitch(
                            checked = lanState.requireBearerToken,
                            onCheckedChange = { viewModel.toggleLanBearerToken(it) }
                        )
                    }
                    lanState.errorMessage?.let { err ->
                        Text(err, fontSize = 11.5.sp, color = Color(0xFFB91C1C))
                    }
                }
            }

            // Floating Assistant Surface & System Overlay Controls
            item(key = "floating_overlay_card") {
                MoteCard(background = MoteBrandNavy) {
                    Text("FLOATING ASSISTANT SURFACE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Keep Mote's draggable voice-enabled orb active inside the app or launch a system overlay across other apps.",
                        fontSize = 12.5.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onToggleFloatingAssistant,
                        colors = ButtonDefaults.buttonColors(containerColor = LocalNanoEmerald),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("privacy_toggle_orb_button")
                    ) {
                        Text(
                            text = if (floatingAssistantVisible) "Hide In-App Orb" else "Show In-App Orb",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                context.startForegroundService(Intent(context, BotOverlayService::class.java))
                            } else {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spawn_bot_button")
                    ) {
                        Text("Spawn System Overlay Over Other Apps", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    }
                }
            }

            item(key = "pii_guard_card") {
                MoteCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Local PII Redaction", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Masks emails, phones, OTPs, and cards on-device.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = piiEnabled, onCheckedChange = { piiEnabled = it })
                    }
                }
            }

            item(key = "banking_guard_card") {
                MoteCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Block Banking & FLAG_SECURE", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Skips protected windows and password inputs.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = blockBanking, onCheckedChange = { blockBanking = it })
                    }
                }
            }

            item(key = "accessibility_button") {
                val tokens = LocalMoteColors.current
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryActionBg),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("accessibility_settings_button")
                ) {
                    Text("Open Accessibility Settings", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun HardwareMetricTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(IosCanvas)
            .padding(10.dp)
    ) {
        Text(label, fontSize = 10.5.sp, color = SlateMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        Text(sub, fontSize = 10.5.sp, color = CloudFlashCobalt, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SettingsScreen(
    viewModel: MoteViewModel,
    isGuest: Boolean,
    userEmail: String?,
    llmConfig: LlmConfig,
    onClearChatHistory: () -> Unit,
    onAuthSuccess: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val studioPrefs by viewModel.studioPrefs.collectAsStateWithLifecycle()
    val toolPermissions by viewModel.toolPermissions.collectAsStateWithLifecycle()
    val utilityStatus by viewModel.utilityStatus.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val systemHealth by viewModel.systemHealth.collectAsStateWithLifecycle()
    val lastAdaptiveRouteNote by viewModel.lastAdaptiveRouteNote.collectAsStateWithLifecycle()
    var haptics by remember { mutableStateOf(true) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showToolPermissions by remember { mutableStateOf(false) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSettingsBackup(uri)
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importSettingsBackup(uri)
        }
    }

    if (showToolPermissions) {
        ToolAndModelPermissionsScreen(
            viewModel = viewModel,
            onBack = { showToolPermissions = false }
        )
        return
    }

    val authorizedToolsCount = listOf(
        studioPrefs.toolAlarmEnabled,
        studioPrefs.toolTimerEnabled,
        studioPrefs.toolStopwatchEnabled,
        studioPrefs.toolCalendarEnabled,
        studioPrefs.toolCameraEnabled,
        studioPrefs.toolScreenCaptureEnabled,
        studioPrefs.toolAppLauncherEnabled,
        studioPrefs.toolNavigationEnabled,
        studioPrefs.toolGenUiEnabled
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Settings",
            subtitle = "Engine configuration, permissions & sync"
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            item(key = "settings_global_theme_card") {
                val tokens = LocalMoteColors.current
                val isDark by ThemePreferenceStore.isDarkTheme.collectAsStateWithLifecycle()
                fun applyTheme(dark: Boolean) {
                    ThemePreferenceStore.setDarkTheme(context, dark)
                    viewModel.updateDarkThemeEnabled(dark)
                }
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
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) tokens.accentBlueBg else Color(0xFFFEF3C7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                                    contentDescription = null,
                                    tint = if (isDark) tokens.accentBlue else Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("APPEARANCE · GLOBAL THEME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tokens.accentBlue)
                                Text(
                                    text = if (isDark) "Dark Mode Active" else "Light Mode Active",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = MoteNavy
                                )
                                Text(
                                    text = "Syncs across Chat, Model Hub, Floating Assistant & Overlays.",
                                    fontSize = 11.5.sp,
                                    color = SlateMuted
                                )
                            }
                        }
                        IosSpringSwitch(
                            checked = isDark,
                            onCheckedChange = { applyTheme(it) },
                            modifier = Modifier.testTag("settings_theme_toggle")
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IosCanvas)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!isDark) tokens.surface else Color.Transparent)
                                .clickable { applyTheme(false) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("settings_theme_light_button"),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LightMode,
                                contentDescription = null,
                                tint = if (!isDark) Color(0xFFD97706) else SlateMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Light",
                                fontSize = 12.5.sp,
                                fontWeight = if (!isDark) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isDark) MoteNavy else SlateMuted
                            )
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) tokens.surface else Color.Transparent)
                                .clickable { applyTheme(true) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("settings_theme_dark_button"),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DarkMode,
                                contentDescription = null,
                                tint = if (isDark) tokens.accentBlue else SlateMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Dark",
                                fontSize = 12.5.sp,
                                fontWeight = if (isDark) FontWeight.Bold else FontWeight.Medium,
                                color = if (isDark) MoteNavy else SlateMuted
                            )
                        }
                    }
                }
            }
            item(key = "settings_ai_logic_card") {
                val tokens = LocalMoteColors.current
                MoteCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LobeModelAvatar(modelId = llmConfig.modelName, size = 36.dp)
                        Column {
                            Text("FIREBASE AI LOGIC", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = tokens.accentBlue)
                            Text("Active Model: ${llmConfig.modelName}", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Temp %.2f · Top-P %.2f · Max %d tokens",
                                    llmConfig.temperature,
                                    llmConfig.topP,
                                    llmConfig.maxOutputTokens
                                ),
                                fontSize = 11.5.sp,
                                color = SlateMuted
                            )
                        }
                    }
                }
            }
            item(key = "settings_system_health_adaptive_card") {
                SystemHealthAdaptiveCard(
                    health = systemHealth,
                    adaptiveEnabled = studioPrefs.adaptiveThermalBatteryRouting,
                    lastRouteNote = lastAdaptiveRouteNote,
                    onToggleAdaptive = { viewModel.setAdaptiveThermalBatteryRouting(it) },
                    onRefresh = { viewModel.refreshSystemHealth() }
                )
            }
            item(key = "settings_tool_model_permissions_entry") {
                val tokens = LocalMoteColors.current
                MoteCard(
                    modifier = Modifier
                        .border(1.dp, tokens.accentBlue.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .clickable { showToolPermissions = true }
                        .testTag("settings_open_tool_permissions_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(tokens.accentBlueBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.VerifiedUser,
                                    contentDescription = null,
                                    tint = tokens.accentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tool & Model Permissions",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = MoteNavy
                                )
                                Text(
                                    text = "$authorizedToolsCount/9 AI tools authorized · ${toolPermissions.grantedCount}/${toolPermissions.totalCount} Android perms",
                                    fontSize = 11.5.sp,
                                    color = tokens.accentBlue,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Manage Clock, Calendar, Camera, Screenshots, Apps, Navigation & GenUI.",
                                    fontSize = 11.sp,
                                    color = SlateMuted
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "Open Tool and Model Permissions",
                            tint = SlateMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            item(key = "settings_json_backup_card") {
                val tokens = LocalMoteColors.current
                MoteCard(
                    modifier = Modifier
                        .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
                        .testTag("settings_backup_restore_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(tokens.accentEmeraldBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsBackupRestore,
                                contentDescription = null,
                                tint = LocalNanoEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("BACKUP & RESTORE (JSON)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LocalNanoEmerald)
                            Text("Export & Import Settings", fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = MoteNavy)
                            Text(
                                text = "Save or restore theme, model configs, sampling sliders, tool permissions & personas as a JSON file.",
                                fontSize = 11.5.sp,
                                color = SlateMuted
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                exportBackupLauncher.launch("mote_settings_backup.json")
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.primaryActionBg),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_export_json_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileUpload,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Export JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_import_json_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = null,
                                tint = MoteNavy,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Import JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                        }
                    }

                    utilityStatus?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.accentEmeraldBg)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = status,
                                fontSize = 11.5.sp,
                                color = if (tokens.isDark) Color(0xFFA7F3D0) else Color(0xFF065F46),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearUtilityStatus() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss status",
                                    tint = if (tokens.isDark) Color(0xFFA7F3D0) else Color(0xFF065F46),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    actionError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.errorBg)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = err,
                                fontSize = 11.5.sp,
                                color = tokens.errorBody,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearActionError() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss error",
                                    tint = tokens.errorTitle,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
            item {
                MoteCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Haptic Navigation & Feedback", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Tactile waveforms for orb expand/collapse and tab glide.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = haptics, onCheckedChange = { haptics = it })
                    }
                }
            }
            item {
                MoteCard {
                    Text("CONVERSATION DATA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isGuest) {
                            "Stored locally in memory for this guest session."
                        } else {
                            "Synced securely to your private Firestore chat history."
                        },
                        fontSize = 12.sp,
                        color = SlateMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onClearChatHistory,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_clear_history_button")
                    ) {
                        Text("Clear Chat History", color = Color(0xFFB91C1C), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                MoteCard {
                    Text("ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                    Spacer(Modifier.height(6.dp))
                    if (isGuest) {
                        Text("Guest Mode (Local only)", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Sign in with Google to sync your bot personas and chat history to Firestore.",
                            fontSize = 12.sp,
                            color = SlateMuted
                        )
                        Spacer(Modifier.height(12.dp))
                        GoogleSignInButton(
                            onAuthSuccess = {
                                authError = null
                                onAuthSuccess()
                            },
                            onAuthError = { msg -> authError = msg }
                        )
                        authError?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(msg, fontSize = 12.sp, color = Color(0xFFB91C1C))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onSignedOut,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("exit_guest_mode_button")
                        ) {
                            Text("Back to Sign-In Screen", color = SlateMuted, fontSize = 12.5.sp)
                        }
                    } else {
                        Text(userEmail ?: "Signed in with Google", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                signOut(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onSignOutComplete = onSignedOut
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sign_out_button")
                        ) {
                            Text("Sign Out", color = MoteNavy, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

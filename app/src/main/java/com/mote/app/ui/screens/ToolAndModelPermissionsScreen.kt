package com.mote.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.GenUiToolRegistry
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors

/**
 * Dedicated 'Tool & Model Permissions' screen accessible from the Settings menu.
 * Features:
 * - Explicit Device Calendar Access section to grant or revoke Android Calendar Provider access
 *   and toggle the AI's ability to list, create, modify, and delete calendar events.
 * - Granular per-tool authorization toggles (AlarmManager, Countdown Timer, Stopwatch, Calendar, GenUI).
 * - Android OS Permission Inspector & Grant controls.
 * - Persisted Tool Execution Audit Log.
 */
@Composable
fun ToolAndModelPermissionsScreen(
    viewModel: MoteViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val studioPrefs by viewModel.studioPrefs.collectAsStateWithLifecycle()
    val toolPermissions by viewModel.toolPermissions.collectAsStateWithLifecycle()
    val toolAuditLog by viewModel.toolAuditLog.collectAsStateWithLifecycle()
    val clockAlarms by viewModel.clockAlarms.collectAsStateWithLifecycle()
    val clockTimers by viewModel.clockTimers.collectAsStateWithLifecycle()
    val clockStopwatch by viewModel.clockStopwatch.collectAsStateWithLifecycle()
    val calendarEvents by viewModel.calendarEvents.collectAsStateWithLifecycle()

    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshToolPermissions()
        val granted = results[Manifest.permission.READ_CALENDAR] == true &&
            results[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            viewModel.setToolEnabled(GenUiToolRegistry.TOOL_CALENDAR, true)
            viewModel.syncDeviceCalendarNow()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
            .testTag("tool_and_model_permissions_screen")
    ) {
        // Top Navigation Bar with Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tool_permissions_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = MoteNavy
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tool & Model Permissions",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                Text(
                    text = "Granular AI authorization & Android OS access",
                    fontSize = 11.5.sp,
                    color = SlateMuted
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            // 1. Explicit User Authorization Policy Banner
            item(key = "explicit_auth_policy_banner") {
                MoteCard(
                    background = MoteBrandNavy,
                    modifier = Modifier.border(1.dp, LocalNanoEmerald.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            tint = LocalNanoEmerald,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = "EXPLICIT AUTHORIZATION ENFORCED",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = LocalNanoEmerald
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The AI model can only call tools that are explicitly authorized below. Toggling any tool OFF immediately removes it from model prompts and blocks execution at runtime.",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                GenUiToolRegistry.ALL_TOOL_IDS.forEach { id ->
                                    viewModel.setToolEnabled(id, true)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("authorize_all_tools_button")
                        ) {
                            Text("Authorize All", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = LocalNanoEmerald)
                        }
                        OutlinedButton(
                            onClick = {
                                GenUiToolRegistry.ALL_TOOL_IDS.forEach { id ->
                                    viewModel.setToolEnabled(id, false)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("revoke_all_tools_button")
                        ) {
                            Text("Revoke All", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFCA5A5))
                        }
                    }
                }
            }

            // 2. Dedicated Device Calendar Access Section (Grant/Revoke Android Calendar Provider + AI Event Access)
            item(key = "device_calendar_access_section") {
                DeviceCalendarAccessSection(
                    osCalendarGranted = toolPermissions.calendarGranted,
                    aiCalendarToolEnabled = studioPrefs.toolCalendarEnabled,
                    syncedEventCount = calendarEvents.count { it.syncedToSystemCalendar },
                    totalEventCount = calendarEvents.size,
                    onToggleAiCalendarTool = { enabled ->
                        viewModel.setToolEnabled(GenUiToolRegistry.TOOL_CALENDAR, enabled)
                    },
                    onGrantCalendarPermission = {
                        calendarPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR
                            )
                        )
                    },
                    onRevokeInOsSettings = {
                        viewModel.setToolEnabled(GenUiToolRegistry.TOOL_CALENDAR, false)
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onSyncNow = { viewModel.syncDeviceCalendarNow() }
                )
            }

            // 3. Granular Per-Tool Switches (AlarmManager, Countdown Timer, Stopwatch, Calendar, GenUI)
            item(key = "settings_tools_registry_card") {
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

            // 4. Android OS Permission Inspector & Grant Controls
            item(key = "settings_android_permissions_card") {
                ToolPermissionsManagerCard(
                    permissions = toolPermissions,
                    onRefreshPermissions = { viewModel.refreshToolPermissions() }
                )
            }

            // 5. Persisted Tool Execution History & Clear Button
            item(key = "settings_tool_audit_log_card") {
                ToolExecutionAuditCard(
                    entries = toolAuditLog,
                    onClear = { viewModel.clearToolAuditLog() }
                )
            }
        }
    }
}

@Composable
private fun DeviceCalendarAccessSection(
    osCalendarGranted: Boolean,
    aiCalendarToolEnabled: Boolean,
    syncedEventCount: Int,
    totalEventCount: Int,
    onToggleAiCalendarTool: (Boolean) -> Unit,
    onGrantCalendarPermission: () -> Unit,
    onRevokeInOsSettings: () -> Unit,
    onSyncNow: () -> Unit
) {
    val fullyAuthorized = osCalendarGranted && aiCalendarToolEnabled
    val borderColor = when {
        fullyAuthorized -> LocalNanoEmerald.copy(alpha = 0.45f)
        aiCalendarToolEnabled -> CloudFlashCobalt.copy(alpha = 0.3f)
        else -> Color(0xFFFCA5A5)
    }

    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.2.dp, borderColor, RoundedCornerShape(14.dp))
            .testTag("device_calendar_access_section")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        .background(if (fullyAuthorized) Color(0xFFDCFCE7) else Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = if (fullyAuthorized) LocalNanoEmerald else CloudFlashCobalt,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DEVICE CALENDAR ACCESS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (fullyAuthorized) LocalNanoEmerald else CloudFlashCobalt
                    )
                    Text(
                        text = "Android Calendar Provider API",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            fullyAuthorized -> Color(0xFFDCFCE7)
                            osCalendarGranted -> Color(0xFFFEF3C7)
                            else -> Color(0xFFFEE2E2)
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = when {
                        fullyAuthorized -> "GRANTED"
                        osCalendarGranted -> "AI PAUSED"
                        else -> "REVOKED"
                    },
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        fullyAuthorized -> Color(0xFF15803D)
                        osCalendarGranted -> Color(0xFFB45309)
                        else -> Color(0xFFB91C1C)
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (osCalendarGranted) {
                "Android Calendar read/write access is granted ($syncedEventCount synced / $totalEventCount total events). Toggle below to enable or block AI event creation, edits, and deletions."
            } else {
                "Grant Android Calendar access so the AI Calendar Tool can directly list, create, update, and delete events in your device's Calendar Provider."
            },
            fontSize = 11.5.sp,
            color = SlateMuted,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(10.dp))

        // Explicit AI Calendar Tool Authorization Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IosCanvas)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Allow AI to Create & Modify Events",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MoteNavy
                )
                Text(
                    text = if (aiCalendarToolEnabled) "AI can list, add, update & delete events" else "Blocked — AI cannot modify your calendar",
                    fontSize = 11.sp,
                    color = if (aiCalendarToolEnabled) LocalNanoEmerald else Color(0xFFB91C1C)
                )
            }
            IosSpringSwitch(
                checked = aiCalendarToolEnabled,
                onCheckedChange = onToggleAiCalendarTool,
                modifier = Modifier.testTag("calendar_ai_access_switch")
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!osCalendarGranted) {
                Button(
                    onClick = onGrantCalendarPermission,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalMoteColors.current.primaryActionBg),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("grant_device_calendar_access_button")
                ) {
                    Text("Grant Calendar Access", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            } else {
                OutlinedButton(
                    onClick = onSyncNow,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sync_device_calendar_button")
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp), tint = CloudFlashCobalt)
                    Spacer(Modifier.width(5.dp))
                    Text("Sync Events", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
                }
                OutlinedButton(
                    onClick = onRevokeInOsSettings,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("revoke_device_calendar_access_button")
                ) {
                    Text("Revoke Access", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB91C1C))
                }
            }
        }
    }
}

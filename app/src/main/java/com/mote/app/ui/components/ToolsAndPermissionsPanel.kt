package com.mote.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mote.app.service.MediaProjectionConsentBridge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mote.app.ai.GenUiToolRegistry
import com.mote.app.ai.RegisteredToolDescriptor
import com.mote.app.ai.ToolExecutionAuditEntry
import com.mote.app.control.ToolPermissionsSnapshot
import com.mote.app.data.db.ModelStudioPrefsEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive Model Tools Control Center:
 * Lets users inspect every AI-callable tool in [GenUiToolRegistry], see its exact operations
 * (addAlarm, updateAlarm, deleteAlarm, startTimer, getTimerStatus, startStopwatch, etc.),
 * and enable/disable model access per tool (persisted in Room).
 */
@Composable
fun ModelToolsRegistryCard(
    tools: List<RegisteredToolDescriptor>,
    prefs: ModelStudioPrefsEntity,
    permissions: ToolPermissionsSnapshot,
    activeAlarmCount: Int,
    activeTimerCount: Int,
    stopwatchRunning: Boolean,
    activeCalendarCount: Int = 0,
    onToggleTool: (toolId: String, enabled: Boolean) -> Unit
) {
    val enabledCount = tools.count { isToolEnabled(it.id, prefs) }

    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.dp, CloudFlashCobalt.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .testTag("model_tools_registry_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Extension, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(18.dp))
                Column {
                    Text("MODEL TOOLS REGISTRY", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                    Text(
                        text = "$enabledCount of ${tools.size} AI tools active in chat streaming",
                        fontSize = 11.sp,
                        color = SlateMuted
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabledCount > 0) Color(0xFFDCFCE7) else Color(0xFFF1F5F9))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (enabledCount > 0) "LIVE" else "PAUSED",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabledCount > 0) Color(0xFF15803D) else SlateMuted
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tools.forEach { tool ->
                val enabled = isToolEnabled(tool.id, prefs)
                val (icon, accent, liveBadge) = when (tool.id) {
                    GenUiToolRegistry.TOOL_ALARM_MANAGER -> Triple(
                        Icons.Outlined.Alarm,
                        CloudFlashCobalt,
                        if (permissions.exactAlarmsGranted) {
                            "$activeAlarmCount active · Exact"
                        } else {
                            "$activeAlarmCount active · Inexact"
                        }
                    )
                    GenUiToolRegistry.TOOL_COUNTDOWN_TIMER -> Triple(
                        Icons.Outlined.Timer,
                        LocalNanoEmerald,
                        "$activeTimerCount running"
                    )
                    GenUiToolRegistry.TOOL_STOPWATCH -> Triple(
                        Icons.Outlined.AvTimer,
                        Color(0xFF0284C7),
                        if (stopwatchRunning) "Running" else "Standby"
                    )
                    GenUiToolRegistry.TOOL_CALENDAR -> Triple(
                        Icons.Outlined.CalendarMonth,
                        Color(0xFFD97706),
                        if (permissions.calendarGranted) "$activeCalendarCount events · Synced" else "$activeCalendarCount events"
                    )
                    GenUiToolRegistry.TOOL_CAMERA -> Triple(
                        Icons.Outlined.PhotoCamera,
                        Color(0xFFEC4899),
                        if (permissions.cameraGranted) "Camera Ready" else "Intent Ready"
                    )
                    GenUiToolRegistry.TOOL_SCREEN_CAPTURE -> Triple(
                        Icons.Outlined.Screenshot,
                        Color(0xFF0284C7),
                        if (permissions.mediaProjectionGranted) "MediaProjection Ready" else "Ask Consent"
                    )
                    GenUiToolRegistry.TOOL_APP_LAUNCHER -> Triple(
                        Icons.Outlined.Apps,
                        CloudFlashCobalt,
                        if (permissions.usageStatsGranted) "UsageStats + Apps" else "Common Apps"
                    )
                    GenUiToolRegistry.TOOL_NAVIGATION -> Triple(
                        Icons.Outlined.Navigation,
                        LocalNanoEmerald,
                        if (permissions.accessibilityGranted) "System + Maps" else "Maps + Settings"
                    )
                    else -> Triple(
                        Icons.Outlined.DashboardCustomize,
                        Color(0xFF7C3AED),
                        "Inline UI"
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled) Color(0xFFF8FAFC) else Color(0xFFF1F5F9).copy(alpha = 0.65f))
                        .border(
                            width = 1.dp,
                            color = if (enabled) accent.copy(alpha = 0.25f) else Color(0x120F172A),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(11.dp)
                        .testTag("tool_control_row_${tool.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = if (enabled) 0.14f else 0.06f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (enabled) accent else SlateMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = tool.displayName,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (enabled) MoteNavy else SlateMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color.White)
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    ) {
                                        Text(
                                            text = liveBadge,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = accent
                                        )
                                    }
                                }
                                Text(
                                    text = tool.description,
                                    fontSize = 11.sp,
                                    color = SlateMuted,
                                    lineHeight = 14.5.sp
                                )
                            }
                        }
                        IosSpringSwitch(
                            checked = enabled,
                            onCheckedChange = { onToggleTool(tool.id, it) },
                            modifier = Modifier.testTag("tool_switch_${tool.id}")
                        )
                    }

                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        tool.operations.forEach { op ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White)
                                    .border(0.75.dp, Color(0x160F172A), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$op()",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (enabled) MoteNavy else SlateMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Android Permissions Control Center for AI Model Tools:
 * Audits real Android OS permission states (Exact Alarms, Notifications, Microphone,
 * Write Settings, Do Not Disturb, Overlay) and provides direct 1-tap grant flows.
 */
@Composable
fun ToolPermissionsManagerCard(
    permissions: ToolPermissionsSnapshot,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-refresh permission badges whenever user returns from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onRefreshPermissions()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onRefreshPermissions()
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onRefreshPermissions()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onRefreshPermissions()
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        MediaProjectionConsentBridge.storeConsent(result.resultCode, result.data)
        onRefreshPermissions()
        val msg = if (MediaProjectionConsentBridge.hasCachedConsent()) {
            "MediaProjection screen capture authorized."
        } else {
            "Screen capture authorization declined."
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.dp, Color(0x140F172A), RoundedCornerShape(14.dp))
            .testTag("tool_permissions_manager_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(19.dp))
                Column {
                    Text("ANDROID TOOL PERMISSIONS", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                    Text(
                        text = "${permissions.grantedCount}/${permissions.totalCount} capabilities authorized on device",
                        fontSize = 11.sp,
                        color = SlateMuted
                    )
                }
            }
            IconButton(
                onClick = onRefreshPermissions,
                modifier = Modifier.size(38.dp).testTag("refresh_permissions_button")
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh permissions", tint = SlateMuted, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionRow(
                icon = Icons.Outlined.AlarmOn,
                title = "Exact Alarms (AlarmManager)",
                subtitle = "Lets addAlarm & updateAlarm ring at exact minute",
                granted = permissions.exactAlarmsGranted,
                actionLabel = if (permissions.exactAlarmsGranted) "Manage" else "Allow",
                tag = "perm_exact_alarms_button",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSettingsIntent(
                            context,
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    } else {
                        Toast.makeText(context, "Exact alarms are automatically enabled on this Android version.", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.CalendarMonth,
                title = "Calendar (Read & Write)",
                subtitle = "Lets addEvent & listEvents sync directly with Android Calendar",
                granted = permissions.calendarGranted,
                actionLabel = if (permissions.calendarGranted) "Settings" else "Grant",
                tag = "perm_calendar_button",
                onClick = {
                    if (!permissions.calendarGranted) {
                        calendarPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR
                            )
                        )
                    } else {
                        openAppDetailsSettings(context)
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.PhotoCamera,
                title = "Camera & Hardware Torch",
                subtitle = "Powers real-time AI Camera launch and Flashlight control",
                granted = permissions.cameraGranted,
                actionLabel = if (permissions.cameraGranted) "Settings" else "Grant",
                tag = "perm_camera_button",
                onClick = {
                    if (!permissions.cameraGranted) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        openAppDetailsSettings(context)
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.Screenshot,
                title = "Screen Capture (MediaProjection)",
                subtitle = "Explicitly authorizes live MediaProjection screenshots for visual AI analysis",
                granted = permissions.mediaProjectionGranted,
                actionLabel = if (permissions.mediaProjectionGranted) "Revoke" else "Authorize",
                tag = "perm_media_projection_button",
                onClick = {
                    if (permissions.mediaProjectionGranted) {
                        MediaProjectionConsentBridge.clearConsent()
                        onRefreshPermissions()
                    } else {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (mpm != null) {
                            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                        } else {
                            Toast.makeText(context, "MediaProjection service unavailable on this device.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.Apps,
                title = "App Usage Access (UsageStats)",
                subtitle = "Lets App Navigation Tool inspect recent apps & prioritize Chrome, YouTube, etc.",
                granted = permissions.usageStatsGranted,
                actionLabel = if (permissions.usageStatsGranted) "Manage" else "Allow",
                tag = "perm_usage_stats_button",
                onClick = {
                    openSettingsIntent(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )

            PermissionRow(
                icon = Icons.Outlined.AccessibilityNew,
                title = "Accessibility Navigation Service",
                subtitle = "Enables global Back/Home/Recents & redacted screen text reading",
                granted = permissions.accessibilityGranted,
                actionLabel = if (permissions.accessibilityGranted) "Manage" else "Enable",
                tag = "perm_accessibility_button",
                onClick = {
                    openSettingsIntent(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionRow(
                icon = Icons.Outlined.NotificationsActive,
                title = "Alarm & Timer Notifications",
                subtitle = "Displays high-priority alerts when alarms fire",
                granted = permissions.notificationsGranted,
                actionLabel = if (permissions.notificationsGranted) "Settings" else "Grant",
                tag = "perm_notifications_button",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissions.notificationsGranted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.Mic,
                title = "Microphone (Voice Input)",
                subtitle = "Powers Speech-to-Text in the Floating Assistant",
                granted = permissions.microphoneGranted,
                actionLabel = if (permissions.microphoneGranted) "Settings" else "Grant",
                tag = "perm_microphone_button",
                onClick = {
                    if (!permissions.microphoneGranted) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        openAppDetailsSettings(context)
                    }
                }
            )

            PermissionRow(
                icon = Icons.Outlined.Brightness6,
                title = "Modify System Settings",
                subtitle = "Allows Generative UI sliders to adjust screen brightness",
                granted = permissions.writeSettingsGranted,
                actionLabel = if (permissions.writeSettingsGranted) "Manage" else "Allow",
                tag = "perm_write_settings_button",
                onClick = {
                    openSettingsIntent(
                        context,
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            )

            PermissionRow(
                icon = Icons.Outlined.DoNotDisturbOn,
                title = "Do Not Disturb Access",
                subtitle = "Allows Focus mode GenUI toggle to silence alerts",
                granted = permissions.dndPolicyGranted,
                actionLabel = if (permissions.dndPolicyGranted) "Manage" else "Allow",
                tag = "perm_dnd_button",
                onClick = {
                    openSettingsIntent(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            )

            PermissionRow(
                icon = Icons.Outlined.Layers,
                title = "Display Over Other Apps",
                subtitle = "Enables the system-wide Floating Assistant Orb",
                granted = permissions.overlayGranted,
                actionLabel = if (permissions.overlayGranted) "Manage" else "Allow",
                tag = "perm_overlay_button",
                onClick = {
                    openSettingsIntent(
                        context,
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            )
        }
    }
}

/**
 * Live audit log of recent AI tool invocations (addAlarm, updateAlarm, deleteAlarm,
 * startTimer, startStopwatch, render_gen_ui) with real-time success/blocked badges.
 */
@Composable
fun ToolExecutionAuditCard(
    entries: List<ToolExecutionAuditEntry>,
    onClear: () -> Unit
) {
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    MoteCard(
        background = Color.White,
        modifier = Modifier
            .border(1.dp, Color(0x140F172A), RoundedCornerShape(14.dp))
            .testTag("tool_execution_audit_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(18.dp))
                Text("LIVE TOOL ACTIVITY (${entries.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
            }
            if (entries.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("Clear", fontSize = 11.5.sp, color = SlateMuted)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text = "No AI tool calls recorded yet. Ask the AI in Chat Hub to add an alarm, start a timer, or start the stopwatch.",
                fontSize = 11.5.sp,
                color = SlateMuted,
                lineHeight = 16.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.take(8).forEach { item ->
                    val badgeBg = when {
                        item.blocked -> Color(0xFFFEF3C7)
                        item.success -> Color(0xFFDCFCE7)
                        else -> Color(0xFFFEE2E2)
                    }
                    val badgeFg = when {
                        item.blocked -> Color(0xFFB45309)
                        item.success -> Color(0xFF15803D)
                        else -> Color(0xFFB91C1C)
                    }
                    val badgeLabel = when {
                        item.blocked -> "BLOCKED"
                        item.success -> "SUCCESS"
                        else -> "FAILED"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${item.operation}()",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy
                                )
                                Text(
                                    text = timeFmt.format(Date(item.timestampMs)),
                                    fontSize = 10.sp,
                                    color = SlateMuted
                                )
                            }
                            Text(
                                text = item.feedback,
                                fontSize = 11.sp,
                                color = Color(0xFF475569),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(badgeBg)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(badgeLabel, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = badgeFg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) LocalNanoEmerald else Color(0xFFD97706),
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (granted) Color(0xFFDCFCE7) else Color(0xFFFEF3C7))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (granted) "Granted" else "Needed",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (granted) Color(0xFF15803D) else Color(0xFFB45309)
                        )
                    }
                }
                Text(subtitle, fontSize = 10.5.sp, color = SlateMuted, lineHeight = 14.sp)
            }
        }
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp).testTag(tag)
        ) {
            Text(
                text = actionLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (granted) SlateMuted else CloudFlashCobalt
            )
        }
    }
}

private fun isToolEnabled(toolId: String, prefs: ModelStudioPrefsEntity): Boolean = when (toolId) {
    GenUiToolRegistry.TOOL_ALARM_MANAGER -> prefs.toolAlarmEnabled
    GenUiToolRegistry.TOOL_COUNTDOWN_TIMER -> prefs.toolTimerEnabled
    GenUiToolRegistry.TOOL_STOPWATCH -> prefs.toolStopwatchEnabled
    GenUiToolRegistry.TOOL_CALENDAR -> prefs.toolCalendarEnabled
    GenUiToolRegistry.TOOL_CAMERA -> prefs.toolCameraEnabled
    GenUiToolRegistry.TOOL_SCREEN_CAPTURE -> prefs.toolScreenCaptureEnabled
    GenUiToolRegistry.TOOL_APP_LAUNCHER -> prefs.toolAppLauncherEnabled
    GenUiToolRegistry.TOOL_NAVIGATION -> prefs.toolNavigationEnabled
    GenUiToolRegistry.TOOL_GEN_UI_WIDGETS -> prefs.toolGenUiEnabled
    else -> true
}

private fun openSettingsIntent(context: Context, intent: Intent) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    openSettingsIntent(context, intent)
}

private fun openAppDetailsSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Open Android Settings to manage app permissions.", Toast.LENGTH_SHORT).show()
    }
}

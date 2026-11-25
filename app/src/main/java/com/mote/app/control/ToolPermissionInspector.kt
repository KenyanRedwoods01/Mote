package com.mote.app.control

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mote.app.service.MediaProjectionConsentBridge
import com.mote.app.service.MoteAccessibilityService

data class ToolPermissionsSnapshot(
    val exactAlarmsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val mediaProjectionGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val dndPolicyGranted: Boolean = false,
    val overlayGranted: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(
            exactAlarmsGranted,
            calendarGranted,
            cameraGranted,
            mediaProjectionGranted,
            usageStatsGranted,
            accessibilityGranted,
            notificationsGranted,
            microphoneGranted,
            writeSettingsGranted,
            dndPolicyGranted,
            overlayGranted
        ).count { it }

    val totalCount: Int = 11
}

/**
 * Inspects real Android OS permission states required by Mote's model tools
 * (AlarmManager exact alarms, Calendar, Camera, MediaProjection consent, UsageStats,
 * Accessibility navigation/screenshots, Notifications, Microphone STT, Write Settings, DND, Overlay).
 */
object ToolPermissionInspector {

    fun hasUsageStatsAccess(context: Context): Boolean {
        val appCtx = context.applicationContext
        return try {
            val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appCtx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appCtx.packageName
                )
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                ContextCompat.checkSelfPermission(
                    appCtx,
                    Manifest.permission.PACKAGE_USAGE_STATS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        } catch (_: Exception) {
            false
        }
    }

    fun inspect(context: Context): ToolPermissionsSnapshot {
        val appCtx = context.applicationContext

        val exactAlarms = try {
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.canScheduleExactAlarms() == true
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }

        val calendar = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

        val camera = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val mediaProjection = MediaProjectionConsentBridge.hasCachedConsent()
        val usageStats = hasUsageStatsAccess(appCtx)
        val accessibility = MoteAccessibilityService.instance != null

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val microphone = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val writeSettings = try {
            Settings.System.canWrite(appCtx)
        } catch (_: Exception) {
            false
        }

        val dndPolicy = try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        } catch (_: Exception) {
            false
        }

        val overlay = try {
            Settings.canDrawOverlays(appCtx)
        } catch (_: Exception) {
            false
        }

        return ToolPermissionsSnapshot(
            exactAlarmsGranted = exactAlarms,
            calendarGranted = calendar,
            cameraGranted = camera,
            mediaProjectionGranted = mediaProjection,
            usageStatsGranted = usageStats,
            accessibilityGranted = accessibility,
            notificationsGranted = notifications,
            microphoneGranted = microphone,
            writeSettingsGranted = writeSettings,
            dndPolicyGranted = dndPolicy,
            overlayGranted = overlay
        )
    }
}

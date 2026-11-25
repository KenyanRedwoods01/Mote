package com.mote.app.control

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mote.app.MainActivity
import com.mote.app.R

/**
 * Receives alarms scheduled via Android's AlarmManager from Mote's Alarm Tool interface.
 * Vibrates and posts a high-priority alarm notification if notifications are permitted.
 */
class MoteAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: "mote_alarm"
        val label = intent.getStringExtra(EXTRA_ALARM_LABEL)?.ifBlank { "Mote Alarm" } ?: "Mote Alarm"
        val formattedTime = intent.getStringExtra(EXTRA_ALARM_TIME).orEmpty()

        triggerVibration(context)
        postAlarmNotification(context, alarmId, label, formattedTime)
    }

    private fun triggerVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 450), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500L)
            }
        } catch (_: Exception) {
            // Device may not expose vibrator hardware
        }
    }

    private fun postAlarmNotification(
        context: Context,
        alarmId: String,
        label: String,
        formattedTime: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mote Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts triggered by AI-scheduled alarms via AlarmManager."
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            alarmId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (formattedTime.isNotBlank()) {
            "$label · Scheduled for $formattedTime"
        } else {
            label
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .setContentTitle("Mote Alarm")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        nm.notify(alarmId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "mote_alarms_channel"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_ALARM_TIME = "extra_alarm_time"
    }
}

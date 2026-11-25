package com.mote.app.control

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import com.mote.app.ai.MoteAction
import com.mote.app.service.MoteAccessibilityService

class DeviceActionExecutor(private val context: Context) {

    fun execute(action: MoteAction): Boolean = when (action) {
        is MoteAction.SetBrightness -> setBrightness(action.percent)
        is MoteAction.ToggleDnd -> setDnd(action.enabled)
        is MoteAction.StartTimer -> startTimer(action.minutes, action.label)
        is MoteAction.CopyText -> copyToClipboard(action.text)
        is MoteAction.PasteReply -> MoteAccessibilityService.instance?.pasteIntoFocusedInput(action.text) ?: false
        is MoteAction.ConfirmSendMessage -> false // Requires explicit Tier-3 Biometric UI gate first
    }

    private fun setBrightness(percent: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val value = (percent.coerceIn(5, 100) * 255) / 100
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }.isSuccess
    }

    private fun setDnd(enabled: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return false
        val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
        return true
    }

    private fun startTimer(minutes: Int, label: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes.coerceIn(1, 180) * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun copyToClipboard(text: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mote", text))
        return true
    }
}

package com.mote.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mote.app.security.PrivacyGuard
import java.util.Locale

class MoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Zero idle CPU: only track foreground package to auto-hide bots on sensitive apps
        val pkg = event?.packageName?.toString()
        currentPackageName = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Reads pruned, PII-redacted text on-demand when the user taps a floating Mote bot
     * or asks the AI to inspect the current screen.
     */
    fun captureRedactedScreenSummary(maxChars: Int = 1800): String? {
        if (PrivacyGuard.isPackageBlocked(currentPackageName)) return null
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        traverseSafe(root, sb, maxChars)
        return PrivacyGuard.redactPii(sb.toString())
    }

    private fun traverseSafe(node: AccessibilityNodeInfo?, out: StringBuilder, limit: Int) {
        if (node == null || out.length >= limit) return
        if (node.isPassword) return // Never inspect password inputs
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) {
            out.append(txt).append(" | ")
        }
        for (i in 0 until node.childCount) {
            if (out.length >= limit) break
            traverseSafe(node.getChild(i), out, limit)
        }
    }

    fun pasteIntoFocusedInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (focused.isPassword) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Executes real Android system navigation global actions (back, home, recents, notifications,
     * quick settings, lock screen, power dialog, split screen, or system screenshot).
     */
    fun performNavigationAction(action: String): Boolean {
        val normalized = action.trim().lowercase(Locale.US)
        val globalAction = when (normalized) {
            "back", "go_back" -> GLOBAL_ACTION_BACK
            "home", "go_home" -> GLOBAL_ACTION_HOME
            "recents", "overview", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power", "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "split_screen", "split" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock", "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot", "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return try {
            performGlobalAction(globalAction)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Captures a full-device screenshot bitmap via Android 11+ AccessibilityService.takeScreenshot.
     */
    fun captureScreenshotBitmap(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            val softwareCopy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            onResult(softwareCopy)
                        } catch (_: Exception) {
                            onResult(null)
                        } finally {
                            hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null)
                    }
                }
            )
        } catch (_: Exception) {
            onResult(null)
        }
    }

    companion object {
        @Volatile
        var instance: MoteAccessibilityService? = null
            private set

        @Volatile
        var currentPackageName: String? = null
            private set
    }
}

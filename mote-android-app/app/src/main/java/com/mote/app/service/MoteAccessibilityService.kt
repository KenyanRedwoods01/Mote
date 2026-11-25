package com.mote.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mote.app.security.PrivacyGuard

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
     * Reads pruned, PII-redacted text on-demand when the user taps a floating Mote bot.
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

    companion object {
        @Volatile
        var instance: MoteAccessibilityService? = null
            private set

        @Volatile
        var currentPackageName: String? = null
            private set
    }
}

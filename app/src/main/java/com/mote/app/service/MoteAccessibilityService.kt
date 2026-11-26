package com.mote.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mote.app.security.PrivacyGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

data class ScreenUiNodeInfo(
    val label: String,
    val viewId: String?,
    val className: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean
)

data class AppControlActionResult(
    val success: Boolean,
    val summary: String,
    val detail: String,
    val targetX: Float? = null,
    val targetY: Float? = null,
    val matchedLabel: String? = null
)

class MoteAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isShuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private var floatingWindowManager: AccessibilityFloatingWindowManager? = null
    private var pendingAttachRunnable: Runnable? = null
    @Volatile
    private var lastPublishedPackage: String? = null
    @Volatile
    private var lastPublishedBlocked: Boolean? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isShuttingDown.set(false)
        instance = this
        lastPublishedPackage = null
        lastPublishedBlocked = null
        runCatching { OverlayCustomizationStore.ensureLoaded(applicationContext) }
        pendingAttachRunnable?.let { mainHandler.removeCallbacks(it) }
        val attachTask = Runnable {
            if (isShuttingDown.get() || instance !== this) return@Runnable
            runCatching {
                val existing = floatingWindowManager
                val mgr = if (existing == null || existing.isDestroyed) {
                    AccessibilityFloatingWindowManager(
                        service = this,
                        screenContextProvider = { captureRedactedScreenSummary() }
                    ).also { floatingWindowManager = it }
                } else {
                    existing
                }
                mgr.attachAndShow()
            }
        }
        pendingAttachRunnable = attachTask
        // Brief delay ensures the AccessibilityService window token is fully bound before addView
        mainHandler.postDelayed(attachTask, 260L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isShuttingDown.get() || instance !== this) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val rawPkg = runCatching { event.packageName?.toString() }.getOrNull()
        val pkg = rawPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val blocked = runCatching { PrivacyGuard.isPackageBlocked(pkg) }.getOrDefault(false)
        if (pkg == lastPublishedPackage && blocked == lastPublishedBlocked) {
            return
        }
        lastPublishedPackage = pkg
        lastPublishedBlocked = blocked
        currentPackageName = pkg
        runCatching {
            floatingWindowManager?.setPrivacyHidden(blocked)
            SystemOverlayStateBus.publishForegroundPackage(pkg, blocked)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardownOverlayAndCursor()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownOverlayAndCursor()
        super.onDestroy()
    }

    private fun teardownOverlayAndCursor() {
        isShuttingDown.set(true)
        pendingAttachRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAttachRunnable = null
        lastPublishedPackage = null
        lastPublishedBlocked = null
        runCatching { SystemCursorOverlayManager.hideNow() }
        runCatching { floatingWindowManager?.detach() }
        floatingWindowManager = null
        if (instance === this) instance = null
    }

    fun showFloatingOverlay(): Boolean {
        if (isShuttingDown.get() || instance !== this) return false
        return runCatching {
            val existing = floatingWindowManager
            val mgr = if (existing == null || existing.isDestroyed) {
                AccessibilityFloatingWindowManager(
                    service = this,
                    screenContextProvider = { captureRedactedScreenSummary() }
                ).also { floatingWindowManager = it }
            } else {
                existing
            }
            mgr.attachAndShow()
        }.getOrDefault(false)
    }

    fun hideFloatingOverlay() {
        runCatching { floatingWindowManager?.hide() }
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

    /**
     * Enumerates visible interactive elements on the active window so the AI can
     * target real UI controls with the live floating cursor.
     */
    fun inspectInteractiveElements(maxItems: Int = 28): List<ScreenUiNodeInfo> {
        if (PrivacyGuard.isPackageBlocked(currentPackageName)) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<ScreenUiNodeInfo>()
        collectInteractiveNodes(root, results, maxItems)
        return results
    }

    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<ScreenUiNodeInfo>,
        maxItems: Int
    ) {
        if (node == null || out.size >= maxItems) return
        if (node.isPassword) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }
        val isInteractive = node.isClickable || node.isEditable || node.isCheckable
        if (isInteractive && rect.width() > 6 && rect.height() > 6 && (label.isNotEmpty() || !node.viewIdResourceName.isNullOrBlank())) {
            val cleanLabel = PrivacyGuard.redactPii(label.ifBlank {
                node.viewIdResourceName?.substringAfterLast('/') ?: "Control"
            })
            out += ScreenUiNodeInfo(
                label = cleanLabel.take(64),
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = rect,
                clickable = node.isClickable,
                editable = node.isEditable
            )
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxItems) break
            collectInteractiveNodes(node.getChild(i), out, maxItems)
        }
    }

    /**
     * Real-time cursor hover + click on any visible UI element matching [query] by text,
     * contentDescription, or resource ID.
     */
    suspend fun hoverAndClickNodeByText(
        query: String,
        hoverOnly: Boolean = false
    ): AppControlActionResult {
        if (PrivacyGuard.isPackageBlocked(currentPackageName)) {
            return AppControlActionResult(false, "Blocked by PrivacyGuard", "Cursor automation is paused on sensitive screens.")
        }
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty()) {
            return AppControlActionResult(false, "Missing target label", "Provide a button or element name to hover and click.")
        }
        val root = rootInActiveWindow
            ?: return AppControlActionResult(false, "No active app window", "Switch to an app screen before triggering cursor control.")

        val matched = findBestMatchingNode(root, listOf(cleanedQuery), emptyList())
            ?: return AppControlActionResult(
                success = false,
                summary = "Target '$cleanedQuery' not visible",
                detail = "Could not locate '$cleanedQuery' on ${currentPackageName ?: "the active screen"}."
            )

        val rect = Rect()
        matched.getBoundsInScreen(rect)
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val label = matched.text?.toString()?.takeIf { it.isNotBlank() }
            ?: matched.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: cleanedQuery

        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = cx,
            targetY = cy,
            label = if (hoverOnly) "Hover: $label" else "Click: $label",
            useAccessibilityLayer = true,
            triggerClickPulse = !hoverOnly
        )

        if (hoverOnly) {
            return AppControlActionResult(
                success = true,
                summary = "Cursor hovering over '$label'",
                detail = "Cursor positioned at (${cx.toInt()}, ${cy.toInt()}) on '$label'.",
                targetX = cx,
                targetY = cy,
                matchedLabel = label
            )
        }

        val clickedByNode = clickClickableAncestor(matched)
        val clicked = if (clickedByNode) true else dispatchTapGesture(cx, cy, longPress = false)
        return if (clicked) {
            AppControlActionResult(
                success = true,
                summary = "Hovered & clicked '$label'",
                detail = "Animated cursor hovered over '$label' (${cx.toInt()}, ${cy.toInt()}) and executed click.",
                targetX = cx,
                targetY = cy,
                matchedLabel = label
            )
        } else {
            AppControlActionResult(
                success = false,
                summary = "Click gesture rejected on '$label'",
                detail = "Cursor hovered over '$label' (${cx.toInt()}, ${cy.toInt()}) but the foreground window blocked click dispatch.",
                targetX = cx,
                targetY = cy,
                matchedLabel = label
            )
        }
    }

    /**
     * Specifically locates the Phone/Dialer Call button across stock Android, Pixel Dialer,
     * Samsung Phone, and AOSP dialers, visibly glides the real-time cursor onto it, and clicks it.
     */
    suspend fun hoverAndClickDialerCallButton(): AppControlActionResult {
        val root = rootInActiveWindow
            ?: return AppControlActionResult(false, "Dialer window not ready", "Open the Phone dialer first so Mote can click the Call button.")

        val preferredIdHints = listOf(
            "dialpad_floating_action_button",
            "floating_action_button",
            "call_button",
            "dialButton",
            "dial_button",
            "btn_call",
            "call_fab",
            "fab"
        )
        val preferredTextHints = listOf("dial", "call", "place call", "voice call", "audio call")

        val matched = findBestMatchingNode(root, preferredTextHints, preferredIdHints)
        if (matched != null) {
            val rect = Rect()
            matched.getBoundsInScreen(rect)
            if (rect.width() > 8 && rect.height() > 8) {
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                val label = matched.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: matched.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Call Button"

                SystemCursorOverlayManager.animateGlideTo(
                    context = this,
                    targetX = cx,
                    targetY = cy,
                    label = "Calling · $label",
                    useAccessibilityLayer = true,
                    triggerClickPulse = true
                )
                val ok = clickClickableAncestor(matched) || dispatchTapGesture(cx, cy, longPress = false)
                if (ok) {
                    return AppControlActionResult(
                        success = true,
                        summary = "Clicked Dialer Call button",
                        detail = "Live cursor hovered over '$label' (${cx.toInt()}, ${cy.toInt()}) and triggered the Call button.",
                        targetX = cx,
                        targetY = cy,
                        matchedLabel = label
                    )
                }
            }
        }

        // Fallback: stock Android dialer places the green Call FAB centered near bottom (50%, 88% height)
        val dm = resources.displayMetrics
        val fallbackX = dm.widthPixels * 0.5f
        val fallbackY = dm.heightPixels * 0.875f
        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = fallbackX,
            targetY = fallbackY,
            label = "Tap Call FAB",
            useAccessibilityLayer = true,
            triggerClickPulse = true
        )
        val tapped = dispatchTapGesture(fallbackX, fallbackY, longPress = false)
        return AppControlActionResult(
            success = tapped,
            summary = if (tapped) "Tapped Dialer Call FAB" else "Could not tap Call button",
            detail = if (tapped) {
                "Glided cursor to Dialer Call button area (${fallbackX.toInt()}, ${fallbackY.toInt()}) and dispatched tap."
            } else {
                "Dialer Call button could not be clicked automatically; tap the green Call button directly."
            },
            targetX = fallbackX,
            targetY = fallbackY,
            matchedLabel = "Call FAB"
        )
    }

    /**
     * Locates the active Camera app's shutter/capture button, hovers with the live cursor, and clicks it.
     */
    suspend fun hoverAndClickCameraShutter(): AppControlActionResult {
        val root = rootInActiveWindow
            ?: return AppControlActionResult(false, "Camera window not ready", "Open the Camera app first before pressing the shutter.")

        val idHints = listOf("shutter_button", "camera_shutter", "capture_button", "take_photo", "record_button", "shutter")
        val textHints = listOf("take photo", "shutter", "capture", "take picture", "record video", "start recording")
        val matched = findBestMatchingNode(root, textHints, idHints)
        if (matched != null) {
            val rect = Rect()
            matched.getBoundsInScreen(rect)
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val label = matched.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: "Camera Shutter"
            SystemCursorOverlayManager.animateGlideTo(
                context = this,
                targetX = cx,
                targetY = cy,
                label = "Capture · $label",
                useAccessibilityLayer = true,
                triggerClickPulse = true
            )
            val ok = clickClickableAncestor(matched) || dispatchTapGesture(cx, cy, longPress = false)
            return AppControlActionResult(
                success = ok,
                summary = if (ok) "Triggered Camera Shutter" else "Camera Shutter click blocked",
                detail = "Live cursor hovered on '$label' (${cx.toInt()}, ${cy.toInt()}).",
                targetX = cx,
                targetY = cy,
                matchedLabel = label
            )
        }

        val dm = resources.displayMetrics
        val fallbackX = dm.widthPixels * 0.5f
        val fallbackY = dm.heightPixels * 0.855f
        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = fallbackX,
            targetY = fallbackY,
            label = "Camera Shutter",
            useAccessibilityLayer = true,
            triggerClickPulse = true
        )
        val tapped = dispatchTapGesture(fallbackX, fallbackY, longPress = false)
        return AppControlActionResult(
            success = tapped,
            summary = if (tapped) "Tapped Camera Shutter" else "Could not tap shutter",
            detail = "Cursor glided to camera shutter zone (${fallbackX.toInt()}, ${fallbackY.toInt()}).",
            targetX = fallbackX,
            targetY = fallbackY,
            matchedLabel = "Camera Shutter"
        )
    }

    /**
     * Glides the real-time cursor to exact screen coordinates (px or normalized 0..1) and taps or hovers.
     */
    suspend fun hoverAndTapCoordinates(
        x: Float,
        y: Float,
        label: String = "Tap",
        longPress: Boolean = false,
        hoverOnly: Boolean = false
    ): AppControlActionResult {
        val dm = resources.displayMetrics
        val px = if (x in 0f..1f) x * dm.widthPixels else x.coerceIn(12f, (dm.widthPixels - 12).toFloat())
        val py = if (y in 0f..1f) y * dm.heightPixels else y.coerceIn(24f, (dm.heightPixels - 24).toFloat())

        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = px,
            targetY = py,
            label = label,
            useAccessibilityLayer = true,
            triggerClickPulse = !hoverOnly
        )
        if (hoverOnly) {
            return AppControlActionResult(
                success = true,
                summary = "Cursor hovered at (${px.toInt()}, ${py.toInt()})",
                detail = "Visual cursor hovered on '$label'.",
                targetX = px,
                targetY = py,
                matchedLabel = label
            )
        }
        val ok = dispatchTapGesture(px, py, longPress = longPress)
        return AppControlActionResult(
            success = ok,
            summary = if (ok) "${if (longPress) "Long-pressed" else "Tapped"} $label" else "Gesture tap failed",
            detail = "Cursor glided to (${px.toInt()}, ${py.toInt()}) and executed ${if (longPress) "long-press" else "tap"}.",
            targetX = px,
            targetY = py,
            matchedLabel = label
        )
    }

    /**
     * Performs a real directional swipe/scroll gesture with live cursor visual tracking.
     */
    suspend fun hoverAndSwipe(direction: String): AppControlActionResult {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val norm = direction.trim().lowercase(Locale.US)
        val (sx, sy, ex, ey, label) = when {
            norm.contains("down") -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, "Scroll Down")
            norm.contains("up") -> listOf(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.75f, "Scroll Up")
            norm.contains("left") -> listOf(w * 0.80f, h * 0.5f, w * 0.20f, h * 0.5f, "Swipe Left")
            norm.contains("right") -> listOf(w * 0.20f, h * 0.5f, w * 0.80f, h * 0.5f, "Swipe Right")
            else -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, "Scroll Down")
        }
        val startX = sx as Float
        val startY = sy as Float
        val endX = ex as Float
        val endY = ey as Float
        val title = label as String

        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = startX,
            targetY = startY,
            label = title,
            useAccessibilityLayer = true,
            triggerClickPulse = false
        )
        val swiped = dispatchSwipeGesture(startX, startY, endX, endY, durationMs = 300L)
        SystemCursorOverlayManager.showHoverAndWait(
            context = this,
            targetX = endX,
            targetY = endY,
            label = "$title Done",
            useAccessibilityLayer = true,
            autoHideMs = 1200L
        )
        return AppControlActionResult(
            success = swiped,
            summary = if (swiped) "$title executed" else "$title failed",
            detail = "Swiped from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()}).",
            targetX = endX,
            targetY = endY,
            matchedLabel = title
        )
    }

    /**
     * Hovers onto an editable field (matching [targetFieldQuery] or current focus) and injects text.
     */
    suspend fun hoverAndTypeText(targetFieldQuery: String?, text: String): AppControlActionResult {
        if (PrivacyGuard.isPackageBlocked(currentPackageName)) {
            return AppControlActionResult(false, "Blocked by PrivacyGuard", "Text entry is paused on sensitive screens.")
        }
        val root = rootInActiveWindow
            ?: return AppControlActionResult(false, "No active window", "Open an app input field before typing.")

        var targetNode: AccessibilityNodeInfo? = null
        if (!targetFieldQuery.isNullOrBlank()) {
            targetNode = findBestMatchingNode(root, listOf(targetFieldQuery), emptyList())
        }
        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
        }
        if (targetNode == null || targetNode.isPassword) {
            return AppControlActionResult(false, "No editable field found", "Focus an input field or specify its label.")
        }
        val rect = Rect()
        targetNode.getBoundsInScreen(rect)
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        SystemCursorOverlayManager.animateGlideTo(
            context = this,
            targetX = cx,
            targetY = cy,
            label = "Type: ${text.take(18)}",
            useAccessibilityLayer = true,
            triggerClickPulse = true
        )
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val applied = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return AppControlActionResult(
            success = applied,
            summary = if (applied) "Typed text into active field" else "Could not type into field",
            detail = if (applied) "Cursor hovered (${cx.toInt()}, ${cy.toInt()}) and entered '${text.take(40)}'." else "Field rejected ACTION_SET_TEXT.",
            targetX = cx,
            targetY = cy,
            matchedLabel = targetFieldQuery ?: "Input Field"
        )
    }

    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo,
        textHints: List<String>,
        idHints: List<String>
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        val lowerTextHints = textHints.map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }
        val lowerIdHints = idHints.map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || node.isPassword) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 4 && rect.height() > 4) {
                val txt = node.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty()
                val viewId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()
                var score = 0
                for (hint in lowerIdHints) {
                    if (viewId.endsWith("/$hint") || viewId == hint) score = maxOf(score, 120)
                    else if (viewId.contains(hint)) score = maxOf(score, 95)
                }
                for (hint in lowerTextHints) {
                    if (txt == hint || desc == hint) score = maxOf(score, 110)
                    else if (txt.contains(hint) || desc.contains(hint)) score = maxOf(score, 80)
                }
                if (score > 0 && node.isClickable) score += 15
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }
        visit(root)
        return bestNode
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || node.isPassword) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun clickClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float, longPress: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) 580L else 65L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
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

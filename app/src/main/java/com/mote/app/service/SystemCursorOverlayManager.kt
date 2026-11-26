package com.mote.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Renders a real-time floating AI cursor pointer with hover halo, target tooltip pill,
 * and click ripple above any foreground app while Mote controls the screen.
 *
 * Uses FLAG_NOT_TOUCHABLE so the visual cursor never blocks underlying touches or
 * AccessibilityService.dispatchGesture() synthetic taps.
 */
object SystemCursorOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: LiveCursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeAnimator: ValueAnimator? = null
    private var hideRunnable: Runnable? = null

    private var currentX: Float = 240f
    private var currentY: Float = 420f

    fun showHoverAndWait(
        context: Context,
        targetX: Float,
        targetY: Float,
        label: String,
        useAccessibilityLayer: Boolean = false,
        autoHideMs: Long = 2200L
    ) {
        OverlayCustomizationStore.ensureLoaded(context)
        val cfg = OverlayCustomizationStore.config.value
        if (!cfg.showCursorHover) return
        mainHandler.post {
            showInternal(
                context = context,
                targetX = targetX,
                targetY = targetY,
                label = label,
                useAccessibilityLayer = useAccessibilityLayer,
                glideMs = cfg.cursorGlideMs,
                accentColor = cfg.cursorAccentHex.toInt(),
                autoHideMs = autoHideMs
            )
        }
    }

    suspend fun animateGlideTo(
        context: Context,
        targetX: Float,
        targetY: Float,
        label: String,
        useAccessibilityLayer: Boolean = false,
        triggerClickPulse: Boolean = true,
        autoHideMs: Long = 2200L
    ) {
        OverlayCustomizationStore.ensureLoaded(context)
        val cfg = OverlayCustomizationStore.config.value
        if (!cfg.showCursorHover) return

        suspendCancellableCoroutine<Unit> { cont ->
            mainHandler.post {
                val attached = showInternal(
                    context = context,
                    targetX = targetX,
                    targetY = targetY,
                    label = label,
                    useAccessibilityLayer = useAccessibilityLayer,
                    glideMs = cfg.cursorGlideMs,
                    accentColor = cfg.cursorAccentHex.toInt(),
                    autoHideMs = autoHideMs,
                    onArrive = {
                        if (triggerClickPulse) {
                            cursorView?.triggerClickRipple()
                        }
                        if (cont.isActive) cont.resume(Unit)
                    }
                )
                if (!attached && cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
    }

    fun triggerClickPulse() {
        mainHandler.post {
            cursorView?.triggerClickRipple()
        }
    }

    fun hideNow() {
        mainHandler.post {
            hideRunnable?.let { mainHandler.removeCallbacks(it) }
            hideRunnable = null
            activeAnimator?.cancel()
            activeAnimator = null
            val wm = windowManager
            val view = cursorView
            if (wm != null && view != null) {
                runCatching { wm.removeViewImmediate(view) }
            }
            cursorView = null
            layoutParams = null
            SystemOverlayStateBus.publishCursorStatus(null)
        }
    }

    private fun showInternal(
        context: Context,
        targetX: Float,
        targetY: Float,
        label: String,
        useAccessibilityLayer: Boolean,
        glideMs: Long,
        accentColor: Int,
        autoHideMs: Long,
        onArrive: (() -> Unit)? = null
    ): Boolean {
        val canUseAccessibility = useAccessibilityLayer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
        val canUseAppOverlay = Settings.canDrawOverlays(context)
        if (!canUseAccessibility && !canUseAppOverlay) {
            return false
        }

        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return false
        windowManager = wm

        val dm = context.resources.displayMetrics
        val clampedX = targetX.coerceIn(24f, (dm.widthPixels - 24).toFloat())
        val clampedY = targetY.coerceIn(48f, (dm.heightPixels - 48).toFloat())

        if (cursorView == null) {
            val view = LiveCursorView(context.applicationContext)
            val windowType = when {
                canUseAccessibility -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            val added = runCatching {
                wm.addView(view, lp)
                true
            }.getOrDefault(false)
            if (!added) return false
            cursorView = view
            layoutParams = lp
            currentX = dm.widthPixels * 0.5f
            currentY = dm.heightPixels * 0.45f
        }

        val view = cursorView ?: return false
        view.accentColor = accentColor
        view.hoverLabel = label.take(34)
        SystemOverlayStateBus.publishCursorStatus(label)

        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        val startX = currentX
        val startY = currentY

        activeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = glideMs.coerceIn(180L, 650L)
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentX = startX + (clampedX - startX) * f
                currentY = startY + (clampedY - startY) * f
                view.updateCursorPosition(currentX, currentY)
            }
        }
        animator.start()
        activeAnimator = animator

        if (onArrive != null) {
            mainHandler.postDelayed({ onArrive() }, glideMs.coerceIn(180L, 650L) + 40L)
        }

        val scheduleHide = Runnable { hideNow() }
        hideRunnable = scheduleHide
        mainHandler.postDelayed(scheduleHide, glideMs + autoHideMs.coerceAtLeast(1000L))
        return true
    }

    private class LiveCursorView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        var accentColor: Int = 0xFF38BDF8.toInt()
        var hoverLabel: String = "Hovering"

        private var cursorX: Float = 240f
        private var cursorY: Float = 420f
        private var rippleProgress: Float = 0f
        private var rippleAnimator: ValueAnimator? = null

        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val haloFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val pointerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(6f * density, 0f, 2f * density, 0x80000000.toInt())
        }
        private val pointerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xE60F172A.toInt()
        }
        private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f * density
            isFakeBoldText = true
        }
        private val pointerPath = Path()
        private val pillRect = RectF()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun updateCursorPosition(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            invalidate()
        }

        fun triggerClickRipple() {
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0.05f, 1f).apply {
                duration = 420L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    rippleProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Outer hover target halo centered at cursor tip
            val baseHaloRadius = 18f * density
            haloFillPaint.color = (accentColor and 0x00FFFFFF) or 0x33000000
            haloPaint.color = accentColor
            canvas.drawCircle(cursorX, cursorY, baseHaloRadius, haloFillPaint)
            canvas.drawCircle(cursorX, cursorY, baseHaloRadius, haloPaint)

            // Expanding click ripple ring
            if (rippleProgress in 0.01f..0.99f) {
                val rippleRadius = (16f + 34f * rippleProgress) * density
                val alpha = ((1f - rippleProgress) * 230).toInt().coerceIn(0, 255)
                haloPaint.color = (accentColor and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawCircle(cursorX, cursorY, rippleRadius, haloPaint)
            }

            // Sharp crisp pointer arrow anchored at (cursorX, cursorY)
            val s = 22f * density
            pointerPath.reset()
            pointerPath.moveTo(cursorX, cursorY)
            pointerPath.lineTo(cursorX + s * 0.14f, cursorY + s * 0.96f)
            pointerPath.lineTo(cursorX + s * 0.42f, cursorY + s * 0.68f)
            pointerPath.lineTo(cursorX + s * 0.84f, cursorY + s * 0.66f)
            pointerPath.close()

            pointerStrokePaint.color = accentColor
            canvas.drawPath(pointerPath, pointerFillPaint)
            canvas.drawPath(pointerPath, pointerStrokePaint)

            // Floating target tooltip pill beside cursor
            if (hoverLabel.isNotBlank()) {
                val textWidth = pillTextPaint.measureText(hoverLabel)
                val padH = 10f * density
                val pillW = textWidth + padH * 2f
                val pillH = 26f * density
                var left = cursorX + 20f * density
                if (left + pillW > width - 12f * density) {
                    left = (cursorX - pillW - 14f * density).coerceAtLeast(12f * density)
                }
                var top = cursorY + 18f * density
                if (top + pillH > height - 16f * density) {
                    top = (cursorY - pillH - 14f * density).coerceAtLeast(16f * density)
                }
                pillRect.set(left, top, left + pillW, top + pillH)
                pillBorderPaint.color = accentColor
                canvas.drawRoundRect(pillRect, 13f * density, 13f * density, pillBgPaint)
                canvas.drawRoundRect(pillRect, 13f * density, 13f * density, pillBorderPaint)
                val textY = pillRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
                canvas.drawText(hoverLabel, left + padH, textY, pillTextPaint)
            }
        }
    }
}

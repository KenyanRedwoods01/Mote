package com.mote.app.service

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Hardware-smooth draggable FrameLayout wrapper for WindowManager system overlays.
 *
 * Uses screen-absolute [MotionEvent.getRawX] / [MotionEvent.getRawY] rather than local
 * Compose drag deltas so moving the WindowManager surface underneath the pointer never
 * induces coordinate feedback oscillation when dragging over foreground apps.
 *
 * Taps smaller than [ViewConfiguration.getScaledTouchSlop] pass cleanly to child Compose views.
 */
class DraggableOverlayFrameLayout(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParamsProvider: () -> WindowManager.LayoutParams?,
    private val isDragEnabled: () -> Boolean = { true },
    private val onDragStateChanged: (Boolean) -> Unit = {},
    private val onPositionSettled: (Int, Int) -> Unit = { _, _ -> }
) : FrameLayout(context) {

    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(10f)

    private var downRawX = 0f
    private var downRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isDragEnabled()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val params = layoutParamsProvider() ?: return false
                activePointerId = ev.getPointerId(0)
                downRawX = ev.rawX
                downRawY = ev.rawY
                startWindowX = params.x
                startWindowY = params.y
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    dragging = true
                    onDragStateChanged(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateWindowPosition(dx, dy)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (dragging) {
                    finishDrag()
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragEnabled()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val params = layoutParamsProvider() ?: return false
                activePointerId = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                startWindowX = params.x
                startWindowY = params.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    dragging = true
                    onDragStateChanged(true)
                }
                if (dragging) {
                    updateWindowPosition(dx, dy)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (dragging) {
                    finishDrag()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateWindowPosition(deltaRawX: Float, deltaRawY: Float) {
        val params = layoutParamsProvider() ?: return
        val dm = context.resources.displayMetrics
        val viewWidth = width.takeIf { it > 0 } ?: (72 * dm.density).toInt()
        val viewHeight = height.takeIf { it > 0 } ?: (72 * dm.density).toInt()
        val maxX = (dm.widthPixels - viewWidth).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewHeight).coerceAtLeast(0)
        params.x = (startWindowX + deltaRawX.toInt()).coerceIn(0, maxX)
        params.y = (startWindowY + deltaRawY.toInt()).coerceIn(0, maxY)
        runCatching {
            windowManager.updateViewLayout(this, params)
        }
    }

    private fun finishDrag() {
        dragging = false
        onDragStateChanged(false)
        val params = layoutParamsProvider()
        if (params != null) {
            onPositionSettled(params.x, params.y)
        }
    }
}

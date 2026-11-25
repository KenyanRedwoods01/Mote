package com.mote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mote.app.R
import com.mote.app.ui.HybridInferenceActivity
import com.mote.app.ui.components.FloatingAssistantOrb
import com.mote.app.ui.components.LocalNanoEmerald
import kotlin.math.roundToInt

/**
 * System-level overlay Foreground Service rendering Mote's draggable [FloatingAssistantOrb]
 * over any third-party Android app via [WindowManager] (`TYPE_APPLICATION_OVERLAY`).
 */
class BotOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        startForegroundNotification()
        attachBotOrb()
    }

    private fun startForegroundNotification() {
        val channelId = "mote_overlay_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Mote Floating Bots", NotificationManager.IMPORTANCE_MIN)
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Mote active")
            .setContentText("Drag or tap your floating bot anytime for Generative UI.")
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    private fun attachBotOrb() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val edgeMarginPx = (14f * metrics.density).roundToInt()
        val orbApproxPx = (62f * metrics.density).roundToInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            y = (screenHeightPx * 0.45f).roundToInt()
        }
        overlayLayoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BotOverlayService)
            setViewTreeViewModelStoreOwner(this@BotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BotOverlayService)
            setContent {
                var isDragging by remember { mutableStateOf(false) }

                FloatingAssistantOrb(
                    accentColor = LocalNanoEmerald,
                    isDragging = isDragging,
                    isBusy = false,
                    onTap = {
                        val screenCtx = MoteAccessibilityService.instance?.captureRedactedScreenSummary()
                        val intent = Intent(this@BotOverlayService, HybridInferenceActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(HybridInferenceActivity.EXTRA_SCREEN_CONTEXT, screenCtx)
                        }
                        startActivity(intent)
                    },
                    onDragStart = { isDragging = true },
                    onDragDelta = { dx, dy ->
                        val p = overlayLayoutParams ?: return@FloatingAssistantOrb
                        val viewW = overlayView?.width?.takeIf { it > 0 } ?: orbApproxPx
                        val viewH = overlayView?.height?.takeIf { it > 0 } ?: orbApproxPx
                        p.x = (p.x + dx.roundToInt()).coerceIn(edgeMarginPx, (screenWidthPx - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                        p.y = (p.y + dy.roundToInt()).coerceIn(edgeMarginPx, (screenHeightPx - viewH - edgeMarginPx).coerceAtLeast(edgeMarginPx))
                        overlayView?.let { v ->
                            runCatching { wm.updateViewLayout(v, p) }
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val p = overlayLayoutParams ?: return@FloatingAssistantOrb
                        val viewW = overlayView?.width?.takeIf { it > 0 } ?: orbApproxPx
                        // Snap to nearest horizontal screen edge
                        val snapRight = (screenWidthPx - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                        p.x = if (p.x + viewW / 2 < screenWidthPx / 2) edgeMarginPx else snapRight
                        overlayView?.let { v ->
                            runCatching { wm.updateViewLayout(v, p) }
                        }
                    }
                )
            }
        }
        overlayView = view
        wm.addView(view, params)
    }

    override fun onDestroy() {
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        overlayLayoutParams = null
        viewModelStore.clear()
        super.onDestroy()
    }
}

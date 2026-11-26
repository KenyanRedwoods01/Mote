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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlin.math.roundToInt

/**
 * System Overlay Foreground Service using Android [WindowManager] (`TYPE_APPLICATION_OVERLAY`)
 * to maintain Mote's Lottie-based animated voice interface consistently across external Android
 * applications (Chrome, YouTube, Phone, Maps, WhatsApp, etc.).
 *
 * Automatically coordinates with [AccessibilityFloatingWindowManager] via [SystemOverlayStateBus]
 * so if `TYPE_ACCESSIBILITY_OVERLAY` is already active, the two surfaces never duplicate.
 */
class BotOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var overlayContainer: DraggableOverlayFrameLayout? = null
    private var overlayView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        OverlayCustomizationStore.ensureLoaded(this)
        SystemOverlayStateBus.markServiceRunning(true)
        startForegroundNotification()
        attachLottieVoiceOverlay()
    }

    private fun startForegroundNotification() {
        val channelId = "mote_overlay_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Mote Floating Agent", NotificationManager.IMPORTANCE_MIN)
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Mote Voice Overlay Active")
            .setContentText("Lottie voice assistant floating over your apps for chained tools & live voice relay.")
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    private fun launchOverlayBot(bot: OverlayQuickBot) {
        val screenCtx = MoteAccessibilityService.instance?.captureRedactedScreenSummary()
        val intent = Intent(this, HybridInferenceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(HybridInferenceActivity.EXTRA_SCREEN_CONTEXT, screenCtx)
            putExtra(HybridInferenceActivity.EXTRA_BOT_ID, bot.id)
            putExtra(HybridInferenceActivity.EXTRA_BOT_NAME, bot.name)
            putExtra(HybridInferenceActivity.EXTRA_BOT_COLOR, bot.colorHex)
            putExtra(HybridInferenceActivity.EXTRA_BOT_SYSTEM_PROMPT, bot.systemPrompt)
            putExtra(HybridInferenceActivity.EXTRA_INITIAL_PROMPT, bot.defaultPrompt)
        }
        startActivity(intent)
    }

    private fun attachLottieVoiceOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val edgeMarginPx = (8f * metrics.density).roundToInt()
        val orbApproxPx = (OverlayCustomizationStore.config.value.orbSizeDp * metrics.density).roundToInt()

        val defaultX = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val defaultY = (screenHeightPx * 0.36f).roundToInt()
        val (rawSavedX, rawSavedY) = OverlayCustomizationStore.loadSavedPosition(this, defaultX, defaultY)
        val savedX = rawSavedX.coerceIn(0, (screenWidthPx - orbApproxPx).coerceAtLeast(0))
        val savedY = rawSavedY.coerceIn(0, (screenHeightPx - orbApproxPx).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        overlayLayoutParams = params

        val container = DraggableOverlayFrameLayout(
            context = this,
            windowManager = wm,
            layoutParamsProvider = { overlayLayoutParams },
            onDragStateChanged = { dragging ->
                SystemOverlayStateBus.setOverlayDragging(dragging)
            },
            onPositionSettled = { x, y ->
                OverlayCustomizationStore.savePosition(this, x, y)
            }
        )

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BotOverlayService)
            setViewTreeViewModelStoreOwner(this@BotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BotOverlayService)
            setContent {
                val hudState by SystemOverlayStateBus.state.collectAsState()
                // Yield seamlessly when AccessibilityFloatingWindowManager is already hosting the overlay
                if (!hudState.isAccessibilityOverlayActive && !hudState.isHiddenForPrivacy) {
                    SystemAssistantOverlayContent(
                        hostBadgeLabel = "WM",
                        onLaunchBot = { bot -> launchOverlayBot(bot) }
                    )
                }
            }
        }
        container.addView(view)
        overlayView = view
        overlayContainer = container
        wm.addView(container, params)
    }

    companion object {
        internal const val OVERLAY_PREFS_NAME = "mote_overlay_position_prefs"
        internal const val KEY_OVERLAY_X = "overlay_pos_x"
        internal const val KEY_OVERLAY_Y = "overlay_pos_y"
    }

    override fun onDestroy() {
        SystemOverlayStateBus.setOverlayDragging(false)
        SystemOverlayStateBus.markServiceRunning(false)
        overlayContainer?.let { runCatching { windowManager?.removeView(it) } }
        overlayContainer = null
        overlayView = null
        overlayLayoutParams = null
        viewModelStore.clear()
        super.onDestroy()
    }
}

package com.mote.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mote.app.ui.HybridInferenceActivity
import kotlin.math.roundToInt

/**
 * AccessibilityService-based floating window manager hosting Mote's Lottie-based AI interaction
 * overlay via [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * Advantages over standard `TYPE_APPLICATION_OVERLAY`:
 * - Renders persistently above other Android apps AND across the System UI (status bar, recents,
 *   split-screen, dialer, Chrome, YouTube, Maps) without requiring `SYSTEM_ALERT_WINDOW`.
 * - Automatically hides when [PrivacyGuard] detects sensitive foreground packages (e.g., banking/auth)
 *   and reappears immediately when leaving them.
 */
class AccessibilityFloatingWindowManager(
    private val service: AccessibilityService,
    private val screenContextProvider: () -> String?
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var overlayWindowParams: WindowManager.LayoutParams? = null
    private var initializedLifecycle = false
    private var isAttached = false
    private var hiddenForPrivacy = false

    fun attachAndShow(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { attachAndShow() }
            return true
        }
        if (isAttached && composeView != null) {
            composeView?.visibility = if (hiddenForPrivacy) View.GONE else View.VISIBLE
            SystemOverlayStateBus.markAccessibilityOverlayRunning(true)
            return true
        }
        return try {
            ensureLifecycleResumed()
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val prefs = service.getSharedPreferences(
                BotOverlayService.OVERLAY_PREFS_NAME,
                AccessibilityService.MODE_PRIVATE
            )
            val metrics = service.resources.displayMetrics
            val screenWidthPx = metrics.widthPixels
            val screenHeightPx = metrics.heightPixels
            val edgeMarginPx = (8f * metrics.density).roundToInt()
            val orbApproxPx = (74f * metrics.density).roundToInt()

            val defaultX = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val defaultY = (screenHeightPx * 0.34f).roundToInt()
            val savedX = prefs.getInt(BotOverlayService.KEY_OVERLAY_X, defaultX)
                .coerceIn(edgeMarginPx, (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))
            val savedY = prefs.getInt(BotOverlayService.KEY_OVERLAY_Y, defaultY)
                .coerceIn(edgeMarginPx, (screenHeightPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }
            overlayWindowParams = params

            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(this@AccessibilityFloatingWindowManager)
                setViewTreeViewModelStoreOwner(this@AccessibilityFloatingWindowManager)
                setViewTreeSavedStateRegistryOwner(this@AccessibilityFloatingWindowManager)
                visibility = if (hiddenForPrivacy) View.GONE else View.VISIBLE
                setContent {
                    SystemAssistantOverlayContent(
                        hostBadgeLabel = "A11Y",
                        onLaunchBot = { bot -> launchBotOverlaySheet(bot) },
                        onDragStart = {},
                        onDragDelta = { dx, dy ->
                            val p = overlayWindowParams ?: return@SystemAssistantOverlayContent
                            val liveMetrics = service.resources.displayMetrics
                            val maxW = liveMetrics.widthPixels
                            val maxH = liveMetrics.heightPixels
                            val viewW = composeView?.width?.takeIf { it > 0 } ?: orbApproxPx
                            val viewH = composeView?.height?.takeIf { it > 0 } ?: orbApproxPx
                            p.x = (p.x + dx.roundToInt()).coerceIn(
                                edgeMarginPx,
                                (maxW - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            )
                            p.y = (p.y + dy.roundToInt()).coerceIn(
                                edgeMarginPx,
                                (maxH - viewH - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            )
                            composeView?.let { v ->
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            // Keep the user's exact free-placed X/Y coordinates anywhere on screen & persist them
                            val p = overlayWindowParams ?: return@SystemAssistantOverlayContent
                            prefs.edit()
                                .putInt(BotOverlayService.KEY_OVERLAY_X, p.x)
                                .putInt(BotOverlayService.KEY_OVERLAY_Y, p.y)
                                .apply()
                        }
                    )
                }
            }

            wm.addView(view, params)
            composeView = view
            isAttached = true
            SystemOverlayStateBus.markAccessibilityOverlayRunning(true)
            true
        } catch (_: Exception) {
            SystemOverlayStateBus.markAccessibilityOverlayRunning(false)
            false
        }
    }

    fun setPrivacyHidden(hidden: Boolean) {
        hiddenForPrivacy = hidden
        mainHandler.post {
            composeView?.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

    fun hide() {
        mainHandler.post {
            composeView?.visibility = View.GONE
            SystemOverlayStateBus.markAccessibilityOverlayRunning(false)
        }
    }

    fun detach() {
        val cleanup = Runnable {
            composeView?.let { v ->
                runCatching { windowManager?.removeView(v) }
            }
            composeView = null
            overlayWindowParams = null
            isAttached = false
            SystemOverlayStateBus.markAccessibilityOverlayRunning(false)
            if (initializedLifecycle && lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
                runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
                runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            }
            viewModelStore.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            mainHandler.post(cleanup)
        }
    }

    private fun ensureLifecycleResumed() {
        if (!initializedLifecycle) {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            initializedLifecycle = true
        }
    }

    private fun launchBotOverlaySheet(bot: OverlayQuickBot) {
        val screenCtx = screenContextProvider()
        val intent = Intent(service, HybridInferenceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(HybridInferenceActivity.EXTRA_SCREEN_CONTEXT, screenCtx)
            putExtra(HybridInferenceActivity.EXTRA_BOT_ID, bot.id)
            putExtra(HybridInferenceActivity.EXTRA_BOT_NAME, bot.name)
            putExtra(HybridInferenceActivity.EXTRA_BOT_COLOR, bot.colorHex)
            putExtra(HybridInferenceActivity.EXTRA_BOT_SYSTEM_PROMPT, bot.systemPrompt)
            putExtra(HybridInferenceActivity.EXTRA_INITIAL_PROMPT, bot.defaultPrompt)
        }
        runCatching { service.startActivity(intent) }
    }
}

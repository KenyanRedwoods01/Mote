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
    private var overlayContainer: DraggableOverlayFrameLayout? = null
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
        OverlayCustomizationStore.ensureLoaded(service)
        if (isAttached && overlayContainer != null) {
            overlayContainer?.visibility = if (hiddenForPrivacy) View.GONE else View.VISIBLE
            SystemOverlayStateBus.markAccessibilityOverlayRunning(true)
            return true
        }
        return try {
            ensureLifecycleResumed()
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val metrics = service.resources.displayMetrics
            val screenWidthPx = metrics.widthPixels
            val screenHeightPx = metrics.heightPixels
            val edgeMarginPx = (8f * metrics.density).roundToInt()
            val orbApproxPx = (OverlayCustomizationStore.config.value.orbSizeDp * metrics.density).roundToInt()

            val defaultX = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val defaultY = (screenHeightPx * 0.34f).roundToInt()
            val (rawSavedX, rawSavedY) = OverlayCustomizationStore.loadSavedPosition(service, defaultX, defaultY)
            val savedX = rawSavedX.coerceIn(0, (screenWidthPx - orbApproxPx).coerceAtLeast(0))
            val savedY = rawSavedY.coerceIn(0, (screenHeightPx - orbApproxPx).coerceAtLeast(0))

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

            val container = DraggableOverlayFrameLayout(
                context = service,
                windowManager = wm,
                layoutParamsProvider = { overlayWindowParams },
                onDragStateChanged = { dragging ->
                    SystemOverlayStateBus.setOverlayDragging(dragging)
                },
                onPositionSettled = { x, y ->
                    OverlayCustomizationStore.savePosition(service, x, y)
                }
            ).apply {
                visibility = if (hiddenForPrivacy) View.GONE else View.VISIBLE
            }

            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(this@AccessibilityFloatingWindowManager)
                setViewTreeViewModelStoreOwner(this@AccessibilityFloatingWindowManager)
                setViewTreeSavedStateRegistryOwner(this@AccessibilityFloatingWindowManager)
                setContent {
                    SystemAssistantOverlayContent(
                        hostBadgeLabel = "A11Y",
                        onLaunchBot = { bot -> launchBotOverlaySheet(bot) }
                    )
                }
            }

            container.addView(view)
            wm.addView(container, params)
            overlayContainer = container
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
            overlayContainer?.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

    fun hide() {
        mainHandler.post {
            overlayContainer?.visibility = View.GONE
            SystemOverlayStateBus.markAccessibilityOverlayRunning(false)
        }
    }

    fun detach() {
        val cleanup = Runnable {
            overlayContainer?.let { v ->
                runCatching { windowManager?.removeView(v) }
            }
            overlayContainer = null
            composeView = null
            overlayWindowParams = null
            isAttached = false
            SystemOverlayStateBus.setOverlayDragging(false)
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

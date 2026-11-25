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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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

class BotOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

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
            .setContentText("Tap your floating bot anytime for Hybrid Generative UI.")
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    private fun attachBotOrb() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BotOverlayService)
            setViewTreeViewModelStoreOwner(this@BotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BotOverlayService)
            setContent {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0B0D1D))
                        .clickable {
                            val screenCtx = MoteAccessibilityService.instance?.captureRedactedScreenSummary()
                            val intent = Intent(this@BotOverlayService, HybridInferenceActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(HybridInferenceActivity.EXTRA_SCREEN_CONTEXT, screenCtx)
                            }
                            startActivity(intent)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mote_launcher),
                        contentDescription = "Open Mote",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }
        overlayView = view
        windowManager?.addView(view, params)
    }

    override fun onDestroy() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        viewModelStore.clear()
        super.onDestroy()
    }
}

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mote.app.ui.components.CloudFlashCobalt
import com.mote.app.ui.components.FloatingAssistantOrb
import com.mote.app.ui.components.LocalNanoEmerald
import com.mote.app.ui.components.MoteBrandNavy
import kotlin.math.roundToInt

private data class OverlayQuickBot(
    val id: String,
    val name: String,
    val colorHex: Long,
    val icon: ImageVector,
    val systemPrompt: String,
    val defaultPrompt: String
)

private val systemOverlayBots = listOf(
    OverlayQuickBot(
        id = "focus",
        name = "Focus",
        colorHex = 0xFF10B981L,
        icon = Icons.Outlined.AutoAwesome,
        systemPrompt = "You are Focus, a proactive Android assistant with real-time Clock, Alarm, Timer, Calendar, and GenUI tools.",
        defaultPrompt = "Start a 25 minute deep focus timer"
    ),
    OverlayQuickBot(
        id = "calendar",
        name = "Calendar",
        colorHex = 0xFF2563EBL,
        icon = Icons.Outlined.CalendarMonth,
        systemPrompt = "You are Calendar Bot. Manage events using Android Calendar Provider tools (listEvents, addEvent, updateEvent, deleteEvent).",
        defaultPrompt = "Show my upcoming calendar events"
    ),
    OverlayQuickBot(
        id = "clock",
        name = "Clock",
        colorHex = 0xFFF59E0BL,
        icon = Icons.Outlined.Alarm,
        systemPrompt = "You are Clock Bot. Manage real alarms via AlarmManager, countdown timers, and stopwatch operations.",
        defaultPrompt = "Set an alarm for 7:30 AM labeled Morning Standup"
    ),
    OverlayQuickBot(
        id = "reply",
        name = "Reply",
        colorHex = 0xFF7C3AEDL,
        icon = Icons.Outlined.ChatBubbleOutline,
        systemPrompt = "You are Reply Bot. Draft crisp contextual replies based on the redacted screen summary and offer quick reply pills.",
        defaultPrompt = "Draft 3 polite quick replies for what is on my screen"
    )
)

/**
 * System-level overlay Foreground Service rendering Mote's draggable Multi-Bot Floating Dock
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
        attachMultiBotDock()
    }

    private fun startForegroundNotification() {
        val channelId = "mote_overlay_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Mote Floating Bots", NotificationManager.IMPORTANCE_MIN)
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Mote Floating Bots active")
            .setContentText("Tap your floating orb to summon real-time AI bots & tools over any app.")
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

    private fun attachMultiBotDock() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val metrics = resources.displayMetrics
        val screenWidthPx = metrics.widthPixels
        val screenHeightPx = metrics.heightPixels
        val edgeMarginPx = (12f * metrics.density).roundToInt()
        val orbApproxPx = (64f * metrics.density).roundToInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx - orbApproxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            y = (screenHeightPx * 0.42f).roundToInt()
        }
        overlayLayoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BotOverlayService)
            setViewTreeViewModelStoreOwner(this@BotOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BotOverlayService)
            setContent {
                var isDragging by remember { mutableStateOf(false) }
                var dockExpanded by remember { mutableStateOf(false) }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    AnimatedVisibility(
                        visible = dockExpanded && !isDragging,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            systemOverlayBots.forEach { bot ->
                                SystemFloatingBotPill(
                                    bot = bot,
                                    onClick = {
                                        dockExpanded = false
                                        launchOverlayBot(bot)
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .shadow(6.dp, RoundedCornerShape(999.dp))
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xE6334155))
                                    .clickable { dockExpanded = false }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Collapse floating bot dock",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Hide",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    FloatingAssistantOrb(
                        accentColor = if (dockExpanded) CloudFlashCobalt else LocalNanoEmerald,
                        isDragging = isDragging,
                        isBusy = dockExpanded,
                        onTap = {
                            if (dockExpanded) {
                                dockExpanded = false
                                launchOverlayBot(systemOverlayBots.first())
                            } else {
                                dockExpanded = true
                            }
                        },
                        onDragStart = {
                            isDragging = true
                            dockExpanded = false
                        },
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
                            val snapRight = (screenWidthPx - viewW - edgeMarginPx).coerceAtLeast(edgeMarginPx)
                            p.x = if (p.x + viewW / 2 < screenWidthPx / 2) edgeMarginPx else snapRight
                            overlayView?.let { v ->
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        }
                    )
                }
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

@Composable
private fun SystemFloatingBotPill(
    bot: OverlayQuickBot,
    onClick: () -> Unit
) {
    val accent = Color(bot.colorHex)
    Row(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(MoteBrandNavy.copy(alpha = 0.95f))
            .border(1.5.dp, accent, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = bot.name,
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = bot.icon,
                contentDescription = "Open ${bot.name} Bot",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

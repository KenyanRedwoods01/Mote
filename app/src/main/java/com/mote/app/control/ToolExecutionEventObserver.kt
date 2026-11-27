package com.mote.app.control

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class SystemEventCategory(val label: String) {
    ALARM_CLOCK("AlarmManager"),
    AUDIO_VOLUME("AudioManager"),
    DND_POLICY("NotificationManager"),
    SCREEN_BRIGHTNESS("Settings.System"),
    CALENDAR_PROVIDER("CalendarContract"),
    CLIPBOARD("ClipboardManager")
}

data class ObservedSystemEvent(
    val id: String = UUID.randomUUID().toString(),
    val category: SystemEventCategory,
    val mechanism: String,
    val detail: String,
    val observedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * EventObserver contract inside [ToolExecutionSyncService] for confirming real Android system
 * state transitions via [BroadcastReceiver], [ContentObserver], and system listeners before
 * updating the Generative UI log or marking an action 'Execution Verified'.
 */
interface SystemActionEventObserver {
    val observedEvents: StateFlow<List<ObservedSystemEvent>>
    val isActive: Boolean
    fun start()
    fun stop()
    fun ensureCalendarObserverRegistered()
    suspend fun awaitEvent(
        category: SystemEventCategory,
        sinceElapsedMs: Long,
        timeoutMs: Long = 700L
    ): ObservedSystemEvent?
    fun latestEventSince(category: SystemEventCategory, sinceElapsedMs: Long): ObservedSystemEvent?
}

/**
 * Production Android [SystemActionEventObserver] hooking into:
 * - [BroadcastReceiver]: `AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED`, `AudioManager` volume &
 *   ringer mode broadcasts, and `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED`.
 * - [ContentObserver] (on a dedicated background [HandlerThread]): `Settings.System.SCREEN_BRIGHTNESS`
 *   and `CalendarContract.Events.CONTENT_URI` (when `READ_CALENDAR` is granted).
 * - [ClipboardManager.OnPrimaryClipChangedListener]: real-time system clipboard write callbacks.
 */
class AndroidSystemEventObserver(context: Context) : SystemActionEventObserver {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val started = AtomicBoolean(false)
    private val calendarObserverAttached = AtomicBoolean(false)

    private var observerThread: HandlerThread? = null
    private var observerHandler: Handler? = null
    private var brightnessObserver: ContentObserver? = null
    private var systemSettingsObserver: ContentObserver? = null
    private var calendarObserver: ContentObserver? = null
    private var systemBroadcastReceiver: BroadcastReceiver? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val _observedEvents = MutableStateFlow<List<ObservedSystemEvent>>(emptyList())
    override val observedEvents: StateFlow<List<ObservedSystemEvent>> = _observedEvents.asStateFlow()

    private val eventBus = MutableSharedFlow<ObservedSystemEvent>(extraBufferCapacity = 32)

    override val isActive: Boolean
        get() = started.get()

    override fun start() {
        if (!started.compareAndSet(false, true)) return

        val thread = HandlerThread("MoteToolEventObserver").apply { start() }
        val handler = Handler(thread.looper)
        observerThread = thread
        observerHandler = handler

        // 1. BroadcastReceiver for AlarmManager, AudioManager (Volume/Ringer), and NotificationManager (DND)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                        val nextTrigger = runCatching { alarmManager?.nextAlarmClock?.triggerTime }.getOrNull()
                        val detail = if (nextTrigger != null) {
                            "Next AlarmClock updated (trigger=${nextTrigger})"
                        } else {
                            "AlarmManager next alarm clock schedule changed"
                        }
                        emitEvent(
                            category = SystemEventCategory.ALARM_CLOCK,
                            mechanism = "BroadcastReceiver",
                            detail = detail
                        )
                    }
                    ACTION_VOLUME_CHANGED,
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val musicVol = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                        val maxVol = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                        val ringerLabel = when (runCatching { audioManager?.ringerMode }.getOrNull()) {
                            AudioManager.RINGER_MODE_SILENT -> "Silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                            else -> "Normal"
                        }
                        val detail = if (musicVol != null && maxVol != null && maxVol > 0) {
                            val pct = (musicVol * 100) / maxVol
                            "AudioManager stream=$pct% ($musicVol/$maxVol) · Ringer=$ringerLabel"
                        } else {
                            "AudioManager ringer/volume state changed ($ringerLabel)"
                        }
                        emitEvent(
                            category = SystemEventCategory.AUDIO_VOLUME,
                            mechanism = "BroadcastReceiver",
                            detail = detail
                        )
                    }
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                        val filterLabel = when (runCatching { notificationManager?.currentInterruptionFilter }.getOrNull()) {
                            NotificationManager.INTERRUPTION_FILTER_ALL -> "All Alerts (DND Off)"
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority Only (DND On)"
                            NotificationManager.INTERRUPTION_FILTER_NONE -> "Total Silence (DND On)"
                            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms Only (DND On)"
                            else -> "Interruption Filter Updated"
                        }
                        emitEvent(
                            category = SystemEventCategory.DND_POLICY,
                            mechanism = "BroadcastReceiver",
                            detail = "NotificationManager DND -> $filterLabel"
                        )
                    }
                }
            }
        }
        systemBroadcastReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(ACTION_VOLUME_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        // 2. ContentObserver for Settings.System.SCREEN_BRIGHTNESS
        val brightObs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val raw = runCatching {
                    Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                }.getOrDefault(-1)
                val pct = if (raw in 0..255) ((raw * 100) / 255) else -1
                emitEvent(
                    category = SystemEventCategory.SCREEN_BRIGHTNESS,
                    mechanism = "ContentObserver",
                    detail = if (pct >= 0) "Screen brightness confirmed at $pct% (raw=$raw)" else "Screen brightness updated"
                )
            }
        }
        brightnessObserver = brightObs
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                brightObs
            )
        }

        // 2b. ContentObserver for Settings.System descendants (volume_* streams & next_alarm_formatted)
        // Ensures reliable verification even on Android 14-16 OEMs that throttle VOLUME_CHANGED_ACTION.
        val settingsObs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val seg = uri?.lastPathSegment?.lowercase().orEmpty()
                when {
                    seg.contains("volume") || seg.contains("ringer") || seg.contains("mute") -> {
                        val musicVol = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                        val maxVol = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
                        val detail = if (musicVol != null && maxVol != null && maxVol > 0) {
                            val pct = (musicVol * 100) / maxVol
                            "Settings.System ($seg) confirmed · Media=$pct% ($musicVol/$maxVol)"
                        } else {
                            "Settings.System audio key updated ($seg)"
                        }
                        emitEvent(
                            category = SystemEventCategory.AUDIO_VOLUME,
                            mechanism = "ContentObserver",
                            detail = detail
                        )
                    }
                    seg.contains("alarm") -> {
                        emitEvent(
                            category = SystemEventCategory.ALARM_CLOCK,
                            mechanism = "ContentObserver",
                            detail = "Settings.System alarm schedule updated ($seg)"
                        )
                    }
                }
            }
        }
        systemSettingsObserver = settingsObs
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                settingsObs
            )
        }

        // 3. ContentObserver for CalendarContract.Events (registered when permission is granted)
        ensureCalendarObserverRegistered()

        // 4. ClipboardManager primary clip change listener
        val clipObs = ClipboardManager.OnPrimaryClipChangedListener {
            val preview = runCatching {
                clipboardManager?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.take(36)
            }.getOrNull()
            emitEvent(
                category = SystemEventCategory.CLIPBOARD,
                mechanism = "ClipboardListener",
                detail = if (!preview.isNullOrBlank()) {
                    "System clipboard updated (\"$preview\")"
                } else {
                    "System clipboard clip changed"
                }
            )
        }
        clipListener = clipObs
        runCatching { clipboardManager?.addPrimaryClipChangedListener(clipObs) }
    }

    override fun ensureCalendarObserverRegistered() {
        if (!started.get() || calendarObserverAttached.get()) return
        val hasCalendarPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCalendarPerm) return
        val handler = observerHandler ?: return
        if (!calendarObserverAttached.compareAndSet(false, true)) return

        val calObs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                emitEvent(
                    category = SystemEventCategory.CALENDAR_PROVIDER,
                    mechanism = "ContentObserver",
                    detail = "Android CalendarContract provider synchronized${uri?.lastPathSegment?.let { " (#$it)" }.orEmpty()}"
                )
            }
        }
        calendarObserver = calObs
        val ok = runCatching {
            appContext.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                calObs
            )
        }.isSuccess
        if (!ok) {
            calendarObserverAttached.set(false)
            calendarObserver = null
        }
    }

    override fun latestEventSince(
        category: SystemEventCategory,
        sinceElapsedMs: Long
    ): ObservedSystemEvent? {
        return _observedEvents.value.firstOrNull {
            it.category == category && it.observedAtElapsedMs >= sinceElapsedMs
        }
    }

    override suspend fun awaitEvent(
        category: SystemEventCategory,
        sinceElapsedMs: Long,
        timeoutMs: Long
    ): ObservedSystemEvent? {
        latestEventSince(category, sinceElapsedMs)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            eventBus.firstOrNull {
                it.category == category && it.observedAtElapsedMs >= sinceElapsedMs
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        systemBroadcastReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        systemBroadcastReceiver = null
        brightnessObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        brightnessObserver = null
        systemSettingsObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        systemSettingsObserver = null
        calendarObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        calendarObserver = null
        calendarObserverAttached.set(false)
        clipListener?.let { runCatching { clipboardManager?.removePrimaryClipChangedListener(it) } }
        clipListener = null
        runCatching { observerThread?.quitSafely() }
        observerThread = null
        observerHandler = null
    }

    private fun emitEvent(
        category: SystemEventCategory,
        mechanism: String,
        detail: String
    ) {
        val event = ObservedSystemEvent(
            category = category,
            mechanism = mechanism,
            detail = detail,
            observedAtElapsedMs = SystemClock.elapsedRealtime(),
            timestampMs = System.currentTimeMillis()
        )
        _observedEvents.update { (listOf(event) + it).take(30) }
        eventBus.tryEmit(event)
    }

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}

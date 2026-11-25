package com.mote.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mote.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class MediaProjectionToken(
    val resultCode: Int,
    val data: Intent
)

/**
 * Coordinates explicit user authorization for the Android MediaProjection API between
 * Compose Activity result launchers and AI tool execution coroutines.
 */
object MediaProjectionConsentBridge {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedToken: MediaProjectionToken? = null

    @Volatile
    private var consentRequester: ((Intent, (Int, Intent?) -> Unit) -> Unit)? = null

    private val _hasAuthorizedToken = MutableStateFlow(false)
    val hasAuthorizedToken: StateFlow<Boolean> = _hasAuthorizedToken.asStateFlow()

    fun hasCachedConsent(): Boolean = cachedToken != null

    fun storeConsent(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            cachedToken = MediaProjectionToken(resultCode, Intent(data))
            _hasAuthorizedToken.value = true
        } else {
            cachedToken = null
            _hasAuthorizedToken.value = false
        }
    }

    fun clearConsent() {
        cachedToken = null
        _hasAuthorizedToken.value = false
    }

    fun attachConsentLauncher(launcher: (Intent, (Int, Intent?) -> Unit) -> Unit) {
        consentRequester = launcher
    }

    fun detachConsentLauncher(launcher: (Intent, (Int, Intent?) -> Unit) -> Unit) {
        if (consentRequester === launcher) {
            consentRequester = null
        }
    }

    /**
     * Consumes a pre-authorized MediaProjection token if present, or launches the system
     * MediaProjection consent dialog via the attached foreground Activity and awaits
     * the user's explicit authorization decision.
     */
    suspend fun requestOrConsumeConsent(context: Context): MediaProjectionToken? {
        val existing = cachedToken
        if (existing != null) {
            // Android 14+ MediaProjection tokens are single-use per session
            cachedToken = null
            _hasAuthorizedToken.value = false
            return existing
        }

        val requester = consentRequester ?: return null
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        val captureIntent = mpm.createScreenCaptureIntent()

        val deferred = CompletableDeferred<MediaProjectionToken?>()
        mainHandler.post {
            try {
                requester(captureIntent) { resultCode, data ->
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        deferred.complete(MediaProjectionToken(resultCode, Intent(data)))
                    } else {
                        deferred.complete(null)
                    }
                }
            } catch (_: Exception) {
                deferred.complete(null)
            }
        }
        return withTimeoutOrNull(45_000L) { deferred.await() }
    }
}

/**
 * Play-compliant Foreground Service (`foregroundServiceType="mediaProjection"`) that executes
 * a single-frame screen capture using Android's MediaProjection + VirtualDisplay + ImageReader API,
 * returns the resulting Bitmap to the caller, and stops itself immediately afterward.
 */
class ScreenCaptureProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private val frameDelivered = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildCaptureNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            deliverResult(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            deliverResult(null)
            stopSelf()
            return START_NOT_STICKY
        }

        captureSingleFrame(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun captureSingleFrame(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            deliverResult(null)
            stopSelf()
            return
        }

        val projection = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (_: Exception) {
            null
        }
        if (projection == null) {
            deliverResult(null)
            stopSelf()
            return
        }
        mediaProjection = projection

        val (width, height, densityDpi) = resolveDisplayDimensions()
        val thread = HandlerThread("MoteMediaProjectionCapture").apply { start() }
        captureThread = thread
        val bgHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // Android 14+ requires registering a MediaProjection.Callback BEFORE createVirtualDisplay
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (frameDelivered.compareAndSet(false, true)) {
                        deliverResult(null)
                    }
                    releaseResources()
                    stopSelf()
                }
            },
            bgHandler
        )

        reader.setOnImageAvailableListener({ ir ->
            if (frameDelivered.get()) return@setOnImageAvailableListener
            val image = try {
                ir.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                if (frameDelivered.compareAndSet(false, true)) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val paddedWidth = width + (if (pixelStride > 0) rowPadding / pixelStride else 0)

                    val rawBmp = Bitmap.createBitmap(
                        paddedWidth.coerceAtLeast(width),
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBmp.copyPixelsFromBuffer(buffer)
                    val cleanBmp = if (rawBmp.width != width || rawBmp.height != height) {
                        val cropped = Bitmap.createBitmap(rawBmp, 0, 0, width, height)
                        rawBmp.recycle()
                        cropped
                    } else {
                        rawBmp
                    }
                    deliverResult(cleanBmp)
                    bgHandler.post {
                        releaseResources()
                        stopSelf()
                    }
                }
            } catch (_: Exception) {
                if (frameDelivered.compareAndSet(false, true)) {
                    deliverResult(null)
                }
                bgHandler.post {
                    releaseResources()
                    stopSelf()
                }
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }, bgHandler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "MoteScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                bgHandler
            )
        } catch (_: Exception) {
            deliverResult(null)
            releaseResources()
            stopSelf()
            return
        }

        // Safety timeout in case no frame arrives within 4.5 seconds
        bgHandler.postDelayed({
            if (frameDelivered.compareAndSet(false, true)) {
                deliverResult(null)
                releaseResources()
                stopSelf()
            }
        }, 4_500L)
    }

    private fun resolveDisplayDimensions(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val dpi = resources.configuration.densityDpi.coerceAtLeast(160)
            Triple(
                bounds.width().coerceAtLeast(360),
                bounds.height().coerceAtLeast(640),
                dpi
            )
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(
                metrics.widthPixels.coerceAtLeast(360),
                metrics.heightPixels.coerceAtLeast(640),
                metrics.densityDpi.coerceAtLeast(160)
            )
        }
    }

    private fun releaseResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        captureThread = null
    }

    private fun buildCaptureNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mote Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while capturing an authorized MediaProjection screenshot for AI visual analysis."
            }
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mote_launcher)
            .setContentTitle("Mote Screen Capture")
            .setContentText("Capturing authorized screenshot for AI analysis...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (frameDelivered.compareAndSet(false, true)) {
            deliverResult(null)
        }
        releaseResources()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "mote_screen_capture_channel"
        private const val NOTIFICATION_ID = 4409
        private const val EXTRA_RESULT_CODE = "extra_mp_result_code"
        private const val EXTRA_RESULT_DATA = "extra_mp_result_data"

        @Volatile
        private var pendingCaptureDeferred: CompletableDeferred<Bitmap?>? = null

        private fun deliverResult(bitmap: Bitmap?) {
            val d = pendingCaptureDeferred
            pendingCaptureDeferred = null
            d?.complete(bitmap)
        }

        /**
         * Launches the MediaProjection foreground service to capture a single frame using
         * the user's explicit MediaProjection consent token.
         */
        suspend fun captureFrame(context: Context, token: MediaProjectionToken): Bitmap? {
            pendingCaptureDeferred?.cancel()
            val deferred = CompletableDeferred<Bitmap?>()
            pendingCaptureDeferred = deferred

            val intent = Intent(context.applicationContext, ScreenCaptureProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, token.resultCode)
                putExtra(EXTRA_RESULT_DATA, token.data)
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                pendingCaptureDeferred = null
                return null
            }
            return withTimeoutOrNull(7_000L) { deferred.await() }
        }
    }
}

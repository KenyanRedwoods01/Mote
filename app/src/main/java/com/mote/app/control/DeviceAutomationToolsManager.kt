package com.mote.app.control

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.PixelCopy
import com.mote.app.service.MediaProjectionConsentBridge
import com.mote.app.service.MoteAccessibilityService
import com.mote.app.service.ScreenCaptureProjectionService
import com.mote.app.service.SystemOverlayStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min

data class DeviceToolOutcome(
    val success: Boolean,
    val title: String,
    val summary: String,
    val badge: String,
    val detailLines: List<String> = emptyList(),
    val captureId: String? = null,
    val savedUriString: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String
)

data class AppUsageSummary(
    val label: String,
    val packageName: String,
    val lastTimeUsedMs: Long,
    val totalTimeInForegroundMs: Long
)

private data class CommonAppCatalogEntry(
    val canonicalName: String,
    val aliases: List<String>,
    val packageCandidates: List<String>,
    val categoryFallback: String? = null,
    val actionFallback: String? = null,
    val webFallbackUrl: String? = null
)

/**
 * Real-time Android Device Automation Tools Controller.
 *
 * Powers four AI-callable tool families with real Android APIs:
 * 1. Camera Tool (`openCamera`, `openVideoCamera`, `takePhoto`, `toggleFlashlight`)
 * 2. MediaProjection Screen Capture Tool (`takeScreenshot`, `captureAndAnalyzeScreen`, `captureScreenContext`)
 * 3. App Navigation Tool (`openApp`, `openRecentApp`, `getRecentApps`, `listInstalledApps`) using UsageStatsManager + Intents
 * 4. Device Navigation Tool (`navigateSystem`, `navigateMaps`, `openUrl`, `searchWeb`, `openSettings`, `navigateAppTab`)
 */
class DeviceAutomationToolsManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    val appLauncherUtility = AppLauncherUtility(appContext)

    @Volatile
    private var torchEnabledState: Boolean = false

    @Volatile
    private var lastSavedCaptureUri: String? = null

    private val _capturedScreenshots = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val capturedScreenshots: StateFlow<Map<String, Bitmap>> = _capturedScreenshots.asStateFlow()

    fun getCapturedBitmap(captureId: String?): Pair<String, Bitmap>? {
        val map = _capturedScreenshots.value
        if (!captureId.isNullOrBlank()) {
            val exact = map[captureId]
            if (exact != null && !exact.isRecycled) return captureId to exact
        }
        val latest = map.entries.lastOrNull { !it.value.isRecycled } ?: return null
        return latest.key to latest.value
    }

    // -------------------------------------------------------------------------
    // 1. CAMERA & FLASHLIGHT TOOLS
    // -------------------------------------------------------------------------

    fun openCamera(mode: String = "photo", facing: String? = null): DeviceToolOutcome {
        val isVideo = mode.lowercase(Locale.US).contains("video") || mode.lowercase(Locale.US).contains("record")
        val action = if (isVideo) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            when (facing?.lowercase(Locale.US)) {
                "front", "selfie" -> {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                }
                "back", "rear" -> {
                    putExtra("android.intent.extras.CAMERA_FACING", 0)
                    putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
                }
            }
        }
        return try {
            appContext.startActivity(intent)
            val facingNote = facing?.takeIf { it.isNotBlank() }?.let { " ($it lens)" }.orEmpty()
            DeviceToolOutcome(
                success = true,
                title = if (isVideo) "Video Camera Launched" else "Camera Launched",
                summary = if (isVideo) {
                    "Opened Android Video Camera$facingNote in real time."
                } else {
                    "Opened Android Camera$facingNote ready to capture."
                },
                badge = "CAMERA LIVE"
            )
        } catch (_: Exception) {
            try {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(fallback)
                DeviceToolOutcome(
                    success = true,
                    title = "Camera Capture Opened",
                    summary = "Launched device camera capture activity.",
                    badge = "CAMERA LIVE"
                )
            } catch (e: Exception) {
                DeviceToolOutcome(
                    success = false,
                    title = "Camera Unavailable",
                    summary = "No camera app responded on this device (${e.localizedMessage ?: "missing handler"}).",
                    badge = "UNAVAILABLE"
                )
            }
        }
    }

    fun toggleFlashlight(requestedState: Boolean?): DeviceToolOutcome {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return DeviceToolOutcome(false, "Flashlight Unavailable", "CameraManager service is not available.", "UNAVAILABLE")

        return try {
            val flashCameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lens = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && lens == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (flashCameraId == null) {
                return DeviceToolOutcome(
                    success = false,
                    title = "No Flash Hardware",
                    summary = "This device does not expose a camera torch flash unit.",
                    badge = "NO HARDWARE"
                )
            }

            val nextState = requestedState ?: !torchEnabledState
            cm.setTorchMode(flashCameraId, nextState)
            torchEnabledState = nextState
            DeviceToolOutcome(
                success = true,
                title = if (nextState) "Flashlight Turned ON" else "Flashlight Turned OFF",
                summary = "Camera2 hardware torch is now ${if (nextState) "active" else "off"}.",
                badge = if (nextState) "TORCH ON" else "TORCH OFF"
            )
        } catch (e: Exception) {
            DeviceToolOutcome(
                success = false,
                title = "Flashlight Error",
                summary = e.localizedMessage ?: "Could not toggle camera torch.",
                badge = "ERROR"
            )
        }
    }

    // -------------------------------------------------------------------------
    // 2. MEDIAPROJECTION SCREEN CAPTURE TOOLS
    // -------------------------------------------------------------------------

    /**
     * Captures a live screenshot using Android's MediaProjection API (with pre-authorized or on-demand
     * consent) or Android 11+ AccessibilityService display capture when Mote is running a multi-app
     * chain in the background over an external app (such as Chrome search results).
     * Saves the PNG to Pictures/MoteCaptures and surfaces the resulting Bitmap in
     * [capturedScreenshots] for inline chat rendering and multimodal visual analysis.
     */
    suspend fun takeScreenshot(label: String? = null): DeviceToolOutcome {
        val isMoteBackgrounded = foregroundActivityRef?.get() == null
        val accessibility = MoteAccessibilityService.instance
        var capturedBitmap: Bitmap? = null
        var captureSource = "MediaProjection API"

        // Multi-App Chain Seamless Path:
        // When Mote has just launched an external app (e.g., Chrome search) so Mote is no longer in
        // foreground AND no MediaProjection token was pre-cached, capture the live display directly
        // via user-enabled Android 11+ AccessibilityService.takeScreenshot so the chain never stalls!
        if (isMoteBackgrounded &&
            !MediaProjectionConsentBridge.hasCachedConsent() &&
            accessibility != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            capturedBitmap = suspendCancellableCoroutine { cont ->
                accessibility.captureScreenshotBitmap { bmp ->
                    if (cont.isActive) cont.resume(bmp)
                }
            }
            if (capturedBitmap != null) {
                captureSource = "Accessibility Display Capture"
            }
        }

        if (capturedBitmap == null) {
            // Explicit MediaProjection user authorization flow (consumes any pre-authorized token cached
            // before launching Chrome, or requests consent interactively if Mote is foreground)
            val token = MediaProjectionConsentBridge.requestOrConsumeConsent(appContext)
            if (token == null) {
                // Final fallback if Accessibility is active on API 30+ so external-app chains stay resilient
                if (accessibility != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    capturedBitmap = suspendCancellableCoroutine { cont ->
                        accessibility.captureScreenshotBitmap { bmp ->
                            if (cont.isActive) cont.resume(bmp)
                        }
                    }
                    if (capturedBitmap != null) {
                        captureSource = "Accessibility Display Capture"
                    }
                }
                if (capturedBitmap == null) {
                    return DeviceToolOutcome(
                        success = false,
                        title = "Screen Capture Authorization Required",
                        summary = "Explicit user authorization via Android's MediaProjection prompt is required before capturing the screen. Tap Grant in Tools & Permissions (or enable Mote Accessibility) before retrying.",
                        badge = "CONSENT REQUIRED"
                    )
                }
            } else {
                capturedBitmap = ScreenCaptureProjectionService.captureFrame(appContext, token)
                captureSource = "MediaProjection API"

                // If MediaProjection frame delivery timed out on a headless/restricted display, fall back
                // only AFTER the user explicitly granted MediaProjection consent above.
                if (capturedBitmap == null && accessibility != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    capturedBitmap = suspendCancellableCoroutine { cont ->
                        accessibility.captureScreenshotBitmap { bmp ->
                            if (cont.isActive) cont.resume(bmp)
                        }
                    }
                    if (capturedBitmap != null) {
                        captureSource = "Authorized Display Capture"
                    }
                }
                if (capturedBitmap == null) {
                    val activity = foregroundActivityRef?.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        capturedBitmap = captureActivityWindowBitmap(activity)
                        if (capturedBitmap != null) {
                            captureSource = "Authorized Window Capture"
                        }
                    }
                }
            }
        }

        if (capturedBitmap == null) {
            return DeviceToolOutcome(
                success = false,
                title = "Screen Capture Timed Out",
                summary = "Screen capture was authorized, but no display frame arrived. Please try again.",
                badge = "CAPTURE TIMEOUT"
            )
        }

        val captureId = "cap_${UUID.randomUUID().toString().take(8)}"
        val savedUri = saveBitmapToMediaStore(capturedBitmap, label)
        if (savedUri != null) {
            lastSavedCaptureUri = savedUri.toString()
        }
        val previewBmp = createScaledPreview(capturedBitmap, maxDim = 1080)
        if (previewBmp !== capturedBitmap) {
            capturedBitmap.recycle()
        }

        val updatedMap = LinkedHashMap(_capturedScreenshots.value).apply {
            put(captureId, previewBmp)
            while (size > 8) {
                val firstKey = keys.firstOrNull() ?: break
                remove(firstKey)
            }
        }
        _capturedScreenshots.value = updatedMap

        val saveNote = if (savedUri != null) {
            "Saved to Pictures/MoteCaptures."
        } else {
            "Ready in chat for visual analysis."
        }
        val captureTitle = label?.takeIf { it.isNotBlank() }?.let { "Captured: $it" } ?: "MediaProjection Screenshot"
        SystemOverlayStateBus.publishAppContext(
            appLabel = MoteAccessibilityService.currentPackageName?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "Screen",
            statusBanner = "✓ $captureTitle · Saved & ready in Mote"
        )
        return DeviceToolOutcome(
            success = true,
            title = captureTitle,
            summary = "$captureSource (${previewBmp.width}×${previewBmp.height}) · $saveNote",
            badge = captureSource,
            captureId = captureId,
            savedUriString = savedUri?.toString(),
            widthPx = previewBmp.width,
            heightPx = previewBmp.height
        )
    }

    fun captureScreenContext(): DeviceToolOutcome {
        val accessibility = MoteAccessibilityService.instance
        if (accessibility == null) {
            val currentActivity = foregroundActivityRef?.get()?.javaClass?.simpleName ?: "Mote"
            return DeviceToolOutcome(
                success = true,
                title = "Active Window Context",
                summary = "Currently viewing $currentActivity. Enable Mote Accessibility Service in Tools & Permissions to read third-party app screens.",
                badge = "IN-APP CONTEXT",
                detailLines = listOf("Active screen: $currentActivity", "Accessibility service: inactive")
            )
        }

        val pkg = MoteAccessibilityService.currentPackageName ?: appContext.packageName
        val redacted = accessibility.captureRedactedScreenSummary(maxChars = 900)
        return if (redacted.isNullOrBlank()) {
            DeviceToolOutcome(
                success = true,
                title = "Screen Context Protected",
                summary = "Foreground app ($pkg) returned no readable text or is protected by Privacy Guard.",
                badge = "REDACTED"
            )
        } else {
            val snippet = redacted.replace(" | ", "\n").trim().take(420)
            DeviceToolOutcome(
                success = true,
                title = "Screen Context Captured",
                summary = "Captured redacted UI text from $pkg.",
                badge = "SCREEN READ",
                detailLines = snippet.lines().filter { it.isNotBlank() }.take(6)
            )
        }
    }

    private suspend fun captureActivityWindowBitmap(activity: Activity): Bitmap? =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    val view = activity.window?.decorView?.rootView
                    if (view == null || view.width <= 0 || view.height <= 0) {
                        if (cont.isActive) cont.resume(null)
                        return@post
                    }
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PixelCopy.request(
                            activity.window,
                            bitmap,
                            { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    if (cont.isActive) cont.resume(bitmap)
                                } else {
                                    try {
                                        val canvas = Canvas(bitmap)
                                        view.draw(canvas)
                                        if (cont.isActive) cont.resume(bitmap)
                                    } catch (_: Exception) {
                                        bitmap.recycle()
                                        if (cont.isActive) cont.resume(null)
                                    }
                                }
                            },
                            mainHandler
                        )
                    } else {
                        val canvas = Canvas(bitmap)
                        view.draw(canvas)
                        if (cont.isActive) cont.resume(bitmap)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun saveBitmapToMediaStore(bitmap: Bitmap, label: String?): Uri? = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val slug = label?.trim()?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "_")
            ?.trim('_')
            ?.take(20)
            ?.takeIf { it.isNotBlank() }
        val fileName = if (slug != null) "Mote_${slug}_$stamp.png" else "Mote_Capture_$stamp.png"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MoteCaptures")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val dir = File(appContext.filesDir, "captures").apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createScaledPreview(source: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxDim) return source
        val scale = maxDim.toFloat() / maxSide.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    // -------------------------------------------------------------------------
    // 3. APP NAVIGATION TOOL (USAGE STATISTICS + INTENT-BASED LAUNCHING)
    // -------------------------------------------------------------------------

    fun hasUsageStatsPermission(): Boolean = ToolPermissionInspector.hasUsageStatsAccess(appContext)

    fun queryRecentUsageApps(hoursBack: Int = 24): List<AppUsageSummary> {
        if (!hasUsageStatsPermission()) return emptyList()
        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val pm = appContext.packageManager
        val endMs = System.currentTimeMillis()
        val startMs = endMs - hoursBack.coerceIn(1, 168) * 60L * 60L * 1000L

        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        // Aggregate by packageName (skip Mote itself and zero-usage background system packages)
        val byPkg = LinkedHashMap<String, Pair<Long, Long>>()
        for (item in stats) {
            val pkg = item.packageName ?: continue
            if (pkg == appContext.packageName) continue
            if (item.lastTimeUsed <= 0L) continue
            val prev = byPkg[pkg]
            val bestLast = maxOf(prev?.first ?: 0L, item.lastTimeUsed)
            val totalFg = (prev?.second ?: 0L) + item.totalTimeInForeground
            byPkg[pkg] = bestLast to totalFg
        }

        return byPkg.entries
            .mapNotNull { (pkg, pair) ->
                val launchable = pm.getLaunchIntentForPackage(pkg) != null
                if (!launchable) return@mapNotNull null
                val label = resolvePackageLabel(pm, pkg)
                AppUsageSummary(
                    label = label,
                    packageName = pkg,
                    lastTimeUsedMs = pair.first,
                    totalTimeInForegroundMs = pair.second
                )
            }
            .sortedByDescending { it.lastTimeUsedMs }
    }

    fun queryLaunchableApps(): List<InstalledAppInfo> {
        val pm = appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return resolved.mapNotNull { info ->
            val ai = info.activityInfo ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { ai.packageName }
            InstalledAppInfo(
                label = label,
                packageName = ai.packageName,
                activityName = ai.name
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.US) }
    }

    fun openRecentApp(index: Int = 0): DeviceToolOutcome {
        if (!hasUsageStatsPermission()) {
            return DeviceToolOutcome(
                success = false,
                title = "Usage Access Permission Needed",
                summary = "Grant 'App Usage Access' in Tools & Permissions so Mote can inspect recently used apps via UsageStatsManager.",
                badge = "NEEDS USAGE ACCESS"
            )
        }
        val recents = queryRecentUsageApps(hoursBack = 48)
        if (recents.isEmpty()) {
            return DeviceToolOutcome(
                success = false,
                title = "No Recent Apps Recorded",
                summary = "UsageStatsManager returned no recent launchable apps in the last 48 hours.",
                badge = "NO HISTORY"
            )
        }
        val target = recents[index.coerceIn(0, recents.lastIndex)]
        return launchPackageDirectly(target.packageName, target.label, badge = "RECENT APP LAUNCHED")
    }

    fun getRecentApps(limit: Int = 8): DeviceToolOutcome {
        val maxItems = limit.coerceIn(1, 15)
        if (!hasUsageStatsPermission()) {
            val installedSample = queryLaunchableApps().take(maxItems).map { "${it.label} (${it.packageName})" }
            return DeviceToolOutcome(
                success = true,
                title = "Usage Access Not Granted",
                summary = "Grant 'App Usage Access' in Tools & Permissions for UsageStats ranking. Showing ${installedSample.size} installed apps ready to launch:",
                badge = "NEEDS USAGE ACCESS",
                detailLines = installedSample
            )
        }
        val recents = queryRecentUsageApps(hoursBack = 24).take(maxItems)
        if (recents.isEmpty()) {
            return listInstalledApps(null)
        }
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val lines = recents.map { app ->
            val mins = (app.totalTimeInForegroundMs / 60_000L)
            val usageText = if (mins > 0L) "${mins}m active" else "<1m active"
            "${app.label} · last ${timeFmt.format(Date(app.lastTimeUsedMs))} ($usageText)"
        }
        return DeviceToolOutcome(
            success = true,
            title = "Recently Used Apps (${recents.size})",
            summary = "Ranked via Android UsageStatsManager over the last 24h.",
            badge = "USAGESTATS LIVE",
            detailLines = lines
        )
    }

    fun openApp(appQuery: String): DeviceToolOutcome {
        val clean = appQuery.trim()
        if (clean.isBlank()) {
            return DeviceToolOutcome(
                false,
                "App Name Required",
                "Specify which app to open (e.g., Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, Settings).",
                "MISSING PARAM"
            )
        }
        val normalized = normalizeQuery(clean)

        // Shortcut: "last app" / "recent app" / "previous app" via UsageStatsManager
        if (normalized in setOf("recent", "recentapp", "last", "lastapp", "previous", "previousapp", "lastused")) {
            return openRecentApp(0)
        }

        // Shortcut: System Settings
        if (normalized in setOf("settings", "androidsettings", "systemsettings")) {
            return openSettings("main")
        }

        // Shortcut: Camera
        if (normalized in setOf("camera", "cam", "photo", "photoscamera")) {
            return openCamera(mode = "photo")
        }

        val pm = appContext.packageManager

        // Tier 1: Direct match against Common App Catalog (Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, etc.)
        val catalogEntry = matchCommonAppCatalog(clean, normalized)
        if (catalogEntry != null) {
            for (pkg in catalogEntry.packageCandidates) {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    return launchIntentNow(
                        intent = launchIntent,
                        appLabel = resolvePackageLabel(pm, pkg).ifBlank { catalogEntry.canonicalName },
                        detail = pkg,
                        badge = "APP LAUNCHED"
                    )
                }
            }
        }

        // Tier 2: Installed launchable apps prioritized by UsageStats recency
        val recentRank = queryRecentUsageApps(hoursBack = 72)
            .mapIndexed { idx, summary -> summary.packageName to idx }
            .toMap()
        val installedApps = queryLaunchableApps()
            .sortedBy { recentRank[it.packageName] ?: Int.MAX_VALUE }

        val installedMatch = findBestInstalledApp(clean, normalized, installedApps)
        if (installedMatch != null) {
            return launchPackageDirectly(installedMatch.packageName, installedMatch.label)
        }

        // Tier 3: UsageStats recently used apps (even if not in cached launcher query)
        if (hasUsageStatsPermission()) {
            val recentMatch = queryRecentUsageApps(hoursBack = 168).firstOrNull { app ->
                val normLabel = normalizeQuery(app.label)
                normLabel == normalized || normLabel.contains(normalized) ||
                    app.packageName.lowercase(Locale.US).contains(normalized)
            }
            if (recentMatch != null) {
                return launchPackageDirectly(recentMatch.packageName, recentMatch.label, badge = "USAGESTATS LAUNCH")
            }
        }

        // Tier 4: Android Category / Action Intent fallback from Common App Catalog
        if (catalogEntry != null) {
            catalogEntry.categoryFallback?.let { cat ->
                try {
                    val catIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(catIntent)
                    return DeviceToolOutcome(
                        success = true,
                        title = "Opened ${catalogEntry.canonicalName}",
                        summary = "Launched default Android ${catalogEntry.canonicalName} app via system category intent.",
                        badge = "CATEGORY INTENT"
                    )
                } catch (_: Exception) {
                }
            }
            catalogEntry.actionFallback?.let { act ->
                try {
                    val actIntent = Intent(act).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    appContext.startActivity(actIntent)
                    return DeviceToolOutcome(
                        success = true,
                        title = "Opened ${catalogEntry.canonicalName}",
                        summary = "Launched ${catalogEntry.canonicalName} via Android intent.",
                        badge = "INTENT LAUNCH"
                    )
                } catch (_: Exception) {
                }
            }
            // Tier 5: Web App Fallback if native APK is not installed (e.g., YouTube/Spotify on emulator)
            catalogEntry.webFallbackUrl?.let { webUrl ->
                val webOutcome = openUrl(webUrl)
                if (webOutcome.success) {
                    return DeviceToolOutcome(
                        success = true,
                        title = "Opened ${catalogEntry.canonicalName} (Web)",
                        summary = "${catalogEntry.canonicalName} APK is not installed on this device; opened $webUrl in browser.",
                        badge = "WEB FALLBACK"
                    )
                }
            }
        }

        val sample = installedApps.take(6).joinToString(", ") { it.label }
        return DeviceToolOutcome(
            success = false,
            title = "App Not Found: $clean",
            summary = "No installed app matched '$clean'. Installed examples: $sample",
            badge = "NOT INSTALLED"
        )
    }

    fun listInstalledApps(filter: String? = null): DeviceToolOutcome {
        val recentRank = queryRecentUsageApps(hoursBack = 72)
            .mapIndexed { idx, summary -> summary.packageName to idx }
            .toMap()
        val apps = queryLaunchableApps().sortedBy { recentRank[it.packageName] ?: Int.MAX_VALUE }
        val q = filter?.trim()?.lowercase(Locale.US).orEmpty()
        val filtered = if (q.isBlank()) apps else apps.filter {
            it.label.lowercase(Locale.US).contains(q) || it.packageName.lowercase(Locale.US).contains(q)
        }
        val lines = filtered.take(14).map { app ->
            val recentTag = if (app.packageName in recentRank) " ★ recent" else ""
            "${app.label} (${app.packageName})$recentTag"
        }
        return DeviceToolOutcome(
            success = true,
            title = "Installed Apps (${filtered.size})",
            summary = if (filtered.isEmpty()) {
                "No launchable apps matched '$filter'."
            } else {
                val statsNote = if (recentRank.isNotEmpty()) " (recent UsageStats prioritized)" else ""
                "Found ${filtered.size} launchable app(s)$statsNote."
            },
            badge = if (recentRank.isNotEmpty()) "USAGESTATS + APPS" else "APP CATALOG",
            detailLines = lines
        )
    }

    fun shareContent(
        targetAppQuery: String?,
        mediaUriString: String?,
        textMessage: String?
    ): DeviceToolOutcome {
        val resolvedUri = mediaUriString
            ?.takeIf { it.isNotBlank() && !it.startsWith("$") }
            ?: lastSavedCaptureUri
        return appLauncherUtility.shareToTargetApp(
            targetAppQuery = targetAppQuery,
            mediaUriString = resolvedUri,
            textMessage = textMessage
        )
    }

    private fun launchPackageDirectly(
        packageName: String,
        label: String,
        badge: String = "APP LAUNCHED"
    ): DeviceToolOutcome {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return DeviceToolOutcome(
                success = false,
                title = "Cannot Launch $label",
                summary = "Package $packageName has no launchable activity.",
                badge = "NO LAUNCHER"
            )
        return launchIntentNow(launchIntent, label, packageName, badge)
    }

    private fun launchIntentNow(
        intent: Intent,
        appLabel: String,
        detail: String,
        badge: String
    ): DeviceToolOutcome {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            val overlayActive = SystemOverlayStateBus.ensureSystemOverlayRunning(appContext)
            SystemOverlayStateBus.publishAppContext(
                appLabel = appLabel,
                statusBanner = "Active on $appLabel"
            )
            DeviceToolOutcome(
                success = true,
                title = "Opened $appLabel",
                summary = "Launched $appLabel ($detail) immediately.",
                badge = badge,
                detailLines = listOfNotNull(
                    "Target package/handler: $detail",
                    if (overlayActive) "Animated Siri-style floating overlay active on top of $appLabel" else null
                )
            )
        } catch (e: Exception) {
            DeviceToolOutcome(
                success = false,
                title = "Launch Failed",
                summary = "Could not launch $appLabel: ${e.localizedMessage ?: "system blocked"}",
                badge = "ERROR"
            )
        }
    }

    private fun matchCommonAppCatalog(rawQuery: String, normalized: String): CommonAppCatalogEntry? {
        val lower = rawQuery.lowercase(Locale.US).trim()
        // Exact alias match first
        COMMON_APPS_CATALOG.firstOrNull { entry ->
            entry.aliases.any { alias ->
                alias.equals(lower, ignoreCase = true) || normalizeQuery(alias) == normalized
            }
        }?.let { return it }

        // Contains match
        COMMON_APPS_CATALOG.firstOrNull { entry ->
            entry.aliases.any { alias ->
                val na = normalizeQuery(alias)
                na.length >= 4 && (normalized.contains(na) || na.contains(normalized))
            }
        }?.let { return it }

        // Typo-tolerant Levenshtein match (e.g. "yutibe" -> "youtube", "chorme" -> "chrome")
        if (normalized.length >= 4) {
            val maxDist = if (normalized.length >= 6) 2 else 1
            var bestEntry: CommonAppCatalogEntry? = null
            var bestDist = maxDist + 1
            for (entry in COMMON_APPS_CATALOG) {
                for (alias in entry.aliases) {
                    val na = normalizeQuery(alias)
                    if (na.length < 4) continue
                    val dist = levenshteinDistance(normalized, na)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestEntry = entry
                    }
                }
            }
            if (bestEntry != null) return bestEntry
        }
        return null
    }

    private fun findBestInstalledApp(
        rawQuery: String,
        normalized: String,
        apps: List<InstalledAppInfo>
    ): InstalledAppInfo? {
        val lower = rawQuery.lowercase(Locale.US)
        apps.firstOrNull { it.label.equals(rawQuery, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.packageName.equals(rawQuery, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { normalizeQuery(it.label) == normalized }?.let { return it }
        apps.firstOrNull { it.label.lowercase(Locale.US).startsWith(lower) }?.let { return it }
        apps.firstOrNull { it.label.lowercase(Locale.US).contains(lower) }?.let { return it }
        apps.firstOrNull { it.packageName.lowercase(Locale.US).contains(normalized) }?.let { return it }

        if (normalized.length >= 4) {
            val maxDist = if (normalized.length >= 6) 2 else 1
            return apps.minByOrNull { levenshteinDistance(normalized, normalizeQuery(it.label)) }
                ?.takeIf { levenshteinDistance(normalized, normalizeQuery(it.label)) <= maxDist }
        }
        return null
    }

    private fun resolvePackageLabel(pm: PackageManager, packageName: String): String {
        return try {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(ai).toString().trim().ifBlank { packageName }
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun normalizeQuery(input: String): String =
        input.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }

    // -------------------------------------------------------------------------
    // 4. DEVICE & MAP NAVIGATION TOOLS
    // -------------------------------------------------------------------------

    fun navigateSystem(action: String): DeviceToolOutcome {
        val norm = action.trim().lowercase(Locale.US)
        val accessibility = MoteAccessibilityService.instance

        if ((norm == "home" || norm == "go_home") && accessibility == null) {
            return try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(homeIntent)
                DeviceToolOutcome(true, "Navigated Home", "Returned to Android Home Screen.", "NAVIGATED")
            } catch (e: Exception) {
                DeviceToolOutcome(false, "Home Action Failed", e.localizedMessage ?: "Failed", "ERROR")
            }
        }

        if (accessibility == null) {
            return DeviceToolOutcome(
                success = false,
                title = "Accessibility Needed",
                summary = "Enable Mote Screen Assistant in Tools & Permissions to execute system '$norm' navigation.",
                badge = "NEEDS ACCESSIBILITY"
            )
        }

        val ok = accessibility.performNavigationAction(norm)
        return if (ok) {
            DeviceToolOutcome(
                success = true,
                title = "System Action: ${norm.replace('_', ' ').replaceFirstChar { it.uppercase() }}",
                summary = "Executed Android global navigation '$norm' in real time.",
                badge = "NAVIGATED"
            )
        } else {
            DeviceToolOutcome(
                success = false,
                title = "Unsupported Navigation",
                summary = "Action '$norm' was not accepted on this Android version.",
                badge = "FAILED"
            )
        }
    }

    fun navigateMaps(destination: String, mode: String? = null): DeviceToolOutcome {
        val dest = destination.trim()
        if (dest.isBlank()) {
            return DeviceToolOutcome(false, "Destination Required", "Specify a destination address or place name.", "MISSING PARAM")
        }
        val modeCode = when (mode?.trim()?.lowercase(Locale.US)) {
            "walk", "walking", "w" -> "w"
            "bike", "bicycle", "cycling", "b" -> "b"
            "transit", "bus", "train", "r" -> "r"
            else -> "d"
        }
        val encoded = Uri.encode(dest)
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded&mode=$modeCode")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(navIntent)
            DeviceToolOutcome(
                success = true,
                title = "Navigating to $dest",
                summary = "Started turn-by-turn navigation to $dest.",
                badge = "MAPS NAV"
            )
        } catch (_: Exception) {
            try {
                val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(geoIntent)
                DeviceToolOutcome(
                    success = true,
                    title = "Opened Map for $dest",
                    summary = "Opened maps location view for $dest.",
                    badge = "MAPS"
                )
            } catch (_: Exception) {
                openUrl("https://www.google.com/maps/search/?api=1&query=$encoded")
            }
        }
    }

    fun openUrl(rawUrl: String): DeviceToolOutcome {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return DeviceToolOutcome(false, "URL Required", "Provide a website URL to open.", "MISSING PARAM")
        }
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            val overlayActive = SystemOverlayStateBus.ensureSystemOverlayRunning(appContext)
            SystemOverlayStateBus.publishAppContext("Browser", "Browsing ${Uri.parse(normalized).host ?: "web"}")
            DeviceToolOutcome(
                success = true,
                title = "Opened Web Link",
                summary = "Opened $normalized in browser.",
                badge = "URL OPENED",
                detailLines = listOfNotNull(
                    normalized,
                    if (overlayActive) "Siri-style floating agent overlay active over browser" else null
                )
            )
        } catch (e: Exception) {
            DeviceToolOutcome(
                success = false,
                title = "Browser Unavailable",
                summary = "Could not open $normalized (${e.localizedMessage ?: "no handler"}).",
                badge = "ERROR"
            )
        }
    }

    fun searchWeb(query: String): DeviceToolOutcome {
        val q = query.trim()
        if (q.isBlank()) {
            return DeviceToolOutcome(false, "Query Required", "Provide a search query.", "MISSING PARAM")
        }
        return searchInApp(appName = "Chrome", query = q)
    }

    /**
     * Opens a specific target app (Chrome, YouTube, Maps, Play Store, etc.) directly with a live search query
     * and activates the system-wide animated Siri-like overlay right on top of the app.
     */
    fun searchInApp(appName: String, query: String): DeviceToolOutcome {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return openApp(appName.ifBlank { "Chrome" })
        }
        val normApp = appName.trim().lowercase(Locale.US)
        val encoded = Uri.encode(cleanQuery)

        val (targetUrl, targetPkg, displayLabel) = when {
            normApp.contains("youtube") || normApp == "yt" ->
                Triple("https://www.youtube.com/results?search_query=$encoded", "com.google.android.youtube", "YouTube")
            normApp.contains("map") ->
                return navigateMaps(cleanQuery, mode = null)
            normApp.contains("play") || normApp.contains("store") ->
                Triple("https://play.google.com/store/search?q=$encoded&c=apps", "com.android.vending", "Play Store")
            normApp.contains("amazon") ->
                Triple("https://www.amazon.com/s?k=$encoded", "com.amazon.mShop.android.shopping", "Amazon")
            normApp.contains("spotify") ->
                Triple("https://open.spotify.com/search/$encoded", "com.spotify.music", "Spotify")
            else ->
                Triple("https://www.google.com/search?q=$encoded", "com.android.chrome", "Chrome")
        }

        val targetedIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val launchedTargeted = try {
            appContext.startActivity(targetedIntent)
            true
        } catch (_: Exception) {
            false
        }

        if (!launchedTargeted) {
            val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                appContext.startActivity(genericIntent)
            } catch (e: Exception) {
                return DeviceToolOutcome(
                    success = false,
                    title = "Search Failed",
                    summary = "Could not open $displayLabel search for '$cleanQuery': ${e.localizedMessage ?: "no handler"}",
                    badge = "ERROR"
                )
            }
        }

        val overlayActive = SystemOverlayStateBus.ensureSystemOverlayRunning(appContext)
        SystemOverlayStateBus.publishAppContext(
            appLabel = displayLabel,
            statusBanner = "$displayLabel · Searching \"$cleanQuery\""
        )
        return DeviceToolOutcome(
            success = true,
            title = "Searched $displayLabel",
            summary = "Opened $displayLabel and searched for \"$cleanQuery\" in real time.",
            badge = "${displayLabel.uppercase(Locale.US)} SEARCH",
            detailLines = listOfNotNull(
                "Query: $cleanQuery",
                "Handler: ${if (launchedTargeted) targetPkg else "System default handler"}",
                if (overlayActive) "Animated Siri-style Mote overlay active over $displayLabel" else "Grant Overlay permission in Tools to keep the animated orb floating over $displayLabel"
            )
        )
    }

    /**
     * Play-compliant phone dialer launch using `ACTION_DIAL` (zero restricted CALL_PHONE permission),
     * opening the Android Phone app pre-filled with the requested number and activating the animated
     * Siri-like floating overlay ready for live STT <-> AI <-> TTS voice relay.
     */
    fun dialPhoneNumber(phoneNumber: String, contactLabel: String? = null): DeviceToolOutcome {
        val cleaned = phoneNumber.replace(Regex("[^0-9+*#]"), "").trim()
        val uri = if (cleaned.isNotBlank()) Uri.parse("tel:$cleaned") else Uri.parse("tel:")
        val intent = Intent(Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            val overlayActive = SystemOverlayStateBus.ensureSystemOverlayRunning(appContext)
            val labelText = contactLabel?.takeIf { it.isNotBlank() }?.let { "$it ($cleaned)" }
                ?: cleaned.ifBlank { "Phone Dialer" }
            SystemOverlayStateBus.publishAppContext(
                appLabel = "Phone",
                statusBanner = "Phone Dialer · $labelText"
            )
            DeviceToolOutcome(
                success = true,
                title = if (cleaned.isNotBlank()) "Dialing $labelText" else "Opened Phone Dialer",
                summary = if (cleaned.isNotBlank()) {
                    "Opened Android Phone pre-filled with $labelText (ACTION_DIAL). Tap Call and put on speakerphone for live Mote voice relay."
                } else {
                    "Opened Android Phone dialer ready for your call."
                },
                badge = "PHONE DIALER",
                detailLines = listOfNotNull(
                    if (cleaned.isNotBlank()) "Number: $cleaned" else null,
                    "Play-compliant ACTION_DIAL intent",
                    if (overlayActive) "Siri-style Mote overlay floating over Phone" else null
                )
            )
        } catch (e: Exception) {
            DeviceToolOutcome(
                success = false,
                title = "Phone Dialer Unavailable",
                summary = "No phone dialer app responded on this device (${e.localizedMessage ?: "missing dialer"}).",
                badge = "ERROR"
            )
        }
    }

    fun openSettings(section: String): DeviceToolOutcome {
        val norm = section.trim().lowercase(Locale.US)
        val action = when {
            norm.contains("wifi") || norm.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            norm.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            norm.contains("display") || norm.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
            norm.contains("sound") || norm.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
            norm.contains("battery") || norm.contains("power") -> Intent.ACTION_POWER_USAGE_SUMMARY
            norm.contains("location") || norm.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            norm.contains("access") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            norm.contains("usage") -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            norm.contains("app") -> Settings.ACTION_APPLICATION_SETTINGS
            norm.contains("date") || norm.contains("time") -> Settings.ACTION_DATE_SETTINGS
            norm.contains("storage") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            norm.contains("security") -> Settings.ACTION_SECURITY_SETTINGS
            norm.contains("notif") -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            appContext.startActivity(intent)
            DeviceToolOutcome(
                success = true,
                title = "Opened Android Settings",
                summary = "Navigated directly to ${norm.ifBlank { "system" }} settings.",
                badge = "SETTINGS"
            )
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                appContext.startActivity(fallback)
                DeviceToolOutcome(true, "Opened Android Settings", "Opened main Android Settings screen.", "SETTINGS")
            } catch (e: Exception) {
                DeviceToolOutcome(false, "Settings Error", e.localizedMessage ?: "Failed to open settings", "ERROR")
            }
        }
    }

    fun navigateAppTab(tab: String): DeviceToolOutcome {
        val norm = tab.trim().lowercase(Locale.US)
        val canonical = when {
            norm.contains("chat") -> "chat"
            norm.contains("gen") || norm.contains("studio") || norm.contains("ui") -> "genui"
            norm.contains("model") || norm.contains("hub") -> "models"
            norm.contains("tool") || norm.contains("perm") || norm.contains("sec") -> "tools"
            norm.contains("set") -> "settings"
            else -> norm
        }
        val handler = onNavigateInAppTab
        return if (handler != null) {
            mainHandler.post { handler(canonical) }
            DeviceToolOutcome(
                success = true,
                title = "Switched to ${canonical.replaceFirstChar { it.uppercase() }}",
                summary = "Navigated Mote directly to the $canonical tab.",
                badge = "IN-APP NAV"
            )
        } else {
            DeviceToolOutcome(
                success = false,
                title = "Navigation Ready",
                summary = "Open Mote main window to switch to $canonical.",
                badge = "STANDBY"
            )
        }
    }

    fun buildDeviceContextForAi(): String {
        val accessibilityActive = MoteAccessibilityService.instance != null
        val usageStatsGranted = hasUsageStatsPermission()
        val recentUsage = if (usageStatsGranted) queryRecentUsageApps(hoursBack = 24) else emptyList()
        val recentApps = recentUsage.take(5).joinToString(", ") { it.label }
        val recentPkgs = recentUsage.map { it.packageName }.toSet()
        val manifestSummary = appLauncherUtility.buildCompactManifestForAi(recentPkgs, maxApps = 24)
        val mpReady = MediaProjectionConsentBridge.hasCachedConsent()
        val fgPkg = MoteAccessibilityService.currentPackageName ?: appContext.packageName
        return buildString {
            appendLine("Device Automation Status:")
            appendLine("- Flashlight: ${if (torchEnabledState) "ON" else "OFF"}")
            appendLine("- MediaProjection Screen Capture: ${if (mpReady) "PRE-AUTHORIZED (ready)" else "PROMPT ON DEMAND (Android explicit consent dialog will appear when takeScreenshot or captureAndAnalyzeScreen is called)"}")
            appendLine("- App UsageStats Access: ${if (usageStatsGranted) "GRANTED${if (recentApps.isNotBlank()) " · Recent apps: $recentApps" else ""}" else "NOT GRANTED (common & installed apps can still be opened directly via openApp)"}")
            appendLine("- $manifestSummary")
            appendLine("- Accessibility Service: ${if (accessibilityActive) "ACTIVE" else "INACTIVE"}")
            appendLine("- Foreground Package: $fgPkg")
        }.trim()
    }

    companion object {
        @Volatile
        private var foregroundActivityRef: WeakReference<Activity>? = null

        @Volatile
        var onNavigateInAppTab: ((String) -> Unit)? = null

        fun attachForegroundActivity(activity: Activity) {
            foregroundActivityRef = WeakReference(activity)
        }

        fun detachForegroundActivity(activity: Activity) {
            if (foregroundActivityRef?.get() === activity) {
                foregroundActivityRef = null
            }
        }

        private val COMMON_APPS_CATALOG = listOf(
            CommonAppCatalogEntry(
                canonicalName = "Chrome",
                aliases = listOf("chrome", "google chrome", "browser", "web browser", "internet"),
                packageCandidates = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx"),
                categoryFallback = Intent.CATEGORY_APP_BROWSER,
                webFallbackUrl = "https://www.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "YouTube",
                aliases = listOf("youtube", "yutibe", "yutube", "youtbe", "yt", "you tube"),
                packageCandidates = listOf("com.google.android.youtube"),
                webFallbackUrl = "https://m.youtube.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "YouTube Music",
                aliases = listOf("youtube music", "yt music", "ytmusic"),
                packageCandidates = listOf("com.google.android.apps.youtube.music"),
                categoryFallback = Intent.CATEGORY_APP_MUSIC,
                webFallbackUrl = "https://music.youtube.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Gmail",
                aliases = listOf("gmail", "google mail", "email", "mail"),
                packageCandidates = listOf("com.google.android.gm"),
                categoryFallback = Intent.CATEGORY_APP_EMAIL,
                webFallbackUrl = "https://mail.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Maps",
                aliases = listOf("maps", "google maps", "gmaps", "navigation"),
                packageCandidates = listOf("com.google.android.apps.maps"),
                categoryFallback = Intent.CATEGORY_APP_MAPS,
                webFallbackUrl = "https://maps.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Photos",
                aliases = listOf("photos", "google photos", "gallery", "pictures"),
                packageCandidates = listOf("com.google.android.apps.photos"),
                categoryFallback = Intent.CATEGORY_APP_GALLERY
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Drive",
                aliases = listOf("drive", "google drive", "gdrive"),
                packageCandidates = listOf("com.google.android.apps.docs"),
                webFallbackUrl = "https://drive.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Docs",
                aliases = listOf("docs", "google docs"),
                packageCandidates = listOf("com.google.android.apps.docs.editors.docs"),
                webFallbackUrl = "https://docs.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Sheets",
                aliases = listOf("sheets", "google sheets"),
                packageCandidates = listOf("com.google.android.apps.docs.editors.sheets"),
                webFallbackUrl = "https://sheets.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Calendar",
                aliases = listOf("calendar", "google calendar", "gcal"),
                packageCandidates = listOf("com.google.android.calendar"),
                categoryFallback = Intent.CATEGORY_APP_CALENDAR,
                webFallbackUrl = "https://calendar.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Play Store",
                aliases = listOf("play store", "google play", "playstore", "app store", "store"),
                packageCandidates = listOf("com.android.vending"),
                categoryFallback = Intent.CATEGORY_APP_MARKET
            ),
            CommonAppCatalogEntry(
                canonicalName = "Messages",
                aliases = listOf("messages", "sms", "text", "texting", "messaging"),
                packageCandidates = listOf("com.google.android.apps.messaging"),
                categoryFallback = Intent.CATEGORY_APP_MESSAGING
            ),
            CommonAppCatalogEntry(
                canonicalName = "Phone",
                aliases = listOf("phone", "dialer", "call", "calls"),
                packageCandidates = listOf("com.google.android.dialer", "com.android.dialer"),
                actionFallback = Intent.ACTION_DIAL
            ),
            CommonAppCatalogEntry(
                canonicalName = "Contacts",
                aliases = listOf("contacts", "people", "address book"),
                packageCandidates = listOf("com.google.android.contacts", "com.android.contacts"),
                categoryFallback = Intent.CATEGORY_APP_CONTACTS
            ),
            CommonAppCatalogEntry(
                canonicalName = "Clock",
                aliases = listOf("clock", "android clock", "alarm clock"),
                packageCandidates = listOf("com.google.android.deskclock", "com.android.deskclock")
            ),
            CommonAppCatalogEntry(
                canonicalName = "Calculator",
                aliases = listOf("calculator", "calc"),
                packageCandidates = listOf("com.google.android.calculator", "com.android.calculator2"),
                categoryFallback = Intent.CATEGORY_APP_CALCULATOR
            ),
            CommonAppCatalogEntry(
                canonicalName = "Files",
                aliases = listOf("files", "file manager", "downloads", "my files"),
                packageCandidates = listOf("com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui")
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Keep",
                aliases = listOf("keep", "google keep", "notes"),
                packageCandidates = listOf("com.google.android.keep"),
                webFallbackUrl = "https://keep.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Translate",
                aliases = listOf("translate", "google translate"),
                packageCandidates = listOf("com.google.android.apps.translate"),
                webFallbackUrl = "https://translate.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Google Meet",
                aliases = listOf("meet", "google meet", "duo"),
                packageCandidates = listOf("com.google.android.apps.tachyon"),
                webFallbackUrl = "https://meet.google.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "WhatsApp",
                aliases = listOf("whatsapp", "whats app", "wa"),
                packageCandidates = listOf("com.whatsapp", "com.whatsapp.w4b"),
                webFallbackUrl = "https://web.whatsapp.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Instagram",
                aliases = listOf("instagram", "insta", "ig"),
                packageCandidates = listOf("com.instagram.android"),
                webFallbackUrl = "https://www.instagram.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "TikTok",
                aliases = listOf("tiktok", "tik tok"),
                packageCandidates = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
                webFallbackUrl = "https://www.tiktok.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "X / Twitter",
                aliases = listOf("twitter", "x", "x app", "tweets"),
                packageCandidates = listOf("com.twitter.android"),
                webFallbackUrl = "https://x.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Spotify",
                aliases = listOf("spotify", "music"),
                packageCandidates = listOf("com.spotify.music"),
                categoryFallback = Intent.CATEGORY_APP_MUSIC,
                webFallbackUrl = "https://open.spotify.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Telegram",
                aliases = listOf("telegram", "tg"),
                packageCandidates = listOf("org.telegram.messenger"),
                webFallbackUrl = "https://web.telegram.org"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Discord",
                aliases = listOf("discord"),
                packageCandidates = listOf("com.discord"),
                webFallbackUrl = "https://discord.com/app"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Slack",
                aliases = listOf("slack"),
                packageCandidates = listOf("com.Slack"),
                webFallbackUrl = "https://app.slack.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Reddit",
                aliases = listOf("reddit"),
                packageCandidates = listOf("com.reddit.frontpage"),
                webFallbackUrl = "https://www.reddit.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Netflix",
                aliases = listOf("netflix"),
                packageCandidates = listOf("com.netflix.mediaclient"),
                webFallbackUrl = "https://www.netflix.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Snapchat",
                aliases = listOf("snapchat", "snap"),
                packageCandidates = listOf("com.snapchat.android"),
                webFallbackUrl = "https://web.snapchat.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Facebook",
                aliases = listOf("facebook", "fb"),
                packageCandidates = listOf("com.facebook.katana"),
                webFallbackUrl = "https://m.facebook.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Messenger",
                aliases = listOf("messenger", "facebook messenger"),
                packageCandidates = listOf("com.facebook.orca"),
                webFallbackUrl = "https://www.messenger.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "LinkedIn",
                aliases = listOf("linkedin", "linked in"),
                packageCandidates = listOf("com.linkedin.android"),
                webFallbackUrl = "https://www.linkedin.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Uber",
                aliases = listOf("uber"),
                packageCandidates = listOf("com.ubercab"),
                webFallbackUrl = "https://m.uber.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Amazon",
                aliases = listOf("amazon", "amazon shopping"),
                packageCandidates = listOf("com.amazon.mShop.android.shopping"),
                webFallbackUrl = "https://www.amazon.com"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Zoom",
                aliases = listOf("zoom", "zoom meetings"),
                packageCandidates = listOf("us.zoom.videomeetings"),
                webFallbackUrl = "https://zoom.us"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Notion",
                aliases = listOf("notion"),
                packageCandidates = listOf("notion.id"),
                webFallbackUrl = "https://www.notion.so"
            ),
            CommonAppCatalogEntry(
                canonicalName = "Firefox",
                aliases = listOf("firefox", "mozilla"),
                packageCandidates = listOf("org.mozilla.firefox"),
                categoryFallback = Intent.CATEGORY_APP_BROWSER
            ),
            CommonAppCatalogEntry(
                canonicalName = "Brave",
                aliases = listOf("brave", "brave browser"),
                packageCandidates = listOf("com.brave.browser"),
                categoryFallback = Intent.CATEGORY_APP_BROWSER
            ),
            CommonAppCatalogEntry(
                canonicalName = "Microsoft Edge",
                aliases = listOf("edge", "microsoft edge"),
                packageCandidates = listOf("com.microsoft.emmx"),
                categoryFallback = Intent.CATEGORY_APP_BROWSER
            ),
            CommonAppCatalogEntry(
                canonicalName = "GitHub",
                aliases = listOf("github", "git hub"),
                packageCandidates = listOf("com.github.android"),
                webFallbackUrl = "https://github.com"
            )
        )
    }
}

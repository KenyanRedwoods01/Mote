package com.mote.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mote.app.data.db.LocalModelDatabase
import com.mote.app.data.db.LocalModelEntity
import com.mote.app.data.db.ModelStudioPrefsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager service that inspects downloaded local AI models in Room,
 * checks their remote host headers (ETag, Last-Modified, Content-Length), and
 * automatically triggers [ModelDownloadWorker] when a newer model version is published.
 */
class ModelUpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private data class RemoteVersionHead(
        val etag: String?,
        val lastModified: String?,
        val contentLength: Long
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = LocalModelDatabase.getInstance(applicationContext).localModelDao()
        val prefs = dao.getPrefsOnce() ?: ModelStudioPrefsEntity()
        val autoTrigger = inputData.getBoolean(KEY_AUTO_DOWNLOAD, true)
        val now = System.currentTimeMillis()

        val downloadedModels = dao.getDownloadedModelsOnce().filter { model ->
            model.downloadUrl.isNotBlank() &&
                !model.localFilePath.isNullOrBlank() &&
                File(model.localFilePath).let { it.exists() && it.length() > 1024L }
        }

        if (downloadedModels.isEmpty()) {
            val msg = "No downloaded local models with remote URLs to check."
            dao.upsertPrefs(
                prefs.copy(
                    lastModelUpdateCheckEpochMs = now,
                    lastModelUpdateSummary = msg
                )
            )
            return@withContext Result.success(
                workDataOf(
                    KEY_CHECKED_COUNT to 0,
                    KEY_UPDATES_FOUND to 0,
                    KEY_DOWNLOADS_TRIGGERED to 0,
                    KEY_SUMMARY to msg
                )
            )
        }

        val ecoRestricted = prefs.ecoInferenceEnabled && isBatteryBelowEcoThreshold()
        var checkedCount = 0
        var updatesFound = 0
        var downloadsTriggered = 0
        val updatedNames = mutableListOf<String>()
        var networkErrors = 0

        for (model in downloadedModels) {
            if (isStopped) break
            checkedCount++
            val head = fetchRemoteVersionHead(model.downloadUrl)
            if (head == null) {
                networkErrors++
                dao.recordUpdateCheck(
                    modelId = model.id,
                    remoteEtag = model.remoteEtag,
                    remoteLastModified = model.remoteLastModified,
                    remoteContentLength = model.remoteContentLength,
                    checkedAtEpochMs = now,
                    updateAvailable = model.updateAvailable,
                    updateStatusNote = "Host unreachable during last check"
                )
                continue
            }

            val hasUpdate = detectNewerVersion(model, head)
            if (!hasUpdate) {
                // Baseline or up-to-date: persist current remote headers in Room for future comparisons
                dao.recordUpdateCheck(
                    modelId = model.id,
                    remoteEtag = head.etag ?: model.remoteEtag,
                    remoteLastModified = head.lastModified ?: model.remoteLastModified,
                    remoteContentLength = if (head.contentLength > 0L) head.contentLength else model.remoteContentLength.coerceAtLeast(model.downloadedBytes),
                    checkedAtEpochMs = now,
                    updateAvailable = false,
                    updateStatusNote = "Up to date"
                )
            } else {
                updatesFound++
                updatedNames += model.displayName
                val note = if (autoTrigger && !ecoRestricted) {
                    "Newer version detected · Background update queued"
                } else if (ecoRestricted) {
                    "Update available · Deferred by Eco-Inference (<20% battery)"
                } else {
                    "Newer model weights available"
                }
                dao.recordUpdateCheck(
                    modelId = model.id,
                    remoteEtag = model.remoteEtag,
                    remoteLastModified = model.remoteLastModified,
                    remoteContentLength = model.remoteContentLength,
                    checkedAtEpochMs = now,
                    updateAvailable = true,
                    updateStatusNote = note
                )

                if (autoTrigger && !ecoRestricted) {
                    enqueueUpdateDownload(model, prefs)
                    downloadsTriggered++
                }
            }
        }

        val summary = when {
            updatesFound > 0 && downloadsTriggered > 0 ->
                "Update detected for ${updatedNames.joinToString(", ")} · Queued $downloadsTriggered download(s)."
            updatesFound > 0 && ecoRestricted ->
                "Found $updatesFound model update(s) (${updatedNames.joinToString(", ")}) · Deferred by Eco-Inference."
            updatesFound > 0 ->
                "Found $updatesFound update(s) ready: ${updatedNames.joinToString(", ")}."
            networkErrors == checkedCount ->
                "Could not reach model host for $checkedCount model(s). Will retry automatically."
            else ->
                "All $checkedCount downloaded model(s) are up to date."
        }

        dao.upsertPrefs(
            prefs.copy(
                lastModelUpdateCheckEpochMs = now,
                lastModelUpdateSummary = summary
            )
        )

        Result.success(
            workDataOf(
                KEY_CHECKED_COUNT to checkedCount,
                KEY_UPDATES_FOUND to updatesFound,
                KEY_DOWNLOADS_TRIGGERED to downloadsTriggered,
                KEY_SUMMARY to summary
            )
        )
    }

    private fun detectNewerVersion(model: LocalModelEntity, head: RemoteVersionHead): Boolean {
        val localEtag = model.remoteEtag?.trim()?.ifBlank { null }
        val remoteEtag = head.etag?.trim()?.ifBlank { null }
        if (localEtag != null && remoteEtag != null && localEtag != remoteEtag) {
            return true
        }

        val localLastMod = model.remoteLastModified?.trim()?.ifBlank { null }
        val remoteLastMod = head.lastModified?.trim()?.ifBlank { null }
        if (localLastMod != null && remoteLastMod != null && localLastMod != remoteLastMod) {
            return true
        }

        val expectedLocalLength = if (model.remoteContentLength > 0L) model.remoteContentLength else model.downloadedBytes
        if (expectedLocalLength > 0L && head.contentLength > 0L && head.contentLength != expectedLocalLength) {
            return true
        }

        return false
    }

    private fun enqueueUpdateDownload(model: LocalModelEntity, prefs: ModelStudioPrefsEntity) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(prefs.requireBatteryNotLow)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to model.id,
                    ModelDownloadWorker.KEY_DOWNLOAD_URL to model.downloadUrl,
                    ModelDownloadWorker.KEY_FILE_NAME to model.fileName,
                    ModelDownloadWorker.KEY_APPROX_SIZE_MB to model.approxSizeMb,
                    ModelDownloadWorker.KEY_FORCE_FRESH to true
                )
            )
            .addTag(ModelDownloadWorker.TAG_MODEL_DOWNLOAD)
            .addTag("model_id:${model.id}")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ModelDownloadWorker.uniqueWorkName(model.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun fetchRemoteVersionHead(startUrl: String): RemoteVersionHead? {
        return try {
            val headConn = openHeadFollowingRedirects(startUrl, useRangeFallback = false)
            try {
                val code = headConn.responseCode
                if (code in 200..299) {
                    return RemoteVersionHead(
                        etag = headConn.getHeaderField("ETag")?.trim()?.ifBlank { null },
                        lastModified = headConn.getHeaderField("Last-Modified")?.trim()?.ifBlank { null },
                        contentLength = headConn.contentLengthLong
                    )
                }
            } finally {
                headConn.disconnect()
            }

            // Fallback to a 1-byte Range GET if host rejects HEAD (405 / 403)
            val rangeConn = openHeadFollowingRedirects(startUrl, useRangeFallback = true)
            try {
                val code = rangeConn.responseCode
                if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                    val contentRange = rangeConn.getHeaderField("Content-Range")
                    val totalFromRange = contentRange
                        ?.substringAfter('/', missingDelimiterValue = "")
                        ?.toLongOrNull()
                        ?: rangeConn.contentLengthLong
                    return RemoteVersionHead(
                        etag = rangeConn.getHeaderField("ETag")?.trim()?.ifBlank { null },
                        lastModified = rangeConn.getHeaderField("Last-Modified")?.trim()?.ifBlank { null },
                        contentLength = totalFromRange
                    )
                }
                null
            } finally {
                rangeConn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openHeadFollowingRedirects(startUrl: String, useRangeFallback: Boolean): HttpURLConnection {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = if (useRangeFallback) "GET" else "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "MoteAndroidLLMClient/1.0")
                if (useRangeFallback) {
                    setRequestProperty("Range", "bytes=0-0")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IllegalStateException("Redirect missing Location header")
                currentUrl = URL(URL(currentUrl), next).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many HTTP redirects while checking model updates")
    }

    private fun isBatteryBelowEcoThreshold(): Boolean {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || charging) return false
        val pct = (level * 100) / scale
        return pct < LocalModelManager.ECO_BATTERY_THRESHOLD_PCT
    }

    companion object {
        const val PERIODIC_WORK_NAME = "mote_periodic_model_update_check"
        const val IMMEDIATE_WORK_NAME = "mote_immediate_model_update_check"
        const val TAG_MODEL_UPDATE_CHECK = "mote_model_update_check"

        const val KEY_AUTO_DOWNLOAD = "auto_download"
        const val KEY_CHECKED_COUNT = "checked_count"
        const val KEY_UPDATES_FOUND = "updates_found"
        const val KEY_DOWNLOADS_TRIGGERED = "downloads_triggered"
        const val KEY_SUMMARY = "summary"

        fun schedulePeriodic(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val periodicRequest = PeriodicWorkRequestBuilder<ModelUpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_AUTO_DOWNLOAD to true))
                .addTag(TAG_MODEL_UPDATE_CHECK)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
        }

        fun enqueueImmediateCheck(context: Context, autoDownload: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ModelUpdateCheckWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_AUTO_DOWNLOAD to autoDownload))
                .addTag(TAG_MODEL_UPDATE_CHECK)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

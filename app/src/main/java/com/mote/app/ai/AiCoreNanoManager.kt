package com.mote.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AiCoreAvailabilityState(val label: String) {
    CHECKING("Checking AICore..."),
    AVAILABLE("Gemini Nano Ready"),
    DOWNLOADABLE("Downloadable via AICore"),
    DOWNLOADING("Downloading Gemini Nano..."),
    UNAVAILABLE("Unavailable on this device"),
    ERROR("AICore check error")
}

data class AiCoreStatusSnapshot(
    val state: AiCoreAvailabilityState = AiCoreAvailabilityState.CHECKING,
    val detail: String = "Checking Android AICore / Gemini Nano on-device support...",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val lastCheckedEpochMs: Long = 0L
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val isReadyForOnDevice: Boolean
        get() = state == AiCoreAvailabilityState.AVAILABLE
}

/**
 * Manages real Android AICore / Gemini Nano on-device availability checks and model download
 * orchestration via Google's official ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`).
 *
 * Gracefully degrades to [AiCoreAvailabilityState.UNAVAILABLE] on emulators or devices without
 * AICore hardware support without throwing or disrupting cloud/LiteRT-LM inference.
 */
class AiCoreNanoManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var promptClient: GenerativeModel? = null

    private val _status = MutableStateFlow(AiCoreStatusSnapshot())
    val status: StateFlow<AiCoreStatusSnapshot> = _status.asStateFlow()

    init {
        refreshStatus()
    }

    private fun getOrCreateClient(): GenerativeModel? {
        return promptClient ?: runCatching {
            Generation.getClient().also { promptClient = it }
        }.getOrNull()
    }

    fun refreshStatus() {
        scope.launch {
            checkStatusSuspend()
        }
    }

    suspend fun checkStatusSuspend(): AiCoreStatusSnapshot {
        _status.value = _status.value.copy(
            state = AiCoreAvailabilityState.CHECKING,
            detail = "Querying Android AICore / ML Kit GenAI Prompt API..."
        )
        val client = getOrCreateClient()
        if (client == null) {
            val snap = AiCoreStatusSnapshot(
                state = AiCoreAvailabilityState.UNAVAILABLE,
                detail = "Android AICore is not present on this device. Cloud Gemini & LiteRT-LM remain active.",
                lastCheckedEpochMs = System.currentTimeMillis()
            )
            _status.value = snap
            return snap
        }
        return try {
            val featureStatus = client.checkStatus()
            val snap = when (featureStatus) {
                FeatureStatus.AVAILABLE -> AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.AVAILABLE,
                    detail = "Gemini Nano is installed in Android AICore and ready for Firebase AI Logic on-device hybrid inference.",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
                FeatureStatus.DOWNLOADABLE -> AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.DOWNLOADABLE,
                    detail = "This device supports Gemini Nano via AICore. Tap Download Nano to install the on-device model.",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
                FeatureStatus.DOWNLOADING -> AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.DOWNLOADING,
                    detail = "Android AICore is currently downloading Gemini Nano in the background.",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
                else -> AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.UNAVAILABLE,
                    detail = "Gemini Nano via AICore is unsupported on this hardware/OS. Firebase Hybrid SDK will automatically route to Cloud Gemini or LiteRT-LM.",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
            }
            _status.value = snap
            snap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = e.localizedMessage?.take(140) ?: "AICore service unavailable on this device."
            val snap = AiCoreStatusSnapshot(
                state = AiCoreAvailabilityState.UNAVAILABLE,
                detail = "AICore unavailable ($msg). Firebase Hybrid SDK will cleanly use Cloud Gemini or LiteRT-LM.",
                lastCheckedEpochMs = System.currentTimeMillis()
            )
            _status.value = snap
            snap
        }
    }

    fun triggerGeminiNanoDownload() {
        scope.launch {
            val client = getOrCreateClient()
            if (client == null) {
                _status.value = AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.UNAVAILABLE,
                    detail = "Android AICore is not installed on this device.",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
                return@launch
            }
            try {
                var expectedTotalBytes = 0L
                _status.value = AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.DOWNLOADING,
                    detail = "Requesting Gemini Nano download from Android AICore...",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
                client.download().collect { dl ->
                    when (dl) {
                        is DownloadStatus.DownloadStarted -> {
                            expectedTotalBytes = dl.bytesToDownload
                            _status.value = AiCoreStatusSnapshot(
                                state = AiCoreAvailabilityState.DOWNLOADING,
                                detail = "Downloading Gemini Nano (${formatMb(expectedTotalBytes)})...",
                                bytesDownloaded = 0L,
                                totalBytes = expectedTotalBytes,
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        }
                        is DownloadStatus.DownloadProgress -> {
                            _status.value = AiCoreStatusSnapshot(
                                state = AiCoreAvailabilityState.DOWNLOADING,
                                detail = "Downloading Gemini Nano: ${formatMb(dl.totalBytesDownloaded)}" +
                                    if (expectedTotalBytes > 0L) " / ${formatMb(expectedTotalBytes)}" else "",
                                bytesDownloaded = dl.totalBytesDownloaded,
                                totalBytes = expectedTotalBytes,
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            _status.value = AiCoreStatusSnapshot(
                                state = AiCoreAvailabilityState.AVAILABLE,
                                detail = "Gemini Nano download complete and ready for Firebase AI Logic hybrid inference.",
                                bytesDownloaded = expectedTotalBytes,
                                totalBytes = expectedTotalBytes,
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _status.value = AiCoreStatusSnapshot(
                                state = AiCoreAvailabilityState.ERROR,
                                detail = "Gemini Nano download failed: ${dl.e.localizedMessage ?: "AICore rejected download."}",
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.value = AiCoreStatusSnapshot(
                    state = AiCoreAvailabilityState.ERROR,
                    detail = "Gemini Nano download unavailable: ${e.localizedMessage ?: "Unsupported device."}",
                    lastCheckedEpochMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun close() {
        runCatching { promptClient?.close() }
        promptClient = null
    }

    private fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024f * 1024f)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }
}

package com.mote.app.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mote.app.ui.components.LobeModelBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class LocalModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class GgufInspectionMetadata(
    val isValidGgufHeader: Boolean,
    val ggufVersion: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val fileSizeBytes: Long,
    val loadedAtEpochMs: Long
)

data class LocalModelEntry(
    val id: String,
    val displayName: String,
    val providerBrand: LobeModelBrand,
    val quantLabel: String,
    val parameterCount: String,
    val approxSizeMb: Long,
    val fileName: String,
    val downloadUrl: String,
    val summary: String,
    val status: LocalModelDownloadStatus = LocalModelDownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val isImported: Boolean = false
) {
    val progressFraction: Float
        get() = when {
            status == LocalModelDownloadStatus.DOWNLOADED -> 1f
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            approxSizeMb > 0L -> (downloadedBytes.toFloat() / (approxSizeMb * 1024f * 1024f)).coerceIn(0f, 0.99f)
            else -> 0f
        }
}

data class LoadedLocalModelState(
    val modelId: String,
    val displayName: String,
    val brand: LobeModelBrand,
    val filePath: String,
    val metadata: GgufInspectionMetadata
)

data class DeviceHardwareSnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val modelsDiskUsageBytes: Long
)

/**
 * Real local GGUF model download, SAF import, disk storage, and binary GGUF header loader
 * storing model weight files safely inside app-private `filesDir/models/` (zero storage permissions).
 */
class LocalModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

    private val _models = MutableStateFlow<List<LocalModelEntry>>(emptyList())
    val models: StateFlow<List<LocalModelEntry>> = _models.asStateFlow()

    private val _loadedModel = MutableStateFlow<LoadedLocalModelState?>(null)
    val loadedModel: StateFlow<LoadedLocalModelState?> = _loadedModel.asStateFlow()

    private val _hardware = MutableStateFlow(readHardwareSnapshot())
    val hardware: StateFlow<DeviceHardwareSnapshot> = _hardware.asStateFlow()

    init {
        refreshCatalogFromDisk()
    }

    fun refreshCatalogFromDisk() {
        val knownFiles = curatedCatalog.map { it.fileName }.toSet()
        val updatedCatalog = curatedCatalog.map { entry ->
            val target = File(modelsDir, entry.fileName)
            if (target.exists() && target.length() > 0L) {
                entry.copy(
                    status = LocalModelDownloadStatus.DOWNLOADED,
                    downloadedBytes = target.length(),
                    totalBytes = target.length(),
                    errorMessage = null
                )
            } else {
                entry
            }
        }

        // Also detect user-imported .gguf / .task / .litertlm files in modelsDir
        val importedEntries = modelsDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0L && it.name !in knownFiles && !it.name.endsWith(".part") }
            .map { file ->
                LocalModelEntry(
                    id = "imported_${file.name.hashCode().toUInt()}",
                    displayName = file.nameWithoutExtension,
                    providerBrand = LobeModelBrand.fromModelId(file.name),
                    quantLabel = file.extension.uppercase().ifBlank { "GGUF" },
                    parameterCount = "Local Import",
                    approxSizeMb = (file.length() / (1024L * 1024L)).coerceAtLeast(1L),
                    fileName = file.name,
                    downloadUrl = "",
                    summary = "Imported from device storage via Android document picker.",
                    status = LocalModelDownloadStatus.DOWNLOADED,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    isImported = true
                )
            }

        _models.value = updatedCatalog + importedEntries
        _hardware.value = readHardwareSnapshot()
    }

    fun startDownload(modelId: String) {
        val entry = _models.value.firstOrNull { it.id == modelId } ?: return
        if (entry.downloadUrl.isBlank()) return
        if (activeJobs[modelId]?.isActive == true) return

        val job = scope.launch {
            val partFile = File(modelsDir, "${entry.fileName}.part")
            val finalFile = File(modelsDir, entry.fileName)

            updateEntry(modelId) {
                it.copy(
                    status = LocalModelDownloadStatus.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = it.approxSizeMb * 1024L * 1024L,
                    speedBytesPerSec = 0L,
                    errorMessage = null
                )
            }

            try {
                val connection = openFollowingRedirects(entry.downloadUrl)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code from model host")
                    }
                    val contentLength = connection.contentLengthLong.takeIf { it > 0L }
                        ?: (entry.approxSizeMb * 1024L * 1024L)

                    var downloaded = 0L
                    var lastEmitTime = System.currentTimeMillis()
                    var bytesSinceEmit = 0L

                    connection.inputStream.use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                bytesSinceEmit += read

                                val now = System.currentTimeMillis()
                                val deltaMs = now - lastEmitTime
                                if (deltaMs >= 250L) {
                                    val speed = (bytesSinceEmit * 1000L) / deltaMs.coerceAtLeast(1L)
                                    updateEntry(modelId) {
                                        it.copy(
                                            status = LocalModelDownloadStatus.DOWNLOADING,
                                            downloadedBytes = downloaded,
                                            totalBytes = contentLength.coerceAtLeast(downloaded),
                                            speedBytesPerSec = speed
                                        )
                                    }
                                    lastEmitTime = now
                                    bytesSinceEmit = 0L
                                }
                            }
                        }
                    }

                    if (!isActive) {
                        partFile.delete()
                        updateEntry(modelId) {
                            it.copy(
                                status = LocalModelDownloadStatus.NOT_DOWNLOADED,
                                downloadedBytes = 0L,
                                speedBytesPerSec = 0L
                            )
                        }
                        return@launch
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }

                    updateEntry(modelId) {
                        it.copy(
                            status = LocalModelDownloadStatus.DOWNLOADED,
                            downloadedBytes = finalFile.length(),
                            totalBytes = finalFile.length(),
                            speedBytesPerSec = 0L,
                            errorMessage = null
                        )
                    }
                    _hardware.value = readHardwareSnapshot()
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                partFile.delete()
                updateEntry(modelId) {
                    it.copy(
                        status = LocalModelDownloadStatus.NOT_DOWNLOADED,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L
                    )
                }
                throw e
            } catch (e: Exception) {
                partFile.delete()
                updateEntry(modelId) {
                    it.copy(
                        status = LocalModelDownloadStatus.ERROR,
                        speedBytesPerSec = 0L,
                        errorMessage = e.localizedMessage ?: "Download failed"
                    )
                }
            } finally {
                activeJobs.remove(modelId)
            }
        }
        activeJobs[modelId] = job
    }

    fun cancelDownload(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        val entry = _models.value.firstOrNull { it.id == modelId } ?: return
        if (_loadedModel.value?.modelId == modelId) {
            _loadedModel.value = null
        }
        File(modelsDir, entry.fileName).delete()
        File(modelsDir, "${entry.fileName}.part").delete()
        if (entry.isImported) {
            _models.update { list -> list.filterNot { it.id == modelId } }
        } else {
            updateEntry(modelId) {
                it.copy(
                    status = LocalModelDownloadStatus.NOT_DOWNLOADED,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    speedBytesPerSec = 0L,
                    errorMessage = null
                )
            }
        }
        _hardware.value = readHardwareSnapshot()
    }

    suspend fun importModelFromUri(uri: Uri): Result<LocalModelEntry> = withContext(Dispatchers.IO) {
        try {
            val rawName = resolveDisplayName(uri) ?: "imported_model_${System.currentTimeMillis()}.gguf"
            val safeName = rawName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            val dest = File(modelsDir, safeName)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open selected model file."))

            refreshCatalogFromDisk()
            val imported = _models.value.firstOrNull { it.fileName == safeName }
                ?: return@withContext Result.failure(IllegalStateException("Imported file not found after copy."))
            Result.success(imported)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Loads a downloaded/imported local model by opening the file and parsing its binary
     * GGUF v2/v3 header (magic bytes `GGUF`, version, tensor count, KV metadata count).
     */
    suspend fun loadModel(modelId: String): Result<LoadedLocalModelState> = withContext(Dispatchers.IO) {
        try {
            val entry = _models.value.firstOrNull { it.id == modelId }
                ?: return@withContext Result.failure(IllegalArgumentException("Model not found."))
            val file = File(modelsDir, entry.fileName)
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Download model weights before loading."))
            }

            val metadata = parseGgufHeader(file)
            val loaded = LoadedLocalModelState(
                modelId = entry.id,
                displayName = entry.displayName,
                brand = entry.providerBrand,
                filePath = file.absolutePath,
                metadata = metadata
            )
            _loadedModel.value = loaded
            _hardware.value = readHardwareSnapshot()
            Result.success(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun unloadModel() {
        _loadedModel.value = null
        _hardware.value = readHardwareSnapshot()
    }

    fun refreshHardware() {
        _hardware.value = readHardwareSnapshot()
    }

    private fun parseGgufHeader(file: File): GgufInspectionMetadata {
        val fileSize = file.length()
        if (fileSize < 24L) {
            return GgufInspectionMetadata(false, 0, 0L, 0L, fileSize, System.currentTimeMillis())
        }
        val header = ByteArray(24)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header)
        }
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val version = buf.int
        val tensorCount = buf.long
        val kvCount = buf.long
        val valid = magic == "GGUF" && version in 1..4
        return GgufInspectionMetadata(
            isValidGgufHeader = valid,
            ggufVersion = if (valid) version else 0,
            tensorCount = if (valid) tensorCount else 0L,
            kvCount = if (valid) kvCount else 0L,
            fileSizeBytes = fileSize,
            loadedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun openFollowingRedirects(startUrl: String): HttpURLConnection {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MoteAndroidLLMClient/1.0")
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
        throw IllegalStateException("Too many HTTP redirects while fetching model")
    }

    private fun resolveDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun readHardwareSnapshot(): DeviceHardwareSnapshot {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val diskBytes = modelsDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
        return DeviceHardwareSnapshot(
            totalRamMb = memInfo.totalMem / (1024L * 1024L),
            availableRamMb = memInfo.availMem / (1024L * 1024L),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            modelsDiskUsageBytes = diskBytes
        )
    }

    private fun updateEntry(modelId: String, transform: (LocalModelEntry) -> LocalModelEntry) {
        _models.update { list ->
            list.map { if (it.id == modelId) transform(it) else it }
        }
    }

    companion object {
        val curatedCatalog = listOf(
            LocalModelEntry(
                id = "smollm2-135m-q4",
                displayName = "SmolLM2 135M Instruct",
                providerBrand = LobeModelBrand.HUGGINGFACE,
                quantLabel = "Q4_K_M",
                parameterCount = "135M",
                approxSizeMb = 105L,
                fileName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
                summary = "Ultra-compact mobile GGUF ideal for rapid local download & header verification."
            ),
            LocalModelEntry(
                id = "qwen2.5-0.5b-q4",
                displayName = "Qwen 2.5 0.5B Instruct",
                providerBrand = LobeModelBrand.QWEN,
                quantLabel = "Q4_K_M",
                parameterCount = "0.5B",
                approxSizeMb = 398L,
                fileName = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
                summary = "Fast multilingual instruction model sized for mid-range Android devices."
            ),
            LocalModelEntry(
                id = "gemma-3-1b-q4",
                displayName = "Gemma 3 1B Instruct",
                providerBrand = LobeModelBrand.GEMMA,
                quantLabel = "Q4_K_M",
                parameterCount = "1B",
                approxSizeMb = 806L,
                fileName = "gemma-3-1b-it-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
                summary = "Google's lightweight open-weight Gemma 3 quantized for on-device phones."
            ),
            LocalModelEntry(
                id = "llama-3.2-1b-q4",
                displayName = "Llama 3.2 1B Instruct",
                providerBrand = LobeModelBrand.LLAMA,
                quantLabel = "Q4_K_M",
                parameterCount = "1B",
                approxSizeMb = 808L,
                fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                summary = "Meta's mobile-optimized 1B model tuned for summarization and tool prompts."
            ),
            LocalModelEntry(
                id = "deepseek-r1-1.5b-q4",
                displayName = "DeepSeek-R1 Distill Qwen 1.5B",
                providerBrand = LobeModelBrand.DEEPSEEK,
                quantLabel = "Q4_K_M",
                parameterCount = "1.5B",
                approxSizeMb = 1117L,
                fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                summary = "Compact reasoning-distilled weights for step-by-step problem solving."
            ),
            LocalModelEntry(
                id = "ministral-3b-q4",
                displayName = "Mistral / Ministral 3B",
                providerBrand = LobeModelBrand.MISTRAL,
                quantLabel = "Q4_K_M",
                parameterCount = "3B",
                approxSizeMb = 2150L,
                fileName = "Ministral-3B-Instruct-Q4_K_M.gguf",
                downloadUrl = "https://huggingface.co/bartowski/Ministral-3B-Instruct-2410-GGUF/resolve/main/Ministral-3B-Instruct-2410-GGUF-Q4_K_M.gguf",
                summary = "Edge-sized Mistral model for high-RAM flagship Android devices."
            )
        )
    }
}

package com.messenger.crisix.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import audio.soniqo.speech.ModelPrecision
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ModelFileInfo(
    val repo: String,
    val filename: String,
    val url: String = "",
    var sizeBytes: Long = 0,
)

enum class FileDownloadStatus { Pending, Downloading, Done, Failed }

data class FileDownloadState(
    val file: ModelFileInfo,
    val status: FileDownloadStatus = FileDownloadStatus.Pending,
    val bytesDownloaded: Long = 0,
    val totalSize: Long = 0,
) {
    val progress: Float
        get() = if (totalSize > 0) (bytesDownloaded.toFloat() / totalSize).coerceAtMost(1f) else 0f
    val totalSizeHuman: String
        get() = when {
            totalSize >= 1_000_000 -> "%.0f MB".format(totalSize.toDouble() / 1_000_000)
            totalSize >= 1_000 -> "%.0f KB".format(totalSize.toDouble() / 1_000)
            else -> "$totalSize B"
        }
    val bytesHuman: String
        get() = when {
            bytesDownloaded >= 1_000_000 -> "%.1f MB".format(bytesDownloaded.toDouble() / 1_000_000)
            bytesDownloaded >= 1_000 -> "%.0f KB".format(bytesDownloaded.toDouble() / 1_000)
            else -> "$bytesDownloaded B"
        }
}

data class OverallDownloadState(
    val files: List<FileDownloadState> = emptyList(),
    val totalBytes: Long = 0,
    val totalDownloaded: Long = 0,
    val overallSpeedBytesPerSec: Long = 0,
    val isComplete: Boolean = false,
) {
    val overallEtaSeconds: Long
        get() {
            if (overallSpeedBytesPerSec <= 0) return 0
            val remaining = totalBytes - totalDownloaded
            return if (remaining > 0) remaining / overallSpeedBytesPerSec else 0
        }
    val overallEtaHuman: String
        get() {
            if (overallEtaSeconds <= 0) return ""
            val m = overallEtaSeconds / 60
            val s = overallEtaSeconds % 60
            return if (m > 0) "${m}m ${s}s" else "${s}s"
        }
    val overallProgress: Float
        get() = if (totalBytes > 0) (totalDownloaded.toFloat() / totalBytes).coerceAtMost(1f) else 0f
    val totalBytesHuman: String
        get() = when {
            totalBytes >= 1_000_000 -> "%.0f MB".format(totalBytes.toDouble() / 1_000_000)
            totalBytes >= 1_000 -> "%.0f KB".format(totalBytes.toDouble() / 1_000)
            else -> "$totalBytes B"
        }
    val totalDownloadedHuman: String
        get() = when {
            totalDownloaded >= 1_000_000 -> "%.1f MB".format(totalDownloaded.toDouble() / 1_000_000)
            totalDownloaded >= 1_000 -> "%.0f KB".format(totalDownloaded.toDouble() / 1_000)
            else -> "$totalDownloaded B"
        }
    val speedHuman: String
        get() {
            if (overallSpeedBytesPerSec <= 0) return ""
            return when {
                overallSpeedBytesPerSec >= 1_000_000 -> "%.1f MB/s".format(overallSpeedBytesPerSec.toDouble() / 1_000_000)
                overallSpeedBytesPerSec >= 1_000 -> "%.0f KB/s".format(overallSpeedBytesPerSec.toDouble() / 1_000)
                else -> "$overallSpeedBytesPerSec B/s"
            }
        }
}

class ModelDownloader(private val appContext: Context) {
    companion object {
        const val MAX_RETRIES = 5
        const val RETRY_DELAY_MS = 2000L
        const val PARALLEL_WORKERS = 3
        const val MODEL_VERSION = 2

        val FILES: List<ModelFileInfo> = listOf(
            ModelFileInfo("Silero-VAD-v5-ONNX", "silero-vad.onnx"),
            ModelFileInfo("Parakeet-TDT-v3-ONNX", "parakeet-encoder-int8.onnx"),
            ModelFileInfo("Parakeet-TDT-v3-ONNX", "parakeet-decoder-joint-int8.onnx"),
            ModelFileInfo("Parakeet-TDT-v3-ONNX", "vocab.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "kokoro-e2e.onnx"),
            ModelFileInfo("Kokoro-82M-ONNX", "kokoro-e2e.onnx.data"),
            ModelFileInfo("Kokoro-82M-ONNX", "vocab_index.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "us_gold.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "us_silver.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "dict_fr.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "dict_es.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "dict_it.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "dict_pt.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "dict_hi.json"),
            ModelFileInfo("Kokoro-82M-ONNX", "voices/af_heart.bin"),
            ModelFileInfo("DeepFilterNet3-ONNX", "deepfilter-auxiliary.bin"),
        ).map {
            it.copy(url = "https://huggingface.co/aufklarer/${it.repo}/resolve/main/${it.filename}")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(OverallDownloadState())
    val state: StateFlow<OverallDownloadState> = _state.asStateFlow()

    private data class SpeedSample(val timeNanos: Long, val totalBytes: Long)

    val modelDir: String get() = File(appContext.filesDir, "models").absolutePath

    fun areModelsReady(): Boolean {
        val dir = File(modelDir)
        if (!dir.exists()) return false
        val versionFile = File(dir, "version.txt")
        if (!versionFile.exists()) return false
        if (versionFile.readText().trim() != MODEL_VERSION.toString()) return false
        return FILES.all { File(dir, it.filename).exists() }
    }

    suspend fun downloadAll(): Boolean = withContext(Dispatchers.IO) {
        _state.value = OverallDownloadState()
        val dir = File(modelDir)
        dir.mkdirs()

        // Step 1: HEAD requests to get true file sizes
        val files = FILES.toMutableList()
        var totalBytes = 0L
        for (fi in files) {
            try {
                val headReq = Request.Builder().url(fi.url).head().build()
                val headResp = client.newCall(headReq).execute()
                headResp.use { resp ->
                    val cl = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    fi.sizeBytes = cl
                    totalBytes += cl
                }
            } catch (e: Exception) {
                Timber.w(e, "HEAD failed for ${fi.filename}, using default size")
                fi.sizeBytes = 1_000_000L
                totalBytes += fi.sizeBytes
            }
        }

        val initialFileStates = files.map { FileDownloadState(it) }
        _state.value = OverallDownloadState(
            files = initialFileStates,
            totalBytes = totalBytes,
        )

        val speedLock = Any()
        val speedSamples = mutableListOf<SpeedSample>()

        fun computeSpeed(nowNanos: Long, total: Long): Long {
            synchronized(speedLock) {
                speedSamples.add(SpeedSample(nowNanos, total))
                val cutoff = nowNanos - 2_000_000_000L
                val iter = speedSamples.iterator()
                while (iter.hasNext()) {
                    if (iter.next().timeNanos < cutoff) iter.remove()
                }
                if (speedSamples.size >= 2) {
                    val f = speedSamples.first()
                    val l = speedSamples.last()
                    val dt = l.timeNanos - f.timeNanos
                    val db = l.totalBytes - f.totalBytes
                    if (dt > 0) return (db * 1_000_000_000L) / dt
                }
            }
            return 0L
        }

        val stateLock = Any()

        fun updateState(idx: Int, update: FileDownloadState) {
            synchronized(stateLock) {
                val current = _state.value
                val updatedFiles = current.files.toMutableList().also { it[idx] = update }
                val newTotal = updatedFiles.sumOf {
                    if (it.status == FileDownloadStatus.Done) it.totalSize
                    else it.bytesDownloaded.coerceAtLeast(0)
                }
                val isComplete = updatedFiles.all { it.status == FileDownloadStatus.Done }
                val speed = computeSpeed(System.nanoTime(), newTotal)

                _state.value = OverallDownloadState(
                    files = updatedFiles,
                    totalBytes = current.totalBytes,
                    totalDownloaded = newTotal,
                    overallSpeedBytesPerSec = speed,
                    isComplete = isComplete,
                )
            }
        }

        // Step 2: Parallel downloads with semaphore
        try {
            coroutineScope {
                val semaphore = Semaphore(PARALLEL_WORKERS)
                files.indices.map { idx ->
                    async {
                        semaphore.withPermit {
                            val fi = files[idx]
                            updateState(idx, FileDownloadState(
                                file = fi,
                                status = FileDownloadStatus.Downloading,
                                bytesDownloaded = 0,
                                totalSize = fi.sizeBytes,
                            ))
                            downloadFile(fi, dir) { bytes ->
                                updateState(idx, FileDownloadState(
                                    file = fi,
                                    status = FileDownloadStatus.Downloading,
                                    bytesDownloaded = bytes,
                                    totalSize = fi.sizeBytes,
                                ))
                            }
                            updateState(idx, FileDownloadState(
                                file = fi,
                                status = FileDownloadStatus.Done,
                                bytesDownloaded = fi.sizeBytes,
                                totalSize = fi.sizeBytes,
                            ))
                        }
                    }
                }.awaitAll()
            }

            // Write version + precision files
            File(dir, "version.txt").writeText(MODEL_VERSION.toString())
            File(dir, "precision.txt").writeText(ModelPrecision.INT8.name)

            true
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            false
        }
    }

    private fun downloadFile(fi: ModelFileInfo, destDir: File, onProgress: (Long) -> Unit) {
        var lastError: IOException? = null
        val dest = File(destDir, fi.filename)
        dest.parentFile?.mkdirs()
        val tempFile = File(destDir, ".${fi.filename}.tmp")
        tempFile.parentFile?.mkdirs()

        for (attempt in 1..MAX_RETRIES) {
            try {
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val req = Request.Builder().url(fi.url).apply {
                    if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
                }.build()

                val resp = client.newCall(req).execute()
                resp.use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        val code = response.code
                        throw IOException(
                            if (code in 500..599) "Server temporarily unavailable (HTTP $code)"
                            else "HTTP $code for ${fi.url}"
                        )
                    }

                    val body = response.body ?: throw IOException("No response body")
                    val isResume = response.code == 206
                    val contentLength = body.contentLength()

                    FileOutputStream(tempFile, isResume).use { output ->
                        val buffer = ByteArray(65536)
                        var downloaded = if (isResume) existingBytes else 0L
                        body.byteStream().use { stream ->
                            var bytesRead: Int
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                onProgress(downloaded)
                            }
                        }
                    }

                    if (!isResume && contentLength > 0 && tempFile.length() != contentLength) {
                        throw IOException("Incomplete download: got ${tempFile.length()} bytes, expected $contentLength")
                    }

                    if (!tempFile.renameTo(dest)) {
                        tempFile.copyTo(dest, overwrite = true)
                        tempFile.delete()
                    }
                }
                return
            } catch (e: IOException) {
                lastError = e
                Timber.w(e, "Download attempt $attempt failed for ${fi.filename}")
                val delay = if (e.message?.contains("temporarily unavailable") == true) {
                    RETRY_DELAY_MS * attempt * 3
                } else {
                    RETRY_DELAY_MS * attempt
                }
                Thread.sleep(delay)
            }
        }
        throw IOException("Download failed after $MAX_RETRIES attempts: ${lastError?.message}", lastError)
    }
}

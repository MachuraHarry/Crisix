package com.messenger.crisix.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import audio.soniqo.speech.ModelPrecision
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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
        const val PARALLEL_WORKERS = 5
        const val MODEL_VERSION = 2
        const val CHUNK_THRESHOLD = 10_000_000L
        const val CHUNKS = 4
        const val BUFFER_SIZE = 262144

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

    private val engine: CronetEngine by lazy {
        CronetEngine.Builder(appContext)
            .setUserAgent("CrisixAI/1.0")
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .build()
    }

    private val callbackExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "cronet-cb")
    }

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

        // Step 1: parallel HEAD requests to get true file sizes
        val files = FILES.toMutableList()
        coroutineScope {
            files.indices.map { idx ->
                async {
                    try {
                        val size = cronetHead(files[idx].url)
                        files[idx].sizeBytes = size
                    } catch (e: Exception) {
                        Timber.w(e, "HEAD failed for ${files[idx].filename}")
                        files[idx].sizeBytes = 1_000_000L
                    }
                }
            }.awaitAll()
        }
        val totalBytes = files.sumOf { it.sizeBytes }

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

        // Step 2: parallel downloads via Cronet (QUIC/HTTP3)
        try {
            val downloadDispatcher = Dispatchers.IO.limitedParallelism(PARALLEL_WORKERS)

            coroutineScope {
                files.indices.map { idx ->
                    async(downloadDispatcher) {
                        val fi = files[idx]
                        updateState(idx, FileDownloadState(
                            file = fi,
                            status = FileDownloadStatus.Downloading,
                            bytesDownloaded = 0,
                            totalSize = fi.sizeBytes,
                        ))
                        if (fi.sizeBytes >= CHUNK_THRESHOLD) {
                            downloadFileChunked(fi, dir) { bytes ->
                                updateState(idx, FileDownloadState(
                                    file = fi,
                                    status = FileDownloadStatus.Downloading,
                                    bytesDownloaded = bytes,
                                    totalSize = fi.sizeBytes,
                                ))
                            }
                        } else {
                            downloadFileSingle(fi, dir) { bytes ->
                                updateState(idx, FileDownloadState(
                                    file = fi,
                                    status = FileDownloadStatus.Downloading,
                                    bytesDownloaded = bytes,
                                    totalSize = fi.sizeBytes,
                                ))
                            }
                        }
                        updateState(idx, FileDownloadState(
                            file = fi,
                            status = FileDownloadStatus.Done,
                            bytesDownloaded = fi.sizeBytes,
                            totalSize = fi.sizeBytes,
                        ))
                    }
                }.awaitAll()
            }

            File(dir, "version.txt").writeText(MODEL_VERSION.toString())
            File(dir, "precision.txt").writeText(ModelPrecision.INT8.name)

            true
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            false
        }
    }

    private suspend fun cronetHead(url: String): Long = suspendCancellableCoroutine { cont ->
        var resumed = false
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest, info: UrlResponseInfo, newLocation: String,
            ) { request.followRedirect() }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                val length = info.allHeaders
                    ?.get("content-length")
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
                request.cancel()
                resumed = true
                cont.resume(length)
            }

            override fun onReadCompleted(
                request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer,
            ) {}

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                if (!resumed) { resumed = true; cont.resume(0L) }
            }

            override fun onFailed(
                request: UrlRequest, info: UrlResponseInfo?, error: CronetException,
            ) {
                if (!resumed) { resumed = true; cont.resume(0L) }
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                if (!resumed) { resumed = true; cont.resume(0L) }
            }
        }
        engine.newUrlRequestBuilder(url, callback, callbackExecutor)
            .setHttpMethod("HEAD")
            .build()
            .start()
    }

    private suspend fun downloadFileSingle(
        fi: ModelFileInfo,
        destDir: File,
        onProgress: (Long) -> Unit,
    ) {
        var lastError: IOException? = null
        val dest = File(destDir, fi.filename)
        dest.parentFile?.mkdirs()
        val tempFile = File(destDir, ".${fi.filename}.tmp")
        tempFile.parentFile?.mkdirs()

        for (attempt in 1..MAX_RETRIES) {
            try {
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val rangeHeader = if (existingBytes > 0) "bytes=$existingBytes-" else null

                val result = cronetDownload(
                    url = fi.url,
                    dest = tempFile,
                    rangeHeader = rangeHeader,
                    append = existingBytes > 0,
                    startOffset = existingBytes,
                    onProgress = onProgress,
                )

                if (!result) throw IOException("Download incomplete")
                if (!tempFile.renameTo(dest)) {
                    tempFile.copyTo(dest, overwrite = true)
                    tempFile.delete()
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

    private suspend fun downloadFileChunked(
        fi: ModelFileInfo,
        destDir: File,
        onTotalProgress: (Long) -> Unit,
    ) = coroutineScope {
        val tempDir = File(destDir, ".${fi.filename}.chunks")
        tempDir.mkdirs()
        val dest = File(destDir, fi.filename)

        val chunkSize = fi.sizeBytes / CHUNKS
        val chunkProgress = LongArray(CHUNKS)
        val progressLock = Any()

        try {
            (0 until CHUNKS).map { chunkIdx ->
                async(Dispatchers.IO) {
                    val startByte = chunkIdx * chunkSize
                    val endByte = if (chunkIdx == CHUNKS - 1) fi.sizeBytes else (chunkIdx + 1) * chunkSize
                    val chunkFile = File(tempDir, "chunk_$chunkIdx")
                    val tempChunk = File(tempDir, ".chunk_$chunkIdx.tmp")

                    cronetDownload(
                        url = fi.url,
                        dest = tempChunk,
                        rangeHeader = "bytes=$startByte-${endByte - 1}",
                        append = false,
                        startOffset = 0L,
                        onProgress = { chunkBytes ->
                            synchronized(progressLock) { chunkProgress[chunkIdx] = chunkBytes }
                            val total = synchronized(progressLock) { chunkProgress.sum() }
                            onTotalProgress(total)
                        },
                    )

                    if (!tempChunk.renameTo(chunkFile)) {
                        tempChunk.copyTo(chunkFile, overwrite = true)
                        tempChunk.delete()
                    }
                }
            }.awaitAll()

            FileOutputStream(dest).use { output ->
                for (chunkIdx in 0 until CHUNKS) {
                    val chunkFile = File(tempDir, "chunk_$chunkIdx")
                    if (chunkFile.exists()) {
                        chunkFile.inputStream().use { it.copyTo(output) }
                        chunkFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            throw e
        }

        tempDir.delete()
    }

    private suspend fun cronetDownload(
        url: String,
        dest: File,
        rangeHeader: String?,
        append: Boolean,
        startOffset: Long,
        onProgress: (Long) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        dest.parentFile?.mkdirs()
        var resumed = false

        val callback = object : UrlRequest.Callback() {
            private var output: FileOutputStream? = null
            private var total = startOffset

            override fun onRedirectReceived(
                request: UrlRequest, info: UrlResponseInfo, newLocation: String,
            ) { request.followRedirect() }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                val code = info.httpStatusCode
                if (code in 200..299) {
                    output = FileOutputStream(dest, append)
                    request.read(ByteBuffer.allocateDirect(BUFFER_SIZE))
                } else {
                    request.cancel()
                    resumed = true
                    cont.resumeWithException(IOException("HTTP $code for $url"))
                }
            }

            override fun onReadCompleted(
                request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val arr = ByteArray(byteBuffer.remaining())
                byteBuffer.get(arr)
                try {
                    output?.write(arr)
                    total += arr.size
                    onProgress(total)
                } catch (e: IOException) {
                    request.cancel()
                    resumed = true
                    cont.resumeWithException(e)
                    return
                }
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                try { output?.close() } catch (_: IOException) {}
                if (!resumed) { resumed = true; cont.resume(true) }
            }

            override fun onFailed(
                request: UrlRequest, info: UrlResponseInfo?, error: CronetException,
            ) {
                try { output?.close() } catch (_: IOException) {}
                if (!resumed) {
                    resumed = true
                    cont.resumeWithException(IOException(
                        "Cronet download failed: ${error.message}", error,
                    ))
                }
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                try { output?.close() } catch (_: IOException) {}
                if (!resumed) { resumed = true; cont.resume(false) }
            }
        }

        val builder = engine.newUrlRequestBuilder(url, callback, callbackExecutor)
        if (rangeHeader != null) {
            builder.addHeader("Range", rangeHeader)
        }
        builder.build().start()
    }
}

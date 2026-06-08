package com.messenger.crisix.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class AiModelManager(private val context: Context) {

    sealed class ModelStatus {
        data object NotDownloaded : ModelStatus()
        data class Downloading(
            val progress: Float,
            val partIndex: Int,
            val partCount: Int,
            val speedBytesPerSec: Long,
        ) : ModelStatus()
        data object Extracting : ModelStatus()
        data object Initializing : ModelStatus()
        data class DownloadingMmproj(
            val progress: Float,
            val speedBytesPerSec: Long,
        ) : ModelStatus()
        data object Ready : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    companion object {
        private const val TAG = "AiModelManager"
        const val MODEL_DIR = "crisix-ai"
        const val MODEL_FILENAME = "model.gguf"
        const val MMPROJ_FILENAME = "mmproj-BF16.gguf"
        const val DEFAULT_MODEL_URL =
            "https://github.com/MachuraHarry/CrisixAi/releases/download/v2.0/CrisixAi.tar"
        const val DEFAULT_MMPROJ_URL =
            "https://github.com/MachuraHarry/CrisixAi/releases/download/v2.0/mmproj-BF16.gguf"
        const val DEFAULT_MODEL_PARTS = 2
        private const val DEFAULT_GPU_LAYERS = 99
        private const val FALLBACK_GPU_LAYERS = 0

        private fun disableVulkanIfUnsupported() {
            if (Build.MANUFACTURER.equals("Google", ignoreCase = true)) return
            try {
                Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                Log.i(TAG, "Vulkan disabled on ${Build.MANUFACTURER} ${Build.MODEL} (non-Pixel)")
            } catch (_: Exception) {}
        }

        suspend fun applyVulkanSetting(context: Context) {
            val prefs = context.settingsDataStore.data.first()
            val userDisabled = prefs[SettingsKeys.AI_VULKAN_DISABLED] ?: false
            val isPixel = Build.MANUFACTURER.equals("Google", ignoreCase = true)
            if (userDisabled || !isPixel) {
                try {
                    Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                    Log.i(TAG, "Vulkan disabled (user=$userDisabled, pixel=$isPixel)")
                } catch (_: Exception) {}
            } else {
                try { Os.unsetenv("LM_GGML_DISABLE_VULKAN") } catch (_: Exception) {}
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    init { disableVulkanIfUnsupported() }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    data class BenchmarkResult(
        val tokens: Int,
        val elapsedMs: Long,
        val tokensPerSec: Float
    )

    private val _lastBenchmark = MutableStateFlow<BenchmarkResult?>(null)
    val lastBenchmark: StateFlow<BenchmarkResult?> = _lastBenchmark.asStateFlow()

    private val llama = LlamaAndroid(context.contentResolver)
    private val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceContext = newSingleThreadContext("crisix-ai-inference")

    @Volatile
    private var contextId: Int? = null

    @Volatile
    var modelInfo: Map<String, Any>? = null
        private set

    @Volatile
    private var currentTokenCallback: ((String) -> Unit)? = null

    private val internalTokenCallback: (String) -> Unit = { token ->
        currentTokenCallback?.invoke(token)
    }

    val isDownloaded: Boolean get() {
        val exists = modelFile.exists()
        val len = modelFile.length()
        Log.d(TAG, "isDownloaded: exists=$exists, length=$len, path=${modelFile.absolutePath}")
        return exists && len > 0
    }
    val isInitialized: Boolean get() = contextId != null
    val modelFileSize: Long get() = modelFile.length()

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    private val modelFile: File get() = File(modelDir, MODEL_FILENAME)
    private val mmprojFile: File get() = File(modelDir, MMPROJ_FILENAME)

    suspend fun getSavedModelUrl(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_MODEL_URL] ?: DEFAULT_MODEL_URL
    }

    suspend fun getSavedGpuLayers(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_GPU_LAYERS] ?: DEFAULT_GPU_LAYERS
    }

    suspend fun getSavedContextSize(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_CONTEXT_SIZE] ?: 2048
    }

    suspend fun getSavedBatchSize(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_BATCH_SIZE] ?: 512
    }

    suspend fun getSavedThreads(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_THREADS] ?: 4
    }

    suspend fun getSavedModelParts(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_MODEL_PARTS] ?: DEFAULT_MODEL_PARTS
    }

    suspend fun selectLocalModel(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                _status.value = ModelStatus.Initializing
                modelDir.mkdirs()

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw RuntimeException("Konnte Datei nicht lesen")

                Log.i(TAG, "Local model copied: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs.remove(SettingsKeys.AI_MODEL_URL)
                    prefs.remove(SettingsKeys.AI_MODEL_PARTS)
                }

                tryDownloadMmproj()

                initEngine()
            } catch (e: Exception) {
                Log.e(TAG, "Local model selection failed", e)
                _status.value = ModelStatus.Error(e.message ?: "Local model selection failed")
            }
        }
    }

    suspend fun downloadModel() {
        if (isDownloaded) {
            Log.d(TAG, "Model already downloaded (${modelFile.length()} bytes), calling initEngine()")
            tryDownloadMmproj()
            initEngine()
            return
        }
        Log.d(TAG, "Model not downloaded yet, starting download")

        withContext(Dispatchers.IO) {
            try {
                val prefs = context.settingsDataStore.data.first()
                val baseUrl = prefs[SettingsKeys.AI_MODEL_URL] ?: DEFAULT_MODEL_URL

                modelDir.mkdirs()

                val tempDir = File(context.cacheDir, "crisix-ai-download")
                tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                val partFiles = mutableListOf<File>()
                var partIndex = 0

                while (true) {
                    val suffix = ('a'.code + partIndex / 26).toChar().toString() + ('a'.code + partIndex % 26).toChar()
                    val partUrl = "$baseUrl.part_$suffix"
                    val partFile = File(tempDir, "part_$suffix")

                    Log.d(TAG, "Trying part $suffix: $partUrl")
                    val request = Request.Builder().url(partUrl).head().build()
                    val headResponse = client.newCall(request).execute()
                    if (!headResponse.isSuccessful) {
                        headResponse.close()
                        if (partIndex == 0) throw RuntimeException("Keine Download-Teile gefunden (HTTP ${headResponse.code})")
                        break
                    }
                    headResponse.close()

                    _status.value = ModelStatus.Downloading(
                        progress = 0f,
                        partIndex = partIndex,
                        partCount = partIndex + 1,
                        speedBytesPerSec = 0,
                    )

                    downloadFile(partUrl, partFile, partIndex, partIndex + 1)
                    partFiles.add(partFile)
                    partIndex++
                }

                val totalParts = partFiles.size
                Log.i(TAG, "Downloaded $totalParts part(s)")
                _status.value = ModelStatus.Extracting
                concatAndExtract(partFiles)

                tempDir.listFiles()?.forEach { it.delete() }

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs[SettingsKeys.AI_MODEL_URL] = baseUrl
                }

                Log.i(TAG, "Model file ready: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

                tryDownloadMmproj()

                initEngine()
            } catch (e: Exception) {
                Log.e(TAG, "Download/Initialization failed", e)
                _status.value = ModelStatus.Error(e.message ?: "Download/Initialization failed")
            }
        }
    }

    private fun downloadFile(url: String, target: File, partIndex: Int, partCount: Int) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code} für $url")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val totalBytes = body.contentLength()
        val buffer = ByteArray(32768)
        var downloadedBytes = 0L
        var lastUpdateNs = System.nanoTime()
        var lastBytes = 0L

        body.byteStream().use { input ->
            FileOutputStream(target).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val nowNs = System.nanoTime()
                    val elapsed = nowNs - lastUpdateNs
                    if (elapsed > 500_000_000L) {
                        val deltaBytes = downloadedBytes - lastBytes
                        val speed = (deltaBytes.toDouble() / (elapsed / 1_000_000_000.0)).roundToLong()
                        lastUpdateNs = nowNs
                        lastBytes = downloadedBytes

                        val partProgress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        val overall = (partIndex.toFloat() + partProgress) / partCount

                        _status.value = ModelStatus.Downloading(
                            progress = overall.coerceIn(0f, 1f),
                            partIndex = partIndex,
                            partCount = partCount,
                            speedBytesPerSec = speed,
                        )
                    }
                }
            }
        }
    }

    private fun concatAndExtract(partFiles: List<File>) {
        if (partFiles.size == 1) {
            partFiles[0].copyTo(modelFile, overwrite = true)
            return
        }

        val tarFile = File(context.cacheDir, "crisix-ai-download/combined.tar")
        FileOutputStream(tarFile).use { output ->
            for (part in partFiles) {
                FileInputStream(part).use { input ->
                    input.copyTo(output)
                }
            }
        }

        TarArchiveInputStream(FileInputStream(tarFile)).use { tarIn ->
            var entry = tarIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    FileOutputStream(modelFile).use { output ->
                        tarIn.copyTo(output)
                    }
                }
                entry = tarIn.nextEntry
            }
        }

        tarFile.delete()
    }

    private fun releaseCurrentContext() {
        contextId?.let { id ->
            try { llama.releaseContext(id) } catch (_: Exception) {}
    }
    contextId = null
}

private suspend fun tryDownloadMmproj() {
    if (mmprojFile.exists() && mmprojFile.length() > 0) {
        Log.d(TAG, "mmproj already downloaded (${mmprojFile.length()} bytes)")
        return
    }
    try {
        _status.value = ModelStatus.DownloadingMmproj(0f, 0)
        val mmprojUrl = DEFAULT_MMPROJ_URL
        Log.i(TAG, "Downloading mmproj from $mmprojUrl")
        val request = Request.Builder().url(mmprojUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "mmproj download failed: HTTP ${response.code} - multimodal disabled")
            return
        }
        val body = response.body ?: return
        val totalBytes = body.contentLength()
        val buffer = ByteArray(32768)
        var downloadedBytes = 0L
        var lastUpdateNs = System.nanoTime()
        var lastBytes = 0L
        body.byteStream().use { input ->
            FileOutputStream(mmprojFile).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    val nowNs = System.nanoTime()
                    val elapsed = nowNs - lastUpdateNs
                    if (elapsed > 500_000_000L) {
                        val deltaBytes = downloadedBytes - lastBytes
                        val speed = (deltaBytes.toDouble() / (elapsed / 1_000_000_000.0)).roundToLong()
                        lastUpdateNs = nowNs
                        lastBytes = downloadedBytes
                        val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        _status.value = ModelStatus.DownloadingMmproj(progress.coerceIn(0f, 1f), speed)
                    }
                }
            }
        }
        Log.i(TAG, "mmproj downloaded (${mmprojFile.length()} bytes)")
    } catch (e: Exception) {
        Log.w(TAG, "mmproj download failed: ${e.message} - multimodal disabled")
        mmprojFile.delete()
    }
}

private fun buildEngineConfig(
    modelFd: Int, gpuLayers: Int, contextSize: Int, batchSize: Int, threads: Int,
    mmprojFd: Int, imageFds: IntArray
): Map<String, Any> {
        val modelUri = Uri.fromFile(modelFile).toString()
        return mutableMapOf(
            "model" to modelUri,
            "model_fd" to modelFd,
            "use_mmap" to false,
            "use_mlock" to false,
            "n_ctx" to contextSize,
            "embedding" to false,
            "n_batch" to batchSize,
            "n_threads" to threads,
            "n_gpu_layers" to gpuLayers,
            "vocab_only" to false,
            "lora" to "",
            "lora_scaled" to 1.0,
            "rope_freq_base" to 0.0,
            "rope_freq_scale" to 0.0,
            "mmproj_fd" to mmprojFd,
            "image_fds" to imageFds,
        )
    }

    private suspend fun initEngine() = withContext(inferenceContext) {
        _status.value = ModelStatus.Initializing
        releaseCurrentContext()

        val gpuLayers = getSavedGpuLayers()
        val contextSize = getSavedContextSize()
        val batchSize = getSavedBatchSize()
        val threads = getSavedThreads()

        val modelUri = Uri.fromFile(modelFile)
        val modelPfd = context.contentResolver.openFileDescriptor(modelUri, "r")
        if (modelPfd == null) {
            Log.e(TAG, "Modelldatei konnte nicht geöffnet werden: $modelUri")
            _status.value = ModelStatus.Error("Konnte Modelldatei nicht öffnen")
            return@withContext
        }
        val modelFd = modelPfd.detachFd()

        val mmprojFd = if (mmprojFile.exists() && mmprojFile.length() > 0) {
            val mmprojUri = Uri.fromFile(mmprojFile)
            val pfd = context.contentResolver.openFileDescriptor(mmprojUri, "r")
            val fd = pfd?.detachFd() ?: -1
            if (fd >= 0) Log.i(TAG, "Multimodal enabled (mmproj ${mmprojFile.length()} bytes)")
            fd
        } else -1

        if (gpuLayers > 0) {
            try {
                Log.i(TAG, "Init: ctx=$contextSize batch=$batchSize threads=$threads gpu=$gpuLayers multimodal=${mmprojFd >= 0}")
                val config = buildEngineConfig(modelFd, gpuLayers, contextSize, batchSize, threads, mmprojFd, intArrayOf())
                val result = llama.startEngine(config, internalTokenCallback)
                    ?: throw Exception("startEngine returned null")

                contextId = (result["contextId"] as Number).toInt()
                modelInfo = result["model"] as? Map<String, Any>
                _status.value = ModelStatus.Ready
                Log.i(TAG, "Engine ready (ctx=$contextSize gpu=$gpuLayers multimodal=${mmprojFd >= 0})")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "GPU init failed, trying CPU fallback", e)
            }
        }
        tryCpuInit(modelFd, contextSize, batchSize, threads, mmprojFd)
    }

    private fun tryCpuInit(modelFd: Int, contextSize: Int, batchSize: Int, threads: Int, mmprojFd: Int = -1) {
        try {
            Log.i(TAG, "Initializing CPU fallback (GPU layers = 0)")
            val config = buildEngineConfig(modelFd, 0, contextSize, batchSize, threads, mmprojFd, intArrayOf())
            val result = llama.startEngine(config, internalTokenCallback)
                ?: throw Exception("startEngine returned null (CPU)")

            contextId = (result["contextId"] as Number).toInt()
            modelInfo = result["model"] as? Map<String, Any>
            _status.value = ModelStatus.Ready
            Log.i(TAG, "Engine initialized (CPU fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "CPU init also failed", e)
            _status.value = ModelStatus.Error(e.message ?: "Engine init failed (GPU+CPU)")
        }
    }

    suspend fun predict(
        prompt: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        imageUris: List<Uri> = emptyList(),
    ) = withContext(inferenceContext) {
        val id = contextId ?: throw IllegalStateException("Model not initialized")
        val threads = getSavedThreads()

        val imageFds = imageUris.mapNotNull { uri ->
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
                pfd.detachFd()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open image: $uri", e)
                null
            }
        }

        currentTokenCallback = onToken

        try {
            val params = mutableMapOf<String, Any>(
                "prompt" to prompt,
                "emit_partial_completion" to true,
                "temperature" to 0.7,
                "top_k" to 40,
                "top_p" to 0.95,
                "n_predict" to -1,
                "n_threads" to threads,
            )
            if (imageFds.isNotEmpty()) {
                params["image_fds"] = imageFds
            }

            val startTime = System.nanoTime()
            var tokenCount = 0
            currentTokenCallback = { token ->
                tokenCount++
                onToken(token)
            }

            llama.launchCompletion(id, params)
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            val tokensPerSec = if (elapsedMs > 0) (tokenCount * 1000f / elapsedMs) else 0f
            _lastBenchmark.value = BenchmarkResult(tokenCount, elapsedMs, tokensPerSec)
            Log.i(TAG, "Benchmark: $tokenCount tokens in ${elapsedMs}ms = %.1f tok/s".format(tokensPerSec))
            onDone()
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            onError(e.message ?: "Prediction failed")
        } finally {
            currentTokenCallback = null
        }
    }

    fun stopPrediction() {
        contextId?.let { id ->
            try { runBlocking { llama.stopCompletion(id) } } catch (_: Exception) {}
        }
    }

    suspend fun getSavedSystemPrompt(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_SYSTEM_PROMPT]
            ?: "Du bist Crisix AI, ein hilfreicher KI-Assistent, der in der Crisix Messenger-App läuft. Du antwortest auf Deutsch."
    }

    fun getContextId(): Int? = contextId

    fun close() {
        releaseCurrentContext()
        helperScope.cancel()
        inferenceContext.close()
    }
}

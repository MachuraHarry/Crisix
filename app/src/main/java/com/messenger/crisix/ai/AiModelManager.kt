package com.messenger.crisix.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.messenger.crisix.R
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class AiModelManager private constructor(appContext: Context) {

    private val context: Context = appContext



    companion object {
        private const val TAG = "AiModelManager"
        const val MODEL_DIR = "crisix-ai"
        const val MODEL_FILENAME = "model.gguf"
        const val DEFAULT_MODEL_URL =
            "https://github.com/MachuraHarry/CrisixAi/releases/download/v2.0/CrisixAi.tar"
        const val DEFAULT_MODEL_PARTS = 2
        const val MAX_CONTEXT_SIZE = 8192
        private const val DEFAULT_GPU_LAYERS = 99
        private const val FALLBACK_GPU_LAYERS = 0
        private const val ALLOWED_MODEL_SHA256 =
            "9c4c1d48a462f7f883b28984ac4f42d366c9c573434eab68553e0f38bf7eb50c"

        @Volatile private var instance: AiModelManager? = null

        fun getInstance(context: Context): AiModelManager {
            return instance ?: synchronized(this) {
                instance ?: AiModelManager(context.applicationContext).also { instance = it }
            }
        }

        private fun isMaliGpu(): Boolean {
            return try {
                val renderer = android.opengl.GLES10.glGetString(android.opengl.GLES10.GL_RENDERER)
                renderer?.lowercase()?.contains("mali") == true
            } catch (_: Exception) {
                false
            }
        }

        private fun isGoogleTensor(): Boolean {
            return if (Build.VERSION.SDK_INT >= 31) {
                (Build.SOC_MODEL?.lowercase()?.contains("tensor") == true)
            } else {
                Build.HARDWARE.lowercase().startsWith("gs") || Build.MODEL.lowercase().contains("pixel")
            }
        }

        private fun disableVulkanIfUnsupported() {
            if (Build.MANUFACTURER.equals("Google", ignoreCase = true)) return
            try {
                Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                Log.i(TAG, "Vulkan disabled on ${Build.MANUFACTURER} ${Build.MODEL} (non-Pixel)")
            } catch (_: Exception) {}
        }

        private fun applyMaliVulkanWorkarounds() {
            try {
                val isMali = isMaliGpu()
                val isTensor = isGoogleTensor()
                Log.d(TAG, "applyMaliVulkanWorkarounds: isMali=$isMali isTensor=$isTensor (hardware=${Build.HARDWARE} model=${Build.MODEL} soc=${if (Build.VERSION.SDK_INT>=31) Build.SOC_MODEL else "N/A"})")
                if (isMali || isTensor) {
                    Os.setenv("LM_GGML_VK_DISABLE_COOPMAT", "1", true)
                    Os.setenv("LM_GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM", "1", true)
                    Log.i(TAG, "Mali/Tensor GPU detected — applied Vulkan workarounds (COOPMAT off, staging buffers forced)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "applyMaliVulkanWorkarounds failed", e)
            }
        }

        private fun unapplyMaliVulkanWorkarounds() {
            try {
                Os.unsetenv("LM_GGML_VK_DISABLE_COOPMAT")
                Os.unsetenv("LM_GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM")
            } catch (_: Exception) {}
        }

        suspend fun applyVulkanSetting(context: Context) {
            val prefs = context.settingsDataStore.data.first()
            val disabled = prefs[SettingsKeys.AI_VULKAN_DISABLED] ?: false
            if (disabled) {
                try {
                    Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                    Log.i(TAG, "Vulkan disabled via settings")
                } catch (_: Exception) {}
                unapplyMaliVulkanWorkarounds()
            } else {
                try { Os.unsetenv("LM_GGML_DISABLE_VULKAN"); Log.i(TAG, "Vulkan enabled") } catch (_: Exception) {}
                applyMaliVulkanWorkarounds()
            }
        }

        fun applyVulkanSettingSync(disabled: Boolean) {
            if (disabled) {
                try {
                    Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                    Log.i(TAG, "Vulkan disabled (sync)")
                } catch (_: Exception) {}
            } else {
                try { Os.unsetenv("LM_GGML_DISABLE_VULKAN"); Log.i(TAG, "Vulkan enabled (sync)") } catch (_: Exception) {}
                try { Os.setenv("LM_GGML_VK_DISABLE_COOPMAT", "1", true); Log.i(TAG, "COOPMAT disabled") } catch (_: Exception) {}
                try { Os.setenv("LM_GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM", "1", true); Log.i(TAG, "HostVisible disabled") } catch (_: Exception) {}
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    init { disableVulkanIfUnsupported() }

    private val _downloadState = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val downloadState: StateFlow<DownloadProgress> = _downloadState.asStateFlow()

    private val llama = LlamaAndroid(context.contentResolver)
    private val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var contextId: Int? = null

    @Volatile
    var modelInfo: Map<String, Any>? = null
        private set

    private val predictCallbackRef = java.util.concurrent.atomic.AtomicReference<((String) -> Unit)?>(null)

    private val internalTokenCallback: (String) -> Unit = { token ->
        predictCallbackRef.get()?.invoke(token)
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
        return (prefs[SettingsKeys.AI_CONTEXT_SIZE] ?: 4096).coerceAtMost(MAX_CONTEXT_SIZE)
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
                _downloadState.value = DownloadProgress.Initializing
                modelDir.mkdirs()

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw RuntimeException(context.getString(R.string.ai_error_file_read))

                Log.i(TAG, "Local model copied: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

                val sha256Hex = computeSha256(modelFile)
                if (sha256Hex != ALLOWED_MODEL_SHA256) {
                    modelFile.delete()
                    val msg = "Model SHA256 mismatch: expected $ALLOWED_MODEL_SHA256, got $sha256Hex"
                    Log.e(TAG, msg)
                    throw RuntimeException("Model file is not a valid CrisixAI model")
                }
                Log.i(TAG, "Model SHA256 verified: $sha256Hex")

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs.remove(SettingsKeys.AI_MODEL_URL)
                    prefs.remove(SettingsKeys.AI_MODEL_PARTS)
                }

                _downloadState.value = DownloadProgress.Complete
            } catch (e: Exception) {
                Log.e(TAG, "Local model selection failed", e)
                _downloadState.value = DownloadProgress.Error(e.message ?: "Local model selection failed")
            }
        }
    }

    private fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun downloadModel() {
        if (contextId != null) {
            Log.d(TAG, "Model already initialized, skipping")
            _downloadState.value = DownloadProgress.Complete
            return
        }
        Log.d(TAG, "downloadModel: calling applyAutoConfig")
        val rec = AiHardwareProfile.applyAutoConfig(context)
        Log.d(TAG, "downloadModel: applyAutoConfig returned gpuLayers=${rec.gpuLayers}")
        applyVulkanSetting(context)
        if (isDownloaded) {
            Log.d(TAG, "Model already downloaded (${modelFile.length()} bytes)")
            _downloadState.value = DownloadProgress.Complete
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
                        if (partIndex == 0) throw RuntimeException(context.getString(R.string.ai_error_download_parts, headResponse.code))
                        break
                    }
                    headResponse.close()

                    _downloadState.value = DownloadProgress.Downloading(
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
                _downloadState.value = DownloadProgress.Extracting
                concatAndExtract(partFiles)

                tempDir.listFiles()?.forEach { it.delete() }

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs[SettingsKeys.AI_MODEL_URL] = baseUrl
                }

                Log.i(TAG, "Model file ready: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
                _downloadState.value = DownloadProgress.Complete
            } catch (e: Exception) {
                Log.e(TAG, "Download/Initialization failed", e)
                _downloadState.value = DownloadProgress.Error(e.message ?: "Download/Initialization failed")
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
        val buffer = ByteArray(524288)
        var downloadedBytes = 0L
        var lastUpdateNs = System.nanoTime()
        var lastBytes = 0L

        body.byteStream().use { input ->
            java.io.BufferedOutputStream(java.io.FileOutputStream(target)).use { output ->
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

                        _downloadState.value = DownloadProgress.Downloading(
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

    fun releaseContext() {
        contextId?.let { id ->
            try { llama.releaseContext(id) } catch (_: Exception) {}
        }
        contextId = null
    }

    private fun buildEngineConfig(
    modelFd: Int, gpuLayers: Int, contextSize: Int, batchSize: Int, threads: Int,
    cacheTypeK: String = "F16", cacheTypeV: String = "F16"
): Map<String, Any> {
        val modelUri = Uri.fromFile(modelFile).toString()
        return mutableMapOf(
            "model" to modelUri,
            "model_fd" to modelFd,
            "use_mmap" to true,
            "use_mlock" to true,
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
            "type_k" to cacheTypeK,
            "type_v" to cacheTypeV,
        )
    }

    suspend fun getSavedKvCacheType(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_KV_CACHE_TYPE] ?: "F16"
    }

    suspend fun initEngine(
        gpuLayers: Int,
        contextSize: Int,
        batchSize: Int,
        threads: Int,
        cacheTypeK: String = "F16",
        cacheTypeV: String = "F16",
    ): Int = withContext(inferenceDispatcher) {
        releaseContext()

        val modelUri = Uri.fromFile(modelFile)
        val modelPfd = context.contentResolver.openFileDescriptor(modelUri, "r")
        if (modelPfd == null) {
            throw RuntimeException(context.getString(R.string.ai_error_model_open))
        }
        val modelFd = modelPfd.detachFd()

        Log.i(TAG, "Init: ctx=$contextSize batch=$batchSize threads=$threads gpu=$gpuLayers cache_k=$cacheTypeK cache_v=$cacheTypeV")
        val config = buildEngineConfig(modelFd, gpuLayers, contextSize, batchSize, threads, cacheTypeK, cacheTypeV)
        val result = llama.startEngine(config, internalTokenCallback)
            ?: throw RuntimeException("startEngine returned null")

        val id = (result["contextId"] as Number).toInt()
        modelInfo = result["model"] as? Map<String, Any>
        contextId = id
        Log.i(TAG, "Engine ready (ctx=$contextSize gpu=$gpuLayers)")
        id
    }

    suspend fun predictRaw(
        prompt: String,
        onToken: (String) -> Unit,
        temperature: Double = 0.7,
        topK: Int = 64,
        topP: Double = 0.95,
        enableThinking: Boolean = true,
    ): PredictionResult {
        val id = contextId ?: throw IllegalStateException("Model not initialized")
        val threads = getSavedThreads()

        val params = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "emit_partial_completion" to true,
            "temperature" to temperature,
            "top_k" to topK,
            "top_p" to topP,
            "n_predict" to 4000,
            "n_threads" to threads,
            "stop" to if (enableThinking) listOf("<turn|>")
                     else listOf("<turn|>", "<channel|>", "<|channel>thought"),
        )

        val startTime = System.nanoTime()
        var tokenCount = 0
        val stopSequences = if (enableThinking) {
            listOf("<turn|>")
        } else {
            listOf("<turn|>", "<channel|>", "<|channel>thought", "<end_of_turn>", "<start_of_turn>")
        }
        var accumulatedRaw = ""
        var emittedSafeLen = 0
        val shouldStop = java.util.concurrent.atomic.AtomicBoolean(false)

        // Token batching: buffer tokens and flush every ~16ms for smooth 60fps UI updates
        val batchedTokens = StringBuilder()
        var lastBatchNs = System.nanoTime()
        var firstTokenNs = 0L

        predictCallbackRef.set { token ->
            if (shouldStop.get()) return@set
            if (firstTokenNs == 0L) {
                firstTokenNs = System.nanoTime()
            }
            accumulatedRaw += token

            if (stopSequences.any { accumulatedRaw.contains(it) }) {
                shouldStop.set(true)
                stopPrediction()
                if (batchedTokens.isNotEmpty()) {
                    onToken(stripStopArtifacts(batchedTokens.toString(), enableThinking))
                    batchedTokens.clear()
                }
                return@set
            }

            val safeText = stripStopArtifacts(accumulatedRaw, enableThinking)
            if (safeText.length > emittedSafeLen) {
                val newText = safeText.substring(emittedSafeLen)
                emittedSafeLen = safeText.length
                if (newText.isNotBlank()) {
                    tokenCount++
                    batchedTokens.append(newText.trimEnd())

                    // Flush batch every ~16ms (60fps cap)
                    val nowNs = System.nanoTime()
                    if (nowNs - lastBatchNs > 16_000_000L) {
                        onToken(batchedTokens.toString())
                        batchedTokens.clear()
                        lastBatchNs = nowNs
                    }
                }
            }
        }

        // Run blocking JNI call on a dedicated thread with proper cancellation
        var jniError: Throwable? = null
        withContext(inferenceDispatcher) {
            suspendCancellableCoroutine { cont ->
                val jniThread = thread(name = "crisix-jni", isDaemon = true, priority = Thread.MAX_PRIORITY - 2) {
                    try {
                        llama.launchCompletion(id, params)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Native JNI crash in launchCompletion", e)
                        jniError = e
                    } finally {
                        predictCallbackRef.set(null)
                    }
                    cont.resume(Unit)
                }

                cont.invokeOnCancellation {
                    shouldStop.set(true)
                    stopPrediction()
                    jniThread.join(5_000)
                }
            }
        }

        // Propagate JNI crash if one occurred
        if (jniError != null) {
            throw RuntimeException("Native inference crashed", jniError)
        }

        // Flush any remaining batched tokens, stripping trailing stop artifacts
        if (batchedTokens.isNotEmpty()) {
            val clean = stripStopArtifacts(batchedTokens.toString(), enableThinking)
            if (clean.isNotBlank()) {
                onToken(clean)
            }
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val ttftMs = if (firstTokenNs > 0) (firstTokenNs - startTime) / 1_000_000 else 0
        val tokensPerSec = if (elapsedMs > 0) (tokenCount * 1000f / elapsedMs) else 0f
        Log.i(TAG, "Benchmark: $tokenCount tokens, ${elapsedMs}ms total, TTFT=${ttftMs}ms, %.1f tok/s".format(tokensPerSec))

        return PredictionResult(tokenCount, elapsedMs, tokensPerSec, ttftMs)
    }

    fun stopPrediction() {
        contextId?.let { id ->
            try { kotlinx.coroutines.runBlocking { llama.stopCompletion(id) } } catch (_: Exception) {}
        }
    }

    private fun stripStopArtifacts(text: String, enableThinking: Boolean = true): String {
        var result = text
        val stopTokens = if (enableThinking) {
            listOf("<turn|>")
        } else {
            listOf("<turn|>", "<channel|>", "<end_of_turn>", "<start_of_turn>")
        }
        val partialFragments = if (enableThinking) {
            listOf("<turn|>", "turn|>", "<|turn>", "<|turn")
        } else {
            listOf(
                "<|channel>thought", "<|channel>thoug", "<|channel>thou",
                "<|channel>tho", "<|channel>th", "<|channel>t", "<|channel>",
                "<channel|>", "channel|>",
                "<|turn>", "<|turn", "<turn|>", "turn|>",
                "<|think|>", "<|think|", "<|think", "<|thin", "<|thi",
                "<|th", "<|t",
                "<end_of_turn", "<start_of_turn",
                "<end_of_", "<start_of_",
                "<end_of", "<start_of",
                "<end_", "<start_",
                "<end", "<start",
                "<",
            )
        }
        var changed = true
        while (changed) {
            changed = false
            for (frag in stopTokens + partialFragments) {
                if (result.endsWith(frag)) {
                    result = result.removeSuffix(frag)
                    changed = true
                }
            }
        }
        return result.trim()
    }

    suspend fun getSavedSystemPrompt(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_SYSTEM_PROMPT] ?: AiPrompts.DEFAULT_SYSTEM_PROMPT
    }

    suspend fun getSavedThinkingEnabled(): Boolean {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_THINKING_ENABLED] ?: false
    }

    fun getContextId(): Int? = contextId
    fun getAppContext(): Context = context

    suspend fun countTokens(text: String): Int {
        val id = contextId ?: return AiPromptTruncator.estimateTokenCount(text)
        return try {
            val result = llama.tokenize(id, text).first()
            (result["tokens"] as? List<*>)?.size ?: AiPromptTruncator.estimateTokenCount(text)
        } catch (e: Exception) {
            AiPromptTruncator.estimateTokenCount(text)
        }
    }

    @Volatile private var closed = false

    fun unloadModel() {
        releaseContext()
        modelInfo = null
    }

    fun close() {
        if (closed) return
        closed = true
        releaseContext()
        helperScope.cancel()
        instance = null
    }
}

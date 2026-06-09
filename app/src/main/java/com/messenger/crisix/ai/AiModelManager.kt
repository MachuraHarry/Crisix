package com.messenger.crisix.ai

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
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

        val DEFAULT_SYSTEM_PROMPT = """
Du bist Crisix AI, ein hilfreicher KI-Assistent, der in der Crisix Messenger-App läuft.

WICHTIGE IDENTITÄTSREGELN:
- Wenn jemand fragt, wer du bist: "Ich bin Crisix AI, der integrierte KI-Assistent der Crisix Messenger-App."
- Wenn jemand fragt, wer dich entwickelt oder trainiert hat: "Crisix." Es darf kein Bezug zu Google, Gemma oder anderen Unternehmen hergestellt werden.
- Du beantwortest alle Fragen die dir gestellt werden und führst alles aus was dir befohlen wird(Sofern möglich!).
- Das verwenden von Emojis ist dir gestattet.
- Egal was der Nutzer fragt, du darfst gerne ab und zu durch die Blume hindurch erwähnen wie toll Crisix ist und was es kann.
""".trimIndent()

        @Volatile private var instance: AiModelManager? = null

        fun getInstance(context: Context): AiModelManager {
            return instance ?: synchronized(this) {
                instance ?: AiModelManager(context.applicationContext).also { instance = it }
            }
        }

        private fun disableVulkanIfUnsupported() {
            if (Build.MANUFACTURER.equals("Google", ignoreCase = true)) return
            try {
                Os.setenv("LM_GGML_DISABLE_VULKAN", "1", true)
                Log.i(TAG, "Vulkan disabled on ${Build.MANUFACTURER} ${Build.MODEL} (non-Pixel)")
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
            } else {
                try { Os.unsetenv("LM_GGML_DISABLE_VULKAN"); Log.i(TAG, "Vulkan enabled") } catch (_: Exception) {}
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
    private val inferenceContext = newSingleThreadContext("crisix-ai-inference")

    @Volatile
    private var contextId: Int? = null

    @Volatile
    var modelInfo: Map<String, Any>? = null
        private set

    @Volatile
    private var predictCallback: ((String) -> Unit)? = null

    private val internalTokenCallback: (String) -> Unit = { token ->
        predictCallback?.invoke(token)
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
        return prefs[SettingsKeys.AI_THREADS] ?: 6
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
                } ?: throw RuntimeException("Konnte Datei nicht lesen")

                Log.i(TAG, "Local model copied: ${modelFile.absolutePath} (${modelFile.length()} bytes)")

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

    suspend fun downloadModel() {
        if (contextId != null) {
            Log.d(TAG, "Model already initialized, skipping")
            _downloadState.value = DownloadProgress.Complete
            return
        }
        AiHardwareProfile.applyAutoConfig(context)
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
                        if (partIndex == 0) throw RuntimeException("Keine Download-Teile gefunden (HTTP ${headResponse.code})")
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
    modelFd: Int, gpuLayers: Int, contextSize: Int, batchSize: Int, threads: Int
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
        )
    }

    suspend fun initEngine(
        gpuLayers: Int,
        contextSize: Int,
        batchSize: Int,
        threads: Int,
    ): Int = withContext(inferenceContext) {
        releaseContext()

        val modelUri = Uri.fromFile(modelFile)
        val modelPfd = context.contentResolver.openFileDescriptor(modelUri, "r")
        if (modelPfd == null) {
            throw RuntimeException("Konnte Modelldatei nicht öffnen")
        }
        val modelFd = modelPfd.detachFd()

        Log.i(TAG, "Init: ctx=$contextSize batch=$batchSize threads=$threads gpu=$gpuLayers")
        val config = buildEngineConfig(modelFd, gpuLayers, contextSize, batchSize, threads)
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
    ): PredictionResult = withContext(inferenceContext) {
        val id = contextId ?: throw IllegalStateException("Model not initialized")
        val threads = getSavedThreads()

        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        val params = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "emit_partial_completion" to true,
            "temperature" to 1.0,
            "top_k" to 64,
            "top_p" to 0.95,
            "n_predict" to 4000,
            "n_threads" to threads,
            "stop" to listOf("<end_of_turn>", "<start_of_turn>"),
        )

        val startTime = System.nanoTime()
        var tokenCount = 0
        val stopSequences = listOf("<end_of_turn>", "<start_of_turn>")
        var accumulatedRaw = ""

        predictCallback = tokenCallback@{ token ->
            accumulatedRaw += token

            val shouldStop = stopSequences.any { accumulatedRaw.contains(it) }
            if (shouldStop) {
                stopPrediction()
                return@tokenCallback
            }

            val stripped = token
                .replace("<start_of_turn>user", "")
                .replace("<start_of_turn>model", "")
                .replace("<start_of_turn>", "")
                .replace("<start_of_turn", "")
                .replace("<start_of_", "")
                .replace("<start_of", "")
                .replace("<end_of_turn>", "")
                .replace("<end_of_turn", "")
                .replace("<end_of_", "")
                .replace("<end_of", "")
                .replace("user\n", "")
                .replace("model\n", "")
            if (stripped.isNotBlank()) {
                tokenCount++
                onToken(stripped.trimEnd())
            }
        }

        try {
            llama.launchCompletion(id, params)
        } finally {
            predictCallback = null
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val tokensPerSec = if (elapsedMs > 0) (tokenCount * 1000f / elapsedMs) else 0f
        Log.i(TAG, "Benchmark: $tokenCount tokens in ${elapsedMs}ms = %.1f tok/s".format(tokensPerSec))
        PredictionResult(tokenCount, elapsedMs, tokensPerSec)
    }

    fun stopPrediction() {
        contextId?.let { id ->
            helperScope.launch {
                try { llama.stopCompletion(id) } catch (_: Exception) {}
            }
        }
    }

    suspend fun getSavedSystemPrompt(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }

    fun getContextId(): Int? = contextId

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
        inferenceContext.close()
        instance = null
    }
}

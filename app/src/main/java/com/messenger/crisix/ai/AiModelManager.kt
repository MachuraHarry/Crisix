package com.messenger.crisix.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import androidx.datastore.preferences.core.edit
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AiModelManager(private val context: Context) {

    sealed class ModelStatus {
        data object NotDownloaded : ModelStatus()
        data class Downloading(val progress: Float) : ModelStatus()
        data object Ready : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    companion object {
        private const val TAG = "AiModelManager"
        private const val MODEL_DIR = "crisix-ai"
        private const val MODEL_FILENAME = "model.litertlm"
        const val DEFAULT_MODEL_URL =
            "https://crisix.org/downloads/crisixAi.gguf"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    @Volatile
    private var engine: Engine? = null

    val isDownloaded: Boolean get() = modelFile.exists() && modelFile.length() > 0
    val isInitialized: Boolean get() = engine != null

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    private val modelFile: File get() = File(modelDir, MODEL_FILENAME)

    suspend fun getSavedModelUrl(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_MODEL_URL] ?: DEFAULT_MODEL_URL
    }

    fun downloadModel(url: String = DEFAULT_MODEL_URL) {
        if (isDownloaded) {
            _status.value = ModelStatus.Ready
            return
        }

        scope.launch {
            try {
                _status.value = ModelStatus.Downloading(0f)

                modelDir.mkdirs()
                if (modelFile.exists()) modelFile.delete()

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    _status.value = ModelStatus.Error("Download failed: HTTP ${response.code}")
                    return@launch
                }

                val body = response.body ?: run {
                    _status.value = ModelStatus.Error("Empty response body")
                    return@launch
                }

                val totalBytes = body.contentLength()
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(modelFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                _status.value = ModelStatus.Downloading(
                                    downloadedBytes.toFloat() / totalBytes.toFloat()
                                )
                            }
                        }
                    }
                }

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs[SettingsKeys.AI_MODEL_URL] = url
                }

                _status.value = ModelStatus.Ready
                Log.i(TAG, "Model downloaded: ${modelFile.absolutePath} (${downloadedBytes} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _status.value = ModelStatus.Error(e.message ?: "Download failed")
            }
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        if (!isDownloaded) {
            _status.value = ModelStatus.Error("Model not downloaded")
            return@withContext false
        }

        try {
            Log.i(TAG, "Initializing LiteRT-LM Engine…")
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath,
            )
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine
            _status.value = ModelStatus.Ready
            Log.i(TAG, "Engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization failed", e)
            _status.value = ModelStatus.Error(e.message ?: "Engine init failed")
            false
        }
    }

    suspend fun getSavedSystemPrompt(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_SYSTEM_PROMPT]
            ?: "Du bist Crisix AI, ein hilfreicher KI-Assistent, der in der Crisix Messenger-App läuft. Du antwortest auf Deutsch."
    }

    fun getEngine(): Engine? = engine

    fun close() {
        engine?.close()
        engine = null
    }
}

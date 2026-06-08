package com.messenger.crisix.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import androidx.datastore.preferences.core.edit
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.SequenceInputStream
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
        data object Ready : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    companion object {
        private const val TAG = "AiModelManager"
        const val MODEL_DIR = "crisix-ai"
        const val MODEL_FILENAME = "model.gguf"
        const val DEFAULT_MODEL_URL =
            "https://github.com/MachuraHarry/CrisixAi/releases/download/v1.0/CrisixAi.gguf.tar"
        const val DEFAULT_MODEL_PARTS = 3
    }

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
    val modelFileSize: Long get() = modelFile.length()

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    private val modelFile: File get() = File(modelDir, MODEL_FILENAME)

    suspend fun getSavedModelUrl(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_MODEL_URL] ?: DEFAULT_MODEL_URL
    }

    suspend fun getSavedModelParts(): Int {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SettingsKeys.AI_MODEL_PARTS] ?: DEFAULT_MODEL_PARTS
    }

    suspend fun downloadModel() {
        if (isDownloaded) {
            _status.value = ModelStatus.Ready
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val prefs = context.settingsDataStore.data.first()
                val baseUrl = prefs[SettingsKeys.AI_MODEL_URL] ?: DEFAULT_MODEL_URL
                val partCount = prefs[SettingsKeys.AI_MODEL_PARTS] ?: DEFAULT_MODEL_PARTS

                modelDir.mkdirs()

                val tempDir = File(context.cacheDir, "crisix-ai-download")
                tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                val partFiles = mutableListOf<File>()

                for (i in 0 until partCount) {
                    val suffix = ('a'.toInt() + i / 26).toChar().toString() + ('a'.toInt() + i % 26).toChar()
                    val partUrl = "$baseUrl.part_$suffix"
                    val partFile = File(tempDir, "part_$suffix")

                    _status.value = ModelStatus.Downloading(
                        progress = i.toFloat() / partCount,
                        partIndex = i,
                        partCount = partCount,
                        speedBytesPerSec = 0,
                    )

                    downloadFile(partUrl, partFile, i, partCount)
                    partFiles.add(partFile)
                }

                _status.value = ModelStatus.Extracting

                concatAndExtract(partFiles)

                tempDir.listFiles()?.forEach { it.delete() }

                context.settingsDataStore.edit { prefs ->
                    prefs[SettingsKeys.AI_MODEL_DOWNLOADED] = true
                    prefs[SettingsKeys.AI_MODEL_URL] = baseUrl
                    prefs[SettingsKeys.AI_MODEL_PARTS] = partCount
                }

                _status.value = ModelStatus.Ready
                Log.i(TAG, "Model ready: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _status.value = ModelStatus.Error(e.message ?: "Download failed")
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
                    if (elapsed > 500_000_000L) { // update every 500ms
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
            // Single file: just rename/move to target
            partFiles[0].copyTo(modelFile, overwrite = true)
            return
        }

        // Concatenate parts into a temp tar
        val tarFile = File(context.cacheDir, "crisix-ai-download/combined.tar")
        FileOutputStream(tarFile).use { output ->
            for (part in partFiles) {
                FileInputStream(part).use { input ->
                    input.copyTo(output)
                }
            }
        }

        // Extract tar to model file
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

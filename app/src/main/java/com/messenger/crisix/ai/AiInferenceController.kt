package com.messenger.crisix.ai

import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PredictionResult(
    val tokens: Int,
    val elapsedMs: Long,
    val tokensPerSec: Float,
    val ttftMs: Long = 0,  // Time-To-First-Token in ms
)

class AiInferenceController(
    private val engine: AiModelManager,
) {
    private val TAG = "AiInferenceCtrl"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<AiRuntimeState>(AiRuntimeState.Idle)
    val state: StateFlow<AiRuntimeState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var currentContextId: Int? = null
    private var inactivityJob: Job? = null
    private var lastActivityMs = 0L

    val isReady: Boolean get() = _state.value is AiRuntimeState.Ready
    val isGenerating: Boolean get() = _state.value is AiRuntimeState.Generating

    suspend fun load() {
        mutex.withLock {
            when (_state.value) {
                is AiRuntimeState.Idle, is AiRuntimeState.Error -> {
                    _state.value = AiRuntimeState.Loading
                }
                else -> return
            }
        }

        try {
            AiHardwareProfile.applyAutoConfig(engine.getAppContext())

            val prefs = engine.getAppContext().settingsDataStore.data.first()
            val vulkanDisabled = prefs[SettingsKeys.AI_VULKAN_DISABLED] ?: false
            AiModelManager.applyVulkanSettingSync(vulkanDisabled)

            val gpuLayers = if (vulkanDisabled) 0 else (prefs[SettingsKeys.AI_GPU_LAYERS] ?: 99)
            val contextSize = prefs[SettingsKeys.AI_CONTEXT_SIZE] ?: 4096
            val batchSize = prefs[SettingsKeys.AI_BATCH_SIZE] ?: 512
            val threads = prefs[SettingsKeys.AI_THREADS] ?: 4
            val kvCacheType = prefs[SettingsKeys.AI_KV_CACHE_TYPE] ?: "F16"

            if (gpuLayers > 0) {
                try {
                    val ctxId = engine.initEngine(gpuLayers, contextSize, batchSize, threads, kvCacheType, kvCacheType)
                    currentContextId = ctxId
                    mutex.withLock { _state.value = AiRuntimeState.Ready(ctxId) }
                    startInactivityTimer()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "GPU init failed, trying CPU fallback", e)
                }
            }

            val ctxId = engine.initEngine(0, contextSize, batchSize, threads, kvCacheType, kvCacheType)
            currentContextId = ctxId
            mutex.withLock { _state.value = AiRuntimeState.Ready(ctxId) }
            startInactivityTimer()
        } catch (e: Exception) {
            mutex.withLock {
                _state.value = AiRuntimeState.Error(
                    e.message ?: "Init failed", recoverable = true,
                )
            }
        } catch (e: OutOfMemoryError) {
            mutex.withLock {
                _state.value = AiRuntimeState.Error(
                    "Nicht genügend Arbeitsspeicher: ${e.message}", recoverable = false,
                )
            }
        }
    }

    suspend fun predict(
        prompt: String,
        onToken: (String) -> Unit,
        onDone: (PredictionResult) -> Unit,
        onError: (String) -> Unit,
        temperature: Double = 0.7,
        topK: Int = 64,
        topP: Double = 0.95,
        enableThinking: Boolean = true,
    ) {
        val id = currentContextId ?: run {
            onError("Model not initialized")
            return
        }

        mutex.withLock {
            when (_state.value) {
                is AiRuntimeState.Ready -> {
                    _state.value = AiRuntimeState.Generating(id)
                }
                else -> {
                    onError("Controller not ready: ${_state.value}")
                    return
                }
            }
        }

        touchActivity()

        val hintSession = createHintSession()
        val startNanos = System.nanoTime()
        try {
            val result = engine.predictRaw(prompt, onToken, temperature, topK, topP, enableThinking)

            hintSession?.reportActualWorkDuration(System.nanoTime() - startNanos)

            mutex.withLock {
                when (_state.value) {
                    is AiRuntimeState.Cancelling -> {
                        currentContextId?.let { _state.value = AiRuntimeState.Ready(it) }
                        return
                    }
                    else -> {
                        _state.value = AiRuntimeState.Ready(id)
                    }
                }
            }
            touchActivity()
            onDone(result)
            saveBenchmarkResult(result)
        } catch (e: CancellationException) {
            mutex.withLock {
                currentContextId?.let { _state.value = AiRuntimeState.Ready(it) }
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Predict failed"
            mutex.withLock {
                _state.value = AiRuntimeState.Error(msg, recoverable = true)
            }
            onError(msg)
        } finally {
            hintSession?.close()
        }
    }

    fun cancel() {
        if (_state.value is AiRuntimeState.Generating) {
            _state.value = AiRuntimeState.Cancelling
        }
        engine.stopPrediction()
    }

    suspend fun unload() {
        inactivityJob?.cancel()
        inactivityJob = null
        engine.releaseContext()
        currentContextId = null
        mutex.withLock {
            _state.value = AiRuntimeState.Idle
        }
    }

    suspend fun resetError() {
        mutex.withLock {
            if (_state.value is AiRuntimeState.Error) {
                _state.value = AiRuntimeState.Idle
            }
        }
    }

    private fun touchActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            while (isActive) {
                delay(60_000)
                val idle = System.currentTimeMillis() - lastActivityMs
                if (idle > 180_000 && currentContextId != null) {
                    Log.i(TAG, "Inactivity timeout reached, unloading model")
                    unload()
                }
            }
        }
    }

    suspend fun runBenchmark(): PredictionResult? {
        val id = currentContextId ?: return null
        if (_state.value !is AiRuntimeState.Ready) return null

        val benchmarkPrompt = "<|turn>system\n<|think|>\nDu bist Crisix AI, ein hilfreicher KI-Assistent.\n<turn|>\n<|turn>user\nAntworte in 2-3 Sätzen: Was ist Messaging und warum ist Privatsphäre wichtig?\n<turn|>\n<|turn>model\n"
        var result: PredictionResult? = null
        predict(
            prompt = benchmarkPrompt,
            onToken = {},
            onDone = { result = it },
            onError = { Log.w(TAG, "Benchmark failed: $it") },
            enableThinking = false,
        )
        return result
    }

    private suspend fun saveBenchmarkResult(result: PredictionResult) {
        try {
            val ctx = engine.getAppContext()
            ctx.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.AI_BENCHMARK_TOKENS] = result.tokens
                prefs[SettingsKeys.AI_BENCHMARK_TOKENS_PER_SEC] = result.tokensPerSec
                prefs[SettingsKeys.AI_BENCHMARK_TTFT_MS] = result.ttftMs
                prefs[SettingsKeys.AI_BENCHMARK_TIMESTAMP] = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private fun createHintSession(): PerformanceHintManager.Session? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val manager = engine.getAppContext().getSystemService(PerformanceHintManager::class.java) ?: return null
            val session = manager.createHintSession(intArrayOf(Process.myTid()), 100_000_000L)
            Log.i(TAG, "PerformanceHintManager session created")
            session
        } catch (e: Exception) {
            Log.w(TAG, "PerformanceHint unavailable: ${e.message}")
            null
        }
    }
}

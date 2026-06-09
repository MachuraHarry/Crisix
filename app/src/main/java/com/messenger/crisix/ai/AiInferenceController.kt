package com.messenger.crisix.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
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
            val gpuLayers = engine.getSavedGpuLayers()
            val contextSize = engine.getSavedContextSize()
            val batchSize = engine.getSavedBatchSize()
            val threads = engine.getSavedThreads()

            if (gpuLayers > 0) {
                try {
                    val ctxId = engine.initEngine(gpuLayers, contextSize, batchSize, threads)
                    currentContextId = ctxId
                    mutex.withLock { _state.value = AiRuntimeState.Ready(ctxId) }
                    startInactivityTimer()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "GPU init failed, trying CPU fallback", e)
                }
            }

            val ctxId = engine.initEngine(0, contextSize, batchSize, threads)
            currentContextId = ctxId
            mutex.withLock { _state.value = AiRuntimeState.Ready(ctxId) }
            startInactivityTimer()
        } catch (e: Exception) {
            mutex.withLock {
                _state.value = AiRuntimeState.Error(
                    e.message ?: "Init failed", recoverable = true,
                )
            }
        }
    }

    suspend fun predict(
        prompt: String,
        onToken: (String) -> Unit,
        onDone: (PredictionResult) -> Unit,
        onError: (String) -> Unit,
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

        try {
            val result = engine.predictRaw(prompt, onToken)

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
                if (idle > 600_000 && currentContextId != null) {
                    Log.i(TAG, "Inactivity timeout reached, unloading model")
                    unload()
                }
            }
        }
    }
}

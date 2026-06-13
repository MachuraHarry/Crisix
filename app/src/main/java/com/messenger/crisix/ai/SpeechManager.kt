package com.messenger.crisix.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import timber.log.Timber
import java.io.File

class SpeechManager private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var micJob: Job? = null

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _transcriptions = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val transcriptions: SharedFlow<String> = _transcriptions.asSharedFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val downloader = ModelDownloader(appContext)
    val downloadState: StateFlow<OverallDownloadState> = downloader.state

    val areModelsDownloaded: Boolean
        get() = downloader.areModelsReady()

    val nnapiFallbackReason: String?
        get() = pipeline?.nnapiFallbackReason

    suspend fun downloadModels(): Boolean {
        _state.value = SpeechState.Downloading
        val success = downloader.downloadAll()
        _state.value = if (success) SpeechState.Idle else SpeechState.Error("Download failed")
        return success
    }

    suspend fun load(): Boolean {
        if (pipeline != null) {
            Timber.d("load: pipeline already loaded, skipping")
            return true
        }
        _state.value = SpeechState.Loading
        return try {
            val modelDir = downloader.modelDir
            if (!File(modelDir).exists()) {
                _state.value = SpeechState.Error("Models not downloaded")
                return false
            }
            val config = SpeechConfig(
                modelDir = modelDir,
                useNnapi = true,
                enableEnhancer = true,
                precision = ModelPrecision.INT8,
                emitPartialTranscriptions = true,
                partialTranscriptionInterval = 0.5f,
            )
            withContext(Dispatchers.IO) {
                pipeline = SpeechPipeline(config)
            }
            pipeline?.let { collectEvents(it) }
            _state.value = SpeechState.Ready
            Timber.i("Speech pipeline loaded, NNAPI: ${pipeline?.nnapiFallbackReason ?: "OK"}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Speech pipeline load failed")
            _state.value = SpeechState.Error(e.message ?: "Load failed")
            false
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "Speech pipeline OOM")
            _state.value = SpeechState.Error("Nicht genügend Arbeitsspeicher: ${e.message}")
            false
        }
    }

    fun startListening() {
        val p = pipeline ?: run {
            Timber.w("startListening: pipeline not loaded")
            return
        }
        if (audioRecord != null) stopListening()

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) {
            _state.value = SpeechState.Error("AudioRecord buffer init failed")
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBuf * 4,
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            _state.value = SpeechState.Error("AudioRecord init failed")
            return
        }

        p.start()
        audioRecord?.startRecording()
        _state.value = SpeechState.Listening

        micJob = scope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            try {
                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: -1
                    if (read > 0) {
                        val chunk = if (read < buf.size) buf.copyOf(read) else buf
                        try {
                            p.pushAudio(chunk)
                        } catch (e: Exception) {
                            Timber.w(e, "pushAudio error")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Mic loop error")
            }
        }
    }

    fun stopListening() {
        micJob?.cancel()
        micJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        pipeline?.stop()
        if (_state.value is SpeechState.Listening || _state.value is SpeechState.Transcribing) {
            _state.value = SpeechState.Idle
        }
    }

    suspend fun unload() {
        stopListening()
        pipeline?.close()
        pipeline = null
        _state.value = SpeechState.Idle
    }

    fun destroy() {
        scope.launch { unload() }
        scope.cancel()
    }

    private fun collectEvents(p: SpeechPipeline) {
        scope.launch {
            p.events.collect { event ->
                when (event) {
                    is SpeechEvent.SpeechStarted -> {
                        _state.value = SpeechState.Listening
                    }
                    is SpeechEvent.SpeechEnded -> {
                        _state.value = SpeechState.Transcribing
                    }
                    is SpeechEvent.PartialTranscription -> {
                        _partialText.value = event.text
                    }
                    is SpeechEvent.TranscriptionCompleted -> {
                        _transcriptions.tryEmit(event.text)
                        _partialText.value = ""
                        _state.value = SpeechState.Ready
                    }
                    is SpeechEvent.ResponseAudioDelta -> {
                        // TTS — Phase 3
                    }
                    is SpeechEvent.ResponseDone -> {
                        pipeline?.resumeListening()
                    }
                    is SpeechEvent.Error -> {
                        Timber.e("Speech error: ${event.message}")
                        _state.value = SpeechState.Error(event.message)
                    }
                    else -> {}
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SpeechManager? = null

        fun getInstance(context: Context): SpeechManager {
            return instance ?: synchronized(this) {
                instance ?: SpeechManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

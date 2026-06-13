package com.messenger.crisix.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Locale

class TtsManager private constructor(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentMessageId = MutableStateFlow<String?>(null)
    val currentMessageId: StateFlow<String?> = _currentMessageId.asStateFlow()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val locale = Locale.getDefault()
                selectBestVoice(locale)
                if (tts?.voice == null) {
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                }
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
                Timber.i("TTS initialized: voice=${tts?.voice?.name}, quality=${tts?.voice?.quality}, locale=${tts?.voice?.locale}")
            } else {
                Timber.e("TTS init failed: $status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                _currentMessageId.value = null
            }
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                _currentMessageId.value = null
            }
        })
    }

    private fun selectBestVoice(locale: Locale) {
        val voices = tts?.voices ?: return
        if (voices.isEmpty()) return

        val best = voices
            .filter { v ->
                v.locale.language == locale.language &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .maxByOrNull { v ->
                var score = v.quality
                if (!v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)) {
                    score += 100
                }
                score
            }

        if (best != null) {
            tts?.voice = best
        }
    }

    fun speak(messageId: String, text: String) {
        if (!isInitialized || tts == null) return
        stop()
        _currentMessageId.value = messageId
        _isSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentMessageId.value = null
    }

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        @Volatile
        private var instance: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return instance ?: synchronized(this) {
                instance ?: TtsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

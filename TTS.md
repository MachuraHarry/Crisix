# Speech Integration — soniqo/speech-android

**SDK**: `audio.soniqo:speech:0.0.9` (Maven Central)  
**Lizenz**: Apache 2.0  
**STT**: Parakeet TDT v3 (114 Sprachen)  
**TTS**: Kokoro 82M (8 Sprachen)  
**VAD**: Silero v5 + DeepFilterNet3 Noise Cancellation  
**Acceleration**: NNAPI → Google TPU (Pixel 9 Tensor G4)  
**Modellgröße**: ~1.2 GB on-device

---

## Phase 1 — Abhängigkeit + Architektur-Grundlage

| Schritt | File | Änderung |
|---|---|---|
| 1.1 | `app/build.gradle.kts` | `implementation("audio.soniqo:speech:0.0.9")` + `ndk { abiFilters "arm64-v8a" }` |
| 1.2 | `ai/SpeechState.kt` (neu) | `sealed class SpeechState` + `SpeechEvent`, Zustände: Idle, Downloading, DownloadError, Ready, Listening, Transcribing, Speaking, Error |
| 1.3 | `ai/SpeechManager.kt` (neu) | Singleton `getInstance(context)`. Wrapped `SpeechPipeline` in StateFlow/SharedFlow. `downloadModels()`, `load()`, `startListening()`, `stopListening()`, `speak()`, `unload()` |
| 1.4 | `AndroidManifest.xml` | `<service android:name=".service.SpeechForegroundService" android:foregroundServiceType="microphone" />` |
| 1.5 | `util/NotificationHelper.kt` | Konstante `CHANNEL_SPEECH_MODELS = "crisix_speech_models"` + `createChannels()` |
| 1.6 | `service/SpeechForegroundService.kt` (neu) | Foreground Service für Modell-Download (Progress-Notification) + langes Mikrofon-Hören |

---

## Phase 2 — STT (Sprache → Text) im AI Chat

| Schritt | File | Änderung |
|---|---|---|
| 2.1 | `AiChatViewModel.kt` | `startVoiceInput()` / `stopVoiceInput()`. Sammelt `speechManager.transcriptions` |
| 2.2 | `AiChatDetailScreen.kt` | Mikrofon-Button in Input-Bar. 3 States: Idle (ic_mic), Listening (rot pulsierend), Transcribing (Spinner) |
| 2.3 | `CrisixNavHost.kt` | `SpeechManager` an ViewModel übergeben |
| 2.4 | `strings.xml` + `values-en/strings.xml` | `ai_voice_input_start`, `ai_voice_input_stop`, `ai_voice_input_processing` |

---

## Phase 3 — TTS (Text → Sprache) für KI-Antworten

| Schritt | File | Änderung |
|---|---|---|
| 3.1 | `SpeechManager.kt` | `speak(text: String)` → Kokoro → PCM → AudioTrack |
| 3.2 | `AiChatDetailScreen.kt` | Speaker-Button (play/pause) auf jeder AI-Nachricht |
| 3.3 | `AiChatViewModel.kt` | `speakMessage()`, `stopSpeaking()` |
| 3.4 | `AiSettingsScreen.kt` | Toggle "Antworten vorlesen" (Auto-TTS) |
| 3.5 | `SettingsDataStore.kt` | `AI_TTS_ENABLED = booleanPreferencesKey("ai_tts_enabled")` |
| 3.6 | `strings.xml` | `ai_tts_play`, `ai_tts_stop`, `ai_tts_auto_toggle` |

---

## Phase 4 — Settings + Model-Management

| Schritt | File | Änderung |
|---|---|---|
| 4.1 | `AiSettingsScreen.kt` | Sektion "Sprache": STT-Sprachauswahl, TTS-Toggle, Download-Status |
| 4.2 | `SettingsViewModel.kt` | `speechLanguage`, `speechModelReady`, `speechDownloadProgress` StateFlows |
| 4.3 | `SettingsDataStore.kt` | `AI_SPEECH_LANGUAGE`, `AI_TTS_ENABLED` |

---

## Phase 5 (Optional) — Systemweite Spracherkennung

| Schritt | File | Änderung |
|---|---|---|
| 5.1 | `AndroidManifest.xml` | `<service android:permission="android.permission.BIND_RECOGNITION_SERVICE">` + Intent-Filter |
| 5.2 | `service/SpeechRecognitionService.kt` (neu) | `RecognitionService` → alle 114 Sprachen via Tastatur nutzbar |

---

## Architektur — SpeechManager

```kotlin
class SpeechManager private constructor(private val context: Context) {
    val state: StateFlow<SpeechState>
    val transcriptions: SharedFlow<String>
    val partialTranscriptions: StateFlow<String>

    suspend fun downloadModels(onProgress: (Float) -> Unit)
    suspend fun load()
    fun startListening()
    fun stopListening()
    fun speak(text: String)
    suspend fun unload()

    companion object {
        @Volatile private var instance: SpeechManager? = null
        fun getInstance(context: Context): SpeechManager
            = instance ?: synchronized(this) {
                instance ?: SpeechManager(context.applicationContext).also { instance = it }
            }
    }
}
```

## Threading

| Thread | Nutzung |
|---|---|
| Native worker thread (speech-core) | STT/TTS/VAD-Inference |
| `Dispatchers.IO` (Kotlin) | `AudioRecord.read()` loop → `pushAudio()` |
| `AudioTrack` (Kotlin) | TTS PCM-Ausgabe |
| Hauptthread (UI) | StateFlow/SharedFlow Events |

Kein Konflikt mit llama.cpp: Speech nutzt NNAPI (Tensor TPU), LLM läuft auf CPU.

## Risiken

| Risiko | Maßnahme |
|---|---|
| 1.2 GB Download | WorkManager + Foreground-Service + HTTP Range-Resume (im SDK) |
| Speicherdruck | `SpeechManager.unload()` bei Inaktivität, `onTrimMemory` in Application |
| NNAPI-Fallback | `nnapiFallbackReason` in Settings anzeigen |
| Audio-Konflikte | Mutex in SpeechManager zwischen Voice-Message und Speech |

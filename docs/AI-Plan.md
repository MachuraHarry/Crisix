# KI-Assistent-Integration in Crisix – Plan

## Übersicht

Integration eines separaten KI-Assistenten-Tabs (basierend auf Gemma 4 E2B it) in die bestehende Crisix-Messenger-App. Der Assistent läuft vollständig auf dem Gerät (on-device, offline) und wird als eigener Bottom-Navigation-Eintrag (`AI`) zwischen `Chats` und `Kontakte` eingefügt.

---

## 1. Architektur-Entscheidungen

### 1.1 Modell-Runtime: LiteRT-LM

**Entscheidung: LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`)

Begründung:
- Vollständig on-device, keine Internetverbindung nötig
- Funktioniert auf **allen Android-Geräten** ab minSdk = 23 (Crisix hat minSdk = 30)
- CPU-Backend auf jedem Gerät, GPU/NPU als optionale Beschleunigung
- Optimiert für Gemma 4 E2B (2B effective params, ~2,58 GB Modell)
- Kotlin API ist ✅ **Stable**, aktiv maintained, open-source (Apache 2.0)
- Multi-Token Prediction (MTP) für bis zu 2,2x Speedup
- Unterstützt Streaming, Chat-Templates, Tool Calling, Multi-Modality
- ~1,5-3 GB RAM (CPU), ~676 MB (GPU) – akzeptabel

### 1.2 Architektur-Pattern: MVVM (wie bestehender Crisix-Code)

Neue Komponenten:
- **Model**: `AiChatRepository`, `AiSession` (kapselt LiteRT-LM Session)
- **ViewModel**: `AiChatViewModel` (analog zu `ChatDetailViewModel`)
- **View**: `AiChatScreen` (analog zu `ChatDetailScreen`)
- **Route**: `NavRoutes.AI_CHAT` + `NavRoutes.AI_CHAT_DETAIL`

### 1.3 Modell-Download & Asset-Management

Das Modell (`.litertlm`-Datei) wird nicht im APK mitgeliefert (~2,58 GB). Stattdessen:
- Beim ersten Start des AI-Tabs wird das Modell per Direktdownload von HuggingFace heruntergeladen
- Fortschrittsanzeige während des Downloads mit Resume-Support
- Quelle: `litert-community/gemma-4-E2B-it-litert-lm` von HuggingFace
- Optional: ADB-Push für Entwickler (Modell direkt nach `/sdcard/Android/data/com.messenger.crisix/files/`)

---

## 2. Dateien & Änderungen (Implementierungs-Reihenfolge)

### Phase 1: Grundlage schaffen (Navigation & UI-Struktur)

| # | Datei | Änderung |
|---|-------|----------|
| 1 | `app/build.gradle.kts` | Dependency `com.google.ai.edge.litertlm:litertlm-android:latest.release` hinzufügen |
| 2 | `app/src/main/res/values/strings.xml` | Neue Strings: `bottom_nav_ai`, `ai_chat_title`, `ai_input_placeholder`, etc. |
| 3 | `app/src/main/res/values-en/strings.xml` | Englische Übersetzungen der neuen Strings |
| 4 | `app/src/main/res/drawable/ic_ai.xml` | Neues Icon für den AI-Tab (z. B. „smartphone“ oder „auto_awesome“) |
| 5 | `app/src/main/java/com/messenger/crisix/ui/navigation/NavRoutes.kt` | Neue Routes: `AI_CHAT`, `AI_CHAT_DETAIL` |
| 6 | `app/src/main/AndroidManifest.xml` | `<uses-native-library>` Einträge für GPU-Backend hinzufügen |
| 7 | `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixNavHost.kt` | 5. Bottom-Nav-Button „AI“ hinzufügen (zwischen Chats & Kontakte), `composable(AI_CHAT)` registrieren |
| 8 | `app/src/main/java/com/messenger/crisix/data/SettingsDataStore.kt` | AI-spezifische Einstellungen: `ai_model_downloaded`, `ai_model_path`, `ai_system_prompt` |

### Phase 2: AI-Engine (LiteRT-LM Integration)

| # | Datei | Änderung |
|---|-------|----------|
| 9 | `app/src/main/java/com/messenger/crisix/ai/AiModelManager.kt` | **NEU**: Manager für LiteRT-LM. Lädt Modell, verwaltet `Engine`-Lifecycle, lädt bei Bedarf herunter |
| 10 | `app/src/main/java/com/messenger/crisix/ai/AiChatRepository.kt` | **NEU**: Kapselt die Kommunikation mit dem Modell. Bietet `suspend fun generateResponse(prompt: String, history: List<AiMessage>): Flow<String>` (Streaming) und `suspend fun generateFullResponse(...): String` |
| 11 | `app/src/main/java/com/messenger/crisix/ai/AiMessage.kt` | **NEU**: Datenklasse für KI-Chat-Nachrichten (analog zu `Message` in MessageBubble.kt, aber ohne Transport/E2EE-Felder) |

### Phase 3: ViewModel & Screen

| # | Datei | Änderung |
|---|-------|----------|
| 12 | `app/src/main/java/com/messenger/crisix/ui/viewmodel/AiChatViewModel.kt` | **NEU**: ViewModel für KI-Chat. State: `messages: List<AiMessage>`, `isLoading: Boolean`, `modelStatus: ModelStatus`. Methoden: `sendMessage()`, `clearChat()`, `loadModel()` |
| 13 | `app/src/main/java/com/messenger/crisix/ui/screens/AiChatScreen.kt` | **NEU**: Hauptscreen des AI-Tabs. TopBar mit "Crisix AI"-Titel, Plus-Button (neuer Chat) und Settings-Button. Zeigt Chat-Verlauf und Input-Bar (ähnlich `ChatDetailScreen`, aber vereinfacht: nur Text). Zeigt Modell-Status und Download-Fortschritt |

### Phase 4: Integration & Tests

| # | Datei | Änderung |
|---|-------|----------|
| 14 | `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixApp.kt` | `AiModelManager` initialisieren, an `CrisixNavHost` übergeben |
| 15 | Testing | Manuelle Tests: Modell-Download, Chat-Funktionalität, Speicherverbrauch |

---

## 3. Detail-Design

### 3.1 NavRoutes.kt – neue Routen

```kotlin
const val AI_CHAT = "ai_chat"
```

### 3.2 CrisixNavHost.kt – Bottom-Nav-Änderung

- `bottomNavRoutes` erweitern um `NavRoutes.AI_CHAT`
- Neuer `NavigationBarItem` zwischen Chats und Kontakte:

| Position | Vorher | Nachher |
|----------|--------|---------|
| Index 0 | Chats | Chats |
| Index 1 | Kontakte | **AI (NEU)** |
| Index 2 | Netzwerk | Kontakte |
| Index 3 | Einstellungen | Netzwerk |
| Index 4 | – | Einstellungen |

NavigationBarItem für AI:
```kotlin
NavigationBarItem(
    selected = currentRoute == NavRoutes.AI_CHAT,
    onClick = {
        navController.navigate(NavRoutes.AI_CHAT) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    },
    icon = { Icon(painter = painterResource(id = R.drawable.ic_ai), ...) },
    label = { Text(stringResource(R.string.bottom_nav_ai)) },
    colors = ...
)
```

### 3.3 AiModelManager – LiteRT-LM Integration

```kotlin
class AiModelManager(private val context: Context) {
    sealed class ModelStatus {
        object NotDownloaded : ModelStatus()
        data class Downloading(val progress: Float) : ModelStatus()
        object Ready : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var engine: Engine? = null

    suspend fun downloadModel()
    suspend fun loadModel(): Boolean
    fun getEngine(): Engine?
    fun close()
}
```

**LiteRT-LM Kotlin API (offiziell):**
```kotlin
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend

// Konfiguration
val engineConfig = EngineConfig(
    modelPath = modelFile.absolutePath,
    backend = Backend.GPU(),       // oder Backend.CPU(), Backend.NPU(...)
    cacheDir = context.cacheDir.path, // beschleunigt zweiten Load
)

// Engine initialisieren (auf Hintergrund-Thread, dauert bis zu 10s)
val engine = Engine.create(engineConfig).also { it.initialize() }

// Text generieren (blockierend)
val response: String = engine.generateResponse(prompt)

// Streaming
engine.generateResponseAsync(prompt) { partialResult, done ->
    // tokenweise UI-Updates
}
```

**AndroidManifest.xml – GPU-Backend aktivieren:**
```xml
<application>
    <uses-native-library android:name="libvndksupport.so" android:required="false"/>
    <uses-native-library android:name="libOpenCL.so" android:required="false"/>
</application>
```

### 3.4 AiChatRepository – Chat-Logik

```kotlin
class AiChatRepository(private val modelManager: AiModelManager) {
    suspend fun generateResponseStream(
        messages: List<AiMessage>,
        newMessage: String
    ): Flow<String> = callbackFlow {
        val engine = modelManager.getEngine()
            ?: throw IllegalStateException("Model not loaded")

        val prompt = buildChatPrompt(messages, newMessage)
        engine.generateResponseAsync(prompt) { partialResult, done ->
            trySend(partialResult)
            if (done) close()
        }
        awaitClose { /* cleanup */ }
    }

    private fun buildChatPrompt(messages: List<AiMessage>, newMessage: String): String {
        // System-Prompt + Chat-History + neue Nachricht im Gemma Chat Template
    }
}
```

### 3.5 AiChatViewModel

```kotlin
class AiChatViewModel(
    private val repository: AiChatRepository,
    private val modelManager: AiModelManager,
) : ViewModel() {

    data class UiState(
        val messages: List<AiMessage> = emptyList(),
        val isLoading: Boolean = false,
        val modelStatus: ModelStatus = ModelStatus.NotDownloaded,
        val currentResponse: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) { ... }
    fun clearChat() { ... }
    fun loadModel() { ... }
}
```

### 3.6 AiChatScreen (UI-Konzept)

- **TopAppBar**: Titel "Crisix AI", Plus-Button (neuer Chat) und Settings-Button (AI-Einstellungen)
- **Wenn Modell nicht geladen**: Download-Button + Fortschrittsbalken
- **Wenn Modell bereit**: Chat-UI ähnlich ChatDetailScreen
  - Nachrichtenliste (`LazyColumn`) mit MessageBubble-ähnlichen Einträgen
  - Input-Bar unten (nur Text, kein Media/Voice – kann später erweitert werden)
  - Senden-Button
- **Bei Fehler**: Fehlermeldung mit Retry-Button

### 3.7 Strings (Deutsch)

```xml
<string name="bottom_nav_ai">AI</string>
<string name="ai_chat_title">Crisix AI</string>
<string name="ai_input_placeholder">Frage Crisix AI…</string>
<string name="ai_model_download_title">Crisix AI Modell herunterladen</string>
<string name="ai_model_download_body">Für Crisix AI muss das Gemma 4 Modell (~2,6 GB) einmalig heruntergeladen werden.</string>
<string name="ai_model_download_progress">Lade Modell herunter… %1$d%%</string>
<string name="ai_model_download_complete">Modell bereit!</string>
<string name="ai_model_error">Fehler beim Laden des KI-Modells</string>
<string name="ai_model_retry">Erneut versuchen</string>
<string name="ai_clear_chat">Chat leeren</string>
<string name="ai_clear_chat_confirm">Möchtest du den gesamten Chat-Verlauf löschen?</string>
<string name="ai_thinking">Denke nach…</string>
```

### 3.8 Strings (Englisch)

```xml
<string name="bottom_nav_ai">AI</string>
<string name="ai_chat_title">Crisix AI</string>
<string name="ai_input_placeholder">Ask Crisix AI…</string>
<string name="ai_model_download_title">Download Crisix AI Model</string>
<string name="ai_model_download_body">The Gemma 4 model (~2.6 GB) needs to be downloaded once for Crisix AI.</string>
<string name="ai_model_download_progress">Downloading model… %1$d%%</string>
<string name="ai_model_download_complete">Model ready!</string>
<string name="ai_model_error">Failed to load AI model</string>
<string name="ai_model_retry">Try again</string>
<string name="ai_clear_chat">Clear chat</string>
<string name="ai_clear_chat_confirm">Delete the entire conversation history?</string>
<string name="ai_thinking">Thinking…</string>
```

---

## 4. Datenmodell (AiMessage)

```kotlin
data class AiMessage(
    val id: String,
    val role: AiRole, // USER oder ASSISTANT
    val text: String,
    val timestamp: Long,
)

enum class AiRole { USER, ASSISTANT }
```

Keine Verschlüsselung, kein Transport, kein Status – das KI-Modell läuft lokal.

---

## 5. Abhängigkeiten (build.gradle.kts)

```kotlin
// LiteRT-LM für on-device LLM Inference
implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

// Für Modell-Download (bereits vorhanden)
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

**Aktueller Stand**: Crisix hat bereits OkHttp 4.12.0. Nur eine neue Dependency nötig.

**AndroidManifest.xml**: GPU-Backend erfordert:
```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```

---

## 6. Risiken & offene Fragen

| Risiko | Lösung |
|--------|--------|
| Modell-Download ~2,6 GB – Nutzerabbruch | Fortschrittsanzeige + Resume-Support |
| Hoher RAM-Verbrauch (~1,5-3 GB CPU / ~676 MB GPU) | GPU bevorzugen, Engine schließen bei Verlassen des AI-Tabs |
| LiteRT-LM API Änderungen | Abstraktion durch `AiModelManager` – austauschbar |
| Gerät ohne GPU/OpenCL | Fallback auf `Backend.CPU()` – funktioniert überall |
| Gerät ohne ausreichend Speicher | Speicherprüfung vor Download, Warnung anzeigen |
| Engine-Initialisierung dauert bis zu 10s | Auf Hintergrund-Coroutine, Loading-Indikator in UI |
| System-Prompt anpassbar? | In SettingsDataStore speicherbar |

**Wichtig vor Implementierung**:
- Prüfen, ob `latest.release` von `litertlm-android` auf dem Zielgerät läuft
- Prüfen, ob das Modell `gemma-4-E2B-it.litertlm` von HuggingFace korrekt geladen wird
- Gerät mit ≥6 GB RAM zum Testen empfohlen

---

## 7. Meilensteine

| Phase | Beschreibung | Aufwand |
|-------|-------------|---------|
| **1** | Navigation, Strings, Icons, Routes | ~2h |
| **2** | AiModelManager + LiteRT-LM Wrapper | ~4h |
| **3** | AiChatViewModel + AiChatScreen | ~4h |
| **4** | Integration in CrisixApp + Testing | ~3h |
| **Gesamt** | | **~13h** |

---

## 8. Referenzen

- LiteRT-LM Overview: https://ai.google.dev/edge/litert-lm/overview
- LiteRT-LM Android (Kotlin) Guide: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM GitHub: https://github.com/google-ai-edge/LiteRT-LM
- Gemma 4 E2B Model Card: https://ai.google.dev/gemma/docs/core/model_card_4
- HuggingFace (fertig konvertiertes Modell): https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Google AI Edge Gallery (Referenz-App): https://github.com/google-ai-edge/ai-edge-gallery
- Gemma 4 Announcement (Blog): https://developers.googleblog.com/en/bring-state-of-the-art-agentic-skills-to-the-edge-with-gemma-4/
- Crisix existierende Chat-Struktur: `ChatDetailScreen.kt`, `AdaptiveInputBar.kt`, `ChatDetailViewModel.kt`, `MessageBubble.kt`

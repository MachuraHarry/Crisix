# Crisix AI – Vollständiger Gemma 4 E2B Integrationsplan

## Übersicht

Crisix AI nutzt **Gemma 4 E2B IT** (Q4_0, ~5 GB GGUF) über llama.cpp + Vulkan GPU-Backend.
Ziel: **alle Fähigkeiten des Modells** in Crisix verfügbar machen — Text, Vision, Tool Calling, Reasoning, strukturierte Ausgaben, Performance.

---

## 1. Modell-Steckbrief: Gemma 4 E2B IT

| Eigenschaft | Wert |
|---|---|
| **Architektur** | Gemma 4, 35 Layers, 4.6B Params |
| **Kontextlänge** | 131.072 Tokens (128K effektiv) |
| **Attention** | Sliding Window Attention (SWA) + Global Attention |
| **Shared KV Layers** | 20 (Sparsam im RAM) |
| **Embedding-Dim** | 1536 |
| **Heads** | 8 Query, 1 Key/Value |
| **Sprachen** | 140+ (inkl. Deutsch) |
| **Chat-Template** | Jinja (Gemma-Format) |
| **Quantisierung (GGUF)** | Q4_0 (~5 GB auf Disk, ~4 GB im RAM) |

### 1.1 Fähigkeiten des Modells

| Fähigkeit | Status in Crisix | Benötigt |
|---|---|---|
| **Text-Chat** | ✅ funktioniert | – |
| **Vision (Bilder erkennen)** | ❌ fehlt | mmproj-Datei (Vision Encoder) + Image Input UI |
| **Tool Calling** | ❌ fehlt | Chat-Template + JSON-Parsing + Tool-Registry |
| **Thinking/Reasoning** | ❌ fehlt | `<start_of_turn>think` im Template |
| **Strukturierte Ausgaben** | ❌ fehlt | llama.cpp Grammar (JSON Schema) |
| **131K Kontext** | ⬜ begrenzt auf 2K | n_ctx erhöhen, SWA testen |
| **Streaming** | ✅ funktioniert | – |
| **System-Prompt** | ⬜ editierbar, Key existiert | UI in Settings |
| **Multi-Turn Chat** | ✅ funktioniert | – |
| **Speculative Decoding** | ❌ fehlt | Draft-Model (separates GGUF) |

---

## 2. Phasenplan

### Phase 1: Kontext & Performance (aktuell)
**Status: ✅ erledigt**

- Vulkan GPU-Backend integriert
- GPU-Layers konfigurierbar (0–99)
- Context-Size, Batch-Size, Threads einstellbar
- Benchmark-Anzeige (tok/s)
- Modell-Info in Settings

---

### Phase 2: Vision / Bilder-Erkennung 🔥

**Ziel:** User kann ein Bild an Crisix AI senden, das Modell beschreibt/analysiert es.

**Benötigt:**
1. **mmproj-Datei** (Multimodal Projection) – konvertiert Bild → Embedding-Vektor für das LLM
   - Quelle: `google/gemma-4-E2B-it` von HuggingFace
   - Konvertieren mit llama.cpp `convert_hf_to_gguf.py --mmproj`
   - Datei: `mmproj.gguf` (~600 MB)
2. **Image Input UI** – Galerie/Kamera-Button im Chat-Input
3. **Native Multimodal Pipeline** – `rn-llama.cpp` / `jni.cpp` unterstützen bereits `initMultimodal()` und `image_fds`
4. **Download** – mmproj-Datei optional herunterladen (nur bei Bedarf)

**Dateien:**
| # | Datei | Änderung |
|---|-------|----------|
| 2.1 | `AiChatDetailScreen.kt` | Bild-Button (Galerie + Kamera) im Input-Bar |
| 2.2 | `AiChatViewModel.kt` | `sendImage(uri: Uri)` – Bild an Repository übergeben |
| 2.3 | `AiChatRepository.kt` | Image-FD an `predict()` übergeben |
| 2.4 | `AiModelManager.kt` | `buildEngineConfig()` um `mmproj_fd` + `image_fds` erweitern |
| 2.5 | `NiModelManager.kt` | Mmproj-Download + Init |
| 2.6 | `strings.xml` | Neue Strings (Bild senden, Bild wird analysiert, etc.) |
| 2.7 | Native (AAR) | `initMultimodal()` wird bereits im `jni.cpp:287` aufgerufen wenn `mmproj_fd >= 0` |

**Ablauf:**
```
User tippt Bild-Button → Galerie/Kamera → URI
→ ViewModel.sendImage(uri)
→ Repository: öffnet FD, ruft modelManager.predictImage(prompt, imageFds)
→ Native: mtmd_wrapper verarbeitet Bild, hängt Embeddings an Prompt
→ LLM generiert Antwort mit Bild-Kontext
```

---

### Phase 3: Tool Calling / Agent-Fähigkeiten 🔥🔥

**Ziel:** Crisix AI kann externe Aktionen ausführen (z.B. Wetter abrufen, Wikipedia suchen, Kontakte durchsuchen).

**Wie es funktioniert:**
Das Gemma-4-Chat-Template unterstützt `<start_of_turn>tool` Blöcke. Das Modell kann antworten mit:
```
<start_of_turn>tool
{"name": "get_weather", "arguments": {"city": "Berlin"}}
<end_of_turn>
```
Die App parst das JSON, führt die Funktion aus, und sendet das Ergebnis zurück:
```
<start_of_turn>tool_result
{"temperature": 22, "condition": "sonnig"}
<end_of_turn>
```
Dann generiert das Modell die natürliche Antwort.

**Benötigt:**
1. **Tool Registry** – `ToolRegistry` mit registrierbaren Funktionen
2. **Tool-Definition im System-Prompt** – JSON-Schema pro Tool
3. **Response Parser** – erkennt `<start_of_turn>tool` Blöcke im Stream
4. **Tool Executor** – führt native/Web-Funktionen aus (auf IO-Thread)
5. **Built-in Tools:**
   - `search_web` – DuckDuckGo/Wikipedia API
   - `get_time` – Systemzeit
   - `get_battery` – Akkustand
   - `search_contacts` – Crisix-Kontakte durchsuchen
   - `calculator` – Mathematische Ausdrücke

**Dateien:**
| # | Datei | Änderung |
|---|-------|----------|
| 3.1 | `ai/tools/ToolRegistry.kt` | NEU: Registrierung + Schema-Generierung |
| 3.2 | `ai/tools/ToolExecutor.kt` | NEU: Führt Tool-Calls aus |
| 3.3 | `ai/tools/BuiltinTools.kt` | NEU: Built-in Tools |
| 3.4 | `AiChatRepository.kt` | Tool-Loop: bis zu N Runden Tool→Result→Model |
| 3.5 | `AiChatDetailScreen.kt` | Tool-Indikator ("Führe Tool aus…") |
| 3.6 | `AiModelManager.kt` | Streaming-tauglicher Parser für Tool-Blöcke |

---

### Phase 4: Thinking / Reasoning-Modus 🔥

**Ziel:** User kann "Denkmodus" aktivieren – das Modell "denkt" intern nach, bevor es antwortet.

**Wie es funktioniert:**
Gemma 4 unterstützt `<start_of_turn>think` Blöcke im Chat-Template:
```
<start_of_turn>user
Was ist 15% von 280?
<end_of_turn>
<start_of_turn>think
15% bedeutet 15/100. 280 * 0.15 = 42.
<end_of_turn>
<start_of_turn>model
15% von 280 sind 42.
<end_of_turn>
```

**Implementierung:**
- Toggle "Reasoning" im Chat-Header (Glühbirne-Icon)
- Wenn aktiv: `<start_of_turn>think` ins Template injizieren
- Thinking-Text in UI anzeigen (collapsed, aufklappbar)

**Dateien:**
| # | Datei | Änderung |
|---|-------|----------|
| 4.1 | `AiChatDetailScreen.kt` | Thinking-Toggle + Thinking-Bubble (collapsed) |
| 4.2 | `AiChatRepository.kt` | `enableThinking` im Prompt-Template |
| 4.3 | `strings.xml` | Neue Strings |

---

### Phase 5: Strukturierte Ausgaben (JSON/Grammar)

**Ziel:** Modell antwortet im definierten JSON-Format – nützlich für App-Integration.

**Implementierung:**
- llama.cpp unterstützt GBNF-Grammar für constrained decoding
- `jni.cpp` `doCompletion` hat bereits `grammar`-Parameter
- `llama.cpp` `common` hat JSON-Schema→Grammar-Konverter
- UI: "JSON-Modus" Toggle + Schema-Input (optional, für Power-User)

**Dateien:**
| # | Datei | Änderung |
|---|-------|----------|
| 5.1 | `AiModelManager.kt` | `predict()` um `grammar`-Parameter erweitern |
| 5.2 | `AiChatDetailScreen.kt` | JSON-Toggle im Chat-Menü |

---

### Phase 6: 131K Kontext-Fenster

**Ziel:** Volles 131K-Kontextfenster nutzen statt 2K.

**Aktuell:** `n_ctx = 2048` (Default in Settings, Slider 512–8192)
**Ziel:** Slider auf 512–32768 erweitern (höheres frisst RAM)

**Risiko:** Bei 131K Kontext ~8 GB RAM nötig → für ~5 GB-Geräte nicht machbar
**Empfehlung:** Max auf 32K setzen (Slider), Advanced-Option für mehr

---

### Phase 7: UI/UX – Chat-Erlebnis verbessern

**Features:**
- **Message Reactions** – 👍👎 auf AI-Antworten (Feedback)
- **Regenerate** – Antwort neu generieren mit Button
- **Edit last message** – Letzte User-Nachricht editieren und neu senden
- **Conversation Export** – Chat als Text/Markdown exportieren
- **Voice Input** – Spracheingabe (Android SpeechRecognizer)
- **TTS Output** – Antwort vorlesen (Android TTS, Modell hat Vocoder-Support via rn-tts.cpp)
- **Animated typing dots** – Statt Spinner (CSS-artig)
- **Code-Blöcke mit Syntax-Highlighting** – wenn Markdown re-enabled
- **Scroll-to-Bottom FAB** – Wenn hochgescrollt
- **Date headers** – "Heute", "Gestern" im Chat

---

### Phase 8: Speculative Decoding

**Ziel:** 1.5–2x Speedup durch Draft-Model.

**Benötigt:**
- Separates Draft-Model GGUF (~200 MB, z.B. Gemma 4 0.5B)
- llama.cpp speculative decoding Support
- Konfiguration in AiModelManager

**Priorität:** Niedrig (erst wenn alles andere läuft)

---

## 3. Priorisierung & Meilensteine

| Phase | Beschreibung | Aufwand | Prio |
|-------|-------------|---------|------|
| **1** | Kontext & Performance | ✅ done | – |
| **2** | Vision / Bilder | ~6h | 🔥🔥🔥 |
| **3** | Tool Calling | ~8h | 🔥🔥 |
| **4** | Thinking/Reasoning | ~3h | 🔥 |
| **5** | JSON/Grammar | ~3h | 🔥 |
| **6** | 131K Kontext | ~1h | 🔥 |
| **7** | UI/UX Polish | ~6h | 🔥 |
| **8** | Speculative Decoding | ~4h | ⭐ |

**Reihenfolge:** 2 → 3 → 4 → 6 → 5 → 7 → 8

---

## 4. Risiken

| Risiko | Lösung |
|---|---|
| mmproj-Konvertierung schlägt fehl | Fertige mmproj von HuggingFace Community nutzen |
| Vision-Modus RAM-Verbrauch zu hoch | Optional, nur laden wenn Bild gesendet wird |
| Tool-Loop blockiert UI | Auf IO-Dispatcher, mit Timeout |
| 131K Kontext OOM | Max auf 32K begrenzt in UI |
| Draft-Model inkompatibel | Fallback ohne SpecDec |

---

## 5. Architektur-Übersicht (nach allen Phasen)

```
┌─────────────────────────────────────────────┐
│  AiChatDetailScreen                         │
│  ┌─────────┐ ┌──────────┐ ┌──────────────┐ │
│  │ Thinking │ │ Vision   │ │ Tool-Indikator│ │
│  │ Toggle   │ │ Button   │ │ "Führt aus…" │ │
│  └─────────┘ └──────────┘ └──────────────┘ │
│  ┌─────────────────────────────────────────┐│
│  │  Chat Messages (LazyColumn)             ││
│  │  ┌──────────────────────────────────┐   ││
│  │  │ Thinking Bubble (collapsed)       │   ││
│  │  │ Image Thumbnail + Caption         │   ││
│  │  │ Tool Call Card (→ Result)         │   ││
│  │  │ Markdown Response                 │   ││
│  │  └──────────────────────────────────┘   ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │  Input Bar [🎤] [📷] [Text] [▶️Send]    ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  AiChatViewModel                │
│  - sendMessage()                │
│  - sendImage()                  │
│  - toggleThinking()             │
│  - toggleToolMode()             │
└─────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  AiChatRepository               │
│  - buildPrompt()                │
│  - Tool Loop (bis zu N Runden)  │
│  - Image Encoding               │
└─────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  AiModelManager                 │
│  - predict()                    │
│  - predictImage()               │
│  - mmproj management            │
│  - Tool Registry                │
└─────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  llama.cpp (Native)             │
│  - Vulkan GPU Backend           │
│  - Multimodal (mtmd)            │
│  - Grammar constrained          │
│  - Speculative Decoding         │
└─────────────────────────────────┘
```

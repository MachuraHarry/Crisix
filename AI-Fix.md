# Crisix AI & kotlinllamacpp – Optimierungs- und Fix-Plan

## 📊 Tiefenanalyse

### Überblick der Architektur

**kotlinllamacpp Library** (`/home/harry/src/kotlinllamacpp/`)
- `LlamaAndroid` – Entry Point, verwaltet Contexts via `ConcurrentHashMap<Int, LlamaContext>`
- `LlamaContext` – JNI-Wrapper um native llama.cpp Context
- `LlamaHelper` – Coroutine-Convenience-Wrapper

**Crisix AI** (`/home/harry/AndroidStudioProjects/Crisix/app/src/main/java/com/messenger/crisix/ai/`)
- `AiModelManager` – Singleton, managed LlamaAndroid, Download, Konfiguration
- `AiInferenceController` – State-Machine (Idle→Loading→Ready→Generating→Cancelling)
- `AiAgent` – Tool-using Agent mit XML-Tool-Tags
- `AiChatRepository` – Einfacher Chat (ohne Tools)
- `AiToolExecutor` – Führt Tools gegen lokale DB aus
- `AiHardwareProfile` – Gerätespezifische Auto-Konfiguration

---

## 🐛 Kritische Bugs & Architekturprobleme

### kotlinllamacpp Library

| # | Problem | Datei | Details |
|---|---------|-------|---------|
| L1 | Doppeltes Native-Loading | LlamaAndroid.kt + LLamaContext.kt | Beide companion object Init-Blöcke laden identisch die .so-Libs. Kann zu UnsatisfiedLinkError führen |
| L2 | Blocking JNI-Call | LLamaContext.kt:185 | doCompletion() blockiert aufrufenden Thread bis ALLE Tokens generiert sind |
| L3 | Zufalls-ID-Kollisionen | LlamaAndroid.kt:105 | Random().nextInt().absoluteValue – nicht atomic |
| L4 | Kein echtes Cancellation | LLamaContext.kt:223 | stopCompletion() muss von ANDEREM Thread kommen |

### Crisix AI

| # | Problem | Datei | Details |
|---|---------|-------|---------|
| C1 | Blocking Predict zerstört Coroutine-Cancellation | AiModelManager.kt:405-468 | llama.launchCompletion() blockiert. predictRaw kann nicht gecancelled werden |
| C2 | Unsicherer Token-Callback | AiModelManager.kt:121-125 | predictCallback ist @Volatile, racing zwischen Native-Thread und Kotlin |
| C3 | Fragiles Token-Stripping | AiModelManager.kt:440-451 | 10 replace()-Aufrufe PRO TOKEN, versucht partielle Tags zu strippen |
| C4 | Temperature=1.0 hardcodiert | AiModelManager.kt:417 | Zu heiß für die meisten Modelle |
| C5 | Kein KV-Cache-Reuse | Gesamte Architektur | Jeder predict()-Call evaluiert gesamten Prompt neu |
| C6 | Thread-Leak | AiModelManager.kt:111 | newSingleThreadContext wird nur in close() geschlossen |
| C7 | Kein Memory-Pressure-Handling | Fehlend | Modell bleibt im RAM bei onTrimMemory() |

---

## 📋 Umsetzungsplan

### Phase 1: Quick Fixes (niedriges Risiko, hoher Impact) ⏱ ~1-2h ✅ ABGESCHLOSSEN

- [x] **C4**: Temperature von 1.0 → 0.7 in `AiModelManager.predictRaw()`
- [x] **C3**: Token-Stripping durch `startsWith`-Check ersetzen statt 10 Replace-Aufrufen
- [x] **C2**: `predictCallback` thread-sicher machen mit `AtomicReference`
- [x] **L3**: `AtomicInteger` statt `Random()` für Context-IDs in `LlamaAndroid`
- [x] **C6**: `inferenceContext` durch `Dispatchers.IO.limitedParallelism(1)` ersetzen
- [x] **L1**: Native-Loading in `LlamaContext` entfernen (nur `LlamaAndroid` lädt)
- [x] **C7**: `ComponentCallbacks2` / `onTrimMemory()` in `CrisixApplication`

### Phase 2: Non-blocking Inference ⏱ ~4-8h ✅ ABGESCHLOSSEN

- [x] **C1**: predictRaw mit `suspendCancellableCoroutine` + dediziertem JNI-Thread – echte Coroutine-Cancellation
- [x] **L2**: C++ `is_interrupted` bereits vorhanden; `stopPrediction()` synchron gemacht für sofortige Wirkung
- [x] Inactivity-Timer von 10min → 3min (besser für Akku)

### Phase 3: KV-Cache & Performance ⏱ ~3-4h ✅ ABGESCHLOSSEN

- [x] **C5**: Session-Save/Load zwischen Nachrichten – `saveSessionState()` / `loadSessionState()` in `AiModelManager`
- [x] Prompt-Continuation: Wenn Session aktiv, nur neue Nachricht senden (nicht gesamte History)
- [x] Agent-Tool-Zyklen geschützt: Session-Continuation nur wenn keine TOOL_RESULT-Nachrichten
- [x] Session-Cleanup bei `releaseContext()` / `unloadModel()`

### Phase 4: Streaming UX & Flüssigkeit ⏱ ~3-5h ✅ ABGESCHLOSSEN

- [x] Token-Batching: Tokens werden alle ~16ms gebatched (60fps-Cap) – drastisch weniger UI-Recompositions
- [x] Final-Flush: Verbleibende Tokens werden nach JNI-Completion ausgegeben
- [x] Modell-Preload: `preloadIfNeeded()` lädt Modell im Hintergrund beim Öffnen des AI-Chats
- [x] Sampling-Presets: `temperature`, `topK`, `topP` als Parameter in `predictRaw` → konfigurierbar

### Phase 5: Robustheit ⏱ ~2-4h ✅ ABGESCHLOSSEN

- [x] Native-Crash Error-Recovery: `launchCompletion` JNI-Abstürze werden gefangen, Session gecleared, als `RuntimeException` propagiert
- [x] Benchmark/Diagnose-Tooling: TTFT (Time-To-First-Token) in `PredictionResult` und Log (`Benchmark: N tokens, Xms total, TTFT=Yms, Z.Z tok/s`)
- [x] Prompt-Truncator mit echtem Tokenizer: `AiPromptTruncator` akzeptiert `tokenCounter`-Lambda; `AiModelManager.countTokens()` nutzt `llama.tokenize()`; Fallback auf `chars/3.5`-Schätzung
- [x] Prompt-Builder (`AiAgent`, `AiChatRepository`) nutzen echten Tokenizer via `modelManager::countTokens`

---

## 🎨 Flüssigkeits-Verbesserungen (UX)

| Verbesserung | Impact |
|-------------|--------|
| Adaptive Token-Budget-Anzeige (echter Tokenizer statt chars/2) | Mittel |
| Streaming-Chunk-Größe optimieren (Batching ~16ms) | Hoch |
| First-Token-Latenz reduzieren (Prefill-Progress anzeigen) | Mittel |
| Warm-Up / Preload beim App-Start | Hoch |
| Sampling-Presets für User | Niedrig |
| Response-Streaming sofort abbrechbar | Hoch |
| Tool-Execution animierter visualisieren | Niedrig |

---

*Erstellt am 9. Juni 2026 – Phase 1 beginnt jetzt.*

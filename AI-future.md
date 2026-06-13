# AI Future — Erweiterungsplan

## Phase 1 — Neue Agent-Tools

### 1a. Datenbank-Erweiterung (Notes + Reminders)

| Schritt | File | Änderung |
|---|---|---|
| Room Entity `AiNote` | `AiNote.kt` (neu) | `id`, `title`, `content`, `updatedAt`, `createdAt` |
| Room Entity `AiReminder` | `AiReminder.kt` (neu) | `id`, `title`, `dueTime`, `isCompleted`, `createdAt` |
| DAO `AiNoteDao` | `AiNoteDao.kt` (neu) | CRUD Operations |
| DAO `AiReminderDao` | `AiReminderDao.kt` (neu) | CRUD + query by time |
| AppDatabase | `AppDatabase.kt` | Entities + DAOs registrieren, Migration 9→10 |
| AlarmManager + Receiver | `ReminderAlarmReceiver.kt` (neu) | Alarm bei Fälligkeit, Notification |
| Manifest | `AndroidManifest.xml` | ReminderReceiver registrieren |

### 1b. Tool-Registrierung in `AiToolRegistry.kt`

| Tool | Parameter | Funktionalität |
|---|---|---|
| `web_search` | `query: string` | DuckDuckGo Instant Answer API (`api.duckduckgo.com?q=...&format=json`), OkHttp, kein API-Key |
| `create_reminder` | `title: string`, `due: string` (ISO 8601) | Speichert in DB, setzt AlarmManager + Notification |
| `create_note` | `title: string`, `content: string` | Speichert in DB |
| `get_notes` | `search: string?` (optional) | Sucht/Listet Notizen aus DB |
| `remember_info` | `key: string`, `value: string` | Key-Value in DataStore (`AI_REMEMBERED_*`) |
| `get_remembered_info` | `key: string` (optional) | Gibt gespeicherte Infos zurück |

### 1c. Executor-Erweiterung in `AiToolExecutor.kt`

- `executeWebSearch(query)` → OkHttp GET, JSON parsen, Ergebnis formatieren
- `executeCreateReminder(title, due)` → NoteDao einfügen + AlarmManager setzen
- `executeCreateNote(title, content)` → NoteDao einfügen
- `executeGetNotes(search)` → NoteDao abfragen
- `executeRememberInfo(key, value)` → DataStore schreiben (`AI_REMEMBERED_` prefix)
- `executeGetRememberedInfo(key)` → DataStore lesen, alle wenn key null

### 1d. System-Prompt-Update

- `AiPrompts.kt`: `buildConversationPrompt()` um remembered-content erweitern

---

## Phase 2 — Benchmark / Performance-View

| Schritt | File | Änderung |
|---|---|---|
| SettingsKeys | `SettingsDataStore.kt` | `AI_BENCHMARK_TOKENS`, `AI_BENCHMARK_TOKENS_PER_SEC`, `AI_BENCHMARK_TTFT_MS`, `AI_BENCHMARK_TIMESTAMP` |
| PredictionResult speichern | `AiInferenceController.kt` | Nach `onDone` → in DataStore persistieren |
| SettingsViewModel | `SettingsViewModel.kt` | `aiLastBenchmark: StateFlow<String?>` exponieren |
| Settings UI | `AiSettingsScreen.kt` | "Letzter Benchmark"-Sektion mit Tok/s, TTFT, Zeitstempel + "Neu messen"-Button |
| Benchmark-Trigger | `AiInferenceController` | `runBenchmark()` Funktion: fixen Prompt generieren, Inference, Ergebnis speichern |
| Strings | `strings.xml` | Existieren bereits (`ai_settings_benchmark*`) |

**Benchmark-Prompt**: "Antworte in 2-3 Sätzen: Was ist Messaging und warum ist Privatsphäre wichtig?"

---

## Phase 3 — Kontext-Reset-Button im Chat

| Schritt | File | Änderung |
|---|---|---|
| ViewModel Methode | `AiChatViewModel.kt` | `resetContext(conversationId)` → inserted System-Nachricht |
| UI Button | `AiChatDetailScreen.kt` | IconButton in der TopBar |
| Prompt Building | `AiPrompts.kt` | `buildConversationPrompt()` erkennt Reset-Marker |

---

## Phase 4 — Auto-Summary wenn Context voll ist

| Schritt | File | Änderung |
|---|---|---|
| Truncator Enhancement | `AiPromptTruncator.kt` | Wenn Nachrichten wegfallen: Keyword-Extraktion aus betroffenen Messages |
| Summary-Insert | `AiPrompts.kt` | Gefallene Nachrichten durch "Context: [Keywords]" ersetzen |
| Konfiguration | `AiModelManager.kt` | `getSavedAutoSummaryEnabled()` + SettingsKeys |

---

## Abhängigkeiten

```
Phase 1a (DB) → Phase 1b (Tools) → Phase 1c (Executor) 
    → Phase 2 (Benchmark) → Phase 3 (Context Reset) 
    → Phase 4 (Auto-Summary) → 1d (remembered info im Prompt)
```

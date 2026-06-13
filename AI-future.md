# AI Future — Erweiterungsplan

## ✅ Phase 1 — Neue Agent-Tools (ABGESCHLOSSEN)

### ✅ 1a. Datenbank-Erweiterung (Notes + Reminders)

| Schritt | File | Änderung |
|---|---|---|
| ✅ Room Entity `AiNote` | `AiNoteEntity.kt` (neu) | `id`, `title`, `content`, `updatedAt`, `createdAt` |
| ✅ Room Entity `AiReminder` | `AiReminderEntity.kt` (neu) | `id`, `title`, `dueTime`, `isCompleted`, `createdAt` |
| ✅ DAO `AiNoteDao` | `AiNoteDao.kt` (neu) | CRUD + search |
| ✅ DAO `AiReminderDao` | `AiReminderDao.kt` (neu) | CRUD + due query |
| ✅ AppDatabase | `AppDatabase.kt` | Entities + DAOs registrieren, Migration 7→8 |
| ✅ AlarmManager + Receiver | `ReminderAlarmReceiver.kt` (neu) | Alarm bei Fälligkeit, Notification |
| ✅ Manifest | `AndroidManifest.xml` | ReminderReceiver, SCHEDULE_EXACT_ALARM |

### ✅ 1b. Tool-Registrierung in `AiToolRegistry.kt`

| Tool | Parameter | Funktionalität |
|---|---|---|
| ✅ `web_search` | `query: string` | DuckDuckGo Instant Answer API, OkHttp, kein API-Key |
| ✅ `create_note` | `title: string`, `content: string` | Speichert in DB |
| ✅ `get_notes` | `search: string?` (optional) | Sucht/Listet Notizen aus DB |
| ✅ `create_reminder` | `title: string`, `due: string` (ISO 8601) | DB + AlarmManager + Notification |
| ✅ `get_reminders` | `status: string` (pending/completed/all) | Listet filterbare Erinnerungen |
| ✅ `complete_reminder` | `reminder_id: string` | Markiert als erledigt |
| ✅ `delete_reminder` | `reminder_id: string` | Löscht dauerhaft |
| ✅ `remember_info` | `key: string`, `value: string` | Key-Value in DataStore |
| ✅ `get_remembered_info` | `key: string` (optional) | Gibt gespeicherte Infos zurück |

### ✅ 1c. Executor-Erweiterung in `AiToolExecutor.kt`

- ✅ `executeWebSearch(query)` → OkHttp GET, JSON parsen
- ✅ `executeCreateNote(title, content)` → NoteDao einfügen
- ✅ `executeGetNotes(search)` → NoteDao abfragen
- ✅ `executeCreateReminder(title, due)` → NoteDao + AlarmManager
- ✅ `executeGetReminders(status)` → ReminderDao filtern
- ✅ `executeCompleteReminder(reminderId)` → ReminderDao markCompleted
- ✅ `executeDeleteReminder(reminderId)` → ReminderDao delete
- ✅ `executeRememberInfo(key, value)` → DataStore schreiben
- ✅ `executeGetRememberedInfo(key)` → DataStore lesen

### ✅ 1d. System-Prompt-Update

- ✅ `AiPrompts.kt`: `buildFullSystemPrompt()` um `rememberedInfo`-Parameter erweitert
- ✅ `AiAgent.kt`: Liest remembered Info aus DataStore vor Prompt-Bau

---

## ✅ Phase 2 — Benchmark / Performance-View (ABGESCHLOSSEN)

| Schritt | File | Änderung |
|---|---|---|
| ✅ SettingsKeys | `SettingsDataStore.kt` | `AI_BENCHMARK_TOKENS`, `_TOKENS_PER_SEC`, `_TTFT_MS`, `_TIMESTAMP` |
| ✅ PredictionResult speichern | `AiInferenceController.kt` | `saveBenchmarkResult()` nach jedem `onDone` |
| ✅ SettingsViewModel | `SettingsViewModel.kt` | `aiLastBenchmark: StateFlow<BenchmarkInfo?>` |
| ✅ Settings UI | `AiSettingsScreen.kt` | Benchmark-Sektion mit Tok/s, TTFT, Zeitstempel + "Neu messen"-Button |
| ✅ Benchmark-Trigger | `AiInferenceController.kt` | `runBenchmark()` mit fixed Prompt |
| ✅ Strings | `strings.xml` | Existieren bereits, Button "Neu messen" fest codiert |

**Benchmark-Prompt**: "Antworte in 2-3 Sätzen: Was ist Messaging und warum ist Privatsphäre wichtig?"

---

## Phase 3 — Kontext-Reset-Button im Chat

| Schritt | File | Änderung |
|---|---|---|
| ⬜ ViewModel Methode | `AiChatViewModel.kt` | `resetContext(conversationId)` → inserted System-Nachricht |
| ⬜ UI Button | `AiChatDetailScreen.kt` | IconButton in der TopBar |
| ⬜ Prompt Building | `AiPrompts.kt` | `buildConversationPrompt()` erkennt Reset-Marker |

---

## Phase 4 — Auto-Summary wenn Context voll ist

| Schritt | File | Änderung |
|---|---|---|
| ⬜ Truncator Enhancement | `AiPromptTruncator.kt` | Wenn Nachrichten wegfallen: Keyword-Extraktion aus betroffenen Messages |
| ⬜ Summary-Insert | `AiPrompts.kt` | Gefallene Nachrichten durch "Context: [Keywords]" ersetzen |
| ⬜ Konfiguration | `AiModelManager.kt` | `getSavedAutoSummaryEnabled()` + SettingsKeys |

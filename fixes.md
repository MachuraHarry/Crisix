# Crisix UI — Analyse & Umbau-Plan

## Architektur-Zustand

Die App hat **9.093 Zeilen Kotlin-UI-Code** in 30 Dateien. Das Kernproblem: `CrisixApp.kt` (1.278 Zeilen) ist ein **God-Composable** — es mischt Navigation, State-Management, Transport, E2EE, Persistenz und UI-Logik in einer einzigen Funktion. Vier ViewModel-Dateien (327 Zeilen) existieren als totes Code-Skelett, werden aber nicht genutzt. Der gesamte State lebt in `remember { mutableStateMapOf() }` auf Composable-Ebene, was unnötige Rekompositionen der gesamten App verursacht.

### Datei-Inventar

| Bereich | Dateien | Zeilen |
|---------|---------|--------|
| Navigation | `CrisixApp.kt`, `NavRoutes.kt` | 1.306 |
| Screens | 13 Dateien | 6.320 |
| Components | 4 Dateien | 837 |
| ViewModels | 5 Dateien (4 ungenutzt) | 448 |
| State-Klassen | 2 Dateien | 38 |
| Theme | 3 Dateien (`Color.kt`, `Theme.kt`, `Type.kt`) | 163 |
| **Gesamt** | **30 Dateien** | **9.093** |

---

## Phase 1: Robustheit & Bugfixes (hohe Priorität)

### 1.1 `incomingTransports` auf `mutableStateMapOf` umstellen

**Datei:** `ui/navigation/CrisixApp.kt:209`

Mutationen an `mutableMapOf` lösen keine Rekomposition aus — Änderungen an der Transport-Zuordnung werden nicht im UI sichtbar.

```kotlin
// Vorher (buggy):
val incomingTransports = mutableMapOf<String, TransportType>()

// Nachher:
val incomingTransports = mutableStateMapOf<String, TransportType>()
```

### 1.2 `processedIncomingIds` mit LRU-Eviction versehen

**Datei:** `ui/navigation/CrisixApp.kt:165`

`ConcurrentHashMap<String, Boolean>` wächst unbegrenzt über die App-Laufzeit — Memory-Leak. Mit einer größenbeschränkten LRU-Map (z.B. 10.000 Einträge) ersetzen.

```kotlin
// Vorher:
private val processedIncomingIds = ConcurrentHashMap<String, Boolean>()

// Nachher: LruCache oder LinkedHashMap mit removeEldestEntry
private val processedIncomingIds = object : LinkedHashMap<String, Boolean>(10000, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
        return size > 10000
    }
}
```

### 1.3 QR-Generierung auf `Dispatchers.Default` auslagern

**Datei:** `ui/screens/MyIdScreen.kt:331-334`

Die 512×512-Pixel-QR-Code-Generierung läuft synchron auf dem Main-Thread und blockiert das UI.

```kotlin
// Vorher:
LaunchedEffect(qrMode) {
    val bitmap = generateQrCode(...)
    qrBitmap = bitmap
}

// Nachher:
LaunchedEffect(qrMode) {
    val bitmap = withContext(Dispatchers.Default) {
        generateQrCode(...)
    }
    qrBitmap = bitmap
}
```

### 1.4 `processBinaryEncryptedMessage()` aus UI in `MessageProcessor` verschieben

**Datei:** `ui/navigation/CrisixApp.kt:348-442` → `message/MessageProcessor.kt`

95 Zeilen Binary-Parsing + File-I/O gehören nicht in ein Composable. Der Code verarbeitet eingehende verschlüsselte Bild-/Voice-Nachrichten und sollte im `MessageProcessor` residieren, der per Callback die UI benachrichtigt.

### 1.5 Doppelte `transportLabel()`-Funktionen vereinheitlichen

**Dateien:** `ChatDetailScreen.kt:205-214` und `ChatDetailScreen.kt:930-937`

Zwei private `transportLabel()`-Funktionen mit unterschiedlichem Output (lange vs. kurze Labels). In eine zentrale Funktion mit Parameter `short: Boolean` zusammenführen.

### 1.6 Doppelte Datums-Gruppierung vereinheitlichen

**Dateien:** `ChatListScreen.kt:91-115` (Enum `DateGroup`) vs `ChatDetailScreen.kt:591-609` (Int-basiert)

Zwei unterschiedliche Implementierungen für dasselbe Konzept. Die `DateGroup`-Enum-Lösung übernehmen und in ein `util/DateGrouper.kt` auslagern.

### 1.7 `AudioPlayer`-Singleton mit Lifecycle-Awareness versehen

**Datei:** `ui/components/AudioBubble.kt`

Der globale `AudioPlayer`-Singleton wird bei Activity-Neustart nicht freigegeben. `DisposableEffect` oder `LifecycleEventObserver` zur sauberen Freigabe nutzen.

---

## Phase 2: Code-Struktur bereinigen (mittlere Priorität)

### 2.1 `CrisixApp.kt` aufteilen

**Ziel:** Von 1.278 Zeilen auf **<400 Zeilen** reduzieren.

Aufteilung in:
- `ui/navigation/CrisixHost.kt` — Navigation + NavHost (~150 Zeilen)
- `ui/state/AppStateHolder.kt` — State-Management (Flows, mutableStateMaps) (~200 Zeilen)
- `ui/init/AppInitializer.kt` — Init-Logik: KeyStore, E2EE, Transport-Init, DB-Load (~200 Zeilen)
- `CrisixApp.kt` — Nur Koordination (~100 Zeilen)

### 2.2 Send-Callbacks in Factory-Methode extrahieren

**Datei:** `ui/navigation/CrisixApp.kt:822-918`

Die `MessageAddedCallback`-Blöcke sind dreifach identisch dupliziert (sendImage, sendVoice, sendText). In eine zentrale Factory-Funktion auslagern:

```kotlin
private fun createMessageCallbacks(
    normChatId: String,
    allMessages: SnapshotStateMap<String, List<Message>>,
    messageRepository: MessageRepository,
    includeReply: Boolean = false,
): MessageSender.MessageAddedCallback
```

### 2.3 `SendContext`-Konstruktion zentralisieren

Ebenfalls dreifach dupliziert:

```kotlin
private fun buildSendContext(
    normChatId: String,
    e2eeManager: E2eeManager,
    discoveredPeers: List<...>,
    allMessages: Map<...>,
    activeTransport: TransportType?,
): MessageSender.SendContext
```

### 2.4 Tote ViewModels bereinigen

**Dateien (327 Zeilen Dead Code):**
- `CrisixViewModel.kt` (154 Zeilen) — komplett ungenutzt
- `ChatDetailViewModel.kt` (57 Zeilen) — komplett ungenutzt
- `SettingsViewModel.kt` (59 Zeilen) — komplett ungenutzt
- `ConnectionsViewModel.kt` (57 Zeilen) — komplett ungenutzt

Entweder integrieren (siehe 2.1) oder löschen.

### 2.5 `ChatListViewModel` korrekt integrieren

**Datei:** `ui/viewmodel/ChatListViewModel.kt`

`computeChats()` wird derzeit als statische Funktion aufgerufen ohne ViewModel-Lifecycle. Als echtes ViewModel mit `StateFlow` integrieren.

### 2.6 Lange Composables zerlegen

| Composable | Zeilen | Ziel | Aufteilen in |
|------------|--------|------|-------------|
| `MessageBubble()` | 269 | <100 | `TextBubble`, `ImageBubble`, `AudioBubble`, `BubbleMetadata` |
| `ChatDetailScreen()` | 461 | <200 | `MessageList`, `MediaPickerSheet`, `DeleteConfirmDialog` |
| `ChatListScreen()` | 618 | <250 | `ChatListContent`, `PeerConnectDialog`, `NetworkStatusBar` |

---

## Phase 3: Theming & Accessibility (mittlere Priorität)

### 3.1 Hardcodierte Farben durch Theme-Farben ersetzen

**10+ Stellen** in folgenden Dateien mit `NavyDarkColorScheme`-Konstanten ersetzen:

| Datei | Zeile | Farbe | Ersetzen durch |
|-------|-------|-------|---------------|
| `ChatDetailScreen.kt` | 684 | `0xFF1B2A4A` | `MaterialTheme.colorScheme.surfaceVariant` |
| `ChatListScreen.kt` | 317 | `0xFF9E9E9E` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `ChatListScreen.kt` | 325 | `0xFFF44336` | `MaterialTheme.colorScheme.error` |
| `ChatListScreen.kt` | 702 | `0xFFE53935` | `MaterialTheme.colorScheme.error` |
| `AdaptiveInputBar.kt` | 109 | `0xFF1B2A4A` | `MaterialTheme.colorScheme.surfaceVariant` |
| `AdaptiveInputBar.kt` | 178 | `0xFFE53935` | `MaterialTheme.colorScheme.error` |
| `AdaptiveInputBar.kt` | 215 | `0xFFE53935` | `MaterialTheme.colorScheme.error` |
| `AdaptiveInputBar.kt` | 234 | `0xFF6C8FF9` | `MaterialTheme.colorScheme.primary` |
| `ConnectionsScreen.kt` | 359-363 | `0xFF4CAF50` etc. | `MaterialTheme.colorScheme` |
| `AudioBubble.kt` | 68-70 | `Color.White` | `MaterialTheme.colorScheme.onPrimary` |

### 3.2 Theme-Konstanten aus `Color.kt` nutzen

`NavyChatBubbleSelf`, `NavyChatBubbleOther` etc. sind definiert, werden aber nicht verwendet. Alle Hardcodes aus 3.1 durch diese Konstanten ersetzen.

### 3.3 `contentDescription = null` beheben

**30 von 66** Content-Descriptions sind `null`:

| Datei | Element | Fix |
|-------|---------|-----|
| `AudioBubble.kt:151` | Play/Pause-Button | `stringResource(R.string.play_pause)` |
| `ImagePreviewDialog.kt:63` | Angezeigtes Bild | `stringResource(R.string.image_preview)` |
| `ConnectionsScreen.kt` | Diverse Icons | Jeweils passende descriptions |
| `ChatDetailScreen.kt` | 5 Icons | Kontext-spezifische descriptions |
| `ChatListScreen.kt` | Dropdown-Icons | `stringResource(R.string.more_options)` |

### 3.4 Hardcodierte Strings in `stringResource` migrieren

| Datei | String | Ressource-Key |
|-------|--------|--------------|
| `MyIdScreen.kt:157` | `"E2EE Handshake QR"` | `R.string.e2ee_handshake_qr` |
| `MyIdScreen.kt:189` | `"Show Contact QR"` | `R.string.show_contact_qr` |
| `MyIdScreen.kt:190` | `"Show E2EE Handshake QR"` | `R.string.show_e2ee_handshake_qr` |
| `ChatDetailScreen.kt:415` | `"Ich"` | `R.string.chat_detail_me` |
| `AdaptiveInputBar.kt:129` | `"Du"` | `R.string.reply_to_you` |
| `SettingsScreen.kt:330` | `"A"` | Icon ersetzen |
| `SettingsScreen.kt:709` | `"📋"` | Icon ersetzen |

### 3.5 Emoji-Statusindikatoren durch Vector-Drawables ersetzen

| Emoji | Verwendung | Ersetzen durch |
|-------|-----------|---------------|
| `"✓"` | Gesendet-Status | `ic_check.xml` |
| `"✓✓"` | Zugestellt-Status | `ic_double_check.xml` (neu) |
| `"⏳"` | Sende-Status | `ic_hourglass.xml` (neu) |
| `"🔒"` | Verschlüsselungs-Icon | Vector-Drawable |
| `"↓"` | Scroll-nach-unten | `ic_arrow_down.xml` (neu) |

---

## Phase 4: Feature-Ausbau (niedrigere Priorität)

| Feature | Aufwand | Nutzen | Abhängigkeiten |
|---------|---------|--------|---------------|
| **Typing Indicators** | Klein | UX-Standard | Neuer Nachrichtentyp `"typing"` |
| **Message-Suche pro Chat** | Mittel | Usability | `MessageDao` Query + SearchBar-UI |
| **Online-Präsenz / Last seen** | Mittel | Messenger-Standard | Transport-Status-Polling |
| **Media-Galerie pro Chat** | Mittel | Fehlt komplett | Grid-Layout + Media-Filter |
| **Nachrichten-Löschtimer** | Klein | Privacy | `MessageEntity`-Feld + Scheduled-Job |
| **Swipe-to-reply auf Nachrichten** | Klein | UX | Swipe-Gesture auf Message-Bubble |
| **Link-Previews** | Mittel | Rich Content | URL-Metadata-Fetcher + Preview-Card |
| **Lesebestätigungen (Read by X)** | Mittel | Transparenz | Erweiterter ACK-Mechanismus |
| **Gruppen-Chats** | Groß | Neue Dimension | Multi-Peer-Sessions, Broadcast |
| **Voice/Video-Calls** | Sehr groß | Echtzeit-Kommunikation | WebRTC-Integration |
| **Dateianhänge (PDF, etc.)** | Mittel | Funktionalität | File-Picker + Binary-Transfer |
| **Chat-Backup/Export** | Mittel | Datensicherung | ZIP-Export + Import |
| **Block/Mute von Kontakten** | Klein | User-Control | `ContactEntity.blocked`-Feld |
| **Draft-Nachrichten** | Klein | UX | `SharedPreferences` pro Chat |
| **Hell/Dunkel-Theme-Toggle** | Klein | Accessibility | `Theme.kt` + `isSystemInDarkTheme` |
| **Sticker / GIFs** | Mittel | Spaß-Faktor | GIPHY-API + Sticker-Pack-Format |
| **Mark as unread** | Klein | Organisation | `ChatEntity.unread`-Feld |
| **@Erwähnungen** | Klein | Gruppen-Vorbereitung | Text-Parsing + Highlight |
| **Nachrichten-Reaktionen** | Mittel | Interaktion | Emoji-Picker + Reaction-Modell |
| **Mehrere Geräte sync** | Sehr groß | Plattform | Server-Infrastruktur nötig |

---

## Zusammenfassung der größten Probleme

| # | Problem | Impact | Phase |
|---|---------|--------|-------|
| 1 | `CrisixApp.kt` ist ein 1.278-Zeilen-God-Composable | Unwartbar, unnötige Rekompositionen | 2.1 |
| 2 | Dreifache Duplizierung der Send-Callbacks (Zeilen 822-918) | Bug-Risiko bei inkonsistenten Änderungen | 2.2 |
| 3 | `processBinaryEncryptedMessage()` in UI-Schicht (95 Zeilen) | Falsche Trennung von Concerns | 1.4 |
| 4 | Nicht-reaktives `mutableMapOf` für `incomingTransports` | Silent Data Bug | 1.1 |
| 5 | 327 Zeilen toter ViewModel-Code | Maintenance-Overhead | 2.4 |
| 6 | QR-Generierung auf Main-Thread | UI-Freeze | 1.3 |
| 7 | 30/66 `contentDescription = null` | Accessibility kaputt | 3.3 |
| 8 | 10+ hardcodierte Farben am Theme vorbei | Design-Inkonsistenz | 3.1 |
| 9 | `processedIncomingIds` unbegrenztes Wachstum | Memory-Leak | 1.2 |
| 10 | Keine Typing Indicators, keine Suche, keine Media-Galerie | Fehlende Messenger-Features | Phase 4 |

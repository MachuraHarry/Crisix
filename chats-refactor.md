# Chats-Liste Refactoring Plan

## Phase 1: DNS Echo entfernen

### Betroffene Dateien

- `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixApp.kt` — 21 Referenzen auf `"echo-self"`
- `app/src/main/res/values/strings.xml` — 2 Strings
- `app/src/main/res/values-en/strings.xml` — 2 Strings

### Zu entfernende Logik

1. **ChatPreview-Erzeugung** (CrisixApp.kt ~Zeile 1482–1495): Den `chatList.add(ChatPreview(id = "echo-self", ...))` Block entfernen
2. **`isEchoChat`-Branch in `onChatClick`** (Zeile 1604, 1610–1614): Entfernen, Nachrichten laden nur noch per `normChatId`
3. **Echo-Nachricht senden** (Zeile 1975–1990): Direktes `dnsTransport.send()` entfernen, `isEchoChat`-Logik raus
4. **Echo-Nachrichten empfangen** (Zeilen 1144–1147, 1224–1227, 1304–1307): `allMessages["echo-self"]`-Branches entfernen
5. **E2EE-Handshake-Skip** (Zeile 1681): `normChatId != "echo-self"` entfernen (wird überflüssig)
6. **Peer-Checks** (Zeilen 1822, 1913, 1974): `normChatId != "echo-self"` aus den Bedingungen entfernen
7. **Chat-Liste Skip** (Zeile 1462): `if (peerId == "echo-self") continue` entfernen
8. **String-Ressourcen**: `crisix_app_echo_chat_name` und `crisix_app_echo_chat_preview` in DE+EN löschen

### Ergebnis

- ~10 Sonderfälle und Bedingungen fallen weg
- Chat-Liste enthält nur noch echte Peers/Kontakte
- Kein Hardcoded-Chat mehr, der den DNS-Tunnel testet

---

## Phase 2: ChatListViewModel extrahieren

### Problem

- `CrisixApp.kt` ist 2.478 Zeilen lang — der Chat-Listen-Teil ist darin vergraben
- Keine ViewModels im gesamten Projekt — alles liegt in einem Monolithen
- Chat-Liste wird per `derivedStateOf` (Zeile 1426–1498) on-the-fly aus `discoveredPeers` + `allMessages` berechnet
- Die existierende Room-Infrastruktur (`ChatEntity`, `ChatDao`, `MessageRepository.allChats`) wird für die Listenanzeige ignoriert
- Keine Trennung von UI und State — schlecht testbar

### Plan

1. **`ChatListViewModel` neu erstellen**
   - `ChatListUiState`-Data-Class mit Feldern: `chats: List<ChatPreview>`, `isLoading: Boolean`, `isEmpty: Boolean`
   - `StateFlow<ChatListUiState>` als öffentliches API
   - Funktionen: `loadChats()`, `deleteChat(id)`, `search(query)`

2. **Room als Single Source of Truth**
   - Chats beim Entdecken/Persistieren direkt in `ChatEntity`-Tabelle schreiben
   - `MessageRepository.allChats` (`Flow<List<ChatEntity>>`) als primäre Datenquelle nutzen
   - `ChatEntity` zu `ChatPreview` mappen (transportType, lastMessage, timestamp)

3. **Integration in CrisixApp**
   - `CrisixApp.kt` wird von 2.478 auf ca. 2.200 Zeilen reduziert
   - `ChatListScreen` erhält ViewModel statt direkter Parameter
   - `derivedStateOf`-Block (Zeile 1426–1498) entfällt

### Neue Dateien

- `app/src/main/java/com/messenger/crisix/ui/viewmodel/ChatListViewModel.kt`
- `app/src/main/java/com/messenger/crisix/ui/state/ChatListUiState.kt`

---

## Phase 3: UI/UX-Verbesserungen

### 3.1 Pull-to-Refresh

- `pullToRefresh`-Modifier aus Material3 in `ChatListScreen.kt` einbauen
- Zeigt einen Lade-Indikator beim Herunterziehen
- Triggert `transportManager.selectBestTransport()` + `viewModel.refresh()`

### 3.2 Floating Action Button (FAB)

- "Neuer Chat"-Button prominent unten rechts platzieren
- Führt zur Kontaktliste oder QR-Scanner
- Entlastet das überladene Hamburger-Menü

### 3.3 Transport-spezifische Icons

- Statt einheitlichem `ic_network`-Icon pro Chat-Zeile unterschiedliche Icons anzeigen:
  - WLAN: `ic_wifi`
  - Bluetooth: `ic_bluetooth`
  - Internet/DHT: `ic_globe`
  - Relay: `ic_cloud`
  - DNS: `ic_dns`
- `TransportType`-Enum erweitern um Icon-Res-ID

### 3.4 Pinned Chats (optional)

- `ChatEntity` um `pinned: Boolean` erweitern
- `ChatDao`: Query mit `ORDER BY pinned DESC, timestampMillis DESC`
- `ChatListItem`: Pin-Icon rechts oben anzeigen
- Long-press-Menü: "Anheften / Lösen"-Option

### 3.5 Leerer-Zustand verbessern

- Statt nur Icon + Text einen CTA-Button ("Kontakt hinzufügen") einbauen
- Trennung: "Keine Chats" vs. "Keine Suchergebnisse" deutlicher machen

### 3.6 Swipe-to-Delete optimieren

- Statt Bestätigungsdialog: Undo-Snackbar nach dem Löschen
- "Chat gelöscht" mit "Rückgängig"-Button für 5 Sekunden

### 3.7 Performance

- `@Stable`-Annotation auf `ChatPreview` für Compose-Optimierung
- `LazyColumn`-Keys sind bereits korrekt gesetzt — beibehalten
- `remember` für `groupedChats` ist bereits da — beibehalten

---

## Priorisierung

| Phase | Aufwand | Risiko | Nutzen |
|-------|---------|--------|--------|
| 1. DNS Echo entfernen | Klein | Niedrig | Cleaner Code, weniger Sonderfälle |
| 2. ViewModel extrahieren | Mittel | Mittel | Testbarkeit, Architektur, Wartbarkeit |
| 3. UI/UX verbessern | Mittel–Groß | Niedrig | Bessere Nutzererfahrung |

Empfohlene Reihenfolge: Phase 1 → Phase 2 → Phase 3

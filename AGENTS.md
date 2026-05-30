# Crisix — Summary & Plan

## Goal
Robuste Chat-Kommunikation mit bidirektionalen Streams, Transport-Hierarchie (WLAN→Internet→BLE→DNS-Tunnel), und Empfangsbestätigung (ACK→UI). Gegenseitige Capability-Erkennung verhindert verlorene Nachrichten (z.B. Relay-Versand an Peer ohne Internet).

## Constraints & Preferences
- Ed25519-Fingerprint als Single Source of Truth für Geräteidentität
- Keine automatische Peer-Discovery (UDP-Broadcast, Subnet-Scan, mDNS entfernt)
- Nachrichten müssen sauber zugestellt werden – keine Protokoll-Leaks im Chat, keine verlorenen Replies
- Fallback-Kette: WLAN (WifiTransport) → Internet (DHT/InternetTransport) → BLE (BleTransport) → DNS-Tunnel (DnsTunnelTransport)
- Build muss erfolgreich sein (Kotlin + Android)
- Kein Netz auf einem Gerät (z.B. Phone 1 nur BLE) darf nicht zu "falschen Haken" führen – Relay/Internet dürfen nur probiert werden, wenn der Empfänger sie auch hat
- Samsung SM-G920F hat oft temporäre BLE-Advertising-Fehler (errorCode=1) + liefert oft keine serviceUuids im ScanRecord
- Render Free Web Service erlaubt keine raw TCP-Ports → Relay auf WebSocket (wss://)

## Progress
### Done (Phase 0 — Identity + Grundlagen)
- Ed25519-Fingerprint als einheitliche `deviceId` für alle Transporte
- `readFully`-Busy-Loop-Fix: Blocking `InputStream.read()` statt Busy-Loop
- Automatische Suche entfernt (UDP-Broadcast, Subnet-Scan, mDNS, Scan-Button)
- PeerId-Normalisierung: `peerId.split("@").first()`
- UUID-Leak-Fix: Nur `CHAT_MESSAGE` wird an UI weitergeleitet
- Chat-Liste dedupliziert + Live-Updates via `derivedStateOf`
- Dummy-Chats entfernt
- Reply-Fix: `allMessages.containsKey()` für unbekannte Peers
- Build: `./gradlew assembleDebug` → SUCCESSFUL

### Done (Phase A — Bidirektionale Streams + DNS-Fallback)
- **A1**: `Libp2pManager.getActiveStream(peerId)` — findet bestehenden Stream per peerId
- **A1b**: `connectToPeer()` feuert `onIncomingConnection` → Reader-Coroutine für ausgehende Verbindungen
- **A2**: `InternetTransport.send()` prüft `getActiveStream()` vor neuem `connectToPeer()`
- **A3**: `InternetTransport.sendAck()` nutzt `getActiveStream()` statt neuer Verbindung
- **A4**: `TransportManager.sendMessage()` probiert DNS-Tunnel als letzten Fallback

### Done (Phase B — Delivery-Status + Retry)
- **B1**: `Message`-Data-Class erweitert mit `status: MessageStatus` und `transport: TransportType?`
- **B1b**: `MessageBubble` zeigt Status-Icons (⏳/✓/✓✓/✗) + Transport-Label ("via WIFI")
- **B2**: `InternetTransport.onMessageSent` + `onDeliveryAck` Callbacks für ACK-Tracking
- **B3**: `TransportManager.deliveryUpdates: SharedFlow<DeliveryUpdate>` für UI-Updates
- **B3b**: `sendMessage()` akzeptiert `uiMessageId`, emitted SENT/FAILED inkl. Transport
- **B3c**: Retry-Queue + Background-Job (30s) für fehlgeschlagene Nachrichten
- **B4**: `CrisixApp.kt` subscribed auf `deliveryUpdates`, updatet `allMessages`
- **B4b**: Incoming-Listener setzt SENT→DELIVERED bei Antwort des Peers
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL

### Done (Phase D — BLE Transport + Relay WebSocket)
- **D1**: `RelayTransport` umgestellt von raw TCP-Socket auf OkHttp WebSocket (`wss://crisix-dns.onrender.com/ws`)
- **D2**: `dns_server.py`: TCP-8054 entfernt, WebSocket-Endpoint `/ws` auf Port 8080
- **D3**: `BleTransport.kt`: Vollständiger BLE-Transport (Advertising + Scanning + GATT Server/Client + Base64)
- **D4**: `TransportManager.sendMessage()` Priority-Loop statt hartcodierter Kette
- **D5**: `BleTransport`: Scan-Fix (Device-Name-Filter entfernt, `scanRecord.serviceUuids` statt `ScanFilter`)
- **D6**: BLE-Permissions (SCAN, CONNECT, ADVERTISE) in `AndroidManifest.xml` + Runtime-Request in `CrisixApp.kt`
- **D7**: GATT-Server → Client-Gegenrichtung: `gattServerCallback.onConnectionStateChange` ruft `connectToDevice()` auf
- **D7b**: `pendingConnections`-Set verhindert doppelte Client-Verbindungen
- **D7c**: `onCharacteristicReadRequest` offset-Fix: `copyOfRange(offset, size)`
- **D7d**: Long Write / Prepared Write Handling: `onExecuteWrite` + `pendingWrites`-Buffer
- **D8**: Capability-Exchange über BLE CAP_CHAR (c510c513): `PeerCapabilities` Data-Class + Mutual Priority
- **D8b**: `PENDING`-Status für Nachrichten bei fehlenden Capabilities
- **D8c**: UI `StatusIcon` zeigt ⏳ für `PENDING` + exhaustive `when`-Branch

### Done (Phase 0 – Quick Wins + Bugfixes)
- **retryPendingMessages()**: Queue nicht vor Iteration leeren → kein Datenverlust bei Crash
- **probeTransport()**: Route-Hint nur Sortierung, Probe immer aktiv → kein falscher "SENT"-Haken bei totem Transport
- **QR-Scanner reset fix**: `scanningActive` in `DisposableEffect` lokalisiert → kein hängender Scan nach zweitem Aufruf
- **getDateGroup()**: String-basiertes Matching durch echte `Calendar`-Vergleiche ersetzt; `Message.timestampMillis` + `ChatPreview.timestampMillis`
- **Capability-Refresh bei Internet-Status-Wechsel**: `ConnectivityManager.NetworkCallback` → `broadcastCapabilities()` an BLE-Peers + `retryPendingMessages()`
- **InAppLogger reaktiv**: `StateFlow<Int> logCount` für reaktives LogViewer-Scrolling
- **BLE thread-safe**: `messageListeners` auf `CopyOnWriteArrayList`
- **Polling-Intervall**: 2s → 5s
- **println() → Log**: `AddContactScreen`-Stubs
- **Dead Code entfernt**: ~300 Zeilen (PeerDiscovery.mDNS/NATPunch, NatTraversal.UPnP/HolePunch, CryptoHelper.sign/verify, InternetTransport.processIncomingMessage, Libp2pManager.getDiscoveredPeers, MainlineDhtNode.findPeer)
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL

### Done (Phase 1 – Transport-Fixes)
- **BLE onCharacteristicChanged**: `setCharacteristicNotification(char, true)` vor CCCD-Write + `onCharacteristicChanged` verarbeitet eingehende Notifications via `processIncomingMessage`
- **WifiTransport Send-Fix**: Auto-Reconnect führt jetzt vollständigen Handshake durch (vorher: Chat-Nachricht direkt gesendet → Server parsed sie als JSON-Handshake → Abbruch). `peerAddresses`-Map für IP-Persistenz bei Reconnect
- **Relay Dual-Reconnect**: `reconnectMutex` verhindert konkurrierende `connect()`-Aufrufe; `scheduleReconnect()` entfernt (war Konflikt mit `startReconnectLoop`); einheitlicher Reconnect-Loop mit exponentiellem Backoff (1s→30s); `reconnecting`-Flag + `synchronized`-Guard
- **DummyTransport**: Test-Transport mit `injectMessage()`, `sentMessages`-Log, `failSends`-Modus; nutzt `TransportType.LORA`
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL

### Done (Phase 2 – Message-Persistenz)
- **Room-Dependency**: 2.7.1 + KSP 2.2.10-2.0.2 in `build.gradle.kts`
- **MessageEntity/ChatEntity**: Room-Entities mit allen Feldern (id, chatId, text, isFromMe, timestamp, timestampMillis, status, transport)
- **MessageDao**: Fluss-Queries (`getMessages(chatId): Flow<List>`), Status-Updates (`updateStatus`, `updateAllSentToDelivered`)
- **ChatDao**: `getAll(): Flow<List>` für Chat-Liste, `updateLastMessage`
- **AppDatabase**: Singleton mit `fallbackToDestructiveMigration()` (Version 1)
- **MessageRepository**: Kapselt DAO-Zugriff, `addMessage()`, `updateMessageStatus()`, `loadAllMessages()`
- **CrisixApp.kt**: `messageRepository` remember'd; `LaunchedEffect` lädt alle Nachrichten aus DB; jede neue/aktualisierte Nachricht wird persistiert; `toMessage()`-Extension für MessageEntity→Message
- **KSP-Kompatibilität**: `android.disallowKotlinSourceSets=false` in `gradle.properties` (AGP 9.x + KSP)
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL

## Critical Context
- **Nachrichten-Status-Fluss**: SENDING → SENT (via send) → DELIVERED (via incoming reply/ACK)
- **BLE-Grundproblem gelöst**: gattServerCallback → connectToDevice() → peerConnections befüllt → send() findet Peer
- **BLE-Notifications**: `setCharacteristicNotification()` vor CCCD-Write (Android erforderlich); `onCharacteristicChanged` verarbeitet eingehende Notifications
- **Samsung errorCode=1 (Advertising)**: temporärer Fehler, 5s-Retry; serviceUuids fehlen → unfiltered Scan-Fallback nach 10s
- **Retry-Queue**: 10s-Intervall, max 10 Versuche, jetzt crash-safe (kein Clear vor Iteration)
- **Capability-Refresh**: ConnectivityManager-Callback → BLE-Broadcast an alle Peers
- **WifiTransport**: Auto-Reconnect mit Handshake + IP-Persistenz in `peerAddresses`
- **RelayTransport**: Single-Reconnect-Loop mit Mutex-Guard, kein Konflikt mehr zwischen zwei Reconnect-Mechanismen
- **DummyTransport**: Verfügbar, aber nicht im Priority-Loop von TransportManager (manuell hinzufügbar für Tests)

## Next Steps
1. **Phase 2: Message-Persistenz** (Room-DB)
2. **Phase 3: UI 2.0** — Anhänge, Bild-Vorschau, Capability-Badge, Zeichenzähler, Copy-to-Clipboard
3. **Phase 4: i18n + Theme** — strings.xml, Light Theme, Dynamic Colors
4. **Phase 5: Security** — AndroidKeyStore, E2E-Verschlüsselung
5. **Phase 6+**: Neue Transporte, Media Queue, A/V Calls

## Key Decisions
- `MessageStatus` + `DeliveryUpdate` in `TransportManager.kt` (nicht in UI)
- DELIVERED wird durch eingehende Nachrichten des Peers inferiert (alle SENT → DELIVERED)
- Retry verwendet `sendMessage()` (kein eigener Sendepfad)
- Keine Änderung am `Transport`-Interface — alle Erweiterungen via Callback + Flow
- Unicode-Text (⏳/✓/✓✓/✗) statt Material-Icons für Status
- Route-Hint + Probe: Hint bestimmt nur Reihenfolge, Probe ist obligatorisch
- QR-Guard: Lokal in `DisposableEffect` statt in `remember`

## Relevant Files
- `app/.../transport/BleTransport.kt` — BLE-Transport + CAP_CHAR + broadcastCapabilities()
- `app/.../transport/TransportManager.kt` — initNetworkMonitor(), retryPendingMessages(), probeTransport()
- `app/.../ui/navigation/CrisixApp.kt` — initNetworkMonitor(), Message/ChatPreview mit timestampMillis, MessageRepository-Integration
- `app/.../ui/screens/ChatDetailScreen.kt` — Message-Data-Class, MessageBubble mit Status
- `app/.../ui/screens/ChatListScreen.kt` — getDateGroup() calendar-basiert
- `app/.../ui/screens/QrCodeScannerScreen.kt` — scanningActive-Fix
- `app/.../ui/screens/InAppLogger.kt` — StateFlow reaktiv
- `app/.../ui/screens/LogViewerScreen.kt` — logCount-basiertes Scrolling
- `app/.../ui/screens/AddContactScreen.kt` — println() → Log
- `app/.../transport/internet/InternetTransport.kt` — P2P-Transport
- `app/.../transport/RelayTransport.kt` — WebSocket Reconnect
- `app/.../data/AppDatabase.kt` — Room-Datenbank (Singleton)
- `app/.../data/MessageRepository.kt` — Nachrichten-Persistenz
- `app/.../data/MessageEntity.kt` — Room-Entity
- `app/.../data/MessageDao.kt` — Room-DAO mit Flow-Queries
- `Crisix-Plan.md` — Vision & Architektur
- `Crisix-Bugs.md` — Audit-Funde

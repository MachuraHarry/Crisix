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
- **D1**: `RelayTransport` umgestellt von raw TCP-Socket auf OkHttp WebSocket (`wss://crisix-dns.onrender.com/ws`) — Render Free Web Service blockt raw TCP
- **D2**: `dns_server.py`: TCP-8054 entfernt, WebSocket-Endpoint `/ws` auf Port 8080
- **D3**: `BleTransport.kt`: Vollständiger BLE-Transport (Advertising + Scanning + GATT Server/Client + Base64)
- **D4**: `TransportManager.sendMessage()` Priority-Loop statt hartcodierter Kette (WIFI → DHT → RELAY → BLE → SMS → DNS → LORA)
- **D5**: `BleTransport`: Scan-Fix (Device-Name-Filter entfernt, `scanRecord.serviceUuids` statt `ScanFilter`)
- **D6**: BLE-Permissions (SCAN, CONNECT, ADVERTISE) in `AndroidManifest.xml` + Runtime-Request in `CrisixApp.kt`
- **D7 (THIS SESSION)**: GATT-Server → Client-Gegenrichtung: `gattServerCallback.onConnectionStateChange(STATE_CONNECTED)` ruft `connectToDevice(device)` auf → `peerConnections` wird befüllt → `send()` findet Peer
- **D7b**: `pendingConnections`-Set verhindert doppelte Client-Verbindungen + Cleanup in Disconnect-/Error-Handlern
- **D7c**: `onCharacteristicReadRequest` offset-Fix: `copyOfRange(offset, size)` statt ganzer ByteArray → keine korrupten Peer-IDs mehr
- **D7d**: Long Write / Prepared Write Handling: `onExecuteWrite` + `pendingWrites`-Buffer für Chunked-Writes bei kleinem MTU (23 Bytes)
- **D8**: Capability-Exchange über BLE CAP_CHAR (c510c513): `PeerCapabilities` Data-Class + Mutual Priority in `sendMessage()` – nur Transporte probieren, die der Empfänger hat
- **D8b**: `PENDING`-Status für Nachrichten, die wegen fehlender Capabilities nicht zugestellt werden können
- **D8c**: UI `StatusIcon` zeigt ⏳ für `PENDING` + exhaustive `when`-Branch
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL (alle Phasen)

## Critical Context
- **Nachrichten-Status-Fluss**: SENDING → SENT (via send) → DELIVERED (via incoming reply/ACK)
- **Transport-Indikator**: Wird pro Message in `Message.transport` gespeichert, via `deliveryUpdates` von TransportManager an UI
- **Retry**: Fehlgeschlagene Sends landen in `retryQueue`, alle 30s automatischer Wiederholungsversuch
- **Echo-Chat**: Debugging-Tool, sendet über DNS-Tunnel, kein Status-Tracking
- **QR-Code-Verbindung**: Geht über `InternetTransport.connectToPeer()` → erzeugt Reader-Coroutine + `activeStreams`-Eintrag. Läuft stabil.

## Key Decisions
- `MessageStatus` + `DeliveryUpdate` in `TransportManager.kt` (nicht in UI)
- DELIVERED wird durch eingehende Nachrichten des Peers inferiert (alle SENT → DELIVERED)
- Retry verwendet `sendMessage()` (kein eigener Sendepfad)
- Keine Änderung am `Transport`-Interface — alle Erweiterungen via Callback + Flow
- Unicode-Text (⏳/✓/✓✓/✗) statt Material-Icons für Status (keine Zusatz-Dependency)

## Relevant Files
- `app/src/main/java/com/messenger/crisix/ui/screens/ChatDetailScreen.kt` — `Message`-Data-Class, `MessageBubble` mit Status + Transport
- `app/src/main/java/com/messenger/crisix/transport/TransportManager.kt` — `MessageStatus`, `DeliveryUpdate`, Delivery-Tracking, Retry, `sendMessage()` mit `uiMessageId`
- `app/src/main/java/com/messenger/crisix/transport/internet/InternetTransport.kt` — `onMessageSent`/`onDeliveryAck` Callbacks
- `app/src/main/java/com/messenger/crisix/transport/internet/Libp2pManager.kt` — `getActiveStream()`, Reader-Callback in `connectToPeer()`
- `app/src/main/java/com/messenger/crisix/ui/navigation/CrisixApp.kt` — Delivery-Subscription, `onSendMessage` mit Status + `messageId` im JSON

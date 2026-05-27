# Crisix — Summary & Plan

## Goal
Robuste Chat-Kommunikation mit bidirektionalen Streams, Transport-Hierarchie (WLAN→Internet→DNS-Tunnel), und Empfangsbestätigung (ACK→UI).

## Constraints & Preferences
- Ed25519-Fingerprint als Single Source of Truth für Geräteidentität
- Keine automatische Peer-Discovery (UDP-Broadcast, Subnet-Scan, mDNS entfernt)
- Nachrichten müssen sauber zugestellt werden – keine Protokoll-Leaks im Chat, keine verlorenen Replies
- Fallback-Kette: WLAN (WifiTransport) → Internet (DHT/InternetTransport) → DNS-Tunnel (DnsTunnelTransport)
- Build muss erfolgreich sein (Kotlin + Android)

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

### Done (Phase C — DNS end-to-end ACK + Fingerprint-Shortening)
- **C1**: `DnsTunnelTransport.onDeliveryAck`-Callback hinzugefügt
- **C2**: `TransportManager.sendMessage()` DNS-Fallback hängt `\x00$uiMessageId` an die Nutzdaten an
- **C3**: `DnsTunnelTransport.pollMessages()` erkennt `__ACK__:`-Prefix → feuert `onDeliveryAck` (statt Listener-Dispatch)
- **C4**: `DnsTunnelTransport.pollMessages()` sendet bei regulären Nachrichten automatisch `__ACK__:$uiMessageId` via `send()` an den Sender zurück
- **C5**: `TransportManager.registerTransport()`-Wiring: `DnsTunnelTransport.onDeliveryAck` → `_deliveryUpdates.tryEmit(DELIVERED)`
- **C6**: `pollMessages()` strippt `\x00$uiMessageId`-Suffix bevor die Nachricht an Listener weitergegeben wird (unsichtbar für UI)
- **C7 (REVERTED)**: Fingerprint-Shortening auf 16 Hex-Zeichen → zurückgenommen. Der Empfänger braucht den vollen 64-Char-Fingerprint zur Reply-Routing (DNS-Domain `send.$b32.$peerId.$serverDomain`). Ohne vollen Fingerprint geht die Antwort des Empfängers an die 16-Char-Kurz-ID → Alice pollt mit 64-Char-ID → bekommt nie.
- **Build**: `./gradlew assembleDebug` → SUCCESSFUL (alle Phasen)

### Blocked / Offen
- (none)

## Offene Fragen / Nächste Schritte
- (none)

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

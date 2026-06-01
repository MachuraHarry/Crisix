# E2EE Fallback Robustness — Masterplan

## Übersicht

Ziel: E2EE muss zu 100% erzwungen werden, egal wie schlecht das Netzwerk ist — auch über SMS, LoRa oder DNS-Tunnel.

Die App (`CrisixApp.kt`, `TransportManager.kt`, `E2eeManager.kt`, `DoubleRatchet.kt`) hat bereits eine gute Grundlage. Dieser Plan bewertet die vorgeschlagenen Maßnahmen gegen den tatsächlichen Code und identifiziert konkrete Implementierungsschritte.

---

## Phase 1: Den Klartext-Fallback eliminieren

### 1.1 Strict E2EE Mode

**Status: ✅ Bereits umgesetzt**

`CrisixApp.kt:2048` enthält bereits: *"KEINE unverschlüsselte Fallback-Nachricht!"*

Bei `!hasSession` wird immer `e2eeManager.queueMessageForHandshake()` aufgerufen. Nachrichten werden in der Queue gehalten, bis der Handshake abgeschlossen ist. Der `SessionStateMachine`-Übergang `NONE → HANDSHAKING → ACTIVE` triggert `flushQueue()`, das die wartenden Nachrichten verschlüsselt und sendet.

**Keine Arbeit nötig.**

### 1.2 Offline-Handshake via QR-Code (Pre-Shared E2EE)

**Status: 🔧 Umsetzbar**

Aktueller QR-Code-Format: `crisix://contact?key=<peerId>&name=<name>&ip=<ip>&port=<port>`
(`QrCodeScannerScreen.kt`, Parsing in `CrisixApp.kt:2376-2422`)

**Zu tun:**
- PreKeyBundle (`E2eeManager.createPreKeyBundle()`) serialisieren und in den QR-Code packen
- Das Bundle enthält: Ed25519 Identity Key, X25519 SignedPreKey + Signatur, OneTimePreKeys
- Geschätzte Bundle-Größe: ~500–1000 Bytes → passt in QR-Code (max ~2953 Bytes binary)
- Beim Scannen: `processHandshakeAsResponder()` direkt mit dem Bundle ausführen
- Danach ist sofortige One-Sided Encryption möglich → kein X3DH-Roundtrip über langsame Transporte nötig

**Betroffene Dateien:**
- `QrCodeScannerScreen.kt` — QR-Code-Generierung erweitern
- `CrisixApp.kt` — QR-Parsing um Bundle-Extraktion ergänzen
- `E2eeManager.kt` — `createPreKeyBundle()` mit Serialisierung/Deserialisierung

**TODO:**
- [ ] PreKeyBundle-Serialisierung (Base64/Protobuf) implementieren
- [ ] QR-Format auf `crisix://handshake?bundle=<bundle>&name=<name>` erweitern
- [ ] Beim Scan automatisch `processHandshakeAsResponder()` ausführen
- [ ] Fallback: Falls Bundle zu groß für QR, nur PeerID + IP tauschen und normalen X3DH starten

---

## Phase 2: Payload-Optimierung für SMS, LoRa und DNS-Tunnel

### 2.1 Binäres E2EE-Format (Protobuf statt JSON)

**Status: 🔧 Umsetzbar**

Aktuell nutzt `DoubleRatchet.EncryptedMessage.toJson()` JSON zur Serialisierung. Das vorhandene Protobuf (`crisix_messages.proto`) definiert nur generische Transport-Typen (CHAT_MESSAGE, PING, PONG etc.), **nicht** das E2EE-Wire-Format.

**Zu tun:**
- Neues Proto-Message `EncryptedPayload` definieren mit Feldern:
  - `dh_public_key` (bytes, 32)
  - `chain_index` (uint32)
  - `message_index` (uint32)
  - `nonce` (bytes, 12)
  - `ciphertext` (bytes)
  - `session_version` (uint32)
- `EncryptedMessage.toJson()` → `EncryptedMessage.toProto()` umstellen
- `EncryptedMessage.fromJson()` → `EncryptedMessage.fromProto()` umstellen
- Erwartete Einsparung: ~30–40% Payload-Größe
- Proto ist bereits ins Projekt eingebunden (Internet-Transport nutzt es)

**Betroffene Dateien:**
- `DoubleRatchet.kt` — `EncryptedMessage` um Protobuf-Serialisierung erweitern
- `crisix_messages.proto` — Neue `EncryptedPayload` message hinzufügen

**TODO:**
- [ ] `EncryptedPayload` proto message definieren
- [ ] `EncryptedMessage.toProto()` / `fromProto()` implementieren
- [ ] `E2eeManager.encryptMessage()` auf Proto-Ausgabe umstellen
- [ ] `E2eeManager.decryptMessage()` auf Proto-Eingabe umstellen
- [ ] Abwärtskompatibilität: JSON-Format für bestehende Sessions beibehalten (Version-Flag)

### 2.2 Message Fragmentation (Chunking)

**Status: 🔧 Umsetzbar, aber komplexer als skizziert**

Die Skizze im TransportManager ist zu einfach. Folgende Aspekte fehlen:

**Herausforderungen:**
- Chunk-Metadaten benötigt: `<chunkIndex>/<totalChunks>/<messageId>` pro Fragment
- Reassembly-Buffer mit Timeout pro Peer (alte Chunks verwerfen)
- Umgang mit Out-of-Order Chunks (SMS)
- Umgang mit verlorenen Chunks (keine Reassembly möglich → verwerfen)
- Mehrere parallele Nachrichten vom selben Peer (Interleaving der Chunks)
- `TransportCapabilities` hat aktuell kein `maxPayloadSize`-Feld (nur `maxTextLength`)

**Zu tun:**
- `TransportCapabilities` um `maxPayloadSize: Int` erweitern (Standard: `Int.MAX_VALUE`)
- Transport-spezifische Limits setzen:
  - SMS: 140 Bytes (7-bit encoding) oder 160 Bytes (GSM-7)
  - LoRa: 250 Bytes (typisch, konfigurierbar)
  - DNS-Tunnel: ~512 Bytes (UDP DNS limit)
  - WIFI/INTERNET/BLUETOOTH: `Int.MAX_VALUE`
- `Fragmenter`-Klasse implementieren:
  ```kotlin
  data class Chunk(val messageId: String, val chunkIndex: Int, val totalChunks: Int, val data: ByteArray)
  ```
- `Defragmenter`-Klasse implementieren:
  - Buffer: `ConcurrentHashMap<String, MutableMap<Int, ByteArray>>` (pro messageId)
  - Timeout: z.B. 5 Minuten, dann Chunks verwerfen
  - Bei vollständiger Reassembly: komplettes ByteArray zurückgeben und an `E2eeManager.decryptMessage()` weiterleiten
- Integration in `TransportManager.sendMessage()`:
  ```kotlin
  if (payload.size > transport.capabilities.maxPayloadSize) {
      val chunks = Fragmenter.split(payload, transport.capabilities.maxPayloadSize, uiMessageId)
      for (chunk in chunks) {
          transport.send(peerId, chunk.toBytes())
      }
      return Result.success()
  }
  ```
- Integration in `TransportManager.registerMessageListener()`:
  - Eingehende Daten prüfen, ob es ein Chunk ist
  - Wenn Chunk: an `Defragmenter.addChunk()` übergeben
  - Wenn vollständig: reassemblierte Daten an `onMessage`-Callback weiterleiten

**Betroffene Dateien:**
- `Transport.kt` — `TransportCapabilities.maxPayloadSize`
- `TransportManager.kt` — Chunking/Dechunking-Logik
- Neue Datei: `Fragmenter.kt` — Chunk-Splitting
- Neue Datei: `Defragmenter.kt` — Chunk-Reassembly mit Timeout

**TODO:**
- [ ] `TransportCapabilities.maxPayloadSize` hinzufügen
- [ ] `Fragmenter` implementieren (Split-Logik)
- [ ] `Defragmenter` implementieren (Reassembly + Timeout + Cleanup)
- [ ] Chunk-Header-Format definieren (magic bytes zur Erkennung)
- [ ] In `sendMessage()` integrieren
- [ ] In `registerMessageListener()` integrieren
- [ ] Unit-Tests für Split/Reassembly, Timeout, Lost Chunks, Out-of-Order

---

## Phase 3: Transport-Verhalten anpassen

### 3.1 Ping/Pong für langsame Transporte deaktivieren

**Status: 🔧 Umsetzbar**

Aktuell führt `TransportManager.probeTransport()` (Zeile 296–322) ein Ping/Pong mit `PING_TIMEOUT_MS = 2000L` durch, bevor die echte Nachricht gesendet wird. Bei SMS, LoRa und DNS-Tunnel ist das teuer und langsam.

**Zu tun:**
- `TransportCapabilities` um `val requiresProbing: Boolean = true` erweitern
- Für SMS, LoRa, DNS_TUNNEL auf `false` setzen
- In `TransportManager.sendMessage()` (Zeile 601) abfragen:
  ```kotlin
  if (!isHandshakeOrAck && transport.capabilities.requiresProbing) {
      val probeOk = probeTransport(normalizedPeerId, transport)
      if (!probeOk) {
          circuitBreakers[transport.type]?.recordFailure()
          continue
      }
  }
  ```
- SMS-Transport-Implementierung prüfen: `SmsTransport` existiert als `TransportType.SMS`, aber der konkrete Transport muss `requiresProbing = false` setzen.

**Betroffene Dateien:**
- `Transport.kt` — `TransportCapabilities.requiresProbing`
- `TransportManager.kt` — Probing-Check erweitern
- Transport-Implementierungen — `requiresProbing = false` setzen

**TODO:**
- [ ] `TransportCapabilities.requiresProbing` hinzufügen
- [ ] Probing-Gate in `sendMessage()` erweitern
- [ ] SMS, LoRa, DNS_TUNNEL: `requiresProbing = false`
- [ ] Dok: Probing nur noch für INTERNET, WIFI_DIRECT, BLUETOOTH_MESH, RELAY

### 3.2 Dynamischer Circuit Breaker Timeout

**Status: 🔧 Umsetzbar**

Aktuell: `CB_TIMEOUT_MS = 30_000L` (Zeile 97) pauschal für alle Transporte.

**Zu tun:**
- `CircuitBreaker`-Klasse um per-Transport `timeoutMs` erweitern
- Transport-spezifische Timeouts:
  - WIFI_DIRECT / INTERNET: 10 Sekunden
  - RELAY / BLUETOOTH_MESH: 30 Sekunden
  - DNS_TUNNEL / LORA: 120 Sekunden (2 Minuten)
  - SMS: Kein Circuit Breaker (SMS schlägt lokal fast nie fehl)
- `TransportCapabilities` um `val circuitBreakerTimeoutMs: Long` erweitern
- Oder: Typ-Map in `TransportManager`: `Map<TransportType, Long>`

**Betroffene Dateien:**
- `TransportManager.kt` — `CircuitBreaker` anpassen, Timeout-Map
- `Transport.kt` — Optional: `TransportCapabilities.circuitBreakerTimeoutMs`

**TODO:**
- [ ] `CircuitBreaker` um konfigurierbares `timeoutMs` erweitern
- [ ] Timeout-Map pro `TransportType` erstellen
- [ ] SMS vom Circuit Breaker ausnehmen
- [ ] Test: Timeout-Verhalten pro Transport

---

## Phase 4: Krypto-Ratchet Robustness bei Transportwechseln

### 4.1 MAX_SKIP im DoubleRatchet

**Status: ⚠️ Kritisch — Fehlt im aktuellen Code**

`DoubleRatchet.kt` hat **kein** `MAX_SKIP`-Limit. Der `OutOfOrderMessageHandler` puffert Nachrichten ohne Obergrenze. Ein Angreifer könnte tausende Nachrichten in die Zukunft senden und den Speicher sprengen.

Das Signal-Protokoll empfiehlt einen `MAX_SKIP`-Wert. Typische Werte: 100–2000, je nach Anwendung.

**Zu tun:**
- `MAX_SKIP`-Konstante in `DoubleRatchet.kt` definieren (Vorschlag: 200 für diese Anwendung)
- Im `OutOfOrderMessageHandler` prüfen: wenn `messageIndex - currentChainIndex > MAX_SKIP`, Nachricht verwerfen
- Übersprungene Keys, die außerhalb des Fensters liegen, löschen (Memory-Leak verhindern)
- Bei `MAX_SKIP`-Verletzung: Session als `STALE` oder `COMPROMISED` markieren (oder nur Log-Warnung)

**Betroffene Dateien:**
- `DoubleRatchet.kt` — `MAX_SKIP`, Limit-Logik im `OutOfOrderMessageHandler`
- `E2eeManager.kt` — ggf. Session-State-Update bei `MAX_SKIP`-Verletzung

**TODO:**
- [ ] `MAX_SKIP = 200` in `DoubleRatchet.kt` definieren
- [ ] `OutOfOrderMessageHandler` um Skip-Limit erweitern
- [ ] Alte zwischengespeicherte Message-Keys bei Überschreitung löschen
- [ ] `SessionStateMachine`-Übergang `STALE` bei Skip-Verletzung
- [ ] Unit-Test: Nachricht innerhalb `MAX_SKIP` → ok, außerhalb → verworfen

### 4.2 Encrypt-Once-Cache Persistenz

**Status: ✅ Bereits gelöst**

`PendingMessageEntity.data` speichert bereits den **verschlüsselten** Blob. Der Flow:

1. `CrisixApp.kt` verschlüsselt via `e2eeManager.encryptOnce()` → `encryptedPayload`
2. `TransportManager.sendMessage(data = encryptedPayload)` → speichert `RetryEntry(..., data=encryptedPayload)`
3. Bei Retry: `entry.data` wird erneut gesendet (selber Chiffretext, kein erneuter Ratchet-Schritt)
4. Bei App-Neustart: `MessageRepository.loadPendingMessages()` lädt `PendingMessageEntity` aus Room-DB
5. Die `data` in der DB ist der bereits verschlüsselte Blob → kein erneutes `ratchetEncrypt()`

**Der `encryptOnceCache` (TTL 60s) dient nur dem Same-Session-Dedup**, wenn `TransportManager` dieselbe Nachricht innerhalb von 60s über mehrere Transporte sendet.

**Keine Arbeit nötig.**

---

## Zusammenfassung der TODOs (priorisiert)

### Kritisch
1. **MAX_SKIP implementieren (Phase 4.1)** — Sicherheitslücke, kein Skip-Limit im aktuellen Code
2. **Chunking/Defragmenting (Phase 2.2)** — Notwendig für SMS/LoRa-Betrieb, komplex in der Reassembly

### Hoch
3. **QR-Key-Exchange (Phase 1.2)** — Löst 90% der Handshake-Probleme über langsame Netzwerke
4. **Protobuf für E2EE (Phase 2.1)** — Reduziert Payload-Größe um ~30%
5. **Dynamischer Circuit Breaker (Phase 3.2)** — Verhindert Fehlalarme bei langsamen Transporten

### Mittel
6. **Probing deaktivieren (Phase 3.1)** — Spart Zeit/Geld bei SMS, LoRa, DNS

### Bereits erledigt (keine Arbeit nötig)
- Strict E2EE Mode (Phase 1.1) — Kein Klartext-Fallback
- Encrypt-Once Persistenz (Phase 4.2) — Persistiert über App-Neustarts

---

## Geschätzte Dateien und Änderungen

| Datei | Phase | Art der Änderung |
|---|---|---|
| `DoubleRatchet.kt` | 4.1, 2.1 | MAX_SKIP, Protobuf-Serialisierung |
| `Transport.kt` | 2.2, 3.1, 3.2 | Neue Felder in TransportCapabilities |
| `TransportManager.kt` | 2.2, 3.1, 3.2 | Chunking, Probing-Gate, dynamischer CB |
| `Fragmenter.kt` (neu) | 2.2 | Split-Logik |
| `Defragmenter.kt` (neu) | 2.2 | Reassembly + Timeout |
| `crisix_messages.proto` | 2.1 | EncryptedPayload message |
| `E2eeManager.kt` | 1.2, 2.1 | QR-Bundle, Proto-Encrypt/Decrypt |
| `QrCodeScannerScreen.kt` | 1.2 | QR-Bundle-Generierung/Scan |
| `CrisixApp.kt` | 1.2 | QR-Parsing um Bundle erweitern |

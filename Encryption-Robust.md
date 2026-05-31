# Crisix — Encryption Robustness Plan

## Status Quo

### Vorhandene E2EE-Architektur
Das Projekt besitzt bereits eine **vollständige, Custom-Built E2EE-Schicht** (~3500 Zeilen), die das Signal-Protokoll nachbaut:

| Komponente | Datei | Zeilen | Funktion |
|---|---|---|---|
| **X3DH Key Agreement** | `crypto/X3DHSession.kt` | 562 | DH1–DH4, OPK, SPK-Signaturen |
| **Double Ratchet** | `crypto/DoubleRatchet.kt` | 514 | DH-Ratchet, sym. Ratchet, Verschlüsselung |
| **E2EE Manager** | `crypto/E2eeManager.kt` | 980 | Session-Lebenszyklus, Handshake-Koordination |
| **Crypto Primitives** | `transport/internet/CryptoHelper.kt` | 504 | Ed25519, X25519, AES-256-GCM, HKDF |
| **Ed2Curve Conversion** | `crypto/Ed2Curve.kt` | — | Edwards → Montgomery |
| **Out-of-Order Handler** | `crypto/OutOfOrderMessageHandler.kt` | 237 | Verspätete Nachrichten |
| **Handshake Retry** | `crypto/HandshakeRetryManager.kt` | 260 | Exponentielles Backoff |
| **ACK Validator** | `crypto/AckValidator.kt` | 278 | Downgrade-Schutz |
| **Key Rotation** | `crypto/KeyRotationManager.kt` | 316 | SPK 7-Tage, OPK-Tagesrotation |
| **Session Storage** | `crypto/EncryptedSessionStorage.kt` | 211 | AES-GCM via EncryptedSharedPreferences |
| **Session Cleanup** | `crypto/SessionCleanupManager.kt` | 321 | 90-Tage-Inaktivität |

Alle Medien (Text, Bild, Voice) werden über dieselbe Double-Ratchet-Session verschlüsselt (`CrisixApp.kt:538–821`).

### Verschlüsselungs-Flow (aktuell)

```
SENDEN (CrisixApp.kt:1670–1790):
  onSendMessage / onSendImage / onSendVoice
    ├─ hasSession? → e2eeManager.encryptMessage()
    │   └─ DoubleRatchet.ratchetEncrypt(plaintext)
    │       └─ JSON {"t":"e","d":"<encrypted_b64>"}
    ├─ !hasSession? → Unverschlüsselt senden + Handshake triggern
    └─ transportManager.sendMessage(payload)

EMPFANGEN (CrisixApp.kt:329–821):
  registerMessageListener
    ├─ type="crisix_e2ee" → e2eeManager.decryptMessage()
    │   └─ DoubleRatchet.ratchetDecrypt(encryptedMessage)
    │       ├─ Normal-Decrypt (in-order)
    │       └─ Out-of-Order (gecachte ChainKeys)
    ├─ type="crisix_e2ee_handshake" → processHandshakeAsResponder()
    └─ type="crisix_e2ee_ack" → completeHandshakeAsInitiator()
```

### Kritische Schwachstellen (identifiziert)

1. **Race Condition bei parallelen Handshakes** (`E2eeManager.kt:408–421`)
   - Beide Peers starten gleichzeitig einen Handshake → zwei Sessions mit unterschiedlichen Keys
   - Der `sessions.containsKey()`-Guard verhindert Überschreiben, erkennt aber nicht, dass der andere Peer **auch** initiiert hat → eine Seite hat falschen Key

2. **Session-Desynchronisation nach Transport-Fehlern**
   - Nach `bad_decrypt` wird Session geschlossen + Neu-Handshake getriggert (`CrisixApp.kt:758–816`)
   - Während des Neu-Handshakes gesendete Nachrichten gehen unverschlüsselt raus
   - Keine Message-Queue für Nachrichten während Handshake

3. **Unverschlüsselte Fallback-Nachrichten** (`CrisixApp.kt:1545–1564`, `1646–1655`)
   - Wenn `!hasSession` → Nachricht wird unverschlüsselt gesendet
   - Handshake wird asynchron gestartet, aber Nachricht ist schon unterwegs
   - Keinerlei Warnung an den Benutzer

4. **Binary-Blob-Overhead durch Base64 vor Verschlüsselung**
   - Bilder/Voice werden **vor** der Verschlüsselung in Base64 gewandelt → 33% Overhead
   - Für BLE (MTU 512) bedeutet ein 500KB Bild ca. 1500 Chunks statt 1000
   - Besser: Binär verschlüsseln → erst nach Transport Base64

5. **Kein Session-State-Machine**
   - `e2eeSessions` ist ein einfaches `MutableMap<String, Boolean>` → kennt nur "hat Session" oder nicht
   - Keine Zustände wie `HANDSHAKING`, `ACTIVE`, `STALE`, `CLOSED`
   - Führt zu inkonsistenten Entscheidungen über Verschlüsselung

6. **OutOfOrderMessageHandler mit hartcodierter peerId** (`DoubleRatchet.kt:127, 143`)
   - `peerId = "unknown"` → keine Peer-Isolierung bei Out-of-Order-Nachrichten
   - Potenzieller Cross-Peer Key-Leak

7. **Keine PreKey-Server-Infrastruktur**
   - PreKey-Bundles werden via Handshake-Nachricht gesendet → Out-of-Band
   - Kein PreKey-Server → kein Offline-Handshake möglich
   - Erster Kontakt erfordert beidseitige Online-Präsenz

8. **libsignal-Kompatibilität fehlt**
   - Custom-Implementierung ist nicht mit offiziellem Signal-Protokoll kompatibel
   - Kann nicht mit Signal-Clients kommunizieren
   - Kein externes Audit der Krypto-Implementierung

---

## Plan: Signal-Protokoll-Integration

### Entscheidung: Option A vs. Option B

| Kriterium | A: libsignal-client | B: Custom-E2EE härten |
|---|---|---|
| **Sicherheits-Audit** | ✅ Extern geprüft | ❌ Nur Eigenbau |
| **Signal-Kompatibilität** | ✅ Standard-konform | ❌ Inkompatibel |
| **Wartungsaufwand** | Gering (Library-Updates) | Hoch (eigene Bugfixes) |
| **Build-Komplexität** | Mittel (JNI + Native-Setup) | Gering (reines Kotlin) |
| **APK-Größe** | +~8 MB (native .so) | 0 Zuwachs |
| **Risiko** | Migrations-Bugs | Unentdeckte Krypto-Bugs |
| **Abbrüche beheben** | ✅ Stabile State-Machine | Teilweise fixbar |

**Empfehlung: Option A (libsignal-client) als Ziel, Option B als Fallback.**

---

## Phase 1: libsignal-client Integration (Option A — Empfohlen)

### 1.1 Dependency Setup

```kotlin
// app/build.gradle.kts
dependencies {
    // Signal Protocol Library (offiziell, Rust-basiert via JNI)
    implementation("org.signal:libsignal-client:0.60.1")
    
    // CryptoProvider für libsignal (nutzt Android Keystore)
    // libsignal braucht einen CryptoProvider — wir wrappen BouncyCastle
    // oder nutzen den integrierten Native-Crypto
}
```

**Lösbare Herausforderungen:**
- `libsignal-client` enthält native `.so` für arm64-v8a, armeabi-v7a, x86_64
- Gradle-Konfiguration für JNI-Extraktion nötig
- Klasse `org.signal.libsignal.protocol.SignalProtocolAddress` als Peer-Identifier

### 1.2 Identitäts-Management anpassen

**Datei:** `E2eeManager.kt` (rewrite)

```kotlin
// Bisher: Custom Ed25519 KeyPair → Fingerprint
// Neu: libsignal IdentityKeyPair + RegistrationId
class E2eeManager(private val context: Context) {
    private lateinit var identityKeyPair: IdentityKeyPair  // libsignal
    private val registrationId: Int  // Zufallszahl 1..16380
    
    fun initialize() {
        val identityStore = CrisixIdentityKeyStore(context)
        identityKeyPair = identityStore.getIdentityKeyPair()
        registrationId = identityStore.getLocalRegistrationId()
    }
}
```

**Neu zu erstellende Dateien:**

| Datei | Zweck |
|---|---|
| `crypto/signal/SignalProtocolStore.kt` | Interface-Implementierung für libsignal |
| `crypto/signal/CrisixIdentityKeyStore.kt` | Identity-Key im AndroidKeyStore |
| `crypto/signal/CrisixPreKeyStore.kt` | SignedPreKeys + OneTimePreKeys |
| `crypto/signal/CrisixSessionStore.kt` | Session-Persistenz (via Room) |
| `crypto/signal/CrisixSignedPreKeyStore.kt` | SPK-Store |
| `crypto/signal/SignalEncryptionManager.kt` | Neuer zentraler Manager (ersetzt E2eeManager) |

### 1.3 Session-Store via Room

libsignal benötigt persistenten Session-Store. Bisher: EncryptedSharedPreferences. Neu: Room-Datenbank für Sessions.

```kotlin
@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(
    @PrimaryKey val address: String,  // "peerId_deviceId"
    val serialized: ByteArray         // SessionRecord.serialize()
)

@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE address = :address")
    suspend fun load(address: String): SignalSessionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(session: SignalSessionEntity)
    
    @Query("DELETE FROM signal_sessions WHERE address = :address")
    suspend fun delete(address: String)
}
```

### 1.4 PreKey-Bundle-Austausch

libsignal erzwingt korrekten PreKey-Flow:

```
Alice                                    Bob
  │                                        │
  ├─ processPreKeyBundle(bobsBundle) ──────┤
  │   └─ PreKeySignalMessage               │
  │       (verschlüsselt mit initialem     │
  │        Root-Key aus X3DH)              │
  │                                        │
  ├─ PreKeySignalMessage.serialize() ──────┤
  │   (wird via crisix_e2ee_handshake      │
  │    JSON gesendet)                      │
  │                                        │
  │      Bob empfängt ────────────────────┤
  │      processPreKeySignalMessage()     │
  │      → Session gespeichert            │
  │      → SignalMessage als ACK          │
  │                                        │
  │   Alice empfängt ACK ←────────────────┤
  │   → Session gespeichert               │
  │   → ENCRYPTED STATE                   │
```

### 1.5 Verschlüsselungs-Flow (neu)

```kotlin
class SignalEncryptionManager(
    private val store: SignalProtocolStore,
    private val address: SignalProtocolAddress
) {
    fun encrypt(peerId: String, plaintext: ByteArray): ByteArray {
        val sessionCipher = SessionCipher(store, address)
        val ciphertextMessage = sessionCipher.encrypt(plaintext)
        // ciphertextMessage ist SignalMessage oder PreKeySignalMessage
        // Beide haben .serialize() → ByteArray
        return ciphertextMessage.serialize()
    }
    
    fun decrypt(peerId: String, ciphertext: ByteArray): ByteArray {
        val sessionCipher = SessionCipher(store, address)
        val message = when {
            isPreKeySignalMessage(ciphertext) -> PreKeySignalMessage(ciphertext)
            else -> SignalMessage(ciphertext)
        }
        return sessionCipher.decrypt(message)
    }
}
```

### 1.6 Encryption-Layer in CrisixApp.kt anpassen

Die Änderungen in `CrisixApp.kt` sind **minimal** — nur die Aufrufe an `e2eeManager` werden auf `signalManager` umgeleitet:

```kotlin
// ALT (Zeile 1541–1543):
val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)

// NEU:
val encrypted = signalManager.encrypt(normChatId, plainMessage)
```

Der Handshake-Flow in `CrisixApp.kt:1427–1463` wird vereinfacht, da libsignal den PreKey-Bundle-Austausch automatisch managed.

### 1.7 Binary Data Handling (Phase 1.7)

**Kernänderung:** Bilder/Voice werden **binär** verschlüsselt, nicht als Base64:

```kotlin
// ALT (Zeile 1532–1539):
val b64 = Base64.encodeToString(imageBytes, Base64.DEFAULT)
val plainMessage = JSONObject().apply {
    put("type", "image")
    put("data", b64)  // Base64 IN der Verschlüsselung
    ...
}.toString().toByteArray()
val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)

// NEU:
// 1. Binäre Nutzlast vorbereiten (type-Byte + binary)
val binaryPayload = ByteArray(1 + imageBytes.size).apply {
    this[0] = 0x01  // type=image
    imageBytes.copyInto(this, 1)
}
// 2. Binär verschlüsseln → 33% kleiner als Base64-Ansatz
val encrypted = signalManager.encrypt(normChatId, binaryPayload)
// 3. Wire-Format: {"t":"eb","d":"<b64_of_encrypted_bytes>"}
val envelope = JSONObject().apply {
    put("t", "eb")  // "encrypted binary"
    put("d", Base64.encodeToString(encrypted, Base64.NO_WRAP))
}.toString().toByteArray()
```

**Vorteil:** ~33% kleinere Payloads, besonders kritisch für BLE (Chunking).

### 1.8 Message-Queue während Handshake

**Problem Bisher:** Wenn `!hasSession` → Nachricht wird unverschlüsselt gesendet.  
**Neu:** Nachrichten werden in Warteschlange gestellt, bis Handshake abgeschlossen ist:

```kotlin
// SignalEncryptionManager.kt
private val pendingQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<QueuedMessage>>()

data class QueuedMessage(
    val payload: ByteArray,
    val uiMessageId: String,
    val onComplete: (Boolean) -> Unit
)

fun enqueueForHandshake(peerId: String, message: QueuedMessage) {
    pendingQueues.getOrPut(peerId) { ConcurrentLinkedQueue() }.add(message)
}

fun onHandshakeComplete(peerId: String) {
    val queue = pendingQueues.remove(peerId) ?: return
    for (msg in queue) {
        val encrypted = encrypt(peerId, msg.payload)
        // Senden...
    }
}
```

### 1.9 Session-State-Machine

```kotlin
enum class SessionState {
    NONE,           // Keine Session, kein Handshake
    HANDSHAKING,    // Handshake läuft (nicht senden!)
    ACTIVE,         // Session aktiv (verschlüsseln möglich)
    STALE,          // Session >7 Tage alt (Re-Handshake empfohlen)
    COMPROMISED     // BAD_DECRYPT → Reset nötig
}

class EncryptionSessionState(
    val state: SessionState,
    val peerId: String,
    val establishedAt: Long,
    val lastUsedAt: Long
)
```

---

## Phase 2: Reliability Hardening (Unabhängig von Phase 1)

### 2.1 Race-Condition Fix: Bidirektionale Handshake-Erkennung

**Problem:** Beide Peers initiieren gleichzeitig einen Handshake.
**Lösung:** `handshakeNonce` als Tie-Breaker:

```kotlin
// Jeder Handshake bekommt eine 8-Byte Zufalls-Nonce
data class HandshakeInit(
    val handshakeNonce: ByteArray,
    val isInitiator: Boolean
)

// Wenn beide initiieren → der mit höherer Nonce gewinnt
// Der Verlierer verwirft seinen Handshake und nutzt den des Gewinners
fun resolveConcurrentHandshakes(myNonce: ByteArray, theirNonce: ByteArray): Boolean {
    // Lexikographischer Vergleich der Nonces
    for (i in 0 until 8) {
        if ((myNonce[i].toInt() and 0xFF) > (theirNonce[i].toInt() and 0xFF)) return true
        if ((myNonce[i].toInt() and 0xFF) < (theirNonce[i].toInt() and 0xFF)) return false
    }
    return true  // Gleichstand → wir gewinnen
}
```

### 2.2 DH-Key-Konflikt-Erkennung im DoubleRatchet

**Problem:** Nach Session-Reset kann der DoubleRatchet mit falschen Chain-Indizes laufen.
**Lösung:** Header-Validierung im `EncryptedMessage`:

```kotlin
data class EncryptedMessage(
    val dhPublicKey: ByteArray,
    val chainIndex: Int,
    val messageIndex: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    // NEU: Session-Version zur Erkennung von Resets
    val sessionVersion: Int = 0
)
```

### 2.3 Automatische Session-Wiederherstellung

**Problem:** `bad_decrypt` → manueller Neu-Handshake → Downtime.
**Lösung:** Automatischer Re-Handshake ohne Nachrichtenverlust:

```kotlin
fun handleDecryptFailure(peerId: String, failedMessage: ByteArray) {
    // 1. Alte Session archivieren (für Nachvollziehbarkeit)
    archiveSession(peerId)
    
    // 2. Neuen Handshake asynchron starten
    initiateHandshake(peerId)
    
    // 3. Fehlgeschlagene Nachricht erneut einreihen
    retryMessageQueue.add(peerId, failedMessage)
    
    // 4. UI-Updates: DELIVERED → PENDING für alle ausstehenden Nachrichten
    emitDeliveryUpdateBulk(peerId, MessageStatus.PENDING)
}
```

### 2.4 Transport-übergreifende Verschlüsselungs-Idempotenz

**Problem:** Dieselbe Nachricht wird über mehrere Transporte gleichzeitig gesendet → mehrfache DH-Ratchet-Schritte.
**Lösung:** Der TransportManager versendet nacheinander (Priority-Loop), nicht parallel. Der DoubleRatchet wird nicht beeinflusst, da `encryptOnce()` dedupliziert:

```kotlin
private val encryptedCache = ConcurrentHashMap<String, ByteArray>()

fun encryptOnce(peerId: String, messageId: String, plaintext: ByteArray): ByteArray {
    val cacheKey = "$peerId:$messageId"
    return encryptedCache.getOrPut(cacheKey) {
        // Nur EINMAL verschlüsseln → ein DH-Schritt
        encrypt(peerId, plaintext).also {
            // Cache nach 60s leeren
            scheduler.schedule({ encryptedCache.remove(cacheKey) }, 60, TimeUnit.SECONDS)
        }
    }
}
```

---

## Phase 3: Binary Protocol Optimization

### 3.1 Wire-Format mit Typ-Byte

Statt JSON-basierter Typ-Erkennung ein kompaktes Binärformat:

```
Byte 0:     Message Type (0x00=text, 0x01=image, 0x02=voice, 0x03=file,
                         0x10=e2ee_handshake, 0x11=e2ee_ack, 0x20=ack,
                         0xfe=encrypted_envelope, 0xff=system)
Bytes 1-4:  Payload Length (uint32, big-endian)
Bytes 5+:   Payload (entweder Plain-Text oder verschlüsseltes Binary)
```

Vorteil: Kein JSON-Parsing nötig, 1 Byte statt ~20 Bytes Overhead.

### 3.2 Encrypted Envelope Format

```
Byte 0:     0xFE (encrypted_envelope)
Bytes 1-4:  Total length
Bytes 5-8:  Session version (uint32)
Bytes 9-40: DH public key (32 bytes)
Bytes 41-44: Chain index (uint32)
Bytes 45-48: Message index (uint32)
Bytes 49-60: Nonce (12 bytes)
Bytes 61+:  AES-GCM ciphertext (variable)
```

### 3.3 Binary-Medien direkt verschlüsseln (kein Base64-Zwischenschritt)

```kotlin
// onSendImage (CrisixApp.kt ~1473)
val imageBytes = ImageCompressor.compress(context, uri)
val typeByte = byteArrayOf(0x01)  // image
val metaJson = JSONObject().apply {
    put("mime", "image/jpeg")
    put("ts", timestamp)
}.toString().toByteArray()
val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()

// Binär-Payload: [type][meta_len][meta_json][image_bytes]
val payload = typeByte + metaLen + metaJson + imageBytes

// Verschlüsseln (libsignal verarbeitet beliebige byte[])
val encrypted = signalManager.encrypt(peerId, payload)
val envelope = byteArrayOf(0xFE.toByte()) + 
    ByteBuffer.allocate(4).putInt(encrypted.size).array() + 
    encrypted

transportManager.sendMessage(peerId, envelope, uiMessageId = msgId)
```

---

## Phase 4: Testing & Validation

### 4.1 Unit Tests

```
crypto/signal/
├── X3DHKeyAgreementTest.kt        // DH1-DH4 Korrektheit
├── DoubleRatchetForwardSecrecyTest.kt  // FS-Garantien
├── SessionPersistenceTest.kt      // Serialize/Deserialize Roundtrip
├── OutOfOrderDecryptionTest.kt    // 100-Message-Fenster
├── ConcurrentHandshakeTest.kt     // Race-Condition-Szenario
├── TransportFailureRecoveryTest.kt // BAD_DECRYPT → Re-Handshake
└── BinaryMediaEncryptionTest.kt   // 1MB-Bild roundtrip
```

### 4.2 Integration Tests

```
┌─────────────────────────────────────────┐
│ Szenario 1: Normal Flow                 │
│  Alice sendet "Hello" → Bob empfängt    │
│  → Verschlüsselt, kein Abbruch          │
├─────────────────────────────────────────┤
│ Szenario 2: Race Condition              │
│  Alice + Bob starten gleichzeitig H/S   │
│  → Erste Nachricht nach H/S korrekt     │
├─────────────────────────────────────────┤
│ Szenario 3: Transport-Wechsel           │
│  WiFi → BLE während Session aktiv       │
│  → Kein Re-Handshake, Ratchet bleibt    │
├─────────────────────────────────────────┤
│ Szenario 4: Session-Stale               │
│  8 Tage kein Kontakt → Re-Handshake     │
│  → Automatisch, kein Nachrichtenverlust │
├─────────────────────────────────────────┤
│ Szenario 5: Image-Verschlüsselung       │
│  Bild senden via BLE → empfangen        │
│  → Korrekt entschlüsselt, preview ok    │
├─────────────────────────────────────────┤
│ Szenario 6: Voice-Verschlüsselung       │
│  Sprachnachricht via Relay → empfangen  │
│  → Korrekt entschlüsselt, abspielbar    │
├─────────────────────────────────────────┤
│ Szenario 7: Multi-Transport-Handshake   │
│  H/S via BLE, Nachricht via WiFi        │
│  → Session transport-agnostisch         │
└─────────────────────────────────────────┘
```

### 4.3 Stress-Test Suite

```kotlin
class EncryptionStressTest {
    @Test
    fun `1000 rapid messages no de-sync`() {
        // 1000 Nachrichten in 5 Sekunden
        // Keine BAD_DECRYPT-Exception
    }
    
    @Test
    fun `interleaved senders no key conflict`() {
        // A→B→A→B→... 500 interleaved
        // Beide empfangen korrekt
    }
    
    @Test
    fun `50MB video chunk roundtrip`() {
        // Großes Binary fehlerfrei
    }
}
```

---

## Phase 5: Migration Path (Custom → libsignal)

### 5.1 Schrittweise Migration (6 Stages)

| Stage | Was | Rollback |
|---|---|---|
| **S0** | libsignal-Dependency + Kompilierung | Ja (kein Feature-Toggle nötig) |
| **S1** | Neue Store-Klassen implementieren | Ja (bestehende Stores unberührt) |
| **S2** | `SignalEncryptionManager` parallel zu `E2eeManager` | Ja (Feature-Flag) |
| **S3** | `CrisixApp.kt` auf `SignalEncryptionManager` umstellen | Ja (Feature-Flag) |
| **S4** | Binary-Protocol in Transporten aktivieren | Ja (Version-Negotiation) |
| **S5** | Alten `E2eeManager` entfernen | Nein (Breaking Change) |
| **S6** | Cleanup: BouncyCastle-Referenzen aus CryptoHelper entfernen | Nein |

### 5.2 Feature-Flag

```kotlin
// SharedPreferences
const val PREF_USE_LIBSIGNAL = "e2ee_use_libsignal"  // default: true

val useLibsignal = prefs.getBoolean(PREF_USE_LIBSIGNAL, true)
val encryptionManager: EncryptionManager = if (useLibsignal) {
    SignalEncryptionManager(context)
} else {
    LegacyE2eeManager(context)
}
```

---

## Zeitplan

| Phase | Aufgabe | Geschätzter Aufwand |
|---|---|---|
| **1.1–1.5** | libsignal-Integration + Stores | 3 Tage |
| **1.6** | CrisixApp-Integration anpassen | 1 Tag |
| **1.7** | Binary Data Handling (kein Base64) | 1 Tag |
| **1.8** | Message-Queue während Handshake | 0.5 Tage |
| **1.9** | Session-State-Machine | 0.5 Tage |
| **2.1–2.4** | Reliability Hardening | 2 Tage |
| **3.1–3.3** | Binary Protocol Optimization | 1 Tag |
| **4.1–4.3** | Testing | 2 Tage |
| **5.1–5.2** | Migration Path | 0.5 Tage |
| **Build-Verifikation** | `./gradlew assembleDebug` nach jedem Stage | kontinuierlich |

**Gesamt: ~11.5 Tage**

---

## Risiken & Mitigation

| Risiko | Wahrscheinlichkeit | Mitigation |
|---|---|---|
| JNI-Bindings inkompatibel mit AGP 9.x | Mittel | Feature-Flag mit Fallback auf Custom-E2EE |
| Session-Migration (alt → neu) schlägt fehl | Hoch | Beide Seiten starten cleanen Handshake |
| APK-Größe >100MB durch native .so | Niedrig | ABI-Splits (nur arm64-v8a für Play Store) |
| ~8MB Zuwachs durch libsignal .so | Sicher | Akzeptabel (aktuelle APK ~15MB) |
| libsignal PreKey-Server fehlt | Hoch | PreKey-Bundles bleiben über Handshake-Nachricht |
| Binary-Protocol bricht alte Clients | Sicher | Version-Negotiation via Capability-Exchange |
| Room-Migration für SessionStore | Mittel | Neue Tabelle, keine Migration alter Daten |

---

## Erfolgskriterien

- [ ] `./gradlew assembleDebug` → SUCCESSFUL nach jedem Phase-Commit
- [ ] Keine unverschlüsselten Nachrichten mehr bei aktivem Handshake
- [ ] Keine `BAD_DECRYPT`-Abbrüche nach Transport-Wechsel
- [ ] Bilder werden binär verschlüsselt (nicht Base64) → 33% kleiner
- [ ] Sprachnachrichten werden binär verschlüsselt
- [ ] Session-State-Machine zeigt `HANDSHAKING`/`ACTIVE`/`STALE` in UI
- [ ] Race-Condition bei bidirektionalem Handshake gelöst
- [ ] libsignal-Sessions persistent in Room (nicht SharedPreferences)
- [ ] All 7 Integrations-Test-Szenarien grün
- [ ] Stress-Test: 1000 Nachrichten in 5s ohne BAD_DECRYPT

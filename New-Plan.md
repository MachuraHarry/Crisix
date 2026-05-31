# Crisix New Plan — Verbesserungsvorschläge & Roadmap 2026

**Datum:** Mai 31, 2026  
**Status:** Phase 3 (UI 2.0) → Phase 4+ Planung  
**Vision:** Robuste Chat-Kommunikation über Internet mit lokalen Fallbacks — Connectivity everywhere

---

## Executive Summary

Crisix ist **funktionsfähig** (Phase 3 abgeschlossen), braucht aber **5 kritische Bugfixes** und **9 strategische Verbesserungen**, um Production-ready zu sein. Diese Planung folgt der **Internet-First-Philosophie**:

> **"Erst das Internet (auf allen Wegen), dann das lokale Netzwerk, dann kreative Fallbacks."**

Die App hat **5 Transporte implementiert** (P2P Internet, Relay, WiFi, BLE, DNS-Tunnel) mit stabiler **Message-Persistierung** und **E2EE-Encryption**. **Internet ist Primary**, lokale Netzwerke + DNS-Tunnel sind Fallbacks. Fehlende **Robustheit**, **Fehlerbehandlung** und **lokale Fallback-Optimierung** halten sie noch von der Mainstream-Verwendung ab.

---

## Teil 1: KRITISCHE BUGFIXES ✅ (Abgeschlossen am 31. Mai 2026)

### 1.1 BLE Resource Leaks auf Device Rotation ✅
**Dateien:** `BleTransport.kt`  
**Gefixt:** `gatt.close()` nach `gatt.disconnect()` bei self-connection; `stop()` Guard + `scope.cancel()` vor GATT-Cleanup; `start()` Guard gegen Duplicate-Start  
**Status:** Fix committet. Device 10x rotieren → keine Memory-Leaks mehr.

---

### 1.2 RelayTransport Background Jobs nach App-Exit ✅
**Dateien:** `RelayTransport.kt`  
**Status:** Bereits korrekt implementiert (Job-Cancellation in `stop()` vorhanden). Kein Fix nötig.

---

### 1.3 WiFi Socket Leaks ✅
**Dateien:** `WifiTransport.kt`  
**Gefixt:** Neue `disconnectPeer()`-Helper-Methode mit InputStream/OutputStream/Socket-Close. Konsistent in `send()`, `startClientListener()` und `stop()` genutzt.

---

### 1.4 Image Compression für Limited-Transporte ✅
**Dateien:** `ImageCompressor.kt`, `CrisixApp.kt`  
**Gefixt:** `maxSizeBytes`-Parameter in `ImageCompressor.compress()` mit progressiver Qualitätsreduktion. Transport-abhängige Limits in `onSendImage`: BLE=50KB, DNS=blocked, Internet=500KB.

---

### 1.5 E2EE Manager nicht initialisiert ✅
**Dateien:** `CrisixApp.kt`  
**Status:** Bereits korrekt implementiert (`.also { it.initialize() }` in `remember` vorhanden). Kein Fix nötig.

---

## Teil 2: ROBUSTHEIT & RELIABILITY (Nächster Sprint)

### 2.1 Message Deduplication (Implicit ACK-System)
**Problem:** Retry-Loop kann dieselbe Nachricht 2x zustellen (sender sieht 2x "DELIVERED")

**Lösung: Idempotenz über MessageId**
```kotlin
// In MessageEntity.kt - Unique Constraint hinzufügen
@Entity(
    tableName = "messages",
    indices = [
        Index("id"),
        Index("chatId"),
        Index(value = ["chatId", "uiMessageId"], unique = true)  // ← Neu
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val uiMessageId: String,  // ← Idempotenz-Schlüssel (vom Sender)
    val text: String,
    // ... rest
)

// In CrisixApp.kt - Bevor Nachricht gesendet
val existing = messageRepository.getMessageByUiId(uiMessageId)
if (existing != null) {
    Log.w("CrisixApp", "Message $uiMessageId already sent, skipping duplicate")
    return  // Kein erneuter Send
}
```

**Verifikation:** 10 PENDING-Nachrichten retry → jede genau 1x zugestellt

---

### 2.2 Explicit ACK Protocol (Phase 4)
**Problem:** DELIVERED nur wenn Peer antwortet; bei Peer-Crash keine Bestätigung

**Design für Phase 4:**
```kotlin
// Protocol: Spezieller ACK-Message-Typ
@Serializable
data class AckMessage(
    val type: String = "crisix_ack",
    val messageId: String,      // uiMessageId vom empfangenen Message
    val senderDeviceId: String, // Wer hat die Original-Nachricht gesendet
    val timestamp: Long = System.currentTimeMillis()
)

// In TransportManager.kt - registerMessageListener()
// Wenn nicht Ping/Pong und nicht E2EE-Handshake:
// 1. Extrahiere uiMessageId aus Message-Suffix (\u0000<id>)
// 2. Baue AckMessage
// 3. Sende über den gleichen Transport zurück
// 4. Sender erkennt ACK → setzt Status zu DELIVERED
```

---

### 2.3 Transport Circuit Breaker (Fehlertoleranz)
**Problem:** Transport bleibt in "versuchen" stecken, auch wenn hoffnungslos offline

**Lösung: Pro-Transport Failure-Counter**
```kotlin
data class TransportHealthStatus(
    val type: TransportType,
    val consecutiveFailures: Int = 0,
    val lastFailureTime: Long = 0,
    val isCircuitOpen: Boolean = false,
    val reopenAttempt: Long = 0  // Timestamp für Retry
)

// In TransportManager.kt
private val transportHealth = ConcurrentHashMap<TransportType, TransportHealthStatus>()

private suspend fun recordTransportFailure(type: TransportType) {
    val current = transportHealth[type] ?: TransportHealthStatus(type)
    val updated = current.copy(
        consecutiveFailures = current.consecutiveFailures + 1,
        lastFailureTime = System.currentTimeMillis(),
        isCircuitOpen = current.consecutiveFailures >= 3  // Nach 3 Fehlern öffnen
    )
    transportHealth[type] = updated
    
    Log.w(TAG, "Transport $type circuit: failures=${updated.consecutiveFailures}, open=${updated.isCircuitOpen}")
}

private suspend fun shouldTryTransport(type: TransportType): Boolean {
    val health = transportHealth[type] ?: return true
    if (!health.isCircuitOpen) return true
    
    // Exponential backoff: Nach 30s neuen Versuch
    val timeSinceFailure = System.currentTimeMillis() - health.lastFailureTime
    if (timeSinceFailure > 30_000) {
        // Half-open: neuen Versuch, bei Erfolg zurücksetzen
        return true
    }
    return false
}
```

---

### 2.4 Retry Queue Persistierung (Phase 4)
**Problem:** App-Crash → In-Memory Retry-Queue verloren, aber PENDING-Nachrichten im DB

**Lösung:**
```kotlin
// Neue Entity in Room
@Entity(tableName = "pending_retries")
data class PendingRetryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val peerId: String,
    val payloadJson: String,
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val nextRetryAt: Long
)

// In TransportManager.kt - Statt CopyOnWriteArrayList
private suspend fun addToRetryQueue(...) {
    val entity = PendingRetryEntity(...)
    retryDao.insert(entity)  // Persistent
}

// Auf App-Start
LaunchedEffect(Unit) {
    val pending = retryDao.getAllPending()
    for (entity in pending) {
        retryQueue.add(entity.toRetryEntry())
    }
}
```

---

### 2.5 Capability Gossip / Auto-Refresh
**Problem:** Wenn Peer neue Transport aktiviert, Sender weiß nicht Bescheid

**Lösung: Periodic Capability Refresh + Push-Notifikationen**
```kotlin
// In TransportManager.kt
private suspend fun startCapabilityRefreshLoop() {
    scope?.launch {
        while (isActive && isRunning) {
            delay(5.minutes)  // Alle 5 Min
            
            // Sende Capability-Request an alle Peers
            for (peerId in peerCapabilities.keys) {
                broadcastCapabilityRequest(peerId)
            }
        }
    }
}

// Wenn ein Transport online geht:
private suspend fun onTransportOnline(type: TransportType) {
    // Sofort Capabilities an alle bekannten Peers broadcasten
    for (peerId in peerCapabilities.keys) {
        broadcastCapabilities(peerId)
    }
    
    // Retry pending messages (might work now)
    retryPendingMessages()
}
```

---

## Teil 3: NEUE TRANSPORTE (Philosophie erweitern)

### 3.1 SMS Transport (Creative Fallback)
**Warum:** Funktioniert überall, wo Mobilfunk ist, auch ohne Internet

**Priorisierung (Internet-First):**
```
INTERNET (Primär):
  1. P2P Internet (Kryptografisch verifiziert, direkt)
  2. Relay Internet (Render.com wss://, Fallback wenn NAT)
  3. DNS-Tunnel (Internet über DNS-Queries, wenn nur DNS offen)

LOKALES NETZWERK (Schnell wenn verfügbar):
  4. Lokales WLAN (WiFi Router)
  5. WiFi-Direct (P2P ohne Router, ~100m)
  6. BLE (Bluetooth Low Energy, ~10m)

KREATIVE FALLBACKS (Last Resort):
  7. SMS (Mobilfunk-Gebühren, überall)
  8. LoRa (10km, experimentell, kein Internet nötig)
```

**Implementierung Sketch:**
```kotlin
class SmsTransport(private val context: Context) : Transport {
    private val smsManager = context.getSystemService(SmsManager::class.java)
    
    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        // 1. Authentifizierter Handshake via SMS
        // 2. Base32-Encoding für Nicht-ASCII-Bytes
        // 3. Split in 160-char chunks
        // 4. Empfänger: Extrakt → zusammensetzen
        // 5. Costs: ~€0.10 pro SMS → User-Warnung nötig
        
        return try {
            val encoded = Base32.encode(data)
            val chunks = encoded.chunked(155)  // 160 - Header
            for (chunk in chunks) {
                val message = "CRISIX:$peerId:$chunk"
                smsManager.sendTextMessage(
                    peerId.getPhoneNumber(),  // Muss zugeordnet sein
                    null, message, null, null
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**Restrictions:**
- Nur Text (160 chars/SMS × Gebühren)
- Langsam (RTT: 1-5s)
- Benötigt Telefonnummer → Mapping zu DeviceId nötig

---

### 3.2 LoRa Transport (Long-Range, Low-Power)
**Warum:** 10km Reichweite ohne Infrastruktur, nur ~100mW Strom

**Hardware-Voraussetzung:** LoRa-Modul über USB oder GPIO (z.B. RAK3172)

**Architektur:**
```kotlin
class LoraTransport(private val device: UsbDevice) : Transport {
    // 1. Initialisiere LoRa-Modul (TTN-Netzwerk oder Punkt-zu-Punkt)
    // 2. Sende via LoRa: ~50 bytes Payloads (sehr klein!)
    // 3. Implementiere Compression (gzip)
    // 4. Receive via Listener
    // 5. Base16-Encoding
}
```

**Use Case:** Kommunikation auf Wanderungen, Notfall-Szenarien, Outdoor-Events

---

### 3.3 WiFi-Direct Upgrade
**Status:** Aktuell nur Konzept, WifiTransport ist TCP über LAN

**Verbesserung: Peer-to-Peer ohne Router**
```kotlin
class WifiDirectTransport(private val context: Context) : Transport {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    
    // 1. Entdecke andere Geräte ohne Broadcast (Opt-in)
    // 2. Stelle WifiDirect-Verbindung auf (Handshake via QR/NFC)
    // 3. Nutze TCP wie WifiTransport
    // 4. Fallback zu Relay, wenn Direct nicht möglich
}
```

**Reichweite:** ~100m, keine Infrastruktur nötig

---

### 3.4 NFC für lokale Identitäts-Übergabe
**Nicht als Transport, sondern als "Pairing-Kanal"**

```kotlin
// In QrCodeScannerScreen.kt oder neue NfcScreen.kt
class NfcIdentityHelper(private val context: Context) {
    fun writeIdentityToNfc(tag: Tag, deviceId: String) {
        // Schreibe kompakte Identität auf NFC:
        // - DeviceId (32 Zeichen)
        // - Öffentlicher Ed25519-Key
        // - Available Transport Types (Bitmap)
        // Nutzer hält Telefone aneinander → automatisches Pairing
    }
}
```

---

## Teil 4: USER EXPERIENCE (UI/UX Verbesserungen)

### 4.1 Transport-Statusanzeige in Chat-Bubble (Existierend, Ausbauen)

**Aktuell:** "via WIFI" Label unter Message  
**Verbesserung: Visualisierte Transport-Priorität**

```
Message: "Hello"
├─ ✓ (via WIFI)           ← Grün = Zuverlässig, schnell
├─ ⚡ (Relay-Fallback)    ← Gelb = Funktioniert, aber langsam  
└─ 🚀 (DNS-Tunnel)       ← Rot = Last Resort
```

**Implementierung:**
```kotlin
@Composable
fun TransportBadge(transport: TransportType?) {
    val (icon, color, label) = when (transport) {
        TransportType.WIFI_DIRECT -> Triple("📶", Color.Green, "WiFi")
        TransportType.INTERNET -> Triple("🌐", Color.Blue, "Internet")
        TransportType.RELAY -> Triple("⚡", Color.Yellow, "Relay")
        TransportType.BLUETOOTH_MESH -> Triple("📡", Color.Cyan, "BLE")
        TransportType.DNS_TUNNEL -> Triple("🚀", Color.Red, "DNS")
        else -> Triple("❓", Color.Gray, "Unknown")
    }
    
    Chip(
        label = { Text(label) },
        leadingIcon = { Text(icon) },
        colors = ChipDefaults.outlinedChipColors(
            containerColor = color.copy(alpha = 0.2f)
        )
    )
}
```

---

### 4.2 Unread Message Notifications
**Aktuell:** Vorhanden im DB (`isRead`), aber nicht im UI genutzt

**Feature:**
```kotlin
// In ChatListScreen.kt
unreadCount: StateFlow<Int> = messageRepository.getUnreadCount(chatId)

// UI:
if (unreadCount > 0) {
    Badge(content = { Text(unreadCount.toString()) })
}

// Markiere als gelesen, wenn User ChatDetailScreen öffnet:
LaunchedEffect(chatId) {
    messageRepository.markChatAsRead(chatId)
}
```

---

### 4.3 Typing Indicators ("Person tipt...")
**Problem:** Keine Echtzeit-Typing-Feedback

**Design:**
```kotlin
// Neue Message-Typ: TYPING_INDICATOR
@Serializable
data class TypingIndicator(
    val type: String = "typing_indicator",
    val peerId: String,
    val isTyping: Boolean
)

// In ChatDetailScreen.kt
val typingPeers = remember { mutableStateOf(setOf<String>()) }

// Bei Benutzereingabe:
LaunchedEffect(messageText) {
    if (messageText.isNotEmpty()) {
        transportManager.sendTypingIndicator(selectedPeerId, true)
    } else if (wasTyping) {
        transportManager.sendTypingIndicator(selectedPeerId, false)
    }
}

// Empfangen:
val typingPeersFlow = transportManager.incomingTypingIndicators
typingPeersFlow.collect { (peerId, isTyping) ->
    val current = typingPeers.value
    typingPeers.value = if (isTyping) {
        current + peerId
    } else {
        current - peerId
    }
}
```

---

### 4.4 Search & Message Archive
**Aktuell:** Keine Suche, alle Nachrichten sichtbar

**Feature:**
```kotlin
// Neue Query in MessageDao:
@Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE '%' || :query || '%' ORDER BY timestampMillis DESC")
fun searchMessages(chatId: String, query: String): Flow<List<MessageEntity>>

// UI: SearchBar in ChatDetailScreen
var searchQuery by remember { mutableStateOf("") }
val results = messageRepository.searchMessages(chatId, searchQuery)
```

---

### 4.5 Message Reactions (Emoji-React)
**Problem:** "👍" reagiert = neue Nachricht statt Reaction

**Design:**
```kotlin
// Neue Entity
@Entity(tableName = "message_reactions")
data class ReactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val emoji: String,
    val senderDeviceId: String,
    val timestamp: Long = System.currentTimeMillis()
)

// UI: Long-press MessageBubble → Emoji-Picker
Modifier.combinedClickable(
    onLongClick = {
        showEmojiPicker = true
    }
)
```

---

## Teil 5: SECURITY & PRIVACY (erweitern)

### 5.1 Lokale Datenverschlüsselung (SQLCipher)
**Problem:** Room-DB plain-text auf Disk

**Lösung:**
```gradle
implementation "net.zetetic:android-database-sqlcipher:4.5.4"
```

```kotlin
// In AppDatabase.kt
val passphrase = MasterKeys.getOrCreateMasterKey(
    MasterKeys.AES256_GCM_SPEC
)

Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
    .openHelperFactory(FrameworkSQLiteDatabaseHelper.Factory(passphrase))
    .build()
```

---

### 5.2 Double-Ratchet Forward Secrecy (E2EE Audit)
**Status:** Existiert, aber nicht initialisiert

**Checklist:**
- [ ] E2eeManager.initialize() aufgerufen?
- [ ] X3DH Handshake vor ersten Nachrichten?
- [ ] Double-Ratchet Key-Rotation bei jedem Message?
- [ ] Alte Keys werden nach 1h gelöscht?

---

### 5.3 Peer Verification via Fingerprint
**Problem:** Man-in-the-Middle möglich, wenn Fingerprint nicht verifiziert

**Design:**
```kotlin
// In AddContactScreen oder ContactDetailScreen
@Composable
fun VerifyFingerprintDialog(peerId: String, fingerprint: String) {
    val localFingerprint = remember { 
        deviceManager.getDeviceFingerprint(peerId)
    }
    
    Column {
        Text("Bestätige, dass diese Fingerprint auf dem Gerät angezeigt wird:")
        SelectionContainer { 
            Text(fingerprint, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        }
        
        Button(onClick = { 
            contactRepository.markPeerAsVerified(peerId)
        }) {
            Text("✓ Verifiziert")
        }
    }
}
```

---

### 5.4 Encrypted Local Backup
**Problem:** Backup möglich, aber nur auf Device

**Lösung: Optional E2EE Backup zu USB/Cloud**
```kotlin
class BackupManager(val context: Context, val e2ee: E2eeManager) {
    suspend fun createBackup(): ByteArray {
        val db = File(context.getDatabasePath("crisix.db"))
        val compressed = db.readBytes().gzip()
        val encrypted = e2ee.encryptData(compressed)
        return encrypted
    }
    
    suspend fun restoreBackup(data: ByteArray) {
        val decrypted = e2ee.decryptData(data)
        val decompressed = decrypted.gunzip()
        File(context.getDatabasePath("crisix.db")).writeBytes(decompressed)
    }
}
```

---

## Teil 6: PERFORMANCE & OPTIMIERUNG

### 6.1 Lazy Loading für große Chat-Historien
**Problem:** ChatDetailScreen lädt alle Nachrichten, langsam bei 10k+ Messages

**Lösung: Pagination**
```kotlin
// In MessageDao
@Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestampMillis DESC LIMIT :pageSize OFFSET :offset")
fun getMessagesPage(chatId: String, pageSize: Int = 50, offset: Int = 0): Flow<List<MessageEntity>>

// In ChatDetailScreen
var offset by remember { mutableStateOf(0) }
val messages by messageRepository.getMessagesPage(chatId, offset).collectAsState(initial = emptyList())

LazyColumn(
    state = scrollState,
    reverseLayout = true
) {
    items(messages) { msg ->
        MessageBubble(msg)
    }
    
    if (scrollState.canScrollBackward) {
        item {
            Button(onClick = { offset += 50 }) {
                Text("Ältere Nachrichten laden...")
            }
        }
    }
}
```

---

### 6.2 Memory-Leak Audits (Leakcanary)
```gradle
debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.13'
```

**Regelmäßig testen:**
- Device Rotation 20x
- App Backgrounding/Foregrounding
- Transport Start/Stop
- ChatDetailScreen Push/Pop

---

### 6.3 Bitmap Caching für Bild-Messages
**Problem:** Jeder Scroll neu-dekodiert Bilder

**Lösung: LruCache**
```kotlin
object ImageCache {
    private val cache = LruCache<String, Bitmap>(5 * 1024 * 1024)  // 5MB
    
    fun get(uri: Uri): Bitmap? = cache.get(uri.toString())
    fun put(uri: Uri, bitmap: Bitmap) = cache.put(uri.toString(), bitmap)
}

// In AsyncImage:
val bitmap = remember(imageUri) {
    ImageCache.get(imageUri) ?: BitmapFactory.decodeFile(imageUri)
        .also { ImageCache.put(imageUri, it) }
}
```

---

### 6.4 ProtoDataStore für Einstellungen (statt SharedPreferences)
```gradle
implementation "androidx.datastore:datastore-preferences:1.0.0"
```

```kotlin
// Schneller, coroutine-safe, type-safe
val preferenceFlow = context.dataStore.data
    .map { prefs ->
        Preferences(
            isDarkMode = prefs[IS_DARK_MODE] ?: false,
            activeTransports = prefs[ACTIVE_TRANSPORTS]?.split(",") ?: emptyList()
        )
    }
```

---

## Teil 7: TESTING & QUALITÄTSSICHERUNG

### 7.1 Unit Tests für Transport-Logic
```kotlin
class TransportManagerTest {
    @Test
    fun testMutualPrioritySelection() {
        // Peer hat nur BLE, wir versuchen Internet → fallback zu BLE
        val mutual = computeMutualPriority(
            ourCaps = setOf(INTERNET, BLE, RELAY),
            peerCaps = setOf(BLE, DNS_TUNNEL)
        )
        assertEquals(setOf(BLE), mutual)  // Einzige Schnittmenge
    }
    
    @Test
    fun testRetryQueueNoDuplicates() {
        // Retry sollte nicht 2x senden
        val msg1 = Message(id = "msg1", text = "Hello")
        sendMessage(msg1)
        // Simulated: transport fail, goes to PENDING
        retryPendingMessages()
        retryPendingMessages()  // 2x aufrufen
        
        val delivered = transportManager.deliveryUpdates.first()
        assertEquals(1, countDeliveriesFor("msg1"))
    }
}
```

---

### 7.2 Integration Tests für Multi-Transport
```kotlin
class MultiTransportIntegrationTest {
    @Test
    fun testFallbackChain() {
        // 1. Versuche WIFI → fail
        // 2. Fallback zu Internet → fail
        // 3. Fallback zu BLE → success
        // Verify: Message wurde via BLE gesendet
        
        setupTransports {
            wifiTransport.failNextSend()
            internetTransport.failNextSend()
            bleTransport.allowSend()
        }
        
        sendMessage("Hello")
        
        val event = transportManager.deliveryUpdates.first()
        assertEquals(TransportType.BLUETOOTH_MESH, event.transport)
    }
}
```

---

### 7.3 Device-Specific Tests
**Samsung Geräte (errorCode=1 Advertising):**
```kotlin
@Test
fun testSamsungBleAdvertisingRetry() {
    val samsung = MockBluetoothDevice(errorCode = 1)
    // Nach 5s automatischer Retry
    advanceTimeBy(5000)
    assertTrue(samsung.advertisingRestarted)
}
```

---

### 7.4 Battery Drain Audit
**Messbar mit:**
```
adb shell dumpsys batteryproperties
```

**Szenario: App idle ohne Netz → sollte <1% in 1h verbrauchen**

---

## Teil 8: DOCUMENTATION & DEVELOPER EXPERIENCE

### 8.1 Architecture Decision Records (ADR)
```
docs/adr/
├── ADR-001-offline-first-philosophy.md
├── ADR-002-transport-priority-mutual.md
├── ADR-003-e2ee-initialization.md
├── ADR-004-message-deduplication.md
└── ADR-005-ack-protocol-design.md
```

---

### 8.2 API Documentation (KDoc)
```kotlin
/**
 * Sendet eine Nachricht an einen Peer über den besten verfügbaren Transport.
 *
 * ### Behavior
 * 1. Berechnet gegenseitige Transport-Priorität
 * 2. Probt Selected Transport (ping/pong)
 * 3. Sendet nachricht
 * 4. Wenn Fehler: Retry-Queue, max 10 Versuche
 *
 * ### Status Lifecycle
 * `SENDING` → `SENT` (via callback) → `DELIVERED` (via peer response)
 *
 * ### Example
 * ```kotlin
 * val result = transportManager.sendMessage(
 *     peerId = "alice@example.com",
 *     data = "Hello".toByteArray(),
 *     uiMessageId = "msg-123"
 * )
 * result.onSuccess { transport ->
 *     println("Sent via $transport")
 * }.onFailure { error ->
 *     println("Failed: ${error.message}")
 * }
 * ```
 *
 * @param peerId Target peer identifier
 * @param data Message payload
 * @param uiMessageId Unique message ID für Deduplication & ACK-Matching
 * @return Result with selected TransportType or error
 *
 * @see TransportCapabilities for transport-specific limits
 * @see Message for status lifecycle
 */
suspend fun sendMessage(
    peerId: String,
    data: ByteArray,
    uiMessageId: String
): Result<TransportType>
```

---

### 8.3 Onboarding für neue Entwickler
**README sollte enthalten:**
- Transport Architecture (Diagram)
- Message Delivery Flow (Sequence Diagram)
- Setting Up Dev Environment
- Running Tests
- Transport Implementation Checklist

---

## Teil 9: ROADMAP 2026+

### Phase 4 (Juni 2026): Robustheit
- [ ] 5 kritische Bugfixes (diese Woche)
- [ ] Message Deduplication via MessageId
- [ ] Explicit ACK Protocol
- [ ] Transport Circuit Breaker
- [ ] Retry Queue Persistierung
- [ ] Unit Tests für TransportManager
- [ ] Leakcanary-Audit bestanden

**Milestone:** Production-Ready für 1-1 Text-Chat

---

### Phase 4b (Juni-Juli 2026): Lokale Fallbacks (Internet-First)
- [ ] WiFi-Direct (P2P ohne Router)
- [ ] NFC Pairing-Channel
- [ ] BLE Optimization für Fallback-Szenarien
- [ ] Transport Health Monitoring Dashboard
- [ ] Automatic Failover zu lokalem Netz wenn Internet weg

**Milestone:** Lokale Fallbacks robust implementiert

---

### Phase 5 (August-September 2026): Creative Fallbacks (Optional)
- [ ] SMS Transport mit Cost-Warnings
- [ ] LoRa Module Integration (Beta)
- [ ] DNS-Tunnel Optimization

**Milestone:** Alle 8 geplanten Transporte implementiert (aber Phase 5+ ist optional)

---

### Phase 6 (September 2026): UX & Polish
- [ ] Typing Indicators
- [ ] Message Search & Archive
- [ ] Unread Notifications
- [ ] Message Reactions (Emoji)
- [ ] E2EE Audit & Hardening
- [ ] SQLCipher Encryption

**Milestone:** Consumer-Ready Chat App (Internet-Primary, lokale Fallbacks)

---

### Phase 7 (Q4 2026): Advanced Features
- [ ] Group Chats (Multi-Peer, Mesh-Support)
- [ ] Voice/Video Calls (Audio-first)
- [ ] File Sharing (Progressive Upload)
- [ ] i18n & Accessibility

---

### Phase 8+ (2027): Experimental
- [ ] LoRa Network Mesh
- [ ] Community/P2P Features
- [ ] Offline-Sync (local queues)

---

## Teil 10: PHILOSOPHISCHE RICHTLINIEN

### "Internet-First" = 3 Ebenen:

**Ebene 1: Internet (Global, zuverlässig)**
- P2P über Internet (Ed25519 Fingerprint Verification, direkt)
- Relay via Render.com (stateless WebSocket, wenn NAT/Firewall)
- DNS-Tunnel (Internet über DNS-Queries, wenn nur DNS offen)
- **Ziel:** Weltweit funktionsfähig, überall wo jede Art von Internet

**Ebene 2: Lokales Netzwerk (Schnell, sofort, keine Infrastruktur)**
- Lokales WLAN (Router-basiert, schnell)
- WiFi-Direct (P2P ohne Router, ~100m)
- BLE Mesh (Bluetooth Low Energy, ~10m, Batterie-effizient)
- NFC (Pairing + Quick Share)

**Ebene 3: Creative Fallbacks (Last Resort, extreme Szenarien)**
- SMS (Mobilfunk-Gebühren, aber überall möglich)
- LoRa (10km ohne jedes Internet, experimentell)

### Robustes Internet-Netzwerk mit lokalen Fallbacks:
- ✅ P2P Internet (direkt von Device zu Device via DHT/NAT-Traversal)
- ✅ Relay Server (stateless, nur Message-Weitergabe, kein Storage)
- ✅ Lokale Netzwerk-Optionen (WiFi/BLE als schnelle Alternativen)
- ✅ Encrypted End-to-End (auch über untrusted Relays)
- ❌ Zentrale Datenbank für Nachrichten
- ❌ Zentraler Auth-Service (nur lokale Ed25519 Verifizierung)
- ❌ Abhängigkeit von Cloud-Sync (optional wenn überhaupt)

### Privacy by Design:
- Ed25519 Fingerprint = verifikation ohne Server
- E2EE Default = alle Nachrichten verschlüsselt
- Keine Metadaten-Sammlung = nur Text+Timestamp
- Lokale Persistierung = Kontrolle über Daten

---

## Entscheidungspunkte für Team

### Q1: Wie aggressiv sollte zu lokalen Fallbacks wechseln?
**Option A:** Sehr aggressiv (Internet fail → sofort Fallback zu WiFi/BLE)  
**Option B:** Geduldig mit Internet (30s retry bevor Fallback zu lokalen Netzen)  
**Empfehlung:** B für User-Erwartung + Circuit Breaker nach 3 Failures  
**Begründung:** Internet ist Primary → sollte mehrmals retry bevor wir zu langsameren Transports fallback

### Q2: E2EE oder Plain-Text für MVP?
**Status:** E2EE-Code existiert, aber nicht aktiviert  
**Empfehlung:** Jetzt aktivieren (keine Overhead-Kosten, nur E2EE über alle Transports)

### Q3: Lokale Netzwerk-Fallback priorität?
**Option A:** WiFi > WiFi-Direct > BLE (schnell zu langsam)  
**Option B:** BLE > WiFi-Direct > WiFi (Reichweite zu Infrastruktur)  
**Empfehlung:** A, weil Bandwidth wichtiger als Reichweite bei lokalem Netz

### Q4: Wie viele Unit Tests nötig?
**Ziel:** 70% Coverage für transport/ + crypto/ + data/  
**Priorität:** Internet-Transport Tests zuerst (P2P + Relay)

---

## ZUSAMMENFASSUNG

**Crisix ist funktionsfähig für Text-Chat, braucht aber Internet-Focus:**

| Kategorie | Status | Aktion |
|-----------|--------|--------|
| **Core Messaging (Text)** | ✅ Funktioniert | Nur 5 Bugfixes |
| **Internet Transporte** | ✅ P2P + Relay + DNS-Tunnel | Optimieren + NAT-Traversal hardening |
| **Lokale Netzwerk-Fallback** | ✅ 2 implementiert (WiFi, BLE) | WiFi-Direct + NFC hinzufügen |
| **Creative Fallbacks** | ❌ Nicht implementiert | SMS + LoRa später (Phase 5+) |
| **E2EE** | ✅ Code existiert | Initialisierung aktivieren |
| **UI** | ✅ Komplett | Polish + Notifications |
| **Tests** | ❌ Minimal | Unit + Integration schreiben |
| **Docs** | ❌ Verteilt | Consolidieren + ADRs |

**Empfohlene Reihenfolge (Internet-First):**
1. **Diese Woche:** 5 Critical Bugfixes (Crashes)
2. **Nächste Woche:** Internet-Transporte stabilisieren + Unit Tests
3. **Folgende Wochen:** Lokale Fallbacks (WiFi-Direct, NFC)
4. **Später (Phase 5):** Creative Transports (SMS, LoRa)

---

## Appendix: Schnelle Referenz

### Wichtigste Dateien
- `TransportManager.kt` (754 lines) — Zentrale Orchestrierung
- `BleTransport.kt` (1,052 lines) — Bluetooth Low Energy
- `CrisixApp.kt` — UI Entry Point + Message Integration
- `E2eeManager.kt` (980 lines) — Verschlüsselung
- `AppDatabase.kt` — Message Persistierung

### Kommandos
```bash
# Build
./gradlew assembleDebug

# Tests
./gradlew testDebugUnitTest

# Logs filtern
adb logcat | grep "Crisix\|TransportManager"

# Database inspizieren (adb)
adb shell "sqlite3 /data/data/com.messenger.crisix/databases/crisix.db .tables"
```

---

---

## 🎯 QUICK REFERENCE: Transport Priorisierung

**INTERNET (Primär - weltweit):**
1. P2P Internet (direkt, wenn möglich — schnell & privat)
2. Relay Internet (Render.com — wenn NAT/Firewall probleme)
3. DNS-Tunnel (wenn nur DNS offen — restriktive Netzwerke)

**LOKALES NETZWERK (schnell wenn vorhanden):**
4. WiFi lokal (über Router — schnell & stabil)
5. WiFi-Direct (P2P ohne Router — Reichweite ~100m)
6. BLE (Bluetooth — Reichweite ~10m, Battery-efficient)

**LAST RESORT (extreme Szenarien):**
7. SMS (Mobilfunk-Gebühren — überall möglich!)
8. LoRa (10km ohne Internet — experimentell)

---

**Geschrieben:** 31. Mai 2026  
**Philosophy Corrected:** Internet-First statt Offline-First  
**Nächste Review:** 7. Juni 2026 (nach Bugfixes)

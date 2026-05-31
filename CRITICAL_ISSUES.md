# Crisix Critical Issues & Action Items

**Date:** May 31, 2026  
**Status:** Phase 3 (UI 2.0)  
**Priority:** Address before production release

---

## 🔴 CRITICAL ISSUES (Must Fix)

### 1. Resource Leaks in BLE Transport
**File:** `BleTransport.kt`  
**Severity:** HIGH  
**Issue:** BluetoothGatt connections never explicitly closed in all code paths

**Code locations:**
- Line 340+: `onConnectionStateChange` - GATT not closed on disconnect
- Line 550+: `connectToDevice()` - No try-finally for GATT.close()

**Symptom:** 
- Memory leaks on configuration changes
- Activity destruction leaks scope references
- Multiple reconnections exhaust Bluetooth resources

**Fix:**
```kotlin
// In stop() method - add explicit cleanup
override suspend fun stop() {
    isRunning = false
    for ((_, conn) in peerConnections) {
        conn.gatt?.close()  // MUST close GATT
    }
    peerConnections.clear()
    bluetoothLeAdvertiser?.stopAdvertisingSet(advertisingSetCallback)
    bluetoothLeScanner?.stopScan(scanCallback)
    gattServer?.close()
    scope?.cancel()
}
```

**Test:** Rotate device 10 times, check BLE reconnects cleanly

---

### 2. RelayTransport Job Cleanup
**File:** `RelayTransport.kt`  
**Severity:** HIGH  
**Issue:** Jobs not cancelled in stop() method

**Current code:**
```kotlin
private var reconnectJob: Job? = null
private var keepaliveJob: Job? = null
```

**Problem:** These jobs continue running after stop() is called

**Fix:**
```kotlin
override suspend fun stop() {
    isRunning = false
    reconnectJob?.cancel()
    keepaliveJob?.cancel()
    webSocket?.close(1000, "Normal closure")
    okHttpClient = null
    scope?.cancel()
}
```

**Symptom:** Relay tries to reconnect even after user leaves chat

---

### 3. WifiTransport Socket Not Closed
**File:** `WifiTransport.kt`  
**Severity:** HIGH  
**Issue:** Sockets in `connectedClients` map never explicitly closed

**Current code:**
```kotlin
private val connectedClients = ConcurrentHashMap<String, Socket>()
```

**Problem:** On socket errors, connection stays open consuming resources

**Fix:**
```kotlin
private suspend fun disconnectPeer(peerId: String) {
    val socket = connectedClients.remove(peerId)
    try {
        socket?.getInputStream()?.close()
        socket?.getOutputStream()?.close()
        socket?.close()
    } catch (e: Exception) {
        Log.w(TAG, "Error closing socket for $peerId: ${e.message}")
    }
}
```

---

### 4. No Message Deduplication
**File:** `CrisixApp.kt` & `TransportManager.kt`  
**Severity:** MEDIUM  
**Issue:** Same message can be delivered multiple times if retry triggers + peer responds

**Scenario:**
1. User sends msg ID "abc123"
2. Transport fails, goes to PENDING
3. Retry fires after 10s → msg re-sent with SAME ID
4. Both copies delivered to peer
5. Peer processes both → sends 2 responses
6. Sender shows message delivered twice

**Root cause:** No uniqueness check on (peerId, messageId) pair

**Fix:**
```kotlin
// In CrisixApp.kt onSendMessage
val messageId = UUID.randomUUID().toString()
// Check: don't send if messageId already in database for this peer
val existing = messageRepository.getMessageById(messageId)
if (existing != null) {
    return // Skip duplicate
}
```

---

### 5. Mutual Priority Not Re-evaluated on Retry
**File:** `TransportManager.kt` lines 550-568  
**Severity:** MEDIUM  
**Issue:** If peer's capabilities change, retry doesn't adapt

**Scenario:**
1. User sends message over Internet (only transport peer had)
2. Message fails, goes to PENDING with Internet transport tried
3. Peer comes online with BLE → broadcasts capabilities
4. Retry fires but still only tries Internet (old Mutual Priority)
5. Message never delivered (should use BLE)

**Fix:**
```kotlin
private suspend fun retryPendingMessages() {
    if (retryQueue.isEmpty()) return
    val entries = retryQueue.toList()
    val failedEntries = mutableListOf<RetryEntry>()
    for (entry in entries) {
        // ✅ sendMessage() will re-evaluate Mutual Priority
        // because capabilities were updated since first attempt
        val result = sendMessage(entry.peerId, entry.data, entry.uiMessageId)
        if (result.isFailure) {
            // existing retry logic
        }
    }
}
```

**Status:** Already correct! sendMessage() is called which re-evaluates. **Mark as resolved.**

---

### 6. No Explicit ACK Protocol
**File:** System-wide  
**Severity:** MEDIUM  
**Issue:** DELIVERED status inferred from peer's response, not explicit ACK

**Risk:**
- Peer crashes after reading message → sender never knows delivery (shows ✓ SENT, not ✓✓ DELIVERED)
- No NAK (negative acknowledgment) for failed crypto or parsing

**Scenario:**
1. Alice sends: "Hello"
2. Bob receives & processes (shows on Bob's screen)
3. Bob's app crashes before responding
4. Alice sees ✓ SENT (not ✓✓ DELIVERED) forever

**Recommended Fix (Phase 4):**
```kotlin
// Protocol: ACK message format
{
    "type": "crisix_ack",
    "messageId": "<uiMessageId from received message>",
    "peerId": "<sender's peer ID>",
    "timestamp": <epoch millis>
}

// In registerMessageListener: if not Ping/Pong and not E2EE-handshake
// Extract uiMessageId from payload suffix (\u0000<id>)
// Auto-send ACK
```

**Workaround for now:**
- Accept implicit delivery (peer's next message = confirmation)
- Document limitation in UI: "Delivery confirmed when peer responds"

---

## 🟡 IMPORTANT ISSUES (Fix Before Release)

### 7. Image Compression Missing
**File:** `ChatDetailScreen.kt` & `TransportManager.kt`  
**Severity:** HIGH  
**Issue:** Images sent directly over BLE/DNS will fail or truncate

**Current code:**
```kotlin
// ChatDetailScreen.kt - onSendImage
onSendImage?.invoke(uri)
```

**Problem:**
- BLE max: 475 bytes per chunk
- DNS max: 200 characters (base32 encoded)
- User sends 5MB photo → instant failure

**Required Fix:**
```kotlin
// In CrisixApp.kt onSendImage callback
private suspend fun onSendImage(uri: Uri) {
    val caps = transportManager.getCurrentCapabilities()
    if (!caps.supportsImages) {
        showToast("Current transport doesn't support images")
        return
    }
    
    // Compress image based on transport
    val maxSizeKb = when (activeTransport) {
        TransportType.BLUETOOTH_MESH -> 50
        TransportType.DNS_TUNNEL -> 30
        else -> 1000
    }
    
    val compressed = ImageCompressor.compress(uri, maxSizeKb * 1024)
    // Send compressed...
}
```

**Status:** ImageCompressor.kt exists but not integrated. **INTEGRATE NOW.**

---

### 8. E2EE Not Initialized by Default
**File:** `CrisixApp.kt` line 150+  
**Severity:** MEDIUM  
**Issue:** E2eeManager created but `initialize()` never called

**Current code:**
```kotlin
val e2eeManager = remember(context) {
    E2eeManager(context)
    // Missing: e2eeManager.initialize()
}
```

**Fix:**
```kotlin
LaunchedEffect(Unit) {
    withContext(Dispatchers.Default) {
        e2eeManager.initialize()  // ← Add this
    }
}
```

**Symptom:** All messages sent unencrypted even with E2EE available

---

### 9. Thread Safety: RelayTransport Listener List
**File:** `RelayTransport.kt` line 37  
**Severity:** LOW  
**Issue:** `messageListeners` uses `mutableListOf` (not thread-safe)

**Current:**
```kotlin
private val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
```

**Fix:**
```kotlin
private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
```

---

### 10. Transport Status Updates Trigger Unnecessary Recompositions
**File:** `TransportManager.kt` lines 310-327  
**Severity:** LOW  
**Issue:** UI recomposes even if values unchanged

**Example:**
```kotlin
updateConnectionStatus(type, ConnectionState.CONNECTED, 5, "5 peers")
// Then called again with identical values
updateConnectionStatus(type, ConnectionState.CONNECTED, 5, "5 peers")
// Both trigger StateFlow emission
```

**Fix:**
```kotlin
fun updateConnectionStatus(...) {
    val currentMap = _connectionStatuses.value.toMutableMap()
    val newStatus = ConnectionStatus(...)
    if (currentMap[type] == newStatus) return  // Skip if identical
    currentMap[type] = newStatus
    _connectionStatuses.value = currentMap
}
```

---

## 🟠 KNOWN LIMITATIONS (Document & Plan)

### 11. Stale Capability Data
**Issue:** Peer capabilities not auto-refreshed

**Impact:** If peer's transport changes (e.g., enables Internet), sender doesn't know

**Current behavior:**
- BLE: Broadcasts on network state change
- Internet/Relay: No capability broadcast at all
- WiFi: No capability broadcast

**Recommended fix (Phase 4):**
- Periodic capability refresh request (every 5 min on idle)
- Gossip protocol for capability changes

---

### 12. No Group Chat Support
**Scope:** Single-peer conversations only

**Planned:**
- Extend MessageEntity: groupId, participants[]
- Negotiate lowest-common-denominator transport for group
- Handle group member add/remove

---

### 13. Retry Queue Not Persisted
**Issue:** If app crashes, pending messages in queue are lost

**Current:**
```kotlin
private val retryQueue = CopyOnWriteArrayList<RetryEntry>()  // In-memory
```

**Scenario:**
1. 5 messages in PENDING state
2. Device out of battery → app crash
3. User restarts → messages gone (only in DB as PENDING)

**Fix for Phase 4:**
- Add PendingMessageEntity to Room database
- Load from DB on startup
- Remove from DB only after DELIVERED or max retries

---

## 📋 VERIFICATION CHECKLIST

Before deploying to production:

- [ ] Run 10x device rotation on BLE Transport → no leaks
- [ ] Stop app while Relay reconnecting → no background reconnect after exit
- [ ] Send 5x WiFi message, check all sockets closed
- [ ] Retry 10 PENDING messages, verify no duplicates in Chat
- [ ] Compress large image (5MB) before BLE/DNS send → success
- [ ] Test E2EE handshake → messages encrypted end-to-end
- [ ] Peer capabilities change → retry adapts transport choice
- [ ] RelayTransport health check passes
- [ ] No DEBUG logs in production build

---

## 🎯 PRIORITY ROADMAP

**IMMEDIATE (This Week):**
1. Fix BleTransport.stop() resource cleanup
2. Fix RelayTransport job cancellation
3. Fix WifiTransport socket cleanup
4. Integrate ImageCompressor for BLE/DNS
5. Initialize E2eeManager

**SHORT-TERM (Next Sprint):**
1. Add explicit ACK protocol
2. Implement message deduplication
3. Add transport circuit breaker
4. Fix RelayTransport.messageListeners thread safety

**MEDIUM-TERM (Phase 4):**
1. Peer capability refresh protocol
2. Unread notification system
3. Message search/archive
4. Group chat support

---

## 📞 DECISION POINTS FOR TEAM

### Q1: Should we implement explicit ACK now or accept implicit?
**Current:** Implicit (peer's next message = confirmation)  
**Recommendation:** Keep implicit for Phase 3, implement explicit in Phase 4  
**Risk:** Low (most messages result in replies anyway)

### Q2: Should Image compression be mandatory?
**Current:** Optional, crashes on BLE/DNS if too large  
**Recommendation:** YES - auto-compress before ANY send based on transport  
**Risk:** Medium if not fixed (users can't send photos)

### Q3: Should we persist retry queue?
**Current:** In-memory only  
**Recommendation:** YES for Phase 4 (critical for battery drain scenario)  
**Risk:** Low (PENDING messages still exist in DB, just not auto-retried)

---


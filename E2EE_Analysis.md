# Crisix E2EE Implementation - Comprehensive Analysis

## 1. WHERE IS THE X3DH HANDSHAKE IMPLEMENTED?

### Main File
**Location**: `/home/harry/AndroidStudioProjects/Crisix/app/src/main/java/com/messenger/crisix/crypto/X3DHSession.kt`

### Main Flow

#### Initiator (Alice) Flow
1. **Create Handshake** (`E2eeManager.createHandshake()`)
   - File: `E2eeManager.kt`, Lines 467-481
   - Creates a new `X25519KeyPair` (ephemeral)
   - Calls `X3DHSession.createPreKeyBundle(useOneTimePreKey=true)`
   - Returns `HandshakeInitData` containing:
     - `preKeyBundleJson` (Bob's bundle to be received later)
     - `ownEphemeralPrivateKey` (Alice's ephemeral private key)
     - `ownEphemeralPublicKey` (Alice's ephemeral public key - raw 32 bytes)

2. **Send Handshake**
   - File: `CrisixApp.kt`, Lines 978-990, 1201-1213
   - Sends PreKeyBundle as JSON via Transport
   - Also sends `ephemeralKey` (Alice's EK_A) in a separate field

3. **Complete Handshake as Initiator**
   - File: `E2eeManager.kt`, Lines 330-403
   - Called when receiving ACK from responder
   - Receives Bob's `PreKeyMessage` in the ACK
   - Calls `x3dhSession.processAsInitiator()`
   - Parameters:
     - `peerPreKeyMessage`: Bob's response message
     - `ownEphemeralPrivateKey`: Alice's ephemeral private key
     - `peerBundle`: Bob's PreKeyBundle (sent initially)
   - Returns initial `SessionState` for Double Ratchet

#### Responder (Bob) Flow
1. **Receive Handshake**
   - File: `CrisixApp.kt`, Lines 349-388
   - Receives Alice's PreKeyBundle and her ephemeral key (EK_A)
   - Extracts:
     - `handshakeData`: Alice's PreKeyBundle
     - `ephemeralKeyB64`: Alice's EK_A (base64-encoded)

2. **Process as Responder**
   - File: `E2eeManager.kt`, Lines 220-316
   - Calls `x3dhSession.processAsResponder()`
   - Parameters:
     - `peerBundle`: Alice's PreKeyBundle
     - `peerEphemeralKey`: Alice's EK_A
   - Returns: Triple of (SessionState, usedOneTimePreKey flag, usedOTPK public)
   - Creates and sends `PreKeyMessage` back to Alice

### X3DH Implementation Details
**File**: `X3DHSession.kt`

#### Initiator (Alice) DH Calculations
```kotlin
fun processAsInitiator(
    peerPreKeyMessage: PreKeyMessage,  // Bob's response
    ownEphemeralPrivateKey: ByteArray, // EK_A private
    peerBundle: PreKeyBundle           // Bob's PreKeyBundle
)
```

DH Calculations (Lines 356-380):
- **DH1** = `DH(IK_A_priv, SPK_B)` — Alice's identity + Bob's SignedPreKey
- **DH2** = `DH(EK_A_priv, IK_B)` — Alice's ephemeral + Bob's identity (converted to X25519)
- **DH3** = `DH(EK_A_priv, SPK_B)` — Alice's ephemeral + Bob's SignedPreKey
- **DH4** = `DH(EK_A_priv, OPK_B)` — Alice's ephemeral + Bob's OneTimePreKey (optional)

Shared Secret: `DH1 || DH2 || DH3 || DH4` (or without DH4 if no OTPk)

Root Key Derivation (Lines 400-406):
- `RootKey = HKDF(SharedSecret, salt=null, info="Crisix-X3DH-InitialRootKey", length=32)`

Initial Chain Keys (Lines 417-428):
- `SendingChainKey = HKDF(RootKey, salt=RootKey, info="Crisix-X3DH-InitialSendingChainKey")`
- `ReceivingChainKey = HKDF(RootKey, salt=RootKey, info="Crisix-X3DH-InitialReceivingChainKey")`

#### Responder (Bob) DH Calculations
```kotlin
fun processAsResponder(
    peerBundle: PreKeyBundle,  // Alice's PreKeyBundle
    peerEphemeralKey: ByteArray // Alice's EK_A
)
```

DH Calculations (Lines 195-230):
- **DH1** = `DH(SPK_B_priv, IK_A)` — Bob's SignedPreKey + Alice's identity
- **DH2** = `DH(IK_B_priv, EK_A)` — Bob's identity (converted to X25519) + Alice's ephemeral
- **DH3** = `DH(SPK_B_priv, EK_A)` — Bob's SignedPreKey + Alice's ephemeral
- **DH4** = `DH(OPK_B_priv, EK_A)` — Bob's OneTimePreKey + Alice's ephemeral (optional)

Both sides compute the SAME shared secret (same four DH values, same concatenation order).

#### Key Conversion: Ed25519 ↔ X25519
**File**: `Ed2Curve.kt`

- **Public Key Conversion**: `ed25519PublicToX25519()` (Lines 36-73)
  - Extracts y-coordinate from Ed25519 compressed key
  - Uses formula: `u = (1 + y) / (1 - y) mod p`
  - Returns X25519-compatible public key
  
- **Private Key Conversion**: `ed25519PrivateToX25519()` (Lines 88-113)
  - Uses SHA-512 hash of the seed
  - Applies RFC 7748 clamping (set/clear specific bits)
  - Returns X25519-compatible private key

#### PreKeyBundle Structure
```kotlin
data class PreKeyBundle(
    val identityKey: ByteArray,              // 32-byte Ed25519 public
    val signedPreKey: ByteArray,             // 32-byte X25519 public
    val signedPreKeySignature: ByteArray,    // 64-byte Ed25519 signature
    val oneTimePreKey: ByteArray? = null     // optional 32-byte X25519 public
)
```

#### PreKeyMessage Structure
```kotlin
data class PreKeyMessage(
    val identityKey: ByteArray,              // 32-byte Ed25519 public (responder's)
    val ephemeralKey: ByteArray,             // 32-byte X25519 public (responder's EK_B)
    val signedPreKey: ByteArray,             // 32-byte X25519 public (responder's SPK)
    val usedOneTimePreKey: Boolean,          // flag if OTPk was used
    val oneTimePreKey: ByteArray? = null     // 32-byte X25519 public (responder's used OTPk)
)
```

---

## 2. WHERE IS THE DOUBLE RATCHET ENCRYPTION IMPLEMENTED?

### Main File
**Location**: `/home/harry/AndroidStudioProjects/Crisix/app/src/main/java/com/messenger/crisix/crypto/DoubleRatchet.kt`

### Main Components

#### Encryption (`ratchetEncrypt`)
**Lines 56-90**

```kotlin
fun ratchetEncrypt(plaintext: ByteArray): EncryptedMessage
```

Flow:
1. DH-Ratchet triggered if `sendingMessageIndex >= MAX_CHAIN_LENGTH` (1000 messages)
2. Derive message key from sending chain: `deriveMessageKey(sendingChainKey, messageIndex)`
3. Generate nonce: `generateNonce(messageKey, messageIndex)`
4. Encrypt: `aesGcmEncrypt(plaintext, messageKey, nonce)`
5. Increment message index
6. Wipe message key (forward secrecy)

Returns `EncryptedMessage` with:
- `dhPublicKey`: Current sending DH public key
- `chainIndex`: Current chain index
- `messageIndex`: Current message index
- `nonce`: 12-byte nonce
- `ciphertext`: AES-256-GCM ciphertext

#### Decryption (`ratchetDecrypt`)
**Lines 101-135**

```kotlin
fun ratchetDecrypt(message: EncryptedMessage): ByteArray?
```

Flow:
1. Check if message's DH public key matches current receiving DH key
2. If match: Use symmetric ratchet (no DH-Ratchet needed)
   - Derive message key from receiving chain
   - Decrypt
3. If mismatch: Trigger DH-Ratchet
   - Call `dhRatchetReceive(message.dhPublicKey)`
   - Then decrypt with new receiving chain key
4. Wipe message key

### DH-Ratchet Implementation

#### Send-Side DH-Ratchet
**Lines 144-182**

```kotlin
private fun dhRatchetSend()
```

Steps:
1. Generate new DH KeyPair
2. Calculate shared secret: `DH(newSendingPrivate, receivingPublic)`
3. Derive new root key: `HKDF(sharedSecret, salt=rootKey, info="ROOT")`
4. Derive new sending chain key: `HKDF(rootKey, salt=rootKey, info="CHAIN")`
5. Increment sending chain index
6. Reset message index to 0
7. Wipe old keys

#### Receive-Side DH-Ratchet
**Lines 189-223**

```kotlin
private fun dhRatchetReceive(newDhPublicKey: ByteArray)
```

Steps:
1. Calculate shared secret: `DH(receivingPrivate, newSendingPublic)`
2. Derive new root key: `HKDF(sharedSecret, salt=rootKey, info="ROOT")`
3. Derive new receiving chain key: `HKDF(rootKey, salt=rootKey, info="CHAIN")`
4. Generate new receiving DH KeyPair
5. Increment receiving chain index
6. Reset message index to 0
7. Wipe old keys

### Chain-Key Management (`deriveMessageKey`)
**Lines 235-287**

```kotlin
private fun deriveMessageKey(chainKey: ByteArray, messageIndex: Int): ByteArray
```

Critical Implementation:
- **Uses reference comparison (`===`)** NOT content comparison (`contentEquals()`)
  - Reason: After X3DH, sending and receiving chain keys may have same initial value
  - `contentEquals()` would always match the first chain key
  - This would corrupt the chain-key chain
  - Each chain key is a NEW ByteArray object from HKDF
  
Flow:
1. Derive message key: `HKDF(chainKey, salt=chainKey, info=MESSAGE_INFO)`
2. Hash chain key for next message: `HKDF(chainKey, salt=chainKey, info=NEXT_CHAIN_INFO)`
3. Update session state:
   - If `chainKey === sessionState.sendingChainKey`: update sending chain
   - Else if `chainKey === sessionState.receivingChainKey`: update receiving chain
   - Fallback: Use `contentEquals()` if reference comparison fails
4. Return message key

### Nonce Generation
**Lines 297-312**

```kotlin
private fun generateNonce(messageKey: ByteArray, messageIndex: Int): ByteArray
```

- Converts message index to 4-byte big-endian
- Derives 12-byte nonce: `HKDF(messageKey, salt=indexBytes, info="Nonce")`
- Ensures uniqueness even if message key is reused

### EncryptedMessage Structure
**Lines 350-385**

```kotlin
data class EncryptedMessage(
    val dhPublicKey: ByteArray,      // 32 bytes, sender's DH public
    val chainIndex: Int,              // DH-Ratchet iteration count
    val messageIndex: Int,            // Message index within chain
    val nonce: ByteArray,             // 12 bytes for AES-GCM
    val ciphertext: ByteArray         // AES-256-GCM output
)
```

Serialized as JSON with Base64 encoding.

### SessionState Structure
**Lines 400-454**

```kotlin
data class SessionState(
    var rootKey: ByteArray,                    // 32 bytes, updated after DH-Ratchet
    var sendingChainKey: ByteArray,           // 32 bytes, hashed per message
    var receivingChainKey: ByteArray,         // 32 bytes, hashed per message
    var sendingDhKeyPair: X25519KeyPair,     // Current DH pair for sending
    var receivingDhKeyPair: X25519KeyPair,   // Last known DH pair for receiving
    var sendingChainIndex: Int = 0,          // Count of DH-Ratchets on send side
    var receivingChainIndex: Int = 0,        // Count of DH-Ratchets on receive side
    var sendingMessageIndex: Int = 0,        // Message counter in current send chain
    var receivingMessageIndex: Int = 0       // Message counter in current receive chain
)
```

Serialized as JSON with Base64 encoding for persistence.

---

## 3. HOW ARE SESSIONS STORED AND MANAGED?

### Session Storage
**File**: `E2eeManager.kt`

#### In-Memory Storage
**Lines 66-67**

```kotlin
private val sessions = ConcurrentHashMap<String, DoubleRatchet>()
```

- `ConcurrentHashMap` for thread-safe concurrent access
- Key: `peerId` (normalized fingerprint)
- Value: `DoubleRatchet` instance containing the session state

#### Persistent Storage
**Lines 655-673 (Load)**

```kotlin
private fun loadSessions()
```

- Loads from SharedPreferences key `"e2ee_sessions"`
- Format: JSON array of objects, each containing:
  - `peerId`: String
  - `sessionState`: JSON-serialized `SessionState`
- Rebuilds `DoubleRatchet` instances from saved `SessionState`

**Lines 678-697 (Save)**

```kotlin
private fun saveSessions()
```

- Saves to SharedPreferences after every encrypt/decrypt operation
- Serializes all sessions to JSON array
- Called after:
  - `ratchetEncrypt()` in `encryptMessage()` (Line 421)
  - `ratchetDecrypt()` in `decryptMessage()` (Line 447)
  - New session creation (Line 196, 295, 394)

### Handshake Data Management
**File**: `CrisixApp.kt`, Lines 160

```kotlin
val pendingHandshakes = remember { mutableStateMapOf<String, HandshakeInitData>() }
```

- **Key**: `peerId` (normalized)
- **Value**: `HandshakeInitData` containing:
  - `preKeyBundleJson`: Alice's PreKeyBundle (sent to Bob)
  - `ownEphemeralPrivateKey`: Alice's ephemeral private key (32 bytes)
  - `ownEphemeralPublicKey`: Alice's ephemeral public key (32 bytes)
  - `peerBundle`: Alice's PreKeyBundle object (for later use)

- Stored for waiting for ACK from responder
- Removed when ACK received (Line 474)
- Used to complete handshake (Lines 490-496)

### Session State Tracking
**File**: `CrisixApp.kt`, Lines 155

```kotlin
val e2eeSessions = remember { mutableStateMapOf<String, Boolean>() }
```

- **Key**: `peerId` (normalized)
- **Value**: `true` if session is established and ready for encryption
- Set to `true` when:
  - Handshake completes as initiator (Line 499)
  - Handshake completes as responder (Line 367)
  - ACK received with fallback message (Line 506)

- Checked before:
  - Encrypting message (Line 1228: `if (hasSession)`)
  - Opening chat (Line 973: `if (!hasSession && normChatId != "echo-self")`)

### Initial Session Creation
**File**: `E2eeManager.kt`, Lines 163-204, 220-316, 330-403

Flow:
1. `startSessionAsInitiator()` (Lines 163-204) - DEPRECATED, not used
2. `processHandshakeAsResponder()` (Lines 220-316):
   - Checks if session already exists (Line 234)
   - Returns existing session's PreKeyMessage if already established
   - Otherwise creates new `DoubleRatchet(sessionState)` (Line 293)
   - Saves to `sessions` map (Line 294) and to disk (Line 295)

3. `completeHandshakeAsInitiator()` (Lines 330-403):
   - Creates `DoubleRatchet(sessionState)` (Line 393)
   - Saves to `sessions` map (Line 394) and to disk (Line 395)

---

## 4. WHAT HAPPENS WHEN HANDSHAKE FAILS OR TIMES OUT?

### Handshake Failure Handling

#### Initiator (Alice) Side
**File**: `CrisixApp.kt`, Lines 978-1000

```kotlin
val handshakeData = e2eeManager.createHandshake()
if (handshakeData != null) {
    pendingHandshakes[normChatId] = handshakeData
    transportManager.sendMessage(normChatId, handshakePayload)
        .onSuccess { /* logged */ }
        .onFailure { error ->
            Log.w(TAG, "⚠️ Handshake-Fehler beim Chat-Öffnen: ${error.message}")
        }
} else {
    Log.e(TAG, "❌ Handshake-Erstellung fehlgeschlagen")
}
```

**Behavior on Failure**:
1. `createHandshake()` returns `null` → error logged, user cannot send encrypted messages
2. Transport send fails → logged, handshake remains pending
3. ACK not received within timeout → **NO TIMEOUT MECHANISM** (see section 5)

#### Responder (Bob) Side
**File**: `CrisixApp.kt`, Lines 349-388

```kotlin
val preKeyMessageJson = e2eeManager.handleHandshake(normalizedPeerId, handshakeData, ephemeralKeyB64)
if (preKeyMessageJson != null) {
    e2eeSessions[normalizedPeerId] = true
    val ackPayload = JSONObject().apply {
        put("type", "crisix_e2ee_ack")
        put("data", preKeyMessageJson)
    }
    transportManager.sendMessage(normalizedPeerId, ackPayload)
} else {
    Log.w(TAG, "❌ E2EE-Handshake fehlgeschlagen für ${normalizedPeerId.take(8)}")
}
```

**Behavior on Failure**:
1. `handleHandshake()` returns `null` → error logged, no session established
2. Bob sends no ACK → Alice remains in pending state
3. Bob does NOT retry automatically

#### Handshake Processing Errors
**File**: `E2eeManager.kt`

`processHandshakeAsResponder()` (Lines 220-316):
- Returns `null` if:
  - Session already exists (returns old PreKeyMessage anyway)
  - `validatePreKeyBundle()` fails (Line 255)
  - Ed25519 → X25519 conversion fails (Lines 188-193)
  - DH calculations fail (Lines 197-206)
  - X3DHSession not initialized (Line 249)

`completeHandshakeAsInitiator()` (Lines 330-403):
- Returns `false` if:
  - X3DHSession not initialized (Line 341)
  - `processAsInitiator()` returns `null` (Line 351)

### User Experience on Failure

**Encrypted Message Status**:
- **Before Handshake Complete**: Messages are sent unencrypted or in PENDING state
  - File: `CrisixApp.kt`, Lines 1248-1258
  - User sees: "⏳ Sende Nachricht unverschlüsselt (warte auf Handshake-Completion)..."
  
- **After Handshake Complete**: Messages encrypted automatically
  - File: `CrisixApp.kt`, Line 1237
  - User sees: Encrypted message sent

---

## 5. ARE THERE RETRY MECHANISMS FOR FAILED HANDSHAKES?

### Current Retry Implementation

**VERY LIMITED** — Handshakes are NOT automatically retried.

#### What Exists
**File**: `CrisixApp.kt`, Lines 973-1000

```kotlin
if (!hasSession && normChatId != "echo-self") {
    delay(500) // Kurze Verzögerung, um UI zu aktualisieren
    val handshakeData = e2eeManager.createHandshake()
    if (handshakeData != null) {
        pendingHandshakes[normChatId] = handshakeData
        transportManager.sendMessage(normChatId, handshakePayload)
            .onSuccess { Log.i(TAG, "✅ E2EE-Handshake initiiert...") }
            .onFailure { error -> Log.w(TAG, "⚠️ Handshake-Fehler: ${error.message}") }
    }
}
```

- Handshake is sent ONCE when chat is opened
- If transport sends fails: `onFailure` callback logs error, but does NOT retry
- Handshake is also sent when SENDING A MESSAGE if no session exists
  - File: `CrisixApp.kt`, Lines 1198-1223
  - This creates an implicit retry: **Each message send triggers a new handshake if session missing**

#### What's Missing
1. **NO timeout-based retry**: If Bob doesn't send ACK, Alice doesn't retry after N seconds
2. **NO exponential backoff**: Retries always use same timing
3. **NO max retry count**: Could theoretically spam handshakes
4. **NO user notification**: User doesn't know handshake is pending/failed

#### Code Comments on Retry
**File**: `E2eeManager.kt`, Lines 230-237

```kotlin
// Alice' Retry-Loop sendet den Handshake mehrfach (alle 5s).
// Ohne diesen Guard würde jeder erneute Handshake die bestehende
// Session mit neuen Zufalls-Keys überschreiben → BAD_DECRYPT.
```

- References a "retry loop" but NO actual retry loop exists in code
- The guard (checking for existing session) prevents corruption, but doesn't retry

---

## 6. HOW ARE OLD/EXPIRED KEYS HANDLED?

### Key Wiping (Immediate Deletion)

#### Message Keys
**File**: `DoubleRatchet.kt`

- **After Encryption** (Line 79):
  ```kotlin
  val messageKey = deriveMessageKey(sessionState.sendingChainKey, sendingMessageIndex)
  val nonce = generateNonce(messageKey, sendingMessageIndex)
  val ciphertext = aesGcmEncrypt(plaintext, messageKey, nonce)
  wipeBytes(messageKey)  // ← Deleted immediately
  ```

- **After Decryption** (Lines 113, 128):
  ```kotlin
  val messageKey = deriveMessageKey(sessionState.receivingChainKey, messageIndex)
  val nonce = generateNonce(messageKey, messageIndex)
  val plaintext = aesGcmDecrypt(message.ciphertext, messageKey, nonce)
  wipeBytes(messageKey)  // ← Deleted immediately
  ```

#### Chain Keys
- **NOT explicitly wiped** after being replaced
- Chain keys are continuously updated in `deriveMessageKey()` (Lines 269-282)
- Old chain keys are replaced by new ones generated from HKDF
- Java garbage collection will eventually free old ByteArrays (may take time)
- **SECURITY ISSUE**: Garbage collection is not deterministic; old keys may persist in memory

#### DH Private Keys
**File**: `DoubleRatchet.kt`, Line 179

```kotlin
private fun dhRatchetSend() {
    val previousSendingDh = sessionState.sendingDhKeyPair
    sessionState.sendingDhKeyPair = CryptoHelper.generateX25519KeyPair()
    // ...
    wipeBytes(previousSendingDh.privateKey)  // ← Old private key deleted
}
```

- Previous DH private keys wiped after DH-Ratchet
- Current receiving private key is NOT wiped (needed for future DH-Ratchets)

#### DH Shared Secrets (Temporary)
**File**: `X3DHSession.kt`, Lines 307-312

```kotlin
wipeBytes(dh1)
wipeBytes(dh2)
wipeBytes(dh3)
dh4?.let { wipeBytes(it) }
wipeBytes(sharedSecret)
```

- All intermediate DH shared secrets wiped immediately
- Initial root key kept (needed for chain key derivation)

### OneTime PreKey Deletion
**File**: `X3DHSession.kt`, Lines 219-224

```kotlin
if (peerBundle.oneTimePreKey != null) {
    if (ownOneTimePreKeys.isNotEmpty()) {
        val opk = ownOneTimePreKeys.removeFirst()
        dh4 = CryptoHelper.x25519DH(opk.privateKey, peerEphemeralKey)
        usedOtpkPublic = opk.publicKey.copyOf()
        wipeBytes(opk.privateKey)  // ← OTPk private deleted immediately
        usedOneTimePreKey = true
    }
}
```

- OneTime PreKey private key deleted after use
- OneTime PreKey removed from list (`removeFirst()`)
- Public key returned for Alice to receive

### Session Persistence
**File**: `E2eeManager.kt`, Lines 678-697

```kotlin
private fun saveSessions() {
    val jsonArray = org.json.JSONArray()
    sessions.forEach { (peerId, ratchet) ->
        try {
            val obj = org.json.JSONObject().apply {
                put("peerId", peerId)
                put("sessionState", ratchet.serializeSession())  // ← Full state saved
            }
            jsonArray.put(obj)
        }
    }
    prefs.edit().putString(PREFS_SESSIONS, jsonArray.toString()).apply()
}
```

- **SECURITY ISSUE**: Full session state (including root key, chain keys, DH private keys) is stored in **SharedPreferences** in plaintext
- SharedPreferences is NOT encrypted by default on Android
- No rotation of sessions (old sessions kept forever)
- Sessions only removed when:
  - `closeSession(peerId)` called (Line 549-552)
  - `reset()` called (Line 702-718)
  - Manually deleted by user

### Key Rotation
**Currently NO explicit key rotation implemented**:
- SignedPreKey created on startup, never updated
- OneTime PreKeys generated on demand, but not scheduled refresh
- No background task to rotate keys periodically

---

## 7. IS THERE ANY PFS (PERFECT FORWARD SECRECY) IMPLEMENTATION?

### PFS Implementation

**YES**, through the Double Ratchet protocol:

#### 1. DH-Ratchet (Main PFS Mechanism)
**File**: `DoubleRatchet.kt`, Lines 144-182 (send), 189-223 (receive)

- After every message, a new DH shared secret is calculated
- New keys are derived from this shared secret
- **After 1000 messages** (MAX_CHAIN_LENGTH), the DH-Ratchet is forced
- Even if an attacker compromises the current root key, they cannot decrypt:
  - Previous messages (old chain keys already deleted)
  - Future messages (new root key from new DH shared secret)

#### 2. Symmetric Ratchet (Chain Key Hashing)
**File**: `DoubleRatchet.kt`, Lines 235-287

```kotlin
private fun deriveMessageKey(chainKey: ByteArray, messageIndex: Int): ByteArray {
    val messageKey = HKDF(chainKey, salt=chainKey, info=MESSAGE_INFO)
    val newChainKey = HKDF(chainKey, salt=chainKey, info=NEXT_CHAIN_INFO)
    if (chainKey === sessionState.sendingChainKey) {
        sessionState.sendingChainKey = newChainKey  // ← Old key deleted
    }
    return messageKey
}
```

- Chain key is hashed after each message
- Old chain key is replaced by new one
- **Ratchet-down property**: Cannot derive old chain keys from new one
- All message keys derived from old chain key are lost

#### 3. Message Key Destruction
**File**: `DoubleRatchet.kt`, Lines 79, 113, 128

```kotlin
wipeBytes(messageKey)  // ← Overwritten with zeros
```

- Every message key is destroyed immediately after use
- Even if root key and chain key are compromised, this message is safe

### PFS Coverage

| Attack Scenario | Protected? | Mechanism |
|-----------------|-----------|-----------|
| Root key compromised | ✅ YES | New DH-Ratchet derives new root key |
| Chain key compromised | ✅ YES | Symmetric ratchet hashes chain key |
| Message key compromised | ✅ YES | Message key not derivable from later keys |
| Old messages compromised | ✅ YES | Old keys deleted and not recoverable |
| Future messages compromised | ✅ YES | New DH keys from new shared secret |

### PFS Limitations

1. **DH-Ratchet happens every 1000 messages**, not every message
   - File: `DoubleRatchet.kt`, Line 58
   - Trade-off: Performance vs. security
   - In practice: Still provides PFS for most conversations

2. **Root key stored on disk** (SharedPreferences)
   - If device is compromised, all sessions can be decrypted
   - Use of AndroidKeyStore not implemented for E2EE keys
   - Only Identity-Key and SignedPreKey use AndroidKeyStore

---

## 8. HOW ARE ACKS HANDLED FOR THE HANDSHAKE?

### Handshake ACK Flow

#### Bob Sends ACK (PreKeyMessage)
**File**: `CrisixApp.kt`, Lines 375-379

```kotlin
if (preKeyMessageJson != null) {
    val ackPayload = JSONObject().apply {
        put("type", "crisix_e2ee_ack")
        put("data", preKeyMessageJson)  // ← Contains Bob's PreKeyMessage
    }.toString().toByteArray()
    transportManager.sendMessage(normalizedPeerId, ackPayload)
}
```

- Bob sends the ACK with type `"crisix_e2ee_ack"`
- ACK contains Bob's `PreKeyMessage` (not just empty confirmation)
- PreKeyMessage contains:
  - Bob's ephemeral public key (EK_B)
  - Bob's identity key
  - Bob's SignedPreKey
  - Flag if Bob used his OneTime PreKey
  - Bob's actual OneTime PreKey public (if used)

#### Alice Receives ACK
**File**: `CrisixApp.kt`, Lines 470-508

```kotlin
if (messageType == "crisix_e2ee_ack") {
    Log.i(TAG, "✅ E2EE-ACK empfangen von ${normalizedPeerId.take(8)}")
    
    val pendingData = pendingHandshakes.remove(normalizedPeerId)  // ← Remove pending
    if (pendingData != null) {
        scope.launch(Dispatchers.IO) {
            val preKeyMessageJson = try {
                val ackJson = JSONObject(String(data))
                ackJson.getString("data")
            } catch (_: Exception) {
                // Fallback for old clients
                """{"identityKey":"","ephemeralKey":"","usedOneTimePreKey":false}"""
            }
            
            val success = e2eeManager.completeHandshakeAsInitiator(
                peerId = normalizedPeerId,
                peerBundle = pendingData.peerBundle,
                peerPreKeyMessageJson = preKeyMessageJson,
                ownEphemeralPrivateKey = pendingData.ownEphemeralPrivateKey,
                ownEphemeralPublicKey = pendingData.ownEphemeralPublicKey
            )
            if (success) {
                e2eeSessions[normalizedPeerId] = true  // ← Session ready
            }
        }
    }
}
```

### ACK Validation

**MINIMAL VALIDATION** on ACK:
1. Checks `messageType == "crisix_e2ee_ack"` (Line 470)
2. Tries to parse PreKeyMessage as JSON (Lines 482-488)
3. If parsing fails, uses empty fallback message
4. Calls `completeHandshakeAsInitiator()` with fallback PreKeyMessage

**SECURITY ISSUE**: If parsing fails, Alice uses a FAKE PreKeyMessage with empty keys, which will fail decryption and cause BAD_DECRYPT errors.

### ACK Timeout

**NO TIMEOUT MECHANISM**:
- Alice sends handshake and waits for ACK (indefinitely)
- If Bob never sends ACK:
  - Alice's session remains in `pendingHandshakes`
  - `e2eeSessions[peerId]` remains `false`
  - Each message send triggers a new handshake (implicit retry)
  - No notification to user
  - No automatic cleanup after N seconds/attempts

### ACK Acknowledgment

- Alice does NOT send a confirmation that she received the ACK
- Asymmetric: Bob knows session is complete (he created it), Alice waits for ACK
- If Alice's ACK-receipt is lost, she won't know Bob successfully started the session

### Message Delivery After Handshake
**File**: `CrisixApp.kt`, Lines 1225-1260

```kotlin
val messagePayload = if (hasSession) {
    // Encrypted message
    val plainMessage = JSONObject().apply {
        put("type", "message")
        put("text", text)
        // ...
    }.toString().toByteArray()
    
    val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)
    if (encrypted != null) {
        JSONObject().apply {
            put("type", "crisix_e2ee")
            put("data", encrypted)
        }.toString().toByteArray()
    }
} else {
    // Unencrypted fallback message
    JSONObject().apply {
        put("type", "message")
        put("text", text)
        // ...
    }.toString().toByteArray()
}

transportManager.sendMessage(normChatId, messagePayload, uiMessageId = newMessage.id)
```

**Behavior**:
- After handshake is complete: All messages encrypted
- If session missing: Messages sent unencrypted (SECURITY RISK)
- No indication to user whether message is encrypted or not

---

## Summary: Key Findings

### Strengths
1. ✅ X3DH handshake correctly implemented with all four DH calculations
2. ✅ Double Ratchet provides both Forward Secrecy and Break-in Recovery
3. ✅ Perfect Forward Secrecy through DH-Ratchet mechanism
4. ✅ Message keys destroyed immediately after use
5. ✅ OneTime PreKeys properly managed
6. ✅ Ed25519 ↔ X25519 conversion implemented correctly

### Critical Issues
1. ❌ **NO handshake timeout or retry mechanism** — if ACK is lost, Alice waits forever
2. ❌ **NO AndroidKeyStore for session state** — keys stored plaintext in SharedPreferences
3. ❌ **Minimal ACK validation** — accepts fallback empty PreKeyMessage on parsing error
4. ❌ **Unencrypted fallback** — messages sent plaintext if session missing (no error to user)
5. ❌ **No key rotation** — SignedPreKey and identity keys never refreshed
6. ❌ **No session cleanup** — old sessions never automatically deleted
7. ❌ **Garbage collection risk** — old chain keys not explicitly destroyed (Java GC only)

### Missing Features
1. 🔲 Automatic handshake retry with exponential backoff
2. 🔲 Handshake timeout with user notification
3. 🔲 Encrypted session state storage (AndroidKeyStore)
4. 🔲 Periodic key rotation (SPK, OTPk)
5. 🔲 Session expiration and cleanup
6. 🔲 Per-device session management (multiple devices per fingerprint)
7. 🔲 Out-of-order message handling (skipped message indices)
8. 🔲 Invalid message counter recovery


# Crisix E2EE Architecture Diagrams

## 1. Complete Handshake Flow

```
ALICE (Initiator)                          BOB (Responder)
       |                                         |
       | 1. e2eeManager.createHandshake()
       |    - Generate ephemeral key EK_A
       |    - Create PreKeyBundle (IK_A, SPK_A, OPK_A)
       |
       | 2. Send PreKeyBundle to Bob
       |──────────────────────────────────────>|
       |                   handshakePayload:
       |                   - type: "crisix_e2ee_handshake"
       |                   - data: Bob's PreKeyBundle (JSON)
       |                   - ephemeralKey: EK_A (base64)
       |
       |                                  3. Receive handshake
       |                                  4. processHandshakeAsResponder()
       |                                     - Extract Alice's EK_A
       |                                     - Calculate DH1, DH2, DH3, DH4
       |                                     - Derive Root Key and Chain Keys
       |                                     - Create PreKeyMessage with EK_B
       |                                  5. Generate new DH KeyPair for recv
       |
       |                                  6. Send PreKeyMessage to Alice
       |<───────────────────────────────────────|
       |                   ackPayload:
       |                   - type: "crisix_e2ee_ack"
       |                   - data: Bob's PreKeyMessage (JSON)
       |
       | 7. Receive ACK (PreKeyMessage)
       | 8. completeHandshakeAsInitiator()
       |    - Calculate DH1, DH2, DH3, DH4 (same values as Bob)
       |    - Derive Root Key and Chain Keys
       |    - Set sending/receiving DH keys
       |
       | 9. Mark session as ready
       |    e2eeSessions[peerId] = true
       |
       | ✅ BOTH SIDES NOW HAVE IDENTICAL SESSION STATE
       |    Ready to encrypt/decrypt messages
       |
```

## 2. X3DH DH Calculations

### Alice (Initiator) Perspective

```
INPUTS:
  IK_A_priv, IK_A_pub (Alice's Ed25519 identity key)
  EK_A_priv, EK_A_pub (Alice's X25519 ephemeral key)
  SPK_B_pub (Bob's X25519 signed prekey from PreKeyBundle)
  OPK_B_pub (Bob's X25519 one-time prekey, optional)
  IK_B_pub (Bob's Ed25519 identity key from PreKeyMessage)

CONVERSIONS:
  IK_A_priv → X25519 (via ed2curve) for DH2
  IK_B_pub  → X25519 (via ed2curve) for DH2

DH CALCULATIONS:
  DH1 = DH(IK_A_priv_x25519, SPK_B_pub)  ← Alice identity × Bob's SPK
  DH2 = DH(EK_A_priv, IK_B_pub_x25519)   ← Alice ephemeral × Bob identity
  DH3 = DH(EK_A_priv, SPK_B_pub)         ← Alice ephemeral × Bob's SPK
  DH4 = DH(EK_A_priv, OPK_B_pub)         ← Alice ephemeral × Bob's OTPk (optional)

SHARED SECRET:
  SK = DH1 || DH2 || DH3 || DH4 (or without DH4 if no OTPk)
  
KEY DERIVATION:
  RootKey = HKDF(SK, salt=null, info="Crisix-X3DH-InitialRootKey", len=32)
  SendingChainKey = HKDF(RootKey, salt=RootKey, 
                         info="Crisix-X3DH-InitialSendingChainKey", len=32)
  ReceivingChainKey = HKDF(RootKey, salt=RootKey,
                           info="Crisix-X3DH-InitialReceivingChainKey", len=32)
```

### Bob (Responder) Perspective

```
INPUTS:
  IK_B_priv, IK_B_pub (Bob's Ed25519 identity key)
  SPK_B_priv, SPK_B_pub (Bob's X25519 signed prekey)
  OPK_B_priv, OPK_B_pub (Bob's X25519 one-time prekey, optional)
  IK_A_pub (Alice's Ed25519 identity key from PreKeyBundle)
  EK_A_pub (Alice's X25519 ephemeral key from handshake)

CONVERSIONS:
  IK_B_priv → X25519 (via ed2curve) for DH2
  IK_A_pub  → X25519 (via ed2curve) for DH1

DH CALCULATIONS:
  DH1 = DH(SPK_B_priv, IK_A_pub_x25519)  ← Bob's SPK × Alice identity
  DH2 = DH(IK_B_priv_x25519, EK_A_pub)   ← Bob identity × Alice ephemeral
  DH3 = DH(SPK_B_priv, EK_A_pub)         ← Bob's SPK × Alice ephemeral
  DH4 = DH(OPK_B_priv, EK_A_pub)         ← Bob's OTPk × Alice ephemeral (optional)

SHARED SECRET:
  SK = DH1 || DH2 || DH3 || DH4 (or without DH4 if no OTPk used)
  
  ✅ IDENTICAL TO ALICE'S SK (same 4 DH values, same order)

KEY DERIVATION:
  RootKey = HKDF(SK, salt=null, info="Crisix-X3DH-InitialRootKey", len=32)
  ReceivingChainKey = HKDF(RootKey, salt=RootKey,
                           info="Crisix-X3DH-InitialSendingChainKey", len=32)
  SendingChainKey = HKDF(RootKey, salt=RootKey,
                         info="Crisix-X3DH-InitialReceivingChainKey", len=32)
  
  NOTE: Bob's Receiving = Alice's Sending (opposite directions)
```

## 3. Double Ratchet Message Flow

```
SESSION STATE:
  rootKey (32 bytes)
  sendingChainKey (32 bytes) ─┐
  receivingChainKey (32 bytes)├─ Hash chain (ratchet-down)
  sendingDhKeyPair (X25519)  ─┤
  receivingDhKeyPair (X25519)├─ Diffie-Hellman keys
  sendingChainIndex (int)    ─┤
  receivingChainIndex (int)  ├─ Counters
  sendingMessageIndex (int)  ─┤
  receivingMessageIndex (int)┘

ALICE ENCRYPTS MESSAGE 1:
  1. Check sendingMessageIndex (0) < MAX_CHAIN_LENGTH (1000)
     → No DH-Ratchet needed
  
  2. deriveMessageKey(sendingChainKey, 0):
     messageKey = HKDF(sendingChainKey, salt=sendingChainKey, info=MESSAGE)
     nextChainKey = HKDF(sendingChainKey, salt=sendingChainKey, info=NEXT_CHAIN)
     sessionState.sendingChainKey = nextChainKey  ← OLD KEY LOST
     return messageKey
  
  3. generateNonce(messageKey, 0):
     nonce = HKDF(messageKey, salt=indexBytes(0), info=NONCE)[0:12]
  
  4. ciphertext = AES-256-GCM(plaintext, messageKey, nonce)
  
  5. wipeBytes(messageKey)  ← FORWARD SECRECY: messageKey destroyed
  
  6. EncryptedMessage:
     - dhPublicKey: sendingDhKeyPair.publicKey
     - chainIndex: 0
     - messageIndex: 0
     - nonce: [12 bytes]
     - ciphertext: [encrypted]
  
  7. sendingMessageIndex++ → 1

BOB RECEIVES AND DECRYPTS:
  1. Check if encryptedMessage.dhPublicKey == receivingDhKeyPair.publicKey
     → YES (same key) → Symmetric ratchet (no DH-Ratchet)
  
  2. deriveMessageKey(receivingChainKey, 0):
     messageKey = HKDF(receivingChainKey, salt=receivingChainKey, info=MESSAGE)
     nextChainKey = HKDF(receivingChainKey, salt=receivingChainKey, info=NEXT_CHAIN)
     sessionState.receivingChainKey = nextChainKey  ← OLD KEY LOST
     return messageKey
  
  3. generateNonce(messageKey, 0):
     nonce = HKDF(messageKey, salt=indexBytes(0), info=NONCE)[0:12]
  
  4. plaintext = AES-256-GCM-DECRYPT(ciphertext, messageKey, nonce)
  
  5. wipeBytes(messageKey)  ← FORWARD SECRECY: messageKey destroyed

BOB ENCRYPTS REPLY (Message 1 in his sending chain):
  ✅ SAME PROCESS with Bob's sendingChainKey
  (which was derived from Bob's sendingChainKey in X3DH)

DH-RATCHET TRIGGERED (every 1000 messages):
  
  ALICE SENDS MESSAGE #1000:
  1. Check sendingMessageIndex (999) >= MAX_CHAIN_LENGTH (1000)
     → DH-RATCHET NEEDED
  
  2. dhRatchetSend():
     a. newSendingDh = generateX25519KeyPair()
     b. sharedSecret = DH(newSendingDh.priv, receivingDhKeyPair.pub)
     c. rootKey = HKDF(sharedSecret, salt=oldRootKey, info=ROOT)
     d. sendingChainKey = HKDF(rootKey, salt=rootKey, info=CHAIN)
     e. sendingChainIndex++ → 1
     f. sendingMessageIndex = 0
     g. wipeBytes(oldSendingDh.priv)
  
  3. Continue with new sending chain key
  
  BOB RECEIVES MESSAGE #1000:
  1. Check encryptedMessage.dhPublicKey != receivingDhKeyPair.publicKey
     → MISMATCH → DH-RATCHET NEEDED
  
  2. dhRatchetReceive(message.dhPublicKey):
     a. sharedSecret = DH(receivingDhKeyPair.priv, message.dhPublicKey)
     b. rootKey = HKDF(sharedSecret, salt=oldRootKey, info=ROOT)
     c. receivingChainKey = HKDF(rootKey, salt=rootKey, info=CHAIN)
     d. newReceivingDh = generateX25519KeyPair()
     e. receivingChainIndex++ → 1
     f. receivingMessageIndex = 0
  
  3. Continue with new receiving chain key
  
  ✅ BOTH SIDES NOW SYNCHRONIZED on new DH keys and root key
```

## 4. Session Storage Architecture

```
MEMORY (Fast, Lost on Crash):
┌─────────────────────────────────────────┐
│ E2eeManager.sessions                    │
│ ConcurrentHashMap<peerId, DoubleRatchet>
│                                         │
│ alice@fp1 → DoubleRatchet               │
│            └── SessionState             │
│               ├── rootKey[32]           │
│               ├── sendingChainKey[32]   │
│               ├── receivingChainKey[32] │
│               └── DH keys + counters    │
│                                         │
│ bob@fp2   → DoubleRatchet               │
│            └── SessionState             │
└─────────────────────────────────────────┘

PERSISTENT (Disk, Survives Crash):
┌──────────────────────────────────────────────────┐
│ SharedPreferences ("crisix_e2ee")                │
│                                                  │
│ KEY: "e2ee_sessions"                            │
│ VALUE: [                                        │
│   {                                             │
│     "peerId": "alice@fp1",                      │
│     "sessionState": "{                          │
│       rootKey: base64(...),                     │
│       sendingChainKey: base64(...),             │
│       receivingChainKey: base64(...),           │
│       sendingDhPrivate: base64(...),            │
│       sendingDhPublic: base64(...),             │
│       receivingDhPrivate: base64(...),          │
│       receivingDhPublic: base64(...),           │
│       sendingChainIndex: 0,                     │
│       receivingChainIndex: 0,                   │
│       sendingMessageIndex: 42,                  │
│       receivingMessageIndex: 27                 │
│     }"                                          │
│   },                                            │
│   { ...bob... }                                 │
│ ]                                               │
│                                                  │
│ ⚠️ NOT ENCRYPTED!                               │
│    All keys stored in plaintext                 │
│    Accessible if device is compromised          │
└──────────────────────────────────────────────────┘

HANDSHAKE PENDING (While awaiting ACK):
┌──────────────────────────────────────────────┐
│ CrisixApp.pendingHandshakes                  │
│ MutableStateMap<peerId, HandshakeInitData>   │
│                                              │
│ alice@fp1 → HandshakeInitData                │
│            ├── preKeyBundleJson              │
│            ├── ownEphemeralPrivateKey[32]    │
│            ├── ownEphemeralPublicKey[32]     │
│            └── peerBundle (object)           │
│                                              │
│ ⚠️ MEMORY ONLY (lost if app crashes)         │
│    Blocks until ACK received                 │
└──────────────────────────────────────────────┘

SESSION READY FLAG (UI State):
┌──────────────────────────────────────────┐
│ CrisixApp.e2eeSessions                   │
│ MutableStateMap<peerId, Boolean>         │
│                                          │
│ alice@fp1 → true  (session ready)        │
│ bob@fp2   → false (handshake pending)    │
│                                          │
│ ✅ USED TO:                              │
│    - Decide if message should encrypt   │
│    - Show session status in UI          │
│    - Trigger handshake if missing       │
└──────────────────────────────────────────┘
```

## 5. Message Encryption/Decryption Flow

```
SEND ENCRYPTED MESSAGE:
┌─────────────────────────────────────────────────────┐
│ 1. User types: "Hello Bob"                          │
│    Chat opened: peerId = "bob@fingerprint"         │
│                                                    │
│ 2. Check: e2eeSessions[peerId] == true?            │
│    ✅ YES → Encrypt                               │
│    ❌ NO  → Send plain + initiate handshake       │
│                                                    │
│ 3. plainMessage = JSONObject {                     │
│     "type": "message",                             │
│     "text": "Hello Bob",                           │
│     "sender": "Alice",                             │
│     "timestamp": "14:23"                           │
│    }                                                │
│                                                    │
│ 4. e2eeManager.encryptMessage(peerId,             │
│                  plainMessage.toByteArray())       │
│    ├── ratchet = sessions[peerId]                 │
│    ├── encrypted = ratchet.ratchetEncrypt()       │
│    │   └── (see Double Ratchet section)           │
│    ├── saveSessions()  ← STATE SAVED              │
│    └── return encrypted.toJson()                  │
│                                                    │
│ 5. payload = JSONObject {                         │
│     "type": "crisix_e2ee",                         │
│     "data": encryptedJson                         │
│    }                                                │
│                                                    │
│ 6. transportManager.sendMessage(peerId,           │
│                    payload.toString().toByteArray)│
│    └── Sent over WLAN/Internet/BLE/DNS-Tunnel     │
└─────────────────────────────────────────────────────┘

RECEIVE ENCRYPTED MESSAGE:
┌─────────────────────────────────────────────────────┐
│ 1. TransportManager receives messageBytes           │
│                                                    │
│ 2. CrisixApp.registerMessageListener() triggered   │
│    ├── peerId = extractSender(message)             │
│    ├── messageText = String(messageBytes)          │
│    └── messageType = JSON.optString("type")        │
│                                                    │
│ 3. Check: messageType == "crisix_e2ee"?           │
│    ✅ YES → Decrypt                               │
│    ❌ NO  → Handle as plain message                │
│                                                    │
│ 4. json = JSONObject(messageText)                  │
│    encryptedJson = json.getString("data")         │
│                                                    │
│ 5. plaintext = e2eeManager.decryptMessage(        │
│         peerId, encryptedJson)                    │
│    ├── ratchet = sessions[peerId]                 │
│    ├── encrypted = EncryptedMessage.fromJson()    │
│    ├── plaintext = ratchet.ratchetDecrypt()       │
│    │   └── (see Double Ratchet section)           │
│    ├── saveSessions()  ← STATE SAVED              │
│    └── return plaintext                            │
│                                                    │
│ 6. if (plaintext == null):                        │
│       Log.error("BAD_DECRYPT")                    │
│       return (message lost)                       │
│                                                    │
│ 7. decryptedText = String(plaintext)              │
│    decryptedJson = JSONObject(decryptedText)      │
│    displayText = decryptedJson.getString("text")  │
│                                                    │
│ 8. Create Message object:                         │
│    Message {                                       │
│     id = "incoming-e2ee-$now",                    │
│     text = displayText,                           │
│     isFromMe = false,                             │
│     timestamp = "14:25",                          │
│     status = MessageStatus.DELIVERED,             │
│     isEncrypted = true  ← FLAG SET                │
│    }                                                │
│                                                    │
│ 9. Update UI:                                      │
│    ├── messageRepository.addMessage()              │
│    ├── allMessages[peerId].add(message)            │
│    └── currentMessages = allMessages[peerId]      │
│                                                    │
│ 10. Mark old sent messages as DELIVERED:          │
│     ├── existingMessages.filter(isFromMe && SENT) │
│     ├── status = DELIVERED                        │
│     └── messageRepository.updateStatus()          │
└─────────────────────────────────────────────────────┘
```

## 6. Key Management Lifecycle

```
KEY GENERATION (Startup):
┌─────────────────────────────────────────────────────┐
│ E2eeManager.initialize()                            │
│                                                    │
│ 1. Load Identity-Key (Ed25519):                   │
│    ├── Try: AndroidKeyStore[KEY_ALIAS_IDENTITY]   │
│    └── If missing: Generate new + save            │
│       └── Used for: X3DH signatures, identity     │
│                                                    │
│ 2. Load SignedPreKey (X25519):                    │
│    ├── Try: AndroidKeyStore[KEY_ALIAS_SPK]        │
│    ├── Signature: SharedPreferences[PREFS_SPK_SIG] │
│    └── If missing: Generate new + save            │
│       └── Used for: X3DH handshake (DH1/DH3)      │
│                                                    │
│ 3. Generate OneTimePreKeys (X25519):              │
│    ├── Load from: SharedPreferences[opk_*]        │
│    ├── Generate until: MIN_ONETIME_PREKEYS (3)    │
│    ├── Max capacity: MAX_ONETIME_PREKEYS (10)     │
│    └── Used for: X3DH DH4 (optional, deleted)    │
│                                                    │
│ 4. Initialize X3DHSession:                        │
│    └── Created with IK, SPK, SPK_SIG, OTKs       │
│                                                    │
│ 5. Load existing sessions:                        │
│    └── From: SharedPreferences[e2ee_sessions]     │
│                                                    │
│ ✅ READY: All keys loaded/generated               │
└─────────────────────────────────────────────────────┘

KEY ROTATION (MISSING!):
┌──────────────────────────────────────────────────────┐
│ ❌ NOT IMPLEMENTED                                   │
│                                                     │
│ SHOULD DO:                                          │
│ 1. Rotate SignedPreKey:                           │
│    ├── Every 7 days (or configurable)              │
│    ├── Generate new SPK                            │
│    ├── Sign with Identity-Key                      │
│    ├── Publish to all peers                        │
│    └── Keep old SPK for last 7 days (for lag)     │
│                                                     │
│ 2. Replenish OneTimePreKeys:                       │
│    ├── Every time < MIN_ONETIME_PREKEYS            │
│    ├── Generate until MAX_ONETIME_PREKEYS          │
│    └── Broadcast availability to peers             │
│                                                     │
│ 3. Session Cleanup:                                │
│    ├── Delete sessions older than 90 days          │
│    ├── Or after user explicitly deletes contact   │
│    └── Or on factory reset                         │
└──────────────────────────────────────────────────────┘

MESSAGE-KEY LIFECYCLE (Per Message):
┌──────────────────────────────────────────────────────┐
│ ALICE SENDS MESSAGE:                                 │
│                                                     │
│ 1. deriveMessageKey(sendingChainKey, msgIndex):    │
│    ├── messageKey = HKDF(chainKey, ...)            │
│    ├── nextChainKey = HKDF(chainKey, ...)          │
│    ├── sessionState.sendingChainKey = nextChainKey │
│    ├── ← OLD CHAIN KEY DEREFERENCED (GC'd later)  │
│    └── return messageKey                           │
│                                                     │
│ 2. generateNonce(messageKey, msgIndex):            │
│    └── nonce = HKDF(messageKey, ...)[0:12]         │
│                                                     │
│ 3. ciphertext = AES-GCM-ENCRYPT(plaintext,         │
│                  messageKey, nonce)                 │
│                                                     │
│ 4. wipeBytes(messageKey):                          │
│    └── Overwrite all 32 bytes with 0              │
│    ← FORWARD SECRECY ACHIEVED                     │
│                                                     │
│ 5. Send EncryptedMessage:                          │
│    ├── dhPublicKey: current DH public              │
│    ├── chainIndex: current chain iteration         │
│    ├── messageIndex: index in this chain           │
│    ├── nonce: [12 bytes]                           │
│    └── ciphertext: [variable length]               │
│                                                     │
│ ✅ If attacker later compromises:                 │
│    - Root key? → New keys from DH-Ratchet        │
│    - Chain key? → Cannot derive deleted messageKey │
│    - Ciphertext? → No key available to decrypt    │
└──────────────────────────────────────────────────────┘

DH-KEY LIFECYCLE (Per DH-Ratchet):
┌──────────────────────────────────────────────────────┐
│ AFTER 1000 MESSAGES:                                 │
│                                                     │
│ 1. dhRatchetSend():                                │
│    ├── oldSendingDh = sessionState.sendingDhKeyPair │
│    ├── sessionState.sendingDhKeyPair = new DH()    │
│    ├── sharedSecret = DH(newSending, receivingPub) │
│    ├── rootKey = HKDF(sharedSecret, ...)           │
│    ├── sendingChainKey = HKDF(rootKey, ...)        │
│    ├── wipeBytes(oldSendingDh.privateKey)          │
│    │    ← FORCES NEW EPHEMERAL GENERATION         │
│    └── sendingChainIndex++, msgIndex=0             │
│                                                     │
│ 2. dhRatchetReceive(newDhPublic):                  │
│    ├── sharedSecret = DH(oldReceivingPriv,         │
│    │                      newDhPublic)             │
│    ├── rootKey = HKDF(sharedSecret, ...)           │
│    ├── receivingChainKey = HKDF(rootKey, ...)      │
│    ├── oldReceivingDh = sessionState.receivingDh   │
│    ├── sessionState.receivingDhKeyPair = new DH()  │
│    ├── ← GENERATES NEW RECEIVING KEY PAIR          │
│    └── receivingChainIndex++, msgIndex=0           │
│                                                     │
│ ✅ BOTH SIDES NOW SYNCHRONIZED:                   │
│    - New Root Key (derived from new shared secret) │
│    - New Chain Keys (derived from new root)        │
│    - New DH Keys (for next ratchet)                │
│    - OLD KEYS DELETED                              │
│                                                     │
│ ⚠️ ISSUE: Old receiving DH private key kept      │
│    (needed if messages arrive out-of-order)       │
│    Could be stored longer than necessary          │
└──────────────────────────────────────────────────────┘
```

## 7. Critical Timing & State Diagram

```
PERFECT HANDSHAKE:
────────────────────────────────────────────────────────

Time:  0ms              50ms                200ms
       │                 │                   │
       │ Chat open       │ ACK               │
       │ (Alice)         │ received          │
       │                 │                   │
Alice: PENDING ─────────→ READY ─────────→ ENCRYPTED
       │                 │                   │
       │ Send handshake  │ Complete         │
       │                 │ handshake        │
       ├────────────────→│                   │
       │   (50ms delay)  │                   │
       │                 │                   │
Bob:   IDLE ──────────→ PROCESSING ─────→ READY
       │                 │                   │
       │ Receive         │ Send ACK          │
       │ handshake       │                   │
       │                 ├──────────────────→│
       │                 │ (40ms + trans)    │
       │
PROBLEM: ACK LOST
────────────────────────────────────────────────────────

Time:  0ms              50ms                300ms
       │                 │                   │
Alice: PENDING ─────────→ PENDING ─────────→ PENDING
       │                 │ (waiting forever)│
       │ Handshake sent  │                   │
       ├────────────────→│                   │
       │                 │ ❌ ACK LOST      │
       │                 ├─────X            │
       │                 │                   │
Bob:   IDLE ──────────→ READY              READY
       │ Handshake       │ (ready but       │ (forgotten
       │ received        │ Alice doesn't    │ Alice will
       │ & processed     │ know)            │ never know)
       │
RESULT: Bob encrypted & ready, Alice still pending
        Next message: Alice sends UNENCRYPTED
        Bob receives UNENCRYPTED (unexpected)
        ❌ PROTOCOL BREAKS
        
CURRENT RETRY:
────────────────────────────────────────────────────────

Time:  0ms    500ms    1000ms   1500ms   2000ms
       │       │        │        │        │
Alice: PEND ──→PEND ──→PEND ──→PEND ──→PEND
             (waiting)
       │       │        │        │        │
       │    Message 1   Message 2  Message 3
       │    (unenc.)    (unenc.)   (unenc.)
       │       ├──────→│          │
       │       │      (trigger hs)
       │       │        ├──────→  │
       │       │        │      (trigger hs)
       │       │        │         ├──────→│
       │       │        │         │   (trigger hs)
       │       │        │         │        │
Bob:   (gone) (no hs)  (no hs)  (no hs)  (no hs)
       
RESULT: Infinite retry on every message
        No user notification
        No exponential backoff
        ❌ POOR UX & POTENTIAL SPAM
```

## 8. Current Implementation Status

```
IMPLEMENTED ✅:
├── X3DH Handshake (correct DH calculations)
├── Double Ratchet (encrypt/decrypt)
├── Message key destruction (forward secrecy)
├── DH-Ratchet (every 1000 messages)
├── Chain key hashing (symmetric ratchet)
├── OneTime PreKey management
├── Ed25519 ↔ X25519 conversion
├── Session persistence (SharedPreferences)
├── Handshake initiation
├── ACK reception
└── Session state tracking

PARTIAL/BUGGY ⚠️:
├── Handshake retry (only on message send, no exponential backoff)
├── ACK validation (accepts empty fallback PreKeyMessage)
├── Session fallback (sends unencrypted if no session)
├── Key wiping (garbage collection risk for chain keys)
└── Plaintext SharedPreferences (not encrypted)

MISSING ❌:
├── Handshake timeout
├── Handshake retry with exponential backoff
├── Max retry count
├── User notification of handshake status
├── Session cleanup (sessions kept forever)
├── Key rotation (SPK, OTPk, identity keys)
├── AndroidKeyStore for session state
├── Out-of-order message handling
├── Invalid counter recovery
├── Per-device session management
├── Session expiration
└── Encrypted session storage
```


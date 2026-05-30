# Chat-Encryption: OMEMO für Crisix

## Ziel
Ende-zu-Ende-Verschlüsselung für ALLE Nachrichten (Text, Bilder, Sprachnachrichten) in Crisix. Jeder Transport (WLAN, Internet, BLE, Relay, DNS-Tunnel) überträgt nur noch verschlüsselte Payloads. Der Empfänger entschlüsselt automatisch.

## Architektur-Entscheidung: Double Ratchet (OMEMO-ähnlich)

Statt vollem XMPP-OMEMO (das auf XMPP aufsetzt) implementieren wir ein **Double-Ratchet-Protokoll mit PreKeys**, das an OMEMO/X3DH angelehnt ist:

- **X3DH-Handshake** (Extended Triple Diffie-Hellman) beim ersten Kontakt
- **Double Ratchet** für fortlaufende Konversationen (Forward Secrecy + Break-in Recovery)
- **PreKeys** für asynchrone Erstkontakte (Peer ist offline beim ersten Key-Exchange)
- **Ed25519** (bereits vorhanden) als Identitätsschlüssel
- **X25519** (Curve25519) für Ephemeral-Keys (DH-Ratchet)
- **AES-256-GCM** (bereits in CryptoHelper) für Payload-Verschlüsselung

## Warum Double Ratchet statt einfachem Public-Key?

| Kriterium | Einfaches PKI | Double Ratchet |
|-----------|--------------|----------------|
| Forward Secrecy | ❌ (Key-Kompromittierung → alle alten Nachrichten lesbar) | ✅ (tägliche/ stündliche Key-Rotation) |
| Break-in Recovery | ❌ (einmal kompromittiert → für immer kompromittiert) | ✅ (nach Key-Rotation wieder sicher) |
| Asynchroner Erstkontakt | ❌ (beide müssen online sein) | ✅ (via PreKeys) |
| Komplexität | Niedrig | Mittel |

## Dateien & Änderungen

### Neue Dateien

| Datei | Zweck |
|-------|-------|
| `app/.../crypto/DoubleRatchet.kt` | Double-Ratchet-Implementierung (Ratchet-Step, Key-Derivation, AES-GCM) |
| `app/.../crypto/X3DHHandshake.kt` | X3DH-Handshake (PreKey-Bundle erstellen/verarbeiten, Shared-Secret-Berechnung) |
| `app/.../crypto/SessionManager.kt` | Verwaltet Sessions pro PeerId (Speicherung/Laden, Ratchet-Status, PreKey-Bundle-Cache) |
| `app/.../crypto/EncryptedMessage.kt` | Datenklasse für verschlüsselte Payloads (Header + Ciphertext + Nonce) |
| `app/.../crypto/PreKeyStore.kt` | Speicherung von PreKeys im Android KeyStore + SharedPreferences |

### Bestehende Dateien (Änderungen)

| Datei | Änderung |
|-------|----------|
| `CryptoHelper.kt` | + `generateX25519KeyPair()`, + `x25519DH()`, + `aesGcmEncrypt()`, + `aesGcmDecrypt()` |
| `Transport.kt` | + `encryptPayload()` / `decryptPayload()` als Extension? Oder zentral in TransportManager |
| `TransportManager.kt` | `sendMessage()` ruft vor dem Senden `SessionManager.encrypt()` auf; `registerMessageListener()` ruft nach Empfang `SessionManager.decrypt()` auf |
| `CrisixApp.kt` | Nachrichten-Listener entschlüsselt eingehende Payloads bevor sie verarbeitet werden |
| `Contact.kt` | + `preKeyBundle: String?` (serialisiertes PreKey-Bundle für asynchronen Austausch) |
| `ContactRepository.kt` | PreKey-Bundle in Kontakt speichern/laden |
| `MessageEntity.kt` | + `isEncrypted: Boolean` (für UI-Anzeige) |
| `ChatDetailScreen.kt` | + Schloss-Icon bei verschlüsselten Nachrichten |

## Protokoll-Ablauf

### 1. Erstkontakt (X3DH-Handshake)

```
Alice                              Bob
  |                                  |
  |── PreKeyBundle anfordern ───────>|  (via Transport)
  |<── PreKeyBundle (IKb, SPKb, ────|
  |     OPKb, PreKeySig)             |
  |                                  |
  |  SK = DH(IKa, SPKb) ||          |
  |       DH(EKa, IKb)  ||          |
  |       DH(EKa, SPKb)  ||         |
  |       DH(EKa, OPKb)             |
  |                                  |
  |  RootKey = HKDF(SK)             |
  |  ChainKey = HKDF(RootKey)       |
  |                                  |
  |── InitialMessage (IKa, EKa, ────>|
  |     OPK-ID, Ciphertext)          |
  |                                  |
  |  Bob berechnet gleiches SK       |
  |  Bob initialisiert Ratchet       |
  |                                  |
  |  ✅ Session etabliert            |
```

### 2. Fortlaufende Konversation (Double Ratchet)

```
Alice                              Bob
  |                                  |
  |  msg = ratchetEncrypt(text)      |
  |── Ciphertext ───────────────────>|
  |  Bob: ratchetDecrypt(cipher)     |
  |  Bob: ratchetEncrypt(reply)      |
  |<── Ciphertext ──────────────────|
  |  Alice: ratchetDecrypt(cipher)   |
  |                                  |
  |  ⚡ Nach jeder Nachricht:        |
  |    - DH-Ratchet (neue Keys)      |
  |    - ChainKey wird gehasht       |
  |    - Alte Keys werden gelöscht   |
```

### 3. Payload-Format

```json
{
  "type": "encrypted",
  "version": 1,
  "senderKey": "<base64(Ed25519-PublicKey)>",
  "ephemeralKey": "<base64(Ephemeral-X25519-PublicKey)>",
  "preKeyId": 42,
  "nonce": "<base64(12-Byte-AES-GCM-Nonce)>",
  "ciphertext": "<base64(AES-256-GCM-Ciphertext)>",
  "ratchetHeader": {
    "dhPair": "<base64(DH-Ratchet-PublicKey)>",
    "chainIndex": 7,
    "messageIndex": 3
  }
}
```

## Implementierungs-Reihenfolge

### Phase 1: Crypto-Grundlagen (CryptoHelper erweitern)

- [ ] `generateX25519KeyPair()` — X25519-Schlüsselpaar generieren (Bouncy Castle)
- [ ] `x25519DH(privateKey, publicKey)` — Diffie-Hellman-Schlüsselaustausch
- [ ] `aesGcmEncrypt(plaintext, key, nonce)` — AES-256-GCM-Verschlüsselung
- [ ] `aesGcmDecrypt(ciphertext, key, nonce)` — AES-256-GCM-Entschlüsselung
- [ ] `hkdfDerive(inputKeyMaterial, salt, info, length)` — HKDF-Key-Derivation

### Phase 2: Double Ratchet

- [ ] `DoubleRatchet.kt` — Ratchet-Step (DH + Key-Derivation + AES-GCM)
- [ ] `ratchetEncrypt(plaintext, sessionState)` → Ciphertext + Header
- [ ] `ratchetDecrypt(ciphertext, header, sessionState)` → Plaintext
- [ ] Session-State: RootKey, ChainKey, DH-Keys, Message-Index

### Phase 3: X3DH-Handshake

- [ ] `X3DHHandshake.kt` — PreKey-Bundle erstellen/parsen
- [ ] `createPreKeyBundle(identityKey, signedPreKey, oneTimePreKeys)` → Bundle
- [ ] `processPreKeyBundle(bundle, ownIdentityKey)` → SharedSecret
- [ ] `initiateSession(peerId)` → Handshake-Nachricht senden
- [ ] `acceptSession(peerId, handshakeMessage)` → Session etablieren

### Phase 4: Session-Manager

- [ ] `SessionManager.kt` — Sessions speichern/laden (SharedPreferences + JSON)
- [ ] `getOrCreateSession(peerId)` → Session (laden oder Handshake starten)
- [ ] `encrypt(peerId, plaintext)` → Ciphertext (mit Auto-Handshake)
- [ ] `decrypt(peerId, ciphertext)` → Plaintext (mit Auto-Handshake)
- [ ] PreKey-Bundle-Cache für asynchrone Erstkontakte

### Phase 5: Integration in TransportManager

- [ ] `sendMessage()` ruft `SessionManager.encrypt()` vor dem Senden auf
- [ ] `registerMessageListener()` ruft `SessionManager.decrypt()` nach Empfang auf
- [ ] Handshake-Nachrichten (`type: "crisix_handshake"`) werden vor der normalen Verarbeitung abgefangen
- [ ] Fallback: Wenn kein Session-Key existiert → automatischer Handshake

### Phase 6: UI-Integration

- [ ] Schloss-Icon (🔒) in `MessageBubble` für verschlüsselte Nachrichten
- [ ] `Message.isEncrypted`-Flag in der Datenklasse
- [ ] `MessageEntity.isEncrypted` in Room-Datenbank
- [ ] Status-Anzeige: "🔒 Ende-zu-Ende-verschlüsselt" in der Chat-TopBar

## Sicherheitshinweise

1. **Ed25519 ≠ X25519**: Ed25519 ist für Signaturen, X25519 für DH-Key-Agreement. Wir brauchen BEIDE.
   - Ed25519: Identität (Fingerprint), Signatur von PreKeys
   - X25519: Ephemeral DH für Ratchet
2. **Key-Verification**: Der Fingerprint-Vergleich (QR-Code) verifiziert den Ed25519-Identity-Key. Erst dann ist die E2E-Verschlüsselung sicher gegen MITM.
3. **PreKey-Rotation**: Einmal-PreKeys werden nach Verwendung gelöscht. Signed-PreKeys werden täglich rotiert.
4. **Forward Secrecy**: Nach jeder Nachricht werden die alten Chain-Keys gelöscht. Selbst bei Key-Kompromittierung sind alte Nachrichten sicher.
5. **Kein Trust-on-First-Use (TOFU)**: Der erste Handshake wird via QR-Code (Fingerprint-Vergleich) verifiziert. Ohne QR-Code: TOFU mit Warnhinweis.

## Abhängigkeiten

- **Bouncy Castle** (bereits vorhanden: `bcprov-jdk15to18:1.77`)
  - Wird für X25519 benötigt (`org.bouncycastle.math.ec.rfc7748.X25519`)
  - Wird für HKDF benötigt (`org.bouncycastle.crypto.generators.HKDFBytesGenerator`)
- **Keine neuen Dependencies** — alles mit vorhandenen Libraries realisierbar

## Test-Strategie

1. Unit-Tests für `CryptoHelper` (X25519, AES-GCM, HKDF)
2. Unit-Tests für `DoubleRatchet` (Ratchet-Step, Forward Secrecy)
3. Unit-Tests für `X3DHHandshake` (Shared-Secret-Berechnung)
4. Integration-Test: Alice → Bob → Alice (Roundtrip mit Verschlüsselung)
5. Integration-Test: Asynchroner Erstkontakt (Bob offline → PreKey-Bundle → Bob online)

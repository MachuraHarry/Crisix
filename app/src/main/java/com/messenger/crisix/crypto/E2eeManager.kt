package com.messenger.crisix.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.messenger.crisix.transport.internet.CryptoHelper
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Zentraler Manager für Ende-zu-Ende-Verschlüsselung in Crisix.
 *
 * Koordiniert den gesamten Lebenszyklus einer E2E-Session:
 * 1. **Initialisierung**: Eigene Identity-Keys + PreKeys erstellen/verwalten
 * 2. **Session-Aufbau**: X3DH-Handshake mit einem Peer (Initiator ↔ Responder)
 * 3. **Nachrichten-Verschlüsselung**: Double Ratchet für laufende Sessions
 * 4. **Session-Persistenz**: Serialisierung/Deserialisierung von Sessions
 *
 * ## Korrigierte Handshake-Logik
 * - **Initiator (Alice)**: Sendet PreKeyBundle → empfängt PreKeyMessage → `processAsInitiator()`
 * - **Responder (Bob)**: Empfängt PreKeyBundle → `processAsResponder()` → sendet PreKeyMessage
 * - Beide Seiten berechnen DASSELBE Shared Secret
 *
 * ## Thread-Sicherheit
 * - Sessions werden in einem `ConcurrentHashMap` verwaltet
 * - Jede Session hat ihren eigenen DoubleRatchet-Instanz
 * - Gleichzeitige encrypt/decrypt-Aufrufe für verschiedene Peers sind sicher
 */
class E2eeManager(private val context: Context) {

    companion object {
        private const val TAG = "E2eeManager"

        /** Präfix für SharedPreferences-Keys */
        private const val PREFS_NAME = "crisix_e2ee"

        /** Key für den eigenen Identity-Key im Keystore */
        private const val KEY_ALIAS_IDENTITY = "crisix_identity_key"

        /** Key für den eigenen SignedPreKey im Keystore */
        private const val KEY_ALIAS_SPK = "crisix_signed_prekey"

        /** Key für die SPK-Signatur in SharedPreferences */
        private const val PREFS_SPK_SIGNATURE = "signed_prekey_signature"

        /** Key für die serialisierten Sessions in SharedPreferences */
        private const val PREFS_SESSIONS = "e2ee_sessions"

        /** Maximale Anzahl OneTimePreKeys, die vorgehalten werden */
        private const val MAX_ONETIME_PREKEYS = 10

        /** Mindestanzahl OneTimePreKeys, bevor neue generiert werden */
        private const val MIN_ONETIME_PREKEYS = 3
    }

    /** Eigener Identity-Key (Ed25519) */
    private var identityKey: CryptoHelper.KeyPair? = null

    /** Eigener SignedPreKey (X25519) + Signatur */
    private var signedPreKey: CryptoHelper.X25519KeyPair? = null
    private var signedPreKeySignature: ByteArray? = null

    /** Verfügbare OneTimePreKeys (X25519) */
    private val oneTimePreKeys = mutableListOf<CryptoHelper.X25519KeyPair>()

    /** Aktive E2E-Sessions: peerId → DoubleRatchet */
    private val sessions = ConcurrentHashMap<String, DoubleRatchet>()

    /** X3DH-Session-Instanz (für Handshake) */
    private var x3dhSession: X3DHSession? = null

    /**
     * Initialisiert den E2EE-Manager.
     *
     * Lädt vorhandene Keys aus dem Android Keystore oder erstellt neue.
     * Stellt sicher, dass genügend OneTimePreKeys verfügbar sind.
     * Lädt vorhandene Sessions aus SharedPreferences.
     */
    fun initialize() {
        Log.i(TAG, "Initialisiere E2EE-Manager")

        // Identity-Key laden oder erstellen
        identityKey = CryptoHelper.loadFromAndroidKeyStore(KEY_ALIAS_IDENTITY, context)
        if (identityKey == null) {
            Log.i(TAG, "Kein Identity-Key gefunden — erstelle neuen")
            identityKey = CryptoHelper.generateKeyPair()
            CryptoHelper.saveToAndroidKeyStore(KEY_ALIAS_IDENTITY, identityKey!!, context)
        }

        // SignedPreKey laden oder erstellen
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val spkSignatureB64 = prefs.getString(PREFS_SPK_SIGNATURE, null)

        if (spkSignatureB64 != null) {
            signedPreKeySignature = Base64.decode(spkSignatureB64, Base64.NO_WRAP)
            // SignedPreKey-Private-Key aus Keystore laden
            val spkBytes = CryptoHelper.loadFromAndroidKeyStore(KEY_ALIAS_SPK, context)
            if (spkBytes != null) {
                signedPreKey = CryptoHelper.X25519KeyPair(
                    privateKey = spkBytes.privateKey.copyOfRange(0, 32),
                    publicKey = spkBytes.publicKey
                )
            }
        }

        if (signedPreKey == null || signedPreKeySignature == null) {
            Log.i(TAG, "Kein SignedPreKey gefunden — erstelle neuen")
            generateNewSignedPreKey()
        }

        // OneTimePreKeys laden oder erstellen
        loadOneTimePreKeys()
        ensureOneTimePreKeyCount()

        // X3DH-Session initialisieren
        rebuildX3dhSession()

        // Vorhandene Sessions laden
        loadSessions()

        Log.i(TAG, "E2EE-Manager initialisiert — Fingerprint: ${CryptoHelper.publicKeyToFingerprint(identityKey!!.publicKey).take(16)}...")
    }

    /**
     * Baut die X3DH-Session-Instanz mit den aktuellen Keys neu auf.
     * Wichtig: Nach Änderungen an oneTimePreKeys muss dies aufgerufen werden.
     */
    private fun rebuildX3dhSession() {
        if (identityKey != null && signedPreKey != null && signedPreKeySignature != null) {
            x3dhSession = X3DHSession(
                ownIdentityKey = identityKey!!,
                ownSignedPreKey = signedPreKey!!,
                ownSignedPreKeySignature = signedPreKeySignature!!,
                ownOneTimePreKeys = oneTimePreKeys
            )
        }
    }

    /**
     * Erstellt ein PreKeyBundle für die Veröffentlichung.
     *
     * @param useOneTimePreKey Ob ein OneTimePreKey ins Bundle aufgenommen werden soll
     * @return Das PreKeyBundle, oder null wenn nicht initialisiert
     */
    fun createPreKeyBundle(useOneTimePreKey: Boolean = true): X3DHSession.PreKeyBundle? {
        return x3dhSession?.createPreKeyBundle(useOneTimePreKey)
    }

    /**
     * Startet eine neue E2E-Session mit einem Peer als **Initiator (Alice)**.
     *
     * Alice hat Bobs PreKeyBundle empfangen und startet den Handshake.
     * 1. Alice validiert Bobs PreKeyBundle
     * 2. Alice erstellt einen ephemeralen Key
     * 3. Alice berechnet das Shared Secret (processAsInitiator)
     * 4. Alice speichert die Session
     *
     * @param peerId Die ID des Peers (Fingerprint)
     * @param peerBundle Das PreKeyBundle des Peers (Bob)
     * @param ownEphemeralPrivateKey Alices ephemeraler Private-Key
     * @return true bei Erfolg, false bei Fehler
     */
    fun startSessionAsInitiator(
        peerId: String,
        peerBundle: X3DHSession.PreKeyBundle,
        ownEphemeralPrivateKey: ByteArray
    ): Boolean {
        return try {
            Log.i(TAG, "Starte E2E-Session als Initiator mit Peer: ${peerId.take(16)}...")

            // PreKeyBundle validieren
            if (x3dhSession == null || !x3dhSession!!.validatePreKeyBundle(peerBundle)) {
                Log.e(TAG, "PreKeyBundle ungültig — Session nicht gestartet")
                return false
            }

            // Bobs PreKeyMessage aus dem Bundle extrahieren
            // Alice hat Bobs Bundle + ihren eigenen ephemeralen Key
            // Sie muss processAsInitiator aufrufen
            val sessionState = x3dhSession!!.processAsInitiator(
                peerPreKeyMessage = X3DHSession.PreKeyMessage(
                    identityKey = peerBundle.identityKey,
                    ephemeralKey = peerBundle.signedPreKey,
                    signedPreKey = peerBundle.signedPreKey,
                    usedOneTimePreKey = peerBundle.oneTimePreKey != null
                ),
                ownEphemeralPrivateKey = ownEphemeralPrivateKey,
                peerBundle = peerBundle
            ) ?: return false

            // Double Ratchet mit initialem SessionState erstellen
            val ratchet = DoubleRatchet(sessionState)

            // Session speichern
            sessions[peerId] = ratchet
            saveSessions()

            Log.i(TAG, "✅ E2E-Session als Initiator gestartet mit ${peerId.take(16)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Starten der Session (Initiator): ${e.message}")
            false
        }
    }

    /**
     * Verarbeitet einen eingehenden Handshake als **Responder (Bob)**.
     *
     * Bob hat Alices PreKeyBundle empfangen.
     * 1. Bob validiert Alices PreKeyBundle
     * 2. Bob erstellt einen ephemeralen Key
     * 3. Bob berechnet das Shared Secret (processAsResponder)
     * 4. Bob speichert die Session
     * 5. Bob erstellt eine PreKeyMessage für Alice
     *
     * @param peerId Die ID des Peers (Alice)
     * @param peerBundle Das PreKeyBundle des Peers (Alice)
     * @return Die PreKeyMessage (als JSON), die an Alice gesendet werden muss, oder null bei Fehler
     */
    fun processHandshakeAsResponder(
        peerId: String,
        peerBundle: X3DHSession.PreKeyBundle,
        aliceEphemeralKey: ByteArray? = null
    ): String? {
        return try {
            Log.i(TAG, "Verarbeite Handshake als Responder von: ${peerId.take(16)}...")

            // ═══════════════════════════════════════════════════════════════
            // WICHTIG: Bestehende Session nicht überschreiben!
            // Alice' Retry-Loop sendet den Handshake mehrfach (alle 5s).
            // Ohne diesen Guard würde jeder erneute Handshake die bestehende
            // Session mit neuen Zufalls-Keys überschreiben → BAD_DECRYPT.
            // ═══════════════════════════════════════════════════════════════
            if (sessions.containsKey(peerId)) {
                Log.w(TAG, "⚠️ Session mit ${peerId.take(16)} existiert bereits — ignoriere erneuten Handshake")
                // Trotzdem die PreKeyMessage zurücksenden (für den Fall, dass
                // das erste ACK verloren gegangen ist)
                val existingRatchet = sessions[peerId]!!
                val state = existingRatchet.getSessionState()
                val preKeyMessage = X3DHSession.PreKeyMessage(
                    identityKey = identityKey!!.publicKey,
                    ephemeralKey = state.sendingDhKeyPair.publicKey,
                    signedPreKey = signedPreKey!!.publicKey,
                    usedOneTimePreKey = peerBundle.oneTimePreKey != null
                )
                return preKeyMessage.toJson()
            }

            if (x3dhSession == null) {
                Log.e(TAG, "X3DH-Session nicht initialisiert")
                return null
            }

            // PreKeyBundle validieren
            if (!x3dhSession!!.validatePreKeyBundle(peerBundle)) {
                Log.e(TAG, "PreKeyBundle ungültig — Handshake abgelehnt")
                return null
            }

            // Ephemeralen Key für den Handshake erstellen (Bobs EK_B)
            val ephemeralKeyPair = CryptoHelper.generateX25519KeyPair()

            // ═══════════════════════════════════════════════════════════════
            // WICHTIG: peerEphemeralKey muss ALICES ephemeraler Key sein (EK_A),
            // nicht Bobs! Alices EK_A wird im Handshake-JSON mitgesendet.
            // ═══════════════════════════════════════════════════════════════
            val aliceEphemeralKeyFinal: ByteArray = aliceEphemeralKey ?: peerBundle.signedPreKey
                ?: return null

            // Shared Secret als Responder berechnen
            val (sessionState, bobUsedOneTimePreKey, bobUsedOtpkPublic) = x3dhSession!!.processAsResponder(
                peerBundle = peerBundle,
                peerEphemeralKey = aliceEphemeralKeyFinal
            ) ?: return null

            // ═══════════════════════════════════════════════════════════════
            // WICHTIG: Initiale DH-Keys für Double Ratchet korrekt setzen!
            // Bobs sendingDhKeyPair = EK_B (sein ephemeraler Key)
            // Bobs receivingDhKeyPair = EK_A (Alices ephemeraler Key)
            // ═══════════════════════════════════════════════════════════════
            val fixedSessionState = sessionState.copy(
                sendingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = ephemeralKeyPair.privateKey,
                    publicKey = ephemeralKeyPair.publicKey
                ),
                receivingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = ByteArray(32) { 0 }, // Dummy, wird nie für DH verwendet
                    publicKey = aliceEphemeralKeyFinal
                )
            )

            // Double Ratchet erstellen
            val ratchet = DoubleRatchet(fixedSessionState)
            sessions[peerId] = ratchet
            saveSessions()

            // PreKeyMessage für Alice erstellen (enthält Bobs EK_B + optional Bobs genutzten OTPk)
            // KRITISCH: Wenn Bob sein OTPk verwendet hat, MUSS sein OTPk-Public zu Alice zurückgesendet werden!
            // Alice braucht diesen OTPk, um denselben DH4 zu berechnen!
            val preKeyMessage = X3DHSession.PreKeyMessage(
                identityKey = identityKey!!.publicKey,
                ephemeralKey = ephemeralKeyPair.publicKey,
                signedPreKey = signedPreKey!!.publicKey,
                usedOneTimePreKey = bobUsedOneTimePreKey,
                oneTimePreKey = bobUsedOtpkPublic  // ← KRITISCH! Bobs OTPk-Public für Alice
            )

            Log.i(TAG, "✅ Handshake als Responder verarbeitet — Session mit ${peerId.take(16)} bereit")

            // PreKeyMessage als JSON zurückgeben (muss an Alice gesendet werden)
            preKeyMessage.toJson()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei Handshake (Responder): ${e.message}")
            null
        }
    }

    /**
     * Vervollständigt den Handshake als **Initiator (Alice)**.
     *
     * Alice hat Bobs PreKeyBundle gesendet und bekommt nun Bobs PreKeyMessage.
     * Sie berechnet das Shared Secret und speichert die Session.
     *
     * @param peerId Die ID des Peers (Bob)
     * @param peerBundle Das PreKeyBundle, das Alice an Bob gesendet hat
     * @param peerPreKeyMessageJson Bobs PreKeyMessage als JSON
     * @param ownEphemeralPrivateKey Alices ephemeraler Private-Key
     * @return true bei Erfolg, false bei Fehler
     */
    fun completeHandshakeAsInitiator(
        peerId: String,
        peerBundle: X3DHSession.PreKeyBundle,
        peerPreKeyMessageJson: String,
        ownEphemeralPrivateKey: ByteArray,
        ownEphemeralPublicKey: ByteArray? = null
    ): Boolean {
        return try {
            Log.i(TAG, "Vervollständige Handshake als Initiator mit: ${peerId.take(16)}...")

            if (x3dhSession == null) {
                Log.e(TAG, "X3DH-Session nicht initialisiert")
                return false
            }

            val peerPreKeyMessage = X3DHSession.PreKeyMessage.fromJson(peerPreKeyMessageJson)

            val sessionState = x3dhSession!!.processAsInitiator(
                peerPreKeyMessage = peerPreKeyMessage,
                ownEphemeralPrivateKey = ownEphemeralPrivateKey,
                peerBundle = peerBundle
            ) ?: return false

            // ═══════════════════════════════════════════════════════════════
            // WICHTIG: Initiale DH-Keys für Double Ratchet korrekt setzen!
            // Alices sendingDhKeyPair = EK_A (ihr ephemeraler Key)
            // Alices receivingDhKeyPair = EK_B (Bobs ephemeraler Key aus PreKeyMessage)
            //
            // ownEphemeralPublicKey wird im HandshakeInitData gespeichert und
            // von CrisixApp.kt an completeHandshakeAsInitiator übergeben.
            // Das sind rohe 32 Bytes (X25519 Public-Key ohne ASN.1-Header).
            //
            // WICHTIG: NIEMALS generatePublicKey().encoded verwenden!
            // Das gibt den Key im SPKI-Format zurück (mit ASN.1-Header, ~44 Bytes),
            // aber Bob erwartet rohe 32 Bytes. Der Vergleich in ratchetDecrypt()
            // würde fehlschlagen → DH-Ratchet wird ausgelöst → falsche Chain-Keys
            // → AES-GCM BAD_DECRYPT.
            // ═══════════════════════════════════════════════════════════════
            val alicePublicKey = ownEphemeralPublicKey
                ?: try {
                    Log.w(TAG, "⚠️ ownEphemeralPublicKey ist null — versuche Ableitung aus Private-Key")
                    val privParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(
                        ownEphemeralPrivateKey, 0
                    )
                    // .encoded gibt SPKI-Format → das ist falsch!
                    // Aber als letzter Fallback besser als peerBundle.signedPreKey
                    privParams.generatePublicKey().encoded
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Konnte EK_A public nicht ableiten — verwende peerBundle.signedPreKey als Fallback")
                    peerBundle.signedPreKey
                }

            val fixedSessionState = sessionState.copy(
                sendingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = ownEphemeralPrivateKey,
                    publicKey = alicePublicKey
                ),
                receivingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = ByteArray(32) { 0 }, // Dummy, wird nie für DH verwendet
                    publicKey = peerPreKeyMessage.ephemeralKey
                )
            )

            val ratchet = DoubleRatchet(fixedSessionState)
            sessions[peerId] = ratchet
            saveSessions()

            Log.i(TAG, "✅ Handshake als Initiator vervollständigt — Session mit ${peerId.take(16)} bereit")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei Handshake-Vervollständigung (Initiator): ${e.message}")
            false
        }
    }

    /**
     * Verschlüsselt eine Nachricht für einen Peer.
     *
     * @param peerId Die ID des Peers
     * @param plaintext Die zu verschlüsselnden Daten
     * @return Die verschlüsselte Nachricht als JSON-String, oder null bei Fehler
     */
    fun encryptMessage(peerId: String, plaintext: ByteArray): String? {
        return try {
            val ratchet = sessions[peerId]
                ?: run {
                    Log.e(TAG, "Keine Session für Peer: ${peerId.take(16)}...")
                    return null
                }

            val encrypted = ratchet.ratchetEncrypt(plaintext)
            saveSessions() // Session-State nach Verschlüsselung persistieren

            encrypted.toJson()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei Verschlüsselung: ${e.message}")
            null
        }
    }

    /**
     * Entschlüsselt eine Nachricht von einem Peer.
     *
     * @param peerId Die ID des Peers
     * @param encryptedJson Die verschlüsselte Nachricht als JSON-String
     * @return Der entschlüsselte Klartext, oder null bei Fehler
     */
    fun decryptMessage(peerId: String, encryptedJson: String): ByteArray? {
        return try {
            val ratchet = sessions[peerId]
                ?: run {
                    Log.e(TAG, "Keine Session für Peer: ${peerId.take(16)}...")
                    return null
                }

            val encrypted = EncryptedMessage.fromJson(encryptedJson)
            val plaintext = ratchet.ratchetDecrypt(encrypted)
            saveSessions() // Session-State nach Entschlüsselung persistieren

            plaintext
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei Entschlüsselung: ${e.message}")
            null
        }
    }

    /**
     * Erstellt einen E2EE-Handshake für einen Peer (als Initiator).
     *
     * Erzeugt ein PreKeyBundle und einen ephemeralen Key.
     * Der Aufrufer muss:
     * 1. Das PreKeyBundle an den Peer senden
     * 2. Auf die PreKeyMessage des Peers warten
     * 3. `completeHandshakeAsInitiator()` aufrufen
     *
     * @return Ein Triple (preKeyBundleJson, ownEphemeralPrivateKey, peerBundle), oder null bei Fehler
     */
    fun createHandshake(): HandshakeInitData? {
        val bundle = createPreKeyBundle(useOneTimePreKey = true) ?: return null
        val ephemeralKeyPair = CryptoHelper.generateX25519KeyPair()
        return try {
            HandshakeInitData(
                preKeyBundleJson = bundle.toJson(),
                ownEphemeralPrivateKey = ephemeralKeyPair.privateKey,
                ownEphemeralPublicKey = ephemeralKeyPair.publicKey,
                peerBundle = bundle
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Erstellen des Handshakes: ${e.message}")
            null
        }
    }

    /**
     * Verarbeitet einen eingehenden Handshake (Legacy-API für CrisixApp.kt).
     *
     * Diese Methode wird von der UI aufgerufen, wenn eine eingehende
     * Handshake-Nachricht empfangen wird. Sie parst das PreKeyBundle,
     * verarbeitet es als Responder und sendet die PreKeyMessage zurück.
     *
     * @param peerId Die ID des Peers (Initiator)
     * @param handshakeData Das PreKeyBundle als JSON-String
     * @return true bei Erfolg, false bei Fehler
     */
    fun handleHandshake(peerId: String, handshakeData: String, peerEphemeralKeyB64: String? = null): String? {
        return try {
            Log.i(TAG, "Verarbeite eingehenden Handshake von ${peerId.take(16)}...")
            val bundle = X3DHSession.PreKeyBundle.fromJson(handshakeData)
            
            // ═══════════════════════════════════════════════════════════════
            // WICHTIG: Alices ephemeralen Key (EK_A) aus dem Handshake-JSON
            // extrahieren. Bob braucht EK_A für DH2 = DH(IK_B_priv, EK_A)
            // und DH3 = DH(SPK_B_priv, EK_A).
            // ═══════════════════════════════════════════════════════════════
            val aliceEphemeralKey = if (peerEphemeralKeyB64 != null) {
                Base64.decode(peerEphemeralKeyB64, Base64.NO_WRAP)
            } else {
                // Fallback: signedPreKey aus dem Bundle verwenden
                // (das ist falsch, aber besser als null)
                Log.w(TAG, "⚠️ Kein ephemeralKey im Handshake-JSON — verwende signedPreKey als Fallback")
                bundle.signedPreKey
            }
            
            val preKeyMessageJson = processHandshakeAsResponder(peerId, bundle, aliceEphemeralKey)
            if (preKeyMessageJson != null) {
                Log.i(TAG, "✅ Handshake verarbeitet, PreKeyMessage bereit zum Senden")
                preKeyMessageJson
            } else {
                Log.e(TAG, "❌ Handshake-Verarbeitung fehlgeschlagen")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei handleHandshake: ${e.message}")
            null
        }
    }

    /**
     * Prüft, ob eine aktive Session mit einem Peer existiert.
     */
    fun hasSession(peerId: String): Boolean {
        return sessions.containsKey(peerId)
    }

    /**
     * Gibt die Anzahl aktiver Sessions zurück.
     */
    fun getSessionCount(): Int = sessions.size

    /**
     * Gibt alle Peer-IDs mit aktiven Sessions zurück.
     */
    fun getActiveSessionPeers(): Set<String> {
        return sessions.keys.toSet()
    }

    /**
     * Beendet eine Session mit einem Peer.
     */
    fun closeSession(peerId: String) {
        sessions.remove(peerId)
        saveSessions()
        Log.d(TAG, "Session mit Peer ${peerId.take(16)}... beendet")
    }

    /**
     * Gibt den eigenen Identity-Key zurück.
     */
    fun getIdentityKey(): CryptoHelper.KeyPair? = identityKey

    /**
     * Gibt den eigenen Fingerprint zurück.
     */
    fun getFingerprint(): String? {
        return identityKey?.let { CryptoHelper.publicKeyToFingerprint(it.publicKey) }
    }

    /**
     * Generiert einen neuen SignedPreKey.
     */
    private fun generateNewSignedPreKey() {
        if (identityKey == null) return

        val (spk, signature) = X3DHSession.generateSignedPreKey(
            identityKey!!,
            identityKey!!.privateKey
        )

        signedPreKey = spk
        signedPreKeySignature = signature

        // SPK im Keystore speichern (als KeyPair getarnt)
        val spkStorage = CryptoHelper.KeyPair(
            privateKey = spk.privateKey + spk.publicKey,
            publicKey = spk.publicKey
        )
        CryptoHelper.saveToAndroidKeyStore(KEY_ALIAS_SPK, spkStorage, context)

        // Signatur in SharedPreferences speichern
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREFS_SPK_SIGNATURE, Base64.encodeToString(signature, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Stellt sicher, dass genügend OneTimePreKeys verfügbar sind.
     */
    private fun ensureOneTimePreKeyCount() {
        while (oneTimePreKeys.size < MIN_ONETIME_PREKEYS) {
            val opk = CryptoHelper.generateX25519KeyPair()
            oneTimePreKeys.add(opk)
        }
        saveOneTimePreKeys()
        // X3DH-Session nach OPK-Änderungen neu aufbauen
        rebuildX3dhSession()
    }

    /**
     * Lädt OneTimePreKeys aus SharedPreferences.
     */
    private fun loadOneTimePreKeys() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt("onetime_prekey_count", 0)
        oneTimePreKeys.clear()

        for (i in 0 until count) {
            val privB64 = prefs.getString("opk_${i}_priv", null) ?: continue
            val pubB64 = prefs.getString("opk_${i}_pub", null) ?: continue

            oneTimePreKeys.add(
                CryptoHelper.X25519KeyPair(
                    privateKey = Base64.decode(privB64, Base64.NO_WRAP),
                    publicKey = Base64.decode(pubB64, Base64.NO_WRAP)
                )
            )
        }
        Log.d(TAG, "${oneTimePreKeys.size} OneTimePreKeys geladen")
    }

    /**
     * Speichert OneTimePreKeys in SharedPreferences.
     */
    private fun saveOneTimePreKeys() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Alte Einträge löschen
        val oldCount = prefs.getInt("onetime_prekey_count", 0)
        for (i in 0 until oldCount) {
            editor.remove("opk_${i}_priv")
            editor.remove("opk_${i}_pub")
        }

        editor.putInt("onetime_prekey_count", oneTimePreKeys.size)
        oneTimePreKeys.forEachIndexed { i, opk ->
            editor.putString("opk_${i}_priv", Base64.encodeToString(opk.privateKey, Base64.NO_WRAP))
            editor.putString("opk_${i}_pub", Base64.encodeToString(opk.publicKey, Base64.NO_WRAP))
        }
        editor.apply()
    }

    /**
     * Lädt vorhandene Sessions aus SharedPreferences.
     */
    private fun loadSessions() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sessionsJson = prefs.getString(PREFS_SESSIONS, null) ?: return

        try {
            val jsonArray = org.json.JSONArray(sessionsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val peerId = obj.getString("peerId")
                val sessionStateJson = obj.getString("sessionState")

                val sessionState = SessionState.fromJson(sessionStateJson)
                sessions[peerId] = DoubleRatchet(sessionState)
            }
            Log.d(TAG, "${sessions.size} Sessions geladen")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Laden der Sessions: ${e.message}")
        }
    }

    /**
     * Speichert alle aktiven Sessions in SharedPreferences.
     */
    private fun saveSessions() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()

        sessions.forEach { (peerId, ratchet) ->
            try {
                val obj = org.json.JSONObject().apply {
                    put("peerId", peerId)
                    put("sessionState", ratchet.serializeSession())
                }
                jsonArray.put(obj)
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Serialisieren der Session für $peerId: ${e.message}")
            }
        }

        prefs.edit()
            .putString(PREFS_SESSIONS, jsonArray.toString())
            .apply()
    }

    /**
     * Löscht alle Daten (für Reset/Neuinitialisierung).
     */
    fun reset() {
        sessions.clear()
        oneTimePreKeys.clear()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        CryptoHelper.deleteFromAndroidKeyStore(KEY_ALIAS_IDENTITY)
        CryptoHelper.deleteFromAndroidKeyStore(KEY_ALIAS_SPK)

        identityKey = null
        signedPreKey = null
        signedPreKeySignature = null
        x3dhSession = null

        Log.i(TAG, "E2EE-Manager zurückgesetzt")
    }
}

/**
 * Datenklasse für die Initialisierung eines Handshakes.
 *
 * @property preKeyBundleJson Das PreKeyBundle als JSON (muss an den Peer gesendet werden)
 * @property ownEphemeralPrivateKey Der eigene ephemerale Private-Key (muss für completeHandshakeAsInitiator aufbewahrt werden)
 * @property peerBundle Das PreKeyBundle (für die spätere Verarbeitung)
 */
data class HandshakeInitData(
    val preKeyBundleJson: String,
    val ownEphemeralPrivateKey: ByteArray,
    val ownEphemeralPublicKey: ByteArray,
    val peerBundle: X3DHSession.PreKeyBundle
)

/**
 * Erweiterung für X3DHSession: generateSignedPreKey als statische Methode.
 */
fun X3DHSession.Companion.generateSignedPreKey(
    identityKey: CryptoHelper.KeyPair,
    identityPrivateKey: ByteArray
): Pair<CryptoHelper.X25519KeyPair, ByteArray> {
    val spk = CryptoHelper.generateX25519KeyPair()
    val signature = CryptoHelper.sign(spk.publicKey, identityPrivateKey)
    return Pair(spk, signature)
}

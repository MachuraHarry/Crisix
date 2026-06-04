package com.messenger.crisix.crypto

import android.util.Base64
import android.util.Log
import com.messenger.crisix.transport.internet.CryptoHelper
import java.security.SecureRandom

/**
 * X3DH (Extended Triple Diffie-Hellman) Initial Key Exchange für Crisix.
 *
 * X3DH handelt ein initiales Shared Secret zwischen zwei Peers aus, das dann
 * als Root-Key für das Double-Ratchet-Protokoll dient.
 *
 * ## Korrigierte Implementierung
 * Diese Version verwendet die korrekte Ed25519→X25519-Konvertierung (ed2curve)
 * und trennt strikt zwischen Initiator (Alice) und Responder (Bob).
 *
 * ## Ablauf
 * 1. **Alice** erstellt ein PreKeyBundle (IK_A, SPK_A, Sig_A, OPK_A optional)
 * 2. **Alice** sendet Bundle an Bob
 * 3. **Bob** verarbeitet das Bundle als Responder → berechnet Shared Secret
 * 4. **Bob** sendet eine PreKeyMessage (IK_B, EK_B) zurück an Alice
 * 5. **Alice** verarbeitet die PreKeyMessage als Initiator → berechnet Shared Secret
 *
 * @property ownIdentityKey Das eigene Ed25519-Identity-KeyPair
 * @property ownSignedPreKey Der eigene SignedPreKey (X25519)
 * @property ownSignedPreKeySignature Die Signatur des SPK mit dem Identity-Key
 * @property ownOneTimePreKeys Liste von One-Time-PreKeys (X25519)
 */
class X3DHSession(
    private val ownIdentityKey: CryptoHelper.KeyPair,
    private val ownSignedPreKey: CryptoHelper.X25519KeyPair,
    private val ownSignedPreKeySignature: ByteArray,
    private val ownOneTimePreKeys: MutableList<CryptoHelper.X25519KeyPair> = mutableListOf()
) {
    companion object {
        private const val TAG = "X3DHSession"

        /** Kontext-String für HKDF-Derivation des initialen Root-Keys */
        private val X3DH_INFO = "Crisix-X3DH-InitialRootKey".encodeToByteArray()

        /** Kontext-String für HKDF-Derivation des initialen Chain-Keys */
        private val INITIAL_CHAIN_INFO = "Crisix-X3DH-InitialChainKey".encodeToByteArray()
    }

    /**
     * Datenklasse für ein PreKey-Bundle, das ein Peer veröffentlicht.
     *
     * @property identityKey Der öffentliche Ed25519-Identity-Key des Peers (32 Bytes)
     * @property signedPreKey Der öffentliche X25519-SignedPreKey (32 Bytes)
     * @property signedPreKeySignature Signatur des SPK mit dem Identity-Key (64 Bytes)
     * @property oneTimePreKey Optionaler öffentlicher X25519-OneTime-PreKey (32 Bytes)
     */
    data class PreKeyBundle(
        val identityKey: ByteArray,
        val signedPreKey: ByteArray,
        val signedPreKeySignature: ByteArray,
        val oneTimePreKey: ByteArray? = null
    ) {
        fun toJson(): String {
            return org.json.JSONObject().apply {
                put("identityKey", Base64.encodeToString(identityKey, Base64.NO_WRAP))
                put("signedPreKey", Base64.encodeToString(signedPreKey, Base64.NO_WRAP))
                put("signedPreKeySignature", Base64.encodeToString(signedPreKeySignature, Base64.NO_WRAP))
                if (oneTimePreKey != null) {
                    put("oneTimePreKey", Base64.encodeToString(oneTimePreKey, Base64.NO_WRAP))
                }
            }.toString()
        }

        companion object {
            fun fromJson(json: String): PreKeyBundle {
                val obj = org.json.JSONObject(json)
                return PreKeyBundle(
                    identityKey = Base64.decode(obj.getString("identityKey"), Base64.NO_WRAP),
                    signedPreKey = Base64.decode(obj.getString("signedPreKey"), Base64.NO_WRAP),
                    signedPreKeySignature = Base64.decode(obj.getString("signedPreKeySignature"), Base64.NO_WRAP),
                    oneTimePreKey = if (obj.has("oneTimePreKey"))
                        Base64.decode(obj.getString("oneTimePreKey"), Base64.NO_WRAP) else null
                )
            }
        }
    }

    /**
     * Datenklasse für die Antwortnachricht im X3DH-Handshake.
     *
     * Diese Nachricht wird von Bob an Alice gesendet, nachdem er das
     * PreKeyBundle verarbeitet hat. Alice benötigt diese Daten, um
     * denselben Root-Key zu berechnen.
     *
     * @property identityKey Bobs öffentlicher Ed25519-Identity-Key (32 Bytes)
     * @property ephemeralKey Bobs ephemeraler X25519-Public-Key (32 Bytes)
     * @property signedPreKey Bobs X25519-SignedPreKey-Public (32 Bytes) — von Alice für DH1/DH3 benötigt
     * @property usedOneTimePreKey Flag, ob ein OPK verwendet wurde
     * @property oneTimePreKey Bobs verwendeter OTPk-Public (32 Bytes, optional) — KRITISCH für korrekte DH4
     */
    data class PreKeyMessage(
        val identityKey: ByteArray,
        val ephemeralKey: ByteArray,
        val signedPreKey: ByteArray,
        val usedOneTimePreKey: Boolean,
        val oneTimePreKey: ByteArray? = null  // ← Bob sendet seinen OTPk zurück!
    ) {
        fun toJson(): String {
            return org.json.JSONObject().apply {
                put("identityKey", Base64.encodeToString(identityKey, Base64.NO_WRAP))
                put("ephemeralKey", Base64.encodeToString(ephemeralKey, Base64.NO_WRAP))
                put("signedPreKey", Base64.encodeToString(signedPreKey, Base64.NO_WRAP))
                put("usedOneTimePreKey", usedOneTimePreKey)
                if (oneTimePreKey != null) {
                    put("oneTimePreKey", Base64.encodeToString(oneTimePreKey, Base64.NO_WRAP))
                }
            }.toString()
        }

        companion object {
            fun fromJson(json: String): PreKeyMessage {
                val obj = org.json.JSONObject(json)
                return PreKeyMessage(
                    identityKey = Base64.decode(obj.getString("identityKey"), Base64.NO_WRAP),
                    ephemeralKey = Base64.decode(obj.getString("ephemeralKey"), Base64.NO_WRAP),
                    signedPreKey = if (obj.has("signedPreKey"))
                        Base64.decode(obj.getString("signedPreKey"), Base64.NO_WRAP)
                    else
                        ByteArray(32) { 0 }, // Fallback für alte Clients
                    usedOneTimePreKey = obj.getBoolean("usedOneTimePreKey"),
                    oneTimePreKey = if (obj.has("oneTimePreKey"))
                        Base64.decode(obj.getString("oneTimePreKey"), Base64.NO_WRAP)
                    else
                        null
                )
            }
        }
    }

    /**
     * Erstellt ein PreKey-Bundle für die Veröffentlichung.
     *
     * Das Bundle enthält:
     * - Den öffentlichen Identity-Key (Ed25519)
     * - Den öffentlichen SignedPreKey (X25519) + Signatur
     * - Optional einen OneTimePreKey (X25519)
     *
     * @param useOneTimePreKey Ob ein OneTimePreKey ins Bundle aufgenommen werden soll
     * @return Das PreKeyBundle
     */
    fun createPreKeyBundle(useOneTimePreKey: Boolean = true): PreKeyBundle {
        val opk = if (useOneTimePreKey && ownOneTimePreKeys.isNotEmpty()) {
            ownOneTimePreKeys.removeFirst().publicKey
        } else null

        return PreKeyBundle(
            identityKey = ownIdentityKey.publicKey,
            signedPreKey = ownSignedPreKey.publicKey,
            signedPreKeySignature = ownSignedPreKeySignature,
            oneTimePreKey = opk
        )
    }

    /**
     * Verarbeitet ein eingehendes PreKeyBundle als **Responder (Bob)**.
     *
     * Bob hat Alices PreKeyBundle empfangen und berechnet das Shared Secret.
     * Dies ist die Seite, die den Handshake ANNIMMT.
     *
     * DH-Berechnungen (Bob als Responder):
     * - DH1 = DH(SPK_B_priv, IK_A)  — Bobs SPK + Alices Identity
     * - DH2 = DH(IK_B_priv, EK_A)   — Bobs Identity (X25519) + Alices Ephemeral
     * - DH3 = DH(SPK_B_priv, EK_A)  — Bobs SPK + Alices Ephemeral
     * - DH4 = DH(OPK_B_priv, EK_A)  — Bobs OPK + Alices Ephemeral (optional)
     *
     * @param peerBundle Das PreKeyBundle des Initiators (Alice)
     * @param peerEphemeralKey Der ephemerale Public-Key des Initiators (aus PreKeyMessage)
     * @return Ein Triple aus (SessionState, usedOneTimePreKey, usedOtpkPublic) oder null bei Fehler
     *         - SessionState: Der initiale SessionState für das Double Ratchet
     *         - usedOneTimePreKey: true wenn Bob tatsächlich sein OTPk verwendet hat
     *         - usedOtpkPublic: Der Public-Key des verwendeten OTPks (für Alice zur DH4-Berechnung)
     */
    fun processAsResponder(
        peerBundle: PreKeyBundle,
        peerEphemeralKey: ByteArray
    ): Triple<SessionState, Boolean, ByteArray?>? {
        return try {
            Log.d(TAG, "Verarbeite PreKeyBundle als Responder (Bob)")

            // 1. Identity-Key des Peers (Ed25519) → X25519 konvertieren
            val peerIdentityX = Ed2Curve.ed25519PublicToX25519(peerBundle.identityKey)
                ?: throw IllegalStateException("Konvertierung IK_A → X25519 fehlgeschlagen")

            // 2. Eigenen Identity-Key (Ed25519) → X25519 Private konvertieren
            val ownIdentityPrivX = Ed2Curve.ed25519PrivateToX25519(ownIdentityKey.privateKey)
                ?: throw IllegalStateException("Konvertierung IK_B_priv → X25519 fehlgeschlagen")

            // 3. Vier DH-Shared-Secrets berechnen
            // DH1 = DH(SPK_B_priv, IK_A) — Bobs SignedPreKey + Alices Identity
            val dh1 = CryptoHelper.x25519DH(ownSignedPreKey.privateKey, peerIdentityX)
                ?: throw IllegalStateException("DH1 (Responder) fehlgeschlagen")

            // DH2 = DH(IK_B_priv, EK_A) — Bobs Identity + Alices Ephemeral
            val dh2 = CryptoHelper.x25519DH(ownIdentityPrivX, peerEphemeralKey)
                ?: throw IllegalStateException("DH2 (Responder) fehlgeschlagen")

            // DH3 = DH(SPK_B_priv, EK_A) — Bobs SPK + Alices Ephemeral
            val dh3 = CryptoHelper.x25519DH(ownSignedPreKey.privateKey, peerEphemeralKey)
                ?: throw IllegalStateException("DH3 (Responder) fehlgeschlagen")

            // DH4 = DH(OPK_B_priv, EK_A) — optional
            // KRITISCH: DH4 wird NUR berechnet, wenn Alice AUCH ein OPK gesendet hat!
            // Wenn Alice kein OPK hat, darf Bob AUCH kein DH4 berechnen, 
            // sonst haben die beiden unterschiedliche SharedSecret-Längen!
            // WICHTIG: Bob MUSS seinen genutzten OTPk-Public zu Alice zurücksendet,
            // sonst kann Alice nicht denselben DH4 berechnen!
            var dh4: ByteArray? = null
            var usedOneTimePreKey = false
            var usedOtpkPublic: ByteArray? = null  // ← Bobs OTPk Public, das Alice braucht!
            if (peerBundle.oneTimePreKey != null) {
                // Alice hat ein OPK gesendet → Bob darf seinen OPK nutzen
                if (ownOneTimePreKeys.isNotEmpty()) {
                    val opk = ownOneTimePreKeys.removeFirst()
                    dh4 = CryptoHelper.x25519DH(opk.privateKey, peerEphemeralKey)
                    usedOtpkPublic = opk.publicKey.copyOf()  // ← Speichern für PreKeyMessage!
                    wipeBytes(opk.privateKey)
                    usedOneTimePreKey = true  // ← Bob hat tatsächlich sein OPK verwendet
                } else {
                    // Alice hat OPK gesendet, aber Bob hat keinen → Warnung
                    Log.w(TAG, "⚠️  Responder: Alice sent OPK, but Bob has no OneTimePreKey available!")
                    usedOneTimePreKey = false
                }
            }

            // 4. Shared Secret = DH1 || DH2 || DH3 || (DH4 optional)
            val sharedSecret = if (dh4 != null) {
                dh1 + dh2 + dh3 + dh4
            } else {
                dh1 + dh2 + dh3
            }

            // DEBUG: Log all DH values and shared secret
            Log.d(TAG, "🔍 Responder DH1 (32B): ${dh1.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder DH2 (32B): ${dh2.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder DH3 (32B): ${dh3.joinToString("") { "%02x".format(it) }}")
            if (dh4 != null) {
                Log.d(TAG, "🔍 Responder DH4 (32B): ${dh4.joinToString("") { "%02x".format(it) }}")
            }
            Log.d(TAG, "🔍 Responder SharedSecret length: ${sharedSecret.size} bytes")
            Log.d(TAG, "🔍 Responder SharedSecret: ${sharedSecret.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder X3DH_INFO: ${X3DH_INFO.joinToString("") { "%02x".format(it) }} (string: '${String(X3DH_INFO)}')")

            // 5. Initialen Root-Key ableiten (HKDF)
            val initialRootKey = CryptoHelper.hkdfDerive(
                sharedSecret,
                salt = null,
                info = X3DH_INFO,
                length = 32
            )

             // 6. Initiale Chain-Keys ableiten
             // Bobs Sending-Chain = was er an Alice sendet
             // Bobs Receiving-Chain = was er von Alice empfängt
             //
             // WICHTIG: Die Info-Strings MÜSSEN mit processAsInitiator() identisch sein,
             // damit beide Seiten denselben Schlüssel ableiten:
             //   - "Crisix-X3DH-InitialSendingChainKey" für SENDING (Initiator/Alice) = RECEIVING (Responder/Bob)
             //   - "Crisix-X3DH-InitialReceivingChainKey" für RECEIVING (Initiator/Alice) = SENDING (Responder/Bob)
             //
             // Responder (Bob) leitet ab:
             //   - Bobs Receiving = Alices Sending → "Crisix-X3DH-InitialSendingChainKey"
             //   - Bobs Sending = Alices Receiving → "Crisix-X3DH-InitialReceivingChainKey"
              val initialReceivingChainKey = CryptoHelper.hkdfDerive(
                  initialRootKey,
                  salt = initialRootKey,
                  info = "Crisix-X3DH-InitialSendingChainKey".encodeToByteArray(),
                  length = 32
              )
              val initialSendingChainKey = CryptoHelper.hkdfDerive(
                  initialRootKey,
                  salt = initialRootKey,
                  info = "Crisix-X3DH-InitialReceivingChainKey".encodeToByteArray(),
                  length = 32
              )

            // 7. DH-KeyPairs für Double Ratchet initialisieren
            // Wichtig: Beide Seiten initialisieren mit FRISCHEN Keys
            val sendingDhKeyPair = CryptoHelper.generateX25519KeyPair()
            val receivingDhKeyPair = CryptoHelper.generateX25519KeyPair()

            // 8. SessionState erstellen
            val sessionState = SessionState(
                rootKey = initialRootKey,
                sendingChainKey = initialSendingChainKey,
                receivingChainKey = initialReceivingChainKey,
                sendingDhKeyPair = sendingDhKeyPair,
                receivingDhKeyPair = receivingDhKeyPair,
                sendingChainIndex = 0,
                receivingChainIndex = 0,
                sendingMessageIndex = 0,
                receivingMessageIndex = 0
            )

            // DEBUG: Shared Secret Vergleich
            Log.d(TAG, "🔍 Responder SK (first 16): ${sharedSecret.toList().take(16).joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder RootKey: ${initialRootKey.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder SendChain: ${initialSendingChainKey.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Responder RecvChain: ${initialReceivingChainKey.joinToString("") { "%02x".format(it) }}")

            // Alte Keys löschen
            wipeBytes(dh1)
            wipeBytes(dh2)
            wipeBytes(dh3)
            dh4?.let { wipeBytes(it) }
            wipeBytes(sharedSecret)

            Log.i(TAG, "✅ Responder: Shared Secret berechnet, Session bereit (DH4 used: $usedOneTimePreKey)")
            Triple(sessionState, usedOneTimePreKey, usedOtpkPublic)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Responder: Fehler", e)
            null
        }
    }

    /**
     * Verarbeitet eine eingehende PreKeyMessage als **Initiator (Alice)**.
     *
     * Alice hat Bobs PreKeyBundle gesendet und bekommt nun Bobs PreKeyMessage.
     * Sie berechnet dasselbe Shared Secret wie Bob.
     *
     * DH-Berechnungen (Alice als Initiator):
     * - DH1 = DH(IK_A_priv, SPK_B)  — Alices Identity (X25519) + Bobs SPK
     * - DH2 = DH(EK_A_priv, IK_B)   — Alices Ephemeral + Bobs Identity (X25519)
     * - DH3 = DH(EK_A_priv, SPK_B)  — Alices Ephemeral + Bobs SPK
     * - DH4 = DH(EK_A_priv, OPK_B)  — Alices Ephemeral + Bobs OPK (optional)
     *
     * @param peerPreKeyMessage Die PreKeyMessage des Responders (Bob)
     * @param ownEphemeralPrivateKey Der eigene ephemerale Private-Key (von Alice)
     * @param peerBundle Das PreKeyBundle des Responders (Bob) — für SPK und OPK
     * @return Der initiale [SessionState] für das Double Ratchet, oder null bei Fehler
     */
    fun processAsInitiator(
        peerPreKeyMessage: PreKeyMessage,
        ownEphemeralPrivateKey: ByteArray,
        peerBundle: PreKeyBundle
    ): SessionState? {
        return try {
            Log.d(TAG, "Verarbeite PreKeyMessage als Initiator (Alice)")

            // 1. Identity-Key des Peers (Ed25519) → X25519 konvertieren
            val peerIdentityX = Ed2Curve.ed25519PublicToX25519(peerPreKeyMessage.identityKey)
                ?: throw IllegalStateException("Konvertierung IK_B → X25519 fehlgeschlagen")

            // 2. Eigenen Identity-Key (Ed25519) → X25519 Private konvertieren
            val ownIdentityPrivX = Ed2Curve.ed25519PrivateToX25519(ownIdentityKey.privateKey)
                ?: throw IllegalStateException("Konvertierung IK_A_priv → X25519 fehlgeschlagen")

            // 3. Vier DH-Shared-Secrets berechnen
            // DH1 = DH(IK_A_priv, SPK_B) — Bobs SignedPreKey aus PreKeyMessage
            val dh1 = CryptoHelper.x25519DH(ownIdentityPrivX, peerPreKeyMessage.signedPreKey)
                ?: throw IllegalStateException("DH1 (Initiator) fehlgeschlagen")

            // DH2 = DH(EK_A_priv, IK_B)
            val dh2 = CryptoHelper.x25519DH(ownEphemeralPrivateKey, peerIdentityX)
                ?: throw IllegalStateException("DH2 (Initiator) fehlgeschlagen")

            // DH3 = DH(EK_A_priv, SPK_B) — Bobs SignedPreKey aus PreKeyMessage
            val dh3 = CryptoHelper.x25519DH(ownEphemeralPrivateKey, peerPreKeyMessage.signedPreKey)
                ?: throw IllegalStateException("DH3 (Initiator) fehlgeschlagen")

            // DH4 = DH(EK_A_priv, OPK_B) — optional
            // KRITISCH: DH4 wird NUR berechnet, wenn:
            // 1. Alice selbst ein OPK gesendet hat (peerBundle.oneTimePreKey != null) UND
            // 2. Bob tatsächlich sein OPK verwendet hat (peerPreKeyMessage.usedOneTimePreKey)
            // 3. Bob schickt seinen OTPk-Public zurück (peerPreKeyMessage.oneTimePreKey != null)
            // Wenn Bob kein OPK hatte, wird usedOneTimePreKey=false, und Alice darf
            // NICHT DH4 berechnen, sonst sind die SharedSecrets unterschiedlich lang!
            // WICHTIG: Alice MUSS Bobs OTPk-Public (aus PreKeyMessage) verwenden,
            // nicht ihren eigenen OTPk aus dem Bundle!
            var dh4: ByteArray? = null
            if (peerPreKeyMessage.usedOneTimePreKey && peerPreKeyMessage.oneTimePreKey != null) {
                dh4 = CryptoHelper.x25519DH(ownEphemeralPrivateKey, peerPreKeyMessage.oneTimePreKey)
            }

            // 4. Shared Secret = DH1 || DH2 || DH3 || (DH4 optional)
            val sharedSecret = if (dh4 != null) {
                dh1 + dh2 + dh3 + dh4
            } else {
                dh1 + dh2 + dh3
            }

            // DEBUG: Log all DH values and shared secret
            Log.d(TAG, "🔍 Initiator DH1 (32B): ${dh1.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator DH2 (32B): ${dh2.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator DH3 (32B): ${dh3.joinToString("") { "%02x".format(it) }}")
            if (dh4 != null) {
                Log.d(TAG, "🔍 Initiator DH4 (32B): ${dh4.joinToString("") { "%02x".format(it) }}")
            }
            Log.d(TAG, "🔍 Initiator SharedSecret length: ${sharedSecret.size} bytes")
            Log.d(TAG, "🔍 Initiator SharedSecret: ${sharedSecret.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator X3DH_INFO: ${X3DH_INFO.joinToString("") { "%02x".format(it) }} (string: '${String(X3DH_INFO)}')")

            // 5. Initialen Root-Key ableiten (HKDF)
            val initialRootKey = CryptoHelper.hkdfDerive(
                sharedSecret,
                salt = null,
                info = X3DH_INFO,
                length = 32
            )

            // 6. Initiale Chain-Keys ableiten
            // Alices Sending-Chain = was sie an Bob sendet
            // Alices Receiving-Chain = was sie von Bob empfängt
            //
            // WICHTIG: Unterschiedliche Info-Strings für Sending und Receiving!
            // Sonst haben beide Chain-Keys denselben Wert, und nach
            // Deserialisierung (wenn Referenzen verloren gehen) kann
            // deriveMessageKey() nicht mehr unterscheiden, welcher Chain-Key
            // gemeint ist → contentEquals() matched den falschen → BAD_DECRYPT.
            val initialSendingChainKey = CryptoHelper.hkdfDerive(
                initialRootKey,
                salt = initialRootKey,
                info = "Crisix-X3DH-InitialSendingChainKey".encodeToByteArray(),
                length = 32
            )
            val initialReceivingChainKey = CryptoHelper.hkdfDerive(
                initialRootKey,
                salt = initialRootKey,
                info = "Crisix-X3DH-InitialReceivingChainKey".encodeToByteArray(),
                length = 32
            )

            // 7. DH-KeyPairs für Double Ratchet
            val sendingDhKeyPair = CryptoHelper.generateX25519KeyPair()
            val receivingDhKeyPair = CryptoHelper.generateX25519KeyPair()

            // 8. SessionState erstellen
            val sessionState = SessionState(
                rootKey = initialRootKey,
                sendingChainKey = initialSendingChainKey,
                receivingChainKey = initialReceivingChainKey,
                sendingDhKeyPair = sendingDhKeyPair,
                receivingDhKeyPair = receivingDhKeyPair,
                sendingChainIndex = 0,
                receivingChainIndex = 0,
                sendingMessageIndex = 0,
                receivingMessageIndex = 0
            )

            // DEBUG: Shared Secret Vergleich
            Log.d(TAG, "🔍 Initiator SK (first 16): ${sharedSecret.toList().take(16).joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator RootKey: ${initialRootKey.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator SendChain: ${initialSendingChainKey.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "🔍 Initiator RecvChain: ${initialReceivingChainKey.joinToString("") { "%02x".format(it) }}")

            // Alte Keys löschen
            wipeBytes(dh1)
            wipeBytes(dh2)
            wipeBytes(dh3)
            dh4?.let { wipeBytes(it) }
            wipeBytes(sharedSecret)

            Log.i(TAG, "✅ Initiator: Shared Secret berechnet, Session bereit")
            sessionState
        } catch (e: Exception) {
            Log.e(TAG, "❌ Initiator: Fehler", e)
            null
        }
    }

    /**
     * Erstellt eine PreKeyMessage als Responder (Bob).
     *
     * Bob hat Alices PreKeyBundle verarbeitet und sendet nun seine
     * PreKeyMessage zurück. Diese enthält Bobs Identity-Key und einen
     * frischen ephemeralen Key.
     *
     * @return Ein Pair aus (PreKeyMessage, ephemeralPrivateKey)
     */
    fun createResponderPreKeyMessage(): Pair<PreKeyMessage, ByteArray> {
        val ephemeralKeyPair = CryptoHelper.generateX25519KeyPair()
        val message = PreKeyMessage(
            identityKey = ownIdentityKey.publicKey,
            ephemeralKey = ephemeralKeyPair.publicKey,
            signedPreKey = ownSignedPreKey.publicKey,
            usedOneTimePreKey = false
        )
        return Pair(message, ephemeralKeyPair.privateKey)
    }

    /**
     * Validiert ein PreKeyBundle eines Peers.
     *
     * Prüft:
     * - Die Signatur des SignedPreKey mit dem Identity-Key
     * - Die Schlüssellängen
     *
     * @param bundle Das zu validierende PreKeyBundle
     * @return true wenn gültig, false bei Manipulation
     */
    fun validatePreKeyBundle(bundle: PreKeyBundle): Boolean {
        return try {
            // Schlüssellängen prüfen
            require(bundle.identityKey.size == 32) { "Identity-Key muss 32 Bytes haben" }
            require(bundle.signedPreKey.size == 32) { "SignedPreKey muss 32 Bytes haben" }
            require(bundle.signedPreKeySignature.size == 64) { "Signatur muss 64 Bytes haben" }

            // Signatur prüfen: SPK wurde mit Identity-Key signiert
            val isValid = CryptoHelper.verify(
                bundle.signedPreKey,
                bundle.signedPreKeySignature,
                bundle.identityKey
            )

            if (!isValid) {
                Log.w(TAG, "PreKeyBundle-Signatur ungültig — mögliche Manipulation")
            }

            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei PreKeyBundle-Validierung", e)
            false
        }
    }

    /**
     * Generiert einen neuen SignedPreKey (X25519) + Signatur.
     *
     * Der SignedPreKey wird mit dem Ed25519-Identity-Key signiert.
     */
    fun generateSignedPreKey(): Pair<CryptoHelper.X25519KeyPair, ByteArray> {
        val spk = CryptoHelper.generateX25519KeyPair()
        val signature = CryptoHelper.sign(spk.publicKey, ownIdentityKey.privateKey)
        Log.d(TAG, "Neuer SignedPreKey generiert")
        return Pair(spk, signature)
    }

    /**
     * Generiert einen neuen OneTimePreKey (X25519).
     */
    fun generateOneTimePreKey(): CryptoHelper.X25519KeyPair {
        val opk = CryptoHelper.generateX25519KeyPair()
        ownOneTimePreKeys.add(opk)
        Log.d(TAG, "Neuer OneTimePreKey generiert (${ownOneTimePreKeys.size} verfügbar)")
        return opk
    }

    /**
     * Überschreibt ein Byte-Array mit Nullen.
     */
    private fun wipeBytes(data: ByteArray) {
        data.fill(0)
    }
}

/**
 * Ergebnis einer X3DH-Session-Initiation.
 *
 * @property sessionState Der initiale SessionState für das Double Ratchet
 * @property preKeyMessage Die PreKeyMessage, die an den Peer gesendet werden muss
 */
data class X3DHResult(
    val sessionState: SessionState,
    val preKeyMessage: X3DHSession.PreKeyMessage
)

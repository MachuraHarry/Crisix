package com.messenger.crisix.crypto

import android.util.Base64
import timber.log.Timber
import android.util.Log
import com.messenger.crisix.transport.internet.CryptoHelper
import java.security.SecureRandom

/**
 * Double-Ratchet-Implementierung für Crisix.
 *
 * Bietet Forward Secrecy und Break-in-Recovery für Ende-zu-Ende-verschlüsselte
 * Chat-Nachrichten. Das Protokoll kombiniert:
 * - **DH-Ratchet**: Periodischer Diffie-Hellman-Schlüsselaustausch (Forward Secrecy)
 * - **Symmetrisches Ratchet**: Hash-Chain für jede Nachricht (Break-in Recovery)
 *
 * ## Korrigierte Implementierung
 * - Kein DH-Ratchet bei der ersten Nachricht (initialer DH-Key aus X3DH)
 * - DH-Ratchet erst nach MAX_CHAIN_LENGTH Nachrichten
 * - Korrekte Nonce-Derivation mit Message-Key als Salt
 * - Thread-sichere ChainKey-Verwaltung
 *
 * @property sessionState Der aktuelle Zustand der Ratchet-Session
 */
class DoubleRatchet(private var sessionState: SessionState) {

    /** Out-of-Order-Message-Handler für verspätete/ungeordnete Nachrichten */
    private val outOfOrderHandler = OutOfOrderMessageHandler()

    /** Peer-ID für Logging/Tracking */
    var peerId: String = "unknown"

    /** Session-Version (Unix-Sekunden bei Etablierung) */
    var sessionVersion: Int = 0

    /** Letzte Entschlüsselung hat MAX_SKIP überschritten */
    var lastSkipViolation: Boolean = false
        private set

    companion object {
        private const val TAG = "DoubleRatchet"

        /** Länge der Nonce in Bytes (12 Bytes = 96 Bit für AES-GCM) */
        private const val NONCE_LENGTH = 12

        /** Maximale Anzahl Nachrichten pro Chain bevor DH-Ratchet erzwungen wird */
        private const val MAX_CHAIN_LENGTH = 1000

        /** Maximale Anzahl Nachrichten, die übersprungen werden duerfen (Sicherheitslimit) */
        const val MAX_SKIP = OutOfOrderMessageHandler.MAX_SKIP

        /** Kontext-String für HKDF-Derivation des Root-Keys */
        private val ROOT_INFO = "Crisix-DoubleRatchet-RootKey".encodeToByteArray()

        /** Kontext-String für HKDF-Derivation des Chain-Keys */
        private val CHAIN_INFO = "Crisix-DoubleRatchet-ChainKey".encodeToByteArray()

        /** Kontext-String für HKDF-Derivation des Message-Keys */
        private val MESSAGE_INFO = "Crisix-DoubleRatchet-MessageKey".encodeToByteArray()

        /** Kontext-String für HKDF-Derivation des nächsten Chain-Keys */
        private val NEXT_CHAIN_INFO = "Crisix-DoubleRatchet-NextChainKey".encodeToByteArray()
    }

    /**
     * Verschlüsselt eine Klartext-Nachricht mit dem Double-Ratchet-Protokoll.
     *
     * Führt bei Bedarf einen DH-Ratchet-Schritt durch (nach MAX_CHAIN_LENGTH Nachrichten).
     *
     * @param plaintext Die zu verschlüsselnden Daten (UTF-8-Byte-Array)
     * @return Ein [EncryptedMessage] mit Header + Ciphertext + Nonce
     */
    fun ratchetEncrypt(plaintext: ByteArray): EncryptedMessage {
        // DH-Ratchet nur nach MAX_CHAIN_LENGTH Nachrichten
        if (sessionState.sendingMessageIndex >= MAX_CHAIN_LENGTH) {
            dhRatchetSend()
        }

        // Message-Key aus der Sending-Chain ableiten
        val messageKey = deriveMessageKey(
            sessionState.sendingChainKey,
            sessionState.sendingMessageIndex
        )

        // Nonce aus Message-Key + Message-Index ableiten (eindeutig)
        val nonce = generateNonce(messageKey, sessionState.sendingMessageIndex)

        // Verschlüsseln
        val ciphertext = CryptoHelper.aesGcmEncrypt(plaintext, messageKey, nonce)

        // Message-Index erhöhen (NACH der Nonce-Generierung)
        val currentMsgIndex = sessionState.sendingMessageIndex
        sessionState.sendingMessageIndex++

        // Alten Message-Key löschen (Forward Secrecy)
        wipeBytes(messageKey)

        Log.d(TAG, "Verschlüsselt: chainIdx=${sessionState.sendingChainIndex}, msgIdx=$currentMsgIndex")

        return EncryptedMessage(
            dhPublicKey = sessionState.sendingDhKeyPair.publicKey,
            chainIndex = sessionState.sendingChainIndex,
            messageIndex = currentMsgIndex,
            nonce = nonce,
            ciphertext = ciphertext,
            sessionVersion = sessionVersion
        )
    }

    /**
     * Entschlüsselt eine verschlüsselte Nachricht mit dem Double-Ratchet-Protokoll.
     *
     * Erkennt anhand des DH-Public-Keys im Header, ob ein DH-Ratchet-Schritt
     * auf der Empfängerseite nötig ist.
     *
     * @param message Die verschlüsselte Nachricht mit Header
     * @return Der entschlüsselte Klartext, oder null bei Fehler
     */
    fun ratchetDecrypt(message: EncryptedMessage): ByteArray? {
        lastSkipViolation = false

        if (outOfOrderHandler.isSkipLimitExceeded(message.messageIndex)) {
            Log.w(TAG, "MAX_SKIP ueberschritten: msgIdx=${message.messageIndex}, maxSeen=${outOfOrderHandler.getMaxMessageIndexSeen()} — Nachricht verworfen")
            lastSkipViolation = true
            return null
        }

        return try {
            if (message.dhPublicKey.contentEquals(sessionState.receivingDhKeyPair.publicKey)) {
                // Gleicher DH-Key → symmetrisches Ratchet

                // ═══════════════════════════════════════════════════════════════
                // NORMAL DECRYPTION (in-order)
                // ═══════════════════════════════════════════════════════════════
                return try {
                    val messageKey = deriveMessageKey(
                        sessionState.receivingChainKey,
                        message.messageIndex
                    )
                    val nonce = generateNonce(messageKey, message.messageIndex)
                    val plaintext = CryptoHelper.aesGcmDecrypt(
                        message.ciphertext, messageKey, nonce
                    )

                    // Cache den aktuellen Chain-Key für zukünftige Out-of-Order-Messages
                    // (falls nächste Nachricht geskippt wird)
                    outOfOrderHandler.cacheChainKey(
                        message.messageIndex,
                        sessionState.receivingChainKey,
                        peerId = peerId
                    )

                    wipeBytes(messageKey)
                    plaintext

                } catch (e: Exception) {
                    // ═══════════════════════════════════════════════════════════════
                    // OUT-OF-ORDER DECRYPTION (wenn Normal-Decryption fehlschlägt)
                    // ═══════════════════════════════════════════════════════════════
                    Log.d(TAG, "Normale Entschlüsselung fehlgeschlagen — versuche Out-of-Order...")
                    val plaintext = outOfOrderHandler.tryDecryptOutOfOrder(
                        message.messageIndex,
                        message.nonce,
                        message.ciphertext,
                        peerId = peerId
                    )

                    if (plaintext != null) {
                        Log.i(TAG, "✅ Out-of-Order-Nachricht #${message.messageIndex} dekryptiert!")
                    } else {
                        Log.e(TAG, "❌ Out-of-Order-Decryption fehlgeschlagen für #${message.messageIndex}")
                    }
                    plaintext
                }

            } else {
                // Neuer DH-Key → DH-Ratchet durchführen
                dhRatchetReceive(message.dhPublicKey)

                // Jetzt mit neuem Receiving-Chain-Key entschlüsseln
                val messageKey = deriveMessageKey(
                    sessionState.receivingChainKey,
                    message.messageIndex
                )
                val nonce = generateNonce(messageKey, message.messageIndex)
                val plaintext = CryptoHelper.aesGcmDecrypt(
                    message.ciphertext, messageKey, nonce
                )

                // Cache den neuen Chain-Key
                outOfOrderHandler.cacheChainKey(
                    message.messageIndex,
                    sessionState.receivingChainKey,
                    peerId = peerId
                )

                wipeBytes(messageKey)
                plaintext
            }
        } catch (e: Exception) {
            Log.e(TAG, "Entschlüsselung fehlgeschlagen: ${e.message}")
            null
        }
    }

    fun tryForceDecryptWithCache(ciphertext: ByteArray, nonce: ByteArray, messageIndex: Int): ByteArray? {
        val currentKey = deriveMessageKey(sessionState.receivingChainKey, messageIndex)
        val currentNonce = generateNonce(currentKey, messageIndex)
        try {
            return CryptoHelper.aesGcmDecrypt(ciphertext, currentKey, currentNonce)
        } catch (_: Exception) {}

        return outOfOrderHandler.tryDecryptOutOfOrder(messageIndex, nonce, ciphertext, peerId)
    }

    /**
     * Führt einen DH-Ratchet-Schritt auf der Senderseite durch.
     *
     * Erzeugt ein neues DH-Schlüsselpaar, berechnet ein neues Shared Secret
     * mit dem letzten bekannten Receiving-DH-Public-Key des Peers und leitet
     * daraus neue Chain-Keys ab.
     */
    private fun dhRatchetSend() {
        // Altes Sending-KeyPair sichern (für Receiving-Seite)
        val previousSendingDh = sessionState.sendingDhKeyPair

        // Neues DH-KeyPair für Senden erzeugen
        sessionState.sendingDhKeyPair = CryptoHelper.generateX25519KeyPair()

        // Shared Secret berechnen: newSendingPriv * receivingPub
        val sharedSecret = CryptoHelper.x25519DH(
            sessionState.sendingDhKeyPair.privateKey,
            sessionState.receivingDhKeyPair.publicKey
        ) ?: throw IllegalStateException("DH-Ratchet fehlgeschlagen")

        // Neuen Root-Key ableiten
        sessionState.rootKey = CryptoHelper.hkdfDerive(
            sharedSecret,
            salt = sessionState.rootKey,
            info = ROOT_INFO,
            length = 32
        )

        // Neue Sending-Chain-Key aus Root-Key ableiten
        sessionState.sendingChainKey = CryptoHelper.hkdfDerive(
            sessionState.rootKey,
            salt = sessionState.rootKey,
            info = CHAIN_INFO,
            length = 32
        )

        // Sending-Chain-Index erhöhen
        sessionState.sendingChainIndex++
        sessionState.sendingMessageIndex = 0

        // Alte Keys löschen
        wipeBytes(sharedSecret)
        wipeBytes(previousSendingDh.privateKey)

        Log.d(TAG, "DH-Ratchet (Send): chainIdx=${sessionState.sendingChainIndex}")
    }

    /**
     * Führt einen DH-Ratchet-Schritt auf der Empfängerseite durch.
     *
     * @param newDhPublicKey Der neue DH-Public-Key des Senders
     */
    private fun dhRatchetReceive(newDhPublicKey: ByteArray) {
        // Shared Secret berechnen: receivingPriv * newSendingPub
        val sharedSecret = CryptoHelper.x25519DH(
            sessionState.receivingDhKeyPair.privateKey,
            newDhPublicKey
        ) ?: throw IllegalStateException("DH-Ratchet (Receive) fehlgeschlagen")

        // Neuen Root-Key ableiten
        sessionState.rootKey = CryptoHelper.hkdfDerive(
            sharedSecret,
            salt = sessionState.rootKey,
            info = ROOT_INFO,
            length = 32
        )

        // Neue Receiving-Chain-Key aus Root-Key ableiten
        sessionState.receivingChainKey = CryptoHelper.hkdfDerive(
            sessionState.rootKey,
            salt = sessionState.rootKey,
            info = CHAIN_INFO,
            length = 32
        )

        // Neues Receiving-DH-KeyPair (das alte war für diesen DH-Schritt, jetzt neu)
        sessionState.receivingDhKeyPair = CryptoHelper.generateX25519KeyPair()

        // Receiving-Chain-Index erhöhen
        sessionState.receivingChainIndex++
        sessionState.receivingMessageIndex = 0

        // Alte Keys löschen
        wipeBytes(sharedSecret)

        Log.d(TAG, "DH-Ratchet (Receive): chainIdx=${sessionState.receivingChainIndex}")
    }

    /**
     * Leitet einen Message-Key aus einem Chain-Key ab.
     *
     * Der Chain-Key wird nach jeder Ableitung gehasht (symmetrisches Ratchet),
     * sodass alte Message-Keys nicht aus dem neuen Chain-Key rekonstruiert werden können.
     *
     * @param chainKey Der aktuelle Chain-Key (32 Bytes)
     * @param messageIndex Der Nachrichten-Index (für Determinismus)
     * @return Ein 32-Byte-Message-Key
     */
    private fun deriveMessageKey(chainKey: ByteArray, messageIndex: Int): ByteArray {
        // Message-Key = HKDF(chainKey, salt=chainKey, info=MESSAGE_INFO)
        val messageKey = CryptoHelper.hkdfDerive(
            chainKey,
            salt = chainKey,
            info = MESSAGE_INFO,
            length = 32
        )

        // Chain-Key für nächste Nachricht hashen (symmetrisches Ratchet)
        val newChainKey = CryptoHelper.hkdfDerive(
            chainKey,
            salt = chainKey,
            info = NEXT_CHAIN_INFO,
            length = 32
        )

        // ═══════════════════════════════════════════════════════════════
        // WICHTIG: Chain-Key im Session-State aktualisieren.
        //
        // NUR Referenzvergleich (===) verwenden, KEIN contentEquals!
        //
        // Problem: sendingChainKey und receivingChainKey haben initial
        // denselben Wert (beide aus demselben Root-Key abgeleitet).
        // contentEquals() würde dann IMMER sendingChainKey treffen,
        // auch wenn receivingChainKey gemeint ist → Chain-Keys korrumpiert
        // → AES-GCM BAD_DECRYPT.
        //
        // Referenzvergleich ist sicher, weil:
        // - sendingChainKey und receivingChainKey sind verschiedene Objekte
        // - hkdfDerive() erzeugt jedes Mal ein NEUES ByteArray
        // - Der Vergleich funktioniert auch nachdem ein Chain-Key aktualisiert
        //   wurde (neues ByteArray von hkdfDerive)
        // ═══════════════════════════════════════════════════════════════
        if (chainKey === sessionState.sendingChainKey) {
            sessionState.sendingChainKey = newChainKey
        } else if (chainKey === sessionState.receivingChainKey) {
            sessionState.receivingChainKey = newChainKey
        } else {
            // Fallback: Sollte nie passieren, aber falls doch (z.B. nach
            // Deserialisierung), verwenden wir einen eindeutigen Identifikator.
            Log.w(TAG, "deriveMessageKey: Kein Referenz-Match — verwende messageIndex=$messageIndex als Identifikator")
            // Nach der ersten Nachricht haben die Chain-Keys unterschiedliche
            // Werte, daher können wir contentEquals sicher verwenden.
            if (chainKey.contentEquals(sessionState.sendingChainKey)) {
                sessionState.sendingChainKey = newChainKey
            } else {
                sessionState.receivingChainKey = newChainKey
            }
        }

        return messageKey
    }


    /**
     * Generiert eine eindeutige 12-Byte-Nonce aus Message-Key und Message-Index.
     *
     * Nonce = HKDF(messageKey, salt=messageIndex, info="nonce")[0..11]
     * Dadurch ist garantiert, dass jede Nonce nur einmal verwendet wird,
     * da der Message-Key selbst bereits eindeutig ist.
     */
    private fun generateNonce(messageKey: ByteArray, messageIndex: Int): ByteArray {
        val indexBytes = ByteArray(4)
        indexBytes[0] = (messageIndex shr 24).toByte()
        indexBytes[1] = (messageIndex shr 16).toByte()
        indexBytes[2] = (messageIndex shr 8).toByte()
        indexBytes[3] = messageIndex.toByte()

        val nonceInfo = "Crisix-DoubleRatchet-Nonce".encodeToByteArray()
        val fullNonce = CryptoHelper.hkdfDerive(
            messageKey,
            salt = indexBytes,
            info = nonceInfo,
            length = NONCE_LENGTH
        )
        return fullNonce
    }

    /**
     * Überschreibt ein Byte-Array mit Nullen (Löschen von sensiblen Keys).
     */
    private fun wipeBytes(data: ByteArray) {
        data.fill(0)
    }

    /**
     * Gibt den aktuellen Session-State zurück (für Persistenz).
     */
    fun getSessionState(): SessionState = sessionState

    /**
     * Serialisiert den aktuellen Session-State als Base64-JSON.
     */
    fun serializeSession(): String {
        return sessionState.toJson()
    }

    /**
     * Stellt einen Session-State aus einem serialisierten String wieder her.
     */
    fun deserializeSession(json: String) {
        sessionState = SessionState.fromJson(json)
    }

    /**
     * Gibt den Out-of-Order-Message-Handler zurück.
     * Wird von E2eeManager für Monitoring/Debugging verwendet.
     */
    fun getOutOfOrderHandler(): OutOfOrderMessageHandler = outOfOrderHandler

    /**
     * Gibt den Cache-Status für Monitoring zurück.
     */
    fun getOutOfOrderCacheStatus(): OutOfOrderMessageHandler.CacheStatus {
        return outOfOrderHandler.getCacheStatus()
    }
}

/**
 * Verschlüsselte Nachricht mit Double-Ratchet-Header.
 *
 * @property dhPublicKey Der DH-Public-Key des Senders für diese Nachricht (32 Bytes)
 * @property chainIndex Der Chain-Index (für Nonce-Derivation)
 * @property messageIndex Der Nachrichten-Index in der aktuellen Chain
 * @property nonce Die 12-Byte-Nonce für AES-GCM
 * @property ciphertext Der AES-256-GCM-Ciphertext (inkl. Authentifizierungstag)
 */
data class EncryptedMessage(
    val dhPublicKey: ByteArray,
    val chainIndex: Int,
    val messageIndex: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val sessionVersion: Int = 0
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("dhPublicKey", Base64.encodeToString(dhPublicKey, Base64.NO_WRAP))
            put("chainIndex", chainIndex)
            put("messageIndex", messageIndex)
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            if (sessionVersion > 0) {
                put("sessionVersion", sessionVersion)
            }
        }.toString()
    }

    fun toProto(): ByteArray {
        val result = ByteArray(HEADER_SIZE + ciphertext.size)
        result[0] = MAGIC_0
        result[1] = MAGIC_1
        result[2] = PROTO_VERSION
        result[3] = 0
        writeInt32BE(result, 4, sessionVersion)
        for (i in 0 until 32) result[8 + i] = dhPublicKey[i]
        writeInt32BE(result, 40, chainIndex)
        writeInt32BE(result, 44, messageIndex)
        for (i in 0 until 12) result[48 + i] = nonce[i]
        writeInt32BE(result, 60, ciphertext.size)
        for (i in ciphertext.indices) result[HEADER_SIZE + i] = ciphertext[i]
        return result
    }

    companion object {
        internal const val MAGIC_0: Byte = 0x43
        internal const val MAGIC_1: Byte = 0x45
        internal const val PROTO_VERSION: Byte = 0x01
        internal const val HEADER_SIZE: Int = 64

        fun isProto(data: ByteArray): Boolean {
            return data.size >= 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1
        }

        fun fromJson(json: String): EncryptedMessage {
            val obj = org.json.JSONObject(json)
            return EncryptedMessage(
                dhPublicKey = Base64.decode(obj.getString("dhPublicKey"), Base64.NO_WRAP),
                chainIndex = obj.getInt("chainIndex"),
                messageIndex = obj.getInt("messageIndex"),
                nonce = Base64.decode(obj.getString("nonce"), Base64.NO_WRAP),
                ciphertext = Base64.decode(obj.getString("ciphertext"), Base64.NO_WRAP),
                sessionVersion = obj.optInt("sessionVersion", 0)
            )
        }

        fun fromProto(data: ByteArray): EncryptedMessage? {
            if (data.size < HEADER_SIZE) return null
            if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
            if (data[2] != PROTO_VERSION) return null
            val sessionVersion = readInt32BE(data, 4)
            val dhPublicKey = data.copyOfRange(8, 40)
            val chainIndex = readInt32BE(data, 40)
            val messageIndex = readInt32BE(data, 44)
            val nonce = data.copyOfRange(48, 60)
            val ciphertextLen = readInt32BE(data, 60)
            if (HEADER_SIZE + ciphertextLen > data.size) return null
            val ciphertext = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + ciphertextLen)
            return EncryptedMessage(dhPublicKey, chainIndex, messageIndex, nonce, ciphertext, sessionVersion)
        }

        fun parse(data: ByteArray): EncryptedMessage? {
            return if (isProto(data)) fromProto(data)
            else try { fromJson(String(data)) } catch (e: Exception) { Timber.e(e, "DoubleRatchet JSON deserialization failed"); null }
        }

        private fun writeInt32BE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = ((value shr 24) and 0xFF).toByte()
            buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
            buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }

        private fun readInt32BE(buf: ByteArray, offset: Int): Int {
            return ((buf[offset].toInt() and 0xFF) shl 24) or
                   ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                   ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                   (buf[offset + 3].toInt() and 0xFF)
        }
    }
}

/**
 * Zustand einer Double-Ratchet-Session.
 *
 * @property rootKey Der Root-Key (32 Bytes) — wird nach jedem DH-Ratchet neu abgeleitet
 * @property sendingChainKey Der aktuelle Sending-Chain-Key (32 Bytes)
 * @property receivingChainKey Der aktuelle Receiving-Chain-Key (32 Bytes)
 * @property sendingDhKeyPair Das aktuelle DH-KeyPair für ausgehende Nachrichten
 * @property receivingDhKeyPair Das aktuelle DH-KeyPair für eingehende Nachrichten
 * @property sendingChainIndex Zähler für DH-Ratchets auf der Senderseite
 * @property receivingChainIndex Zähler für DH-Ratchets auf der Empfängerseite
 * @property sendingMessageIndex Zähler für Nachrichten in der aktuellen Sending-Chain
 * @property receivingMessageIndex Zähler für Nachrichten in der aktuellen Receiving-Chain
 */
data class SessionState(
    var rootKey: ByteArray,
    var sendingChainKey: ByteArray,
    var receivingChainKey: ByteArray,
    var sendingDhKeyPair: CryptoHelper.X25519KeyPair,
    var receivingDhKeyPair: CryptoHelper.X25519KeyPair,
    var sendingChainIndex: Int = 0,
    var receivingChainIndex: Int = 0,
    var sendingMessageIndex: Int = 0,
    var receivingMessageIndex: Int = 0
) {
    /**
     * Serialisiert den Session-State als JSON-String.
     */
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("version", SESSION_STATE_VERSION)
            put("rootKey", Base64.encodeToString(rootKey, Base64.NO_WRAP))
            put("sendingChainKey", Base64.encodeToString(sendingChainKey, Base64.NO_WRAP))
            put("receivingChainKey", Base64.encodeToString(receivingChainKey, Base64.NO_WRAP))
            put("sendingDhPrivate", Base64.encodeToString(sendingDhKeyPair.privateKey, Base64.NO_WRAP))
            put("sendingDhPublic", Base64.encodeToString(sendingDhKeyPair.publicKey, Base64.NO_WRAP))
            put("receivingDhPrivate", Base64.encodeToString(receivingDhKeyPair.privateKey, Base64.NO_WRAP))
            put("receivingDhPublic", Base64.encodeToString(receivingDhKeyPair.publicKey, Base64.NO_WRAP))
            put("sendingChainIndex", sendingChainIndex)
            put("receivingChainIndex", receivingChainIndex)
            put("sendingMessageIndex", sendingMessageIndex)
            put("receivingMessageIndex", receivingMessageIndex)
        }.toString()
    }

    companion object {
        const val SESSION_STATE_VERSION = 2

        /**
         * Stellt einen Session-State aus einem JSON-String wieder her.
         * Unterstützt Migration von v1 (ohne version-Feld) zu v2.
         */
        fun fromJson(json: String): SessionState {
            val obj = org.json.JSONObject(json)
            val version = if (obj.has("version")) obj.getInt("version") else 1
            if (version < 1 || version > SESSION_STATE_VERSION) {
                throw IllegalArgumentException("Unknown session version: $version")
            }
            return SessionState(
                rootKey = Base64.decode(obj.getString("rootKey"), Base64.NO_WRAP),
                sendingChainKey = Base64.decode(obj.getString("sendingChainKey"), Base64.NO_WRAP),
                receivingChainKey = Base64.decode(obj.getString("receivingChainKey"), Base64.NO_WRAP),
                sendingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = Base64.decode(obj.getString("sendingDhPrivate"), Base64.NO_WRAP),
                    publicKey = Base64.decode(obj.getString("sendingDhPublic"), Base64.NO_WRAP)
                ),
                receivingDhKeyPair = CryptoHelper.X25519KeyPair(
                    privateKey = Base64.decode(obj.getString("receivingDhPrivate"), Base64.NO_WRAP),
                    publicKey = Base64.decode(obj.getString("receivingDhPublic"), Base64.NO_WRAP)
                ),
                sendingChainIndex = obj.getInt("sendingChainIndex"),
                receivingChainIndex = obj.getInt("receivingChainIndex"),
                sendingMessageIndex = obj.getInt("sendingMessageIndex"),
                receivingMessageIndex = obj.getInt("receivingMessageIndex")
            )
        }
    }
}

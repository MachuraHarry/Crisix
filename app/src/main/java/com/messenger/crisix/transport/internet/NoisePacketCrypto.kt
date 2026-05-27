package com.messenger.crisix.transport.internet

import android.util.Log
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * Per-packet Noise-Verschlüsselung für das Hyperswarm-Protokoll.
 *
 * ## Noise-Protokoll: `Noise_XX_25519_ChaChaPoly_SHA256`
 *
 * Dieses Protokoll implementiert den Noise-Protokoll-Handshake und die
 * per-packet Verschlüsselung für das Crisix-P2P-Netzwerk.
 *
 * ### Noise XX (Doppel-X)
 * - **X** = statischer Schlüssel wird übertragen
 * - **XX** = Beide Seiten übertragen ihre statischen Schlüssel
 * - Bietet gegenseitige Authentifizierung
 *
 * ### Kryptografische Primitive
 * - **DH**: Curve25519 (X25519) für Schlüsselaustausch
 * - **Cipher**: ChaCha20-Poly1305 für authenticated encryption
 * - **Hash**: SHA-256 für Schlüsselableitung
 *
 * ### Ablauf
 * 1. **Handshake-Phase**: Beide Seiten führen XX-Handshake durch
 * 2. **Transport-Phase**: Nachrichten werden mit ChaCha20-Poly1305 verschlüsselt
 *
 * ## Verwendung
 * ```kotlin
 * val alice = NoisePacketCrypto(localStaticPrivate, localStaticPublic)
 * val bob = NoisePacketCrypto(localStaticPrivate, localStaticPublic)
 *
 * // Handshake
 * val msg1 = alice.generateHandshakeMessage(null)
 * val msg2 = bob.processHandshakeMessage(msg1, remoteStaticPublic)
 * val msg3 = alice.processHandshakeMessage(msg2, remoteStaticPublic)
 * bob.processHandshakeMessage(msg3, remoteStaticPublic)
 *
 * // Verschlüsselte Kommunikation
 * val encrypted = alice.encrypt("Hallo".toByteArray())
 * val decrypted = bob.decrypt(encrypted)
 * ```
 */
class NoisePacketCrypto(
    private val localStaticPrivate: ByteArray,
    private val localStaticPublic: ByteArray
) {
    companion object {
        private const val TAG = "NoisePacketCrypto"

        /** Noise-Protokoll-Name */
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"

        /** Länge des SHA-256-Hashes in Bytes */
        private const val HASH_LEN = 32

        /** Länge des ChaCha20-Poly1305-Schlüssels in Bytes */
        private const val KEY_LEN = 32

        /** Länge der Nonce für ChaCha20-Poly1305 in Bytes */
        private const val NONCE_LEN = 12

        /** Länge des Poly1305-Authentifizierungstags in Bytes */
        private const val TAG_LEN = 16

        /** Länge einer X25519 Public Key in Bytes */
        private const val DH_LEN = 32

        /** Länge einer X25519 Shared Secret in Bytes */
        private const val SHARED_SECRET_LEN = 32

        /** Maximale Nachrichtenlänge für Handshake-Nachrichten */
        private const val MAX_HANDSHAKE_MSG_LEN = 256

        /** Handshake-Protokoll-Identifier (32 Bytes) */
        private val PROTOCOL_ID = PROTOCOL_NAME.toByteArray(Charsets.UTF_8)
    }

    // =========================================================================
    // Noise-Protokoll-Zustand
    // =========================================================================

    /** Handshake-Hash (h) – kumulativer Hash aller bisherigen Nachrichten */
    private var h: ByteArray = ByteArray(HASH_LEN)

    /** Chaining Key (ck) – wird zur Schlüsselableitung verwendet */
    private var ck: ByteArray = ByteArray(HASH_LEN)

    /** Empfangsschlüssel (rk) – für Entschlüsselung */
    private var rk: ByteArray? = null

    /** Sendeschlüssel (sk) – für Verschlüsselung */
    private var sk: ByteArray? = null

    /** Nonce für Senden */
    private var sendNonce: Long = 0

    /** Nonce für Empfangen */
    private var recvNonce: Long = 0

    /** Ephemeraler privater Schlüssel (für Handshake) */
    private var ePrivate: ByteArray? = null

    /** Ephemeraler öffentlicher Schlüssel (für Handshake) */
    private var ePublic: ByteArray? = null

    /** Öffentlicher Schlüssel des entfernten Peers (nach Handshake) */
    private var remoteStaticPublic: ByteArray? = null

    /** Gibt an, ob der Handshake abgeschlossen ist */
    @Volatile
    var isHandshakeComplete: Boolean = false
        private set

    /** Aktuelle Handshake-Phase (0-3) */
    @Volatile
    var handshakePhase: Int = 0
        private set

    /** Gibt an, ob diese Seite die Initiator-Seite ist */
    @Volatile
    var isInitiator: Boolean = false
        private set

    init {
        // Protokoll-Hash initialisieren
        initializeProtocol(PROTOCOL_ID)
    }

    // =========================================================================
    // Initialisierung
    // =========================================================================

    /**
     * Initialisiert das Noise-Protokoll mit dem Protokoll-Identifier.
     *
     * @param protocolName Der Protokoll-Name als Byte-Array
     */
    private fun initializeProtocol(protocolName: ByteArray) {
        // h = SHA-256(protocol_name)
        h = sha256(protocolName)

        // ck = h
        ck = h.copyOf()
    }

    // =========================================================================
    // Öffentliche API
    // =========================================================================

    /**
     * Generiert die erste Handshake-Nachricht (als Initiator).
     *
     * Noise XX:
     *   -> e
     *   <- e, ee, s, es
     *   -> s, se
     *
     * Phase 0 (Initiator): Sende ephemeralen Public Key
     *
     * @return Die erste Handshake-Nachricht (nur der ephemerale Public Key)
     */
    fun generateHandshakeMessage(): ByteArray {
        isInitiator = true
        handshakePhase = 0

        // Ephemerales Schlüsselpaar generieren
        val (epriv, epub) = generateKeyPair()
        ePrivate = epriv
        ePublic = epub

        // h = SHA-256(h || e.public_key)
        h = sha256(h + epub)

        // Nachricht: nur der ephemerale Public Key (32 Bytes)
        handshakePhase = 1
        return epub
    }

    /**
     * Verarbeitet eine eingehende Handshake-Nachricht.
     *
     * @param message Die empfangene Handshake-Nachricht
     * @param expectedRemoteStatic Der erwartete statische Public Key des Peers (optional)
     * @return Die Antwort-Nachricht, oder null wenn Handshake abgeschlossen
     */
    fun processHandshakeMessage(
        message: ByteArray,
        expectedRemoteStatic: ByteArray? = null
    ): ByteArray? {
        return when (handshakePhase) {
            0 -> processPhase0(message)  // Empfange e (als Responder)
            1 -> processPhase1(message, expectedRemoteStatic)  // Empfange e, ee, s, es
            2 -> processPhase2(message)  // Empfange s, se
            else -> {
                Log.w(TAG, "Handshake bereits abgeschlossen oder ungültige Phase")
                null
            }
        }
    }

    /**
     * Verschlüsselt eine Nachricht für den Transport.
     *
     * @param plaintext Die zu verschlüsselnden Klartext-Daten
     * @return Das verschlüsselte Paket (Nonce + Ciphertext + Tag)
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (!isHandshakeComplete) {
            throw IllegalStateException("Handshake noch nicht abgeschlossen")
        }

        val key = sk ?: throw IllegalStateException("Kein Sendeschlüssel vorhanden")
        return encryptWithKey(key, sendNonce++, plaintext)
    }

    /**
     * Entschlüsselt eine empfangene Nachricht.
     *
     * @param ciphertext Das verschlüsselte Paket (Nonce + Ciphertext + Tag)
     * @return Der entschlüsselte Klartext
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        if (!isHandshakeComplete) {
            throw IllegalStateException("Handshake noch nicht abgeschlossen")
        }

        val key = rk ?: throw IllegalStateException("Kein Empfangsschlüssel vorhanden")
        return decryptWithKey(key, recvNonce++, ciphertext)
    }

    /**
     * Gibt den öffentlichen Schlüssel des entfernten Peers zurück.
     */
    fun getRemoteStaticPublic(): ByteArray? = remoteStaticPublic

    // =========================================================================
    // Handshake-Phasen
    // =========================================================================

    /**
     * Phase 0 (Responder): Empfange ephemeralen Public Key vom Initiator.
     *
     * Noise XX Schritt 1 (Responder):
     *   <- e
     *
     * Antwort (Responder):
     *   -> e, ee, s, es
     */
    private fun processPhase0(message: ByteArray): ByteArray {
        Log.d(TAG, "Phase 0: Empfange ephemeralen Public Key (${message.size} Bytes)")

        // e = message (ephemeraler Public Key des Initiators)
        val re = message

        // h = SHA-256(h || re)
        h = sha256(h + re)

        // Eigenes ephemerales Schlüsselpaar generieren
        val (epriv, epub) = generateKeyPair()
        ePrivate = epriv
        ePublic = epub

        // h = SHA-256(h || e.public_key)
        h = sha256(h + epub)

        // ee = DH(e.private, re)
        val ee = dh(ePrivate!!, re)

        // ck, temp_k1 = HKDF(ck, ee, 2)
        val (newCk, tempK1) = hkdf2(ck, ee)
        ck = newCk

        // s = verschlüsselt(static_public_key)
        val encryptedS = encryptWithKey(tempK1, 0, localStaticPublic)

        // h = SHA-256(h || encrypted_s)
        h = sha256(h + encryptedS)

        // es = DH(s.private, re)
        val es = dh(localStaticPrivate, re)

        // ck, temp_k2 = HKDF(ck, es, 2)
        val (newCk2, tempK2) = hkdf2(ck, es)
        ck = newCk2

        // Antwort-Nachricht: e.public (32) + encrypted_s (32+16=48) + tag
        val response = epub + encryptedS

        // rk = temp_k2
        rk = tempK2

        handshakePhase = 2
        return response
    }

    /**
     * Phase 1 (Initiator): Empfange e, ee, s, es vom Responder.
     *
     * Noise XX Schritt 2 (Initiator):
     *   <- e, ee, s, es
     *
     * Antwort (Initiator):
     *   -> s, se
     */
    private fun processPhase1(
        message: ByteArray,
        expectedRemoteStatic: ByteArray?
    ): ByteArray {
        Log.d(TAG, "Phase 1: Empfange Antwort (${message.size} Bytes)")

        // Parse: e.public (32) + encrypted_s (48)
        val re = message.copyOfRange(0, DH_LEN)
        val encryptedRemoteS = message.copyOfRange(DH_LEN, message.size)

        // h = SHA-256(h || re)
        h = sha256(h + re)

        // ee = DH(e.private, re)
        val ee = dh(ePrivate!!, re)

        // ck, temp_k1 = HKDF(ck, ee, 2)
        val (newCk, tempK1) = hkdf2(ck, ee)
        ck = newCk

        // s = decrypt(encrypted_s)
        val remoteStatic = decryptWithKey(tempK1, 0, encryptedRemoteS)

        // h = SHA-256(h || encrypted_s)
        h = sha256(h + encryptedRemoteS)

        // Prüfe, ob der entfernte Peer der erwartete ist
        if (expectedRemoteStatic != null && !expectedRemoteStatic.contentEquals(remoteStatic)) {
            Log.w(TAG, "Öffentlicher Schlüssel des Peers stimmt nicht mit erwartetem überein")
            throw SecurityException("Peer-Authentifizierung fehlgeschlagen")
        }

        remoteStaticPublic = remoteStatic

        // es = DH(s.private, re)
        val es = dh(localStaticPrivate, re)

        // ck, temp_k2 = HKDF(ck, es, 2)
        val (newCk2, tempK2) = hkdf2(ck, es)
        ck = newCk2

        // s = verschlüsselt(static_public_key)
        val encryptedS = encryptWithKey(tempK2, 0, localStaticPublic)

        // h = SHA-256(h || encrypted_s)
        h = sha256(h + encryptedS)

        // se = DH(s.private, re)
        val se = dh(localStaticPrivate, re)

        // ck, sk, rk = HKDF(ck, se, 3)
        val (finalCk, sendKey, recvKey) = hkdf3(ck, se)
        ck = finalCk
        sk = sendKey
        rk = recvKey

        // Antwort: encrypted_s (48 Bytes)
        val response = encryptedS

        isHandshakeComplete = true
        handshakePhase = 3
        return response
    }

    /**
     * Phase 2 (Responder): Empfange s, se vom Initiator.
     *
     * Noise XX Schritt 3 (Responder):
     *   <- s, se
     */
    private fun processPhase2(message: ByteArray): ByteArray? {
        Log.d(TAG, "Phase 2: Empfange finale Nachricht (${message.size} Bytes)")

        // Parse: encrypted_s (48 Bytes)
        val encryptedRemoteS = message

        // s = decrypt(encrypted_s)
        val remoteStatic = decryptWithKey(rk!!, 0, encryptedRemoteS)

        // h = SHA-256(h || encrypted_s)
        h = sha256(h + encryptedRemoteS)

        remoteStaticPublic = remoteStatic

        // se = DH(s.private, re)
        val se = dh(localStaticPrivate, remoteStatic)

        // ck, sk, rk = HKDF(ck, se, 3)
        val (finalCk, sendKey, recvKey) = hkdf3(ck, se)
        ck = finalCk
        sk = sendKey
        rk = recvKey

        isHandshakeComplete = true
        handshakePhase = 3
        return null // Keine Antwort nötig
    }

    // =========================================================================
    // Kryptografische Primitive
    // =========================================================================

    /**
     * Führt eine X25519-Schlüsselvereinbarung durch.
     *
     * @param privateKey Der private Schlüssel (32 Bytes)
     * @param publicKey Der öffentliche Schlüssel (32 Bytes)
     * @return Das Shared Secret (32 Bytes)
     */
    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val result = ByteArray(32)
        X25519.scalarMult(privateKey, 0, publicKey, 0, result, 0)
        return result
    }

    /**
     * SHA-256-Hashfunktion.
     */
    private fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val result = ByteArray(HASH_LEN)
        digest.doFinal(result, 0)
        return result
    }

    /**
     * HMAC-SHA256.
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val result = ByteArray(HASH_LEN)
        hmac.doFinal(result, 0)
        return result
    }

    /**
     * HKDF mit 2 Ausgabeschlüsseln (für Noise).
     *
     * @param chainingKey Der Chaining Key
     * @param inputKeyMaterial Das Eingabe-Schlüsselmaterial
     * @return Pair(neuer_chaining_key, temp_key)
     */
    private fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
        // temp_key = HMAC-SHA256(ck, ikm || 0x01)
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial + byteArrayOf(0x01.toByte()))

        // output1 = HMAC-SHA256(temp_key, ck || 0x02)
        val output1 = hmacSha256(tempKey, chainingKey + byteArrayOf(0x02.toByte()))

        // output2 = HMAC-SHA256(temp_key, output1 || 0x03)
        val output2 = hmacSha256(tempKey, output1 + byteArrayOf(0x03.toByte()))

        return Pair(output1, output2)
    }

    /**
     * HKDF mit 3 Ausgabeschlüsseln (für Noise).
     *
     * @param chainingKey Der Chaining Key
     * @param inputKeyMaterial Das Eingabe-Schlüsselmaterial
     * @return Triple(neuer_chaining_key, send_key, recv_key)
     */
    private fun hkdf3(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        // temp_key = HMAC-SHA256(ck, ikm || 0x01)
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial + byteArrayOf(0x01.toByte()))

        // output1 = HMAC-SHA256(temp_key, ck || 0x02)
        val output1 = hmacSha256(tempKey, chainingKey + byteArrayOf(0x02.toByte()))

        // output2 = HMAC-SHA256(temp_key, output1 || 0x03)
        val output2 = hmacSha256(tempKey, output1 + byteArrayOf(0x03.toByte()))

        // output3 = HMAC-SHA256(temp_key, output2 || 0x04)
        val output3 = hmacSha256(tempKey, output2 + byteArrayOf(0x04.toByte()))

        return Triple(output1, output2, output3)
    }

    /**
     * Verschlüsselt mit ChaCha20-Poly1305.
     *
     * @param key Der 32-Byte-Schlüssel
     * @param nonce Die 8-Byte-Nonce (wird auf 12 Bytes erweitert)
     * @param plaintext Der Klartext
     * @return Nonce (8) + Ciphertext + Tag (16)
     */
    private fun encryptWithKey(key: ByteArray, nonce: Long, plaintext: ByteArray): ByteArray {
        val nonceBytes = ByteArray(12)
        for (i in 0..7) {
            nonceBytes[4 + i] = ((nonce shr (i * 8)) and 0xFF).toByte()
        }

        val aead = ChaCha20Poly1305()
        aead.init(true, AEADParameters(KeyParameter(key), 128, nonceBytes))

        val out = ByteArray(aead.getOutputSize(plaintext.size))
        val len = aead.processBytes(plaintext, 0, plaintext.size, out, 0)
        aead.doFinal(out, len)
        return out
    }

    /**
     * Entschlüsselt mit ChaCha20-Poly1305.
     *
     * @param key Der 32-Byte-Schlüssel
     * @param nonce Die 8-Byte-Nonce
     * @param data Nonce (8) + Ciphertext + Tag (16)
     * @return Der Klartext
     */
    private fun decryptWithKey(key: ByteArray, nonce: Long, data: ByteArray): ByteArray {
        val nonceBytes = ByteArray(12)
        for (i in 0..7) {
            nonceBytes[4 + i] = ((nonce shr (i * 8)) and 0xFF).toByte()
        }

        val aead = ChaCha20Poly1305()
        aead.init(false, AEADParameters(KeyParameter(key), 128, nonceBytes))

        val out = ByteArray(aead.getOutputSize(data.size))
        val len = aead.processBytes(data, 0, data.size, out, 0)
        aead.doFinal(out, len)
        return if (len < out.size) out.copyOf(len) else out
    }

    // =========================================================================
    // Schlüsselgenerierung
    // =========================================================================

    /**
     * Generiert ein X25519-Schlüsselpaar.
     *
     * @return Pair(private, public)
     */
    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = ((privateKey[31].toInt() and 127) or 64).toByte()

        val publicKey = ByteArray(32)
        X25519.scalarMultBase(privateKey, 0, publicKey, 0)

        return Pair(privateKey, publicKey)
    }

}

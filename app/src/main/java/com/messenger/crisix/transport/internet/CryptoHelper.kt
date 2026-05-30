package com.messenger.crisix.transport.internet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hilfsklasse für kryptografische Operationen im Crisix-P2P-Netzwerk.
 *
 * Bietet Funktionen zur:
 * - Generierung von libp2p-kompatiblen Ed25519-Schlüsselpaaren
 * - Serialisierung/Deserialisierung von Schlüsseln
 * - Sicheren Schlüsselspeicherung im Android Keystore
 * - Fingerprint-Erzeugung für Peer-Identifikation
 * - Signierung und Verifikation von Nachrichten
 *
 * ## Sicherheitshinweise
 * - Private Schlüssel sollten NIEMALS das Gerät verlassen
 * - Der Android Keystore bietet hardwaregestützte Sicherheit (TEE/StrongBox)
 * - Fingerprints dienen nur der Identifikation, nicht der Authentifizierung
 *
 * ## Verwendung
 * ```kotlin
 * val keyPair = CryptoHelper.generateKeyPair()
 * val fingerprint = CryptoHelper.publicKeyToFingerprint(keyPair.public.encoded)
 * CryptoHelper.saveToAndroidKeyStore("my_key", keyPair)
 * ```
 */
object CryptoHelper {

    private const val TAG = "CryptoHelper"

    /** Name des Android Keystore-Providers */
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Algorithmus für die Keystore-Verschlüsselung */
    private const val KEYSTORE_ALGORITHM = "AES"

    /** Transformation für AES-GCM-Verschlüsselung */
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

    /** Länge des GCM-Initialisierungsvektors in Bytes */
    private const val GCM_IV_LENGTH = 12

    /** Länge des GCM-Authentifizierungstags in Bits */
    private const val GCM_TAG_LENGTH = 128

    /** Ed25519 privater Schlüssel hat 64 Bytes (32 Bytes Seed + 32 Bytes Public Key) */
    private const val ED25519_PRIVATE_KEY_LENGTH = 64

    /** Ed25519 öffentlicher Schlüssel hat 32 Bytes */
    private const val ED25519_PUBLIC_KEY_LENGTH = 32

    /**
     * Datenklasse, die ein libp2p-kompatibles Schlüsselpaar repräsentiert.
     *
     * @property privateKey Die rohen Bytes des privaten Schlüssels (64 Bytes)
     * @property publicKey Die rohen Bytes des öffentlichen Schlüssels (32 Bytes)
     */
    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return privateKey.contentEquals(other.privateKey) &&
                    publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = privateKey.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    /**
     * Generiert ein neues libp2p-kompatibles Ed25519-Schlüsselpaar.
     *
     * Ed25519 wird verwendet, weil es:
     * - Sehr schnelle Signatur- und Verifikationsoperationen bietet
     * - Kurze Schlüssellängen hat (32 Byte öffentlich, 64 Byte privat)
     * - Von libp2p nativ unterstützt wird
     * - Widerstandsfähig gegen Seitenkanalangriffe ist
     *
     * @return Ein KeyPair mit privatem und öffentlichem Schlüssel
     */
    fun generateKeyPair(): KeyPair {
        Log.d(TAG, "Generiere neues Ed25519-Schlüsselpaar")

        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()

        val privateKeyParams = keyPair.private as Ed25519PrivateKeyParameters
        val publicKeyParams = keyPair.public as Ed25519PublicKeyParameters

        // Bouncy Castle's encoded private key is 32 bytes (seed only).
        // For libp2p compatibility, we store 64 bytes: seed (32) + public key (32)
        val seed = privateKeyParams.encoded
        val pubKey = publicKeyParams.encoded
        val fullPrivateKey = seed + pubKey

        return KeyPair(
            privateKey = fullPrivateKey,
            publicKey = pubKey
        )
    }

    /**
     * Konvertiert ein Schlüsselpaar in ein Byte-Array für die Speicherung.
     *
     * Format: [publicKey (32 Bytes)] [privateKey (64 Bytes)]
     *
     * @param keyPair Das zu serialisierende Schlüsselpaar
     * @return Die serialisierten Bytes
     */
    fun keyPairToBytes(keyPair: KeyPair): ByteArray {
        return keyPair.publicKey + keyPair.privateKey
    }

    /**
     * Stellt ein Schlüsselpaar aus seinen serialisierten Bytes wieder her.
     *
     * @param bytes Die serialisierten Bytes (96 Bytes: 32 public + 64 private)
     * @return Das wiederhergestellte KeyPair
     * @throws IllegalArgumentException Wenn die Bytes ungültig sind
     */
    fun keyPairFromBytes(bytes: ByteArray): KeyPair {
        require(bytes.size == ED25519_PRIVATE_KEY_LENGTH + ED25519_PUBLIC_KEY_LENGTH) {
            "Ungültige Schlüssellänge: ${bytes.size} Bytes (erwartet 96)"
        }
        val publicKey = bytes.copyOfRange(0, ED25519_PUBLIC_KEY_LENGTH)
        val privateKey = bytes.copyOfRange(
            ED25519_PUBLIC_KEY_LENGTH,
            ED25519_PUBLIC_KEY_LENGTH + ED25519_PRIVATE_KEY_LENGTH
        )
        return KeyPair(privateKey = privateKey, publicKey = publicKey)
    }

    /**
     * Erzeugt einen menschenlesbaren Fingerprint aus einem öffentlichen Schlüssel.
     *
     * Der Fingerprint wird als Hex-kodierter SHA-256-Hash des öffentlichen Schlüssels erstellt.
     * Dies ist ein gängiges Format für Peer-Identifikation.
     *
     * @param publicKey Die rohen Bytes des öffentlichen Schlüssels
     * @return Ein Hex-kodierter Fingerprint-String
     */
    fun publicKeyToFingerprint(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Speichert ein Schlüsselpaar sicher im Android Keystore.
     *
     * Der private Schlüssel wird mit einem AES-GCM-Schlüssel verschlüsselt,
     * der im Android Keystore (hardwaregestützt) gespeichert wird.
     * IV + Ciphertext werden in SharedPreferences persistiert.
     *
     * @param alias Der Alias, unter dem der Schlüssel gespeichert werden soll
     * @param keyPair Das zu speichernde Schlüsselpaar
     * @param context Der Application Context für SharedPreferences
     * @return true bei Erfolg, false bei Fehlschlag
     */
    fun saveToAndroidKeyStore(alias: String, keyPair: KeyPair, context: android.content.Context): Boolean {
        return try {
            val keyBytes = keyPairToBytes(keyPair)
            val secretKey = getOrCreateKeystoreKey(alias)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedKey = cipher.doFinal(keyBytes)

            val prefs = context.getSharedPreferences("crisix_keystore", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString(alias, Base64.encodeToString(iv + encryptedKey, Base64.NO_WRAP))
                .apply()

            Log.i(TAG, "Schlüssel '$alias' sicher im Android KeyStore gespeichert")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Speichern des Schlüssels '$alias': ${e.message}", e)
            false
        }
    }

    /**
     * Lädt ein Schlüsselpaar aus dem Android Keystore.
     *
     * @param alias Der Alias, unter dem der Schlüssel gespeichert ist
     * @param context Der Application Context für SharedPreferences
     * @return Das geladene KeyPair, oder null wenn nicht gefunden
     */
    fun loadFromAndroidKeyStore(alias: String, context: android.content.Context): KeyPair? {
        return try {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keystore.load(null)

            if (!keystore.containsAlias(alias)) {
                Log.w(TAG, "AES-Schlüssel '$alias' nicht im Android KeyStore")
                return null
            }

            val secretKey = keystore.getKey(alias, null) as? SecretKey
                ?: return null

            val prefs = context.getSharedPreferences("crisix_keystore", android.content.Context.MODE_PRIVATE)
            val encoded = prefs.getString(alias, null) ?: return null

            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = decoded.copyOfRange(0, GCM_IV_LENGTH)
            val encryptedKey = decoded.copyOfRange(GCM_IV_LENGTH, decoded.size)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val keyBytes = cipher.doFinal(encryptedKey)

            val keyPair = keyPairFromBytes(keyBytes)
            Log.i(TAG, "Schlüssel '$alias' aus Android KeyStore geladen")
            keyPair
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Laden des Schlüssels '$alias': ${e.message}", e)
            null
        }
    }

    /**
     * Löscht einen Schlüssel aus dem Android Keystore.
     *
     * @param alias Der Alias des zu löschenden Schlüssels
     * @return true bei Erfolg, false bei Fehlschlag
     */
    fun deleteFromAndroidKeyStore(alias: String): Boolean {
        return try {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keystore.load(null)
            keystore.deleteEntry(alias)
            Log.i(TAG, "Schlüssel '$alias' aus dem Keystore gelöscht")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Löschen des Schlüssels '$alias': ${e.message}", e)
            false
        }
    }

    /**
     * Signiert Daten mit einem Ed25519-Private-Key.
     *
     * @param data Die zu signierenden Daten
     * @param privateKey Der 64-Byte-Ed25519-Private-Key (Seed + Public)
     * @return Die 64-Byte-Ed25519-Signatur
     */
    fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        require(privateKey.size == ED25519_PRIVATE_KEY_LENGTH) {
            "Private Key muss $ED25519_PRIVATE_KEY_LENGTH Bytes haben, hat ${privateKey.size}"
        }
        val privParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verifiziert eine Ed25519-Signatur.
     *
     * @param data Die signierten Daten
     * @param signature Die 64-Byte-Signatur
     * @param publicKey Der 32-Byte-Ed25519-Public-Key
     * @return true wenn die Signatur gültig ist
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(publicKey.size == ED25519_PUBLIC_KEY_LENGTH) {
            "Public Key muss $ED25519_PUBLIC_KEY_LENGTH Bytes haben, hat ${publicKey.size}"
        }
        return try {
            val pubParams = Ed25519PublicKeyParameters(publicKey, 0)
            val signer = Ed25519Signer()
            signer.init(false, pubParams)
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Signatur-Verifikation fehlgeschlagen: ${e.message}")
            false
        }
    }

    /**
     * Erstellt oder lädt einen AES-GCM-Schlüssel aus dem Android Keystore.
     *
     * @param alias Der Alias für den Schlüssel
     * @return Der SecretKey für AES-GCM
     */
    private fun getOrCreateKeystoreKey(alias: String): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keystore.load(null)

        // Prüfen, ob bereits ein Schlüssel existiert
        if (keystore.containsAlias(alias)) {
            return keystore.getKey(alias, null) as SecretKey
        }

        // Neuen AES-Schlüssel generieren
        val keyGenerator = KeyGenerator.getInstance(
            KEYSTORE_ALGORITHM,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 1 — E2E-Verschlüsselung: X25519, AES-GCM, HKDF
    // ═══════════════════════════════════════════════════════════════

    /**
     * Datenklasse für ein X25519-Schlüsselpaar (Curve25519 DH).
     *
     * X25519 wird für den Diffie-Hellman-Schlüsselaustausch im Double-Ratchet-Protokoll
     * verwendet. Anders als Ed25519 (das für Signaturen optimiert ist) ist X25519 für
     * sicheren Schlüsselaustausch optimiert.
     *
     * @property privateKey 32 Bytes privater Schlüssel (sk)
     * @property publicKey 32 Bytes öffentlicher Schlüssel (pk)
     */
    data class X25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is X25519KeyPair) return false
            return privateKey.contentEquals(other.privateKey) &&
                    publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = privateKey.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    /** X25519-Schlüssellänge in Bytes (32 Bytes = 256 Bit) */
    private const val X25519_KEY_SIZE = 32

    /**
     * Generiert ein neues X25519-Schlüsselpaar für den DH-Schlüsselaustausch.
     *
     * Verwendet Bouncy Castles X25519-Implementierung (RFC 7748).
     * Der private Schlüssel wird mit einem kryptografisch sicheren Zufallsgenerator erstellt.
     *
     * @return Ein X25519KeyPair mit 32-Byte-Privat- und 32-Byte-Öffentlich-Schlüssel
     */
    fun generateX25519KeyPair(): X25519KeyPair {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()

        val privateParams = keyPair.private as X25519PrivateKeyParameters
        val publicParams = keyPair.public as X25519PublicKeyParameters

        return X25519KeyPair(
            privateKey = privateParams.encoded,
            publicKey = publicParams.encoded
        )
    }

    /**
     * Führt einen X25519-Diffie-Hellman-Schlüsselaustausch durch.
     *
     * Berechnet: sharedSecret = privateKey * publicKey (auf Curve25519)
     *
     * @param privateKey Eigener privater Schlüssel (32 Bytes)
     * @param publicKey  Öffentlicher Schlüssel des Peers (32 Bytes)
     * @return Das 32-Byte-Shared-Secret, oder null bei Fehler (z.B. Low-Order-Punkt)
     */
    fun x25519DH(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
        require(privateKey.size == X25519_KEY_SIZE) {
            "Private Key muss $X25519_KEY_SIZE Bytes haben, hat ${privateKey.size}"
        }
        require(publicKey.size == X25519_KEY_SIZE) {
            "Public Key muss $X25519_KEY_SIZE Bytes haben, hat ${publicKey.size}"
        }
        return try {
            val privParams = X25519PrivateKeyParameters(privateKey, 0)
            val pubParams = X25519PublicKeyParameters(publicKey, 0)
            val agreement = X25519Agreement()
            agreement.init(privParams)
            val shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(pubParams, shared, 0)
            shared
        } catch (e: Exception) {
            Log.e(TAG, "X25519-DH fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Verschlüsselt einen Klartext mit AES-256-GCM.
     *
     * @param plaintext Die zu verschlüsselnden Daten
     * @param key Der 32-Byte-AES-256-Schlüssel
     * @param nonce Die 12-Byte-Nonce (Initialisierungsvektor)
     * @return Der Ciphertext (ohne Nonce — die muss separat übertragen werden)
     * @throws Exception Bei Verschlüsselungsfehlern
     */
    fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256-Schlüssel muss 32 Bytes haben, hat ${key.size}" }
        require(nonce.size == GCM_IV_LENGTH) { "Nonce muss $GCM_IV_LENGTH Bytes haben, hat ${nonce.size}" }

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        return cipher.doFinal(plaintext)
    }

    /**
     * Entschlüsselt einen AES-256-GCM-Ciphertext.
     *
     * @param ciphertext Die verschlüsselten Daten (inkl. GCM-Authentifizierungstag)
     * @param key Der 32-Byte-AES-256-Schlüssel
     * @param nonce Die 12-Byte-Nonce (Initialisierungsvektor)
     * @return Der entschlüsselte Klartext, oder null bei Fehler (z.B. manipulierter Ciphertext)
     */
    fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
        return try {
            require(key.size == 32) { "AES-256-Schlüssel muss 32 Bytes haben, hat ${key.size}" }
            require(nonce.size == GCM_IV_LENGTH) { "Nonce muss $GCM_IV_LENGTH Bytes haben, hat ${nonce.size}" }

            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM-Entschlüsselung fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Leitet Schlüsselmaterial mit HKDF (RFC 5869) ab.
     *
     * Verwendet SHA-256 als Hash-Funktion. Dies wird für die Key-Derivation
     * im Double-Ratchet-Protokoll benötigt (RootKey → ChainKey, ChainKey → MessageKey).
     *
     * @param inputKeyMaterial Das Eingabe-Schlüsselmaterial (IKM)
     * @param salt Optionaler Salt (kann null oder leer sein)
     * @param info Optionaler Kontext-String (kann null oder leer sein)
     * @param length Gewünschte Ausgabelänge in Bytes
     * @return Die abgeleiteten Schlüsselbytes
     */
    fun hkdfDerive(
        inputKeyMaterial: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = null,
        length: Int = 32
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val saltBytes = salt ?: ByteArray(32) // Default-Salt = 32 Null-Bytes
        val infoBytes = info ?: ByteArray(0)
        hkdf.init(HKDFParameters(inputKeyMaterial, saltBytes, infoBytes))
        val result = ByteArray(length)
        hkdf.generateBytes(result, 0, length)
        return result
    }
}

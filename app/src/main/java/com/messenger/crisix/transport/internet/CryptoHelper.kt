package com.messenger.crisix.transport.internet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
     * Signiert eine Nachricht mit dem privaten Schlüssel (Ed25519).
     *
     * @param data Die zu signierenden Daten
     * @param privateKey Der private Schlüssel als Byte-Array
     * @return Die Signatur
     */
    fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        // The private key is stored as 64 bytes (seed + public key).
        // Ed25519PrivateKeyParameters expects only the 32-byte seed.
        val seed = if (privateKey.size == 64) {
            privateKey.copyOfRange(0, 32)
        } else {
            privateKey
        }
        val privateKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verifiziert eine Signatur mit dem öffentlichen Schlüssel (Ed25519).
     *
     * @param data Die signierten Daten
     * @param signature Die zu verifizierende Signatur
     * @param publicKey Der öffentliche Schlüssel als Byte-Array
     * @return true wenn die Signatur gültig ist
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val publicKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
            val signer = Ed25519Signer()
            signer.init(false, publicKeyParams)
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei der Signaturverifikation: ${e.message}", e)
            false
        }
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
}

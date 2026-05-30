package com.messenger.crisix.crypto

import android.util.Log
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Korrekte Ed25519 → X25519 Public-Key-Konvertierung (ed2curve).
 *
 * Ed25519 und X25519 verwenden beide Curve25519, aber unterschiedliche
 * Kurvenrepräsentationen:
 * - Ed25519: Edwards-Form (x, y) mit komprimierter y-Koordinate
 * - X25519: Montgomery-Form (u-Koordinate)
 *
 * Diese Konvertierung folgt der Spezifikation aus RFC 7748 und der
 * libsignal-protocol-Implementierung.
 *
 * ## Wichtig
 * Nicht alle Ed25519-Public-Keys sind gültige X25519-Public-Keys.
 * Die Konvertierung kann fehlschlagen, wenn der Punkt auf der Edwards-Kurve
 * keinem gültigen Punkt auf der Montgomery-Kurve entspricht.
 */
object Ed2Curve {

    private const val TAG = "Ed2Curve"

    /**
     * Konvertiert einen Ed25519-Public-Key in einen X25519-Public-Key.
     *
     * Die Konvertierung verwendet die Formel:
     * u = (1 + y) / (1 - y)   (Montgomery u-Koordinate aus Edwards y-Koordinate)
     *
     * @param ed25519PublicKey 32-Byte-Ed25519-Public-Key (komprimierte y-Koordinate)
     * @return 32-Byte-X25519-Public-Key (Montgomery u-Koordinate), oder null bei Fehler
     */
    fun ed25519PublicToX25519(ed25519PublicKey: ByteArray): ByteArray? {
        return try {
            require(ed25519PublicKey.size == 32) { "Ed25519 Public Key muss 32 Bytes haben" }

            // Ed25519: komprimierte y-Koordinate + Sign-Bit im MSB des letzten Bytes
            val y = ed25519PublicKey.copyOf()
            val sign = (y[31].toInt() and 0x80) != 0
            y[31] = (y[31].toInt() and 0x7F).toByte()

            // y-Koordinate als BigInteger (little-endian)
            val yBigInt = byteArrayToBigInteger(y)

            // p = 2^255 - 19 (Curve25519-Primzahl)
            val p = bigIntegerFromHex(
                "7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFED"
            )

            // u = (1 + y) / (1 - y) mod p
            // = (1 + y) * inverse(1 - y) mod p
            val one = java.math.BigInteger.ONE
            val numerator = one.add(yBigInt).mod(p)
            val denominator = one.subtract(yBigInt).mod(p)

            // Modular Inverse von denominator
            val denominatorInv = denominator.modInverse(p)

            val u = numerator.multiply(denominatorInv).mod(p)

            // u als 32-Byte-Little-Endian-Array
            val result = bigIntegerToByteArray(u, 32)

            Log.d(TAG, "Ed25519 → X25519 Konvertierung erfolgreich")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 → X25519 Konvertierung fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Konvertiert einen Ed25519-Private-Key (Seed) in einen X25519-Private-Key.
     *
     * Ed25519 und X25519 verwenden denselben 32-Byte-Seed für die private
     * Schlüsselerzeugung. Der Unterschied liegt nur in der Art, wie der
     * öffentliche Schlüssel abgeleitet wird.
     *
     * Für X25519 wird der Seed gehasht (SHA-512) und die ersten 32 Bytes
     * werden als privater Schlüssel verwendet (mit Clamping).
     *
     * @param ed25519PrivateKey Der 64-Byte-Ed25519-Private-Key (Seed + Public)
     * @return 32-Byte-X25519-Private-Key, oder null bei Fehler
     */
    fun ed25519PrivateToX25519(ed25519PrivateKey: ByteArray): ByteArray? {
        return try {
            require(ed25519PrivateKey.size == 64) { "Ed25519 Private Key muss 64 Bytes haben" }

            // Der Seed ist die ersten 32 Bytes des Ed25519-Private-Keys
            val seed = ed25519PrivateKey.copyOfRange(0, 32)

            // X25519 verwendet SHA-512(seed)[0..31] mit Clamping
            val digest = java.security.MessageDigest.getInstance("SHA-512")
            val hash = digest.digest(seed)

            // Ersten 32 Bytes als X25519-Private-Key
            val xPrivate = hash.copyOfRange(0, 32)

            // Clamping: Bits setzen/löschen gemäß RFC 7748
            xPrivate[0] = (xPrivate[0].toInt() and 0xF8).toByte()   // untere 3 Bits löschen
            xPrivate[31] = (xPrivate[31].toInt() and 0x7F).toByte()  // MSB löschen
            xPrivate[31] = (xPrivate[31].toInt() or 0x40).toByte()   // Bit 6 setzen

            Log.d(TAG, "Ed25519 → X25519 Private-Key Konvertierung erfolgreich")
            xPrivate
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 → X25519 Private-Key Konvertierung fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Konvertiert einen BigInteger in ein Little-Endian-Byte-Array fester Länge.
     */
    private fun bigIntegerToByteArray(value: java.math.BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        val result = ByteArray(length)

        // BigInteger ist Big-Endian, wir brauchen Little-Endian
        for (i in 0 until minOf(bytes.size, length)) {
            result[i] = bytes[bytes.size - 1 - i]
        }

        return result
    }

    /**
     * Konvertiert ein Little-Endian-Byte-Array in einen BigInteger.
     */
    private fun byteArrayToBigInteger(bytes: ByteArray): java.math.BigInteger {
        // Little-Endian → Big-Endian
        val bigEndian = bytes.reversedArray()
        return java.math.BigInteger(1, bigEndian)
    }

    /**
     * Erstellt einen BigInteger aus einem Hex-String.
     */
    private fun bigIntegerFromHex(hex: String): java.math.BigInteger {
        return java.math.BigInteger(hex, 16)
    }
}

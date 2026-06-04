package com.messenger.crisix.crypto

import android.util.Base64
import org.json.JSONObject

/**
 * Strikte Validierung von E2EE-Handshake-ACKs.
 *
 * Verhindert Downgrade-Angriffe durch:
 * - Validierung aller erforderlichen Felder
 * - Längenkhecks für DH-Keys
 * - Ablehnung von leeren/ungültigen PreKeyMessages
 * - Detaillierte Error-Logs
 *
 * Flow:
 * 1. ACK empfangen → `validateAckMessage()`
 * 2. PreKeyMessage extrahieren → `extractAndValidatePreKeyMessage()`
 * 3. Alle Keys validieren → `validateKeyField()`
 * 4. Bei Fehler: Reject + Retry
 */
class AckValidator {

    companion object {
        private const val TAG = "AckValidator"

        // Key-Längenbeschränkungen (Standard für X25519/Ed25519)
        private const val EXPECTED_KEY_LENGTH = 32  // 32 Bytes = 256 Bit
        private const val SIGNATURE_LENGTH = 64      // Ed25519 Signature = 64 Bytes
    }

    /**
     * Validiert den kompletten ACK-Message.
     *
     * @param ackData Rohe ACK-Daten (JSON)
     * @return AckValidationResult mit Details
     */
    fun validateAckMessage(ackData: String): AckValidationResult {
        return try {
            if (ackData.isBlank()) {
                return AckValidationResult(
                    valid = false,
                    error = "ACK-Daten sind leer"
                )
            }

            val ackJson = JSONObject(ackData)

            // Validiere ACK-Struktur
            if (!ackJson.has("type")) {
                return AckValidationResult(
                    valid = false,
                    error = "ACK fehlt 'type'-Feld"
                )
            }

            val msgType = ackJson.getString("type")
            if (msgType != "crisix_e2ee_ack") {
                return AckValidationResult(
                    valid = false,
                    error = "ACK hat falschen Type: $msgType"
                )
            }

            if (!ackJson.has("data")) {
                return AckValidationResult(
                    valid = false,
                    error = "ACK fehlt 'data'-Feld (PreKeyMessage)"
                )
            }

            // PreKeyMessage extrahieren und validieren
            val preKeyMessageJson = ackJson.getString("data")
            validatePreKeyMessage(preKeyMessageJson)

        } catch (e: Exception) {
            AckValidationResult(
                valid = false,
                error = "ACK-Parsing-Fehler: ${e.message}"
            )
        }
    }

    /**
     * Validiert die PreKeyMessage im ACK.
     *
     * Die PreKeyMessage muss folgende Felder haben:
     * - identityKey: 32 Bytes (Ed25519 public)
     * - ephemeralKey: 32 Bytes (X25519 public)
     * - signedPreKey: 32 Bytes (X25519 public)
     * - usedOneTimePreKey: Boolean
     * - oneTimePreKey: optional 32 Bytes
     */
    private fun validatePreKeyMessage(preKeyMessageJson: String): AckValidationResult {
        return try {
            
            if (preKeyMessageJson.isBlank() || preKeyMessageJson.trim() == "{}") {
                return AckValidationResult(
                    valid = false,
                    error = "PreKeyMessage ist leer (Downgrade-Versuch?)"
                )
            }

            val pkm = JSONObject(preKeyMessageJson)


            // Validiere Identity-Key
            val identityKeyStatus = validateKeyField(
                json = pkm,
                fieldName = "identityKey",
                expectedLength = EXPECTED_KEY_LENGTH,
                description = "Identity-Key (Ed25519)"
            )
            if (!identityKeyStatus.valid) return identityKeyStatus

            // Validiere Ephemeral-Key
            val ephemeralKeyStatus = validateKeyField(
                json = pkm,
                fieldName = "ephemeralKey",
                expectedLength = EXPECTED_KEY_LENGTH,
                description = "Ephemeral-Key (X25519)"
            )
            if (!ephemeralKeyStatus.valid) return ephemeralKeyStatus

            // Validiere Signed-PreKey
            val signedPreKeyStatus = validateKeyField(
                json = pkm,
                fieldName = "signedPreKey",
                expectedLength = EXPECTED_KEY_LENGTH,
                description = "Signed-PreKey (X25519)"
            )
            if (!signedPreKeyStatus.valid) return signedPreKeyStatus

            // Validiere usedOneTimePreKey Flag
            if (!pkm.has("usedOneTimePreKey")) {
                return AckValidationResult(
                    valid = false,
                    error = "PreKeyMessage fehlt 'usedOneTimePreKey'-Flag"
                )
            }

            val usedOtpk = pkm.getBoolean("usedOneTimePreKey")

            // Wenn OneTimePreKey verwendet wurde, muss es vorhanden sein
             if (usedOtpk) {
                 val otpkStatus = validateKeyField(
                     json = pkm,
                     fieldName = "oneTimePreKey",
                     expectedLength = EXPECTED_KEY_LENGTH,
                     description = "OneTimePreKey (X25519)"
                 )
                 if (!otpkStatus.valid) {
                     return otpkStatus
                 }
             }

            // Alle Validierungen bestanden
            AckValidationResult(
                valid = true,
                error = null,
                preKeyMessageJson = preKeyMessageJson,
                usedOneTimePreKey = usedOtpk
            )

        } catch (e: Exception) {
            AckValidationResult(
                valid = false,
                error = "PreKeyMessage-Validierung fehlgeschlagen: ${e.message}"
            )
        }
    }

    /**
     * Validiert ein einzelnes Schlüsselfeld in der PreKeyMessage.
     *
     * @param json JSONObject mit den Schlüsseldaten
     * @param fieldName Name des zu validierenden Feldes
     * @param expectedLength Erwartete Byte-Länge
     * @param description Beschreibung für Error-Messages
     */
    private fun validateKeyField(
         json: JSONObject,
         fieldName: String,
         expectedLength: Int,
         description: String
     ): AckValidationResult {
         return try {
             if (!json.has(fieldName)) {
                 return AckValidationResult(
                     valid = false,
                     error = "$description ($fieldName) fehlt in PreKeyMessage"
                 )
             }

             val keyB64 = json.getString(fieldName)

             if (keyB64.isBlank()) {
                 return AckValidationResult(
                     valid = false,
                     error = "$description ($fieldName) ist leer"
                 )
             }

             val keyBytes = try {
                 Base64.decode(keyB64, Base64.NO_WRAP)
             } catch (e: Exception) {
                 return AckValidationResult(
                     valid = false,
                     error = "$description ($fieldName) ist nicht gültig Base64"
                 )
             }

             if (keyBytes.size != expectedLength) {
                 return AckValidationResult(
                     valid = false,
                     error = "$description ($fieldName) hat ungültige Länge: ${keyBytes.size} (erwartet: $expectedLength)"
                 )
             }

             AckValidationResult(valid = true)

        } catch (e: Exception) {
            AckValidationResult(
                valid = false,
                error = "Fehler bei Validierung von $description: ${e.message}"
            )
        }
    }

    /**
     * Ergebnis einer ACK-Validierung
     */
    data class AckValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val preKeyMessageJson: String? = null,
        val usedOneTimePreKey: Boolean = false
    ) {
        fun getLogMessage(): String {
            return if (valid) {
                "✅ ACK valid (OneTimePreKey verwendet: $usedOneTimePreKey)"
            } else {
                "❌ ACK invalid: $error"
            }
        }
    }
}

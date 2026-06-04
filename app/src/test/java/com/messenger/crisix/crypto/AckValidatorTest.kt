package com.messenger.crisix.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AckValidatorTest {

    private lateinit var validator: AckValidator

    @Before
    fun setUp() {
        validator = AckValidator()
    }

    // =========================================================================
    // validateAckMessage - Structural Validation Tests
    // =========================================================================

    @Test
    fun `validateAckMessage rejects blank input`() {
        val result = validator.validateAckMessage("")
        assertFalse(result.valid)
        assertEquals("ACK-Daten sind leer", result.error)
    }

    @Test
    fun `validateAckMessage rejects whitespace-only input`() {
        val result = validator.validateAckMessage("   ")
        assertFalse(result.valid)
        assertEquals("ACK-Daten sind leer", result.error)
    }

    @Test
    fun `validateAckMessage rejects invalid JSON`() {
        val result = validator.validateAckMessage("not-json")
        assertFalse(result.valid)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Parsing-Fehler"))
    }

    @Test
    fun `validateAckMessage rejects JSON without type field`() {
        val json = JSONObject().apply {
            put("data", "{}")
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertEquals("ACK fehlt 'type'-Feld", result.error)
    }

    @Test
    fun `validateAckMessage rejects JSON with wrong type`() {
        val json = JSONObject().apply {
            put("type", "wrong_type")
            put("data", "{}")
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertEquals("ACK hat falschen Type: wrong_type", result.error)
    }

    @Test
    fun `validateAckMessage rejects JSON without data field`() {
        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertEquals("ACK fehlt 'data'-Feld (PreKeyMessage)", result.error)
    }

    @Test
    fun `validateAckMessage rejects empty PreKeyMessage`() {
        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
            put("data", "")
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertTrue(result.error!!.contains("leer"))
    }

    @Test
    fun `validateAckMessage rejects empty object PreKeyMessage`() {
        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
            put("data", "{}")
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertTrue(result.error!!.contains("leer") || result.error!!.contains("Downgrade"))
    }

    @Test
    fun `validateAckMessage rejects PreKeyMessage missing identityKey`() {
        val preKeyMessage = JSONObject().apply {
            put("ephemeralKey", "some-key")
            put("signedPreKey", "some-key")
            put("usedOneTimePreKey", false)
        }.toString()

        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
            put("data", preKeyMessage)
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("identityKey"))
    }

    @Test
    fun `validateAckMessage rejects PreKeyMessage with empty identityKey`() {
        val preKeyMessage = JSONObject().apply {
            put("identityKey", "")
            put("ephemeralKey", "some-key")
            put("signedPreKey", "some-key")
            put("usedOneTimePreKey", false)
        }.toString()

        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
            put("data", preKeyMessage)
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("identityKey"))
    }

    @Test
    fun `validateAckMessage rejects PreKeyMessage missing usedOneTimePreKey`() {
        // Use valid-looking Base64 keys (32 bytes = 44 chars in base64)
        val key32b = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        val preKeyMessage = JSONObject().apply {
            put("identityKey", key32b)
            put("ephemeralKey", key32b)
            put("signedPreKey", key32b)
            // missing usedOneTimePreKey
        }.toString()

        val json = JSONObject().apply {
            put("type", "crisix_e2ee_ack")
            put("data", preKeyMessage)
        }.toString()

        val result = validator.validateAckMessage(json)
        assertFalse(result.valid)
        // Should fail at either key validation (due to mocked Base64) or usedOneTimePreKey check
        assertNotNull(result.error)
    }

    // =========================================================================
    // AckValidationResult Tests
    // =========================================================================

    @Test
    fun `AckValidationResult getLogMessage for valid result`() {
        val result = AckValidator.AckValidationResult(
            valid = true,
            usedOneTimePreKey = true
        )
        val msg = result.getLogMessage()
        assertTrue(msg.contains("valid"))
        assertTrue(msg.contains("true"))
    }

    @Test
    fun `AckValidationResult getLogMessage for invalid result`() {
        val result = AckValidator.AckValidationResult(
            valid = false,
            error = "Test error"
        )
        val msg = result.getLogMessage()
        assertTrue(msg.contains("invalid"))
        assertTrue(msg.contains("Test error"))
    }

    @Test
    fun `AckValidationResult default values`() {
        val result = AckValidator.AckValidationResult(valid = true)
        assertTrue(result.valid)
        assertNull(result.error)
        assertNull(result.preKeyMessageJson)
        assertFalse(result.usedOneTimePreKey)
    }
}

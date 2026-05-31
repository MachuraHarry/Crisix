package com.messenger.crisix.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BinaryEncryptionTest {

    @Test
    fun `binary image payload encoding and decoding`() {
        val imageBytes = ByteArray(5000) { (it % 256).toByte() }
        val metaJson = JSONObject().apply {
            put("type", "image")
            put("mime", "image/jpeg")
            put("timestamp", "12:00")
            put("sender", "Tester")
        }.toString().toByteArray()
        val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()

        val typeByte = 0x01.toByte()
        val payload = byteArrayOf(typeByte) + metaLen + metaJson + imageBytes

        // Decode
        assertEquals(0x01, payload[0].toInt() and 0xFF)
        val decodedMetaLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        assertEquals(metaJson.size, decodedMetaLen)
        val decodedMeta = String(payload, 3, decodedMetaLen)
        val decodedData = payload.copyOfRange(3 + decodedMetaLen, payload.size)

        val meta = JSONObject(decodedMeta)
        assertEquals("image", meta.getString("type"))
        assertEquals("image/jpeg", meta.getString("mime"))
        assertEquals("Tester", meta.getString("sender"))

        assertArrayEquals(imageBytes, decodedData)
    }

    @Test
    fun `binary voice payload encoding and decoding`() {
        val audioBytes = ByteArray(8000) { (it % 128).toByte() }
        val durationMs = 5000L
        val metaJson = JSONObject().apply {
            put("type", "voice")
            put("mime", "audio/aac")
            put("durationMs", durationMs)
            put("sender", "Tester")
        }.toString().toByteArray()
        val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()

        val typeByte = 0x02.toByte()
        val payload = byteArrayOf(typeByte) + metaLen + metaJson + audioBytes

        val decodedType = payload[0].toInt() and 0xFF
        assertEquals(0x02, decodedType)
        val decodedMetaLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val decodedMeta = String(payload, 3, decodedMetaLen)
        val decodedData = payload.copyOfRange(3 + decodedMetaLen, payload.size)

        val meta = JSONObject(decodedMeta)
        assertEquals("voice", meta.getString("type"))
        assertEquals(durationMs, meta.getLong("durationMs"))

        assertArrayEquals(audioBytes, decodedData)
    }

    @Test
    fun `binary payload type detection`() {
        val imagePayload = byteArrayOf(0x01.toByte(), 0, 4, '{'.toByte(), '}'.toByte(), 'x'.toByte(), 'x'.toByte())
        val voicePayload = byteArrayOf(0x02.toByte(), 0, 4, '{'.toByte(), '}'.toByte(), 'a'.toByte(), 'a'.toByte())
        val textPayload = "{ \"type\": \"message\" }".toByteArray()

        fun isImage(p: ByteArray) = p.isNotEmpty() && p[0] == 0x01.toByte() && p.size > 3
        fun isVoice(p: ByteArray) = p.isNotEmpty() && p[0] == 0x02.toByte() && p.size > 3
        fun isBinary(p: ByteArray) = p.isNotEmpty() &&
            (p[0] == 0x01.toByte() || p[0] == 0x02.toByte()) && p.size > 3
        fun isJson(p: ByteArray) = !isBinary(p)

        assertTrue(isBinary(imagePayload))
        assertTrue(isImage(imagePayload))
        assertTrue(isBinary(voicePayload))
        assertTrue(isVoice(voicePayload))
        assertFalse(isBinary(textPayload))
        assertTrue(isJson(textPayload))

        val empty = ByteArray(0)
        assertFalse(isBinary(empty))
        val tooShort = byteArrayOf(0x01.toByte())
        assertFalse(isBinary(tooShort))
    }

    @Test
    fun `meta length edge cases`() {
        // Meta with max short size
        val metaJson = "{".repeat(65535).toByteArray()
        val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()
        assertEquals(0xFF.toByte(), metaLen[0])
        assertEquals(0xFF.toByte(), metaLen[1])
        val decodedLen = ((metaLen[0].toInt() and 0xFF) shl 8) or (metaLen[1].toInt() and 0xFF)
        assertEquals(65535, decodedLen)
    }

    @Test
    fun `meta length zero`() {
        val metaLen = ByteBuffer.allocate(2).putShort(0.toShort()).array()
        val payload = byteArrayOf(0x01.toByte(), metaLen[0], metaLen[1], 0x42.toByte(), 0x43.toByte())
        val decodedLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        assertEquals(0, decodedLen)
        val data = payload.copyOfRange(3, payload.size)
        assertArrayEquals(byteArrayOf(0x42, 0x43), data)
    }

    @Test
    fun `binary payload is smaller than JSON base64 for same data`() {
        val rawImage = ByteArray(50000) { it.toByte() }

        val b64Str = java.util.Base64.getEncoder().encodeToString(rawImage)
        val b64Len = b64Str.length
        val metaJson = """{"type":"image","mime":"image/jpeg","ts":"12:00","sender":"Tester"}"""
        val jsonPayloadLen = metaJson.length + b64Len

        val metaBytes = metaJson.toByteArray()
        val metaHeader = 2
        val typeByte = 1
        val binaryPayloadLen = typeByte + metaHeader + metaBytes.size + rawImage.size

        assertTrue("Binary should be smaller than JSON/Base64 (${binaryPayloadLen} vs ${jsonPayloadLen})",
            binaryPayloadLen < jsonPayloadLen)
    }
}

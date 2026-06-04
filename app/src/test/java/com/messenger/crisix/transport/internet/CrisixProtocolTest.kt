package com.messenger.crisix.transport.internet

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CrisixProtocolTest {

    // =========================================================================
    // MessageType Tests
    // =========================================================================

    @Test
    fun `MessageType toByte returns correct byte for each type`() {
        assertEquals(0x00.toByte(), CrisixProtocol.MessageType.CHAT_MESSAGE.toByte())
        assertEquals(0x01.toByte(), CrisixProtocol.MessageType.ACK.toByte())
        assertEquals(0x02.toByte(), CrisixProtocol.MessageType.FILE_TRANSFER.toByte())
        assertEquals(0x03.toByte(), CrisixProtocol.MessageType.PING.toByte())
        assertEquals(0x04.toByte(), CrisixProtocol.MessageType.PONG.toByte())
        assertEquals(0x05.toByte(), CrisixProtocol.MessageType.TYPING.toByte())
        assertEquals(0x06.toByte(), CrisixProtocol.MessageType.STATUS_UPDATE.toByte())
    }

    @Test
    fun `MessageType fromByte returns correct type for each byte`() {
        assertEquals(CrisixProtocol.MessageType.CHAT_MESSAGE, CrisixProtocol.MessageType.fromByte(0x00))
        assertEquals(CrisixProtocol.MessageType.ACK, CrisixProtocol.MessageType.fromByte(0x01))
        assertEquals(CrisixProtocol.MessageType.FILE_TRANSFER, CrisixProtocol.MessageType.fromByte(0x02))
        assertEquals(CrisixProtocol.MessageType.PING, CrisixProtocol.MessageType.fromByte(0x03))
        assertEquals(CrisixProtocol.MessageType.PONG, CrisixProtocol.MessageType.fromByte(0x04))
        assertEquals(CrisixProtocol.MessageType.TYPING, CrisixProtocol.MessageType.fromByte(0x05))
        assertEquals(CrisixProtocol.MessageType.STATUS_UPDATE, CrisixProtocol.MessageType.fromByte(0x06))
    }

    @Test
    fun `MessageType fromByte falls back to CHAT_MESSAGE for unknown byte`() {
        assertEquals(CrisixProtocol.MessageType.CHAT_MESSAGE, CrisixProtocol.MessageType.fromByte(0x7F))
        assertEquals(CrisixProtocol.MessageType.CHAT_MESSAGE, CrisixProtocol.MessageType.fromByte(0xFF.toByte()))
    }

    // =========================================================================
    // Encode / Decode Roundtrip Tests
    // =========================================================================

    @Test
    fun `encode and decode roundtrip preserves all fields`() {
        val original = CrisixProtocol.CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = "sender-abc123",
            recipientId = "recipient-xyz789",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "Hello World".toByteArray(),
            timestamp = 1700000000000L,
            nonce = ByteArray(16) { it.toByte() },
            sequenceNumber = 42
        )

        val encoded = CrisixProtocol.encodeMessage(original)
        val decoded = CrisixProtocol.decodeMessage(encoded)

        assertNotNull(decoded)
        assertEquals(original.messageId, decoded!!.messageId)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.recipientId, decoded.recipientId)
        assertEquals(original.type, decoded.type)
        assertArrayEquals(original.payload, decoded.payload)
        assertEquals(original.timestamp, decoded.timestamp)
        assertArrayEquals(original.nonce, decoded.nonce)
        assertEquals(original.sequenceNumber, decoded.sequenceNumber)
    }

    @Test
    fun `encode and decode roundtrip for ACK message`() {
        val original = CrisixProtocol.CrisixMessage(
            messageId = "ack-msg-001",
            senderId = "peer-a",
            recipientId = "peer-b",
            type = CrisixProtocol.MessageType.ACK,
            payload = "original-msg-id".toByteArray(),
            timestamp = 1700000000000L,
            nonce = ByteArray(16) { (it * 2).toByte() },
            sequenceNumber = 0
        )

        val encoded = CrisixProtocol.encodeMessage(original)
        val decoded = CrisixProtocol.decodeMessage(encoded)

        assertNotNull(decoded)
        assertEquals(CrisixProtocol.MessageType.ACK, decoded!!.type)
        assertEquals("original-msg-id", String(decoded.payload))
    }

    @Test
    fun `encode and decode roundtrip for FILE_TRANSFER with large payload`() {
        val largePayload = ByteArray(10_000) { (it % 256).toByte() }
        val original = CrisixProtocol.CrisixMessage(
            messageId = "file-transfer-001",
            senderId = "uploader",
            recipientId = "downloader",
            type = CrisixProtocol.MessageType.FILE_TRANSFER,
            payload = largePayload,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 99
        )

        val encoded = CrisixProtocol.encodeMessage(original)
        val decoded = CrisixProtocol.decodeMessage(encoded)

        assertNotNull(decoded)
        assertEquals(CrisixProtocol.MessageType.FILE_TRANSFER, decoded!!.type)
        assertArrayEquals(largePayload, decoded.payload)
        assertEquals(99, decoded.sequenceNumber)
    }

    @Test
    fun `encode and decode roundtrip for empty payload`() {
        val original = CrisixProtocol.CrisixMessage(
            messageId = "empty-payload",
            senderId = "s",
            recipientId = "r",
            type = CrisixProtocol.MessageType.PING,
            payload = ByteArray(0),
            timestamp = 0L,
            nonce = ByteArray(16),
            sequenceNumber = 0
        )

        val encoded = CrisixProtocol.encodeMessage(original)
        val decoded = CrisixProtocol.decodeMessage(encoded)

        assertNotNull(decoded)
        assertEquals(0, decoded!!.payload.size)
    }

    @Test
    fun `encode and decode preserves unicode sender and recipient ids`() {
        val original = CrisixProtocol.CrisixMessage(
            messageId = "unicode-test",
            senderId = "Benutzer-Ä",
            recipientId = "Empfänger-Ü",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "Nachricht mit Ü".toByteArray(Charsets.UTF_8),
            timestamp = 1700000000000L,
            sequenceNumber = 1
        )

        val encoded = CrisixProtocol.encodeMessage(original)
        val decoded = CrisixProtocol.decodeMessage(encoded)

        assertNotNull(decoded)
        assertEquals("Benutzer-Ä", decoded!!.senderId)
        assertEquals("Empfänger-Ü", decoded.recipientId)
        assertEquals("Nachricht mit Ü", String(decoded.payload, Charsets.UTF_8))
    }

    // =========================================================================
    // Decode Error Handling Tests
    // =========================================================================

    @Test
    fun `decodeMessage returns null for empty byte array`() {
        assertNull(CrisixProtocol.decodeMessage(ByteArray(0)))
    }

    @Test
    fun `decodeMessage returns null for invalid magic number`() {
        val data = ByteArray(20)
        data[0] = 0x00 // Wrong magic
        assertNull(CrisixProtocol.decodeMessage(data))
    }

    @Test
    fun `decodeMessage returns null for truncated data`() {
        // Just the magic number, not enough data
        val data = byteArrayOf(0x43, 0x52, 0x49, 0x58)
        assertNull(CrisixProtocol.decodeMessage(data))
    }

    // =========================================================================
    // Helper Method Tests (createAck, createPing, createPong)
    // =========================================================================

    @Test
    fun `createAck creates correct ACK message`() {
        val original = CrisixProtocol.CrisixMessage(
            messageId = "msg-to-ack",
            senderId = "peer-a",
            recipientId = "peer-b",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "Hello".toByteArray()
        )

        val ack = CrisixProtocol.createAck(original, "peer-b")

        assertEquals(CrisixProtocol.MessageType.ACK, ack.type)
        assertEquals("peer-b", ack.senderId)
        assertEquals("peer-a", ack.recipientId)
        assertEquals("msg-to-ack", String(ack.payload))
        assertNotEquals(original.messageId, ack.messageId)
    }

    @Test
    fun `createPing creates correct PING message`() {
        val ping = CrisixProtocol.createPing("local-peer", "remote-peer")

        assertEquals(CrisixProtocol.MessageType.PING, ping.type)
        assertEquals("local-peer", ping.senderId)
        assertEquals("remote-peer", ping.recipientId)
        assertEquals("ping", String(ping.payload))
        assertTrue(ping.messageId.isNotBlank())
    }

    @Test
    fun `createPong creates correct PONG message referencing ping`() {
        val ping = CrisixProtocol.createPing("remote-peer", "local-peer")
        val pong = CrisixProtocol.createPong(ping, "local-peer")

        assertEquals(CrisixProtocol.MessageType.PONG, pong.type)
        assertEquals("local-peer", pong.senderId)
        assertEquals("remote-peer", pong.recipientId)
        assertEquals(ping.messageId, String(pong.payload))
    }

    // =========================================================================
    // CrisixMessage equality and hashCode Tests
    // =========================================================================

    @Test
    fun `CrisixMessage equality is based on messageId`() {
        val msg1 = CrisixProtocol.CrisixMessage(
            messageId = "same-id",
            senderId = "a",
            recipientId = "b",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "hello".toByteArray()
        )
        val msg2 = CrisixProtocol.CrisixMessage(
            messageId = "same-id",
            senderId = "c",
            recipientId = "d",
            type = CrisixProtocol.MessageType.PING,
            payload = "different".toByteArray()
        )

        assertEquals(msg1, msg2)
        assertEquals(msg1.hashCode(), msg2.hashCode())
    }

    @Test
    fun `CrisixMessage with different ids are not equal`() {
        val msg1 = CrisixProtocol.CrisixMessage(
            messageId = "id-1",
            senderId = "a",
            recipientId = "b",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "hello".toByteArray()
        )
        val msg2 = CrisixProtocol.CrisixMessage(
            messageId = "id-2",
            senderId = "a",
            recipientId = "b",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "hello".toByteArray()
        )

        assertNotEquals(msg1, msg2)
    }

    @Test
    fun `CrisixMessage toString contains key information`() {
        val msg = CrisixProtocol.CrisixMessage(
            messageId = "test-id",
            senderId = "sender",
            recipientId = "recipient",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "hello".toByteArray(),
            timestamp = 1700000000000L
        )
        val str = msg.toString()
        assertTrue(str.contains("test-id"))
        assertTrue(str.contains("sender"))
        assertTrue(str.contains("recipient"))
        assertTrue(str.contains("5 bytes"))
    }

    // =========================================================================
    // All MessageType roundtrip Tests
    // =========================================================================

    @Test
    fun `encode and decode roundtrip for all message types`() {
        val types = listOf(
            CrisixProtocol.MessageType.CHAT_MESSAGE,
            CrisixProtocol.MessageType.ACK,
            CrisixProtocol.MessageType.FILE_TRANSFER,
            CrisixProtocol.MessageType.PING,
            CrisixProtocol.MessageType.PONG,
            CrisixProtocol.MessageType.TYPING,
            CrisixProtocol.MessageType.STATUS_UPDATE
        )

        for (type in types) {
            val original = CrisixProtocol.CrisixMessage(
                messageId = UUID.randomUUID().toString(),
                senderId = "sender",
                recipientId = "recipient",
                type = type,
                payload = "test-payload".toByteArray(),
                timestamp = 1700000000000L,
                nonce = ByteArray(16) { it.toByte() },
                sequenceNumber = 7
            )

            val encoded = CrisixProtocol.encodeMessage(original)
            val decoded = CrisixProtocol.decodeMessage(encoded)

            assertNotNull("Decode failed for type $type", decoded)
            assertEquals("Type mismatch for $type", type, decoded!!.type)
        }
    }
}

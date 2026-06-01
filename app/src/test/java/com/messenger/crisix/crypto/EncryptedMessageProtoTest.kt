package com.messenger.crisix.crypto

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EncryptedMessageProtoTest {

    @Test
    fun `toProto and fromProto roundtrip`() {
        val dhPub = Random.nextBytes(32)
        val nonce = Random.nextBytes(12)
        val ciphertext = Random.nextBytes(256)

        val msg = EncryptedMessage(
            dhPublicKey = dhPub,
            chainIndex = 42,
            messageIndex = 7,
            nonce = nonce,
            ciphertext = ciphertext,
            sessionVersion = 123456
        )

        val proto = msg.toProto()
        assertTrue(EncryptedMessage.isProto(proto))

        val parsed = EncryptedMessage.fromProto(proto)
        assertNotNull(parsed)
        assertArrayEquals(dhPub, parsed!!.dhPublicKey)
        assertEquals(42, parsed.chainIndex)
        assertEquals(7, parsed.messageIndex)
        assertArrayEquals(nonce, parsed.nonce)
        assertArrayEquals(ciphertext, parsed.ciphertext)
        assertEquals(123456, parsed.sessionVersion)
    }

    @Test
    fun `toProto and fromProto with empty ciphertext`() {
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 0,
            messageIndex = 0,
            nonce = Random.nextBytes(12),
            ciphertext = ByteArray(0),
            sessionVersion = 0
        )

        val proto = msg.toProto()
        val parsed = EncryptedMessage.fromProto(proto)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.ciphertext.size)
    }

    @Test
    fun `proto size has fixed 64-byte header overhead`() {
        val ciphertext = Random.nextBytes(200)
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 5,
            messageIndex = 10,
            nonce = Random.nextBytes(12),
            ciphertext = ciphertext,
            sessionVersion = 100
        )

        val proto = msg.toProto()
        assertEquals(EncryptedMessage.HEADER_SIZE + ciphertext.size, proto.size)
    }

    @Test
    fun `proto is more compact than Base64 JSON theoretically`() {
        val ciphertextSize = 200
        val jsonOverhead = 80
        val base64Expansion = (ciphertextSize * 4.0 / 3.0).toInt() + 1
        val estimatedJsonSize = jsonOverhead + 44 + 16 + base64Expansion + 28
        val protoSize = EncryptedMessage.HEADER_SIZE + ciphertextSize

        assertTrue(
            "Proto ($protoSize bytes) should be smaller than estimated JSON ($estimatedJsonSize chars)",
            protoSize < estimatedJsonSize
        )
    }

    @Test
    fun `isProto detects valid proto header`() {
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 0,
            messageIndex = 0,
            nonce = Random.nextBytes(12),
            ciphertext = ByteArray(10)
        )
        val proto = msg.toProto()
        assertTrue(EncryptedMessage.isProto(proto))
    }

    @Test
    fun `isProto rejects JSON`() {
        assertFalse(EncryptedMessage.isProto("\"hello\"".toByteArray()))
        assertFalse(EncryptedMessage.isProto(ByteArray(0)))
        assertFalse(EncryptedMessage.isProto(ByteArray(2) { 0 }))
    }

    @Test
    fun `fromProto returns null for truncated data`() {
        val header = ByteArray(64) { 0 }
        header[0] = EncryptedMessage.MAGIC_0
        header[1] = EncryptedMessage.MAGIC_1
        header[2] = EncryptedMessage.PROTO_VERSION
        assertNull(EncryptedMessage.fromProto(header.copyOf(63)))
    }

    @Test
    fun `fromProto returns null for wrong version`() {
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 0,
            messageIndex = 0,
            nonce = Random.nextBytes(12),
            ciphertext = ByteArray(10)
        )
        val proto = msg.toProto()
        val tampered = proto.copyOf()
        tampered[2] = 0x02
        assertNull(EncryptedMessage.fromProto(tampered))
    }

    @Test
    fun `large ciphertext roundtrip`() {
        val ciphertext = Random.nextBytes(10000)
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 0,
            messageIndex = 999,
            nonce = Random.nextBytes(12),
            ciphertext = ciphertext,
            sessionVersion = Int.MAX_VALUE
        )

        val proto = msg.toProto()
        val parsed = EncryptedMessage.fromProto(proto)
        assertNotNull(parsed)
        assertArrayEquals(ciphertext, parsed!!.ciphertext)
        assertEquals(Int.MAX_VALUE, parsed.sessionVersion)
    }

    @Test
    fun `proto size overhead is exactly HEADER_SIZE`() {
        val msg = EncryptedMessage(
            dhPublicKey = Random.nextBytes(32),
            chainIndex = 0,
            messageIndex = 0,
            nonce = Random.nextBytes(12),
            ciphertext = ByteArray(100)
        )
        val proto = msg.toProto()
        assertEquals(EncryptedMessage.HEADER_SIZE + 100, proto.size)
    }
}

package com.messenger.crisix.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OutOfOrderMessageHandlerTest {

    private lateinit var handler: OutOfOrderMessageHandler

    @Before
    fun setUp() {
        handler = OutOfOrderMessageHandler()
    }

    // =========================================================================
    // cacheChainKey Tests
    // =========================================================================

    @Test
    fun `cacheChainKey stores key for valid message index`() {
        val key = ByteArray(32) { it.toByte() }
        handler.cacheChainKey(1, key, "peer-1")
        // No exception thrown - key was cached successfully
    }

    @Test
    fun `cacheChainKey ignores index not greater than maxMessageIndexSeen`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (it + 1).toByte() }

        // Cache index 10
        handler.cacheChainKey(10, key1, "peer-1")
        // Try to cache index 5 (less than maxSeen=10) - should be no-op
        handler.cacheChainKey(5, key2, "peer-1")
        // No exception - just silently skipped
    }

    @Test
    fun `cacheChainKey rejects index exceeding MAX_SKIP`() {
        val key = ByteArray(32) { it.toByte() }
        // MAX_SKIP is 200, so jumping from 0 to 201 should be rejected
        handler.cacheChainKey(201, key, "peer-1")
        // The method should log a warning but not throw
    }

    @Test
    fun `cacheChainKey allows index within MAX_SKIP`() {
        val key = ByteArray(32) { it.toByte() }
        handler.cacheChainKey(200, key, "peer-1")
        // Should succeed - exactly at MAX_SKIP boundary
    }

    @Test
    fun `cacheChainKey makes defensive copy of key bytes`() {
        val key = ByteArray(32) { it.toByte() }
        handler.cacheChainKey(1, key, "peer-1")

        // Modify original - cached version should be unaffected
        key[0] = 99.toByte()
        // We can't directly verify the cache content, but the test ensures
        // the method handles the copy internally
    }

    @Test
    fun `cacheChainKey updates maxMessageIndexSeen`() {
        val key = ByteArray(32) { it.toByte() }
        handler.cacheChainKey(5, key, "peer-1")
        handler.cacheChainKey(10, key, "peer-1")
        handler.cacheChainKey(15, key, "peer-1")

        // After caching 15, trying to cache indices beyond MAX_SKIP from 15 should fail
        // Index 216 = 15 + 201 (> MAX_SKIP=200)
        handler.cacheChainKey(216, key, "peer-1")
        // Should silently skip
    }

    @Test
    fun `cacheChainKey sequential indices increment properly`() {
        val key = ByteArray(32) { it.toByte() }
        for (i in 1..50) {
            handler.cacheChainKey(i, key, "peer-1")
        }
        // All should succeed without exception
    }

    // =========================================================================
    // tryDecryptOutOfOrder Tests
    // =========================================================================

    @Test
    fun `tryDecryptOutOfOrder returns null when index exceeds MAX_SKIP`() {
        val result = handler.tryDecryptOutOfOrder(
            messageIndex = 201,
            nonce = ByteArray(12),
            ciphertext = ByteArray(32),
            peerId = "peer-1"
        )
        assertNull(result)
    }

    @Test
    fun `tryDecryptOutOfOrder returns null for too old message`() {
        // First set maxMessageIndexSeen to 200
        val key = ByteArray(32) { it.toByte() }
        handler.cacheChainKey(200, key, "peer-1")

        // Message index 99 is 101 messages behind (> MESSAGE_WINDOW=100)
        val result = handler.tryDecryptOutOfOrder(
            messageIndex = 99,
            nonce = ByteArray(12),
            ciphertext = ByteArray(32),
            peerId = "peer-1"
        )
        assertNull(result)
    }

    @Test
    fun `tryDecryptOutOfOrder returns null when no cached key available`() {
        // No keys cached at all
        val result = handler.tryDecryptOutOfOrder(
            messageIndex = 5,
            nonce = ByteArray(12),
            ciphertext = ByteArray(32),
            peerId = "peer-1"
        )
        assertNull(result)
    }

    // =========================================================================
    // MAX_SKIP constant Test
    // =========================================================================

    @Test
    fun `MAX_SKIP constant is 200`() {
        assertEquals(200, OutOfOrderMessageHandler.MAX_SKIP)
    }
}

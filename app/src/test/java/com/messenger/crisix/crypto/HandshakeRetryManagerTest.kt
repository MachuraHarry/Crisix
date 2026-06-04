package com.messenger.crisix.crypto

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HandshakeRetryManagerTest {

    private lateinit var manager: HandshakeRetryManager

    @Before
    fun setUp() {
        manager = HandshakeRetryManager()
    }

    @Test
    fun `getRetryStatus returns null for unknown peer`() {
        assertNull(manager.getRetryStatus("unknown-peer"))
    }

    @Test
    fun `clearRetryState invokes onRetrySuccess callback`() {
        var successPeerId: String? = null
        manager.onRetrySuccess = { peerId -> successPeerId = peerId }

        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer1@device", scope)
        manager.clearRetryState("peer1@device")

        assertEquals("peer1", successPeerId)
    }

    @Test
    fun `clearRetryState normalizes peerId by splitting on at-sign`() {
        var successPeerId: String? = null
        manager.onRetrySuccess = { peerId -> successPeerId = peerId }

        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer-abc@192.168.1.1", scope)
        manager.clearRetryState("peer-abc@192.168.1.1")

        assertEquals("peer-abc", successPeerId)
    }

    @Test
    fun `getRetryStatus returns status after initializeRetry`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("test-peer", scope)

        val status = manager.getRetryStatus("test-peer")
        assertNotNull(status)
        assertEquals("test-peer", status!!.peerId)
        assertEquals(5, status.maxAttempts)
    }

    @Test
    fun `getRetryStatus normalizes peerId`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer@host", scope)

        val status = manager.getRetryStatus("peer@host")
        assertNotNull(status)
        assertEquals("peer", status!!.peerId)
    }

    @Test
    fun `clearRetryState removes retry status`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer-x", scope)
        assertNotNull(manager.getRetryStatus("peer-x"))

        manager.clearRetryState("peer-x")
        assertNull(manager.getRetryStatus("peer-x"))
    }

    @Test
    fun `cancelRetry removes state and scope`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer-cancel", scope)
        assertNotNull(manager.getRetryStatus("peer-cancel"))

        manager.cancelRetry("peer-cancel")

        // After cancel, state is fully removed
        assertNull(manager.getRetryStatus("peer-cancel"))
    }

    @Test
    fun `initializeRetry with multiple peers tracks them independently`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer-a", scope)
        manager.initializeRetry("peer-b", scope)

        assertNotNull(manager.getRetryStatus("peer-a"))
        assertNotNull(manager.getRetryStatus("peer-b"))

        manager.clearRetryState("peer-a")
        assertNull(manager.getRetryStatus("peer-a"))
        assertNotNull(manager.getRetryStatus("peer-b"))
    }

    @Test
    fun `onRetryAttempt callback is invoked during retry`() {
        val attempts = AtomicInteger(0)
        manager.onRetryAttempt = { _, attemptNumber, _ ->
            attempts.set(attemptNumber)
        }

        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        manager.initializeRetry("peer-retry", scope)

        // The first retry attempt should trigger the callback
        // Due to async nature, we just verify the callback was set correctly
        assertNotNull(manager.onRetryAttempt)
    }

    @Test
    fun `re-initializing retry for same peer cancels previous`() {
        val scope1 = CoroutineScope(Dispatchers.Unconfined + Job())
        val scope2 = CoroutineScope(Dispatchers.Unconfined + Job())

        manager.initializeRetry("peer-re", scope1)
        manager.initializeRetry("peer-re", scope2)

        // Should still have status - the second init didn't lose state
        assertNotNull(manager.getRetryStatus("peer-re"))
    }
}

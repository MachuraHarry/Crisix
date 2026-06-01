package com.messenger.crisix.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SessionStateMachineTest {

    @Test
    fun `initial state is NONE`() {
        val sm = SessionStateMachine("test-peer")
        assertEquals(E2eeSessionState.NONE, sm.state)
        assertEquals(0L, sm.establishedAt)
        assertEquals(0L, sm.lastUsedAt)
    }

    @Test
    fun `transition NONE to HANDSHAKING succeeds`() {
        val sm = SessionStateMachine("peer1")
        assertTrue(sm.transitionTo(E2eeSessionState.HANDSHAKING))
        assertEquals(E2eeSessionState.HANDSHAKING, sm.state)
        assertNotNull(sm.handshakeNonce)
        assertEquals(8, sm.handshakeNonce!!.size)
    }

    @Test
    fun `transition HANDSHAKING to ACTIVE succeeds`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
        assertEquals(E2eeSessionState.ACTIVE, sm.state)
        assertTrue(sm.establishedAt > 0)
        assertTrue(sm.lastUsedAt > 0)
    }

    @Test
    fun `same-state transition is no-op but returns true`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
        assertEquals(E2eeSessionState.ACTIVE, sm.state)
    }

    @Test
    fun `invalid transition returns false`() {
        val sm = SessionStateMachine("peer1")
        assertFalse(sm.transitionTo(E2eeSessionState.STALE))
        assertEquals(E2eeSessionState.NONE, sm.state)
    }

    @Test
    fun `transition ACTIVE to COMPROMISED succeeds`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        val before = sm.establishedAt
        assertTrue(before > 0)
        assertTrue(sm.transitionTo(E2eeSessionState.COMPROMISED))
        assertEquals(E2eeSessionState.COMPROMISED, sm.state)
        assertEquals(0L, sm.establishedAt)
    }

    @Test
    fun `transition COMPROMISED to HANDSHAKING succeeds`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        sm.transitionTo(E2eeSessionState.COMPROMISED)
        assertTrue(sm.transitionTo(E2eeSessionState.HANDSHAKING))
    }

    @Test
    fun `isReadyForEncryption returns true for ACTIVE and STALE`() {
        val sm = SessionStateMachine("peer1")
        assertFalse(sm.isReadyForEncryption())
        sm.transitionTo(E2eeSessionState.ACTIVE)
        assertTrue(sm.isReadyForEncryption())
        sm.transitionTo(E2eeSessionState.STALE)
        assertTrue(sm.isReadyForEncryption())
    }

    @Test
    fun `isReadyForEncryption returns false for HANDSHAKING and COMPROMISED`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        assertFalse(sm.isReadyForEncryption())
        sm.transitionTo(E2eeSessionState.COMPROMISED)
        assertFalse(sm.isReadyForEncryption())
    }

    @Test
    fun `touch updates lastUsedAt`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        val before = sm.lastUsedAt
        Thread.sleep(5)
        sm.touch()
        assertTrue(sm.lastUsedAt > before)
    }

    @Test
    fun `isStale returns false for recent session`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        assertFalse(sm.isStale(100_000L))
    }

    @Test
    fun `isStale returns false for non-ACTIVE state`() {
        val sm = SessionStateMachine("peer1")
        assertFalse(sm.isStale(1L))
        sm.transitionTo(E2eeSessionState.COMPROMISED)
        assertFalse(sm.isStale(1L))
    }

    @Test
    fun `handshake nonce is set on HANDSHAKING and cleared on reset`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        assertTrue(sm.hasHandshakeNonce())
        val nonce = sm.handshakeNonce!!
        assertEquals(8, nonce.size)
        assertNotNull(nonce)

        sm.reset()
        assertFalse(sm.hasHandshakeNonce())
        assertNull(sm.handshakeNonce)
    }

    @Test
    fun `resolveConcurrentHandshakes with higher nonce wins`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        val myNonce = sm.handshakeNonce!!

        val theirLower = myNonce.copyOf()
        theirLower[0] = (theirLower[0].toInt() - 1).toByte()

        val theirHigher = myNonce.copyOf()
        theirHigher[0] = (theirHigher[0].toInt() + 1).toByte()

        assertTrue(sm.resolveConcurrentHandshakes(theirLower))
        assertFalse(sm.resolveConcurrentHandshakes(theirHigher))
    }

    @Test
    fun `resolveConcurrentHandshakes returns false without nonce`() {
        val sm = SessionStateMachine("peer1")
        assertFalse(sm.resolveConcurrentHandshakes(ByteArray(8)))
    }

    @Test
    fun `queue message and flush on ACTIVE`() {
        val sm = SessionStateMachine("peer1")
        val encrypted = AtomicBoolean(false)
        val callCount = AtomicInteger(0)

        sm.enqueueMessage(QueuedMessage(
            payload = "hello".toByteArray(),
            uiMessageId = "msg1",
            encryptDirectly = {
                callCount.incrementAndGet()
                "encrypted-data"
            },
            onFlushed = { success ->
                encrypted.set(success)
            }
        ))

        assertEquals(1, sm.queueSize())
        assertEquals(0, callCount.get())

        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        sm.transitionTo(E2eeSessionState.ACTIVE)

        assertEquals(1, callCount.get())
        assertTrue(encrypted.get())
        assertEquals(0, sm.queueSize())
    }

    @Test
    fun `queue multiple messages and flush all on ACTIVE`() {
        val sm = SessionStateMachine("peer1")
        val flushedCount = AtomicInteger(0)

        for (i in 1..5) {
            sm.enqueueMessage(QueuedMessage(
                payload = "msg$i".toByteArray(),
                uiMessageId = "msg$i",
                encryptDirectly = { "enc$i" },
                onFlushed = { if (it) flushedCount.incrementAndGet() }
            ))
        }

        assertEquals(5, sm.queueSize())
        sm.transitionTo(E2eeSessionState.ACTIVE)
        assertEquals(5, flushedCount.get())
        assertEquals(0, sm.queueSize())
    }

    @Test
    fun `clearQueue notifies all pending messages as failed`() {
        val sm = SessionStateMachine("peer1")
        val failedCount = AtomicInteger(0)

        for (i in 1..3) {
            sm.enqueueMessage(QueuedMessage(
                payload = "msg$i".toByteArray(),
                uiMessageId = "msg$i",
                encryptDirectly = { "enc" },
                onFlushed = { if (!it) failedCount.incrementAndGet() }
            ))
        }

        sm.clearQueue("test")
        assertEquals(3, failedCount.get())
        assertEquals(0, sm.queueSize())
    }

    @Test
    fun `reset sets state to NONE and clears queue`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        sm.enqueueMessage(QueuedMessage(
            payload = "test".toByteArray(),
            uiMessageId = "m1",
            encryptDirectly = { "enc" },
            onFlushed = {}
        ))
        assertTrue(sm.hasHandshakeNonce())

        sm.reset()
        assertEquals(E2eeSessionState.NONE, sm.state)
        assertFalse(sm.hasHandshakeNonce())
        assertEquals(0, sm.queueSize())
        assertEquals(0L, sm.establishedAt)
        assertEquals(0L, sm.lastUsedAt)
    }

    @Test
    fun `full state flow NONE→HANDSHAKING→ACTIVE→STALE→HANDSHAKING→ACTIVE`() {
        val sm = SessionStateMachine("peer1")

        assertTrue(sm.transitionTo(E2eeSessionState.HANDSHAKING))
        assertEquals(E2eeSessionState.HANDSHAKING, sm.state)

        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
        assertEquals(E2eeSessionState.ACTIVE, sm.state)

        assertTrue(sm.transitionTo(E2eeSessionState.STALE))
        assertEquals(E2eeSessionState.STALE, sm.state)

        assertTrue(sm.transitionTo(E2eeSessionState.HANDSHAKING))
        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
    }

    @Test
    fun `ACTIVE to STALE transition via MAX_SKIP violation`() {
        val sm = SessionStateMachine("peer1")

        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
        assertEquals(E2eeSessionState.ACTIVE, sm.state)

        assertTrue(sm.transitionTo(E2eeSessionState.STALE))
        assertEquals(E2eeSessionState.STALE, sm.state)
        assertTrue(sm.isReadyForEncryption())
    }

    @Test
    fun `STALE can recover via HANDSHAKING to ACTIVE`() {
        val sm = SessionStateMachine("peer1")

        sm.transitionTo(E2eeSessionState.ACTIVE)
        sm.transitionTo(E2eeSessionState.STALE)

        assertTrue(sm.transitionTo(E2eeSessionState.HANDSHAKING))
        assertEquals(E2eeSessionState.HANDSHAKING, sm.state)

        assertTrue(sm.transitionTo(E2eeSessionState.ACTIVE))
        assertEquals(E2eeSessionState.ACTIVE, sm.state)
        assertTrue(sm.isReadyForEncryption())
    }
}

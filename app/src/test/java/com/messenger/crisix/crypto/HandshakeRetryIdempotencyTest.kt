package com.messenger.crisix.crypto

import com.messenger.crisix.transport.internet.CryptoHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HandshakeRetryIdempotencyTest {

    private fun makeHandshakeData(): HandshakeInitData {
        val eph = CryptoHelper.generateX25519KeyPair()
        val identity = CryptoHelper.generateKeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val sig = CryptoHelper.sign(spk.publicKey, identity.privateKey)
        val opk = CryptoHelper.generateX25519KeyPair()
        return HandshakeInitData(
            preKeyBundleJson = "{\"peerBundle\":{\"spk\":\"abc\"}}",
            ownEphemeralPrivateKey = eph.privateKey,
            ownEphemeralPublicKey = eph.publicKey,
            peerBundle = X3DHSession.PreKeyBundle(
                identityKey = identity.publicKey,
                signedPreKey = spk.publicKey,
                signedPreKeySignature = sig,
                oneTimePreKey = opk.publicKey,
            ),
        )
    }

    @Test
    fun `initializeRetry stores handshake data for later resend`() {
        val manager = HandshakeRetryManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = makeHandshakeData()
        manager.initializeRetry("peer1@host", data, scope)
        val stored = manager.getPendingResendData("peer1")
        assertNotNull(stored)
        assertSame(data.ownEphemeralPublicKey, stored!!.ownEphemeralPublicKey)
    }

    @Test
    fun `second initializeRetry replaces pending data`() {
        val manager = HandshakeRetryManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = makeHandshakeData()
        val second = makeHandshakeData()
        manager.initializeRetry("peer1@host", first, scope)
        manager.initializeRetry("peer1@host", second, scope)
        val stored = manager.getPendingResendData("peer1")
        assertNotNull(stored)
        assertEquals(false, first.ownEphemeralPublicKey.contentEquals(stored!!.ownEphemeralPublicKey))
    }

    @Test
    fun `peerId is normalized in pending resend data`() {
        val manager = HandshakeRetryManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val data = makeHandshakeData()
        manager.initializeRetry("peer1@host.example.com", data, scope)
        assertNotNull(manager.getPendingResendData("peer1"))
    }

    @Test
    fun `clearPendingResendData removes entry`() {
        val manager = HandshakeRetryManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager.initializeRetry("peer1@host", makeHandshakeData(), scope)
        manager.clearPendingResendData("peer1")
        assertEquals(null, manager.getPendingResendData("peer1"))
    }

    @Test
    fun `clearAll removes all entries`() {
        val manager = HandshakeRetryManager()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager.initializeRetry("peer1@host", makeHandshakeData(), scope)
        manager.initializeRetry("peer2@host", makeHandshakeData(), scope)
        manager.clearAll()
        assertEquals(null, manager.getPendingResendData("peer1"))
        assertEquals(null, manager.getPendingResendData("peer2"))
    }
}

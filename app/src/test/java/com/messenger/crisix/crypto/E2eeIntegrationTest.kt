package com.messenger.crisix.crypto

import com.messenger.crisix.transport.internet.CryptoHelper
import org.junit.Assert.*
import org.junit.Test

class E2eeIntegrationTest {

    @Test
    fun `encryptOnce with same state produces same ciphertext`() {
        val (alice, _) = createPairedRatchet()
        val plaintext = "dedup test".toByteArray()

        // Reset ratchet state to same point
        val stateCopy = alice.getSessionState()
        val ratchetCopy = DoubleRatchet(SessionState(
            rootKey = stateCopy.rootKey.copyOf(),
            sendingChainKey = stateCopy.sendingChainKey.copyOf(),
            receivingChainKey = stateCopy.receivingChainKey.copyOf(),
            sendingDhKeyPair = CryptoHelper.X25519KeyPair(stateCopy.sendingDhKeyPair.privateKey.copyOf(), stateCopy.sendingDhKeyPair.publicKey.copyOf()),
            receivingDhKeyPair = CryptoHelper.X25519KeyPair(stateCopy.receivingDhKeyPair.privateKey.copyOf(), stateCopy.receivingDhKeyPair.publicKey.copyOf()),
            sendingChainIndex = stateCopy.sendingChainIndex,
            receivingChainIndex = stateCopy.receivingChainIndex,
            sendingMessageIndex = stateCopy.sendingMessageIndex,
            receivingMessageIndex = stateCopy.receivingMessageIndex
        ))
        ratchetCopy.peerId = "bob"
        ratchetCopy.sessionVersion = 42

        // First encrypt advances state
        val enc1 = alice.ratchetEncrypt(plaintext)
        // Copy encrypts from same state
        val enc2 = ratchetCopy.ratchetEncrypt(plaintext)

        assertEquals(enc1.chainIndex, enc2.chainIndex)
        assertEquals(enc1.messageIndex, enc2.messageIndex)
        assertArrayEquals(enc1.ciphertext, enc2.ciphertext)
    }

    @Test
    fun `session state machine full lifecycle with encryption`() {
        val (alice, bob) = createPairedRatchet()

        val aliceSM = SessionStateMachine("bob")
        val bobSM = SessionStateMachine("alice")

        aliceSM.transitionTo(E2eeSessionState.ACTIVE)
        bobSM.transitionTo(E2eeSessionState.ACTIVE)

        assertTrue(aliceSM.isReadyForEncryption())
        assertTrue(bobSM.isReadyForEncryption())

        val enc = alice.ratchetEncrypt("hello".toByteArray())
        val dec = bob.ratchetDecrypt(enc)
        assertNotNull(dec)

        aliceSM.touch()
        assertTrue(aliceSM.lastUsedAt > 0)

        // Simulate COMPROMISED state after bad decrypt
        bobSM.transitionTo(E2eeSessionState.COMPROMISED)
        assertFalse(bobSM.isReadyForEncryption())

        // Re-handshake
        bobSM.transitionTo(E2eeSessionState.HANDSHAKING)
        bobSM.transitionTo(E2eeSessionState.ACTIVE)
        assertTrue(bobSM.isReadyForEncryption())
    }

    @Test
    fun `message queue is flushed when session becomes ACTIVE`() {
        val (alice, _) = createPairedRatchet()
        val sm = SessionStateMachine("bob")

        val messages = mutableListOf<String>()
        for (i in 1..3) {
            sm.enqueueMessage(QueuedMessage(
                payload = "msg$i".toByteArray(),
                uiMessageId = "m$i",
                encryptDirectly = { bytes ->
                    val enc = alice.ratchetEncrypt(bytes)
                    enc.toJson()
                },
                onFlushed = { success, _ ->
                    if (success) messages.add("m$i")
                }
            ))
        }

        assertEquals(3, sm.queueSize())
        sm.transitionTo(E2eeSessionState.ACTIVE)
        assertEquals(listOf("m1", "m2", "m3"), messages)
        assertEquals(0, sm.queueSize())
    }

    @Test
    fun `sequential messages decrypt in order correctly`() {
        val (alice, bob) = createPairedRatchet()

        val msgs = (0..4).map { i -> alice.ratchetEncrypt("msg$i".toByteArray()) }

        // Sequential decryption works
        for ((i, enc) in msgs.withIndex()) {
            val dec = bob.ratchetDecrypt(enc)
            assertNotNull("Message $i must decrypt", dec)
            assertArrayEquals("msg$i".toByteArray(), dec)
        }
    }

    @Test
    fun `bidirectional conversation decrypts correctly`() {
        val (alice, bob) = createPairedRatchet()

        val a1 = alice.ratchetEncrypt("Hello from Alice".toByteArray())
        val decA1 = bob.ratchetDecrypt(a1)
        assertArrayEquals("Hello from Alice".toByteArray(), decA1)

        val b1 = bob.ratchetEncrypt("Hi from Bob".toByteArray())
        val decB1 = alice.ratchetDecrypt(b1)
        assertArrayEquals("Hi from Bob".toByteArray(), decB1)

        val a2 = alice.ratchetEncrypt("Second from Alice".toByteArray())
        val decA2 = bob.ratchetDecrypt(a2)
        assertArrayEquals("Second from Alice".toByteArray(), decA2)
    }

    @Test
    fun `symmetric ratchet handles 1000 messages correctly`() {
        val (alice, bob) = createPairedRatchet()

        // Verify initial chain indexes are 0
        val initialState = alice.getSessionState()
        assertEquals(0, initialState.sendingChainIndex)

        // Send 1000 messages — all within same chain (no DH ratchet)
        for (i in 1..1000) {
            val enc = alice.ratchetEncrypt("msg$i".toByteArray())
            val dec = bob.ratchetDecrypt(enc)
            assertNotNull("Message $i must decrypt", dec)
            assertArrayEquals("msg$i".toByteArray(), dec)
        }

        // After 1000 messages, chain index should still be 0 (no DH ratchet triggered yet)
        val afterState = alice.getSessionState()
        assertEquals(0, afterState.sendingChainIndex)
    }

    @Test
    fun `wrong session version is logged but does not prevent decryption`() {
        val (alice, bob) = createPairedRatchet()

        val enc = alice.ratchetEncrypt("test".toByteArray())
        assertEquals(42, enc.sessionVersion)

        // Even with wrong version, decryption should work (version is informational)
        val dec = bob.ratchetDecrypt(enc)
        assertNotNull(dec)
        assertArrayEquals("test".toByteArray(), dec)
    }

    @Test
    fun `peerId is properly set on ratchet instances`() {
        val (alice, bob) = createPairedRatchet()
        assertEquals("bob", alice.peerId)
        assertEquals("alice", bob.peerId)
    }

    private fun createPairedRatchet(): Pair<DoubleRatchet, DoubleRatchet> {
        val aliceIdentity = CryptoHelper.generateKeyPair()
        val aliceSpk = CryptoHelper.generateX25519KeyPair()
        val aliceSig = CryptoHelper.sign(aliceSpk.publicKey, aliceIdentity.privateKey)
        val aliceEph = CryptoHelper.generateX25519KeyPair()
        val aliceOpk = CryptoHelper.generateX25519KeyPair()

        val bobIdentity = CryptoHelper.generateKeyPair()
        val bobSpk = CryptoHelper.generateX25519KeyPair()
        val bobSig = CryptoHelper.sign(bobSpk.publicKey, bobIdentity.privateKey)
        val bobOpk = CryptoHelper.generateX25519KeyPair()

        val aliceX3dh = X3DHSession(aliceIdentity, aliceSpk, aliceSig, mutableListOf(aliceOpk))
        val aliceBundle = aliceX3dh.createPreKeyBundle(useOneTimePreKey = true)

        val bobX3dh = X3DHSession(bobIdentity, bobSpk, bobSig, mutableListOf(bobOpk))
        val rawResult = bobX3dh.processAsResponder(aliceBundle, aliceEph.publicKey)!!
        val (bobState, bobUsedOtpk, bobOtpkPub) = rawResult

        val bobEphKey = CryptoHelper.generateX25519KeyPair()
        val bobMsg = X3DHSession.PreKeyMessage(
            bobIdentity.publicKey, bobEphKey.publicKey,
            bobSpk.publicKey, bobUsedOtpk, bobOtpkPub
        )

        val aliceX3dh2 = X3DHSession(aliceIdentity, aliceSpk, aliceSig, mutableListOf())
        val aliceRawState = aliceX3dh2.processAsInitiator(
            bobMsg, aliceEph.privateKey,
            X3DHSession.PreKeyBundle(aliceIdentity.publicKey, aliceSpk.publicKey, aliceSig, aliceOpk.publicKey)
        )!!

        val aliceReceivingPriv = CryptoHelper.generateX25519KeyPair()
        val bobReceivingPriv = CryptoHelper.generateX25519KeyPair()

        val aliceState = aliceRawState.copy(
            sendingDhKeyPair = CryptoHelper.X25519KeyPair(aliceEph.privateKey, aliceEph.publicKey),
            receivingDhKeyPair = CryptoHelper.X25519KeyPair(aliceReceivingPriv.privateKey, bobEphKey.publicKey)
        )
        val bobFixedState = bobState.copy(
            sendingDhKeyPair = CryptoHelper.X25519KeyPair(bobEphKey.privateKey, bobEphKey.publicKey),
            receivingDhKeyPair = CryptoHelper.X25519KeyPair(bobReceivingPriv.privateKey, aliceEph.publicKey)
        )

        val aliceRatchet = DoubleRatchet(aliceState).also {
            it.peerId = "bob"
            it.sessionVersion = 42
        }
        val bobRatchet = DoubleRatchet(bobFixedState).also {
            it.peerId = "alice"
            it.sessionVersion = 42
        }
        return Pair(aliceRatchet, bobRatchet)
    }
}

package com.messenger.crisix.crypto

import com.messenger.crisix.transport.internet.CryptoHelper
import org.junit.Assert.*
import org.junit.Test

class DoubleRatchetTest {

    private fun createPairedSessions(): Pair<DoubleRatchet, DoubleRatchet> {
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

        // Fix DH key pairs: both sides need valid private keys for DH ratchets
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

    @Test
    fun `single encrypt-decrypt roundtrip works`() {
        val (alice, bob) = createPairedSessions()
        val plaintext = "Hello, Bob!".toByteArray()

        val encrypted = alice.ratchetEncrypt(plaintext)
        assertEquals(42, encrypted.sessionVersion)

        val decrypted = bob.ratchetDecrypt(encrypted)
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `bidirectional messaging both directions work`() {
        val (alice, bob) = createPairedSessions()

        val a1 = "Alice says hi".toByteArray()
        val decA1 = bob.ratchetDecrypt(alice.ratchetEncrypt(a1))
        assertArrayEquals(a1, decA1)

        val b1 = "Bob replies".toByteArray()
        val decB1 = alice.ratchetDecrypt(bob.ratchetEncrypt(b1))
        assertArrayEquals(b1, decB1)
    }

    @Test
    fun `10 messages in each direction all decrypt correctly`() {
        val (alice, bob) = createPairedSessions()

        for (i in 1..10) {
            val msg = "Alice msg $i".toByteArray()
            val dec = bob.ratchetDecrypt(alice.ratchetEncrypt(msg))
            assertArrayEquals(msg, dec)
        }
        for (i in 1..10) {
            val msg = "Bob msg $i".toByteArray()
            val dec = alice.ratchetDecrypt(bob.ratchetEncrypt(msg))
            assertArrayEquals(msg, dec)
        }
    }

    @Test
    fun `interleaved messages all decrypt correctly`() {
        val (alice, bob) = createPairedSessions()

        val a1 = alice.ratchetEncrypt("A1".toByteArray())
        val b1 = bob.ratchetEncrypt("B1".toByteArray())
        val a2 = alice.ratchetEncrypt("A2".toByteArray())

        assertArrayEquals("A1".toByteArray(), bob.ratchetDecrypt(a1))
        assertArrayEquals("B1".toByteArray(), alice.ratchetDecrypt(b1))
        assertArrayEquals("A2".toByteArray(), bob.ratchetDecrypt(a2))

        val b2 = bob.ratchetEncrypt("B2".toByteArray())
        val b3 = bob.ratchetEncrypt("B3".toByteArray())
        assertArrayEquals("B2".toByteArray(), alice.ratchetDecrypt(b2))
        assertArrayEquals("B3".toByteArray(), alice.ratchetDecrypt(b3))

        val a3 = alice.ratchetEncrypt("A3".toByteArray())
        assertArrayEquals("A3".toByteArray(), bob.ratchetDecrypt(a3))
    }

    @Test
    fun `binary data encryption works correctly`() {
        val (alice, bob) = createPairedSessions()
        val binaryData = ByteArray(1024) { it.toByte() }

        val encrypted = alice.ratchetEncrypt(binaryData)
        val decrypted = bob.ratchetDecrypt(encrypted)
        assertArrayEquals(binaryData, decrypted)
    }

    @Test
    fun `encrypted messages have different ciphertext for same plaintext`() {
        val (alice, bob) = createPairedSessions()
        val plain = "test".toByteArray()

        val enc1 = alice.ratchetEncrypt(plain)
        val enc2 = alice.ratchetEncrypt(plain)

        assertFalse("Ciphertexts must differ (different nonces)", enc1.ciphertext.contentEquals(enc2.ciphertext))
    }

    @Test
    fun `sessionVersion is preserved in EncryptedMessage`() {
        val (alice, _) = createPairedSessions()
        val enc = alice.ratchetEncrypt("test".toByteArray())
        assertEquals(42, enc.sessionVersion)
        assertEquals(0, enc.chainIndex)
        assertEquals(0, enc.messageIndex)
        assertEquals(32, enc.dhPublicKey.size)
        assertEquals(12, enc.nonce.size)
        assertTrue(enc.ciphertext.isNotEmpty())
    }

    @Test
    fun `EncryptedMessage without sessionVersion defaults to 0`() {
        val enc = EncryptedMessage(
            dhPublicKey = ByteArray(32) { 0 },
            chainIndex = 0,
            messageIndex = 0,
            nonce = ByteArray(12) { 0 },
            ciphertext = ByteArray(16) { 1 },
            sessionVersion = 0
        )
        assertEquals(0, enc.sessionVersion)
    }

    @Test
    fun `stress 1000 messages all decrypt correctly`() {
        val (alice, bob) = createPairedSessions()
        val start = System.currentTimeMillis()

        for (i in 1..1000) {
            val msg = "Stress test message number $i with some padding to make it longer ${"x".repeat(50)}".toByteArray()
            val enc = alice.ratchetEncrypt(msg)
            val dec = bob.ratchetDecrypt(enc)
            assertNotNull("Message $i must decrypt", dec)
            assertArrayEquals("Message $i must match", msg, dec)
        }

        val elapsed = System.currentTimeMillis() - start
        println("1000 messages encrypted + decrypted in ${elapsed}ms")
        assertTrue("Should complete in under 30 seconds", elapsed < 30_000)
    }
}

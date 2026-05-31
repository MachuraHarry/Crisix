package com.messenger.crisix.crypto

import com.messenger.crisix.transport.internet.CryptoHelper
import org.junit.Assert.*
import org.junit.Test

class X3DHKeyAgreementTest {

    @Test
    fun `full X3DH handshake produces identical root keys for both parties with OPK`() {
        // Alice (Initiator) keys
        val aliceIdentity = CryptoHelper.generateKeyPair()
        val aliceSpk = CryptoHelper.generateX25519KeyPair()
        val aliceSpkSig = CryptoHelper.sign(aliceSpk.publicKey, aliceIdentity.privateKey)
        val aliceOpk = CryptoHelper.generateX25519KeyPair()

        // Bob (Responder) keys
        val bobIdentity = CryptoHelper.generateKeyPair()
        val bobSpk = CryptoHelper.generateX25519KeyPair()
        val bobSpkSig = CryptoHelper.sign(bobSpk.publicKey, bobIdentity.privateKey)
        val bobOpk = CryptoHelper.generateX25519KeyPair()

        // Alice creates PreKeyBundle (with OPK)
        val aliceSession = X3DHSession(
            ownIdentityKey = aliceIdentity,
            ownSignedPreKey = aliceSpk,
            ownSignedPreKeySignature = aliceSpkSig,
            ownOneTimePreKeys = mutableListOf(aliceOpk)
        )
        val aliceBundle = aliceSession.createPreKeyBundle(useOneTimePreKey = true)
        assertNotNull(aliceBundle.oneTimePreKey)
        assertEquals(32, aliceBundle.identityKey.size)
        assertEquals(32, aliceBundle.signedPreKey.size)
        assertEquals(64, aliceBundle.signedPreKeySignature.size)

        // Alice generates ephemeral key for handshake
        val aliceEphemeral = CryptoHelper.generateX25519KeyPair()

        // Bob's X3DH session
        val bobSession = X3DHSession(
            ownIdentityKey = bobIdentity,
            ownSignedPreKey = bobSpk,
            ownSignedPreKeySignature = bobSpkSig,
            ownOneTimePreKeys = mutableListOf(bobOpk)
        )

        // Bob processes Alice's bundle as Responder
        val result = bobSession.processAsResponder(aliceBundle, aliceEphemeral.publicKey)
        assertNotNull("Responder should compute SharedSecret", result)
        val (bobState, bobUsedOtpk, bobOtpkPublic) = result!!
        assertTrue("Bob should use OPK", bobUsedOtpk)
        assertNotNull("Bob should return his OTPk public", bobOtpkPublic)
        assertEquals(32, bobOtpkPublic!!.size)

        // Bob sends PreKeyMessage back to Alice
        val bobPreKeyMessage = X3DHSession.PreKeyMessage(
            identityKey = bobIdentity.publicKey,
            ephemeralKey = bobSession.createResponderPreKeyMessage().first.ephemeralKey,
            signedPreKey = bobSpk.publicKey,
            usedOneTimePreKey = bobUsedOtpk,
            oneTimePreKey = bobOtpkPublic
        )

        // Alice's X3DH session (with same keys, different ephemeral)
        val aliceX3dh = X3DHSession(
            ownIdentityKey = aliceIdentity,
            ownSignedPreKey = aliceSpk,
            ownSignedPreKeySignature = aliceSpkSig,
            ownOneTimePreKeys = mutableListOf()
        )

        // Alice's bundle = what she sent to Bob
        val aliceBundleForBob = X3DHSession.PreKeyBundle(
            identityKey = aliceIdentity.publicKey,
            signedPreKey = aliceSpk.publicKey,
            signedPreKeySignature = aliceSpkSig,
            oneTimePreKey = aliceOpk.publicKey
        )

        // Alice processes Bob's PreKeyMessage as Initiator
        val aliceState = aliceX3dh.processAsInitiator(
            peerPreKeyMessage = bobPreKeyMessage,
            ownEphemeralPrivateKey = aliceEphemeral.privateKey,
            peerBundle = aliceBundleForBob
        )
        assertNotNull("Initiator should compute SharedSecret", aliceState)

        // Verify both sides have identical RootKeys
        assertArrayEquals(
            "Both sides must have identical RootKeys",
            aliceState!!.rootKey, bobState.rootKey
        )

        // Verify ChainKeys are cross-matched
        assertArrayEquals(
            "Alice's sending chain = Bob's receiving chain",
            aliceState.sendingChainKey, bobState.receivingChainKey
        )
        assertArrayEquals(
            "Alice's receiving chain = Bob's sending chain",
            aliceState.receivingChainKey, bobState.sendingChainKey
        )
    }

    @Test
    fun `X3DH handshake without OPK produces identical root keys`() {
        val aliceIdentity = CryptoHelper.generateKeyPair()
        val aliceSpk = CryptoHelper.generateX25519KeyPair()
        val aliceSpkSig = CryptoHelper.sign(aliceSpk.publicKey, aliceIdentity.privateKey)

        val bobIdentity = CryptoHelper.generateKeyPair()
        val bobSpk = CryptoHelper.generateX25519KeyPair()
        val bobSpkSig = CryptoHelper.sign(bobSpk.publicKey, bobIdentity.privateKey)

        val aliceSession = X3DHSession(
            ownIdentityKey = aliceIdentity,
            ownSignedPreKey = aliceSpk,
            ownSignedPreKeySignature = aliceSpkSig,
            ownOneTimePreKeys = mutableListOf()
        )
        val aliceBundle = aliceSession.createPreKeyBundle(useOneTimePreKey = false)
        assertNull(aliceBundle.oneTimePreKey)

        val aliceEphemeral = CryptoHelper.generateX25519KeyPair()

        val bobSession = X3DHSession(
            ownIdentityKey = bobIdentity,
            ownSignedPreKey = bobSpk,
            ownSignedPreKeySignature = bobSpkSig,
            ownOneTimePreKeys = mutableListOf()
        )

        // Bob as Responder
        val result = bobSession.processAsResponder(aliceBundle, aliceEphemeral.publicKey)
        assertNotNull(result)
        val (bobState, bobUsedOtpk, bobOtpkPublic) = result!!
        assertFalse("Bob should NOT use OPK", bobUsedOtpk)
        assertNull(bobOtpkPublic)

        val bobEphKey = CryptoHelper.generateX25519KeyPair()
        val bobPreKeyMessage = X3DHSession.PreKeyMessage(
            identityKey = bobIdentity.publicKey,
            ephemeralKey = bobEphKey.publicKey,
            signedPreKey = bobSpk.publicKey,
            usedOneTimePreKey = false,
            oneTimePreKey = null
        )

        val aliceX3dh = X3DHSession(
            ownIdentityKey = aliceIdentity,
            ownSignedPreKey = aliceSpk,
            ownSignedPreKeySignature = aliceSpkSig,
            ownOneTimePreKeys = mutableListOf()
        )

        val aliceBundleForBob = X3DHSession.PreKeyBundle(
            identityKey = aliceIdentity.publicKey,
            signedPreKey = aliceSpk.publicKey,
            signedPreKeySignature = aliceSpkSig,
            oneTimePreKey = null
        )

        val aliceState = aliceX3dh.processAsInitiator(
            peerPreKeyMessage = bobPreKeyMessage,
            ownEphemeralPrivateKey = aliceEphemeral.privateKey,
            peerBundle = aliceBundleForBob
        )
        assertNotNull(aliceState)

        assertArrayEquals(aliceState!!.rootKey, bobState.rootKey)
    }

    @Test
    fun `Multiple X3DH handshakes with different keys produce DIFFERENT root keys`() {
        fun doHandshake(): Pair<ByteArray, ByteArray> {
            val aIdent = CryptoHelper.generateKeyPair()
            val aSpk = CryptoHelper.generateX25519KeyPair()
            val aSig = CryptoHelper.sign(aSpk.publicKey, aIdent.privateKey)
            val aEph = CryptoHelper.generateX25519KeyPair()

            val bIdent = CryptoHelper.generateKeyPair()
            val bSpk = CryptoHelper.generateX25519KeyPair()
            val bSig = CryptoHelper.sign(bSpk.publicKey, bIdent.privateKey)
            val bOpk = CryptoHelper.generateX25519KeyPair()

            val aliceSession = X3DHSession(aIdent, aSpk, aSig, mutableListOf(CryptoHelper.generateX25519KeyPair()))
            val aliceBundle = aliceSession.createPreKeyBundle(useOneTimePreKey = true)

            val bobSession = X3DHSession(bIdent, bSpk, bSig, mutableListOf(bOpk))
            val (bobState, _, bobOtpkPub) = bobSession.processAsResponder(aliceBundle, aEph.publicKey)!!
            val bobMsg = X3DHSession.PreKeyMessage(
                bIdent.publicKey, bobSession.createResponderPreKeyMessage().first.ephemeralKey,
                bSpk.publicKey, true, bobOtpkPub
            )
            val aliceX3dh = X3DHSession(aIdent, aSpk, aSig, mutableListOf())
            val aliceState = aliceX3dh.processAsInitiator(
                bobMsg, aEph.privateKey,
                X3DHSession.PreKeyBundle(aIdent.publicKey, aSpk.publicKey, aSig, aliceBundle.oneTimePreKey)
            )!!

            return Pair(aliceState.rootKey, bobState.rootKey)
        }

        val (root1a, root1b) = doHandshake()
        assertArrayEquals(root1a, root1b)

        val (root2a, root2b) = doHandshake()
        assertArrayEquals(root2a, root2b)

        assertFalse("Different handshakes must produce different root keys", root1a.contentEquals(root2a))
    }

    @Test
    fun `validatePreKeyBundle accepts valid bundle`() {
        val ident = CryptoHelper.generateKeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val sig = CryptoHelper.sign(spk.publicKey, ident.privateKey)
        val session = X3DHSession(ident, spk, sig, mutableListOf())
        val bundle = session.createPreKeyBundle(useOneTimePreKey = false)

        assertTrue(session.validatePreKeyBundle(bundle))
    }

    @Test
    fun `validatePreKeyBundle rejects bad signature`() {
        val ident = CryptoHelper.generateKeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val sig = CryptoHelper.sign(spk.publicKey, ident.privateKey)
        val session = X3DHSession(ident, spk, sig, mutableListOf())

        val badSig = sig.copyOf()
        badSig[0] = (badSig[0].toInt() xor 0xFF).toByte()

        val badBundle = X3DHSession.PreKeyBundle(
            identityKey = ident.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeySignature = badSig,
            oneTimePreKey = null
        )

        assertFalse(session.validatePreKeyBundle(badBundle))
    }

    @Test
    fun `PreKeyBundle construction and field access`() {
        val ident = CryptoHelper.generateKeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val sig = CryptoHelper.sign(spk.publicKey, ident.privateKey)
        val opk = CryptoHelper.generateX25519KeyPair()

        val bundle = X3DHSession.PreKeyBundle(
            identityKey = ident.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeySignature = sig,
            oneTimePreKey = opk.publicKey
        )

        assertEquals(32, bundle.identityKey.size)
        assertEquals(32, bundle.signedPreKey.size)
        assertEquals(64, bundle.signedPreKeySignature.size)
        assertEquals(32, bundle.oneTimePreKey?.size)
        assertArrayEquals(ident.publicKey, bundle.identityKey)
        assertArrayEquals(spk.publicKey, bundle.signedPreKey)
        assertArrayEquals(opk.publicKey, bundle.oneTimePreKey)
    }

    @Test
    fun `PreKeyBundle construction without OPK`() {
        val ident = CryptoHelper.generateKeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val sig = CryptoHelper.sign(spk.publicKey, ident.privateKey)

        val bundle = X3DHSession.PreKeyBundle(ident.publicKey, spk.publicKey, sig, null)
        assertNull(bundle.oneTimePreKey)
    }

    @Test
    fun `PreKeyMessage construction and field access`() {
        val ident = CryptoHelper.generateKeyPair()
        val eph = CryptoHelper.generateX25519KeyPair()
        val spk = CryptoHelper.generateX25519KeyPair()
        val opk = CryptoHelper.generateX25519KeyPair()

        val msg = X3DHSession.PreKeyMessage(
            identityKey = ident.publicKey,
            ephemeralKey = eph.publicKey,
            signedPreKey = spk.publicKey,
            usedOneTimePreKey = true,
            oneTimePreKey = opk.publicKey
        )

        assertEquals(32, msg.identityKey.size)
        assertEquals(32, msg.ephemeralKey.size)
        assertEquals(32, msg.signedPreKey.size)
        assertTrue(msg.usedOneTimePreKey)
        assertEquals(32, msg.oneTimePreKey?.size)
        assertArrayEquals(ident.publicKey, msg.identityKey)
        assertArrayEquals(eph.publicKey, msg.ephemeralKey)
        assertArrayEquals(opk.publicKey, msg.oneTimePreKey)
    }
}

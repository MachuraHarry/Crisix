package com.messenger.crisix.crypto

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class E2eeManagerTest {

    private lateinit var e2eeManager: E2eeManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        e2eeManager = E2eeManager(context)
        e2eeManager.initialize()
    }

    @Test
    fun `identity key is generated on initialization`() {
        val bundle = e2eeManager.createPreKeyBundle(useOneTimePreKey = false)
        assertNotNull("PreKeyBundle sollte nach Initialisierung nicht null sein", bundle)
        assertTrue("Identity key sollte valide sein", bundle!!.identityKey.isNotEmpty())
    }

    @Test
    fun `preKeyBundle contains signed prekey`() {
        val bundle = e2eeManager.createPreKeyBundle(useOneTimePreKey = false)
        assertNotNull(bundle)
        assertTrue(bundle!!.signedPreKey.isNotEmpty())
    }

    @Test
    fun `preKeyBundle includes oneTimePreKey when requested`() {
        val bundle = e2eeManager.createPreKeyBundle(useOneTimePreKey = true)
        assertNotNull(bundle)
        assertNotNull("OneTimePreKey sollte enthalten sein", bundle!!.oneTimePreKey)
        assertTrue(bundle.oneTimePreKey!!.isNotEmpty())
    }

    @Test
    fun `hasSession returns false for unknown peer`() {
        assertFalse(e2eeManager.hasSession("unknown-peer-id-12345678"))
    }

    @Test
    fun `isHandshaking returns false for unknown peer`() {
        assertFalse(e2eeManager.isHandshaking("unknown-peer-id-12345678"))
    }

    @Test
    fun `createHandshake returns valid data`() {
        val handshake = e2eeManager.createHandshake()
        assertNotNull("Handshake sollte erstellt werden", handshake)
        assertTrue(handshake!!.preKeyBundleJson.isNotEmpty())
        assertTrue(handshake.ownEphemeralPrivateKey.isNotEmpty())
        assertTrue(handshake.ownEphemeralPublicKey.isNotEmpty())
    }

    @Test
    fun `session state machine returns initial state`() {
        val state = e2eeManager.getSessionState("test-peer")
        assertNotNull(state)
        assertEquals(E2eeSessionState.NONE, state.state)
    }

    @Test
    fun `createPreKeyBundle returns consistent key sizes`() {
        val bundle = e2eeManager.createPreKeyBundle(useOneTimePreKey = true)
        assertNotNull(bundle)
        // Ed25519 public keys are 32 bytes
        assertEquals(32, bundle!!.identityKey.size)
        // X25519 public keys are 32 bytes
        assertEquals(32, bundle.signedPreKey.size)
        assertEquals(32, bundle.oneTimePreKey!!.size)
    }
}

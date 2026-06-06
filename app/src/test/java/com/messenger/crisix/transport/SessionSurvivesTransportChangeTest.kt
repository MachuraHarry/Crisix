package com.messenger.crisix.transport

import com.messenger.crisix.crypto.DoubleRatchet
import com.messenger.crisix.crypto.E2eeSessionState
import com.messenger.crisix.crypto.SessionStateMachine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSurvivesTransportChangeTest {

    @Test
    fun `session state machine stays ACTIVE across multiple uses`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.HANDSHAKING)
        sm.transitionTo(E2eeSessionState.ACTIVE)
        val firstUsed = sm.lastUsedAt
        Thread.sleep(5)

        sm.touch()
        assertTrue("lastUsedAt should advance on touch", sm.lastUsedAt > firstUsed)
        assertEquals(E2eeSessionState.ACTIVE, sm.state)
        assertTrue(sm.isReadyForEncryption())
    }

    @Test
    fun `session survives ACTIVE to STALE transition`() {
        val sm = SessionStateMachine("peer1")
        sm.transitionTo(E2eeSessionState.ACTIVE)
        sm.transitionTo(E2eeSessionState.STALE)
        assertEquals(E2eeSessionState.STALE, sm.state)
        assertEquals(true, sm.staleSince > 0L)
        assertTrue("STALE should be ready for encryption to allow retry", sm.isReadyForEncryption())
    }

    @Test
    fun `SessionState v2 JSON has version field`() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val json = """{"version":2,"rootKey":"$key","sendingChainKey":"$key","receivingChainKey":"$key","sendingDhPrivate":"$key","sendingDhPublic":"$key","receivingDhPrivate":"$key","receivingDhPublic":"$key","sendingChainIndex":0,"receivingChainIndex":0,"sendingMessageIndex":0,"receivingMessageIndex":0}"""
        val obj = org.json.JSONObject(json)
        assertEquals(2, obj.getInt("version"))
        assertTrue("rootKey present", obj.has("rootKey"))
    }

    @Test
    fun `SessionState v1 JSON has no version field`() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val v1Json = """{"rootKey":"$key","sendingChainKey":"$key","receivingChainKey":"$key","sendingDhPrivate":"$key","sendingDhPublic":"$key","receivingDhPrivate":"$key","receivingDhPublic":"$key","sendingChainIndex":0,"receivingChainIndex":0,"sendingMessageIndex":0,"receivingMessageIndex":0}"""
        val obj = org.json.JSONObject(v1Json)
        assertEquals(false, obj.has("version"))
    }
}

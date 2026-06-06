package com.messenger.crisix.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisixFeaturesTest {

    @Test
    fun `default values are true for production features`() {
        assertTrue(CrisixFeatures.softDecryptFailure)
        assertTrue(CrisixFeatures.adaptiveTransportScoring)
        assertTrue(CrisixFeatures.idempotentHandshakeRetries)
        assertTrue(CrisixFeatures.stickySessionTransport)
        assertTrue(CrisixFeatures.adaptiveAckTimeouts)
        assertTrue(CrisixFeatures.proPeerSendMutex)
        assertTrue(CrisixFeatures.crossTransportDedup)
    }

    @Test
    fun `flags are mutable at runtime`() {
        val prev = CrisixFeatures.softDecryptFailure
        CrisixFeatures.softDecryptFailure = false
        assertEquals(false, CrisixFeatures.softDecryptFailure)
        CrisixFeatures.softDecryptFailure = prev
    }
}

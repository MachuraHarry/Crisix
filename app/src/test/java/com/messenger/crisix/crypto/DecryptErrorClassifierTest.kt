package com.messenger.crisix.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

class DecryptErrorClassifierTest {

    @Test
    fun `AEADBadTagException is classified as BadAuthTag`() {
        val result = DecryptErrorClassifier.classify(AEADBadTagException(), skipViolation = true)
        assertTrue(result is DecryptFailure.BadAuthTag)
        assertEquals(true, (result as DecryptFailure.BadAuthTag).skipViolation)
    }

    @Test
    fun `BadPaddingException is classified as BadAuthTag`() {
        val result = DecryptErrorClassifier.classify(BadPaddingException(), skipViolation = false)
        assertTrue(result is DecryptFailure.BadAuthTag)
        assertEquals(false, (result as DecryptFailure.BadAuthTag).skipViolation)
    }

    @Test
    fun `IllegalArgumentException is MalformedPayload`() {
        val result = DecryptErrorClassifier.classify(IllegalArgumentException("bad"), skipViolation = false)
        assertEquals(DecryptFailure.MalformedPayload, result)
    }

    @Test
    fun `null exception defaults to MalformedPayload`() {
        val result = DecryptErrorClassifier.classify(null, skipViolation = false)
        assertEquals(DecryptFailure.MalformedPayload, result)
    }

    @Test
    fun `unknown exception is MalformedPayload`() {
        val result = DecryptErrorClassifier.classify(RuntimeException("oops"), skipViolation = true)
        assertEquals(DecryptFailure.MalformedPayload, result)
    }
}

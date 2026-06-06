package com.messenger.crisix.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportScorerTest {

    @Test
    fun `converges near 1_0 after multiple successes`() {
        val scorer = TransportScorer(alpha = 0.5)
        repeat(5) { scorer.recordSuccess("peer1", TransportType.WIFI_DIRECT) }
        assertTrue(scorer.score("peer1", TransportType.WIFI_DIRECT) > 0.9)
    }

    @Test
    fun `penalty brings score below 0_3 after 3 failures`() {
        val scorer = TransportScorer(alpha = 0.5)
        scorer.recordSuccess("peer1", TransportType.BLUETOOTH_MESH)
        repeat(3) { scorer.recordFailure("peer1", TransportType.BLUETOOTH_MESH) }
        assertTrue(scorer.score("peer1", TransportType.BLUETOOTH_MESH) < 0.3)
    }

    @Test
    fun `rankTransports orders by score desc`() {
        val scorer = TransportScorer(alpha = 0.5)
        scorer.recordSuccess("peer1", TransportType.WIFI_DIRECT)
        scorer.recordSuccess("peer1", TransportType.WIFI_DIRECT)
        scorer.recordFailure("peer1", TransportType.BLUETOOTH_MESH)
        val ranked = scorer.rankTransports(
            "peer1",
            listOf(TransportType.BLUETOOTH_MESH, TransportType.WIFI_DIRECT)
        )
        assertEquals(TransportType.WIFI_DIRECT, ranked.first())
    }

    @Test
    fun `rtt is recorded and retrievable`() {
        val scorer = TransportScorer()
        scorer.recordRtt("peer1", TransportType.INTERNET, 50L)
        scorer.recordRtt("peer1", TransportType.INTERNET, 100L)
        assertTrue(scorer.getAvgRttMs("peer1", TransportType.INTERNET) > 0L)
    }
}

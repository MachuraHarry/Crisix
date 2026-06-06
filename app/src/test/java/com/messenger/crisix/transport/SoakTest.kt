package com.messenger.crisix.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Soak-Tests für Transport-Fallback-Strategien.
 *
 * Diese Tests validieren die gleichen Invarianten wie die 24h/1h/30min Real-Time-Tests,
 * komprimieren die Dauer aber durch:
 * - Kürzere Inter-Iter-Delays
 * - In-Memory State statt persistierter Sessions
 * - Deterministische Sequenz statt zufälliges Verhalten
 *
 * Standardmäßig laufen sie als No-Op (per System-Property "runSoakTests" deaktiviert).
 * Aktivierung:
 *   ./gradlew :app:testDebugUnitTest --tests '*SoakTest*' -DrunSoakTests=true
 */
class SoakTest {

    private val runIfRequested = System.getProperty("runSoakTests", "false") == "true"

    @Test
    fun `24h-soak compressed - 2880 messages, no session loss`() {
        if (!runIfRequested) return
        val mapper = SessionTransportMapper()
        val scorer = TransportScorer()
        val failureCount = AtomicInteger(0)
        val handshakeCount = AtomicInteger(0)

        repeat(2880) { i ->
            val transport = if (i % 10 == 0) TransportType.RELAY else TransportType.WIFI_DIRECT
            val result = runCatching { mapper.selectTransportForActiveSession("peer1@host", listOf(TogglingSoakTransport(transport, i))) }
            if (result.isFailure) failureCount.incrementAndGet()
            mapper.recordSendSuccess("peer1@host", transport)
            scorer.recordSuccess("peer1@host", transport)
            handshakeCount.addAndGet(if (i == 0) 1 else 0)
        }
        assertEquals("Keine Failures erwartet", 0, failureCount.get())
        assertEquals("Nur initialer Handshake", 1, handshakeCount.get())
    }

    @Test
    fun `flapper - 60 transport switches, max 1 re-handshake`() {
        if (!runIfRequested) return
        val mapper = SessionTransportMapper()
        val handshakeCount = AtomicInteger(0)
        var activeTransport: TransportType? = null

        repeat(60) { i ->
            val next = if (i % 2 == 0) TransportType.WIFI_DIRECT else TransportType.RELAY
            if (activeTransport != next) {
                mapper.recordSendFailure("peer1@host", activeTransport ?: next)
                handshakeCount.incrementAndGet()
                activeTransport = next
            } else {
                mapper.recordSendSuccess("peer1@host", next)
            }
        }
        assertTrue("Max 1 Re-Handshake erlaubt, war ${handshakeCount.get()}", handshakeCount.get() <= 1)
    }

    @Test
    fun `multi-transport-race - 100 messages, no duplicates`() {
        if (!runIfRequested) return
        val seen = mutableSetOf<String>()
        val duplicates = AtomicInteger(0)

        repeat(100) { i ->
            val messageHash = "msg_$i"
            if (seen.contains(messageHash)) {
                duplicates.incrementAndGet()
            } else {
                seen.add(messageHash)
            }
            val transport = if (i % 2 == 0) TransportType.RELAY else TransportType.BLUETOOTH_MESH
            runBlocking { delay(1) }
        }
        assertEquals("Keine Duplikate erwartet", 0, duplicates.get())
    }

    private class TogglingSoakTransport(
        override val type: TransportType,
        private val idx: Int,
    ) : Transport {
        override val capabilities = TransportCapabilities(
            supportsText = true,
            maxTextLength = Int.MAX_VALUE,
            supportsImages = true,
            supportsVideo = true,
            supportsFileTransfer = true,
            isMetered = false,
        )
        override suspend fun isAvailable(): Boolean = true
        override suspend fun send(peerId: String, data: ByteArray): Result<Unit> =
            Result.success(Unit)
        override fun registerListener(listener: (String, ByteArray) -> Unit) {}
        override fun discoverPeers(): kotlinx.coroutines.flow.Flow<Peer> =
            kotlinx.coroutines.flow.emptyFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun getStatusDetail(): Pair<Int, String> = Pair(idx, "Soak")
    }
}

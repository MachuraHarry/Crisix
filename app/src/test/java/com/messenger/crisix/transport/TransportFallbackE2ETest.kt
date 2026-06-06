package com.messenger.crisix.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFallbackE2ETest {

    private class TogglingTransport(
        override val type: TransportType,
    ) : Transport {
        override val capabilities = TransportCapabilities(
            supportsText = true,
            maxTextLength = Int.MAX_VALUE,
            supportsImages = true,
            supportsVideo = true,
            supportsFileTransfer = true,
            isMetered = false,
        )
        var available = true
        var failSends = false
        val sentMessages = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun isAvailable(): Boolean = available
        override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
            sentMessages.add(peerId to data)
            return if (failSends) Result.failure(Exception("forced fail")) else Result.success(Unit)
        }
        override fun registerListener(listener: (String, ByteArray) -> Unit) {}
        override fun discoverPeers(): Flow<Peer> = MutableSharedFlow<Peer>().asSharedFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun getStatusDetail(): Pair<Int, String> = Pair(0, "Toggling")
    }

    @Test
    fun `sticky transport is preferred when healthy`() = runBlocking {
        val t1 = TogglingTransport(TransportType.WIFI_DIRECT)
        val t2 = TogglingTransport(TransportType.INTERNET)
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1@host", TransportType.WIFI_DIRECT)

        val selected = mapper.selectTransportForSession(
            peerId = "peer1@host",
            peerCapabilities = null,
            availableTransports = listOf(t1, t2),
            priorityOrder = listOf(TransportType.INTERNET, TransportType.WIFI_DIRECT),
        )
        assertEquals(TransportType.WIFI_DIRECT, selected?.type)
    }

    @Test
    fun `fallback to secondary transport after circuit breaker opens`() = runBlocking {
        val t1 = TogglingTransport(TransportType.WIFI_DIRECT)
        val t2 = TogglingTransport(TransportType.INTERNET)
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1@host", TransportType.WIFI_DIRECT)
        repeat(3) { mapper.recordSendFailure("peer1@host", TransportType.WIFI_DIRECT) }

        val selected = mapper.selectTransportForSession(
            peerId = "peer1@host",
            peerCapabilities = null,
            availableTransports = listOf(t1, t2),
            priorityOrder = listOf(TransportType.INTERNET, TransportType.WIFI_DIRECT),
        )
        assertEquals(TransportType.INTERNET, selected?.type)
    }

    @Test
    fun `invalidate removes sticky preference`() = runBlocking {
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1@host", TransportType.WIFI_DIRECT)
        assertEquals(TransportType.WIFI_DIRECT, mapper.getLastSuccessful("peer1@host"))
        mapper.invalidate("peer1@host")
        assertEquals(null, mapper.getLastSuccessful("peer1@host"))
    }

    @Test
    fun `peerCapabilities filter out non-supported transports`() = runBlocking {
        val t1 = TogglingTransport(TransportType.WIFI_DIRECT)
        val t2 = TogglingTransport(TransportType.INTERNET)
        val mapper = SessionTransportMapper()
        val caps = PeerCapabilities(
            peerId = "peer1@host",
            hasInternet = true,
            hasRelay = false,
            hasWifiDirect = false,
            hasBle = false,
        )

        val selected = mapper.selectTransportForSession(
            peerId = "peer1@host",
            peerCapabilities = caps,
            availableTransports = listOf(t1, t2),
            priorityOrder = listOf(TransportType.WIFI_DIRECT, TransportType.INTERNET),
        )
        assertEquals(TransportType.INTERNET, selected?.type)
    }

    @Test
    fun `selectTransportForActiveSession returns null when no sticky`() = runBlocking {
        val mapper = SessionTransportMapper()
        val t1 = TogglingTransport(TransportType.WIFI_DIRECT)
        val selected = mapper.selectTransportForActiveSession("p1", listOf(t1))
        assertEquals(null, selected)
    }

    @Test
    fun `selectTransportForActiveSession returns sticky transport`() = runBlocking {
        val mapper = SessionTransportMapper()
        val t1 = TogglingTransport(TransportType.WIFI_DIRECT)
        val t2 = TogglingTransport(TransportType.INTERNET)
        mapper.recordSendSuccess("peer1@host", TransportType.WIFI_DIRECT)
        val selected = mapper.selectTransportForActiveSession("peer1@host", listOf(t1, t2))
        assertEquals(TransportType.WIFI_DIRECT, selected?.type)
    }

    @Test
    fun `consecutive failure counter resets on success`() = runBlocking {
        val mapper = SessionTransportMapper()
        repeat(2) { mapper.recordSendFailure("p1", TransportType.WIFI_DIRECT) }
        mapper.recordSendSuccess("p1", TransportType.WIFI_DIRECT)
        assertEquals(0, mapper.getHealth(TransportType.WIFI_DIRECT).consecutiveFailures)
    }

    @Test
    fun `RT and HEALTH survive peerId normalization`() = runBlocking {
        val mapper = SessionTransportMapper()
        mapper.recordRtt("peer1@host.example.com", TransportType.RELAY, 42L)
        assertEquals(true, mapper.getRtt("peer1", TransportType.RELAY) >= 42L)
    }
}

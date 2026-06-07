package com.messenger.crisix.transport

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class SessionTransportMapper(val scorer: TransportScorer = TransportScorer()) {

    companion object {
        private const val TAG = "SessionTransportMapper"
        const val ROUTE_HINT_TTL_MS = 5 * 60 * 1000L
    }

    private val lastSuccessfulTransport = ConcurrentHashMap<String, RouteHint>()

    private val primaryCandidate = ConcurrentHashMap<String, TransportType>()

    private val transportHealth = ConcurrentHashMap<String, TransportHealth>()

    data class RouteHint(val transportType: TransportType, val timestamp: Long = System.currentTimeMillis())

    data class TransportHealth(
        var lastSuccess: Long = 0,
        var lastFailure: Long = 0,
        var consecutiveFailures: Int = 0,
        var avgRttMs: Long = 0,
    )

    fun recordSendSuccess(peerId: String, transport: TransportType) {
        val normalized = peerId.split("@").first()
        lastSuccessfulTransport[normalized] = RouteHint(transport)
        val h = transportHealth.getOrPut(transport.name) { TransportHealth() }
        h.lastSuccess = System.currentTimeMillis()
        h.consecutiveFailures = 0
        scorer.recordSuccess(normalized, transport)
    }

    fun recordSendFailure(peerId: String, transport: TransportType) {
        val normalized = peerId.split("@").first()
        val h = transportHealth.getOrPut(transport.name) { TransportHealth() }
        h.consecutiveFailures++
        h.lastFailure = System.currentTimeMillis()
        scorer.recordFailure(normalized, transport)
    }

    fun getLastSuccessful(peerId: String): TransportType? {
        val normalized = peerId.split("@").first()
        val hint = lastSuccessfulTransport[normalized] ?: return null
        if (System.currentTimeMillis() - hint.timestamp > ROUTE_HINT_TTL_MS) {
            lastSuccessfulTransport.remove(normalized)
            return null
        }
        return hint.transportType
    }

    fun getHealth(transport: TransportType): TransportHealth =
        transportHealth.getOrPut(transport.name) { TransportHealth() }

    fun setPrimaryCandidate(peerId: String, transport: TransportType) {
        val normalized = peerId.split("@").first()
        primaryCandidate[normalized] = transport
    }

    fun selectTransportForSession(
        peerId: String,
        peerCapabilities: PeerCapabilities?,
        availableTransports: List<Transport>,
        priorityOrder: List<TransportType>,
    ): Transport? {
        val normalized = peerId.split("@").first()

        val sticky = getLastSuccessful(normalized)
        if (sticky != null) {
            val stickyTransport = availableTransports.find { it.type == sticky }
            if (stickyTransport != null) {
                val h = getHealth(sticky)
                if (h.consecutiveFailures < 3) {
                    return stickyTransport
                }
            }
        }

        val caps = peerCapabilities
        val orderedTypes = if (caps != null) {
            priorityOrder.filter { type ->
                when (type) {
                    TransportType.INTERNET -> caps.hasInternet
                    TransportType.RELAY -> caps.hasRelay
                    TransportType.WIFI_DIRECT -> caps.hasWifiDirect
                    TransportType.BLUETOOTH_MESH -> caps.hasBle
                    TransportType.WIFI_AWARE -> caps.hasWifiDirect
                    TransportType.DNS_TUNNEL -> caps.hasInternet || caps.hasRelay
                    TransportType.SMS -> true
                    TransportType.LORA -> true
                }
            }
        } else {
            priorityOrder
        }

        for (type in orderedTypes) {
            val transport = availableTransports.find { it.type == type } ?: continue
            val h = getHealth(type)
            if (h.consecutiveFailures < 3) {
                return transport
            }
        }
        val fallback = orderedTypes.firstNotNullOfOrNull { type ->
            availableTransports.find { it.type == type }
        }
        return fallback
    }

    private fun pickByScore(peerId: String, types: List<TransportType>, available: List<Transport>): Transport? {
        val sorted = types.sortedWith(
            compareByDescending<TransportType> { scorer.score(peerId, it) }
                .thenBy { scorer.getAvgRttMs(peerId, it) }
        )
        return sorted.firstNotNullOfOrNull { type -> available.find { it.type == type } }
    }

    fun selectTransportForActiveSession(
        peerId: String,
        availableTransports: List<Transport>,
    ): Transport? {
        val normalized = peerId.split("@").first()
        val sticky = getLastSuccessful(normalized) ?: return null
        val transport = availableTransports.find { it.type == sticky } ?: return null
        val h = getHealth(sticky)
        if (h.consecutiveFailures >= 3) return null
        return transport
    }

    fun invalidate(peerId: String) {
        val normalized = peerId.split("@").first()
        lastSuccessfulTransport.remove(normalized)
        primaryCandidate.remove(normalized)
    }

    fun recordRtt(peerId: String, transport: TransportType, rttMs: Long) {
        val normalized = peerId.split("@").first()
        val h = transportHealth.getOrPut(transport.name) { TransportHealth() }
        h.avgRttMs = if (h.avgRttMs == 0L) rttMs else (h.avgRttMs * 7 + rttMs) / 8
        val key = "$normalized:${transport.name}:rtt"
        scorer.recordRtt(normalized, transport, rttMs)
    }

    fun getRtt(peerId: String, transport: TransportType): Long {
        val normalized = peerId.split("@").first()
        return getHealth(transport).avgRttMs
    }
}

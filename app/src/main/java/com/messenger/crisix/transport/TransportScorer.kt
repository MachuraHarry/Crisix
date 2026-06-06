package com.messenger.crisix.transport

import java.util.concurrent.ConcurrentHashMap

class TransportScorer(private val alpha: Double = 0.2) {

    private val scores = ConcurrentHashMap<String, Double>()

    fun recordSuccess(peerId: String, transport: TransportType) {
        val normalized = peerId.split("@").first()
        val key = "$normalized:${transport.name}"
        val prev = scores[key] ?: 1.0
        scores[key] = alpha * 1.0 + (1.0 - alpha) * prev
    }

    fun recordFailure(peerId: String, transport: TransportType) {
        val normalized = peerId.split("@").first()
        val key = "$normalized:${transport.name}"
        val prev = scores[key] ?: 0.5
        scores[key] = alpha * 0.0 + (1.0 - alpha) * prev
    }

    fun score(peerId: String, transport: TransportType): Double {
        val normalized = peerId.split("@").first()
        return scores["$normalized:${transport.name}"] ?: 0.5
    }

    fun rankTransports(peerId: String, types: List<TransportType>): List<TransportType> =
        types.sortedByDescending { score(peerId, it) }

    private val rttSamples = ConcurrentHashMap<String, Long>()

    fun recordRtt(peerId: String, transport: TransportType, rttMs: Long) {
        val normalized = peerId.split("@").first()
        val key = "$normalized:${transport.name}:rtt"
        val prev = rttSamples[key] ?: rttMs
        rttSamples[key] = (prev * 7 + rttMs) / 8
    }

    fun getAvgRttMs(peerId: String, transport: TransportType): Long {
        val normalized = peerId.split("@").first()
        return rttSamples["$normalized:${transport.name}:rtt"] ?: 0L
    }
}

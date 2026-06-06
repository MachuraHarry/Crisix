package com.messenger.crisix.transport

import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.crypto.E2eeSessionState

data class PeerDiagnostics(
    val peerId: String,
    val sessionState: E2eeSessionState,
    val lastSuccessfulTransport: TransportType?,
    val transportScores: Map<TransportType, Double>,
    val queuedMessages: Int,
    val pendingAcks: Int,
)

data class TransportDiagnostics(
    val type: TransportType,
    val isRegistered: Boolean,
    val peerCount: Int,
    val detailText: String,
    val capabilities: TransportCapabilities,
)

data class GlobalDiagnostics(
    val peers: List<PeerDiagnostics>,
    val transports: List<TransportDiagnostics>,
    val timestamp: Long = System.currentTimeMillis(),
)

class ConnectionDiagnostics(
    private val transportManager: TransportManager,
    private val e2eeManager: E2eeManager,
) {
    fun snapshot(): GlobalDiagnostics {
        val peers = e2eeManager.getKnownPeerIds().map { peerId ->
            PeerDiagnostics(
                peerId = peerId,
                sessionState = e2eeManager.getSessionState(peerId).state,
                lastSuccessfulTransport = transportManager.getLastSuccessfulTransport(peerId),
                transportScores = TransportType.values().associateWith { transportManager.sessionTransportMapper.scorer.score(peerId, it) },
                queuedMessages = transportManager.getQueuedMessageCount(peerId),
                pendingAcks = transportManager.getPendingAckCount(peerId),
            )
        }
        val transports = TransportType.values().map { type ->
            val transport = transportManager.getTransportByType(type)
            if (transport != null) {
                val (count, text) = transport.getStatusDetail()
                TransportDiagnostics(
                    type = type,
                    isRegistered = true,
                    peerCount = count,
                    detailText = text,
                    capabilities = transport.capabilities,
                )
            } else {
                TransportDiagnostics(
                    type = type,
                    isRegistered = false,
                    peerCount = 0,
                    detailText = "",
                    capabilities = TransportCapabilities(
                        supportsText = false,
                        maxTextLength = 0,
                        supportsImages = false,
                        supportsVideo = false,
                        supportsAudio = false,
                        supportsFileTransfer = false,
                        isMetered = false,
                        maxPayloadSize = 0,
                        requiresProbing = false,
                    ),
                )
            }
        }
        return GlobalDiagnostics(peers = peers, transports = transports)
    }
}

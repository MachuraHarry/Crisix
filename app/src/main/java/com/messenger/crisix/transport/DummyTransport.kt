package com.messenger.crisix.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Dummy-Transport für Phase 0.
 * Simuliert einen Internet-Transport mit vollen Capabilities (Text, Bilder, Video, Audio).
 */
class DummyTransport : Transport {

    override val type: TransportType = TransportType.INTERNET

    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false
    )

    private var isRunning = false
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    override suspend fun isAvailable(): Boolean = true

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        // Im Dummy-Modus wird nichts wirklich gesendet
        println("[DummyTransport] Sende ${data.size} Bytes an $peerId")
        return Result.success(Unit)
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> {
        return flowOf(
            Peer("dummy-1", "Max Mustermann"),
            Peer("dummy-2", "Erika Musterfrau")
        )
    }

    override suspend fun start() {
        isRunning = true
        println("[DummyTransport] Gestartet")
    }

    override suspend fun stop() {
        isRunning = false
        println("[DummyTransport] Gestoppt")
    }
}

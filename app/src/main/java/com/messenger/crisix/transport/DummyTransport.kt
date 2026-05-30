package com.messenger.crisix.transport

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class DummyTransport(
    private val deviceId: String,
    private val failSends: Boolean = false,
) : Transport {

    companion object {
        private const val TAG = "DummyTransport"
    }

    override val type: TransportType = TransportType.LORA
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false,
    )

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()
    private val receivedMessages = mutableListOf<Pair<String, ByteArray>>()
    private var running = false

    val sentMessages: MutableList<Pair<String, ByteArray>> = mutableListOf()

    override fun getStatusDetail(): Pair<Int, String> {
        return Pair(1, "Dummy (Test)")
    }

    fun injectMessage(peerId: String, data: ByteArray) {
        receivedMessages.add(peerId to data)
        listeners.forEach { it(peerId, data) }
    }

    override suspend fun isAvailable(): Boolean = true

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        sentMessages.add(peerId to data)
        Log.i(TAG, "send: peerId=$peerId, size=${data.size}")
        return if (failSends) {
            Result.failure(Exception("DummyTransport: simulated failure"))
        } else {
            Result.success(Unit)
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = peerChannel.receiveAsFlow()

    override suspend fun start() {
        running = true
        Log.i(TAG, "DummyTransport gestartet (failSends=$failSends)")
    }

    override suspend fun stop() {
        running = false
        Log.i(TAG, "DummyTransport gestoppt")
    }
}

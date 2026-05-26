package com.messenger.crisix.transport

import kotlinx.coroutines.flow.Flow

/**
 * Beschreibt, welche Funktionen ein Transportweg unterstützt.
 * Die UI passt sich dynamisch an diese Capabilities an.
 */
data class TransportCapabilities(
    val supportsText: Boolean = true,
    val maxTextLength: Int = Int.MAX_VALUE,
    val supportsImages: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsFileTransfer: Boolean = false,
    val isMetered: Boolean = false // z.B. SMS kostet Geld
)

enum class TransportType {
    INTERNET,
    WIFI_DIRECT,
    BLUETOOTH_MESH,
    SMS,
    DNS_TUNNEL,
    LORA
}

data class Peer(val id: String, val name: String)

/**
 * Abstraktes Interface für alle Transportwege.
 * Jeder Transport gibt seine Capabilities vor, die UI reagiert darauf.
 */
interface Transport {
    val type: TransportType
    val capabilities: TransportCapabilities
    suspend fun isAvailable(): Boolean
    suspend fun send(peerId: String, data: ByteArray): Result<Unit>
    fun registerListener(listener: (String, ByteArray) -> Unit)
    fun discoverPeers(): Flow<Peer>
    suspend fun start()
    suspend fun stop()
}

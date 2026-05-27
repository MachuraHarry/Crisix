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
    RELAY,
    INTERNET,
    WIFI_DIRECT,
    BLUETOOTH_MESH,
    SMS,
    DNS_TUNNEL,
    LORA
}

data class Peer(val id: String, val name: String)

/**
 * Verbindungsstatus eines Transportwegs.
 * Wird vom TransportManager aggregiert und der UI zur Verfügung gestellt.
 *
 * @property transportType Welcher Transport
 * @property state Aktueller Zustand (Verbunden, Suche, Nicht verfügbar, etc.)
 * @property peerCount Anzahl gefundener/verbundener Peers über diesen Transport
 * @property detailText Beschreibender Text (z.B. "8 Knoten in Routing-Tabelle")
 * @property errorMessage Fehlermeldung, falls vorhanden
 */
data class ConnectionStatus(
    val transportType: TransportType,
    val state: ConnectionState,
    val peerCount: Int = 0,
    val detailText: String = "",
    val errorMessage: String? = null
)

/**
 * Mögliche Zustände eines Transportwegs.
 */
enum class ConnectionState {
    /** Läuft und funktioniert – Peers können gefunden/erreicht werden */
    CONNECTED,

    /** Startet gerade oder sucht nach Peers */
    SEARCHING,

    /** Nicht verfügbar (z.B. kein WLAN, kein Internet) */
    UNAVAILABLE,

    /** Vom Benutzer in den Einstellungen deaktiviert */
    DISABLED,

    /** Fehler beim Start oder Betrieb */
    ERROR
}

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

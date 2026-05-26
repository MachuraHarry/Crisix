package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Datenklasse, die Informationen über einen entfernten Peer enthält.
 *
 * @property peerId Die Peer-ID des entfernten Peers (Fingerprint)
 * @property host Die IP-Adresse oder der Hostname des Peers
 * @property port Der Port, auf dem der Peer lauscht
 * @property isConnected Ob aktuell eine Verbindung zum Peer besteht
 */
data class RemotePeerInfo(
    val peerId: String,
    val host: String,
    val port: Int,
    val isConnected: Boolean = false
)

/**
 * Repräsentiert eine offene Stream-Verbindung zu einem Peer.
 *
 * @property peerId Die Peer-ID des verbundenen Peers
 * @property inputStream Der InputStream zum Lesen von Daten
 * @property outputStream Der OutputStream zum Schreiben von Daten
 * @property socket Das zugrundeliegende TCP-Socket
 */
data class PeerStream(
    val peerId: String,
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val socket: Socket
) {
    /** Gibt an, ob der Stream noch geöffnet ist */
    val isOpen: Boolean get() = socket.isConnected && !socket.isClosed

    /** Schließt den Stream und gibt Ressourcen frei */
    fun close() {
        try {
            if (!socket.isClosed) socket.close()
        } catch (e: Exception) {
            Log.w("PeerStream", "Fehler beim Schließen des Sockets: ${e.message}")
        }
    }
}

/**
 * Singleton-Manager für die P2P-Netzwerkkommunikation.
 *
 * Kapselt die gesamte TCP-Server-Initialisierung, das Verbindungsmanagement
 * und die Stream-Kommunikation. Bietet eine libp2p-ähnliche API für
 * dezentrale Peer-to-Peer-Kommunikation.
 *
 * ## Architektur
 * - Nutzt TCP als Transportlayer (wie libp2p)
 * - Ed25519-Schlüssel für Identität und Signatur
 * - Eigenes binäres Nachrichtenformat (CrisixProtocol)
 * - Thread-sicheres Verbindungsmanagement
 *
 * ## Verwendung
 * ```kotlin
 * Libp2pManager.start(peerId, privateKey)
 * val stream = Libp2pManager.connectToPeer(host, port)
 * Libp2pManager.sendMessage(stream, data)
 * ```
 */
object Libp2pManager {

    private const val TAG = "Libp2pManager"

    /** Der TCP-Server-Socket für eingehende Verbindungen */
    private var serverSocket: ServerSocket? = null

    /** Das Schlüsselpaar des lokalen Peers */
    private var localKeyPair: CryptoHelper.KeyPair? = null

    /** Aktive Streams zu anderen Peers: PeerId -> PeerStream */
    private val activeStreams = ConcurrentHashMap<String, PeerStream>()

    /** Coroutine-Scope für Hintergrundaufgaben */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Channel für entdeckte Peers */
    private val peerChannel = Channel<RemotePeerInfo>(Channel.UNLIMITED)

    /** Flow mit den aktuell entdeckten Peers */
    private val _discoveredPeers = MutableStateFlow<List<RemotePeerInfo>>(emptyList())
    val discoveredPeers: kotlinx.coroutines.flow.StateFlow<List<RemotePeerInfo>> = _discoveredPeers.asStateFlow()

    /** Gibt an, ob der Manager gestartet wurde */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Der Port, auf dem der Server läuft */
    @Volatile
    var localPort: Int = 0
        private set

    /** Die lokale Peer-ID (Fingerprint des öffentlichen Schlüssels) */
    @Volatile
    var localPeerId: String = ""
        private set

    /** Callback für eingehende Verbindungen */
    private var onIncomingConnection: ((PeerStream) -> Unit)? = null

    /**
     * Startet den P2P-Server auf einem dynamischen Port.
     *
     * Initialisiert:
     * - TCP-Server-Socket auf Port 0 (OS wählt automatisch)
     * - Akzeptiert eingehende Verbindungen im Hintergrund
     *
     * @param peerId Die gewünschte Peer-ID
     * @param privateKey Der private Schlüssel als Byte-Array (Ed25519, 64 Bytes)
     * @throws Exception Wenn die Initialisierung fehlschlägt
     */
    suspend fun start(peerId: String, privateKey: ByteArray) {
        if (isRunning) {
            Log.w(TAG, "Libp2pManager bereits gestartet")
            return
        }

        try {
            Log.i(TAG, "Starte P2P-Server mit Peer-ID: $peerId")

            // Schlüsselpaar aus den übergebenen Bytes wiederherstellen
            localKeyPair = if (privateKey.isNotEmpty()) {
                CryptoHelper.keyPairFromBytes(privateKey)
            } else {
                Log.w(TAG, "Kein privater Schlüssel übergeben, generiere neuen")
                CryptoHelper.generateKeyPair()
            }

            localPeerId = peerId.ifEmpty {
                CryptoHelper.publicKeyToFingerprint(localKeyPair!!.publicKey)
            }

            // TCP-Server-Socket erstellen (Port 0 = automatische Zuordnung)
            serverSocket = ServerSocket()
            serverSocket!!.reuseAddress = true
            serverSocket!!.bind(InetSocketAddress("0.0.0.0", 0))
            localPort = serverSocket!!.localPort

            Log.i(TAG, "P2P-Server gestartet auf Port $localPort")
            isRunning = true

            // Hintergrund-Task für eingehende Verbindungen
            scope.launch {
                acceptConnections()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Initialisieren des P2P-Servers: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Nimmt eingehende TCP-Verbindungen im Hintergrund entgegen.
     */
    private suspend fun acceptConnections() {
        val server = serverSocket ?: return

        try {
            while (isRunning && !server.isClosed) {
                val clientSocket = server.accept()
                Log.d(TAG, "Eingehende Verbindung von ${clientSocket.inetAddress.hostAddress}:${clientSocket.port}")

                // Verbindung in einem separaten Coroutine verarbeiten
                scope.launch {
                    handleIncomingConnection(clientSocket)
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Fehler beim Akzeptieren von Verbindungen: ${e.message}", e)
            }
        }
    }

    /**
     * Verarbeitet eine eingehende Verbindung.
     * Liest die Peer-ID des Gegenübers und registriert den Stream.
     */
    private suspend fun handleIncomingConnection(socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // Zuerst die Peer-ID des Gegenübers lesen (Längenpräfix + String)
            val peerId = readPeerId(inputStream)

            // Eigene Peer-ID senden
            sendPeerId(outputStream)

            val peerStream = PeerStream(
                peerId = peerId,
                inputStream = inputStream,
                outputStream = outputStream,
                socket = socket
            )

            activeStreams[peerId] = peerStream
            Log.i(TAG, "Eingehende Verbindung von Peer $peerId hergestellt")

            // Callback für eingehende Verbindungen aufrufen
            onIncomingConnection?.invoke(peerStream)

            // Peer zu den entdeckten Peers hinzufügen
            addDiscoveredPeer(
                RemotePeerInfo(
                    peerId = peerId,
                    host = socket.inetAddress.hostAddress ?: "unknown",
                    port = socket.port,
                    isConnected = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei eingehender Verbindung: ${e.message}", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Stellt eine Verbindung zu einem entfernten Peer her.
     *
     * @param host Die IP-Adresse oder der Hostname des Ziel-Peers
     * @param port Der Port des Ziel-Peers
     * @return Ein PeerStream für die Kommunikation, oder null bei Fehlschlag
     */
    suspend fun connectToPeer(host: String, port: Int): PeerStream? {
        // Prüfen, ob bereits ein Stream existiert
        val existingKey = "$host:$port"
        activeStreams.entries.firstOrNull { it.value.socket.inetAddress.hostAddress == host && it.value.socket.port == port }
            ?.let { (_, existingStream) ->
                if (existingStream.isOpen) {
                    Log.d(TAG, "Verwende bestehenden Stream zu $host:$port")
                    return existingStream
                }
                activeStreams.remove(existingKey)
            }

        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000) // 5s Timeout

            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // Eigene Peer-ID senden
            sendPeerId(outputStream)

            // Peer-ID des Gegenübers lesen
            val remotePeerId = readPeerId(inputStream)

            val peerStream = PeerStream(
                peerId = remotePeerId,
                inputStream = inputStream,
                outputStream = outputStream,
                socket = socket
            )

            activeStreams[remotePeerId] = peerStream
            Log.d(TAG, "Verbindung zu Peer $remotePeerId ($host:$port) hergestellt")

            // Peer zu den entdeckten Peers hinzufügen
            addDiscoveredPeer(
                RemotePeerInfo(
                    peerId = remotePeerId,
                    host = host,
                    port = port,
                    isConnected = true
                )
            )

            peerStream
        } catch (e: Exception) {
            Log.e(TAG, "Verbindung zu $host:$port fehlgeschlagen: ${e.message}", e)
            null
        }
    }

    /**
     * Sendet Daten über einen bestehenden PeerStream.
     *
     * Format: [Länge: 4 Bytes (Int)] [Daten: variable Länge]
     *
     * @param stream Der PeerStream, über den gesendet werden soll
     * @param data Die zu sendenden Daten als Byte-Array
     * @throws Exception Wenn das Senden fehlschlägt
     */
    suspend fun sendMessage(stream: PeerStream, data: ByteArray) {
        try {
            withContext(Dispatchers.IO) {
                val outputStream = stream.outputStream
                // Längenpräfix + Daten
                val lengthBytes = intToByteArray(data.size)
                synchronized(outputStream) {
                    outputStream.write(lengthBytes)
                    outputStream.write(data)
                    outputStream.flush()
                }
            }
            Log.d(TAG, "Nachricht gesendet: ${data.size} Bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden der Nachricht: ${e.message}", e)
            throw e
        }
    }

    /**
     * Liest eine Nachricht aus einem PeerStream.
     *
     * @param stream Der PeerStream, aus dem gelesen werden soll
     * @return Die gelesenen Daten als Byte-Array, oder null bei Fehler
     */
    suspend fun readMessage(stream: PeerStream): ByteArray? {
        return try {
            withContext(Dispatchers.IO) {
                val inputStream = stream.inputStream
                // Längenpräfix lesen (4 Bytes)
                val lengthBytes = ByteArray(4)
                readFully(inputStream, lengthBytes)
                val length = byteArrayToInt(lengthBytes)

                if (length <= 0 || length > 10 * 1024 * 1024) { // Max 10 MB
                    Log.w(TAG, "Ungültige Nachrichtenlänge: $length")
                    return@withContext null
                }

                // Daten lesen
                val data = ByteArray(length)
                readFully(inputStream, data)
                data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Lesen der Nachricht: ${e.message}", e)
            null
        }
    }

    /**
     * Sendet die Peer-ID über den OutputStream.
     * Format: [Länge: 2 Bytes (Short)] [UTF-8 String]
     */
    private fun sendPeerId(outputStream: OutputStream) {
        val peerIdBytes = localPeerId.toByteArray(Charsets.UTF_8)
        val lengthBytes = shortToByteArray(peerIdBytes.size.toShort())
        synchronized(outputStream) {
            outputStream.write(lengthBytes)
            outputStream.write(peerIdBytes)
            outputStream.flush()
        }
    }

    /**
     * Liest die Peer-ID aus dem InputStream.
     * Format: [Länge: 2 Bytes (Short)] [UTF-8 String]
     */
    private fun readPeerId(inputStream: InputStream): String {
        val lengthBytes = ByteArray(2)
        readFully(inputStream, lengthBytes)
        val length = byteArrayToShort(lengthBytes)
        val data = ByteArray(length.toInt())
        readFully(inputStream, data)
        return String(data, Charsets.UTF_8)
    }

    /**
     * Liest garantiert die angegebene Anzahl an Bytes aus dem InputStream.
     */
    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inputStream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.EOFException("Stream geschlossen")
            offset += read
        }
    }

    /**
     * Konvertiert einen Int in ein 4-Byte-Array (Big-Endian).
     */
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * Konvertiert ein 4-Byte-Array in einen Int (Big-Endian).
     */
    private fun byteArrayToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    /**
     * Konvertiert einen Short in ein 2-Byte-Array (Big-Endian).
     */
    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() shr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * Konvertiert ein 2-Byte-Array in einen Short (Big-Endian).
     */
    private fun byteArrayToShort(bytes: ByteArray): Short {
        return (((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)).toShort()
    }

    /**
     * Gibt die lokale Adresse zurück.
     *
     * @return Die Adresse im Format "host:port"
     */
    fun getLocalAddress(): String {
        return "0.0.0.0:$localPort"
    }

    /**
     * Gibt einen Flow mit den entdeckten Peers zurück.
     *
     * @return Flow, der RemotePeerInfo-Objekte emittiert
     */
    fun getDiscoveredPeers(): Flow<RemotePeerInfo> = peerChannel.receiveAsFlow()

    /**
     * Registriert einen Callback für eingehende Verbindungen.
     *
     * @param callback Wird aufgerufen, wenn ein neuer Peer eine Verbindung herstellt
     */
    fun setOnIncomingConnection(callback: (PeerStream) -> Unit) {
        onIncomingConnection = callback
    }

    /**
     * Fügt einen entdeckten Peer zur internen Liste hinzu.
     *
     * @param info Die Informationen über den entdeckten Peer
     */
    fun addDiscoveredPeer(info: RemotePeerInfo) {
        peerChannel.trySend(info)
        val currentList = _discoveredPeers.value.toMutableList()
        if (currentList.none { it.peerId == info.peerId }) {
            currentList.add(info)
            _discoveredPeers.value = currentList
        }
    }

    /**
     * Schließt einen aktiven Stream zu einem Peer.
     *
     * @param peerId Die Peer-ID, deren Stream geschlossen werden soll
     */
    fun closeStream(peerId: String) {
        activeStreams.remove(peerId)?.let { stream ->
            try {
                stream.close()
                Log.d(TAG, "Stream zu $peerId geschlossen")
            } catch (e: Exception) {
                Log.w(TAG, "Fehler beim Schließen des Streams zu $peerId: ${e.message}")
            }
        }
    }

    /**
     * Stoppt den P2P-Server und gibt alle Ressourcen frei.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe P2P-Server")

        // Alle aktiven Streams schließen
        activeStreams.keys.toList().forEach { peerId ->
            closeStream(peerId)
        }
        activeStreams.clear()

        // Server-Socket schließen
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Schließen des Server-Sockets: ${e.message}")
        }

        serverSocket = null
        localKeyPair = null
        isRunning = false
        localPort = 0

        Log.i(TAG, "P2P-Server gestoppt")
    }
}

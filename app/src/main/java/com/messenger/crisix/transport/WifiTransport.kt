package com.messenger.crisix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * P2P-Transport über WLAN/LAN.
 * Nutzt TCP-Sockets für Nachrichten.
 * Keine automatische Peer-Discovery – nur Verbindungen zu explizit
 * hinzugefügten Kontakten (QR-Code, manuelle IP-Eingabe).
 */
class WifiTransport(
    private val deviceId: String,
    private val deviceName: String = "Crisix-${android.os.Build.MODEL}",
    private val messagePort: Int = 54230
) : Transport {

    companion object {
        private const val TAG = "WifiTransport"
    }

    override val type: TransportType = TransportType.WIFI_DIRECT
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    private val connectedClients = ConcurrentHashMap<String, Socket>()
    private val peerAddresses = ConcurrentHashMap<String, InetAddress>()
    private val knownPeers = mutableSetOf<String>()

    /**
     * Gibt detaillierten Status für die UI zurück.
     * Zeigt die Anzahl der verbundenen Peers und den Discovery-Status.
     */
    override fun getStatusDetail(): Pair<Int, String> {
        val peerCount = connectedClients.size
        val detail = if (isRunning) {
            if (peerCount > 0) "$peerCount Peer(s) verbunden" else "Bereit, warte auf Peers"
        } else {
            "Nicht gestartet"
        }
        return Pair(peerCount, detail)
    }

    /**
     * Sendet Daten über einen Socket mit dem Längen-Präfix-Protokoll.
     */
    private fun sendViaSocket(socket: Socket, data: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        val lengthStr = "${data.size}\n"
        out.write(lengthStr.toByteArray())
        out.write(data)
        out.flush()
    }

    /**
     * Liest eine vollständige Nachricht von einem InputStream.
     * Format: Längenangabe als ASCII-Textzeile, dann die Daten-Bytes.
     * @return Das gelesene Byte-Array oder null bei Verbindungsende
     */
    private fun readMessage(input: InputStream): ByteArray? {
        val lengthBytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            lengthBytes.add(b.toByte())
        }
        val length = String(lengthBytes.toByteArray()).toIntOrNull() ?: return null
        val result = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(result, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return result
    }

    /**
     * Führt einen Handshake mit einem Peer über einen bestehenden Socket durch.
     * Sendet die eigene Identität und erwartet eine Antwort.
     * @return Das Peer-Info-Objekt bei Erfolg, null bei Fehlschlag
     */
    private fun performHandshake(socket: Socket, remoteIp: String): Peer? {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()

            // Handshake senden
            val handshakeJson = JSONObject().apply {
                put("type", "handshake")
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("port", messagePort)
            }
            sendViaSocket(socket, handshakeJson.toString().toByteArray())

            // Auf Antwort warten
            val responseData = readMessage(input) ?: return null
            val responseJson = JSONObject(String(responseData))

            if (responseJson.getString("type") == "handshake") {
                val remoteId = responseJson.getString("deviceId")
                val remoteName = responseJson.optString("deviceName", "Unbekannt")
                val fullPeerId = "$remoteId@$remoteIp"

                if (remoteId == deviceId) {
                    Log.w(TAG, "Selbst-Verbindung erkannt ($remoteIp), ignoriere")
                    return null // Sich selbst ignorieren
                }

                return Peer(fullPeerId, remoteName)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Stellt eine manuelle Verbindung zu einem Peer über IP-Adresse her.
     * @param port Optionaler Port (z.B. aus QR-Code). Wenn null, wird der Standard-messagePort verwendet.
     */
    suspend fun connectToPeer(ipAddress: String, displayName: String? = null, port: Int? = null): Result<Peer> {
        return withContext(Dispatchers.IO) {
            try {
                val targetPort = port ?: messagePort
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, targetPort), 5000)

                val peer = performHandshake(socket, ipAddress)
                if (peer != null) {
                    connectedClients[peer.id] = socket
                    peerAddresses[peer.id] = socket.inetAddress
                    if (peer.id !in knownPeers) {
                        knownPeers.add(peer.id)
                        peerChannel.trySend(peer)
                    }
                    socket.soTimeout = 0
                    startClientListener(peer.id, socket)
                    Log.i(TAG, "[WifiTransport] Manuelle Verbindung zu ${peer.name} ($ipAddress) hergestellt")
                    Result.success(peer)
                } else {
                    try { socket.close() } catch (_: Exception) {}
                    Result.failure(Exception("Handshake fehlgeschlagen"))
                }
            } catch (e: Exception) {
                Log.i(TAG, "[WifiTransport] Manuelle Verbindung fehlgeschlagen: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isUp && !networkInterface.isLoopback) {
                        // Prüfe, ob das Interface wirklich mit einem Netzwerk verbunden ist:
                        // 1. Es muss eine IPv4-Adresse haben
                        // 2. Es muss eine Broadcast-Adresse haben (nur bei verbundenen WLAN/LAN-Interfaces)
                        // 3. Es darf kein Dummy/TUN-Interface sein (wie z.B. rmnet_data im Flugmodus)
                        val ifName = networkInterface.name.lowercase()
                        if (ifName.startsWith("rmnet") || ifName.startsWith("tun") || ifName.startsWith("dummy")) {
                            continue // Mobilfunk/TUN/Dummy-Interfaces ignorieren
                        }
                        
                        var hasIpv4 = false
                        var hasBroadcast = false
                        for (addr in networkInterface.interfaceAddresses) {
                            if (addr.address is java.net.Inet4Address && !addr.address.isLoopbackAddress) {
                                hasIpv4 = true
                                if (addr.broadcast != null) {
                                    hasBroadcast = true
                                }
                            }
                        }
                        
                        // Ein WLAN-Interface ist nur verfügbar, wenn es eine Broadcast-Adresse hat
                        // (d.h. wirklich mit einem Netzwerk verbunden ist)
                        if (hasIpv4 && hasBroadcast) {
                            return@withContext true
                        }
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedPeerId = peerId.split("@").first()

                // Lookup: exakter Schlüssel oder "fingerprint@ip" → "fingerprint"
                var socketKey = peerId
                var socket = connectedClients[socketKey]
                if (socket == null || socket.isClosed) {
                    val entry = connectedClients.entries.find { it.key.split("@").first() == normalizedPeerId }
                    if (entry != null) {
                        socketKey = entry.key
                        socket = entry.value
                    }
                }

                if (socket != null && !socket.isClosed) {
                    try {
                        sendViaSocket(socket, data)
                        return@withContext Result.success(Unit)
                    } catch (e: Exception) {
                        connectedClients.remove(socketKey)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }

                // Auto-Reconnect: nur möglich wenn wir eine gespeicherte Adresse haben
                val address = peerAddresses[socketKey]
                    ?: peerAddresses[peerId]
                    ?: parsePeerAddress(socketKey)
                    ?: parsePeerAddress(peerId)
                    ?: return@withContext Result.failure(Exception("Keine Adresse für Peer $peerId"))

                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(address, messagePort), 5000)

                // Vollständigen Handshake durchführen (wichtig: sonst parsed der Server
                // die Chat-Nachricht als JSON-Handshake und lehnt sie ab)
                val peer = performHandshake(newSocket, address.hostAddress ?: "unknown")
                if (peer == null) {
                    try { newSocket.close() } catch (_: Exception) {}
                    return@withContext Result.failure(Exception("Handshake fehlgeschlagen bei Reconnect"))
                }

                connectedClients[peer.id] = newSocket
                peerAddresses[peer.id] = address
                newSocket.soTimeout = 0
                sendViaSocket(newSocket, data)
                startClientListener(peer.id, newSocket)

                Result.success(Unit)
            } catch (e: Exception) {
                Log.i(TAG, "[WifiTransport] send fehlgeschlagen: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private fun parsePeerAddress(peerId: String): InetAddress? {
        val parts = peerId.split("@")
        return if (parts.size == 2) {
            try {
                InetAddress.getByName(parts[1])
            } catch (e: Exception) {
                null
            }
        } else null
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = peerChannel.receiveAsFlow()

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        // TCP-Server starten
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(messagePort)
                serverSocket = ss
                ss.soTimeout = 5000

                while (isRunning) {
                    try {
                        val clientSocket = ss.accept()
                        val clientAddress = clientSocket.inetAddress.hostAddress ?: "unknown"
                        scope.launch {
                            handleIncomingConnection(clientSocket, clientAddress)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.i(TAG, "[WifiTransport] Server-Fehler: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "[WifiTransport] Server konnte nicht gestartet werden: ${e.message}")
            } finally {
                serverSocket?.close()
                serverSocket = null
            }
        }

        Log.i(TAG, "[WifiTransport] Gestartet (Gerät: $deviceName, ID: $deviceId)")
    }

    /**
     * Behandelt eine eingehende TCP-Verbindung.
     * Liest den Handshake, antwortet und startet den Listener.
     */
    private suspend fun handleIncomingConnection(socket: Socket, clientAddress: String) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()

            // Handshake lesen
            val handshakeData = readMessage(input) ?: return
            val json = JSONObject(String(handshakeData))

            if (json.has("type") && json.getString("type") == "handshake") {
                val remoteId = json.getString("deviceId")
                val remoteName = json.getString("deviceName")
                val fullPeerId = "$remoteId@$clientAddress"

                // Selbst-Verbindung ignorieren
                if (remoteId == deviceId) {
                    Log.i(TAG, "[WifiTransport] Selbst-Verbindung (Server) erkannt von $clientAddress, ignoriere")
                    try { socket.close() } catch (_: Exception) {}
                    return
                }

                // Handshake-Antwort senden
                val responseJson = JSONObject().apply {
                    put("type", "handshake")
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("port", messagePort)
                }
                sendViaSocket(socket, responseJson.toString().toByteArray())

                // Peer speichern
                connectedClients[fullPeerId] = socket
                peerAddresses[fullPeerId] = socket.inetAddress

                if (fullPeerId !in knownPeers) {
                    knownPeers.add(fullPeerId)
                    val newPeer = Peer(fullPeerId, remoteName)
                    peerChannel.trySend(newPeer)
                    Log.i(TAG, "[WifiTransport] Neuer Peer verbunden: $remoteName ($clientAddress)")
                }

                // Kein Timeout für aktive Verbindungen
                socket.soTimeout = 0
                startClientListener(fullPeerId, socket)
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.i(TAG, "[WifiTransport] Eingehende Verbindung fehlgeschlagen: ${e.message}")
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Startet einen Listener, der eingehende Nachrichten von einem Peer liest.
     */
    private fun startClientListener(peerId: String, socket: Socket) {
        scope.launch {
            try {
                val input = socket.getInputStream()

                while (isRunning && !socket.isClosed) {
                    val data = readMessage(input) ?: break
                    listeners.forEach { it(peerId, data) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.i(TAG, "[WifiTransport] Listener für $peerId beendet: ${e.message}")
                }
            } finally {
                connectedClients.remove(peerId)
                try { socket.close() } catch (_: Exception) {}
                Log.i(TAG, "[WifiTransport] Verbindung zu $peerId getrennt")
            }
        }
    }

    override suspend fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null

        connectedClients.values.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        connectedClients.clear()
        knownPeers.clear()

        scope.cancel()
        Log.i(TAG, "[WifiTransport] Gestoppt")
    }
}

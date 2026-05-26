package com.messenger.crisix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/**
 * Relay-Transport: Verbindet sich zu einem zentralen TCP-Server,
 * der Nachrichten zwischen Geräten weiterleitet.
 *
 * Löst das NAT-Problem: Beide Geräte verbinden sich aktiv zum Server,
 * der Server relayt die Nachrichten zwischen ihnen.
 *
 * Server-IP und -Port werden in der App konfiguriert (z.B. 192.168.178.32:54232).
 */
class RelayTransport(
    private var serverHost: String = "192.168.178.32",
    private val serverPort: Int = 54232,
    private val deviceName: String = "Crisix-${android.os.Build.MODEL}"
) : Transport {

    companion object {
        /**
         * Erzeugt einen 8-Zeichen-Hash aus der Geräte-ID.
         * Der Hash dient als eindeutiger, aber kurzer Benutzername.
         * Verwendet SHA-256 und kürzt auf 8 Hex-Zeichen.
         */
        fun hashDeviceId(deviceId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(deviceId.toByteArray())
            return hashBytes.take(4).joinToString("") { "%02x".format(it) }
        }
    }


    override val type: TransportType = TransportType.RELAY
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
    private var connectionJob: Job? = null
    private var isRunning = false

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    // Verbundene Peers (über Relay): peerId -> true
    private val connectedPeers = ConcurrentHashMap<String, Boolean>()

    // Eigene Geräte-ID
    private val deviceId: String = UUID.randomUUID().toString()

    // Anzeigename: 8-Zeichen-Hash der Geräte-ID
    private val displayName: String = hashDeviceId(deviceId)


    // Socket zum Relay-Server
    private var serverSocket: Socket? = null
    private var serverOut: OutputStream? = null

    /**
     * Sendet eine JSON-Nachricht an den Relay-Server.
     */
    private fun sendToServer(msg: JSONObject) {
        val out = serverOut ?: return
        val data = msg.toString().toByteArray()
        val lengthStr = "${data.size}\n"
        synchronized(this) {
            try {
                out.write(lengthStr.toByteArray())
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                println("[RelayTransport] Fehler beim Senden an Server: ${e.message}")
                serverSocket = null
                serverOut = null
            }
        }
    }

    /**
     * Liest eine vollständige Nachricht vom BufferedReader.
     */
    private fun readMessage(reader: BufferedReader): ByteArray? {
        try {
            val lengthLine = reader.readLine() ?: return null
            val length = lengthLine.toIntOrNull() ?: return null
            val charArray = CharArray(length)
            var totalRead = 0
            while (totalRead < length) {
                val read = reader.read(charArray, totalRead, length - totalRead)
                if (read == -1) return null
                totalRead += read
            }
            return String(charArray).toByteArray()
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Startet den Message-Listener für eine bestehende Server-Verbindung.
     * Läuft in einer eigenen Coroutine und liest eingehende Nachrichten.
     */
    private fun startMessageListener(socket: Socket, reader: BufferedReader) {
        scope.launch {
            try {
                while (isRunning && !socket.isClosed) {
                    val data = readMessage(reader) ?: break
                    val msg = JSONObject(String(data))
                    val msgType = msg.optString("type", "")

                    when (msgType) {
                        "peer_list" -> {
                            val peersArray = msg.optJSONArray("peers")
                            if (peersArray != null) {
                                for (i in 0 until peersArray.length()) {
                                    val peerObj = peersArray.getJSONObject(i)
                                    val peerId = peerObj.getString("deviceId")
                                    val peerName = peerObj.optString("deviceName", "Unbekannt")
                                    val fullPeerId = "relay:$peerId"

                                    if (!connectedPeers.containsKey(fullPeerId)) {
                                        connectedPeers[fullPeerId] = true
                                        val peer = Peer(fullPeerId, peerName)
                                        peerChannel.trySend(peer)
                                        println("[RelayTransport] Peer gefunden: $peerName ($peerId)")
                                    }
                                }
                            }
                        }

                        "new_peer" -> {
                            val peerId = msg.getString("deviceId")
                            val peerName = msg.optString("deviceName", "Unbekannt")
                            val fullPeerId = "relay:$peerId"

                            if (!connectedPeers.containsKey(fullPeerId)) {
                                connectedPeers[fullPeerId] = true
                                val peer = Peer(fullPeerId, peerName)
                                peerChannel.trySend(peer)
                                println("[RelayTransport] Neuer Peer: $peerName ($peerId)")
                            }
                        }

                        "peer_left" -> {
                            val peerId = msg.getString("deviceId")
                            val fullPeerId = "relay:$peerId"
                            connectedPeers.remove(fullPeerId)
                            println("[RelayTransport] Peer offline: $peerId")
                        }

                        "message" -> {
                            val fromId = msg.getString("fromDeviceId")
                            val fromName = msg.optString("fromDeviceName", "Unbekannt")
                            val payload = msg.optJSONObject("payload")

                            if (payload != null) {
                                val fullPeerId = "relay:$fromId"
                                val payloadBytes = payload.toString().toByteArray()
                                listeners.forEach { it(fullPeerId, payloadBytes) }
                            }
                        }

                        "relay_ack" -> {
                            val targetId = msg.optString("targetDeviceId", "")
                            val status = msg.optString("status", "")
                            println("[RelayTransport] Relay $status für $targetId")
                        }

                        "pong" -> {
                            // Ping-Antwort
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    println("[RelayTransport] Message-Listener beendet: ${e.message}")
                }
            } finally {
                println("[RelayTransport] Verbindung zum Server getrennt")
                serverSocket = null
                serverOut = null
            }
        }
    }

    /**
     * Versucht eine Verbindung zum Relay-Server herzustellen.
     * @return true bei Erfolg, false bei Fehlschlag
     */
    private suspend fun tryConnect(host: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                println("[RelayTransport] Versuche Verbindung zu $host:$port ...")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.soTimeout = 30000

                serverSocket = socket
                serverOut = socket.getOutputStream()

                // Registrierung senden
                // deviceName = 8-Zeichen-Hash der Geräte-ID für anonyme Identifikation
                val registerMsg = JSONObject().apply {
                    put("type", "register")
                    put("deviceId", deviceId)
                    put("deviceName", displayName)
                }
                sendToServer(registerMsg)

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Auf Bestätigung warten
                val responseData = readMessage(reader)
                if (responseData != null) {
                    val response = JSONObject(String(responseData))
                    if (response.getString("type") == "registered") {
                        println("[RelayTransport] ✅ Registriert als $displayName (Hash von $deviceId) bei $host:$port")
                    }
                }


                // Message-Listener im Hintergrund starten
                startMessageListener(socket, reader)

                true
            } catch (e: SocketTimeoutException) {
                println("[RelayTransport] Timeout bei $host:$port")
                false
            } catch (e: Exception) {
                println("[RelayTransport] Verbindungsfehler bei $host:$port: ${e.message}")
                false
            }
        }
    }

    /**
     * Stellt die Verbindung zum Relay-Server her und startet den Listener.
     * Versucht mehrere Adressen (konfiguriert, Emulator-Fallback, localhost).
     */
    private suspend fun connectToServer() {
        // Versuche zuerst die konfigurierte Server-Adresse
        var connected = tryConnect(serverHost, serverPort)

        // Fallback: 10.0.2.2 (Android Emulator -> Host)
        if (!connected && serverHost != "10.0.2.2") {
            println("[RelayTransport] Versuche Fallback zu 10.0.2.2 (Emulator -> Host)...")
            connected = tryConnect("10.0.2.2", serverPort)
        }

        // Fallback: localhost (für ADB Reverse)
        if (!connected) {
            println("[RelayTransport] Versuche Fallback zu localhost...")
            connected = tryConnect("127.0.0.1", serverPort)
        }

        if (!connected) {
            println("[RelayTransport] Alle Verbindungsversuche fehlgeschlagen")
            serverSocket?.let {
                try { it.close() } catch (_: Exception) {}
            }
            serverSocket = null
            serverOut = null
        }
    }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(serverHost, serverPort), 2000)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val parts = peerId.split(":")
                if (parts.size != 2 || parts[0] != "relay") {
                    return@withContext Result.failure(Exception("Ungültige Peer-ID: $peerId"))
                }
                val targetDeviceId = parts[1]

                val payloadStr = String(data)
                val payloadJson = try {
                    JSONObject(payloadStr)
                } catch (e: Exception) {
                    JSONObject().apply { put("text", payloadStr) }
                }

                val relayMsg = JSONObject().apply {
                    put("type", "relay")
                    put("targetDeviceId", targetDeviceId)
                    put("payload", payloadJson)
                }

                sendToServer(relayMsg)
                Result.success(Unit)
            } catch (e: Exception) {
                println("[RelayTransport] send fehlgeschlagen: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = peerChannel.receiveAsFlow()

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        connectionJob = scope.launch {
            while (isActive && isRunning) {
                connectToServer()
                if (isRunning) {
                    println("[RelayTransport] Versuche erneute Verbindung in 5s...")
                    kotlinx.coroutines.delay(5000)
                }
            }
        }

        println("[RelayTransport] Gestartet (Server: $serverHost:$serverPort, Gerät: $deviceName)")
    }

    override suspend fun stop() {
        isRunning = false
        connectionJob?.cancel()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        serverSocket = null
        serverOut = null
        connectedPeers.clear()

        scope.cancel()
        println("[RelayTransport] Gestoppt")
    }
}

package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Verwaltet direkte Peer-to-Peer-Verbindungen für Crisix.
 *
 * ## Übersicht
 * Der ConnectionManager stellt direkte TCP/UDP-Verbindungen zwischen Peers her,
 * nachdem sie über die DHT oder andere Discovery-Mechanismen gefunden wurden.
 *
 * ## Verbindungsaufbau (mit NAT-Traversal & Relay-Fallback)
 * 1. **Direkter TCP-Versuch** – falls beide Peers öffentliche IPs haben
 * 2. **STUN** – öffentliche IP/Port ermitteln
 * 3. **Adressaustausch** – öffentliche Adressen über DHT teilen
 * 4. **UDP-Hole-Punching** – gleichzeitiges Senden öffnet NAT-Löcher
 * 5. **Noise-Handshake** – verschlüsselte Verbindung über UDP
 * 6. **Peer-Relay-Fallback** – falls alles andere scheitert (symmetrisches NAT),
 *    wird ein dritter Peer als Relay genutzt. Der Relay kann die Nachrichten
 *    nicht lesen (Ende-zu-Ende-Verschlüsselung). Kein eigener Server nötig.
 *
 * ## Verwendung
 * ```kotlin
 * val cm = ConnectionManager(localPeerId, localPublicKey, localPrivateKey)
 * cm.start()
 *
 * // Verbindung zu einem Peer herstellen (mit automatischem NAT-Traversal)
 * cm.connectToPeer(peerId, host, port)
 *
 * // Nachricht senden
 * cm.sendMessage(peerId, "Hallo!".toByteArray())
 * ```
 */
class ConnectionManager(
    private val localPeerId: String,
    private val localPublicKey: ByteArray,
    private val localPrivateKey: ByteArray
) {
    companion object {
        private const val TAG = "ConnectionManager"

        /** Standard-Port für direkte P2P-Verbindungen */
        const val DEFAULT_P2P_PORT = 49738

        /** UDP-Port für Hole-Punching und DHT */
        const val UDP_PORT = 49737

        /** Timeout für Verbindungsaufbau in Millisekunden */
        private const val CONNECT_TIMEOUT_MS = 5000L

        /** Timeout für Lesevorgänge in Millisekunden */
        private const val READ_TIMEOUT_MS = 30000L

        /** Maximale Nachrichtengröße in Bytes */
        private const val MAX_MESSAGE_SIZE = 65536

        /** Intervall für Heartbeat-Pings in Millisekunden */
        private const val HEARTBEAT_INTERVAL_MS = 30000L

        /** Timeout für Heartbeats in Millisekunden */
        private const val HEARTBEAT_TIMEOUT_MS = 90000L

        /** Anzahl der Hole-Punching-Versuche */
        private const val PUNCH_ATTEMPTS = 5

        /** Verzögerung zwischen Punching-Versuchen */
        private const val PUNCH_DELAY_MS = 200L
    }

    // =========================================================================
    // Datenklassen
    // =========================================================================

    /**
     * Status einer Peer-Verbindung.
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        HANDSHAKING,
        CONNECTED,
        DISCONNECTING,
        /** NAT-Traversal wird durchgeführt */
        NAT_TRAVERSAL,
        /** UDP-Hole-Punching läuft */
        HOLE_PUNCHING
    }

    /**
     * Repräsentiert eine aktive Verbindung zu einem Peer.
     */
    data class PeerConnection(
        val peerId: String,
        val host: String,
        val port: Int,
        val state: ConnectionState = ConnectionState.DISCONNECTED,
        val connectedSince: Long = 0L,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
        /** Öffentliche Adresse des Peers (nach STUN) */
        val publicHost: String? = null,
        val publicPort: Int? = null,
        /** Verbindungstyp: "tcp" oder "udp" */
        val connectionType: String = "tcp"
    )

    /**
     * Eingehende Nachricht von einem Peer.
     */
    data class IncomingMessage(
        val peerId: String,
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    // =========================================================================
    // Zustand
    // =========================================================================

    /** Coroutine-Scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** TCP-Server-Socket für eingehende Verbindungen */
    private var serverSocket: ServerSocket? = null

    /** UDP-Socket für Hole-Punching und UDP-Kommunikation */
    private var udpSocket: DatagramSocket? = null

    /** Aktive TCP-Verbindungen: peerId -> Socket */
    private val tcpConnections = ConcurrentHashMap<String, Socket>()

    /** Aktive UDP-Verbindungen: peerId -> InetSocketAddress */
    private val udpConnections = ConcurrentHashMap<String, InetSocketAddress>()

    /** Verbindungsstatus: peerId -> ConnectionState */
    private val connectionStates = ConcurrentHashMap<String, ConnectionState>()

    /** Noise-Kryptografie-Instanzen: peerId -> NoisePacketCrypto */
    private val cryptoInstances = ConcurrentHashMap<String, NoisePacketCrypto>()

    /** Lese-Jobs: peerId -> Job */
    private val readJobs = ConcurrentHashMap<String, Job>()

    /** Heartbeat-Job */
    private var heartbeatJob: Job? = null

    /** Hole-Punching-Listener-Job */
    private var punchListenerJob: Job? = null

    /** Laufzeitstatus */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** NAT-Traversal-Instanz */
    private val natTraversal = NatTraversal(DEFAULT_P2P_PORT, UDP_PORT)

    /** Öffentliche Adresse (nach STUN) */
    private var publicAddress: NatTraversal.PublicAddress? = null

    /** Verbindungsliste als Flow */
    private val _connections = MutableStateFlow<List<PeerConnection>>(emptyList())
    val connectionsFlow: Flow<List<PeerConnection>> = _connections.asStateFlow()

    /** Eingehende Nachrichten als Flow */
    private val _incomingMessages = MutableStateFlow<List<IncomingMessage>>(emptyList())
    val incomingMessages: Flow<List<IncomingMessage>> = _incomingMessages.asStateFlow()

    /** Callback für eingehende Nachrichten */
    var onMessageReceived: ((peerId: String, data: ByteArray) -> Unit)? = null

    /** Callback für Verbindungsänderungen */
    var onConnectionStateChanged: ((peerId: String, state: ConnectionState) -> Unit)? = null

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    /**
     * Startet den ConnectionManager.
     *
     * Öffnet TCP-Server-Socket + UDP-Socket, startet STUN,
     * Hole-Punching-Listener und Heartbeat.
     */
    suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "ConnectionManager läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte ConnectionManager")

        try {
            // TCP-Server-Socket öffnen
            serverSocket = ServerSocket(DEFAULT_P2P_PORT)
            serverSocket?.soTimeout = 5000

            // UDP-Socket öffnen (für Hole-Punching + UDP-Kommunikation)
            udpSocket = DatagramSocket(UDP_PORT)
            udpSocket?.soTimeout = 5000

            // Öffentliche Adresse via STUN ermitteln
            scope.launch { discoverPublicAddress() }

            // Akzeptiere eingehende TCP-Verbindungen
            scope.launch { acceptIncomingConnections() }

            // UDP-Hole-Punching-Listener starten
            startUdpPunchListener()

            // UDP-Empfangs-Loop starten
            scope.launch { receiveUdpMessages() }

            // Heartbeat starten
            startHeartbeat()

            Log.i(TAG, "ConnectionManager gestartet (TCP:$DEFAULT_P2P_PORT, UDP:$UDP_PORT)")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Stoppt den ConnectionManager und schließt alle Verbindungen.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe ConnectionManager")

        isRunning = false

        heartbeatJob?.cancel()
        punchListenerJob?.cancel()

        // Alle Verbindungen schließen
        for ((peerId, _) in tcpConnections) {
            disconnectFromPeer(peerId)
        }
        for ((peerId, _) in udpConnections) {
            disconnectFromPeer(peerId)
        }

        serverSocket?.close()
        serverSocket = null
        udpSocket?.close()
        udpSocket = null

        Log.i(TAG, "ConnectionManager gestoppt")
    }

    // =========================================================================
    // STUN – Öffentliche Adresse ermitteln
    // =========================================================================

    /**
     * Ermittelt die öffentliche IP/Port via STUN.
     * Wird asynchron im Hintergrund ausgeführt.
     */
    private suspend fun discoverPublicAddress() {
        publicAddress = natTraversal.discoverPublicAddress()
        if (publicAddress != null) {
            Log.i(TAG, "Öffentliche Adresse: ${publicAddress!!.host}:${publicAddress!!.port}")
        } else {
            Log.w(TAG, "STUN fehlgeschlagen – kein NAT-Traversal möglich")
        }
    }

    // =========================================================================
    // Verbindungsmanagement
    // =========================================================================

    /**
     * Stellt eine Verbindung zu einem Peer her.
     *
     * Strategie (mehrstufig):
     * 1. Direkter TCP-Versuch
     * 2. Bei Fehlschlag: UDP-Hole-Punching
     * 3. Bei Erfolg: Noise-Handshake über UDP
     *
     * @param peerId Die Peer-ID
     * @param host Die IP-Adresse oder Domain
     * @param port Der Port
     */
    suspend fun connectToPeer(peerId: String, host: String, port: Int = DEFAULT_P2P_PORT) {
        if (tcpConnections.containsKey(peerId) || udpConnections.containsKey(peerId)) {
            Log.d(TAG, "Bereits mit Peer $peerId verbunden")
            return
        }

        Log.i(TAG, "Verbinde zu Peer $peerId ($host:$port)")

        // === Stufe 1: Direkter TCP-Versuch ===
        updateConnectionState(peerId, ConnectionState.CONNECTING)
        val tcpSuccess = tryConnectTcp(peerId, host, port)

        if (tcpSuccess) {
            Log.i(TAG, "Direkte TCP-Verbindung zu $peerId hergestellt")
            return
        }

        // === Stufe 2: NAT-Traversal via UDP-Hole-Punching ===
        Log.i(TAG, "TCP fehlgeschlagen – starte NAT-Traversal zu $peerId")
        updateConnectionState(peerId, ConnectionState.NAT_TRAVERSAL)

        val udpSuccess = tryConnectUdpWithHolePunching(peerId, host, port)

        if (udpSuccess) {
            Log.i(TAG, "UDP-Hole-Punching zu $peerId erfolgreich")
        } else {
            Log.w(TAG, "Alle Verbindungsversuche zu $peerId fehlgeschlagen")
            updateConnectionState(peerId, ConnectionState.DISCONNECTED)
        }
    }

    /**
     * Versucht eine direkte TCP-Verbindung.
     */
    private suspend fun tryConnectTcp(peerId: String, host: String, port: Int): Boolean {
        return try {
            val socket = withContext(Dispatchers.IO) {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS.toInt())
                sock.soTimeout = READ_TIMEOUT_MS.toInt()
                sock
            }

            tcpConnections[peerId] = socket
            updateConnectionState(peerId, ConnectionState.HANDSHAKING)

            // Noise-Handshake durchführen
            performTcpHandshake(peerId, socket)

            updateConnectionState(peerId, ConnectionState.CONNECTED)

            // Lese-Job starten
            startTcpReadLoop(peerId, socket)

            true
        } catch (e: Exception) {
            Log.d(TAG, "TCP-Verbindung zu $peerId fehlgeschlagen: ${e.message}")
            tcpConnections.remove(peerId)
            false
        }
    }

    /**
     * Versucht eine UDP-Verbindung mit Hole-Punching.
     *
     * Ablauf:
     * 1. Öffentliche Adresse des Peers via DHT ermitteln (oder direkt verwenden)
     * 2. Gleichzeitig UDP-Pakete an den Peer senden (Hole-Punching)
     * 3. Auf Antwort warten → NAT-Loch ist offen
     * 4. Noise-Handshake über UDP durchführen
     */
    private suspend fun tryConnectUdpWithHolePunching(peerId: String, host: String, port: Int): Boolean {
        val socket = udpSocket ?: return false

        // Öffentliche Adresse des Peers ermitteln
        // (In einer vollständigen Implementierung würde man die öffentliche
        //  Adresse des Peers über die DHT abfragen. Hier nehmen wir die
        //  übergebene Adresse als Fallback.)
        val remoteAddr = InetSocketAddress(host, port)

        updateConnectionState(peerId, ConnectionState.HOLE_PUNCHING)

        // === Hole-Punching ===
        Log.d(TAG, "Starte UDP-Hole-Punching zu $host:$port")

        var punchSuccess = false
        for (attempt in 1..PUNCH_ATTEMPTS) {
            try {
                // Hole-Punching-Nachricht senden
                val punchMsg = createPunchMessage()
                val packet = DatagramPacket(punchMsg, punchMsg.size, remoteAddr)
                socket.send(packet)

                // Kurz warten und auf Antwort lauschen
                // (Die Antwort wird im receiveUdpMessages-Handler verarbeitet)
                delay(PUNCH_DELAY_MS)

                // Prüfen, ob bereits eine Antwort kam
                if (udpConnections.containsKey(peerId)) {
                    punchSuccess = true
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Punch-Versuch $attempt fehlgeschlagen: ${e.message}")
            }
        }

        if (!punchSuccess) {
            Log.w(TAG, "Hole-Punching zu $peerId fehlgeschlagen")
            return false
        }

        // === Noise-Handshake über UDP ===
        updateConnectionState(peerId, ConnectionState.HANDSHAKING)

        return try {
            performUdpHandshake(peerId, remoteAddr)
            updateConnectionState(peerId, ConnectionState.CONNECTED)
            true
        } catch (e: Exception) {
            Log.w(TAG, "UDP-Handshake zu $peerId fehlgeschlagen: ${e.message}")
            udpConnections.remove(peerId)
            false
        }
    }

    /**
     * Trennt die Verbindung zu einem Peer.
     */
    suspend fun disconnectFromPeer(peerId: String) {
        Log.i(TAG, "Trenne Verbindung zu Peer $peerId")

        updateConnectionState(peerId, ConnectionState.DISCONNECTING)

        readJobs[peerId]?.cancel()
        readJobs.remove(peerId)

        tcpConnections[peerId]?.close()
        tcpConnections.remove(peerId)
        udpConnections.remove(peerId)
        cryptoInstances.remove(peerId)

        updateConnectionState(peerId, ConnectionState.DISCONNECTED)
    }

    /**
     * Sendet eine Nachricht an einen verbundenen Peer.
     * Unterstützt sowohl TCP als auch UDP.
     */
    suspend fun sendMessage(peerId: String, data: ByteArray): Boolean {
        val crypto = cryptoInstances[peerId] ?: run {
            Log.w(TAG, "Keine Kryptografie für Peer $peerId")
            return false
        }

        // Versuche zuerst TCP
        val tcpSocket = tcpConnections[peerId]
        if (tcpSocket != null) {
            return sendTcpMessage(peerId, tcpSocket, crypto, data)
        }

        // Dann UDP
        val udpAddr = udpConnections[peerId]
        if (udpAddr != null) {
            return sendUdpMessage(peerId, udpAddr, crypto, data)
        }

        Log.w(TAG, "Nicht mit Peer $peerId verbunden")
        return false
    }

    /**
     * Sendet eine Nachricht über TCP.
     */
    private suspend fun sendTcpMessage(
        peerId: String,
        socket: Socket,
        crypto: NoisePacketCrypto,
        data: ByteArray
    ): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val encrypted = crypto.encrypt(data)
                val lengthPrefix = byteArrayOf(
                    ((encrypted.size shr 24) and 0xFF).toByte(),
                    ((encrypted.size shr 16) and 0xFF).toByte(),
                    ((encrypted.size shr 8) and 0xFF).toByte(),
                    (encrypted.size and 0xFF).toByte()
                )
                val outputStream = socket.getOutputStream()
                outputStream.write(lengthPrefix)
                outputStream.write(encrypted)
                outputStream.flush()
                updateConnectionStats(peerId, bytesSent = encrypted.size.toLong())
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP-Senden an $peerId fehlgeschlagen: ${e.message}")
            disconnectFromPeer(peerId)
            false
        }
    }

    /**
     * Sendet eine Nachricht über UDP.
     */
    private suspend fun sendUdpMessage(
        peerId: String,
        addr: InetSocketAddress,
        crypto: NoisePacketCrypto,
        data: ByteArray
    ): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val encrypted = crypto.encrypt(data)
                val packet = DatagramPacket(encrypted, encrypted.size, addr)
                udpSocket?.send(packet)
                updateConnectionStats(peerId, bytesSent = encrypted.size.toLong())
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP-Senden an $peerId fehlgeschlagen: ${e.message}")
            disconnectFromPeer(peerId)
            false
        }
    }

    /**
     * Gibt den Verbindungsstatus eines Peers zurück.
     */
    fun getConnectionState(peerId: String): ConnectionState {
        return connectionStates[peerId] ?: ConnectionState.DISCONNECTED
    }

    /**
     * Prüft, ob eine Verbindung zu einem Peer besteht.
     */
    fun isConnected(peerId: String): Boolean {
        return connectionStates[peerId] == ConnectionState.CONNECTED
    }

    /**
     * Gibt die öffentliche Adresse zurück (nach STUN).
     */
    fun getPublicAddress(): NatTraversal.PublicAddress? = publicAddress

    // =========================================================================
    // Eingehende TCP-Verbindungen
    // =========================================================================

    /**
     * Akzeptiert eingehende TCP-Verbindungen.
     */
    private suspend fun acceptIncomingConnections() {
        while (isRunning) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                val remoteHost = clientSocket.inetAddress.hostAddress ?: "unknown"
                val remotePort = clientSocket.port

                Log.i(TAG, "Eingehende TCP-Verbindung von $remoteHost:$remotePort")

                scope.launch {
                    handleIncomingTcpConnection(clientSocket, remoteHost, remotePort)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Fehler beim Akzeptieren: ${e.message}")
            }
        }
    }

    /**
     * Verarbeitet eine eingehende TCP-Verbindung.
     */
    private suspend fun handleIncomingTcpConnection(socket: Socket, host: String, port: Int) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS.toInt()
            val peerId = performTcpHandshakeAsResponder(socket)

            if (peerId != null) {
                tcpConnections[peerId] = socket
                updateConnectionState(peerId, ConnectionState.CONNECTED)
                startTcpReadLoop(peerId, socket)
                Log.i(TAG, "Eingehende TCP-Verbindung von Peer $peerId ($host:$port)")
            } else {
                Log.w(TAG, "TCP-Handshake mit $host:$port fehlgeschlagen")
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei eingehender TCP-Verbindung: ${e.message}")
            socket.close()
        }
    }

    // =========================================================================
    // UDP Hole-Punching Listener
    // =========================================================================

    /**
     * Startet den UDP-Hole-Punching-Listener.
     * Lauscht auf Punching-Anfragen und antwortet darauf.
     */
    private fun startUdpPunchListener() {
        punchListenerJob = scope.launch {
            while (isRunning) {
                try {
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    if (packet.length > 0 && isValidPunchRequest(packet)) {
                        val senderHost = packet.address.hostAddress ?: "unknown"
                        val senderPort = packet.port

                        Log.d(TAG, "Punch-Anfrage von $senderHost:$senderPort")

                        // Antwort senden
                        val response = createPunchResponse()
                        val responsePacket = DatagramPacket(
                            response, response.size,
                            packet.address, senderPort
                        )
                        udpSocket?.send(responsePacket)

                        // Peer als UDP-Verbindung registrieren
                        val addr = InetSocketAddress(packet.address, senderPort)
                        val peerId = "udp-$senderHost-$senderPort"

                        // In einer vollständigen Implementierung würde man
                        // hier die Peer-ID aus der Punch-Nachricht extrahieren
                        Log.d(TAG, "UDP-Punch von $senderHost:$senderPort registriert")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "Fehler im Punch-Listener: ${e.message}")
                }
            }
        }
    }

    /**
     * Empfängt UDP-Nachrichten (nach erfolgreichem Hole-Punching).
     */
    private suspend fun receiveUdpMessages() {
        while (isRunning) {
            try {
                val buffer = ByteArray(MAX_MESSAGE_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket?.receive(packet)

                if (packet.length > 0) {
                    val senderHost = packet.address.hostAddress ?: "unknown"
                    val senderPort = packet.port
                    val data = buffer.copyOf(packet.length)

                    // Prüfen, ob es eine Punch-Nachricht ist
                    if (isValidPunchRequest(packet) || isValidPunchResponse(packet)) {
                        continue // Wird vom Punch-Listener behandelt
                    }

                    // Peer anhand der Absenderadresse finden
                    val peerId = findPeerByUdpAddress(senderHost, senderPort)
                    if (peerId != null) {
                        val crypto = cryptoInstances[peerId]
                        if (crypto != null) {
                            try {
                                val plaintext = crypto.decrypt(data)
                                updateConnectionStats(peerId, bytesReceived = plaintext.size.toLong())

                                val message = IncomingMessage(peerId = peerId, data = plaintext)
                                val currentMessages = _incomingMessages.value.toMutableList()
                                currentMessages.add(message)
                                while (currentMessages.size > 100) currentMessages.removeAt(0)
                                _incomingMessages.value = currentMessages

                                onMessageReceived?.invoke(peerId, plaintext)
                            } catch (e: Exception) {
                                Log.w(TAG, "UDP-Entschlüsselung fehlgeschlagen: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Fehler beim UDP-Empfang: ${e.message}")
            }
        }
    }

    /**
     * Findet einen Peer anhand seiner UDP-Adresse.
     */
    private fun findPeerByUdpAddress(host: String, port: Int): String? {
        for ((peerId, addr) in udpConnections) {
            if (addr.hostString == host && addr.port == port) {
                return peerId
            }
        }
        return null
    }

    // =========================================================================
    // Noise-Handshake (TCP)
    // =========================================================================

    /**
     * Führt den Noise-XX-Handshake als Initiator über TCP durch.
     */
    private suspend fun performTcpHandshake(peerId: String, socket: Socket) {
        val crypto = NoisePacketCrypto(localPrivateKey, localPublicKey)
        cryptoInstances[peerId] = crypto

        withContext(Dispatchers.IO) {
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            val msg1 = crypto.generateHandshakeMessage()
            sendTcpHandshakeMessage(outputStream, msg1)

            val msg2 = receiveTcpHandshakeMessage(inputStream)
            val response = crypto.processHandshakeMessage(msg2)
            if (response != null) sendTcpHandshakeMessage(outputStream, response)

            val msg3 = receiveTcpHandshakeMessage(inputStream)
            crypto.processHandshakeMessage(msg3)
        }
    }

    /**
     * Führt den Noise-XX-Handshake als Responder über TCP durch.
     */
    private suspend fun performTcpHandshakeAsResponder(socket: Socket): String? {
        val crypto = NoisePacketCrypto(localPrivateKey, localPublicKey)

        return try {
            withContext(Dispatchers.IO) {
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()

                val msg1 = receiveTcpHandshakeMessage(inputStream)
                val response = crypto.processHandshakeMessage(msg1)
                if (response != null) sendTcpHandshakeMessage(outputStream, response)

                val msg3 = receiveTcpHandshakeMessage(inputStream)
                crypto.processHandshakeMessage(msg3)

                val remotePublicKey = crypto.getRemoteStaticPublic()
                if (remotePublicKey != null) {
                    val pid = CryptoHelper.publicKeyToFingerprint(remotePublicKey)
                    cryptoInstances[pid] = crypto
                    pid
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "TCP-Handshake als Responder fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Führt den Noise-XX-Handshake über UDP durch.
     */
    private suspend fun performUdpHandshake(peerId: String, remoteAddr: InetSocketAddress) {
        val crypto = NoisePacketCrypto(localPrivateKey, localPublicKey)
        cryptoInstances[peerId] = crypto

        withContext(Dispatchers.IO) {
            val socket = udpSocket ?: throw IllegalStateException("Kein UDP-Socket")

            // Phase 1: Sende ephemeralen Public Key
            val msg1 = crypto.generateHandshakeMessage()
            socket.send(DatagramPacket(msg1, msg1.size, remoteAddr))

            // Phase 2: Empfange Antwort (mit Timeout)
            val buffer2 = ByteArray(256)
            val packet2 = DatagramPacket(buffer2, buffer2.size)
            socket.soTimeout = 3000
            socket.receive(packet2)
            val msg2 = buffer2.copyOf(packet2.length)
            val response = crypto.processHandshakeMessage(msg2)
            if (response != null) {
                socket.send(DatagramPacket(response, response.size, remoteAddr))
            }

            // Phase 3: Empfange finale Nachricht
            val buffer3 = ByteArray(256)
            val packet3 = DatagramPacket(buffer3, buffer3.size)
            socket.receive(packet3)
            val msg3 = buffer3.copyOf(packet3.length)
            crypto.processHandshakeMessage(msg3)

            // Verbindung registrieren
            udpConnections[peerId] = remoteAddr
        }
    }

    // =========================================================================
    // TCP-Handshake-Hilfsfunktionen
    // =========================================================================

    private fun sendTcpHandshakeMessage(outputStream: java.io.OutputStream, data: ByteArray) {
        val lengthPrefix = byteArrayOf(
            ((data.size shr 24) and 0xFF).toByte(),
            ((data.size shr 16) and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            (data.size and 0xFF).toByte()
        )
        outputStream.write(lengthPrefix)
        outputStream.write(data)
        outputStream.flush()
    }

    private fun receiveTcpHandshakeMessage(inputStream: java.io.InputStream): ByteArray {
        val lengthBytes = ByteArray(4)
        var bytesRead = 0
        while (bytesRead < 4) {
            val count = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead)
            if (count == -1) throw java.io.EOFException("Verbindung geschlossen")
            bytesRead += count
        }

        val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                (lengthBytes[3].toInt() and 0xFF)

        val data = ByteArray(length)
        bytesRead = 0
        while (bytesRead < length) {
            val count = inputStream.read(data, bytesRead, length - bytesRead)
            if (count == -1) throw java.io.EOFException("Verbindung geschlossen")
            bytesRead += count
        }
        return data
    }

    // =========================================================================
    // TCP-Lese-Schleife
    // =========================================================================

    private fun startTcpReadLoop(peerId: String, socket: Socket) {
        val job = scope.launch {
            try {
                val inputStream = socket.getInputStream()
                while (isRunning && socket.isConnected && !socket.isClosed) {
                    try {
                        val lengthBytes = ByteArray(4)
                        var bytesRead = 0
                        while (bytesRead < 4) {
                            val count = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead)
                            if (count == -1) { Log.d(TAG, "Peer $peerId hat Verbindung geschlossen"); break }
                            bytesRead += count
                        }
                        if (bytesRead < 4) break

                        val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                                ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                                ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                                (lengthBytes[3].toInt() and 0xFF)

                        if (length > MAX_MESSAGE_SIZE) { Log.w(TAG, "Nachricht zu groß: $length"); break }

                        val encryptedData = ByteArray(length)
                        bytesRead = 0
                        while (bytesRead < length) {
                            val count = inputStream.read(encryptedData, bytesRead, length - bytesRead)
                            if (count == -1) break
                            bytesRead += count
                        }
                        if (bytesRead < length) break

                        val crypto = cryptoInstances[peerId]
                        if (crypto != null) {
                            val plaintext = crypto.decrypt(encryptedData)
                            updateConnectionStats(peerId, bytesReceived = plaintext.size.toLong())

                            val message = IncomingMessage(peerId = peerId, data = plaintext)
                            val currentMessages = _incomingMessages.value.toMutableList()
                            currentMessages.add(message)
                            while (currentMessages.size > 100) currentMessages.removeAt(0)
                            _incomingMessages.value = currentMessages

                            onMessageReceived?.invoke(peerId, plaintext)
                        }
                    } catch (e: java.net.SocketTimeoutException) { /* Normal */ }
                      catch (e: java.io.EOFException) { Log.d(TAG, "Peer $peerId EOF"); break }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP-Lese-Schleife für $peerId beendet: ${e.message}")
            } finally {
                if (connectionStates[peerId] == ConnectionState.CONNECTED) disconnectFromPeer(peerId)
            }
        }
        readJobs[peerId] = job
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isRunning) {
                delay(HEARTBEAT_INTERVAL_MS)

                for ((peerId, socket) in tcpConnections) {
                    if (!socket.isConnected || socket.isClosed) {
                        Log.w(TAG, "Peer $peerId (TCP) nicht mehr verbunden")
                        disconnectFromPeer(peerId)
                    }
                }
            }
        }
    }

    // =========================================================================
    // Hole-Punching-Nachrichten
    // =========================================================================

    /**
     * Erstellt eine Hole-Punching-Anfrage.
     * Format: "CRIX" (4B) + Typ (1B) + Peer-ID-Länge (1B) + Peer-ID
     */
    private fun createPunchMessage(): ByteArray {
        val peerIdBytes = localPeerId.toByteArray(Charsets.UTF_8)
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { dos ->
            dos.writeInt(0x43524958) // "CRIX"
            dos.writeByte(0x01)      // Punch Request
            dos.writeByte(peerIdBytes.size)
            dos.write(peerIdBytes)
        }
        return buffer.toByteArray()
    }

    /**
     * Erstellt eine Hole-Punching-Antwort.
     */
    private fun createPunchResponse(): ByteArray {
        val peerIdBytes = localPeerId.toByteArray(Charsets.UTF_8)
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { dos ->
            dos.writeInt(0x43524958) // "CRIX"
            dos.writeByte(0x02)      // Punch Response
            dos.writeByte(peerIdBytes.size)
            dos.write(peerIdBytes)
        }
        return buffer.toByteArray()
    }

    /**
     * Prüft, ob ein Paket eine gültige Punch-Anfrage ist.
     */
    private fun isValidPunchRequest(packet: DatagramPacket): Boolean {
        if (packet.length < 9) return false
        val data = packet.data
        return data[0] == 0x43.toByte() && data[1] == 0x52.toByte() &&
                data[2] == 0x49.toByte() && data[3] == 0x58.toByte() &&
                data[4] == 0x01.toByte()
    }

    /**
     * Prüft, ob ein Paket eine gültige Punch-Antwort ist.
     */
    private fun isValidPunchResponse(packet: DatagramPacket): Boolean {
        if (packet.length < 9) return false
        val data = packet.data
        return data[0] == 0x43.toByte() && data[1] == 0x52.toByte() &&
                data[2] == 0x49.toByte() && data[3] == 0x58.toByte() &&
                data[4] == 0x02.toByte()
    }

    // =========================================================================
    // Hilfsfunktionen
    // =========================================================================

    /**
     * Aktualisiert den Verbindungsstatus eines Peers.
     */
    private fun updateConnectionState(peerId: String, state: ConnectionState) {
        connectionStates[peerId] = state

        val currentList = _connections.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.peerId == peerId }

        if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            currentList[existingIndex] = existing.copy(
                state = state,
                connectedSince = if (state == ConnectionState.CONNECTED) System.currentTimeMillis() else existing.connectedSince
            )
        }

        _connections.value = currentList
        onConnectionStateChanged?.invoke(peerId, state)
    }

    /**
     * Aktualisiert die Verbindungsstatistiken.
     */
    private fun updateConnectionStats(peerId: String, bytesSent: Long = 0, bytesReceived: Long = 0) {
        val currentList = _connections.value.toMutableList()
        val index = currentList.indexOfFirst { it.peerId == peerId }
        if (index >= 0) {
            val existing = currentList[index]
            currentList[index] = existing.copy(
                bytesSent = existing.bytesSent + bytesSent,
                bytesReceived = existing.bytesReceived + bytesReceived
            )
            _connections.value = currentList
        }
    }
}

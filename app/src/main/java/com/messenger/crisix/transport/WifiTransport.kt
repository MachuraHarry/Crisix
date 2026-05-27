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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Echter P2P-Transport über WLAN/LAN.
 * Nutzt TCP-Sockets für Nachrichten und UDP-Broadcast für Peer-Discovery.
 *
 * Phase 1: Einfache JSON-Kommunikation ohne Verschlüsselung.
 */
class WifiTransport(
    private val deviceId: String,
    private val deviceName: String = "Crisix-${android.os.Build.MODEL}",
    private val discoveryPort: Int = 54233,
    private val messagePort: Int = 54230
) : Transport {

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
    private var discoveryJob: Job? = null
    private var isRunning = false

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    // Verbundene Clients: peerId -> Socket
    private val connectedClients = ConcurrentHashMap<String, Socket>()

    // Bereits bekannte Peers (um Duplikate zu vermeiden)
    private val knownPeers = mutableSetOf<String>()

    // Scan-Job für Netzwerkscan
    private var scanJob: Job? = null

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
     * Liest eine vollständige Nachricht von einem BufferedReader.
     * Format: Längenangabe als Textzeile, dann die Daten.
     * @return Das gelesene Byte-Array oder null bei Verbindungsende
     */
    private fun readMessage(reader: BufferedReader): ByteArray? {
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
    }

    /**
     * Führt einen Handshake mit einem Peer über einen bestehenden Socket durch.
     * Sendet die eigene Identität und erwartet eine Antwort.
     * @return Das Peer-Info-Objekt bei Erfolg, null bei Fehlschlag
     */
    private fun performHandshake(socket: Socket, remoteIp: String): Peer? {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Handshake senden
            val handshakeJson = JSONObject().apply {
                put("type", "handshake")
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("port", messagePort)
            }
            sendViaSocket(socket, handshakeJson.toString().toByteArray())

            // Auf Antwort warten
            val responseData = readMessage(reader) ?: return null
            val responseJson = JSONObject(String(responseData))

            if (responseJson.getString("type") == "handshake") {
                val remoteId = responseJson.getString("deviceId")
                val remoteName = responseJson.optString("deviceName", "Unbekannt")
                val fullPeerId = "$remoteId@$remoteIp"

                if (remoteId == deviceId) {
                    println("[WifiTransport] Selbst-Verbindung erkannt ($remoteIp), ignoriere")
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
     * Scannt das lokale Subnetz nach Geräten, die auf dem messagePort lauschen.
     * Scannt sowohl das eigene Subnetz als auch das Gateway-Subnetz (für Emulator hinter NAT).
     */
    suspend fun scanLocalNetwork(): List<Peer> {
        return withContext(Dispatchers.IO) {
            val foundPeers = mutableListOf<Peer>()
            val localIp = getLocalIPv4Address() ?: return@withContext foundPeers

            // Subnetze ermitteln
            val subnetsToScan = mutableSetOf<String>()

            // 1. Eigenes Subnetz
            val parts = localIp.split(".")
            if (parts.size == 4) {
                subnetsToScan.add("${parts[0]}.${parts[1]}.${parts[2]}.")
            }

            // 2. Gateway-Subnetz (für Emulator)
            val gatewayIp = getGatewayIP()
            if (gatewayIp != null) {
                val gwParts = gatewayIp.split(".")
                if (gwParts.size == 4) {
                    subnetsToScan.add("${gwParts[0]}.${gwParts[1]}.${gwParts[2]}.")
                }
            }

            // 3. Emulator: Host-Subnetz automatisch ermitteln
            // Der Emulator hat eine NAT-IP (10.0.2.x), der Host ist unter 10.0.2.2 erreichbar.
            // Das Pixel 9 ist im Host-Netzwerk (z.B. 192.168.178.x).
            // Wir ermitteln das Host-Subnetz über die Routing-Tabelle.
            if (localIp.startsWith("10.0.2.")) {
                println("[WifiTransport] Emulator erkannt, ermittle Host-Subnetz...")
                // Host-Subnetz über Routing-Tabelle ermitteln
                val hostSubnet = getHostSubnetFromEmulator()
                if (hostSubnet != null) {
                    subnetsToScan.add(hostSubnet)
                    println("[WifiTransport] Host-Subnetz ermittelt: $hostSubnet")
                } else {
                    // Fallback: Bekannte private Subnetze scannen
                    println("[WifiTransport] Host-Subnetz nicht ermittelbar, scanne bekannte Subnetze...")
                    subnetsToScan.add("192.168.0.")
                    subnetsToScan.add("192.168.1.")
                    subnetsToScan.add("192.168.2.")
                    subnetsToScan.add("192.168.178.")
                    subnetsToScan.add("192.168.188.")
                    subnetsToScan.add("10.0.0.")
                    subnetsToScan.add("10.0.1.")
                    subnetsToScan.add("172.16.0.")
                    subnetsToScan.add("172.16.1.")
                    // Emulator-spezifische Subnetze (Android Emulator verwendet 192.0.0.x für zweite Instanz)
                    subnetsToScan.add("192.0.0.")
                    subnetsToScan.add("192.0.2.")
                }
            }

            println("[WifiTransport] Starte Netzwerkscan in Subnetzen: $subnetsToScan ...")

            val semaphore = Semaphore(30)
            val jobs = mutableListOf<Job>()
            val scannedIps = mutableSetOf<String>()

            for (subnetPrefix in subnetsToScan) {
                for (i in 1..254) {
                    val ip = "$subnetPrefix$i"
                    if (ip == localIp || ip in scannedIps) continue
                    scannedIps.add(ip)

                    jobs.add(scope.launch {
                        semaphore.acquire()
                        try {
                            val socket = Socket()
                            try {
                                socket.connect(InetSocketAddress(ip, messagePort), 150)
                            } catch (e: Exception) {
                                return@launch
                            }

                            val peer = performHandshake(socket, ip)
                            if (peer != null) {
                                val fullPeerId = peer.id
                                if (fullPeerId !in knownPeers) {
                                    knownPeers.add(fullPeerId)
                                    foundPeers.add(peer)
                                    peerChannel.trySend(peer)
                                    println("[WifiTransport] Scan gefunden: ${peer.name} ($ip)")
                                }
                                // Socket für aktive Verbindung speichern (auch bei bekanntem Peer)
                                connectedClients[fullPeerId] = socket
                                socket.soTimeout = 0
                                startClientListener(fullPeerId, socket)
                            } else {
                                try { socket.close() } catch (_: Exception) {}
                            }
                        } finally {
                            semaphore.release()
                        }
                    })
                }
            }

            jobs.forEach { it.join() }
            println("[WifiTransport] Netzwerkscan abgeschlossen: ${foundPeers.size} Peer(s) gefunden")
            foundPeers
        }
    }

    /**
     * Ermittelt die Gateway-IP-Adresse (Standardroute).
     */
    private fun getGatewayIP(): String? {
        try {
            val process = Runtime.getRuntime().exec("ip route show default")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            if (line != null) {
                val parts = line.split(" ")
                if (parts.size >= 3 && parts[0] == "default" && parts[1] == "via") {
                    return parts[2]
                }
            }
        } catch (e: Exception) {
            println("[WifiTransport] Gateway-Ermittlung fehlgeschlagen: ${e.message}")
        }
        return null
    }

    /**
     * Ermittelt das Host-Subnetz aus dem Emulator heraus.
     *
     * Der Emulator hat die IP 10.0.2.x. Der Host ist unter 10.0.2.2 erreichbar.
     * Das Pixel 9 ist im Host-Netzwerk (z.B. 192.168.178.x).
     *
     * Diese Methode analysiert die Routing-Tabelle des Emulators, um
     * herauszufinden, über welches Gateway der Host (10.0.2.2) erreichbar ist,
     * und leitet daraus das Subnetz des Hosts ab.
     *
     * @return Das Subnetz-Präfix (z.B. "192.168.178.") oder null
     */
    private fun getHostSubnetFromEmulator(): String? {
        try {
            // 1. Versuche, die Route zu 10.0.2.2 zu analysieren
            val process = Runtime.getRuntime().exec("ip route get 10.0.2.2")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            if (line != null) {
                println("[WifiTransport] Route zu 10.0.2.2: $line")
                // Beispiel: "10.0.2.2 via 10.0.2.2 dev eth0 src 10.0.2.16 uid 0"
                // Oder: "10.0.2.2 dev eth0 src 10.0.2.16 uid 0"
                // Die "via"-Adresse ist das Gateway zum Host
                val parts = line.split(" ")
                for (i in parts.indices) {
                    if (parts[i] == "via" && i + 1 < parts.size) {
                        val gateway = parts[i + 1]
                        // Das Gateway ist die IP des Hosts im Host-Netzwerk
                        // z.B. 192.168.178.1
                        val gwParts = gateway.split(".")
                        if (gwParts.size == 4) {
                            return "${gwParts[0]}.${gwParts[1]}.${gwParts[2]}."
                        }
                    }
                }
            }

            // 2. Alle Routing-Tabellen-Einträge durchgehen
            // Der Emulator hat oft eine Route zum Host-Netzwerk
            val routeProcess = Runtime.getRuntime().exec("ip route show")
            val routeReader = BufferedReader(InputStreamReader(routeProcess.inputStream))
            var routeLine: String?
            while (routeReader.readLine().also { routeLine = it } != null) {
                println("[WifiTransport] Route: $routeLine")
                val rl = routeLine ?: continue
                // Suche nach Einträgen wie "192.168.178.0/24 via 10.0.2.2 dev eth0"
                // oder "192.168.178.0/24 dev eth0 proto kernel scope link src 192.168.178.51"
                val routeParts = rl.split(" ")
                if (routeParts.size >= 2) {
                    val network = routeParts[0]
                    // Prüfe, ob es ein privates Subnetz ist (192.168.x.x, 10.x.x.x, 172.16.x.x)
                    if (network.contains("/")) {
                        val ipPart = network.split("/")[0]
                        val ipParts = ipPart.split(".")
                        if (ipParts.size == 4) {
                            val firstOctet = ipParts[0].toIntOrNull() ?: 0
                            // Privates Subnetz und NICHT 10.0.2.x (Emulator-Netzwerk)
                            if ((firstOctet == 192 || firstOctet == 10 || firstOctet == 172) && !ipPart.startsWith("10.0.2.")) {
                                return "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}."
                            }
                        }
                    }
                }
            }

            // 3. Fallback: Alle nicht-loopback, nicht-10.0.2.x Interfaces scannen
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            // 10.0.2.x ist das Emulator-Netzwerk, überspringen
                            if (host.startsWith("10.0.2.")) continue
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                println("[WifiTransport] Host-Interface gefunden: $host")
                                return "${parts[0]}.${parts[1]}.${parts[2]}."
                            }
                        }
                    }
                }
            }

            // 4. Letzter Fallback: /proc/net/fib_trie lesen (enthält alle Routing-Informationen)
            try {
                val fibProcess = Runtime.getRuntime().exec("cat /proc/net/fib_trie")
                val fibReader = BufferedReader(InputStreamReader(fibProcess.inputStream))
                var fibLine: String?
                while (fibReader.readLine().also { fibLine = it } != null) {
                    val fl = fibLine ?: continue
                    // Suche nach IP-Adressen in der FIB-Tabelle
                    val match = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""").find(fl)
                    if (match != null) {
                        val ip = match.value
                        if (!ip.startsWith("10.0.2.") && !ip.startsWith("127.") && !ip.startsWith("0.")) {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                println("[WifiTransport] FIB-Tabelle gefunden: $ip")
                                return "${parts[0]}.${parts[1]}.${parts[2]}."
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[WifiTransport] FIB-Tabelle nicht lesbar: ${e.message}")
            }
        } catch (e: Exception) {
            println("[WifiTransport] Host-Subnetz-Ermittlung fehlgeschlagen: ${e.message}")
        }
        return null
    }

    /**
     * Gibt die lokale IPv4-Adresse des Geräts zurück.
     */
    private fun getLocalIPv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[WifiTransport] Fehler beim Ermitteln der lokalen IP: ${e.message}")
        }
        return null
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
                    if (peer.id !in knownPeers) {
                        knownPeers.add(peer.id)
                        peerChannel.trySend(peer)
                    }
                    socket.soTimeout = 0
                    startClientListener(peer.id, socket)
                    println("[WifiTransport] Manuelle Verbindung zu ${peer.name} ($ipAddress) hergestellt")
                    Result.success(peer)
                } else {
                    try { socket.close() } catch (_: Exception) {}
                    Result.failure(Exception("Handshake fehlgeschlagen"))
                }
            } catch (e: Exception) {
                println("[WifiTransport] Manuelle Verbindung fehlgeschlagen: ${e.message}")
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
                val socket = connectedClients[peerId]
                if (socket != null && !socket.isClosed) {
                    try {
                        sendViaSocket(socket, data)
                        return@withContext Result.success(Unit)
                    } catch (e: Exception) {
                        // Socket ist defekt, entfernen und neu verbinden
                        connectedClients.remove(peerId)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }

                // Neuen Socket erstellen
                val address = parsePeerAddress(peerId)
                    ?: return@withContext Result.failure(Exception("Keine Adresse für Peer $peerId"))

                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(address, messagePort), 5000)
                connectedClients[peerId] = newSocket
                newSocket.soTimeout = 0
                sendViaSocket(newSocket, data)
                startClientListener(peerId, newSocket)

                Result.success(Unit)
            } catch (e: Exception) {
                println("[WifiTransport] send fehlgeschlagen: ${e.message}")
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
                val serverSocket = ServerSocket(messagePort)
                serverSocket.soTimeout = 5000

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket.accept()
                        val clientAddress = clientSocket.inetAddress.hostAddress ?: "unknown"
                        scope.launch {
                            handleIncomingConnection(clientSocket, clientAddress)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isRunning) {
                            println("[WifiTransport] Server-Fehler: ${e.message}")
                        }
                    }
                }
                serverSocket.close()
            } catch (e: Exception) {
                println("[WifiTransport] Server konnte nicht gestartet werden: ${e.message}")
            }
        }

        // UDP-Discovery starten
        discoveryJob = scope.launch {
            startDiscovery()
        }

        println("[WifiTransport] Gestartet (Gerät: $deviceName, ID: $deviceId)")
    }

    /**
     * Behandelt eine eingehende TCP-Verbindung.
     * Liest den Handshake, antwortet und startet den Listener.
     */
    private suspend fun handleIncomingConnection(socket: Socket, clientAddress: String) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Handshake lesen
            val handshakeData = readMessage(reader) ?: return
            val json = JSONObject(String(handshakeData))

            if (json.has("type") && json.getString("type") == "handshake") {
                val remoteId = json.getString("deviceId")
                val remoteName = json.getString("deviceName")
                val fullPeerId = "$remoteId@$clientAddress"

                // Selbst-Verbindung ignorieren
                if (remoteId == deviceId) {
                    println("[WifiTransport] Selbst-Verbindung (Server) erkannt von $clientAddress, ignoriere")
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

                if (fullPeerId !in knownPeers) {
                    knownPeers.add(fullPeerId)
                    val newPeer = Peer(fullPeerId, remoteName)
                    peerChannel.trySend(newPeer)
                    println("[WifiTransport] Neuer Peer verbunden: $remoteName ($clientAddress)")
                }

                // Kein Timeout für aktive Verbindungen
                socket.soTimeout = 0
                startClientListener(fullPeerId, socket)
            }
        } catch (e: Exception) {
            if (isRunning) {
                println("[WifiTransport] Eingehende Verbindung fehlgeschlagen: ${e.message}")
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                while (isRunning && !socket.isClosed) {
                    val data = readMessage(reader) ?: break
                    listeners.forEach { it(peerId, data) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    println("[WifiTransport] Listener für $peerId beendet: ${e.message}")
                }
            } finally {
                connectedClients.remove(peerId)
                try { socket.close() } catch (_: Exception) {}
                println("[WifiTransport] Verbindung zu $peerId getrennt")
            }
        }
    }

    /**
     * UDP-Discovery: Sendet regelmäßig Broadcasts und lauscht auf Antworten.
     */
    private suspend fun startDiscovery() {
        try {
            val broadcastSocket = DatagramSocket(discoveryPort)
            broadcastSocket.broadcast = true
            broadcastSocket.soTimeout = 3000

            val broadcastAddress = getBroadcastAddress()

            while (isRunning) {
                val handshakeJson = JSONObject().apply {
                    put("type", "handshake")
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("port", messagePort)
                }
                val sendData = handshakeJson.toString().toByteArray()

                // Broadcast senden
                if (broadcastAddress != null) {
                    val sendPacket = DatagramPacket(
                        sendData, sendData.size,
                        broadcastAddress, discoveryPort
                    )
                    try {
                        broadcastSocket.send(sendPacket)
                    } catch (e: Exception) {
                        println("[WifiTransport] Broadcast senden fehlgeschlagen: ${e.message}")
                    }
                }

                // Auf Antworten warten
                try {
                    val buffer = ByteArray(4096)
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    broadcastSocket.receive(receivePacket)

                    val json = JSONObject(String(receivePacket.data, 0, receivePacket.length))
                    if (json.getString("type") == "handshake") {
                        val remoteId = json.getString("deviceId")
                        val remoteName = json.getString("deviceName")
                        val remoteAddress = receivePacket.address.hostAddress ?: "unknown"

                        if (remoteId != deviceId) {
                            val fullPeerId = "$remoteId@$remoteAddress"

                            if (fullPeerId !in knownPeers) {
                                knownPeers.add(fullPeerId)
                                val newPeer = Peer(fullPeerId, remoteName)
                                peerChannel.trySend(newPeer)
                                println("[WifiTransport] Neuer Peer via UDP: $remoteName ($remoteAddress)")

                                // Automatisch TCP-Verbindung aufbauen
                                scope.launch {
                                    try {
                                        val socket = Socket()
                                        socket.connect(InetSocketAddress(receivePacket.address, messagePort), 3000)
                                        connectedClients[fullPeerId] = socket
                                        sendViaSocket(socket, sendData)

                                        // Handshake-Antwort des Peers lesen und verwerfen,
                                        // damit sie NICHT als Chat-Nachricht an die
                                        // Listener weitergegeben wird (das JSON mit
                                        // deviceId/deviceName/port würde sonst im
                                        // Chat landen).
                                        socket.soTimeout = 5000
                                        val reader = BufferedReader(
                                            InputStreamReader(socket.getInputStream())
                                        )
                                        val responseData = readMessage(reader)
                                        if (responseData != null) {
                                            val responseJson = JSONObject(String(responseData))
                                            if (responseJson.optString("type") == "handshake") {
                                                // Handshake erfolgreich bestätigt, verworfen
                                            }
                                        }

                                        socket.soTimeout = 0
                                        startClientListener(fullPeerId, socket)
                                        println("[WifiTransport] TCP-Verbindung zu $remoteName hergestellt")
                                    } catch (e: Exception) {
                                        println("[WifiTransport] TCP-Verbindung zu $remoteName fehlgeschlagen: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                }

                kotlinx.coroutines.delay(5000)
            }

            broadcastSocket.close()
        } catch (e: Exception) {
            println("[WifiTransport] Discovery-Fehler: ${e.message}")
        }
    }

    /**
     * Ermittelt die Broadcast-Adresse des aktiven Netzwerk-Interfaces.
     */
    private fun getBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    for (address in networkInterface.interfaceAddresses) {
                        val broadcast = address.broadcast
                        if (broadcast != null) return broadcast
                    }
                }
            }
        } catch (e: Exception) {
            println("[WifiTransport] Broadcast-Adresse nicht gefunden: ${e.message}")
        }
        return null
    }

    override suspend fun stop() {
        isRunning = false
        serverJob?.cancel()
        discoveryJob?.cancel()

        connectedClients.values.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        connectedClients.clear()
        knownPeers.clear()

        scope.cancel()
        println("[WifiTransport] Gestoppt")
    }
}

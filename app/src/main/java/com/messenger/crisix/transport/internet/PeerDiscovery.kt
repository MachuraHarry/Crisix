package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Dezentrale Peer-Discovery für das Crisix-P2P-Netzwerk.
 *
 * ## Funktionsweise
 * Die Peer-Discovery kombiniert zwei Verfahren:
 *
 * ### 1. Kademlia DHT (Global)
 * - Eigener DHT-Knoten ([DhtNode]) für dezentrale Peer-Findung
 * - Peers registrieren sich selbst in der DHT
 * - Andere Peers können über die Peer-ID gesucht werden
 * - Kein zentraler Server erforderlich
 *
 * ### 2. mDNS (Lokal)
 * - Multicast DNS für Geräte im selben lokalen Netzwerk
 * - Keine Konfiguration erforderlich
 * - Funktioniert auch ohne Internetverbindung
 *
 * ### 3. NAT-Traversal
 * - STUN für öffentliche IP/Port-Erkennung
 * - UDP Hole Punching für Verbindungen hinter NATs
 *
 * ## Offline-Fallback
 * Wenn die DHT nicht erreichbar ist (keine Internetverbindung),
 * wird automatisch nur mDNS verwendet.
 *
 * ## Verwendung
 * ```kotlin
 * val discovery = PeerDiscovery()
 * discovery.start(localPeerId, localPort)
 * val peer = discovery.findPeer("12D3KooW...")
 * ```
 */
class PeerDiscovery {

    private val TAG = "PeerDiscovery"

    /** Intervall für mDNS-Scans (30 Sekunden) */
    private val MDNS_SCAN_INTERVAL_MS = 30_000L

    /** Intervall für NAT-Traversal-Prüfung (5 Minuten) */
    private val NAT_CHECK_INTERVAL_MS = 300_000L

    /** mDNS-Dienstname für Crisix */
    private val CRISIX_SERVICE = "_crisix._tcp.local"

    /** mDNS-Port */
    private val MDNS_PORT = 5353

    /** mDNS-Multicast-Adresse */
    private val MDNS_ADDR = InetAddress.getByName("224.0.0.251")

    /** Coroutine-Scope für Hintergrundaufgaben */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** DHT-Knoten für globale Peer-Findung (Mainline DHT / BEP 5) */
    var dhtNode: MainlineDhtNode? = null
        private set

    /** NAT-Traversal-Instanz */
    private var natTraversal: NatTraversal? = null

    /** Job für mDNS-Scans */
    private var mdnsScanJob: Job? = null

    /** Job für NAT-Prüfung */
    private var natCheckJob: Job? = null

    /** Job für Hole-Punching-Listener */
    private var punchListenerJob: Job? = null

    /** Liste der entdeckten Peers */
    private val _discoveredPeers = MutableStateFlow<List<RemotePeerInfo>>(emptyList())
    val discoveredPeers: Flow<List<RemotePeerInfo>> = _discoveredPeers.asStateFlow()

    /** Gibt an, ob die DHT verfügbar ist */
    @Volatile
    var isDhtAvailable: Boolean = false
        private set

    /** Gibt an, ob die Discovery läuft */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Öffentliche Adresse (nach STUN) */
    @Volatile
    var publicAddress: NatTraversal.PublicAddress? = null
        private set

    /**
     * Startet die Peer-Discovery.
     *
     * Initialisiert:
     * 1. DHT-Knoten für globale Peer-Findung (mit öffentlichen Bootstrap-Knoten)
     * 2. mDNS für lokale Peer-Findung
     * 3. NAT-Traversal für Verbindungen hinter Routern
     * 4. Topic-basiertes Announcing für Chat-Räume
     *
     * @param localPeerId Die eigene Peer-ID
     * @param localPublicKey Der eigene öffentliche Schlüssel (32 Bytes)
     * @param localPort Der lokale TCP-Port
     */
    fun start(localPeerId: String, localPublicKey: ByteArray, localPort: Int) {
        if (isRunning) {
            Log.w(TAG, "PeerDiscovery läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte Peer-Discovery für Peer: $localPeerId auf Port $localPort")

        // 1. DHT-Knoten starten (Mainline DHT / BEP 5)
        //    Verwendet das GLOBALE Topic (DhtConfig.GLOBAL_TOPIC), damit ALLE
        //    Crisix-Geräte im selben "Telefonbuch" der DHT registriert sind.
        //    Vorher wurde fälschlicherweise die Peer-ID als Topic verwendet,
        //    was dazu führte, dass jedes Gerät ein eigenes Topic hatte und
        //    sich die Peers gegenseitig nicht finden konnten.
        dhtNode = MainlineDhtNode(localPeerId, localPort, DhtConfig.GLOBAL_TOPIC)
        scope.launch {
            try {
                Log.i(TAG, "Versuche Verbindung zu Mainline-DHT-Bootstrap-Knoten...")
                dhtNode?.start()
                isDhtAvailable = true
                Log.i(TAG, "✅ Mainline-DHT-Knoten gestartet mit ${dhtNode?.knownNodesCount ?: 0} bekannten Knoten")
                Log.i(TAG, "🌍 Globales Topic: ${DhtConfig.GLOBAL_TOPIC_HEX.take(16)}...")

                // 1a. Peer in der DHT registrieren (announce)
                //     Verwendet das GLOBALE Topic (DhtConfig.GLOBAL_TOPIC), damit
                //     ALLE Crisix-Geräte im selben Topic registriert sind.
                //     Die öffentliche IP (via STUN) wird mitgesendet, falls verfügbar.
                val publicHost = publicAddress?.host
                val publicPort = publicAddress?.port
                dhtNode?.announce(
                    topicBytes = DhtConfig.GLOBAL_TOPIC,
                    peerId = localPeerId,
                    publicHost = publicHost,
                    publicPort = publicPort
                )
                Log.i(TAG, "✅ Peer $localPeerId in der DHT registriert (topic=${DhtConfig.GLOBAL_TOPIC_HEX.take(16)}..., host=$publicHost, port=$publicPort)")
            } catch (e: Exception) {
                Log.w(TAG, "DHT-Start fehlgeschlagen (Offline-Fallback): ${e.message}")
                isDhtAvailable = false
            }
        }

        // 2. NAT-Traversal initialisieren
        natTraversal = NatTraversal(localPort)
        scope.launch {
            try {
                val addr = natTraversal?.discoverPublicAddress()
                if (addr != null) {
                    publicAddress = addr
                    Log.i(TAG, "Öffentliche Adresse: ${addr.host}:${addr.port}")

                    // Hole-Punching-Listener starten
                    punchListenerJob = natTraversal?.startPunchListener { host, port ->
                        Log.d(TAG, "Hole Punch empfangen von $host:$port")
                        // Peer zur Liste hinzufügen
                        val peerInfo = RemotePeerInfo(
                            peerId = "punch-$host-$port",
                            host = host,
                            port = port,
                            isConnected = false
                        )
                        addPeerToList(peerInfo)
                    }

                    // Peer mit öffentlicher IP in der DHT neu registrieren
                    // (beim ersten announce war die öffentliche IP noch nicht bekannt)
                    // Verwendet weiterhin das GLOBALE Topic!
                    if (isDhtAvailable) {
                        dhtNode?.announce(
                            topicBytes = DhtConfig.GLOBAL_TOPIC,
                            peerId = localPeerId,
                            publicHost = addr.host,
                            publicPort = addr.port
                        )
                        Log.i(TAG, "✅ Peer $localPeerId mit öffentlicher IP ${addr.host}:${addr.port} in der DHT aktualisiert (topic=${DhtConfig.GLOBAL_TOPIC_HEX.take(16)}...)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NAT-Traversal nicht verfügbar: ${e.message}")
            }
        }

        // 3. Periodischer mDNS-Scan (alle 30 Sekunden)
        mdnsScanJob = scope.launch {
            while (isActive) {
                try {
                    val localPeers = discoverLocalPeers()
                    if (localPeers.isNotEmpty()) {
                        Log.d(TAG, "${localPeers.size} lokale Peers gefunden via mDNS")
                        localPeers.forEach { peer ->
                            Libp2pManager.addDiscoveredPeer(peer)
                            addPeerToList(peer)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "mDNS-Scan fehlgeschlagen: ${e.message}")
                }
                delay(MDNS_SCAN_INTERVAL_MS)
            }
        }

        // 4. Periodische NAT-Prüfung (alle 5 Minuten)
        natCheckJob = scope.launch {
            while (isActive) {
                delay(NAT_CHECK_INTERVAL_MS)
                try {
                    val addr = natTraversal?.discoverPublicAddress()
                    if (addr != null) {
                        publicAddress = addr
                        Log.d(TAG, "Öffentliche Adresse aktualisiert: ${addr.host}:${addr.port}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NAT-Prüfung fehlgeschlagen: ${e.message}")
                }
            }
        }

        Log.i(TAG, "Peer-Discovery gestartet (DHT: ${if (isDhtAvailable) "verfügbar" else "prüfe..."})")
    }

    /**
     * Sucht nach einem bestimmten Peer in der DHT.
     *
     * @param targetPeerId Die Peer-ID des gesuchten Peers
     * @return Die RemotePeerInfo, oder null wenn nicht gefunden
     */
    suspend fun findPeer(targetPeerId: String): RemotePeerInfo? {
        Log.d(TAG, "Suche Peer in DHT: $targetPeerId")

        return try {
            val dhtPeer = dhtNode?.findPeer(targetPeerId)
            if (dhtPeer != null) {
                Log.i(TAG, "Peer $targetPeerId gefunden: ${dhtPeer.host}:${dhtPeer.port}")

                // Versuche NAT-Traversal, wenn nötig
                val nat = natTraversal
                if (nat != null) {
                    try {
                        if (!nat.testDirectConnection(dhtPeer.host, dhtPeer.port)) {
                            Log.d(TAG, "Direkte Verbindung fehlgeschlagen, versuche Hole Punching...")
                            nat.performHolePunching(dhtPeer.host, dhtPeer.port)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "NAT-Traversal fehlgeschlagen: ${e.message}")
                    }
                }

                val peerInfo = RemotePeerInfo(
                    peerId = targetPeerId,
                    host = dhtPeer.host,
                    port = dhtPeer.port,
                    isConnected = true
                )
                addPeerToList(peerInfo)
                peerInfo
            } else {
                Log.w(TAG, "Peer $targetPeerId nicht in der DHT gefunden")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DHT-Suche nach $targetPeerId fehlgeschlagen: ${e.message}", e)
            null
        }
    }

    /**
     * Findet Peers im lokalen Netzwerk via mDNS.
     *
     * mDNS (Multicast DNS) ist ein Zero-Configuration-Protokoll,
     * das Geräte im selben Broadcast-Domain automatisch erkennen lässt.
     *
     * @return Liste der gefundenen RemotePeerInfo-Objekte
     */
    suspend fun discoverLocalPeers(): List<RemotePeerInfo> {
        Log.d(TAG, "Starte mDNS-Scan im lokalen Netzwerk")

        return try {
            val peers = performMdnsScan()
            if (peers.isNotEmpty()) {
                Log.i(TAG, "${peers.size} Peers via mDNS gefunden")
            } else {
                Log.d(TAG, "Keine Peers via mDNS gefunden")
            }
            peers
        } catch (e: Exception) {
            Log.e(TAG, "mDNS-Scan fehlgeschlagen: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Führt einen mDNS-Scan durch.
     *
     * Sendet eine mDNS-Abfrage für den Crisix-Dienst und sammelt
     * Antworten von anderen Crisix-Geräten im selben Netzwerk.
     */
    private suspend fun performMdnsScan(): List<RemotePeerInfo> {
        val peers = mutableListOf<RemotePeerInfo>()

        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.soTimeout = 2000
                socket.broadcast = true

                try {
                    // mDNS-Abfrage für Crisix-Dienst
                    val query = buildMdnsQuery()
                    val packet = DatagramPacket(query, query.size, MDNS_ADDR, MDNS_PORT)
                    socket.send(packet)

                    // Antworten sammeln
                    val buffer = ByteArray(1024)
                    val startTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startTime < 2000) {
                        try {
                            val response = DatagramPacket(buffer, buffer.size)
                            socket.receive(response)
                            val peerInfo = parseMdnsResponse(response)
                            if (peerInfo != null) {
                                peers.add(peerInfo)
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            break
                        }
                    }
                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS-Scan fehlgeschlagen: ${e.message}")
        }

        return peers.distinctBy { it.peerId }
    }

    /**
     * Baut eine mDNS-Abfrage für den Crisix-Dienst.
     *
     * Format: Standard-DNS-Abfrage mit PTR-Typ für Dienstsuche.
     */
    private fun buildMdnsQuery(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(buffer)

        // DNS-Header
        dos.writeShort(0x0000) // Transaction ID
        dos.writeShort(0x0000) // Flags: Standard Query
        dos.writeShort(0x0001) // Questions: 1
        dos.writeShort(0x0000) // Answer RRs
        dos.writeShort(0x0000) // Authority RRs
        dos.writeShort(0x0000) // Additional RRs

        // Frage: PTR für _crisix._tcp.local
        val parts = CRISIX_SERVICE.split(".")
        for (part in parts) {
            dos.writeByte(part.length)
            dos.write(part.toByteArray())
        }
        dos.writeByte(0x00) // Ende des Namens
        dos.writeShort(12) // Typ: PTR
        dos.writeShort(0x8001) // Class: IN + QU (unicast response)

        dos.flush()
        return buffer.toByteArray()
    }

    /**
     * Parst eine mDNS-Antwort und extrahiert Peer-Informationen.
     */
    private fun parseMdnsResponse(packet: DatagramPacket): RemotePeerInfo? {
        try {
            val data = packet.data
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))

            // DNS-Header überspringen
            dis.skipBytes(12)

            // Fragen überspringen
            val questions = dis.readUnsignedShort()
            for (i in 0 until questions) {
                skipDnsName(dis)
                dis.skipBytes(4) // Typ + Class
            }

            // Antworten parsen
            val answers = dis.readUnsignedShort()
            var peerId: String? = null
            var host: String? = null
            var port: Int? = null

            for (i in 0 until answers) {
                skipDnsName(dis)
                val type = dis.readUnsignedShort()
                dis.skipBytes(2) // Class
                dis.skipBytes(4) // TTL
                val dataLength = dis.readUnsignedShort()

                when (type) {
                    12 -> { // PTR
                        // Dienstname überspringen
                        skipDnsName(dis)
                    }
                    16 -> { // TXT
                        if (dataLength > 0) {
                            val txtData = ByteArray(dataLength)
                            dis.readFully(txtData)
                            val txtStr = String(txtData, Charsets.UTF_8)
                            // TXT-Einträge parsen (key=value)
                            for (entry in txtStr.split(";")) {
                                val kv = entry.split("=", limit = 2)
                                if (kv.size == 2) {
                                    when (kv[0].trim()) {
                                        "peerId" -> peerId = kv[1].trim()
                                        "host" -> host = kv[1].trim()
                                    }
                                }
                            }
                        }
                    }
                    33 -> { // SRV
                        dis.skipBytes(2) // Priority
                        dis.skipBytes(2) // Weight
                        port = dis.readUnsignedShort()
                        skipDnsName(dis) // Target
                    }
                    1 -> { // A
                        if (dataLength == 4) {
                            val addrBytes = ByteArray(4)
                            dis.readFully(addrBytes)
                            host = InetAddress.getByAddress(addrBytes).hostAddress
                        } else {
                            dis.skipBytes(dataLength)
                        }
                    }
                    else -> {
                        dis.skipBytes(dataLength)
                    }
                }
            }

            if (peerId != null && host != null && port != null) {
                return RemotePeerInfo(
                    peerId = peerId,
                    host = host!!,
                    port = port!!,
                    isConnected = false
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen der mDNS-Antwort: ${e.message}")
        }

        return null
    }

    /**
     * Überspringt einen DNS-Namen im InputStream.
     */
    private fun skipDnsName(dis: java.io.DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                // Komprimierter Name (2 Bytes)
                dis.readUnsignedByte()
                break
            }
            dis.skipBytes(len)
        }
    }

    /**
     * Führt Hole Punching zu einem entfernten Peer durch.
     *
     * @param host Die IP-Adresse des Peers
     * @param port Der UDP-Port des Peers
     * @return true wenn erfolgreich
     */
    suspend fun performHolePunching(host: String, port: Int): Boolean {
        return natTraversal?.performHolePunching(host, port) ?: false
    }

    /**
     * Fügt einen Peer zur internen Liste hinzu, wenn er noch nicht vorhanden ist.
     */
    private fun addPeerToList(peer: RemotePeerInfo) {
        val currentList = _discoveredPeers.value.toMutableList()
        if (currentList.none { it.peerId == peer.peerId }) {
            currentList.add(peer)
            _discoveredPeers.value = currentList
        }
    }

    /**
     * Stoppt die Peer-Discovery.
     */
    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe Peer-Discovery")

        isRunning = false

        // DHT-Knoten stoppen
        scope.launch {
            dhtNode?.stop()
        }
        dhtNode = null

        // Jobs beenden
        mdnsScanJob?.cancel()
        natCheckJob?.cancel()
        punchListenerJob?.cancel()
        mdnsScanJob = null
        natCheckJob = null
        punchListenerJob = null

        isDhtAvailable = false
        publicAddress = null

        Log.i(TAG, "Peer-Discovery gestoppt")
    }
}

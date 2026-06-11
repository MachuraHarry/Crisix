package com.messenger.crisix.transport.internet

import android.util.Log
import timber.log.Timber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * Vollständiger Mainline DHT (BitTorrent) Knoten nach BEP 5.
 *
 * ## Übersicht
 * Diese Klasse implementiert einen vollwertigen Kademlia-DHT-Knoten,
 * der mit dem Mainline-BitTorrent-DHT-Netzwerk kompatibel ist.
 *
 * ## Protokoll (BEP 5 / KRPC)
 * - **bencode**-Serialisierung
 * - **UDP**-Transport auf Port 6881
 * - **SHA-1** (20 Byte) für Node-IDs und Topic-Hashes
 * - Nachrichtentypen: ping, find_node, get_peers, announce_peer
 *
 * ## DNS-Seeds (keine festen IPs!)
 * - router.bittorrent.com
 * - dht.transmissionbt.com
 * - router.utorrent.com
 *
 * ## Ablauf
 * 1. DNS-Seeds auflösen -> aktuelle Bootstrap-Knoten erhalten
 * 2. KRPC-Ping an Bootstrap-Knoten -> lebende Knoten finden
 * 3. KRPC-find_node -> Routing-Tabelle aufbauen
 * 4. Periodischer Refresh (alle 5 Minuten)
 * 5. announce_peer für eigenes Topic
 * 6. get_peers für fremde Topics
 *
 * @param localPeerId Die eigene Peer-ID (Fingerprint)
 * @param localPort Der lokale TCP-Port für eingehende Verbindungen
 */
class MainlineDhtNode(
    private val localPeerId: String,
    private val localPort: Int,
    /** Globales Topic für die Peer-Findung.
     *  Alle Crisix-Geräte verwenden DASSELBE Topic, damit sie sich in der DHT finden.
     *  SHA-1("crisix-messenger-v1") = fester 20-Byte-Hash */
    private val globalTopic: ByteArray = DhtConfig.GLOBAL_TOPIC
) {
    companion object {
        private const val TAG = "MainlineDhtNode"
        const val DHT_PORT = 6881
        private const val KRPC_TIMEOUT_MS = 2000L
        private const val K = 8
        private const val ALPHA = 3
        private const val REFRESH_INTERVAL_MS = 300_000L
        private const val MAX_NODES_RESPONSE = 20
        private const val MAX_PEERS_PER_TOPIC = 50
        private const val DNS_RETRY_DELAY_MS = 10_000L
        private const val MAX_CONCURRENT_PACKETS = 64

        private val DNS_SEEDS = listOf(
            "router.bittorrent.com",
            "dht.transmissionbt.com",
            "router.utorrent.com"
        )

        // Hardcodierte Fallback-Bootstrap-IPs, wenn DNS-Auflösung fehlschlägt.
        // Bekannte, stabile BitTorrent-DHT-Knoten (BEP 5).
        private val HARDCODED_BOOTSTRAP_NODES = listOf(
            "67.215.246.10:6881",   // router.bittorrent.com (häufigste IP)
            "85.17.19.198:6881",    // dht.transmissionbt.com
            "93.158.213.92:6881",   // router.utorrent.com
            "212.129.4.70:6881",    // Weitere bekannte Bootstrap-Knoten
            "185.121.177.177:6881",
            "31.172.123.215:6881",
            "104.154.97.10:6881",
            "185.56.85.37:6881"
        )
    }

    data class DhtNodeInfo(
        val nodeId: ByteArray,
        val host: String,
        val port: Int,
        var lastSeen: Long = System.currentTimeMillis(),
        var isAlive: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DhtNodeInfo) return false
            return nodeId.contentEquals(other.nodeId)
        }
        override fun hashCode(): Int = nodeId.contentHashCode()
    }

    data class TopicPeer(
        val peerId: String,
        val host: String,
        val port: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingPermits = Semaphore(MAX_CONCURRENT_PACKETS)
    private var socket: DatagramSocket? = null

    private val localNodeId: ByteArray by lazy {
        sha1(localPeerId.toByteArray(Charsets.UTF_8))
    }

    private val routingTable = Array<MutableList<DhtNodeInfo>>(160) { mutableListOf() }
    private val topicPeers = ConcurrentHashMap<String, MutableList<TopicPeer>>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    private var receiveJob: Job? = null
    private var refreshJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var knownNodesCount: Int = 0
        private set

    private val _discoveredPeers = MutableStateFlow<List<TopicPeer>>(emptyList())
    val discoveredPeers: Flow<List<TopicPeer>> = _discoveredPeers.asStateFlow()

    private val random = Random()

    private fun sha1(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-1").digest(input)
    }

    private fun nodeIdHex(): String = localNodeId.joinToString("") { "%02x".format(it) }

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    suspend fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starte Mainline-DHT-Knoten (Port $DHT_PORT, Node-ID: ${nodeIdHex().take(8)}...)")
        try {
            socket = try {
                DatagramSocket(DHT_PORT)
            } catch (bindError: Exception) {
                Log.w(TAG, "Port $DHT_PORT nicht verfügbar (${bindError.message}), verwende zufälligen Port")
                DatagramSocket(0)
            }
            val actualPort = socket?.localPort ?: 0
            Log.i(TAG, "DHT-Socket gebunden auf Port $actualPort")
            socket?.soTimeout = 5000
            startReceiveLoop()

            // DNS-Seeds auflösen + Hardcoded-Fallback + Retry
            val bootstrapNodes = resolveDnsSeeds()
            if (bootstrapNodes.isNotEmpty()) {
                Log.i(TAG, "${bootstrapNodes.size} Bootstrap-Knoten via DNS gefunden")
                joinNetwork(bootstrapNodes)
            } else {
                Log.w(TAG, "DNS-Seed-Auflösung fehlgeschlagen, versuche Hardcoded-Fallback...")
                if (joinNetwork(HARDCODED_BOOTSTRAP_NODES) == 0) {
                    Log.w(TAG, "Auch Hardcoded-Fallback antwortet nicht – DHT läuft im Offline-Modus")
                    // Retry DNS im Hintergrund
                    scope.launch {
                        delay(DNS_RETRY_DELAY_MS)
                        val retryNodes = resolveDnsSeeds()
                        if (retryNodes.isNotEmpty()) {
                            joinNetwork(retryNodes)
                        }
                    }
                }
            }
            startRefreshLoop()
            Log.i(TAG, "Mainline-DHT-Knoten gestartet. Routing-Tabelle: $knownNodesCount Knoten (Port $actualPort)")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        Log.i(TAG, "Stoppe Mainline-DHT-Knoten")
        isRunning = false
        receiveJob?.cancel()
        refreshJob?.cancel()
        socket?.close()
        socket = null
        for ((_, deferred) in pendingRequests) {
            deferred.completeExceptionally(CancellationException("DHT gestoppt"))
        }
        pendingRequests.clear()
        Log.i(TAG, "Mainline-DHT-Knoten gestoppt")
    }

    // =========================================================================
    // DNS-Seeds
    // =========================================================================

    private suspend fun resolveDnsSeeds(): List<String> {
        val nodes = mutableListOf<String>()
        for (seed in DNS_SEEDS) {
            try {
                Log.d(TAG, "Löse DNS-Seed auf: $seed")
                val addresses = withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(seed)
                }
                for (addr in addresses) {
                    val host = addr.hostAddress ?: continue
                    if (host.contains(":")) continue
                    val node = "$host:$DHT_PORT"
                    if (node !in nodes) {
                        nodes.add(node)
                        Log.d(TAG, "  -> $node (von $seed)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DNS-Auflösung fehlgeschlagen für $seed: ${e.message}")
            }
        }
        return nodes
    }

    // =========================================================================
    // Netzwerk-Beitritt
    // =========================================================================

    private suspend fun joinNetwork(bootstrapNodes: List<String>): Int {
        Log.i(TAG, "Trete Mainline-DHT-Netzwerk bei mit ${bootstrapNodes.size} Seeds...")
        var liveSeeds = 0
        for (seed in bootstrapNodes) {
            try {
                val parts = seed.split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "$DHT_PORT" }.toInt()
                Log.d(TAG, "Kontaktiere Seed: $host:$port")
                val pingOk = krpcPing(host, port)
                if (!pingOk) { Log.d(TAG, "Seed $host:$port antwortet nicht"); continue }
                val nodes = krpcFindNode(host, port, localNodeId)
                for ((nodeHost, nodePort, nodeId) in nodes) {
                    addToRoutingTable(DhtNodeInfo(nodeId, nodeHost, nodePort))
                }
                liveSeeds++
                Log.d(TAG, "Seed $host:$port: ${nodes.size} Knoten gefunden")
            } catch (e: Exception) {
                Log.d(TAG, "Seed $seed fehlgeschlagen: ${e.message}")
            }
        }
        val queried = mutableSetOf<String>()
        var previousCount = 0
        for (round in 0 until 5) {
            val toQuery = getAllAliveNodes()
                .filter { "${it.host}:${it.port}" !in queried }
                .take(ALPHA)
            if (toQuery.isEmpty()) break
            for (node in toQuery) {
                val key = "${node.host}:${node.port}"
                if (key in queried) continue
                queried.add(key)
                try {
                    val nodes = krpcFindNode(node.host, node.port, localNodeId)
                    for ((nodeHost, nodePort, nodeId) in nodes) {
                        addToRoutingTable(DhtNodeInfo(nodeId, nodeHost, nodePort))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "find_node an ${node.host}:${node.port} fehlgeschlagen")
                }
            }
            val currentCount = knownNodesCount
            if (currentCount == previousCount) break
            previousCount = currentCount
        }
        Log.i(TAG, "Netzwerk-Beitritt abgeschlossen. Routing-Tabelle: $knownNodesCount Knoten (${liveSeeds} lebende Seeds)")
        return liveSeeds
    }

    // =========================================================================
    // Öffentliche API
    // =========================================================================

    /**
     * Announced einen Peer in der DHT.
     *
     * Verwendet die Peer-ID als Topic, damit andere Peers diesen Peer
     * über seine Peer-ID finden können. Die öffentliche IP (via STUN)
     * wird statt "0.0.0.0" verwendet, damit andere Peers eine direkte
     * Verbindung herstellen können.
     *
     * @param topicBytes Das Topic (SHA-1 des Peer-Identifiers)
     * @param peerId Die Peer-ID
     * @param publicHost Die öffentliche IP (optional, via STUN ermittelt)
     * @param publicPort Der öffentliche Port (optional, via STUN ermittelt)
     */
    suspend fun announce(topicBytes: ByteArray, peerId: String, publicHost: String? = null, publicPort: Int? = null) {
        val topicHash = sha1(topicBytes)
        val topicHex = topicHash.joinToString("") { "%02x".format(it) }

        // Wichtig: Keine "0.0.0.0" verwenden! Die DHT kann damit nichts anfangen.
        // 1. Priorität: Öffentliche IP via STUN (publicHost)
        // 2. Priorität: Lokale WLAN-IP (Fallback für LAN-Kommunikation)
        // 3. Keine IP: Announce überspringen und Fehler loggen
        val localIPv4 = getLocalIPv4Address()
        val socketAddr = socket?.localAddress?.hostAddress
        val effectiveHost = publicHost ?: localIPv4 ?: socketAddr
        val effectivePort = publicPort ?: localPort

        Log.d(TAG, "announce: publicHost=$publicHost, localIPv4=$localIPv4, socketAddr=$socketAddr -> effectiveHost=$effectiveHost:$effectivePort")

        if (effectiveHost == null || effectiveHost == "0.0.0.0" || effectiveHost.isEmpty()) {
            Log.e(TAG, "❌ Announce übersprungen: Keine gültige IP-Adresse verfügbar! " +
                    "(publicHost=$publicHost, localPort=$localPort)")
            return
        }

        Log.i(TAG, "Announce: topic=${topicHex.take(8)}..., peer=$peerId, host=$effectiveHost:$effectivePort (publicHost=$publicHost, localPort=$localPort)")
        topicPeers.getOrPut(topicHex) { mutableListOf() }.apply {
            if (none { it.peerId == peerId }) {
                add(TopicPeer(peerId, effectiveHost, effectivePort))
                while (size > MAX_PEERS_PER_TOPIC) removeAt(0)
            }
        }
        val nearestNodes = findNearestNodes(topicHash, K)
        Log.d(TAG, "Announce: sende announce_peer an ${nearestNodes.size} nächste Knoten")
        var successCount = 0
        for (node in nearestNodes) {
            try {
                krpcAnnouncePeer(node.host, node.port, topicHash, effectivePort, peerId)
                successCount++
            } catch (e: Exception) {
                Log.w(TAG, "announce_peer an ${node.host}:${node.port} fehlgeschlagen: ${e.message}")
            }
        }
        Log.i(TAG, "Announce abgeschlossen: $successCount/${nearestNodes.size} Knoten erfolgreich benachrichtigt")
    }

    suspend fun findPeersForTopic(topicBytes: ByteArray): List<TopicPeer> {
        val topicHash = sha1(topicBytes)
        val topicHex = topicHash.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "Finde Peers für Topic: ${topicHex.take(8)}...")
        val localPeers = topicPeers[topicHex]?.toList() ?: emptyList()
        if (localPeers.isNotEmpty()) {
            Log.i(TAG, "${localPeers.size} Peers lokal gefunden, frage trotzdem DHT ab...")
        }
        val allPeers = mutableListOf<TopicPeer>()
        allPeers.addAll(localPeers)
        val queried = mutableSetOf<String>()
        val nearestNodes = findNearestNodes(topicHash, K)
        Log.i(TAG, "Suche in DHT: ${nearestNodes.size} nächste Knoten für Topic ${topicHex.take(8)}...")
        for (node in nearestNodes) {
            val key = "${node.host}:${node.port}"
            if (key in queried) continue
            queried.add(key)
            try {
                val (nodes, peers) = krpcGetPeers(node.host, node.port, topicHash)
                for ((nodeHost, nodePort, nodeId) in nodes) addToRoutingTable(DhtNodeInfo(nodeId, nodeHost, nodePort))
                for (peer in peers) { if (allPeers.none { it.host == peer.host && it.port == peer.port }) allPeers.add(peer) }
            } catch (e: Exception) { Log.d(TAG, "get_peers an ${node.host}:${node.port} fehlgeschlagen") }
        }
        if (allPeers.isNotEmpty()) { topicPeers[topicHex] = allPeers.toMutableList(); _discoveredPeers.value = allPeers }
        Log.i(TAG, "${allPeers.size} Peers für Topic gefunden (${localPeers.size} lokal, ${allPeers.size - localPeers.size} via DHT)")
        return allPeers
    }

    // =========================================================================
    // KRPC-Protokoll (BEP 5)
    // =========================================================================

    private suspend fun krpcPing(host: String, port: Int): Boolean {
        return try {
            withTimeout(KRPC_TIMEOUT_MS) {
                val tid = generateTransactionId()
                sendUdp(encodeKrpcQuery(tid, "ping"), host, port)
                waitForResponse(tid, KRPC_TIMEOUT_MS) != null
            }
        } catch (e: Exception) {
            Timber.w(e, "KRPC ping to $host:$port failed")
            false
        }
    }

    private suspend fun krpcFindNode(host: String, port: Int, targetId: ByteArray): List<Triple<String, Int, ByteArray>> {
        return try {
            withTimeout(KRPC_TIMEOUT_MS) {
                val tid = generateTransactionId()
                sendUdp(encodeKrpcFindNode(tid, targetId), host, port)
                val response = waitForResponse(tid, KRPC_TIMEOUT_MS)
                if (response != null) parseNodesFromResponse(response) else emptyList()
            }
        } catch (e: Exception) {
            Timber.w(e, "KRPC find_node to $host:$port failed")
            emptyList()
        }
    }

    private suspend fun krpcGetPeers(host: String, port: Int, infoHash: ByteArray): Pair<List<Triple<String, Int, ByteArray>>, List<TopicPeer>> {
        return try {
            withTimeout(KRPC_TIMEOUT_MS) {
                val tid = generateTransactionId()
                sendUdp(encodeKrpcGetPeers(tid, infoHash), host, port)
                val response = waitForResponse(tid, KRPC_TIMEOUT_MS)
                if (response != null) parseGetPeersResponse(response) else Pair(emptyList(), emptyList())
            }
        } catch (e: Exception) {
            Timber.w(e, "KRPC get_peers to $host:$port failed")
            Pair(emptyList(), emptyList())
        }
    }

    private suspend fun krpcAnnouncePeer(host: String, port: Int, infoHash: ByteArray, tcpPort: Int, peerId: String) {
        try {
            withTimeout(KRPC_TIMEOUT_MS) {
                val tid = generateTransactionId()
                sendUdp(encodeKrpcAnnouncePeer(tid, infoHash, tcpPort, peerId), host, port)
                waitForResponse(tid, KRPC_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Timber.w(e, "KRPC announce_peer to $host:$port failed")
        }
    }

    // =========================================================================
    // UDP-Kommunikation
    // =========================================================================

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            while (isRunning) {
                try {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    if (packet.length > 0) {
                        val data = buffer.copyOf(packet.length)
                        val senderHost = packet.address.hostAddress ?: "unknown"
                        val senderPort = packet.port
                        launch {
                            processingPermits.withPermit {
                                processIncomingMessage(data, senderHost, senderPort)
                            }
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normaler UDP-Timeout im Receive-Loop — kein Fehler
                }
                catch (e: Exception) { if (isRunning) Log.w(TAG, "Empfangsfehler: ${e.message}") }
            }
        }
    }

    private suspend fun processIncomingMessage(data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            val responseStr = String(data, Charsets.ISO_8859_1)
            if (responseStr.contains("1:y1:r")) {
                val tid = extractTransactionId(data)
                if (tid != null) {
                    val tidHex = tid.joinToString("") { "%02x".format(it) }
                    pendingRequests[tidHex]?.complete(data)
                    pendingRequests.remove(tidHex)
                }
            } else if (responseStr.contains("1:y1:q")) {
                handleIncomingQuery(data, senderHost, senderPort)
            }
        } catch (e: Exception) { Log.w(TAG, "Fehler bei Nachrichtenverarbeitung: ${e.message}") }
    }

    private suspend fun handleIncomingQuery(data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            val responseStr = String(data, Charsets.ISO_8859_1)
            val tid = extractTransactionId(data) ?: return
            when {
                responseStr.contains("1:q4:ping") -> sendUdp(encodeKrpcResponse(tid), senderHost, senderPort)
                responseStr.contains("1:q9:find_node") -> {
                    val targetId = extractTargetId(data)
                    if (targetId != null) {
                        sendUdp(encodeKrpcFindNodeResponse(tid, findNearestNodes(targetId, MAX_NODES_RESPONSE)), senderHost, senderPort)
                    }
                }
                responseStr.contains("1:q9:get_peers") -> {
                    val infoHash = extractInfoHash(data)
                    if (infoHash != null) {
                        val topicHex = infoHash.joinToString("") { "%02x".format(it) }
                        val peers = topicPeers[topicHex]?.take(MAX_PEERS_PER_TOPIC) ?: emptyList()
                        sendUdp(encodeKrpcGetPeersResponse(tid, peers, findNearestNodes(infoHash, MAX_NODES_RESPONSE)), senderHost, senderPort)
                    }
                }
                responseStr.contains("1:q13:announce_peer") -> {
                    val infoHash = extractInfoHash(data)
                    val impliedPort = extractImpliedPort(data)
                    val announcePort = if (impliedPort == 1) senderPort else extractAnnouncePort(data)
                    if (infoHash != null && announcePort != null) {
                        val topicHex = infoHash.joinToString("") { "%02x".format(it) }
                        topicPeers.getOrPut(topicHex) { mutableListOf() }.apply {
                            if (none { it.host == senderHost && it.port == announcePort }) {
                                add(TopicPeer("peer-$senderHost-$announcePort", senderHost, announcePort))
                                while (size > MAX_PEERS_PER_TOPIC) removeAt(0)
                            }
                        }
                        sendUdp(encodeKrpcResponse(tid), senderHost, senderPort)
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Fehler bei Query-Verarbeitung: ${e.message}") }
    }

    private suspend fun sendUdp(data: ByteArray, host: String, port: Int) {
        withContext(Dispatchers.IO) {
            val address = InetAddress.getByName(host)
            val packet = DatagramPacket(data, data.size, address, port)
            socket?.send(packet)
        }
    }

    private suspend fun waitForResponse(tid: ByteArray, timeoutMs: Long): ByteArray? {
        val tidHex = tid.joinToString("") { "%02x".format(it) }
        val deferred = CompletableDeferred<ByteArray>()
        pendingRequests[tidHex] = deferred
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) { pendingRequests.remove(tidHex); null }
        catch (e: Exception) { pendingRequests.remove(tidHex); null }
    }

    // =========================================================================
    // KRPC-Encoding (bencode)
    // =========================================================================

    private fun generateTransactionId(): ByteArray {
        val id = ByteArray(2); random.nextBytes(id); return id
    }

    private fun encodeKrpcQuery(tid: ByteArray, queryType: String): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:q1:q${queryType.length}:${queryType}1:ad2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcFindNode(tid: ByteArray, targetId: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:q1:q9:find_node1:ad2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("6:target20:".toByteArray())
        baos.write(targetId); baos.write("ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcGetPeers(tid: ByteArray, infoHash: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:q1:q9:get_peers1:ad2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("9:info_hash20:".toByteArray())
        baos.write(infoHash); baos.write("ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcAnnouncePeer(tid: ByteArray, infoHash: ByteArray, tcpPort: Int, peerId: String): ByteArray {
        val token = sha1(infoHash).joinToString("") { "%02x".format(it) }.take(8)
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:q1:q13:announce_peer1:ad2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("9:info_hash20:".toByteArray())
        baos.write(infoHash);         baos.write("12:implied_porti0e4:porti${tcpPort}e5:token${token.length}:${token}ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcResponse(tid: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:r1:rd2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcFindNodeResponse(tid: ByteArray, nodes: List<DhtNodeInfo>): ByteArray {
        val compactNodes = encodeCompactNodes(nodes)
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:r1:rd2:id20:".toByteArray())
        baos.write(localNodeId); baos.write("5:nodes${compactNodes.size}:".toByteArray())
        baos.write(compactNodes); baos.write("ee".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeKrpcGetPeersResponse(tid: ByteArray, peers: List<TopicPeer>, nodes: List<DhtNodeInfo>): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("d1:t${tid.size}:".toByteArray()); baos.write(tid)
        baos.write("1:y1:r1:rd2:id20:".toByteArray())
        baos.write(localNodeId)
        val token = sha1(localNodeId).joinToString("") { "%02x".format(it) }.take(8)
        baos.write("5:token${token.length}:${token}".toByteArray())
        if (peers.isNotEmpty()) {
            baos.write("6:valuesl".toByteArray())
            for (peer in peers) {
                val ipBytes = try { InetAddress.getByName(peer.host).address } catch (e: Exception) { byteArrayOf(0,0,0,0) }
                if (ipBytes.size == 4) {
                    baos.write("6:".toByteArray())
                    baos.write(ipBytes)
                    baos.write(((peer.port shr 8) and 0xFF))
                    baos.write((peer.port and 0xFF))
                }
            }
            baos.write("e".toByteArray())
        }
        if (nodes.isNotEmpty()) {
            val compactNodes = encodeCompactNodes(nodes)
            baos.write("5:nodes${compactNodes.size}:".toByteArray())
            baos.write(compactNodes)
        }
        baos.write("e".toByteArray())
        return baos.toByteArray()
    }

    private fun encodeCompactNodes(nodes: List<DhtNodeInfo>): ByteArray {
        val baos = ByteArrayOutputStream()
        for (node in nodes) {
            if (node.nodeId.size == 20) {
                baos.write(node.nodeId)
                val ipBytes = try { InetAddress.getByName(node.host).address } catch (e: Exception) { byteArrayOf(0,0,0,0) }
                if (ipBytes.size == 4) baos.write(ipBytes) else baos.write(byteArrayOf(0,0,0,0))
                baos.write(((node.port shr 8) and 0xFF))
                baos.write((node.port and 0xFF))
            }
        }
        return baos.toByteArray()
    }

    // =========================================================================
    // KRPC-Decoding (bencode)
    // =========================================================================

    private fun extractTransactionId(data: ByteArray): ByteArray? {
        val str = String(data, Charsets.ISO_8859_1)
        val match = Regex("1:t(\\d+):").find(str) ?: return null
        val len = match.groupValues[1].toIntOrNull() ?: return null
        val start = match.range.last + 1
        if (start + len > data.size) return null
        return data.copyOfRange(start, start + len)
    }

    private fun extractTargetId(data: ByteArray): ByteArray? {
        val str = String(data, Charsets.ISO_8859_1)
        val match = Regex("6:target(\\d+):").find(str) ?: return null
        val len = match.groupValues[1].toIntOrNull() ?: return null
        val start = match.range.last + 1
        if (start + len > data.size) return null
        return data.copyOfRange(start, start + len)
    }

    private fun extractInfoHash(data: ByteArray): ByteArray? {
        val str = String(data, Charsets.ISO_8859_1)
        val match = Regex("9:info_hash(\\d+):").find(str) ?: return null
        val len = match.groupValues[1].toIntOrNull() ?: return null
        val start = match.range.last + 1
        if (start + len > data.size) return null
        return data.copyOfRange(start, start + len)
    }

    private fun extractImpliedPort(data: ByteArray): Int {
        val str = String(data, Charsets.ISO_8859_1)
        val match = Regex("12:implied_porti(\\d+)e").find(str)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractAnnouncePort(data: ByteArray): Int? {
        val str = String(data, Charsets.ISO_8859_1)
        val match = Regex("""(?<!implied_)4:porti(\d+)e""").find(str)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseNodesFromResponse(data: ByteArray): List<Triple<String, Int, ByteArray>> {
        val nodes = mutableListOf<Triple<String, Int, ByteArray>>()
        val str = String(data, Charsets.ISO_8859_1)
        try {
            val match = Regex("5:nodes(\\d+):").find(str) ?: return nodes
            val dataLen = match.groupValues[1].toIntOrNull() ?: return nodes
            val start = match.range.last + 1
            if (start + dataLen > data.size) return nodes
            val nodeData = data.copyOfRange(start, start + dataLen)
            var offset = 0
            while (offset + 26 <= nodeData.size) {
                try {
                    val nodeId = nodeData.copyOfRange(offset, offset + 20)
                    offset += 20
                    val ipBytes = nodeData.copyOfRange(offset, offset + 4)
                    offset += 4
                    val port = ((nodeData[offset].toInt() and 0xFF) shl 8) or (nodeData[offset + 1].toInt() and 0xFF)
                    offset += 2
                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                    if (ip.isNotEmpty() && port > 0 && !ip.startsWith("0.")) {
                        nodes.add(Triple(ip, port, nodeId))
                    }
                } catch (e: Exception) { offset += 26 }
            }
        } catch (e: Exception) { Log.w(TAG, "Fehler beim Parsen der Nodes: ${e.message}") }
        return nodes
    }

    private fun parseGetPeersResponse(data: ByteArray): Pair<List<Triple<String, Int, ByteArray>>, List<TopicPeer>> {
        val nodes = mutableListOf<Triple<String, Int, ByteArray>>()
        val peers = mutableListOf<TopicPeer>()
        val str = String(data, Charsets.ISO_8859_1)
        try {
            val valuesMatch = Regex("6:valuesl((?:\\d+:.{6})*)e").find(str)
            if (valuesMatch != null) {
                val valuesStr = valuesMatch.groupValues[1]
                val valueRegex = Regex("(\\d+):(.{6})")
                for (vm in valueRegex.findAll(valuesStr)) {
                    val peerData = vm.groupValues[2].toByteArray(Charsets.ISO_8859_1)
                    if (peerData.size == 6) {
                        val ipBytes = peerData.copyOfRange(0, 4)
                        val port = ((peerData[4].toInt() and 0xFF) shl 8) or (peerData[5].toInt() and 0xFF)
                        val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                        if (ip.isNotEmpty() && port > 0) {
                            peers.add(TopicPeer("peer-$ip-$port", ip, port))
                        }
                    }
                }
            }
            // Auch "nodes" parsen (Fallback, wenn keine values)
            val parsedNodes = parseNodesFromResponse(data)
            nodes.addAll(parsedNodes)
        } catch (e: Exception) { Log.w(TAG, "Fehler beim Parsen der get_peers-Antwort: ${e.message}") }
        return Pair(nodes, peers)
    }

    // =========================================================================
    // Routing-Tabelle
    // =========================================================================

    /**
     * Fügt einen Knoten zur Routing-Tabelle hinzu.
     * Verwendet Kademlia K-Buckets basierend auf der XOR-Distanz.
     */
    private fun addToRoutingTable(node: DhtNodeInfo) {
        if (node.nodeId.size != 20) return
        if (node.nodeId.contentEquals(localNodeId)) return // Sich selbst nicht speichern
        if (node.host.isEmpty() || node.port <= 0) return
        if (node.host.startsWith("0.")) return

        val bucketIndex = bucketIndex(node.nodeId)
        val bucket = routingTable[bucketIndex]

        synchronized(bucket) {
            // Prüfen, ob der Knoten bereits existiert
            val existing = bucket.find { it.nodeId.contentEquals(node.nodeId) }
            if (existing != null) {
                existing.lastSeen = System.currentTimeMillis()
                existing.isAlive = true
                return
            }

            // Neuen Knoten hinzufügen (wenn Platz)
            if (bucket.size < K) {
                bucket.add(node)
                knownNodesCount++
            } else {
                // K-Bucket voll -> ältesten Knoten ersetzen (LRU-ähnlich)
                val oldest = bucket.minByOrNull { it.lastSeen }
                if (oldest != null) {
                    bucket.remove(oldest)
                    bucket.add(node)
                }
            }
        }
    }

    /**
     * Berechnet den K-Bucket-Index für eine Node-ID.
     * Der Index ist die Position des ersten unterschiedlichen Bits.
     */
    private fun bucketIndex(nodeId: ByteArray): Int {
        for (i in 0 until 20) {
            val xor = (localNodeId[i].toInt() xor nodeId[i].toInt()) and 0xFF
            if (xor != 0) {
                // Position des höchsten gesetzten Bits (0-7)
                var bitPos = 7
                var mask = 0x80
                while (mask > 0) {
                    if (xor and mask != 0) {
                        return (i * 8) + bitPos
                    }
                    bitPos--
                    mask = mask shr 1
                }
            }
        }
        return 159 // Gleiche Node-ID (sollte nicht vorkommen)
    }

    /**
     * Findet die nächstgelegenen Knoten zu einer Ziel-ID in der Routing-Tabelle.
     *
     * @param targetId Die Ziel-ID (20 Bytes)
     * @param count Maximale Anzahl zurückzugebender Knoten
     * @return Liste der nächstgelegenen Knoten
     */
    private fun findNearestNodes(targetId: ByteArray, count: Int): List<DhtNodeInfo> {
        val candidates = mutableListOf<DhtNodeInfo>()

        // Alle lebenden Knoten aus der Routing-Tabelle sammeln
        for (bucket in routingTable) {
            synchronized(bucket) {
                candidates.addAll(bucket.filter { it.isAlive })
            }
        }

        // Nach XOR-Distanz sortieren
        candidates.sortBy { xorDistanceAsHex(it.nodeId, targetId) }

        return candidates.take(count)
    }

    /**
     * Findet einen bestimmten Knoten in der Routing-Tabelle anhand seiner Node-ID.
     */
    private fun findInRoutingTable(targetId: ByteArray): DhtNodeInfo? {
        val bucketIndex = bucketIndex(targetId)
        val bucket = routingTable[bucketIndex]
        synchronized(bucket) {
            return bucket.find { it.nodeId.contentEquals(targetId) && it.isAlive }
        }
    }

    /**
     * Gibt alle lebenden Knoten aus der Routing-Tabelle zurück.
     */
    private fun getAllAliveNodes(): List<DhtNodeInfo> {
        val nodes = mutableListOf<DhtNodeInfo>()
        for (bucket in routingTable) {
            synchronized(bucket) {
                nodes.addAll(bucket.filter { it.isAlive })
            }
        }
        return nodes
    }

    /**
     * Berechnet die XOR-Distanz zwischen zwei Node-IDs als Hex-String (für Sortierung).
     */
    private fun xorDistanceAsHex(a: ByteArray, b: ByteArray): String {
        val result = ByteArray(20)
        for (i in 0 until 20) {
            result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return result.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // Refresh-Loop
    // =========================================================================

    /**
     * Startet den periodischen Refresh der Routing-Tabelle.
     * Alle 5 Minuten werden die ältesten Buckets aufgefrischt.
     */
    private fun startRefreshLoop() {
        refreshJob = scope.launch {
            while (isRunning) {
                delay(REFRESH_INTERVAL_MS)
                if (!isRunning) break
                Log.d(TAG, "Starte Routing-Tabellen-Refresh...")

                // Älteste Buckets auffrischen
                val staleBuckets = routingTable.indices
                    .map { it to routingTable[it] }
                    .filter { (_, bucket) ->
                        synchronized(bucket) {
                            bucket.isEmpty() || bucket.all { System.currentTimeMillis() - it.lastSeen > REFRESH_INTERVAL_MS }
                        }
                    }
                    .take(ALPHA)

                for ((index, bucket) in staleBuckets) {
                    synchronized(bucket) {
                        if (bucket.isNotEmpty()) {
                            // Zufällige ID in diesem Bucket-Bereich generieren
                            val randomId = generateRandomIdInBucket(index)
                            val node = bucket.first()
                            launch {
                                try {
                                    val nodes = krpcFindNode(node.host, node.port, randomId)
                                    for ((nodeHost, nodePort, nodeId) in nodes) {
                                        addToRoutingTable(DhtNodeInfo(nodeId, nodeHost, nodePort))
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "Refresh an ${node.host}:${node.port} fehlgeschlagen")
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "Routing-Tabellen-Refresh abgeschlossen. $knownNodesCount Knoten")
            }
        }
    }

    /**
     * Generiert eine zufällige Node-ID, die in den angegebenen Bucket fällt.
     */
    private fun generateRandomIdInBucket(bucketIndex: Int): ByteArray {
        val id = ByteArray(20)
        random.nextBytes(id)

        // Erste `bucketIndex` Bits von localNodeId kopieren
        val bytesToCopy = bucketIndex / 8
        for (i in 0 until bytesToCopy) {
            id[i] = localNodeId[i]
        }

        // Das Bit an Position bucketIndex invertieren
        val bitPosition = bucketIndex % 8
        if (bytesToCopy < 20) {
            val mask = (1 shl (7 - bitPosition)).toByte()
            id[bytesToCopy] = (localNodeId[bytesToCopy].toInt() xor mask.toInt()).toByte()
        }

        return id
    }

    /**
     * Ermittelt die lokale IP-Adresse des Geräts (bevorzugt IPv4).
     * Überspringt Loopback- und Emulator-Interfaces (10.0.2.x).
     * Fallback auf nicht-Link-Local IPv6, wenn kein IPv4 verfügbar.
     *
     * @return Die lokale IP-Adresse, oder null wenn keine gefunden wurde.
     */
    private fun getLocalIPv4Address(): String? {
        var ipv6Fallback: String? = null
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (addr is java.net.Inet4Address) {
                                if (host.startsWith("10.0.2.")) continue
                                Log.d(TAG, "Lokale IPv4 gefunden: $host")
                                return host
                            } else if (addr is java.net.Inet6Address && ipv6Fallback == null) {
                                val ipv6 = host.split("%")[0]
                                if (ipv6 != "::1" && !ipv6.startsWith("fe80")) {
                                    Log.d(TAG, "Lokale IPv6 gefunden (Fallback): $ipv6")
                                    ipv6Fallback = ipv6
                                }
                            }
                        }
                    }
                }
            }
            ipv6Fallback
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Ermitteln der lokalen IP: ${e.message}")
            null
        }
    }
}

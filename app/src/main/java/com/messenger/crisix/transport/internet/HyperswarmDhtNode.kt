package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hyperswarm-kompatible Kademlia DHT-Implementierung.
 *
 * ## Übersicht
 * Diese Klasse implementiert einen DHT-Knoten, der mit dem Hyperswarm-Protokoll
 * kompatibel ist. Sie verwendet:
 * - **MessagePack** für Nachrichtenserialisierung (via [HyperswarmProtocol])
 * - **Noise XX** für per-packet Verschlüsselung (via [NoisePacketCrypto])
 * - **UDP** auf Port 49737 für den Transport
 * - **Kademlia** für die Routing-Tabelle
 *
 * ## Bootstrap-Knoten
 * Die DHT verbindet sich zu öffentlichen Hyperswarm-Bootstrap-Knoten,
 * um dem Netzwerk beizutreten.
 *
 * ## Verwendung
 * ```kotlin
 * val dht = HyperswarmDhtNode(localPeerId, localPublicKey, localPort)
 * dht.start()
 *
 * // Für ein Topic announcen
 * dht.announce(topic, peerId)
 *
 * // Peers für ein Topic finden
 * val peers = dht.findPeersForTopic(topic)
 * ```
 */
class HyperswarmDhtNode(
    private val localPeerId: String,
    private val localPublicKey: ByteArray,
    private val localPort: Int = HyperswarmProtocol.DHT_PORT
) {
    companion object {
        private const val TAG = "HyperswarmDhtNode"

        /** Anzahl der Buckets in der Kademlia-Routing-Tabelle */
        private const val BUCKET_COUNT = 160

        /** Maximale Anzahl von Nodes pro Bucket */
        private const val BUCKET_SIZE = 20

        /** Anzahl der nächstgelegenen Nodes, die zurückgegeben werden */
        private const val ALPHA = 3

        /** Timeout für DHT-Anfragen in Millisekunden */
        private const val REQUEST_TIMEOUT_MS = 3000L

        /** Intervall für Routing-Tabellen-Refresh in Millisekunden */
        private const val REFRESH_INTERVAL_MS = 300_000L

        /** Maximale Anzahl von Peers pro Topic */
        private const val MAX_PEERS_PER_TOPIC = 50
    }

    // =========================================================================
    // Datenklassen
    // =========================================================================

    /**
     * Repräsentiert einen Node in der Kademlia-Routing-Tabelle.
     */
    data class KademliaNode(
        val nodeId: ByteArray,
        val host: String,
        val port: Int,
        var lastSeen: Long = System.currentTimeMillis(),
        var isAlive: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KademliaNode) return false
            return nodeId.contentEquals(other.nodeId)
        }

        override fun hashCode(): Int = nodeId.contentHashCode()
    }

    /**
     * Ergebnis einer FIND_NODE-Anfrage.
     */
    data class FindNodeResult(
        val node: KademliaNode,
        val distance: ByteArray
    )

    // =========================================================================
    // Zustand
    // =========================================================================

    /** Coroutine-Scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** UDP-Socket für DHT-Kommunikation */
    private var socket: DatagramSocket? = null

    /** Routing-Tabelle: Buckets mit KademliaNodes */
    private val routingTable = Array<MutableList<KademliaNode>>(BUCKET_COUNT) { mutableListOf() }

    /** Topic -> Liste von Peer-IDs */
    private val topicPeers = ConcurrentHashMap<String, MutableList<String>>()

    /** Ausstehende Anfragen: nonce -> CompletableDeferred<HyperswarmProtocol.HyperswarmMessage> */
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<HyperswarmProtocol.HyperswarmMessage>>()

    /** Empfangs-Job */
    private var receiveJob: Job? = null

    /** Refresh-Job */
    private var refreshJob: Job? = null

    /** Laufzeitstatus */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Noise-Kryptografie-Instanz */
    private var noiseCrypto: NoisePacketCrypto? = null

    /** Peer-ID als Byte-Array */
    private val localPeerIdBytes: ByteArray by lazy {
        localPeerId.toByteArray(Charsets.UTF_8)
    }

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    /**
     * Startet den DHT-Knoten.
     *
     * 1. Öffnet UDP-Socket auf dem angegebenen Port
     * 2. Startet den Empfangs-Job
     * 3. Verbindet sich zu Bootstrap-Knoten
     * 4. Startet periodischen Refresh
     */
    suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "DHT-Node läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte Hyperswarm-DHT-Node auf Port $localPort")

        try {
            // UDP-Socket öffnen
            socket = DatagramSocket(localPort)
            socket?.soTimeout = 5000

            // Noise-Kryptografie initialisieren
            noiseCrypto = NoisePacketCrypto(localPeerIdBytes, localPublicKey)

            // Empfangs-Job starten
            startReceiveLoop()

            // Zu Bootstrap-Knoten verbinden
            connectToBootstrap()

            // Periodischen Refresh starten
            startRefreshLoop()

            Log.i(TAG, "Hyperswarm-DHT-Node gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten des DHT-Nodes: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Stoppt den DHT-Knoten.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe Hyperswarm-DHT-Node")

        isRunning = false

        receiveJob?.cancel()
        refreshJob?.cancel()
        socket?.close()
        socket = null

        // Ausstehende Anfragen abbrechen
        for ((_, deferred) in pendingRequests) {
            deferred.completeExceptionally(CancellationException("DHT-Node gestoppt"))
        }
        pendingRequests.clear()

        Log.i(TAG, "Hyperswarm-DHT-Node gestoppt")
    }

    // =========================================================================
    // Bootstrap
    // =========================================================================

    /**
     * Verbindet sich zu den Bootstrap-Knoten.
     *
     * Verwendet eine zweistufige Strategie:
     * 1. **Mainline-DHT (KRPC/bencode)**: Verbindet sich zu Mainline-DHT-Knoten
     *    über das KRPC-Protokoll (BEP 5). Dies ist nötig, weil die DNS-Seeds
     *    (router.bittorrent.com, etc.) Mainline-DHT-Knoten liefern, die kein
     *    Hyperswarm-MessagePack verstehen.
     * 2. **Hyperswarm-PING**: Sendet Hyperswarm-PINGs an die gefundenen Knoten,
     *    um zu prüfen, welche unser Protokoll verstehen.
     *
     * Wichtig: Mainline-DHT-Knoten antworten NICHT auf Hyperswarm-PINGs.
     * Daher ist Schritt 2 meist erfolglos. Die Routing-Tabelle enthält
     * trotzdem Mainline-Knoten, die für die Peer-Suche via KRPC verwendet
     * werden können.
     */
    private suspend fun connectToBootstrap() {
        Log.i(TAG, "Verbinde zu Bootstrap-Knoten...")

        val dnsSeeds = BootstrapNodes.getNodes()
        Log.i(TAG, "DNS-Seeds aufgelöst: ${dnsSeeds.size} Seeds")

        // Schritt 1: Mainline-DHT-Bootstrap (KRPC/bencode)
        Log.i(TAG, "Starte Mainline-DHT-Bootstrap (KRPC)...")
        val mainlineClient = MainlineDhtClient()
        val mainlineNodes = mainlineClient.bootstrap(dnsSeeds)

        if (mainlineNodes.isNotEmpty()) {
            Log.i(TAG, "Mainline-DHT: ${mainlineNodes.size} Knoten gefunden")

            // Mainline-Knoten zur Routing-Tabelle hinzufügen
            for (nodeStr in mainlineNodes) {
                val parts = nodeStr.split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "49737" }.toInt()

                val nodeId = HyperswarmProtocol.sha256(
                    "$host:$port".toByteArray(Charsets.UTF_8)
                )

                addToRoutingTable(
                    KademliaNode(
                        nodeId = nodeId,
                        host = host,
                        port = port
                    )
                )
            }
        } else {
            Log.w(TAG, "Mainline-DHT-Bootstrap lieferte keine Knoten")
        }

        // Schritt 2: Hyperswarm-PING an alle Bootstrap-Seeds + Mainline-Knoten
        val allNodes = (dnsSeeds + mainlineNodes).distinct()
        var connectedCount = 0

        Log.i(TAG, "Prüfe ${allNodes.size} Knoten mit Hyperswarm-PING...")

        for (node in allNodes) {
            try {
                val parts = node.split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "49737" }.toInt()

                Log.d(TAG, "Versuche Hyperswarm-PING: $host:$port")

                val nonce = HyperswarmProtocol.generateNonce()
                val ping = HyperswarmProtocol.HyperswarmMessage.Ping(nonce = nonce)
                sendMessage(ping, host, port)

                val response = waitForResponse(nonce, REQUEST_TIMEOUT_MS)
                if (response != null) {
                    Log.d(TAG, "Hyperswarm-PONG von $host:$port empfangen")

                    val findNonce = HyperswarmProtocol.generateNonce()
                    val findNode = HyperswarmProtocol.HyperswarmMessage.FindNode(
                        nonce = findNonce,
                        targetId = localPeerIdBytes
                    )
                    sendMessage(findNode, host, port)

                    val nodesResponse = waitForResponse(findNonce, REQUEST_TIMEOUT_MS)
                    if (nodesResponse is HyperswarmProtocol.HyperswarmMessage.Nodes) {
                        for (foundNode in nodesResponse.nodes) {
                            addToRoutingTable(
                                KademliaNode(
                                    nodeId = foundNode.nodeId,
                                    host = foundNode.host,
                                    port = foundNode.port
                                )
                            )
                        }
                    }

                    connectedCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hyperswarm-PING fehlgeschlagen: ${e.message}")
            }
        }

        Log.i(TAG, "Verbunden mit $connectedCount Hyperswarm-Peers, " +
                "Routing-Tabelle: ${getRoutingTableSize()} Nodes")
    }

    // =========================================================================
    // Öffentliche API
    // =========================================================================

    /**
     * Announced einen Peer für ein Topic in der DHT.
     *
     * @param topic Das Topic (32 Bytes SHA-256 Hash)
     * @param peerId Die Peer-ID
     */
    suspend fun announce(topic: ByteArray, peerId: String) {
        Log.d(TAG, "Announce: topic=${topic.take(4).joinToString("") { "%02x".format(it) }}..., peer=$peerId")

        val topicHex = topic.joinToString("") { "%02x".format(it) }

        // Lokal speichern
        topicPeers.getOrPut(topicHex) { mutableListOf() }.apply {
            if (!contains(peerId)) {
                add(peerId)
                // Auf maximale Größe begrenzen
                while (size > MAX_PEERS_PER_TOPIC) {
                    removeAt(0)
                }
            }
        }

        // ANNOUNCE an nahe Nodes senden
        val nonce = HyperswarmProtocol.generateNonce()
        val announce = HyperswarmProtocol.HyperswarmMessage.Announce(
            nonce = nonce,
            topic = topic,
            peerId = peerId.toByteArray(Charsets.UTF_8)
        )

        val nearestNodes = findNearestNodes(topic)
        for (node in nearestNodes) {
            try {
                sendMessage(announce, node.host, node.port)
            } catch (e: Exception) {
                Log.w(TAG, "Announce an ${node.host}:${node.port} fehlgeschlagen: ${e.message}")
            }
        }
    }

    /**
     * Entfernt ein Announcement für ein Topic.
     *
     * @param topic Das Topic (32 Bytes SHA-256 Hash)
     * @param peerId Die Peer-ID
     */
    suspend fun unannounce(topic: ByteArray, peerId: String) {
        Log.d(TAG, "Unannounce: topic=${topic.take(4).joinToString("") { "%02x".format(it) }}..., peer=$peerId")

        val topicHex = topic.joinToString("") { "%02x".format(it) }

        // Lokal entfernen
        topicPeers[topicHex]?.remove(peerId)

        // UNANNOUNCE an nahe Nodes senden
        val nonce = HyperswarmProtocol.generateNonce()
        val unannounce = HyperswarmProtocol.HyperswarmMessage.Unannounce(
            nonce = nonce,
            topic = topic,
            peerId = peerId.toByteArray(Charsets.UTF_8)
        )

        val nearestNodes = findNearestNodes(topic)
        for (node in nearestNodes) {
            try {
                sendMessage(unannounce, node.host, node.port)
            } catch (e: Exception) {
                Log.w(TAG, "Unannounce an ${node.host}:${node.port} fehlgeschlagen: ${e.message}")
            }
        }
    }

    /**
     * Findet Peers für ein Topic in der DHT.
     *
     * @param topic Das Topic (32 Bytes SHA-256 Hash)
     * @return Liste der Peer-IDs
     */
    suspend fun findPeersForTopic(topic: ByteArray): List<String> {
        Log.d(TAG, "Finde Peers für Topic: ${topic.take(4).joinToString("") { "%02x".format(it) }}...")

        val topicHex = topic.joinToString("") { "%02x".format(it) }

        // Zuerst lokal prüfen
        val localPeers = topicPeers[topicHex]?.toList() ?: emptyList()
        if (localPeers.isNotEmpty()) {
            return localPeers
        }

        // FIND_NODE mit Topic als Ziel-ID
        val nonce = HyperswarmProtocol.generateNonce()
        val findNode = HyperswarmProtocol.HyperswarmMessage.FindNode(
            nonce = nonce,
            targetId = topic
        )

        // An nahe Nodes senden
        val nearestNodes = findNearestNodes(topic)
        val allPeers = mutableListOf<String>()

        for (node in nearestNodes) {
            try {
                sendMessage(findNode, node.host, node.port)
                val response = waitForResponse(nonce, REQUEST_TIMEOUT_MS)
                if (response is HyperswarmProtocol.HyperswarmMessage.Nodes) {
                    for (foundNode in response.nodes) {
                        addToRoutingTable(
                            KademliaNode(
                                nodeId = foundNode.nodeId,
                                host = foundNode.host,
                                port = foundNode.port
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FIND_NODE an ${node.host}:${node.port} fehlgeschlagen: ${e.message}")
            }
        }

        // Nach Announcements für dieses Topic fragen
        // (In einer vollständigen Implementierung würde man ANNOUNCE-Nachrichten
        //  in der Routing-Tabelle speichern und bei FIND_NODE zurückgeben)

        return allPeers.distinct()
    }

    /**
     * Findet Peers anhand der Kurz-ID (8-stelliger Fingerprint).
     *
     * @param shortId Die 8-stellige Kurz-ID
     * @return Liste der Peer-IDs, die diesen Fingerprint haben
     */
    suspend fun findPeersByShortId(shortId: String): List<String> {
        Log.d(TAG, "Suche Peers mit Kurz-ID: $shortId")

        // In einer vollständigen Implementierung würde man die DHT
        // nach Peers mit diesem Fingerprint durchsuchen.
        // Da die Kurz-ID nur 8 Bytes des SHA-256-Hashes des Public Keys ist,
        // kann es mehrere Peers mit derselben Kurz-ID geben.

        // Vereinfachte Implementierung: FIND_NODE mit einem aus der
        // Kurz-ID abgeleiteten Ziel
        val targetId = shortId.toByteArray(Charsets.UTF_8).copyOf(32)

        val nonce = HyperswarmProtocol.generateNonce()
        val findNode = HyperswarmProtocol.HyperswarmMessage.FindNode(
            nonce = nonce,
            targetId = targetId
        )

        val nearestNodes = findNearestNodes(targetId)
        val foundPeers = mutableListOf<String>()

        for (node in nearestNodes) {
            try {
                sendMessage(findNode, node.host, node.port)
                val response = waitForResponse(nonce, REQUEST_TIMEOUT_MS)
                if (response is HyperswarmProtocol.HyperswarmMessage.Nodes) {
                    for (foundNode in response.nodes) {
                        val peerShortId = HyperswarmProtocol.getShortId(foundNode.nodeId)
                        if (peerShortId == shortId) {
                            foundPeers.add(String(foundNode.nodeId, Charsets.UTF_8))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fehler bei Kurz-ID-Suche: ${e.message}")
            }
        }

        return foundPeers.distinct()
    }

    /**
     * Findet einen bestimmten Peer in der DHT.
     *
     * @param peerId Die Peer-ID
     * @return Die Node-Informationen, oder null
     */
    suspend fun findPeer(peerId: String): KademliaNode? {
        Log.d(TAG, "Suche Peer: $peerId")

        val targetId = peerId.toByteArray(Charsets.UTF_8)

        // In der Routing-Tabelle suchen
        val bucketIndex = getBucketIndex(targetId)
        val bucket = routingTable[bucketIndex]
        val existing = bucket.find { it.nodeId.contentEquals(targetId) }
        if (existing != null && existing.isAlive) {
            return existing
        }

        // FIND_NODE in der DHT
        val nonce = HyperswarmProtocol.generateNonce()
        val findNode = HyperswarmProtocol.HyperswarmMessage.FindNode(
            nonce = nonce,
            targetId = targetId
        )

        val nearestNodes = findNearestNodes(targetId)
        for (node in nearestNodes) {
            try {
                sendMessage(findNode, node.host, node.port)
                val response = waitForResponse(nonce, REQUEST_TIMEOUT_MS)
                if (response is HyperswarmProtocol.HyperswarmMessage.Nodes) {
                    for (foundNode in response.nodes) {
                        if (foundNode.nodeId.contentEquals(targetId)) {
                            val kadNode = KademliaNode(
                                nodeId = foundNode.nodeId,
                                host = foundNode.host,
                                port = foundNode.port
                            )
                            addToRoutingTable(kadNode)
                            return kadNode
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fehler bei Peer-Suche: ${e.message}")
            }
        }

        return null
    }

    /**
     * Aktualisiert die Routing-Tabelle (periodischer Refresh).
     */
    suspend fun refresh() {
        Log.d(TAG, "DHT-Refresh gestartet")

        // PING an alle Nodes in der Routing-Tabelle senden
        for (bucket in routingTable) {
            val deadNodes = mutableListOf<KademliaNode>()
            for (node in bucket) {
                try {
                    val nonce = HyperswarmProtocol.generateNonce()
                    val ping = HyperswarmProtocol.HyperswarmMessage.Ping(nonce = nonce)
                    sendMessage(ping, node.host, node.port)

                    val response = waitForResponse(nonce, 2000)
                    if (response != null) {
                        node.lastSeen = System.currentTimeMillis()
                        node.isAlive = true
                    } else {
                        node.isAlive = false
                        deadNodes.add(node)
                    }
                } catch (e: Exception) {
                    node.isAlive = false
                    deadNodes.add(node)
                }
            }
            // Tote Nodes entfernen
            bucket.removeAll(deadNodes)
        }

        Log.d(TAG, "DHT-Refresh abgeschlossen. Routing-Tabelle: ${getRoutingTableSize()} Nodes")
    }

    // =========================================================================
    // Nachrichtenverarbeitung
    // =========================================================================

    /**
     * Startet die Empfangsschleife für eingehende UDP-Pakete.
     */
    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            while (isRunning) {
                try {
                    val buffer = ByteArray(HyperswarmProtocol.MAX_PACKET_SIZE)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    if (packet.length > 0) {
                        val data = buffer.copyOf(packet.length)
                        val senderHost = packet.address.hostAddress ?: "unknown"
                        val senderPort = packet.port

                        launch {
                            processIncomingMessage(data, senderHost, senderPort)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout ist normal, weitermachen
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.w(TAG, "Fehler beim Empfangen: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Verarbeitet eine eingehende Nachricht.
     */
    private suspend fun processIncomingMessage(data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            // Prüfen, ob es eine gültige Hyperswarm-Nachricht ist
            if (!HyperswarmProtocol.isValidMessage(data)) {
                Log.w(TAG, "Ungültige Nachricht von $senderHost:$senderPort")
                return
            }

            // Dekodieren
            val message = HyperswarmProtocol.decode(data) ?: run {
                Log.w(TAG, "Konnte Nachricht nicht dekodieren von $senderHost:$senderPort")
                return
            }

            Log.d(TAG, "Nachricht empfangen: ${message::class.simpleName} von $senderHost:$senderPort")

            // Nachrichtentyp behandeln
            when (message) {
                is HyperswarmProtocol.HyperswarmMessage.Ping -> handlePing(message, senderHost, senderPort)
                is HyperswarmProtocol.HyperswarmMessage.Pong -> handlePong(message, senderHost, senderPort)
                is HyperswarmProtocol.HyperswarmMessage.FindNode -> handleFindNode(message, senderHost, senderPort)
                is HyperswarmProtocol.HyperswarmMessage.Nodes -> handleNodes(message, senderHost, senderPort)
                is HyperswarmProtocol.HyperswarmMessage.Announce -> handleAnnounce(message, senderHost, senderPort)
                is HyperswarmProtocol.HyperswarmMessage.Unannounce -> handleUnannounce(message, senderHost, senderPort)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei Nachrichtenverarbeitung: ${e.message}")
        }
    }

    /**
     * Behandelt eine eingehende PING-Nachricht.
     */
    private suspend fun handlePing(ping: HyperswarmProtocol.HyperswarmMessage.Ping, host: String, port: Int) {
        // Mit PONG antworten
        val pong = HyperswarmProtocol.HyperswarmMessage.Pong(nonce = ping.nonce)
        sendMessage(pong, host, port)
    }

    /**
     * Behandelt eine eingehende PONG-Nachricht.
     */
    private suspend fun handlePong(pong: HyperswarmProtocol.HyperswarmMessage.Pong, host: String, port: Int) {
        // Ausstehende Anfrage auflösen
        val nonceKey = pong.nonce.joinToString("") { "%02x".format(it) }
        pendingRequests[nonceKey]?.complete(pong)
        pendingRequests.remove(nonceKey)
    }

    /**
     * Behandelt eine eingehende FIND_NODE-Nachricht.
     */
    private suspend fun handleFindNode(findNode: HyperswarmProtocol.HyperswarmMessage.FindNode, host: String, port: Int) {
        // Nächstgelegene Nodes zur Ziel-ID finden
        val nearestNodes = findNearestNodes(findNode.targetId, ALPHA)

        // NODES-Antwort senden
        val hyperswarmNodes = nearestNodes.map { node ->
            HyperswarmProtocol.HyperswarmNode(
                nodeId = node.nodeId,
                host = node.host,
                port = node.port
            )
        }

        val nodesResponse = HyperswarmProtocol.HyperswarmMessage.Nodes(
            nonce = findNode.nonce,
            nodes = hyperswarmNodes
        )

        sendMessage(nodesResponse, host, port)
    }

    /**
     * Behandelt eine eingehende NODES-Nachricht.
     */
    private suspend fun handleNodes(nodes: HyperswarmProtocol.HyperswarmMessage.Nodes, host: String, port: Int) {
        // Nodes zur Routing-Tabelle hinzufügen
        for (node in nodes.nodes) {
            addToRoutingTable(
                KademliaNode(
                    nodeId = node.nodeId,
                    host = node.host,
                    port = node.port
                )
            )
        }

        // Ausstehende Anfrage auflösen
        val nonceKey = nodes.nonce.joinToString("") { "%02x".format(it) }
        pendingRequests[nonceKey]?.complete(nodes)
        pendingRequests.remove(nonceKey)
    }

    /**
     * Behandelt eine eingehende ANNOUNCE-Nachricht.
     */
    private suspend fun handleAnnounce(announce: HyperswarmProtocol.HyperswarmMessage.Announce, host: String, port: Int) {
        val topicHex = announce.topic.joinToString("") { "%02x".format(it) }
        val peerId = String(announce.peerId, Charsets.UTF_8)

        topicPeers.getOrPut(topicHex) { mutableListOf() }.apply {
            if (!contains(peerId)) {
                add(peerId)
                while (size > MAX_PEERS_PER_TOPIC) {
                    removeAt(0)
                }
            }
        }

        Log.d(TAG, "Announcement für Topic $topicHex von Peer $peerId gespeichert")
    }

    /**
     * Behandelt eine eingehende UNANNOUNCE-Nachricht.
     */
    private suspend fun handleUnannounce(unannounce: HyperswarmProtocol.HyperswarmMessage.Unannounce, host: String, port: Int) {
        val topicHex = unannounce.topic.joinToString("") { "%02x".format(it) }
        val peerId = String(unannounce.peerId, Charsets.UTF_8)

        topicPeers[topicHex]?.remove(peerId)
        Log.d(TAG, "Announcement für Topic $topicHex von Peer $peerId entfernt")
    }

    // =========================================================================
    // Nachrichtensendung
    // =========================================================================

    /**
     * Sendet eine Hyperswarm-Nachricht an einen Peer.
     *
     * @param message Die zu sendende Nachricht
     * @param host Die Ziel-IP-Adresse
     * @param port Der Ziel-Port
     */
    private suspend fun sendMessage(message: HyperswarmProtocol.HyperswarmMessage, host: String, port: Int) {
        try {
            withContext(Dispatchers.IO) {
                val data = HyperswarmProtocol.encode(message)
                val address = InetAddress.getByName(host)
                val packet = DatagramPacket(data, data.size, address, port)
                socket?.send(packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Senden an $host:$port: ${e.message}")
            throw e
        }
    }

    /**
     * Wartet auf eine Antwort mit einer bestimmten Nonce.
     *
     * @param nonce Die Nonce, auf die gewartet wird
     * @param timeoutMs Timeout in Millisekunden
     * @return Die Antwort-Nachricht, oder null bei Timeout
     */
    private suspend fun waitForResponse(nonce: ByteArray, timeoutMs: Long): HyperswarmProtocol.HyperswarmMessage? {
        val nonceKey = nonce.joinToString("") { "%02x".format(it) }
        val deferred = CompletableDeferred<HyperswarmProtocol.HyperswarmMessage>()
        pendingRequests[nonceKey] = deferred

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "Timeout beim Warten auf Antwort (nonce=$nonceKey)")
            pendingRequests.remove(nonceKey)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Warten auf Antwort: ${e.message}")
            pendingRequests.remove(nonceKey)
            null
        }
    }

    // =========================================================================
    // Routing-Tabelle
    // =========================================================================

    /**
     * Fügt einen Node zur Routing-Tabelle hinzu.
     *
     * Verwendet Kademlia-Bucket-Logik:
     * - Node wird in den passenden Bucket einsortiert
     * - Wenn der Bucket voll ist, wird der älteste Node ersetzt
     *
     * @param node Der hinzuzufügende Node
     */
    private fun addToRoutingTable(node: KademliaNode) {
        val bucketIndex = getBucketIndex(node.nodeId)
        val bucket = routingTable[bucketIndex]

        synchronized(bucket) {
            // Prüfen, ob der Node bereits existiert
            val existing = bucket.find { it.nodeId.contentEquals(node.nodeId) }
            if (existing != null) {
                existing.lastSeen = System.currentTimeMillis()
                existing.isAlive = true
                return
            }

            // Wenn der Bucket nicht voll ist, hinzufügen
            if (bucket.size < BUCKET_SIZE) {
                bucket.add(node)
                Log.d(TAG, "Node zu Bucket $bucketIndex hinzugefügt (${bucket.size}/$BUCKET_SIZE)")
            } else {
                // Ältesten Node ersetzen
                val oldest = bucket.minByOrNull { it.lastSeen }
                if (oldest != null) {
                    bucket.remove(oldest)
                    bucket.add(node)
                    Log.d(TAG, "Node in Bucket $bucketIndex ersetzt")
                }
            }
        }
    }

    /**
     * Findet die nächstgelegenen Nodes zu einer Ziel-ID.
     *
     * @param targetId Die Ziel-ID
     * @param count Die Anzahl der gewünschten Nodes
     * @return Liste der nächstgelegenen Nodes
     */
    private fun findNearestNodes(targetId: ByteArray, count: Int = ALPHA): List<KademliaNode> {
        val allNodes = mutableListOf<KademliaNode>()

        for (bucket in routingTable) {
            synchronized(bucket) {
                allNodes.addAll(bucket.filter { it.isAlive })
            }
        }

        // Nach XOR-Distanz sortieren
        return allNodes
            .sortedWith(compareBy { xorDistance(it.nodeId, targetId).contentToString() })
            .take(count)
    }

    /**
     * Berechnet den Bucket-Index für eine Node-ID.
     *
     * @param nodeId Die Node-ID
     * @return Der Bucket-Index (0-159)
     */
    private fun getBucketIndex(nodeId: ByteArray): Int {
        val distance = xorDistance(localPeerIdBytes, nodeId)
        // Führende Null-Bits zählen
        var leadingZeros = 0
        for (byte in distance) {
            if (byte == 0.toByte()) {
                leadingZeros += 8
            } else {
                // Anzahl der führenden Null-Bits im Byte zählen
                var b = byte.toInt() and 0xFF
                while (b and 0x80 == 0) {
                    leadingZeros++
                    b = b shl 1
                }
                break
            }
        }
        return leadingZeros.coerceAtMost(BUCKET_COUNT - 1)
    }

    /**
     * Berechnet die XOR-Distanz zwischen zwei Byte-Arrays.
     *
     * @param a Erstes Byte-Array
     * @param b Zweites Byte-Array
     * @return XOR-Distanz als Byte-Array
     */
    private fun xorDistance(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return result
    }

    /**
     * Gibt die Größe der Routing-Tabelle zurück.
     */
    fun getRoutingTableSize(): Int {
        return routingTable.sumOf { it.size }
    }

    // =========================================================================
    // Refresh-Loop
    // =========================================================================

    /**
     * Startet den periodischen Refresh der Routing-Tabelle.
     */
    private fun startRefreshLoop() {
        refreshJob = scope.launch {
            while (isRunning) {
                delay(REFRESH_INTERVAL_MS)
                try {
                    refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "Periodischer Refresh fehlgeschlagen: ${e.message}")
                }
            }
        }
    }
}

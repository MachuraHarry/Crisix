package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kademlia-ähnlicher DHT-Knoten für dezentrale Peer-Findung.
 *
 * ## Funktionsweise
 * Der DHT-Knoten implementiert ein verteiltes Hash-Tabellen-Protokoll,
 * das es Peers ermöglicht, sich gegenseitig zu finden, ohne einen
 * zentralen Server zu benötigen.
 *
 * ### Kernkonzepte
 * - **Peer-ID**: SHA-256-Hash des öffentlichen Schlüssels (256 Bit)
 * - **Distanz**: XOR-Distanz zwischen zwei Peer-IDs
 * - **K-Buckets**: Listen von bekannten Peers, organisiert nach Distanz
 * - **Kademlia RPC**: PING, STORE, FIND_NODE, FIND_VALUE
 *
 * ### Ablauf
 * 1. Knoten startet und verbindet sich mit Bootstrap-Knoten
 * 2. Knoten baut seine Routing-Tabelle auf (K-Buckets)
 * 3. Periodisch: Knoten refresht seine K-Buckets
 * 4. Bei Peer-Suche: FIND_NODE-RPC an die nächstgelegenen Knoten
 *
 * ## Verwendung
 * ```kotlin
 * val dht = DhtNode(localPeerId, localPort)
 * dht.bootstrap(bootstrapNodes)
 * dht.announce() // Eigene Adresse in der DHT registrieren
 * val peer = dht.findPeer(targetPeerId) // Peer suchen
 * ```
 */
class DhtNode(
    private val localPeerId: String,
    private val localPort: Int,
    private val localHost: String = "0.0.0.0"
) {
    companion object {
        private const val TAG = "DhtNode"

        /** Kademlia-Parameter: Anzahl der Bits in der Peer-ID */
        private const val ID_BITS = 256

        /** Kademlia-Parameter: Größe der K-Buckets (max Peers pro Bucket) */
        private const val K = 20

        /** Kademlia-Parameter: Replikationsfaktor */
        private const val ALPHA = 3

        /** UDP-Port für DHT-Kommunikation (Hyperswarm-Standard: 49737) */
        const val DHT_PORT = 49737

        /** Timeout für DHT-Anfragen in Millisekunden */
        private const val RPC_TIMEOUT_MS = 3000L

        /** Intervall für Bucket-Refresh in Millisekunden */
        private const val REFRESH_INTERVAL_MS = 60_000L

        /** Intervall für Peer-Announce in Millisekunden */
        private const val ANNOUNCE_INTERVAL_MS = 300_000L

        /** Maximale Anzahl von Peers in der Antwort */
        private const val MAX_PEERS_RESPONSE = 20

        /** RPC-Typen als Int für DataOutputStream.writeByte() */
        private const val RPC_PING: Int = 0x01
        private const val RPC_PONG: Int = 0x02
        private const val RPC_FIND_NODE: Int = 0x03
        private const val RPC_FIND_NODE_RESPONSE: Int = 0x04
        private const val RPC_ANNOUNCE: Int = 0x05
        private const val RPC_ANNOUNCE_RESPONSE: Int = 0x06
        private const val RPC_FIND_PEER: Int = 0x07
        private const val RPC_FIND_PEER_RESPONSE: Int = 0x08
    }

    // =========================================================================
    // Datenstrukturen
    // =========================================================================

    /**
     * Repräsentiert einen bekannten Peer in der DHT.
     */
    data class DhtPeer(
        val peerId: String,
        val host: String,
        val port: Int,
        val udpPort: Int = DHT_PORT,
        var lastSeen: Long = System.currentTimeMillis(),
        var isOnline: Boolean = true
    )

    /**
     * K-Bucket: Liste von Peers mit ähnlicher XOR-Distanz.
     */
    private class KBucket(private val maxSize: Int = K) {
        private val peers = CopyOnWriteArrayList<DhtPeer>()

        @Synchronized
        fun add(peer: DhtPeer): Boolean {
            // Prüfen, ob Peer bereits existiert -> aktualisieren
            val existing = peers.find { it.peerId == peer.peerId }
            if (existing != null) {
                existing.lastSeen = System.currentTimeMillis()
                existing.isOnline = true
                // Nach hinten verschieben (zuletzt gesehen)
                peers.remove(existing)
                peers.add(existing)
                return true
            }

            // Neuen Peer hinzufügen, wenn Platz ist
            if (peers.size < maxSize) {
                peers.add(peer)
                return true
            }

            // Bucket voll -> ältesten Peer evicten
            val oldest = peers.minByOrNull { it.lastSeen }
            if (oldest != null && oldest.lastSeen < System.currentTimeMillis() - 3600_000) {
                peers.remove(oldest)
                peers.add(peer)
                return true
            }

            return false // Bucket voll, kein Platz
        }

        fun getPeers(): List<DhtPeer> = peers.toList()

        fun getAlivePeers(): List<DhtPeer> = peers.filter { it.isOnline }

        fun remove(peerId: String) {
            peers.removeAll { it.peerId == peerId }
        }

        fun size(): Int = peers.size

        fun isFull(): Boolean = peers.size >= maxSize
    }

    // =========================================================================
    // Zustand
    // =========================================================================

    /** K-Buckets: Index = gemeinsames Präfix-Bit (0..ID_BITS) */
    private val buckets = Array(ID_BITS) { KBucket() }

    /** UDP-Socket für DHT-Kommunikation */
    private var udpSocket: DatagramSocket? = null

    /** Coroutine-Scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Job für den UDP-Listener */
    private var listenerJob: Job? = null

    /** Job für Bucket-Refresh */
    private var refreshJob: Job? = null

    /** Job für Peer-Announce */
    private var announceJob: Job? = null

    /** Laufzeitstatus */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Entdeckte Peers */
    private val _discoveredPeers = MutableStateFlow<List<DhtPeer>>(emptyList())
    val discoveredPeers: Flow<List<DhtPeer>> = _discoveredPeers.asStateFlow()

    /** Ausstehende RPC-Anfragen: rpcId -> CompletableDeferred */
    private val pendingRpcs = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    /** Sequenznummer für RPC-IDs */
    private var rpcSequence = 0L

    // =========================================================================
    // Initialisierung
    // =========================================================================

    /**
     * Startet den DHT-Knoten.
     *
     * @param bootstrapNodes Liste von Bootstrap-Knoten zum Beitritt ins Netzwerk
     */
    suspend fun start(bootstrapNodes: List<DhtPeer> = emptyList()) {
        if (isRunning) return

        try {
            // UDP-Socket erstellen (Port 0 = OS wählt automatisch, vermeidet EADDRINUSE)
            udpSocket = DatagramSocket(0)
            udpSocket!!.broadcast = true
            udpSocket!!.soTimeout = 5000

            val actualPort = udpSocket!!.localPort
            Log.i(TAG, "DHT-Knoten gestartet auf Port $actualPort (Peer: $localPeerId)")

            // UDP-Listener starten
            listenerJob = scope.launch {
                listenForMessages()
            }

            // Bucket-Refresh starten
            refreshJob = scope.launch {
                periodicRefresh()
            }

            // Peer-Announce starten
            announceJob = scope.launch {
                periodicAnnounce()
            }

            isRunning = true

            // Mit Bootstrap-Knoten verbinden
            if (bootstrapNodes.isNotEmpty()) {
                Log.i(TAG, "Verbinde mit ${bootstrapNodes.size} Bootstrap-Knoten...")
                for (bootstrap in bootstrapNodes) {
                    try {
                        ping(bootstrap)
                        Log.d(TAG, "Bootstrap-Knoten ${bootstrap.peerId} erreicht")
                    } catch (e: Exception) {
                        Log.w(TAG, "Bootstrap-Knoten ${bootstrap.peerId} nicht erreichbar: ${e.message}")
                    }
                }
            }

            // Eigene Adresse in der DHT bekannt machen
            announce()

            Log.i(TAG, "DHT-Knoten gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten des DHT-Knotens: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    // =========================================================================
    // UDP-Kommunikation
    // =========================================================================

    /**
     * Lauscht auf eingehende UDP-Nachrichten.
     */
    private suspend fun listenForMessages() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(4096)

        try {
            while (isRunning && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val data = buffer.copyOf(packet.length)
                    val senderAddr = packet.address.hostAddress ?: "unknown"
                    val senderPort = packet.port

                    // Nachricht verarbeiten
                    scope.launch {
                        handleMessage(data, senderAddr, senderPort)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout ist normal, weitermachen
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Fehler im UDP-Listener: ${e.message}", e)
            }
        }
    }

    /**
     * Verarbeitet eine eingehende DHT-Nachricht.
     */
    private suspend fun handleMessage(data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    val rpcId = dis.readUTF()
                    val rpcType = dis.readByte()

                    when (rpcType.toInt() and 0xFF) {
                        RPC_PING -> handlePing(rpcId, senderHost, senderPort)
                        RPC_PONG -> handlePong(rpcId, data, senderHost, senderPort)
                        RPC_FIND_NODE -> handleFindNode(rpcId, data, senderHost, senderPort)
                        RPC_FIND_NODE_RESPONSE -> handleFindNodeResponse(rpcId, data)
                        RPC_ANNOUNCE -> handleAnnounce(rpcId, data, senderHost, senderPort)
                        RPC_ANNOUNCE_RESPONSE -> handleAnnounceResponse(rpcId, data)
                        RPC_FIND_PEER -> handleFindPeer(rpcId, data, senderHost, senderPort)
                        RPC_FIND_PEER_RESPONSE -> handleFindPeerResponse(rpcId, data)
                        else -> Log.w(TAG, "Unbekannter RPC-Typ: $rpcType")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Verarbeiten der Nachricht von $senderHost:$senderPort: ${e.message}")
        }
    }

    /**
     * Sendet eine UDP-Nachricht an einen Peer.
     */
    private suspend fun sendMessage(host: String, port: Int, data: ByteArray) {
        try {
            val socket = udpSocket ?: return
            val address = InetAddress.getByName(host)
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Senden an $host:$port: ${e.message}")
        }
    }

    // =========================================================================
    // RPC-Handler
    // =========================================================================

    /**
     * PING: Prüft, ob ein Peer erreichbar ist.
     */
    private suspend fun handlePing(rpcId: String, senderHost: String, senderPort: Int) {
        val response = ByteArrayOutputStream()
        DataOutputStream(response).use { dos ->
            dos.writeUTF(rpcId)
            dos.writeByte(RPC_PONG)
            dos.writeUTF(localPeerId)
            dos.writeInt(localPort)
        }
        sendMessage(senderHost, senderPort, response.toByteArray())
    }

    /**
     * PONG: Antwort auf einen Ping.
     */
    private suspend fun handlePong(rpcId: String, data: ByteArray, senderHost: String, senderPort: Int) {
        // Ausstehende Anfrage auflösen
        pendingRpcs[rpcId]?.complete(data)
        pendingRpcs.remove(rpcId)

        // Peer zu K-Bucket hinzufügen
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    dis.readUTF() // rpcId überspringen
                    dis.readByte() // rpcType überspringen
                    val peerId = dis.readUTF()
                    val tcpPort = dis.readInt()

                    addPeer(DhtPeer(peerId, senderHost, tcpPort, senderPort))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Verarbeiten von PONG: ${e.message}")
        }
    }

    /**
     * FIND_NODE: Sucht nach den nächstgelegenen Peers zu einer Ziel-ID.
     */
    private suspend fun handleFindNode(rpcId: String, data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    dis.readUTF() // rpcId überspringen
                    dis.readByte() // rpcType überspringen
                    val targetId = dis.readUTF()

                    // Nächstgelegene Peers finden
                    val closestPeers = findClosestPeers(targetId, MAX_PEERS_RESPONSE)

                    // Antwort senden
                    val response = ByteArrayOutputStream()
                    DataOutputStream(response).use { dos ->
                        dos.writeUTF(rpcId)
                        dos.writeByte(RPC_FIND_NODE_RESPONSE)
                        dos.writeInt(closestPeers.size)
                        for (peer in closestPeers) {
                            dos.writeUTF(peer.peerId)
                            dos.writeUTF(peer.host)
                            dos.writeInt(peer.port)
                            dos.writeInt(peer.udpPort)
                        }
                    }
                    sendMessage(senderHost, senderPort, response.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei FIND_NODE: ${e.message}")
        }
    }

    /**
     * FIND_NODE_RESPONSE: Antwort auf eine FIND_NODE-Anfrage.
     */
    private suspend fun handleFindNodeResponse(rpcId: String, data: ByteArray) {
        pendingRpcs[rpcId]?.complete(data)
        pendingRpcs.remove(rpcId)

        // Gefundene Peers zu K-Buckets hinzufügen
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    dis.readUTF() // rpcId überspringen
                    dis.readByte() // rpcType überspringen
                    val count = dis.readInt()
                    for (i in 0 until count) {
                        val peerId = dis.readUTF()
                        val host = dis.readUTF()
                        val port = dis.readInt()
                        val udpPort = dis.readInt()
                        addPeer(DhtPeer(peerId, host, port, udpPort))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Verarbeiten von FIND_NODE_RESPONSE: ${e.message}")
        }
    }

    /**
     * ANNOUNCE: Registriert einen Peer in der DHT.
     */
    private suspend fun handleAnnounce(rpcId: String, data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    dis.readUTF() // rpcId überspringen
                    dis.readByte() // rpcType überspringen
                    val peerId = dis.readUTF()
                    val tcpPort = dis.readInt()

                    // Peer zu K-Bucket hinzufügen
                    addPeer(DhtPeer(peerId, senderHost, tcpPort, senderPort))

                    // Bestätigung senden
                    val response = ByteArrayOutputStream()
                    DataOutputStream(response).use { dos ->
                        dos.writeUTF(rpcId)
                        dos.writeByte(RPC_ANNOUNCE_RESPONSE)
                        dos.writeBoolean(true)
                    }
                    sendMessage(senderHost, senderPort, response.toByteArray())

                    Log.d(TAG, "Peer $peerId ($senderHost:$tcpPort) registriert")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei ANNOUNCE: ${e.message}")
        }
    }

    /**
     * ANNOUNCE_RESPONSE: Bestätigung für einen Announce.
     */
    private suspend fun handleAnnounceResponse(rpcId: String, data: ByteArray) {
        pendingRpcs[rpcId]?.complete(data)
        pendingRpcs.remove(rpcId)
    }

    /**
     * FIND_PEER: Sucht nach einem bestimmten Peer.
     */
    private suspend fun handleFindPeer(rpcId: String, data: ByteArray, senderHost: String, senderPort: Int) {
        try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    dis.readUTF() // rpcId überspringen
                    dis.readByte() // rpcType überspringen
                    val targetPeerId = dis.readUTF()

                    // Peer in lokaler Routing-Tabelle suchen
                    val peer = findPeerInBuckets(targetPeerId)

                    val response = ByteArrayOutputStream()
                    DataOutputStream(response).use { dos ->
                        dos.writeUTF(rpcId)
                        dos.writeByte(RPC_FIND_PEER_RESPONSE)
                        if (peer != null) {
                            dos.writeBoolean(true)
                            dos.writeUTF(peer.peerId)
                            dos.writeUTF(peer.host)
                            dos.writeInt(peer.port)
                            dos.writeInt(peer.udpPort)
                        } else {
                            dos.writeBoolean(false)
                        }
                    }
                    sendMessage(senderHost, senderPort, response.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei FIND_PEER: ${e.message}")
        }
    }

    /**
     * FIND_PEER_RESPONSE: Antwort auf eine FIND_PEER-Anfrage.
     */
    private suspend fun handleFindPeerResponse(rpcId: String, data: ByteArray) {
        pendingRpcs[rpcId]?.complete(data)
        pendingRpcs.remove(rpcId)
    }

    // =========================================================================
    // Öffentliche API
    // =========================================================================

    /**
     * Sendet einen Ping an einen Peer.
     *
     * @param peer Der Peer, der angepingt werden soll
     * @return true wenn der Peer geantwortet hat
     */
    suspend fun ping(peer: DhtPeer): Boolean {
        return try {
            val rpcId = generateRpcId()
            val request = ByteArrayOutputStream()
            DataOutputStream(request).use { dos ->
                dos.writeUTF(rpcId)
                dos.writeByte(RPC_PING)
            }

            val deferred = CompletableDeferred<ByteArray>()
            pendingRpcs[rpcId] = deferred

            sendMessage(peer.host, peer.udpPort, request.toByteArray())

            // Auf Antwort warten
            withTimeout(RPC_TIMEOUT_MS) {
                deferred.await()
            }

            // Peer als online markieren
            addPeer(peer.copy(lastSeen = System.currentTimeMillis(), isOnline = true))
            true
        } catch (e: Exception) {
            // Peer als offline markieren
            markPeerOffline(peer.peerId)
            false
        }
    }

    /**
     * Sucht nach einem Peer in der DHT.
     *
     * @param targetPeerId Die Peer-ID des gesuchten Peers
     * @return Die Kontaktinformationen des Peers, oder null
     */
    suspend fun findPeer(targetPeerId: String): DhtPeer? {
        Log.d(TAG, "Suche Peer: $targetPeerId")

        // 1. Zuerst in lokalen K-Buckets suchen
        val localPeer = findPeerInBuckets(targetPeerId)
        if (localPeer != null) {
            Log.d(TAG, "Peer $targetPeerId lokal gefunden")
            return localPeer
        }

        // 2. Iterative Suche über das Netzwerk
        val closestPeers = findClosestPeers(targetPeerId, ALPHA)
        val queried = mutableSetOf<String>()
        val toQuery = closestPeers.toMutableList()

        while (toQuery.isNotEmpty() && queried.size < K) {
            val current = toQuery.removeFirst()
            if (current.peerId in queried) continue
            queried.add(current.peerId)

            try {
                val rpcId = generateRpcId()
                val request = ByteArrayOutputStream()
                DataOutputStream(request).use { dos ->
                    dos.writeUTF(rpcId)
                    dos.writeByte(RPC_FIND_PEER)
                    dos.writeUTF(targetPeerId)
                }

                val deferred = CompletableDeferred<ByteArray>()
                pendingRpcs[rpcId] = deferred

                sendMessage(current.host, current.udpPort, request.toByteArray())

                val response = withTimeout(RPC_TIMEOUT_MS) { deferred.await() }

                // Antwort parsen
                ByteArrayInputStream(response).use { bais ->
                    DataInputStream(bais).use { dis ->
                        dis.readUTF() // rpcId
                        dis.readByte() // rpcType
                        val found = dis.readBoolean()
                        if (found) {
                            val peerId = dis.readUTF()
                            val host = dis.readUTF()
                            val port = dis.readInt()
                            val udpPort = dis.readInt()
                            val peer = DhtPeer(peerId, host, port, udpPort)
                            addPeer(peer)
                            Log.i(TAG, "Peer $targetPeerId gefunden via $host:$port")
                            return peer
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Anfrage an ${current.peerId} fehlgeschlagen: ${e.message}")
            }
        }

        Log.w(TAG, "Peer $targetPeerId nicht in der DHT gefunden")
        return null
    }

    /**
     * Findet die nächstgelegenen Peers zu einer Ziel-ID.
     *
     * @param targetId Die Ziel-Peer-ID
     * @param count Maximale Anzahl von Peers
     * @return Liste der nächstgelegenen Peers
     */
    suspend fun findClosestPeers(targetId: String, count: Int = K): List<DhtPeer> {
        val allPeers = getAllPeers()
        return allPeers
            .filter { it.isOnline }
            .sortedBy { xorDistanceAsHex(it.peerId, targetId) }
            .take(count)
    }

    /**
     * Gibt die eigene Peer-ID zurück.
     */
    fun getLocalPeerId(): String = localPeerId

    /**
     * Gibt alle bekannten Peers zurück.
     */
    fun getAllPeers(): List<DhtPeer> {
        return buckets.flatMap { it.getPeers() }.distinctBy { it.peerId }
    }

    /**
     * Gibt die Anzahl der bekannten Peers zurück.
     */
    fun getPeerCount(): Int = getAllPeers().size

    // =========================================================================
    // Hilfsfunktionen
    // =========================================================================

    /**
     * Fügt einen Peer zum passenden K-Bucket hinzu.
     */
    private fun addPeer(peer: DhtPeer) {
        if (peer.peerId == localPeerId) return // Sich selbst nicht hinzufügen

        val distance = xorDistance(localPeerId, peer.peerId)
        val bucketIndex = leadingZeroBits(distance)

        if (bucketIndex in buckets.indices) {
            buckets[bucketIndex].add(peer)
            updateDiscoveredPeers()
        }
    }

    /**
     * Markiert einen Peer als offline.
     */
    private fun markPeerOffline(peerId: String) {
        for (bucket in buckets) {
            bucket.getPeers().find { it.peerId == peerId }?.let { peer ->
                peer.isOnline = false
            }
        }
    }

    /**
     * Sucht einen Peer in den lokalen K-Buckets.
     */
    private fun findPeerInBuckets(peerId: String): DhtPeer? {
        return buckets.flatMap { it.getPeers() }.find { it.peerId == peerId }
    }

    /**
     * Aktualisiert den discoveredPeers-Flow.
     */
    private fun updateDiscoveredPeers() {
        _discoveredPeers.value = getAllPeers().filter { it.isOnline }
    }

    /**
     * Berechnet die XOR-Distanz zwischen zwei Peer-IDs.
     * Verwendet SHA-256-Hashes der IDs für konsistente Ergebnisse.
     */
    private fun xorDistance(id1: String, id2: String): ByteArray {
        val hash1 = MessageDigest.getInstance("SHA-256").digest(id1.toByteArray())
        val hash2 = MessageDigest.getInstance("SHA-256").digest(id2.toByteArray())
        return ByteArray(32) { (hash1[it].toInt() xor hash2[it].toInt()).toByte() }
    }

    /**
     * Berechnet die XOR-Distanz als Hex-String (vergleichbar für sortedBy).
     */
    private fun xorDistanceAsHex(id1: String, id2: String): String {
        val distance = xorDistance(id1, id2)
        return distance.joinToString("") { "%02x".format(it) }
    }

    /**
     * Zählt die führenden Null-Bits in einem Byte-Array.
     */
    private fun leadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (b in bytes) {
            if (b == 0.toByte()) {
                count += 8
            } else {
                count += (0..7).firstOrNull { (b.toInt() and (0x80 shr it)) != 0 } ?: 8
                break
            }
        }
        return count.coerceAtMost(ID_BITS - 1)
    }

    /**
     * Generiert eine eindeutige RPC-ID.
     */
    private fun generateRpcId(): String {
        return "${localPeerId}_${++rpcSequence}_${System.nanoTime()}"
    }

    // =========================================================================
    // Periodische Aufgaben
    // =========================================================================

    /**
     * Periodischer Refresh der K-Buckets.
     */
    private suspend fun periodicRefresh() {
        while (isRunning) {
            delay(REFRESH_INTERVAL_MS)

            try {
                // Zufällige Buckets refreshen
                val allPeers = getAllPeers()
                if (allPeers.isNotEmpty()) {
                    val sample = allPeers.shuffled().take(ALPHA)
                    for (peer in sample) {
                        try {
                            ping(peer)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fehler beim Bucket-Refresh: ${e.message}")
            }
        }
    }

    /**
     * Periodischer Peer-Announce.
     */
    private suspend fun periodicAnnounce() {
        while (isRunning) {
            delay(ANNOUNCE_INTERVAL_MS)
            try {
                announce()
            } catch (e: Exception) {
                Log.w(TAG, "Fehler beim periodischen Announce: ${e.message}")
            }
        }
    }

    /**
     * Announced die eigene Adresse in der DHT.
     */
    suspend fun announce() {
        val allPeers = getAllPeers()
        if (allPeers.isEmpty()) {
            Log.d(TAG, "Keine Peers zum Announcen (noch nicht im Netzwerk)")
            return
        }

        // Die nächstgelegenen Peers über uns informieren
        val closestPeers = findClosestPeers(localPeerId, ALPHA)

        for (peer in closestPeers) {
            try {
                val rpcId = generateRpcId()
                val request = ByteArrayOutputStream()
                DataOutputStream(request).use { dos ->
                    dos.writeUTF(rpcId)
                    dos.writeByte(RPC_ANNOUNCE)
                    dos.writeUTF(localPeerId)
                    dos.writeInt(localPort)
                }

                val deferred = CompletableDeferred<ByteArray>()
                pendingRpcs[rpcId] = deferred

                sendMessage(peer.host, peer.udpPort, request.toByteArray())

                withTimeout(RPC_TIMEOUT_MS) {
                    deferred.await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Announce an ${peer.peerId} fehlgeschlagen: ${e.message}")
            }
        }

        Log.d(TAG, "Announce abgeschlossen (${closestPeers.size} Peers benachrichtigt)")
    }

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    /**
     * Stoppt den DHT-Knoten.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe DHT-Knoten")

        isRunning = false

        listenerJob?.cancel()
        refreshJob?.cancel()
        announceJob?.cancel()
        listenerJob = null
        refreshJob = null
        announceJob = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Schließen des UDP-Sockets: ${e.message}")
        }

        udpSocket = null
        pendingRpcs.forEach { (_, deferred) ->
            deferred.completeExceptionally(CancellationException("DHT gestoppt"))
        }
        pendingRpcs.clear()

        Log.i(TAG, "DHT-Knoten gestoppt")
    }
}

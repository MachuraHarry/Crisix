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

class PeerDiscovery {

    private val TAG = "PeerDiscovery"

    /** Intervall für NAT-Traversal-Prüfung (5 Minuten) */
    private val NAT_CHECK_INTERVAL_MS = 300_000L

    private val CRISIX_SERVICE = "_crisix._tcp.local"
    private val MDNS_PORT = 5353
    private val MDNS_ADDR = InetAddress.getByName("224.0.0.251")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var dhtNode: MainlineDhtNode? = null
        private set

    private var natTraversal: NatTraversal? = null

    private var natCheckJob: Job? = null

    private var punchListenerJob: Job? = null

    private val _discoveredPeers = MutableStateFlow<List<RemotePeerInfo>>(emptyList())
    val discoveredPeers: Flow<List<RemotePeerInfo>> = _discoveredPeers.asStateFlow()

    @Volatile
    var isDhtAvailable: Boolean = false
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var publicAddress: NatTraversal.PublicAddress? = null
        private set

    fun start(localPeerId: String, localPublicKey: ByteArray, localPort: Int) {
        if (isRunning) {
            Log.w(TAG, "PeerDiscovery läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte Peer-Discovery für Peer: $localPeerId auf Port $localPort")

        dhtNode = MainlineDhtNode(localPeerId, localPort, DhtConfig.GLOBAL_TOPIC)
        scope.launch {
            try {
                Log.i(TAG, "Versuche Verbindung zu Mainline-DHT-Bootstrap-Knoten...")
                dhtNode?.start()
                isDhtAvailable = true
                Log.i(TAG, "✅ Mainline-DHT-Knoten gestartet mit ${dhtNode?.knownNodesCount ?: 0} bekannten Knoten")
                Log.i(TAG, "🌍 Globales Topic: ${DhtConfig.GLOBAL_TOPIC_HEX.take(16)}...")

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

        natTraversal = NatTraversal(localPort)
        scope.launch {
            try {
                val addr = natTraversal?.discoverPublicAddress()
                if (addr != null) {
                    publicAddress = addr
                    Log.i(TAG, "Öffentliche Adresse: ${addr.host}:${addr.port}")

                    punchListenerJob = natTraversal?.startPunchListener { host, port ->
                        Log.d(TAG, "Hole Punch empfangen von $host:$port")
                        val peerInfo = RemotePeerInfo(
                            peerId = "punch-$host-$port",
                            host = host,
                            port = port,
                            isConnected = false
                        )
                        addPeerToList(peerInfo)
                    }

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

    suspend fun findPeer(targetPeerId: String): RemotePeerInfo? {
        Log.d(TAG, "Suche Peer in DHT: $targetPeerId")

        return try {
            val dhtPeer = dhtNode?.findPeer(targetPeerId)
            if (dhtPeer != null) {
                Log.i(TAG, "Peer $targetPeerId gefunden: ${dhtPeer.host}:${dhtPeer.port}")

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

    private suspend fun performMdnsScan(): List<RemotePeerInfo> {
        val peers = mutableListOf<RemotePeerInfo>()

        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.soTimeout = 2000
                socket.broadcast = true

                try {
                    val query = buildMdnsQuery()
                    val packet = DatagramPacket(query, query.size, MDNS_ADDR, MDNS_PORT)
                    socket.send(packet)

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

    private fun buildMdnsQuery(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(buffer)

        dos.writeShort(0x0000)
        dos.writeShort(0x0000)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)

        val parts = CRISIX_SERVICE.split(".")
        for (part in parts) {
            dos.writeByte(part.length)
            dos.write(part.toByteArray())
        }
        dos.writeByte(0x00)
        dos.writeShort(12)
        dos.writeShort(0x8001)

        dos.flush()
        return buffer.toByteArray()
    }

    private fun parseMdnsResponse(packet: DatagramPacket): RemotePeerInfo? {
        try {
            val data = packet.data
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))

            dis.skipBytes(12)

            val questions = dis.readUnsignedShort()
            for (i in 0 until questions) {
                skipDnsName(dis)
                dis.skipBytes(4)
            }

            val answers = dis.readUnsignedShort()
            var peerId: String? = null
            var host: String? = null
            var port: Int? = null

            for (i in 0 until answers) {
                skipDnsName(dis)
                val type = dis.readUnsignedShort()
                dis.skipBytes(2)
                dis.skipBytes(4)
                val dataLength = dis.readUnsignedShort()

                when (type) {
                    12 -> {
                        skipDnsName(dis)
                    }
                    16 -> {
                        if (dataLength > 0) {
                            val txtData = ByteArray(dataLength)
                            dis.readFully(txtData)
                            val txtStr = String(txtData, Charsets.UTF_8)
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
                    33 -> {
                        dis.skipBytes(2)
                        dis.skipBytes(2)
                        port = dis.readUnsignedShort()
                        skipDnsName(dis)
                    }
                    1 -> {
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

    private fun skipDnsName(dis: java.io.DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                dis.readUnsignedByte()
                break
            }
            dis.skipBytes(len)
        }
    }

    suspend fun performHolePunching(host: String, port: Int): Boolean {
        return natTraversal?.performHolePunching(host, port) ?: false
    }

    private fun addPeerToList(peer: RemotePeerInfo) {
        val currentList = _discoveredPeers.value.toMutableList()
        if (currentList.none { it.peerId == peer.peerId }) {
            currentList.add(peer)
            _discoveredPeers.value = currentList
        }
    }

    fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe Peer-Discovery")

        isRunning = false

        scope.launch {
            dhtNode?.stop()
        }
        dhtNode = null

        natCheckJob?.cancel()
        punchListenerJob?.cancel()
        natCheckJob = null
        punchListenerJob = null

        isDhtAvailable = false
        publicAddress = null

        Log.i(TAG, "Peer-Discovery gestoppt")
    }
}

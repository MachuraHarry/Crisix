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
import java.security.MessageDigest

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
        natTraversal = NatTraversal(localPort)

        scope.launch {
            try {
                Log.i(TAG, "Versuche Verbindung zu Mainline-DHT-Bootstrap-Knoten...")
                dhtNode?.start()
                isDhtAvailable = true
                Log.i(TAG, "✅ Mainline-DHT-Knoten gestartet mit ${dhtNode?.knownNodesCount ?: 0} bekannten Knoten")
                Log.i(TAG, "🌍 Globales Topic: ${DhtConfig.GLOBAL_TOPIC_HEX.take(16)}...")
            } catch (e: Exception) {
                Log.w(TAG, "DHT-Start fehlgeschlagen (Offline-Fallback): ${e.message}")
                isDhtAvailable = false
            }

            // NAT Discovery parallel starten, maximal 5 Sekunden warten
            val natDeferred = async {
                try {
                    natTraversal?.discoverPublicAddress()
                } catch (e: Exception) {
                    Log.w(TAG, "NAT-Traversal nicht verfügbar: ${e.message}")
                    null
                }
            }
            val natAddr = try {
                withTimeout(5000) { natDeferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "NAT-Discovery nach 5s Timeout abgebrochen – verwende lokale IP")
                null
            }
            if (natAddr != null) {
                publicAddress = natAddr
                Log.i(TAG, "Öffentliche Adresse: ${natAddr.host}:${natAddr.port}")

                natTraversal?.startPunchListener { host, port ->
                    Log.d(TAG, "Hole Punch empfangen von $host:$port")
                    val peerInfo = RemotePeerInfo(
                        peerId = "punch-$host-$port",
                        host = host,
                        port = port,
                        isConnected = false
                    )
                    addPeerToList(peerInfo)
                }
            }

            // Jetzt announce mit der besten verfügbaren Adresse
            doAnnounce(localPeerId, publicAddress?.host, publicAddress?.port)

            // Periodische NAT-Prüfung + Re-Announce (alle 5 Minuten)
            while (isActive) {
                delay(NAT_CHECK_INTERVAL_MS)
                try {
                    val addr = natTraversal?.discoverPublicAddress()
                    if (addr != null) {
                        publicAddress = addr
                        Log.d(TAG, "Öffentliche Adresse aktualisiert: ${addr.host}:${addr.port}")
                    }
                    // Immer re-announce (auch wenn Adresse gleich – DHT-Einträge verfallen)
                    doAnnounce(localPeerId, publicAddress?.host, publicAddress?.port)
                } catch (e: Exception) {
                    Log.w(TAG, "NAT-Prüfung/Re-Announce fehlgeschlagen: ${e.message}")
                }
            }
        }

        Log.i(TAG, "Peer-Discovery gestartet (DHT: ${if (isDhtAvailable) "verfügbar" else "prüfe..."})")
    }

    private suspend fun doAnnounce(localPeerId: String, host: String?, port: Int?) {
        if (dhtNode == null || !isDhtAvailable) return

        // Announce auf globalem Topic
        Log.i(TAG, "Registriere Peer $localPeerId in globaler DHT (host=$host)")
        dhtNode?.announce(
            topicBytes = DhtConfig.GLOBAL_TOPIC,
            peerId = localPeerId,
            publicHost = host,
            publicPort = port
        )

        // Announce auf eigenem Peer-Topic (SHA-1(localPeerId))
        val peerTopic = MessageDigest.getInstance("SHA-1")
            .digest(localPeerId.toByteArray(Charsets.UTF_8))
        dhtNode?.announce(
            topicBytes = peerTopic,
            peerId = localPeerId,
            publicHost = host,
            publicPort = port
        )
    }

    suspend fun findPeer(targetPeerId: String): RemotePeerInfo? {
        Log.d(TAG, "Suche Peer in DHT: $targetPeerId")

        return try {
            // Per-Peer-Topic: SHA-1(targetPeerId)
            // Jeder Peer announced sich unter seinem eigenen Topic.
            // So liefert get_peers nur genau diesen einen Peer zurück.
            val peerTopic = MessageDigest.getInstance("SHA-1")
                .digest(targetPeerId.toByteArray(Charsets.UTF_8))

            val topicPeers = dhtNode?.findPeersForTopic(peerTopic) ?: emptyList()
            val matchingPeer = topicPeers.firstOrNull { it.peerId == targetPeerId }
                ?: topicPeers.firstOrNull()

            if (matchingPeer != null) {
                Log.i(TAG, "Peer $targetPeerId gefunden: ${matchingPeer.host}:${matchingPeer.port}")

                val peerInfo = RemotePeerInfo(
                    peerId = targetPeerId,
                    host = matchingPeer.host,
                    port = matchingPeer.port,
                    isConnected = true
                )
                addPeerToList(peerInfo)

                // Direktverbindung testen KEIN Hole Punching mehr
                // (Hole Punching ist UDP-only, libp2p nutzt TCP)
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
                val h = host
                val p = port
                return RemotePeerInfo(
                    peerId = peerId,
                    host = h,
                    port = p,
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

        isDhtAvailable = false
        publicAddress = null

        Log.i(TAG, "Peer-Discovery gestoppt")
    }
}

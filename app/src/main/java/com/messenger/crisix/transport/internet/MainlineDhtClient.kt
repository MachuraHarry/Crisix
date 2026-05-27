package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Random

/**
 * KRPC-Client für das Mainline-BitTorrent-DHT-Protokoll (bencode-basiert).
 *
 * ## Warum?
 * Die DNS-Seeds (router.bittorrent.com, etc.) liefern Mainline-DHT-Knoten,
 * die das KRPC-Protokoll (bencode) sprechen, NICHT Hyperswarm-MessagePack.
 * Um sich mit dem Mainline-DHT-Netzwerk zu verbinden, müssen wir deren
 * Protokoll sprechen.
 *
 * ## KRPC-Protokoll (BEP 5)
 * - **bencode**-Serialisierung (nicht JSON, nicht MessagePack)
 * - UDP-Transport
 * - Nachrichtentypen: ping, find_node, get_peers, announce_peer
 * - 20-Byte-Node-IDs (SHA-1)
 * - Transaction-ID (2 Bytes) für Request/Response-Matching
 *
 * ## Verwendung
 * ```kotlin
 * val client = MainlineDhtClient()
 * val nodes = client.bootstrap() // Gibt Liste von (host, port) zurück
 * ```
 */
class MainlineDhtClient {

    companion object {
        private const val TAG = "MainlineDhtClient"

        /** Timeout für KRPC-Anfragen */
        private const val TIMEOUT_MS = 2000L

        /** Maximale Anzahl von Bootstrap-Versuchen */
        private const val MAX_ATTEMPTS = 3

        /** KRPC-Nachrichtentypen */
        private const val KRPC_PING = "ping"
        private const val KRPC_FIND_NODE = "find_node"

        /** Port für Mainline-DHT (Standard: 6881, aber viele nutzen 49737 oder andere) */
        private const val MAINLINE_DHT_PORT = 6881
    }

    /**
     * Führt einen KRPC-Ping an einen Mainline-DHT-Knoten durch.
     *
     * @param host Die IP des Knotens
     * @param port Der Port des Knotens
     * @return true wenn der Knoten geantwortet hat
     */
    suspend fun ping(host: String, port: Int): Boolean {
        return try {
            withTimeout(TIMEOUT_MS) {
                val socket = DatagramSocket()
                socket.soTimeout = TIMEOUT_MS.toInt()

                try {
                    // KRPC-Ping-Nachricht erstellen (bencode)
                    val transactionId = generateTransactionId()
                    val request = encodeKrcpQuery(transactionId, KRPC_PING)

                    val addr = InetAddress.getByName(host)
                    val packet = DatagramPacket(request, request.size, addr, port)
                    socket.send(packet)

                    // Auf Antwort warten
                    val response = ByteArray(1024)
                    val responsePacket = DatagramPacket(response, response.size)
                    socket.receive(responsePacket)

                    val responseData = response.copyOf(responsePacket.length)
                    val responseStr = String(responseData, Charsets.ISO_8859_1)

                    // Prüfen, ob es eine gültige KRPC-Antwort ist
                    responseStr.contains("rd") && responseStr.contains(":r")
                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "KRPC-Ping fehlgeschlagen für $host:$port: ${e.message}")
            false
        }
    }

    /**
     * Führt eine KRPC-find_node-Anfrage durch und extrahiert die
     * nächstgelegenen Knoten.
     *
     * @param host Die IP des Knotens
     * @param port Der Port des Knotens
     * @param targetId Die 20-Byte-Ziel-ID (SHA-1)
     * @return Liste der gefundenen Knoten als (host, port)
     */
    suspend fun findNode(host: String, port: Int, targetId: ByteArray): List<Pair<String, Int>> {
        return try {
            withTimeout(TIMEOUT_MS) {
                val socket = DatagramSocket()
                socket.soTimeout = TIMEOUT_MS.toInt()

                try {
                    // KRPC-find_node-Nachricht erstellen
                    val transactionId = generateTransactionId()
                    val request = encodeKrcpFindNode(transactionId, targetId)

                    val addr = InetAddress.getByName(host)
                    val packet = DatagramPacket(request, request.size, addr, port)
                    socket.send(packet)

                    // Auf Antwort warten
                    val response = ByteArray(2048)
                    val responsePacket = DatagramPacket(response, response.size)
                    socket.receive(responsePacket)

                    val responseData = response.copyOf(responsePacket.length)
                    // Nodes aus der Antwort extrahieren
                    parseNodesFromResponse(responseData)
                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "KRPC-find_node fehlgeschlagen für $host:$port: ${e.message}")
            emptyList()
        }
    }

    /**
     * Führt einen vollständigen Bootstrap-Durchlauf durch.
     *
     * 1. Ping an alle Bootstrap-Knoten (versucht Port 6881 und 49737)
     * 2. find_node an antwortende Knoten
     * 3. Gibt alle gefundenen Knoten zurück
     *
     * @param bootstrapHosts Liste der Bootstrap-Knoten als "host:port"
     * @return Liste der gefundenen Knoten als "host:port"
     */
    suspend fun bootstrap(bootstrapHosts: List<String>): List<String> {
        Log.i(TAG, "Starte Mainline-DHT-Bootstrap mit ${bootstrapHosts.size} Seeds...")

        val foundNodes = mutableSetOf<String>()
        val targetId = generateRandomNodeId()

        // Ports, die wir für jeden Seed versuchen
        val portsToTry = listOf(6881, 49737)

        for (seed in bootstrapHosts) {
            try {
                val parts = seed.split(":")
                val host = parts[0]
                val configuredPort = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0

                // Versuche konfigurierten Port + Standard-Ports
                val tryPorts = mutableSetOf<Int>()
                if (configuredPort > 0) tryPorts.add(configuredPort)
                tryPorts.addAll(portsToTry)

                for (port in tryPorts) {
                    Log.d(TAG, "Versuche Mainline-DHT-Seed: $host:$port")

                    // Zuerst ping
                    val alive = ping(host, port)
                    if (!alive) {
                        Log.d(TAG, "Seed $host:$port antwortet nicht auf KRPC-Ping")
                        continue
                    }

                    Log.d(TAG, "Seed $host:$port lebt, führe find_node aus...")

                    // find_node
                    val nodes = findNode(host, port, targetId)
                    for ((nodeHost, nodePort) in nodes) {
                        val nodeStr = "$nodeHost:$nodePort"
                        if (nodeStr !in foundNodes) {
                            foundNodes.add(nodeStr)
                            Log.d(TAG, "  -> Gefunden: $nodeStr")
                        }
                    }

                    // Wenn dieser Port funktioniert hat, andere Ports überspringen
                    break
                }

                // Kurze Pause zwischen Seeds
                delay(100)
            } catch (e: Exception) {
                Log.w(TAG, "Fehler bei Seed $seed: ${e.message}")
            }
        }

        Log.i(TAG, "Mainline-DHT-Bootstrap abgeschlossen: ${foundNodes.size} Knoten gefunden")
        return foundNodes.toList()
    }

    // =========================================================================
    // KRPC-Protokoll (bencode)
    // =========================================================================

    /**
     * Generiert eine zufällige 2-Byte-Transaction-ID.
     */
    private fun generateTransactionId(): ByteArray {
        val id = ByteArray(2)
        Random().nextBytes(id)
        return id
    }

    /**
     * Generiert eine zufällige 20-Byte-Node-ID (SHA-1).
     */
    private fun generateRandomNodeId(): ByteArray {
        val random = ByteArray(20)
        Random().nextBytes(random)
        return random
    }

    /**
     * Kodiert eine KRPC-Query-Nachricht in bencode.
     *
     * Format: {"t":<transaction_id>, "y":"q", "q":<query_type>, "a":{}}
     *
     * @param transactionId 2-Byte-Transaction-ID
     * @param queryType Der Query-Typ (z.B. "ping", "find_node")
     * @return Bencode-kodierte Nachricht
     */
    private fun encodeKrcpQuery(transactionId: ByteArray, queryType: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // {"t":<tid>, "y":"q", "q":<type>, "a":{}}
        baos.write("d".toByteArray()) // dict start

        // "t":<tid>
        baos.write("1:t".toByteArray())
        baos.write("${transactionId.size}:".toByteArray())
        baos.write(transactionId)

        // "y":"q"
        baos.write("1:y1:q".toByteArray())

        // "q":<type>
        baos.write("1:q${queryType.length}:${queryType}".toByteArray())

        // "a":{}
        baos.write("1:ade".toByteArray()) // empty dict

        baos.write("e".toByteArray()) // dict end

        return baos.toByteArray()
    }

    /**
     * Kodiert eine KRPC-find_node-Query in bencode.
     *
     * Format: {"t":<tid>, "y":"q", "q":"find_node", "a":{"id":<20_byte_id>, "target":<20_byte_id>}}
     *
     * @param transactionId 2-Byte-Transaction-ID
     * @param targetId 20-Byte-Ziel-ID
     * @return Bencode-kodierte Nachricht
     */
    private fun encodeKrcpFindNode(transactionId: ByteArray, targetId: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()

        // {"t":<tid>, "y":"q", "q":"find_node", "a":{"id":<20_byte_id>, "target":<20_byte_id>}}
        baos.write("d".toByteArray()) // dict start

        // "t":<tid>
        baos.write("1:t".toByteArray())
        baos.write("${transactionId.size}:".toByteArray())
        baos.write(transactionId)

        // "y":"q"
        baos.write("1:y1:q".toByteArray())

        // "q":"find_node"
        baos.write("1:q9:find_node".toByteArray())

        // "a":{"id":<20>, "target":<20>}
        baos.write("1:ad2:id20:".toByteArray())
        baos.write(generateRandomNodeId()) // unsere ID
        baos.write("6:target20:".toByteArray())
        baos.write(targetId)
        baos.write("e".toByteArray()) // end "a"

        baos.write("e".toByteArray()) // dict end

        return baos.toByteArray()
    }

    /**
     * Parst Knoten aus einer KRPC-find_node-Antwort.
     *
     * Das "nodes"-Feld enthält kompakte 26-Byte-Einträge:
     * - 4 Bytes IP
     * - 2 Bytes Port (Big-Endian)
     * - 20 Bytes Node-ID
     *
     * @param responseData Die rohe Antwort (bencode)
     * @return Liste der gefundenen Knoten als (host, port)
     */
    private fun parseNodesFromResponse(responseData: ByteArray): List<Pair<String, Int>> {
        val nodes = mutableListOf<Pair<String, Int>>()
        val responseStr = String(responseData, Charsets.ISO_8859_1)

        try {
            // "nodes" gefolgt von Längenangabe und kompakten Nodes
            // Format: ...5:nodes<len>:<compact_node_data>...
            val nodesPattern = Regex("5:nodes(\\d+):")
            val match = nodesPattern.find(responseStr) ?: return nodes

            val lenStr = match.groupValues[1]
            val dataLen = lenStr.toInt()
            val startIdx = match.range.last + 1

            if (startIdx + dataLen > responseData.size) return nodes

            // Kompakte Nodes parsen (26 Bytes pro Node)
            val nodeData = responseData.copyOfRange(startIdx, startIdx + dataLen)
            var offset = 0

            while (offset + 26 <= nodeData.size) {
                try {
                    // IP (4 Bytes)
                    val ipBytes = nodeData.copyOfRange(offset, offset + 4)
                    val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                    offset += 4

                    // Port (2 Bytes, Big-Endian)
                    val port = ((nodeData[offset].toInt() and 0xFF) shl 8) or
                            (nodeData[offset + 1].toInt() and 0xFF)
                    offset += 2

                    // Node-ID (20 Bytes) - überspringen
                    offset += 20

                    if (ip.isNotEmpty() && port > 0 && !ip.startsWith("0.")) {
                        nodes.add(Pair(ip, port))
                    }
                } catch (e: Exception) {
                    offset += 26
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen der Nodes: ${e.message}")
        }

        return nodes
    }
}

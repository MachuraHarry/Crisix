package com.messenger.crisix.transport.internet

import android.util.Log
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.security.MessageDigest

/**
 * Hyperswarm-kompatibles Nachrichtenprotokoll für das Crisix-P2P-Netzwerk.
 *
 * ## Übersicht
 * Dieses Protokoll implementiert die Hyperswarm-Nachrichtenformate unter Verwendung
 * von MessagePack (anstelle von Protocol Buffers) für kompakte, schemalose Serialisierung.
 *
 * ## Nachrichtentypen (MessagePack-basiert)
 * | typ | Name | payload | Beschreibung |
 * |-----|------|---------|--------------|
 * | 1 | PING | [nonce] | Health-Check |
 * | 2 | PONG | [nonce] | Antwort auf PING |
 * | 3 | FIND_NODE | [target_id] | Suche nach Node in DHT |
 * | 4 | NODES | [nodes] | Liste von Nodes |
 * | 5 | ANNOUNCE | [topic, peer_id] | Peer announced sich für Topic |
 * | 6 | UNANNOUNCE | [topic, peer_id] | Peer entfernt Announcement |
 *
 * ## Transport
 * - UDP (Standard-Port: 49737)
 * - Max. Paketgröße: 1400 Bytes
 * - Jedes Paket wird mit Noise verschlüsselt (siehe [NoisePacketCrypto])
 *
 * ## Verwendung
 * ```kotlin
 * val msg = HyperswarmProtocol.createPing(nonce)
 * val encoded = HyperswarmProtocol.encode(msg)
 * val decoded = HyperswarmProtocol.decode(encoded)
 * ```
 */
object HyperswarmProtocol {

    private const val TAG = "HyperswarmProtocol"

    // =========================================================================
    // Konstanten
    // =========================================================================

    /** Magic Number zur Identifikation des Hyperswarm-Protokolls (4 Bytes) */
    const val PROTOCOL_MAGIC: Int = 0x48595045 // "HYPE"

    /** Aktuelle Protokollversion */
    const val PROTOCOL_VERSION: Byte = 0x01

    /** Standard-Port für DHT-Kommunikation (Hyperswarm-kompatibel) */
    const val DHT_PORT = 49737

    /** Maximale Paketgröße in Bytes (Hyperswarm-kompatibel) */
    const val MAX_PACKET_SIZE = 1400

    /** Maximale Anzahl von Nodes in einer NODES-Antwort */
    const val MAX_NODES_PER_RESPONSE = 20

    // =========================================================================
    // Nachrichtentypen (MessagePack)
    // =========================================================================

    /** Nachrichtentyp-IDs */
    const val TYPE_PING: Int = 1
    const val TYPE_PONG: Int = 2
    const val TYPE_FIND_NODE: Int = 3
    const val TYPE_NODES: Int = 4
    const val TYPE_ANNOUNCE: Int = 5
    const val TYPE_UNANNOUNCE: Int = 6

    // =========================================================================
    // Datenklassen
    // =========================================================================

    /**
     * Repräsentiert einen Node im Hyperswarm-Netzwerk.
     */
    data class HyperswarmNode(
        val nodeId: ByteArray,      // 32 Bytes Ed25519 Public Key
        val host: String,
        val port: Int,
        val distance: ByteArray? = null // XOR-Distanz (optional, für Antworten)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HyperswarmNode) return false
            return nodeId.contentEquals(other.nodeId)
        }

        override fun hashCode(): Int = nodeId.contentHashCode()

        override fun toString(): String {
            return "HyperswarmNode(id=${nodeId.take(4).joinToString("") { "%02x".format(it) }}..., host=$host, port=$port)"
        }
    }

    /**
     * Versiegelte Klasse für alle Hyperswarm-Nachrichtentypen.
     */
    sealed class HyperswarmMessage {

        /** Eindeutige Nonce für diese Nachricht (8 Bytes) */
        abstract val nonce: ByteArray

        /**
         * PING: Health-Check für einen Peer.
         */
        data class Ping(
            override val nonce: ByteArray
        ) : HyperswarmMessage()

        /**
         * PONG: Antwort auf einen PING.
         */
        data class Pong(
            override val nonce: ByteArray
        ) : HyperswarmMessage()

        /**
         * FIND_NODE: Suche nach den nächstgelegenen Nodes zu einer Ziel-ID.
         */
        data class FindNode(
            override val nonce: ByteArray,
            val targetId: ByteArray // 32 Bytes
        ) : HyperswarmMessage()

        /**
         * NODES: Liste von Nodes als Antwort auf FIND_NODE.
         */
        data class Nodes(
            override val nonce: ByteArray,
            val nodes: List<HyperswarmNode>
        ) : HyperswarmMessage()

        /**
         * ANNOUNCE: Peer announced sich für ein Topic.
         */
        data class Announce(
            override val nonce: ByteArray,
            val topic: ByteArray,   // 32 Bytes (SHA-256 Hash)
            val peerId: ByteArray   // 32 Bytes (Ed25519 Public Key)
        ) : HyperswarmMessage()

        /**
         * UNANNOUNCE: Peer entfernt sein Announcement für ein Topic.
         */
        data class Unannounce(
            override val nonce: ByteArray,
            val topic: ByteArray,   // 32 Bytes (SHA-256 Hash)
            val peerId: ByteArray   // 32 Bytes (Ed25519 Public Key)
        ) : HyperswarmMessage()
    }

    // =========================================================================
    // MessagePack Serialisierung
    // =========================================================================

    /**
     * Kodiert eine HyperswarmMessage in ein Byte-Array (MessagePack).
     *
     * Format:
     * ```
     * [Magic: 4 Bytes] [Version: 1 Byte] [MessagePack Payload]
     * ```
     *
     * MessagePack-Struktur:
     * ```json
     * {
     *   "type": 1,           // Nachrichtentyp
     *   "nonce": [bytes],    // 8 Bytes Nonce
     *   "payload": { ... }   // Typspezifische Felder
     * }
     * ```
     *
     * @param message Die zu kodierende Nachricht
     * @return Die kodierten Bytes (MessagePack)
     */
    fun encode(message: HyperswarmMessage): ByteArray {
        return try {
            val packer = MessagePack.newDefaultBufferPacker()

            // Header: Magic + Version
            packer.packInt(PROTOCOL_MAGIC)
            packer.packByte(PROTOCOL_VERSION)

            // MessagePack Map für die Nachricht
            packer.packMapHeader(3) // type, nonce, payload

            // Typ
            packer.packString("type")
            packer.packInt(getTypeId(message))

            // Nonce
            packer.packString("nonce")
            packer.packBinaryHeader(message.nonce.size)
            packer.writePayload(message.nonce)

            // Payload
            packer.packString("payload")
            packPayload(packer, message)

            packer.close()
            packer.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Kodieren der Hyperswarm-Nachricht: ${e.message}", e)
            throw RuntimeException("Kodierung fehlgeschlagen", e)
        }
    }

    /**
     * Dekodiert ein Byte-Array in eine HyperswarmMessage.
     *
     * @param data Die kodierten Bytes (MessagePack)
     * @return Die dekodierte Nachricht, oder null bei Fehlern
     */
    fun decode(data: ByteArray): HyperswarmMessage? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(data)

            // Header prüfen: Magic + Version
            val magic = unpacker.unpackInt()
            if (magic != PROTOCOL_MAGIC) {
                Log.w(TAG, "Ungültige Magic Number: ${Integer.toHexString(magic)}")
                return null
            }

            val version = unpacker.unpackByte()
            if (version != PROTOCOL_VERSION) {
                Log.w(TAG, "Nicht unterstützte Protokollversion: $version")
                return null
            }

            // MessagePack Map parsen
            val mapSize = unpacker.unpackMapHeader()

            var type: Int? = null
            var nonce: ByteArray? = null
            var payloadStart = -1L
            var payloadLength = 0

            for (i in 0 until mapSize) {
                val key = unpacker.unpackString()
                when (key) {
                    "type" -> type = unpacker.unpackInt()
                    "nonce" -> {
                        val len = unpacker.unpackBinaryHeader()
                        nonce = ByteArray(len)
                        unpacker.readPayload(nonce!!)
                    }
                    "payload" -> {
                        payloadStart = unpacker.getTotalReadBytes()
                        // Wir müssen den Payload später parsen, basierend auf dem Typ
                        // Merken wir uns die Position
                    }
                }
            }

            if (type == null || nonce == null) {
                Log.w(TAG, "Unvollständige Nachricht: fehlende Pflichtfelder")
                return null
            }

            // Payload basierend auf Typ parsen
            val msg = parsePayload(unpacker, type, nonce)

            msg
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Dekodieren der Hyperswarm-Nachricht: ${e.message}", e)
            null
        }
    }

    /**
     * Gibt die numerische Typ-ID für eine HyperswarmMessage zurück.
     */
    private fun getTypeId(message: HyperswarmMessage): Int = when (message) {
        is HyperswarmMessage.Ping -> TYPE_PING
        is HyperswarmMessage.Pong -> TYPE_PONG
        is HyperswarmMessage.FindNode -> TYPE_FIND_NODE
        is HyperswarmMessage.Nodes -> TYPE_NODES
        is HyperswarmMessage.Announce -> TYPE_ANNOUNCE
        is HyperswarmMessage.Unannounce -> TYPE_UNANNOUNCE
    }

    /**
     * Packt den typspezifischen Payload einer HyperswarmMessage.
     */
    private fun packPayload(packer: MessageBufferPacker, message: HyperswarmMessage) {
        when (message) {
            is HyperswarmMessage.Ping -> {
                // Ping hat keinen zusätzlichen Payload außer nonce
                packer.packNil()
            }
            is HyperswarmMessage.Pong -> {
                // Pong hat keinen zusätzlichen Payload außer nonce
                packer.packNil()
            }
            is HyperswarmMessage.FindNode -> {
                packer.packMapHeader(1)
                packer.packString("target_id")
                packer.packBinaryHeader(message.targetId.size)
                packer.writePayload(message.targetId)
            }
            is HyperswarmMessage.Nodes -> {
                packer.packMapHeader(1)
                packer.packString("nodes")
                packer.packArrayHeader(message.nodes.size)
                for (node in message.nodes) {
                    packer.packMapHeader(4)
                    packer.packString("node_id")
                    packer.packBinaryHeader(node.nodeId.size)
                    packer.writePayload(node.nodeId)
                    packer.packString("host")
                    packer.packString(node.host)
                    packer.packString("port")
                    packer.packInt(node.port)
                    packer.packString("distance")
                    if (node.distance != null) {
                        packer.packBinaryHeader(node.distance.size)
                        packer.writePayload(node.distance)
                    } else {
                        packer.packNil()
                    }
                }
            }
            is HyperswarmMessage.Announce -> {
                packer.packMapHeader(2)
                packer.packString("topic")
                packer.packBinaryHeader(message.topic.size)
                packer.writePayload(message.topic)
                packer.packString("peer_id")
                packer.packBinaryHeader(message.peerId.size)
                packer.writePayload(message.peerId)
            }
            is HyperswarmMessage.Unannounce -> {
                packer.packMapHeader(2)
                packer.packString("topic")
                packer.packBinaryHeader(message.topic.size)
                packer.writePayload(message.topic)
                packer.packString("peer_id")
                packer.packBinaryHeader(message.peerId.size)
                packer.writePayload(message.peerId)
            }
        }
    }

    /**
     * Parst den Payload basierend auf dem Nachrichtentyp.
     */
    private fun parsePayload(unpacker: MessageUnpacker, type: Int, nonce: ByteArray): HyperswarmMessage? {
        return try {
            when (type) {
                TYPE_PING -> HyperswarmMessage.Ping(nonce = nonce)
                TYPE_PONG -> HyperswarmMessage.Pong(nonce = nonce)
                TYPE_FIND_NODE -> {
                    unpacker.unpackMapHeader() // Map-Header
                    unpacker.unpackString() // "target_id"
                    val len = unpacker.unpackBinaryHeader()
                    val targetId = ByteArray(len)
                    unpacker.readPayload(targetId)
                    HyperswarmMessage.FindNode(nonce = nonce, targetId = targetId)
                }
                TYPE_NODES -> {
                    unpacker.unpackMapHeader() // Map-Header
                    unpacker.unpackString() // "nodes"
                    val arraySize = unpacker.unpackArrayHeader()
                    val nodes = mutableListOf<HyperswarmNode>()
                    for (i in 0 until arraySize) {
                        unpacker.unpackMapHeader() // Map-Header für Node
                        var nodeId: ByteArray? = null
                        var host: String? = null
                        var port: Int? = null
                        var distance: ByteArray? = null

                        // Wir wissen, es sind 4 Felder
                        for (j in 0 until 4) {
                            val key = unpacker.unpackString()
                            when (key) {
                                "node_id" -> {
                                    val l = unpacker.unpackBinaryHeader()
                                    nodeId = ByteArray(l)
                                    unpacker.readPayload(nodeId)
                                }
                                "host" -> host = unpacker.unpackString()
                                "port" -> port = unpacker.unpackInt()
                                "distance" -> {
                                    if (unpacker.nextFormat == org.msgpack.core.MessageFormat.NIL) {
                                        unpacker.unpackNil()
                                    } else {
                                        val l = unpacker.unpackBinaryHeader()
                                        distance = ByteArray(l)
                                        unpacker.readPayload(distance)
                                    }
                                }
                            }
                        }

                        if (nodeId != null && host != null && port != null) {
                            nodes.add(HyperswarmNode(nodeId, host, port, distance))
                        }
                    }
                    HyperswarmMessage.Nodes(nonce = nonce, nodes = nodes)
                }
                TYPE_ANNOUNCE -> {
                    unpacker.unpackMapHeader() // Map-Header
                    var topic: ByteArray? = null
                    var peerId: ByteArray? = null
                    for (j in 0 until 2) {
                        val key = unpacker.unpackString()
                        when (key) {
                            "topic" -> {
                                val l = unpacker.unpackBinaryHeader()
                                topic = ByteArray(l)
                                unpacker.readPayload(topic)
                            }
                            "peer_id" -> {
                                val l = unpacker.unpackBinaryHeader()
                                peerId = ByteArray(l)
                                unpacker.readPayload(peerId)
                            }
                        }
                    }
                    if (topic != null && peerId != null) {
                        HyperswarmMessage.Announce(nonce = nonce, topic = topic, peerId = peerId)
                    } else {
                        Log.w(TAG, "Unvollständige ANNOUNCE-Nachricht")
                        null
                    }
                }
                TYPE_UNANNOUNCE -> {
                    unpacker.unpackMapHeader() // Map-Header
                    var topic: ByteArray? = null
                    var peerId: ByteArray? = null
                    for (j in 0 until 2) {
                        val key = unpacker.unpackString()
                        when (key) {
                            "topic" -> {
                                val l = unpacker.unpackBinaryHeader()
                                topic = ByteArray(l)
                                unpacker.readPayload(topic)
                            }
                            "peer_id" -> {
                                val l = unpacker.unpackBinaryHeader()
                                peerId = ByteArray(l)
                                unpacker.readPayload(peerId)
                            }
                        }
                    }
                    if (topic != null && peerId != null) {
                        HyperswarmMessage.Unannounce(nonce = nonce, topic = topic, peerId = peerId)
                    } else {
                        Log.w(TAG, "Unvollständige UNANNOUNCE-Nachricht")
                        null
                    }
                }
                else -> {
                    Log.w(TAG, "Unbekannter Nachrichtentyp: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Parsen des Payloads: ${e.message}", e)
            null
        }
    }

    // =========================================================================
    // Hilfsfunktionen
    // =========================================================================

    /**
     * Generiert eine zufällige 8-Byte-Nonce.
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(8)
        java.security.SecureRandom().nextBytes(nonce)
        return nonce
    }

    /**
     * Berechnet den SHA-256-Hash eines Byte-Arrays.
     *
     * @param data Die Eingabedaten
     * @return 32 Bytes SHA-256-Hash
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Konvertiert einen Public Key in eine 8-stellige, menschenlesbare Kurz-ID.
     *
     * Verwendung:
     * - Anzeige im UI (z.B. "Deine ID: a3k9m2xq")
     * - QR-Code-Inhalt (enthält aber den vollen Public Key)
     * - Manuelle Eingabe als Notfall-Fallback
     *
     * @param publicKey Der öffentliche Schlüssel (32 Bytes Ed25519)
     * @return 8-stellige Base36-kodierte Kurz-ID
     */
    fun getShortId(publicKey: ByteArray): String {
        val hash = sha256(publicKey)       // 32 Bytes
        val first8Bytes = hash.copyOf(8)   // 8 Bytes
        return base36Encode(first8Bytes)   // Ziffern + Kleinbuchstaben
    }

    /**
     * Kodiert ein Byte-Array in Base36 (Ziffern 0-9 + Kleinbuchstaben a-z).
     *
     * @param data Die Eingabedaten
     * @return Base36-kodierter String
     */
    private fun base36Encode(data: ByteArray): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        var value = data.fold(0L) { acc, byte -> (acc * 256 + (byte.toInt() and 0xFF)) % Long.MAX_VALUE }
        val result = StringBuilder()
        for (i in 0 until 8) {
            result.append(alphabet[(value % 36).toInt()])
            value /= 36
        }
        return result.reverse().toString()
    }

    /**
     * Erstellt eine Crisix-URI für QR-Codes.
     *
     * Format: `crisix://contact?key=<base64_public_key>&name=<url_encoded_name>`
     *
     * @param publicKey Der öffentliche Schlüssel (32 Bytes)
     * @param profileName Der Profilname des Nutzers
     * @return Die Crisix-URI als String
     */
    fun createCrisixUri(publicKey: ByteArray, profileName: String): String {
        val publicKeyBase64 = java.util.Base64.getEncoder().encodeToString(publicKey)
        val name = java.net.URLEncoder.encode(profileName, "UTF-8")
        return "crisix://contact?key=$publicKeyBase64&name=$name"
    }

    /**
     * Parst eine Crisix-URI und extrahiert Public Key und Namen.
     *
     * @param uri Die Crisix-URI (z.B. "crisix://contact?key=...&name=...")
     * @return Ein Pair aus (publicKey, name) oder null bei Fehlern
     */
    fun parseCrisixUri(uri: String): Pair<ByteArray, String>? {
        return try {
            if (!uri.startsWith("crisix://contact?")) return null

            val query = uri.substringAfter("?")
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrElse(1) { "" })
            }

            val keyBase64 = params["key"] ?: return null
            val name = java.net.URLDecoder.decode(params["name"] ?: "Unknown", "UTF-8")

            val publicKey = java.util.Base64.getDecoder().decode(keyBase64)
            Pair(publicKey, name)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Parsen der Crisix-URI: ${e.message}", e)
            null
        }
    }

    /**
     * Prüft, ob ein Byte-Array eine gültige Hyperswarm-Nachricht ist.
     */
    fun isValidMessage(data: ByteArray): Boolean {
        if (data.size < 6) return false // Magic (4) + Version (1) + min Payload
        val magic = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        return magic == PROTOCOL_MAGIC
    }
}

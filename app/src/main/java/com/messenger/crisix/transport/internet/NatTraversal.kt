package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * NAT-Traversal für das Crisix-P2P-Netzwerk.
 *
 * ## Problem
 * Die meisten Geräte befinden sich hinter einem Router (NAT).
 * Ohne NAT-Traversal können Peers hinter verschiedenen NATs
 * keine direkte Verbindung zueinander aufbauen.
 *
 * ## Lösung: UDP Hole Punching
 * UDP Hole Punching ist eine Technik, bei der zwei Peers
 * gleichzeitig UDP-Pakete an die öffentliche IP/Port des
 * jeweils anderen senden. Dadurch werden "Löcher" in den
 * NATs geöffnet, durch die dann die Kommunikation fließen kann.
 *
 * ### Ablauf
 * 1. **Port-Erkennung**: Peer erfährt seine öffentliche IP/Port
 *    über einen STUN-Server (oder über einen bekannten Peer)
 * 2. **Adressaustausch**: Peers tauschen ihre öffentlichen
 *    Adressen über die DHT oder einen Relay-Peer aus
 * 3. **Hole Punching**: Beide Peers senden gleichzeitig UDP-Pakete
 *    an die öffentliche Adresse des jeweils anderen
 * 4. **Direkte Verbindung**: Sobald die Löcher geöffnet sind,
 *    können die Peers direkt kommunizieren
 *
 * ## NAT-Typen
 * - **Full Cone**: Jeder kann über die geöffnete Port-Mapping senden
 * - **Restricted Cone**: Nur Peers, die vorher gesendet haben, können antworten
 * - **Port Restricted Cone**: Wie Restricted, aber auch Port muss passen
 * - **Symmetric**: Jede Zieladresse bekommt eine eigene Port-Mapping
 *   (schwierigster Fall, erfordert Relay)
 *
 * ## Verwendung
 * ```kotlin
 * val nat = NatTraversal(localPort)
 * val publicAddr = nat.discoverPublicAddress()
 * nat.performHolePunching(remoteHost, remotePort)
 * ```
 */
class NatTraversal(
    private val localPort: Int,
    private val localUdpPort: Int = 49737
) {
    companion object {
        private const val TAG = "NatTraversal"

        /** STUN-Server mit Fallbacks */
        private val STUN_SERVERS = listOf(
            "stun.l.google.com" to 19302,
            "stun1.l.google.com" to 19302,
            "stun2.l.google.com" to 19302,
            "stun3.l.google.com" to 19302,
            "stun4.l.google.com" to 19302,
            "stun.voipbuster.com" to 19302,
            "stun.sipgate.net" to 3478
        )

        /** Timeout für STUN-Anfragen */
        private const val STUN_TIMEOUT_MS = 3000L

        /** Anzahl der Hole-Punching-Versuche */
        private const val PUNCH_ATTEMPTS = 10

        /** Verzögerung zwischen Punching-Versuchen */
        private const val PUNCH_DELAY_MS = 100L

        /** Maximale Größe einer STUN-Nachricht */
        private const val STUN_HEADER_SIZE = 20
    }

    /**
     * Repräsentiert eine öffentliche Adresse (IP + Port).
     */
    data class PublicAddress(
        val host: String,
        val port: Int
    )

    /**
     * Ermittelt die öffentliche IP-Adresse und den Port über STUN.
     *
     * STUN (Session Traversal Utilities for NAT) ist ein standardisiertes
     * Protokoll (RFC 5389), das es einem Client ermöglicht, seine
     * öffentliche IP-Adresse und den zugeordneten Port zu erfahren.
     *
     * @return Die öffentliche Adresse, oder null bei Fehlschlag
     */
    suspend fun discoverPublicAddress(): PublicAddress? {
        for ((server, port) in STUN_SERVERS) {
            try {
                val addr = withTimeout(STUN_TIMEOUT_MS) {
                    val socket = DatagramSocket()
                    socket.soTimeout = STUN_TIMEOUT_MS.toInt()

                    try {
                        val request = createStunBindingRequest()
                        val serverAddr = InetAddress.getByName(server)
                        val packet = DatagramPacket(request, request.size, serverAddr, port)
                        socket.send(packet)

                        val response = ByteArray(512)
                        val responsePacket = DatagramPacket(response, response.size)
                        socket.receive(responsePacket)

                        parseStunResponse(responsePacket)
                    } finally {
                        socket.close()
                    }
                }
                if (addr != null) {
                    Log.i(TAG, "Öffentliche Adresse via $server:$port: ${addr.host}:${addr.port}")
                    return addr
                }
            } catch (e: Exception) {
                Log.d(TAG, "STUN $server:$port fehlgeschlagen: ${e.message}")
            }
        }
        Log.w(TAG, "Alle STUN-Server fehlgeschlagen")
        return null
    }

    /**
     * Führt UDP Hole Punching zu einem entfernten Peer durch.
     *
     * Beide Peers müssen diese Methode gleichzeitig aufrufen,
     * damit die NAT-Löcher geöffnet werden.
     *
     * @param remoteHost Die öffentliche IP des entfernten Peers
     * @param remotePort Der öffentliche UDP-Port des entfernten Peers
     * @return true wenn Hole Punching erfolgreich war
     */
    suspend fun performHolePunching(remoteHost: String, remotePort: Int): Boolean {
        Log.d(TAG, "Starte Hole Punching zu $remoteHost:$remotePort")

        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 2000

            try {
                // Mehrere Punching-Versuche
                for (attempt in 1..PUNCH_ATTEMPTS) {
                    // Hole-Punching-Nachricht senden
                    val punchMsg = createPunchMessage()
                    val addr = InetAddress.getByName(remoteHost)
                    val packet = DatagramPacket(punchMsg, punchMsg.size, addr, remotePort)
                    socket.send(packet)

                    // Auf Antwort warten
                    try {
                        val response = ByteArray(256)
                        val responsePacket = DatagramPacket(response, response.size)
                        socket.receive(responsePacket)

                        if (isValidPunchResponse(responsePacket)) {
                            Log.i(TAG, "Hole Punching erfolgreich nach $attempt Versuchen")
                            return true
                        }
                    } catch (e: SocketTimeoutException) {
                        // Timeout ist normal, nächster Versuch
                    }

                    delay(PUNCH_DELAY_MS)
                }

                Log.w(TAG, "Hole Punching zu $remoteHost:$remotePort fehlgeschlagen")
                false
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Hole Punching: ${e.message}", e)
            false
        }
    }

    /**
     * Startet einen Hole-Punching-Listener.
     *
     * Lauscht auf eingehende Punching-Anfragen und antwortet darauf.
     * Dies muss gestartet werden, BEVOR der entfernte Peer mit
     * dem Punching beginnt.
     *
     * @param onPunchReceived Callback, wenn ein Punch empfangen wurde
     * @return Job, der den Listener steuert
     */
    fun startPunchListener(onPunchReceived: (String, Int) -> Unit): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(localUdpPort)
                socket.soTimeout = 30000
                val buffer = ByteArray(256)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        if (isValidPunchRequest(packet)) {
                            val senderHost = packet.address.hostAddress ?: "unknown"
                            val senderPort = packet.port

                            // Antwort senden
                            val response = createPunchResponse()
                            val responsePacket = DatagramPacket(
                                response, response.size,
                                packet.address, senderPort
                            )
                            socket.send(responsePacket)

                            // Callback aufrufen
                            onPunchReceived(senderHost, senderPort)
                            Log.d(TAG, "Punch von $senderHost:$senderPort empfangen")
                        }
                    } catch (e: SocketTimeoutException) {
                        // Normaler Timeout
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fehler im Punch-Listener: ${e.message}", e)
            }
        }
    }

    /**
     * Versucht, die öffentliche IP über UPnP zu ermitteln.
     *
     * UPnP (Universal Plug and Play) ist ein Protokoll, das es
     * Geräten erlaubt, automatisch Port-Forwarding-Regeln im
     * Router zu konfigurieren. Dies ist die einfachste Methode
     * für NAT-Traversal, aber nicht auf allen Routern aktiviert.
     *
     * @return Die öffentliche IP, oder null bei Fehlschlag
     */
    suspend fun discoverViaUpnp(): String? {
        return try {
            // UPnP-Entdeckungsanfrage senden
            val socket = MulticastSocket()
            val request = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 2
                ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1
            """.trimIndent().toByteArray()

            val packet = DatagramPacket(
                request, request.size,
                InetAddress.getByName("239.255.255.250"), 1900
            )
            socket.send(packet)

            // Auf Antwort warten
            socket.soTimeout = 2000
            val response = ByteArray(1024)
            val responsePacket = DatagramPacket(response, response.size)

            try {
                socket.receive(responsePacket)
                // UPnP-Antwort parsen (vereinfacht)
                val responseStr = String(response, 0, responsePacket.length)
                Log.d(TAG, "UPnP-Antwort empfangen: $responseStr")
                // In einer vollständigen Implementierung würde hier
                // die öffentliche IP aus der UPnP-Antwort extrahiert
                null
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "Keine UPnP-Antwort (nicht verfügbar)")
                null
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "UPnP fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Prüft, ob eine direkte TCP-Verbindung zu einem Peer möglich ist.
     *
     * @param host Die IP-Adresse des Peers
     * @param port Der TCP-Port des Peers
     * @return true wenn eine direkte Verbindung möglich ist
     */
    suspend fun testDirectConnection(host: String, port: Int): Boolean {
        return try {
            withTimeout(2000) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), 2000)
                    socket.close()
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================================
    // STUN-Protokoll
    // =========================================================================

    /**
     * Erstellt eine STUN-Binding-Request-Nachricht.
     *
     * Format (RFC 5389):
     * - 2 Bytes: Nachrichtentyp (0x0001 = Binding Request)
     * - 2 Bytes: Nachrichtenlänge
     * - 16 Bytes: Transaction ID (zufällig)
     */
    private fun createStunBindingRequest(): ByteArray {
        val buffer = ByteArray(STUN_HEADER_SIZE)
        val dos = DataOutputStream(ByteArrayOutputStream().also { it.write(buffer) })

        // Nachrichtentyp: Binding Request (0x0001)
        dos.writeShort(0x0001)

        // Nachrichtenlänge (ohne Header)
        dos.writeShort(0x0000)

        // Transaction ID (16 Bytes zufällig)
        val transactionId = ByteArray(16)
        java.util.Random().nextBytes(transactionId)
        dos.write(transactionId)

        dos.flush()
        return (dos as? ByteArrayOutputStream)?.toByteArray() ?: buffer
    }

    /**
     * Parst eine STUN-Binding-Response und extrahiert die öffentliche Adresse.
     */
    private fun parseStunResponse(packet: DatagramPacket): PublicAddress? {
        try {
            val data = packet.data
            val dis = DataInputStream(ByteArrayInputStream(data))

            // Nachrichtentyp prüfen (0x0101 = Binding Response)
            val messageType = dis.readUnsignedShort()
            if (messageType != 0x0101) {
                Log.w(TAG, "Ungültiger STUN-Nachrichtentyp: $messageType")
                return null
            }

            // Nachrichtenlänge
            val length = dis.readUnsignedShort()

            // Transaction ID überspringen (16 Bytes)
            dis.skipBytes(16)

            // Attribute parsen
            var publicHost: String? = null
            var publicPort: Int? = null

            while (dis.available() > 0) {
                val attrType = dis.readUnsignedShort()
                val attrLength = dis.readUnsignedShort()

                when (attrType) {
                    0x0001 -> { // MAPPED-ADDRESS
                        dis.readByte() // Family (0x01 = IPv4)
                        val port = dis.readUnsignedShort()
                        val addrBytes = ByteArray(4)
                        dis.readFully(addrBytes)
                        val host = InetAddress.getByAddress(addrBytes).hostAddress
                        publicHost = host
                        publicPort = port
                    }
                    0x0020 -> { // XOR-MAPPED-ADDRESS
                        dis.readByte() // Family
                        val port = dis.readUnsignedShort() xor 0x2112
                        val addrBytes = ByteArray(4)
                        dis.readFully(addrBytes)
                        // XOR mit Magic Cookie (0x2112A442)
                        val magicCookie = intArrayOf(0x21, 0x12, 0xA4, 0x42)
                        for (i in addrBytes.indices) {
                            addrBytes[i] = (addrBytes[i].toInt() xor magicCookie[i]).toByte()
                        }
                        val host = InetAddress.getByAddress(addrBytes).hostAddress
                        publicHost = host
                        publicPort = port
                    }
                    else -> {
                        dis.skipBytes(attrLength)
                    }
                }
            }

            if (publicHost != null && publicPort != null) {
                return PublicAddress(publicHost!!, publicPort!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen der STUN-Antwort: ${e.message}")
        }

        return null
    }

    // =========================================================================
    // Hole-Punching-Nachrichten
    // =========================================================================

    /**
     * Erstellt eine Hole-Punching-Anfrage.
     *
     * Format:
     * - Magic: "CRIX" (4 Bytes)
     * - Typ: 0x01 = Punch Request (1 Byte)
     * - Port: Lokaler TCP-Port (4 Bytes)
     */
    private fun createPunchMessage(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(0x43524958) // "CRIX"
            dos.writeByte(0x01) // Punch Request
            dos.writeInt(localPort) // Lokaler TCP-Port
        }
        return baos.toByteArray()
    }

    /**
     * Erstellt eine Hole-Punching-Antwort.
     */
    private fun createPunchResponse(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(0x43524958) // "CRIX"
            dos.writeByte(0x02) // Punch Response
            dos.writeInt(localPort) // Lokaler TCP-Port
        }
        return baos.toByteArray()
    }

    /**
     * Prüft, ob ein Paket eine gültige Punch-Anfrage ist.
     */
    private fun isValidPunchRequest(packet: DatagramPacket): Boolean {
        if (packet.length < 9) return false
        val data = packet.data
        // Prüfe Magic "CRIX"
        return data[0] == 0x43.toByte() && data[1] == 0x52.toByte() &&
                data[2] == 0x49.toByte() && data[3] == 0x58.toByte() &&
                data[4] == 0x01.toByte() // Punch Request
    }

    /**
     * Prüft, ob ein Paket eine gültige Punch-Antwort ist.
     */
    private fun isValidPunchResponse(packet: DatagramPacket): Boolean {
        if (packet.length < 9) return false
        val data = packet.data
        return data[0] == 0x43.toByte() && data[1] == 0x52.toByte() &&
                data[2] == 0x49.toByte() && data[3] == 0x58.toByte() &&
                data[4] == 0x02.toByte() // Punch Response
    }
}

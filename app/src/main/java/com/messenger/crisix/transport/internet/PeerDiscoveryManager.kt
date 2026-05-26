package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Koordiniert alle 5 Wege der Peer-Discovery für Crisix.
 *
 * ## Die 5 Discovery-Wege (Priorität 1-5)
 *
 * | Priorität | Methode | Reichweite | Sicherheit |
 * |-----------|---------|------------|------------|
 * | **1** | QR-Code Scan | Physische Nähe | Hoch |
 * | **2** | mDNS (WLAN) | Lokales Netzwerk | Mittel |
 * | **3** | Bluetooth LE | 10-30m | Mittel |
 * | **4** | Geheimer Raum-Name | Global (über DHT) | Hoch |
 * | **5** | Manuelle ID-Eingabe | Global | Gering |
 *
 * ## Architektur
 * Der PeerDiscoveryManager koordiniert die verschiedenen Discovery-Mechanismen
 * und bietet eine einheitliche Schnittstelle für die App.
 *
 * ## Verwendung
 * ```kotlin
 * val manager = PeerDiscoveryManager(localPeerId, localPublicKey, localPort)
 * manager.start()
 *
 * // QR-Code scannen
 * manager.addContactViaQR("crisix://contact?key=...&name=...")
 *
 * // Geheimen Raum betreten
 * manager.joinSecretRoom("harry-und-paul-2025")
 *
 * // Manuelle ID-Eingabe
 * manager.connectViaShortId("a3k9m2xq")
 * ```
 */
class PeerDiscoveryManager(
    private val localPeerId: String,
    private val localPublicKey: ByteArray,
    private val localPort: Int
) {
    companion object {
        private const val TAG = "PeerDiscoveryManager"

        /** mDNS-Dienstname für Crisix */
        private const val MDNS_SERVICE = "_crisix._tcp.local"

        /** mDNS-Port */
        private const val MDNS_PORT = 5353

        /** mDNS-Multicast-Adresse */
        private val MDNS_ADDR = InetAddress.getByName("224.0.0.251")

        /** Intervall für mDNS-Ankündigungen (30 Sekunden) */
        private const val MDNS_ANNOUNCE_INTERVAL_MS = 30_000L

        /** Intervall für DHT-Refresh (5 Minuten) */
        private const val DHT_REFRESH_INTERVAL_MS = 300_000L

        /** Timeout für DHT-Anfragen */
        private const val DHT_TIMEOUT_MS = 3000L
    }

    // =========================================================================
    // Datenklassen
    // =========================================================================

    /**
     * Repräsentiert einen entdeckten Kontakt.
     */
    data class DiscoveredContact(
        val peerId: String,
        val publicKey: ByteArray? = null,
        val name: String = "Unknown",
        val shortId: String = "",
        val host: String? = null,
        val port: Int? = null,
        val discoveryMethod: DiscoveryMethod,
        val firstSeen: Long = System.currentTimeMillis(),
        val isConnected: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DiscoveredContact) return false
            return peerId == other.peerId
        }

        override fun hashCode(): Int = peerId.hashCode()
    }

    /**
     * Enum der 5 Discovery-Methoden.
     */
    enum class DiscoveryMethod {
        QR_CODE,
        MDNS,
        BLUETOOTH_LE,
        SECRET_ROOM,
        MANUAL_ID
    }

    // =========================================================================
    // Zustand
    // =========================================================================

    /** Coroutine-Scope für Hintergrundaufgaben */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Entdeckte Kontakte */
    private val _discoveredContacts = MutableStateFlow<List<DiscoveredContact>>(emptyList())
    val discoveredContacts: Flow<List<DiscoveredContact>> = _discoveredContacts.asStateFlow()

    /** DHT-Node für globale Peer-Findung */
    private var hyperswarmDht: HyperswarmDhtNode? = null

    /** mDNS-Ankündigungs-Job */
    private var mdnsAnnounceJob: Job? = null

    /** mDNS-Discovery-Job */
    private var mdnsDiscoveryJob: Job? = null

    /** DHT-Refresh-Job */
    private var dhtRefreshJob: Job? = null

    /** Laufzeitstatus */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Kurz-ID des lokalen Peers */
    val localShortId: String by lazy {
        HyperswarmProtocol.getShortId(localPublicKey)
    }

    /** Bekannte Peers aus geheimen Räumen: topic -> list of peerIds */
    private val secretRoomPeers = ConcurrentHashMap<String, MutableList<String>>()

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    /**
     * Startet alle Discovery-Mechanismen.
     *
     * Initialisiert:
     * 1. Hyperswarm-DHT für globale Peer-Findung
     * 2. mDNS für lokale Peer-Findung
     * 3. Periodische DHT-Refreshs
     */
    suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "PeerDiscoveryManager läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte PeerDiscoveryManager (Peer: $localPeerId, ShortID: $localShortId)")

        // 1. Hyperswarm-DHT starten
        hyperswarmDht = HyperswarmDhtNode(localPeerId, localPublicKey, localPort)
        try {
            hyperswarmDht?.start()
            Log.i(TAG, "Hyperswarm-DHT gestartet")
        } catch (e: Exception) {
            Log.w(TAG, "DHT-Start fehlgeschlagen: ${e.message}")
        }

        // 2. mDNS-Ankündigung starten (alle 30 Sekunden)
        startMdnsAnnounce()

        // 3. mDNS-Discovery starten
        startMdnsDiscovery()

        // 4. Periodischer DHT-Refresh
        startDhtRefresh()

        Log.i(TAG, "PeerDiscoveryManager gestartet")
    }

    /**
     * Stoppt alle Discovery-Mechanismen.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe PeerDiscoveryManager")

        isRunning = false

        // DHT stoppen
        hyperswarmDht?.stop()
        hyperswarmDht = null

        // Jobs beenden
        mdnsAnnounceJob?.cancel()
        mdnsDiscoveryJob?.cancel()
        dhtRefreshJob?.cancel()
        mdnsAnnounceJob = null
        mdnsDiscoveryJob = null
        dhtRefreshJob = null

        Log.i(TAG, "PeerDiscoveryManager gestoppt")
    }

    // =========================================================================
    // Weg 1: QR-Code Scan
    // =========================================================================

    /**
     * Fügt einen Kontakt hinzu, der über QR-Code gescannt wurde.
     *
     * Der QR-Code enthält die Crisix-URI mit dem vollständigen Public Key:
     * `crisix://contact?key=<base64_public_key>&name=<url_encoded_name>`
     *
     * @param crisixUri Die gescannte Crisix-URI
     * @return Der erstellte Kontakt, oder null bei Fehlern
     */
    fun addContactViaQR(crisixUri: String): DiscoveredContact? {
        Log.d(TAG, "Verarbeite QR-Code: $crisixUri")

        val parsed = HyperswarmProtocol.parseCrisixUri(crisixUri) ?: run {
            Log.w(TAG, "Ungültige Crisix-URI: $crisixUri")
            return null
        }

        val (publicKey, name) = parsed
        val peerId = CryptoHelper.publicKeyToFingerprint(publicKey)
        val shortId = HyperswarmProtocol.getShortId(publicKey)

        val contact = DiscoveredContact(
            peerId = peerId,
            publicKey = publicKey,
            name = name,
            shortId = shortId,
            discoveryMethod = DiscoveryMethod.QR_CODE,
            firstSeen = System.currentTimeMillis()
        )

        addContact(contact)

        // Verbindung über DHT initiieren
        scope.launch {
            initiateConnection(peerId)
        }

        Log.i(TAG, "Kontakt via QR-Code hinzugefügt: $name ($shortId)")
        return contact
    }

    /**
     * Erstellt den QR-Code-Inhalt für den lokalen Peer.
     *
     * @return Die Crisix-URI als String
     */
    fun getMyQRCodeContent(): String {
        return HyperswarmProtocol.createCrisixUri(localPublicKey, "Crisix-User")
    }

    /**
     * Erstellt den QR-Code-Inhalt mit benutzerdefiniertem Namen.
     *
     * @param profileName Der Profilname
     * @return Die Crisix-URI als String
     */
    fun getMyQRCodeContent(profileName: String): String {
        return HyperswarmProtocol.createCrisixUri(localPublicKey, profileName)
    }

    // =========================================================================
    // Weg 2: mDNS (Lokales WLAN)
    // =========================================================================

    /**
     * Startet die periodische mDNS-Ankündigung.
     *
     * Sendet alle 30 Sekunden eine mDNS-Ankündigung, damit andere
     * Crisix-Geräte im selben WLAN diesen Peer finden können.
     */
    private fun startMdnsAnnounce() {
        mdnsAnnounceJob = scope.launch {
            while (isRunning) {
                try {
                    announceViaMdns()
                } catch (e: Exception) {
                    Log.w(TAG, "mDNS-Announce fehlgeschlagen: ${e.message}")
                }
                delay(MDNS_ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    /**
     * Sendet eine mDNS-Ankündigung für den Crisix-Dienst.
     */
    private suspend fun announceViaMdns() {
        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.broadcast = true

                try {
                    // mDNS-Ankündigung erstellen
                    val announcement = buildMdnsAnnouncement()
                    val packet = DatagramPacket(announcement, announcement.size, MDNS_ADDR, MDNS_PORT)
                    socket.send(packet)
                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS-Announce fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Startet die mDNS-Discovery.
     *
     * Sendet periodisch mDNS-Abfragen und sammelt Antworten
     * von anderen Crisix-Geräten im selben Netzwerk.
     */
    private fun startMdnsDiscovery() {
        mdnsDiscoveryJob = scope.launch {
            while (isRunning) {
                try {
                    val peers = discoverViaMdns()
                    for (peer in peers) {
                        addContact(peer)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "mDNS-Discovery fehlgeschlagen: ${e.message}")
                }
                delay(MDNS_ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    /**
     * Führt eine mDNS-Suche nach Crisix-Geräten durch.
     *
     * @return Liste der gefundenen Kontakte
     */
    private suspend fun discoverViaMdns(): List<DiscoveredContact> {
        val contacts = mutableListOf<DiscoveredContact>()

        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.soTimeout = 2000
                socket.broadcast = true

                try {
                    // mDNS-Abfrage senden
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
                            val contact = parseMdnsResponse(response)
                            if (contact != null) {
                                contacts.add(contact)
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

        return contacts.distinctBy { it.peerId }
    }

    /**
     * Baut eine mDNS-Abfrage für den Crisix-Dienst.
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
        val parts = MDNS_SERVICE.split(".")
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
     * Baut eine mDNS-Ankündigung für den Crisix-Dienst.
     */
    private fun buildMdnsAnnouncement(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(buffer)

        // DNS-Header (Response)
        dos.writeShort(0x0000) // Transaction ID
        dos.writeShort(0x8400) // Flags: Response, Authoritative
        dos.writeShort(0x0000) // Questions
        dos.writeShort(0x0003) // Answers: 3 (PTR, SRV, TXT)
        dos.writeShort(0x0000) // Authority RRs
        dos.writeShort(0x0000) // Additional RRs

        // 1. PTR-Eintrag: _crisix._tcp.local -> Peer-Name
        val parts = MDNS_SERVICE.split(".")
        for (part in parts) {
            dos.writeByte(part.length)
            dos.write(part.toByteArray())
        }
        dos.writeByte(0x00)
        dos.writeShort(12) // Typ: PTR
        dos.writeShort(0x8001) // Class: IN + cache flush
        dos.writeInt(120) // TTL: 120 Sekunden
        val nameBytes = "$localShortId.$MDNS_SERVICE".toByteArray()
        dos.writeShort(nameBytes.size)
        dos.write(nameBytes)

        // 2. SRV-Eintrag
        for (part in ("$localShortId.$MDNS_SERVICE").split(".")) {
            dos.writeByte(part.length)
            dos.write(part.toByteArray())
        }
        dos.writeByte(0x00)
        dos.writeShort(33) // Typ: SRV
        dos.writeShort(0x8001) // Class: IN + cache flush
        dos.writeInt(120) // TTL
        dos.writeShort(9) // Data length
        dos.writeShort(0) // Priority
        dos.writeShort(0) // Weight
        dos.writeShort(localPort) // Port
        dos.writeByte(0) // Hostname (leer = gleicher Name)

        // 3. TXT-Eintrag mit Peer-Informationen
        for (part in ("$localShortId.$MDNS_SERVICE").split(".")) {
            dos.writeByte(part.length)
            dos.write(part.toByteArray())
        }
        dos.writeByte(0x00)
        dos.writeShort(16) // Typ: TXT
        dos.writeShort(0x8001) // Class: IN + cache flush
        dos.writeInt(120) // TTL
        val txtData = "peer_id=$localPeerId;fingerprint=$localShortId;name=Crisix-User"
        val txtBytes = txtData.toByteArray()
        dos.writeShort(txtBytes.size)
        dos.write(txtBytes)

        dos.flush()
        return buffer.toByteArray()
    }

    /**
     * Parst eine mDNS-Antwort und extrahiert Kontaktinformationen.
     */
    private fun parseMdnsResponse(packet: DatagramPacket): DiscoveredContact? {
        try {
            val data = packet.data
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(data))

            // DNS-Header überspringen
            dis.skipBytes(12)

            // Fragen überspringen
            val questions = dis.readUnsignedShort()
            for (i in 0 until questions) {
                skipDnsName(dis)
                dis.skipBytes(4)
            }

            // Antworten parsen
            val answers = dis.readUnsignedShort()
            var peerId: String? = null
            var host: String? = null
            var port: Int? = null
            var name: String? = null
            var fingerprint: String? = null

            for (i in 0 until answers) {
                skipDnsName(dis)
                val type = dis.readUnsignedShort()
                dis.skipBytes(2) // Class
                dis.skipBytes(4) // TTL
                val dataLength = dis.readUnsignedShort()

                when (type) {
                    12 -> { // PTR
                        skipDnsName(dis)
                    }
                    16 -> { // TXT
                        if (dataLength > 0) {
                            val txtData = ByteArray(dataLength)
                            dis.readFully(txtData)
                            val txtStr = String(txtData, Charsets.UTF_8)
                            for (entry in txtStr.split(";")) {
                                val kv = entry.split("=", limit = 2)
                                if (kv.size == 2) {
                                    when (kv[0].trim()) {
                                        "peer_id" -> peerId = kv[1].trim()
                                        "fingerprint" -> fingerprint = kv[1].trim()
                                        "name" -> name = kv[1].trim()
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
                return DiscoveredContact(
                    peerId = peerId,
                    name = name ?: peerId.take(8),
                    shortId = fingerprint ?: peerId.take(8),
                    host = host,
                    port = port,
                    discoveryMethod = DiscoveryMethod.MDNS,
                    firstSeen = System.currentTimeMillis()
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
                dis.readUnsignedByte()
                break
            }
            dis.skipBytes(len)
        }
    }

    // =========================================================================
    // Weg 3: Bluetooth LE
    // =========================================================================

    /**
     * Fügt einen Kontakt hinzu, der über Bluetooth LE entdeckt wurde.
     *
     * @param peerId Die Peer-ID
     * @param fingerprint Der Fingerprint (8-stellige Kurz-ID)
     * @param name Der Anzeigename (optional)
     * @return Der erstellte Kontakt
     */
    fun addContactViaBLE(peerId: String, fingerprint: String, name: String? = null): DiscoveredContact {
        val contact = DiscoveredContact(
            peerId = peerId,
            name = name ?: fingerprint,
            shortId = fingerprint,
            discoveryMethod = DiscoveryMethod.BLUETOOTH_LE,
            firstSeen = System.currentTimeMillis()
        )

        addContact(contact)

        // Verbindung initiieren
        scope.launch {
            initiateConnection(peerId)
        }

        Log.i(TAG, "Kontakt via BLE hinzugefügt: $fingerprint")
        return contact
    }

    /**
     * Erstellt die BLE-Advertising-Daten für den lokalen Peer.
     *
     * @return Byte-Array mit Peer-ID und Fingerprint
     */
    fun getBLEAdvertiseData(): ByteArray {
        val data = "$localPeerId:$localShortId".toByteArray(Charsets.UTF_8)
        return data
    }

    // =========================================================================
    // Weg 4: Geheimer Raum-Name über DHT
    // =========================================================================

    /**
     * Tritt einem geheimen Raum bei.
     *
     * Zwei Freunde einigen sich persönlich auf einen Raum-Namen,
     * z.B. "harry-und-paul-2025". Beide geben diesen Namen in Crisix ein.
     * Der Raum-Name wird gehasht (SHA-256) und als Topic in der DHT verwendet.
     *
     * **Sicherheit:** Der Raum-Name muss sicher ausgetauscht werden
     * (persönlich, Telefon, Signal). Er wird niemals im Klartext
     * übertragen, nur der SHA-256 Hash.
     *
     * @param roomName Der geheim vereinbarte Raum-Name
     */
    suspend fun joinSecretRoom(roomName: String) {
        Log.i(TAG, "Trete geheimen Raum bei: $roomName")

        val topic = HyperswarmProtocol.sha256(roomName.toByteArray())
        val topicHex = topic.joinToString("") { "%02x".format(it) }

        Log.d(TAG, "Topic-Hash: $topicHex")

        // In der DHT announcen
        hyperswarmDht?.announce(topic, localPeerId)

        // Nach Peers im Raum suchen
        val foundPeers = hyperswarmDht?.findPeersForTopic(topic) ?: emptyList()

        for (peerId in foundPeers) {
            if (peerId != localPeerId) {
                Log.i(TAG, "Peer im geheimen Raum gefunden: $peerId")

                val contact = DiscoveredContact(
                    peerId = peerId,
                    name = "Room: $roomName",
                    shortId = peerId.take(8),
                    discoveryMethod = DiscoveryMethod.SECRET_ROOM,
                    firstSeen = System.currentTimeMillis()
                )

                addContact(contact)

                // Verbindung initiieren
                initiateConnection(peerId)
            }
        }

        // Raum in der Liste der bekannten Räume speichern
        secretRoomPeers[topicHex] = foundPeers.toMutableList()

        // Periodisch nach neuen Peers im Raum suchen
        scope.launch {
            while (isRunning) {
                delay(DHT_REFRESH_INTERVAL_MS)
                try {
                    val newPeers = hyperswarmDht?.findPeersForTopic(topic) ?: emptyList()
                    for (peerId in newPeers) {
                        if (peerId != localPeerId && !foundPeers.contains(peerId)) {
                            Log.i(TAG, "Neuer Peer im Raum $roomName: $peerId")

                            val contact = DiscoveredContact(
                                peerId = peerId,
                                name = "Room: $roomName",
                                shortId = peerId.take(8),
                                discoveryMethod = DiscoveryMethod.SECRET_ROOM,
                                firstSeen = System.currentTimeMillis()
                            )

                            addContact(contact)
                            initiateConnection(peerId)
                        }
                    }
                    secretRoomPeers[topicHex] = newPeers.toMutableList()
                } catch (e: Exception) {
                    Log.w(TAG, "Raum-Suche fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    /**
     * Verlässt einen geheimen Raum.
     *
     * @param roomName Der Raum-Name
     */
    suspend fun leaveSecretRoom(roomName: String) {
        Log.i(TAG, "Verlasse geheimen Raum: $roomName")

        val topic = HyperswarmProtocol.sha256(roomName.toByteArray())
        hyperswarmDht?.unannounce(topic, localPeerId)

        val topicHex = topic.joinToString("") { "%02x".format(it) }
        secretRoomPeers.remove(topicHex)
    }

    // =========================================================================
    // Weg 5: Manuelle ID-Eingabe (Fallback)
    // =========================================================================

    /**
     * Versucht, eine Verbindung über die 8-stellige Kurz-ID herzustellen.
     *
     * Dies ist der Fallback-Weg, wenn QR-Code, mDNS und BLE nicht verfügbar sind.
     * Die Kurz-ID wird in der DHT gesucht.
     *
     * @param shortId Die 8-stellige Kurz-ID (z.B. "a3k9m2xq")
     */
    suspend fun connectViaShortId(shortId: String) {
        Log.i(TAG, "Suche Peer via Kurz-ID: $shortId")

        // In der DHT nach Peers mit diesem Fingerprint suchen
        val candidates = hyperswarmDht?.findPeersByShortId(shortId) ?: emptyList()

        if (candidates.isEmpty()) {
            Log.w(TAG, "Kein Gerät mit der Kurz-ID $shortId gefunden")
            // In einer vollständigen Implementierung würde hier
            // eine UI-Benachrichtigung angezeigt werden
        } else {
            Log.i(TAG, "${candidates.size} Kandidaten für Kurz-ID $shortId gefunden")
            for (peerId in candidates) {
                if (peerId != localPeerId) {
                    val contact = DiscoveredContact(
                        peerId = peerId,
                        name = "Manual: $shortId",
                        shortId = shortId,
                        discoveryMethod = DiscoveryMethod.MANUAL_ID,
                        firstSeen = System.currentTimeMillis()
                    )
                    addContact(contact)
                    initiateConnection(peerId)
                }
            }
        }
    }

    // =========================================================================
    // DHT-Refresh
    // =========================================================================

    /**
     * Startet den periodischen DHT-Refresh.
     */
    private fun startDhtRefresh() {
        dhtRefreshJob = scope.launch {
            while (isRunning) {
                delay(DHT_REFRESH_INTERVAL_MS)
                try {
                    hyperswarmDht?.refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "DHT-Refresh fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    // =========================================================================
    // Hilfsfunktionen
    // =========================================================================

    /**
     * Fügt einen Kontakt zur internen Liste hinzu.
     * Verhindert Duplikate anhand der Peer-ID.
     */
    private fun addContact(contact: DiscoveredContact) {
        val currentList = _discoveredContacts.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.peerId == contact.peerId }

        if (existingIndex >= 0) {
            // Vorhandenen Kontakt aktualisieren (neuere Informationen bevorzugen)
            val existing = currentList[existingIndex]
            val updated = contact.copy(
                firstSeen = existing.firstSeen // Ursprüngliches Datum beibehalten
            )
            currentList[existingIndex] = updated
        } else {
            currentList.add(contact)
        }

        _discoveredContacts.value = currentList
    }

    /**
     * Initiiert eine Verbindung zu einem entdeckten Peer.
     *
     * @param peerId Die Peer-ID des Peers
     */
    private suspend fun initiateConnection(peerId: String) {
        Log.d(TAG, "Initiiere Verbindung zu Peer: $peerId")

        try {
            // Peer in der DHT suchen
            val peerInfo = hyperswarmDht?.findPeer(peerId)
            if (peerInfo != null) {
                Log.i(TAG, "Peer $peerId gefunden: ${peerInfo.host}:${peerInfo.port}")

                // Kontakt mit Host-Informationen aktualisieren
                val currentList = _discoveredContacts.value.toMutableList()
                val index = currentList.indexOfFirst { it.peerId == peerId }
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        host = peerInfo.host,
                        port = peerInfo.port,
                        isConnected = true
                    )
                    _discoveredContacts.value = currentList
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verbindung zu $peerId fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Gibt den Hyperswarm-DHT-Knoten zurück.
     */
    fun getHyperswarmDht(): HyperswarmDhtNode? = hyperswarmDht
}

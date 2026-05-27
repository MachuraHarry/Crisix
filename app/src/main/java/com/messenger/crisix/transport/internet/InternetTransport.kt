package com.messenger.crisix.transport.internet

import android.content.Context
import android.util.Base64
import android.util.Log
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.Transport
import com.messenger.crisix.transport.TransportCapabilities
import com.messenger.crisix.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Internet-Transport für serverlose P2P-Kommunikation über das Internet.
 *
 * Dieser Transport implementiert das [Transport]-Interface und nutzt
 * das Crisix-eigene P2P-Protokoll (TCP-basiert mit Ed25519-Verschlüsselung)
 * für die dezentrale Peer-Findung und verschlüsselte Kommunikation
 * über das Internet. Es werden keine zentralen Server benötigt.
 *
 * ## 🌍 Globale P2P-Kommunikation (über das Internet)
 *
 * Die App findet Peers weltweit über zwei Mechanismen:
 *
 * ### 1. Kademlia DHT (Global)
 * - Jeder Peer registriert sich selbst in der verteilten Hash-Tabelle
 * - Peers können über ihre Peer-ID gesucht werden
 * - Die DHT liefert die aktuelle IP/Port des gesuchten Peers
 * - **Kein zentraler Server nötig** – die DHT ist selbstorganisierend
 *
 * ### 2. NAT-Traversal (Hole Punching)
 * - Die meisten Geräte sind hinter Routern (NATs)
 * - STUN ermittelt die öffentliche IP/Port
 * - UDP Hole Punching öffnet Löcher in den NATs
 * - Danach ist direkte TCP-Kommunikation möglich
 *
 * ### 3. mDNS (Lokal – Fallback)
 * - Nur für Geräte im selben lokalen Netzwerk
 * - Funktioniert auch ohne Internetverbindung
 *
 * ## Ablauf einer Nachricht über das Internet
 * 1. App ruft `send(peerId, data)` auf
 * 2. InternetTransport sucht die IP/Port des Peers:
 *    a. Zuerst in der lokalen Peer-Adress-Tabelle
 *    b. Dann in der DHT (global)
 *    c. Dann via NAT-Traversal (Hole Punching)
 * 3. TCP-Verbindung wird hergestellt
 * 4. Nachricht wird gesendet (verschlüsselt mit Ed25519)
 *
 * ## Architektur
 * - **Peer-Findung**: Kademlia DHT (global) + mDNS (lokal)
 * - **Verschlüsselung**: Ed25519-Signaturen + AES-GCM für Nachrichten
 * - **Transport**: TCP mit Längenpräfix-Protokoll
 * - **NAT-Traversal**: STUN + UDP Hole Punching
 *
 * ## Capabilities
 * - Text: ✅ (unbegrenzte Länge)
 * - Bilder: ✅
 * - Video: ✅
 * - Audio: ✅
 * - Dateien: ✅
 * - Kostenpflichtig: ❌ (nur Internetverbindung nötig)
 *
 * ## Verwendung
 * ```kotlin
 * val internetTransport = InternetTransport()
 * internetTransport.start()
 * internetTransport.send("12D3KooW...", "Hallo".toByteArray())
 * ```
 *
 * @property deviceName Anzeigename des Geräts für die Peer-Erkennung
 */
class InternetTransport(
    private val context: Context,
    private val deviceName: String = "Crisix-${android.os.Build.MODEL}"
) : Transport {

    override val type: TransportType = TransportType.INTERNET
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false
    )

    private val TAG = "InternetTransport"

    /** Coroutine-Scope für Hintergrundaufgaben */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Job für den Nachrichtenempfangs-Loop */
    private var receiveJob: Job? = null

    /** Job für den Reconnect-Mechanismus */
    private var reconnectJob: Job? = null

    /** Laufzeitstatus */
    @Volatile
    private var isRunning = false

    /** Eigene libp2p-Peer-ID (Fingerprint) - gesetzt von Libp2pManager */
    private var localPeerId: String? = null

    /** Privater Schlüssel für Identität - persistent über App-Starts hinweg */
    private var privateKey: ByteArray = ByteArray(0)


    /** Peer-Discovery-Instanz */
    private var peerDiscovery: PeerDiscovery? = null

    /** NatTraversal-Instanz (wiederverwendbar) */
    private var natTraversal: NatTraversal? = null

    /** SharedFlow für entdeckte Peers */
    private val _discoveredPeers = MutableSharedFlow<Peer>(replay = 0, extraBufferCapacity = 64)
    override fun discoverPeers(): Flow<Peer> = _discoveredPeers.asSharedFlow()

    /** Registrierte Listener für eingehende Nachrichten */
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    /** Channel für eingehende Nachrichten (für den Receive-Loop) */
    private val incomingMessageChannel = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    /** Verbundene Peers: peerId -> Stream-Status */
    private val connectedPeers = ConcurrentHashMap<String, Boolean>()

    /**
     * Peer-Adress-Registry: Mapped Peer-IDs auf IP/Port.
     *
     * Diese Registry wird befüllt durch:
     * - Eingehende Verbindungen (wir erfahren die IP des Gegenübers)
     * - DHT-Suche (globale Peer-Findung)
     * - mDNS (lokale Peer-Findung)
     * - Manuelle Eingabe (Benutzer gibt IP ein)
     *
     * Wichtig: Die IP/Port kann sich ändern (mobiles Internet).
     * Daher wird bei jedem Sendeversuch zuerst die DHT gefragt.
     */
    private val peerAddressRegistry = ConcurrentHashMap<String, RemotePeerInfo>()

    /** Initialisiere den Receive-Loop direkt im Konstruktor */
    init {
        startReceiveLoop()
    }

    /**
     * Prüft, ob der Internet-Transport verfügbar ist.
     *
     * Der Transport ist verfügbar, wenn:
     * 1. Eine Netzwerkschnittstelle aktiv ist (nicht Loopback, nicht Dummy)
     * 2. Internet wirklich erreichbar ist (Socket-Verbindung zu einem bekannten Host)
     *
     * @return true wenn Internet-Kommunikation möglich ist
     */
    override suspend fun isAvailable(): Boolean {
        return try {
            // Schritt 1: Prüfe, ob ein gültiges Netzwerk-Interface existiert
            var hasValidInterface = false
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val ifName = networkInterface.name.lowercase()
                    // Dummy/TUN-Interfaces ignorieren (z.B. rmnet_data im Flugmodus)
                    if (ifName.startsWith("rmnet") || ifName.startsWith("tun") || ifName.startsWith("dummy")) {
                        continue
                    }
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            hasValidInterface = true
                            break
                        }
                    }
                }
            }
            if (!hasValidInterface) return false

            // Schritt 2: Prüfe, ob Internet wirklich erreichbar ist
            // Versuche eine Socket-Verbindung zu einem bekannten DNS-Server (Google 8.8.8.8:53)
            // Timeout: 2 Sekunden – kurz genug für regelmäßige Prüfungen
            return try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000)
                socket.close()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Internet nicht erreichbar (8.8.8.8:53): ${e.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei isAvailable: ${e.message}")
            false
        }
    }

    /**
     * Sendet eine Nachricht an einen Peer über das Internet.
     *
     * ## 🌍 Ablauf für globale P2P-Kommunikation
     *
     * 1. **Lokale Registry prüfen** – Ist die IP/Port des Peers bereits bekannt?
     * 2. **DHT-Suche** – Peer in der globalen verteilten Hash-Tabelle suchen
     * 3. **NAT-Traversal** – Wenn direkte Verbindung fehlschlägt, Hole Punching versuchen
     * 4. **TCP-Verbindung** – Nachricht über verschlüsselten Stream senden
     *
     * Die Peer-ID ist ein kryptografischer Fingerprint (z.B. "12D3KooW...").
     * Die IP/Port wird dynamisch über die DHT ermittelt – kein zentraler Server nötig.
     *
     * @param peerId Die Peer-ID des Empfängers (z.B. "12D3KooW...")
     * @param data Die zu sendenden Daten als Byte-Array
     * @return Result.success bei Erfolg, Result.failure bei Fehler
     */
    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        if (!isRunning) {
            return Result.failure(IllegalStateException("InternetTransport nicht gestartet"))
        }

        return try {
            val senderId = localPeerId ?: deviceName

            // Crisix-Nachricht erstellen und kodieren
            val message = CrisixProtocol.CrisixMessage(
                messageId = UUID.randomUUID().toString(),
                senderId = senderId,
                recipientId = peerId,
                type = CrisixProtocol.MessageType.CHAT_MESSAGE,
                payload = data,
                timestamp = System.currentTimeMillis()
            )

            val encodedMessage = CrisixProtocol.encodeMessage(message)

            // === Schritt 1: IP/Port des Peers ermitteln ===
            val peerAddress = resolvePeerAddress(peerId)
            if (peerAddress == null) {
                Log.e(TAG, "Peer $peerId nicht gefunden (weder lokal noch in DHT)")
                return Result.failure(Exception("Peer $peerId nicht gefunden – IP/Port unbekannt"))
            }

            Log.d(TAG, "Sende an Peer $peerId → ${peerAddress.host}:${peerAddress.port}")

            // === Schritt 2: NAT-Traversal (falls nötig) ===
            // Prüfe, ob direkte Verbindung möglich ist
            // Verwende die wiederverwendbare NatTraversal-Instanz
            val nat = natTraversal
            if (nat != null) {
                val directOk = nat.testDirectConnection(peerAddress.host, peerAddress.port)
                if (!directOk) {
                    Log.d(TAG, "Direkte Verbindung zu $peerId fehlgeschlagen, versuche Hole Punching...")
                    nat.performHolePunching(peerAddress.host, peerAddress.port)
                    // Kurz warten, damit NAT-Löcher geöffnet werden
                    delay(500)
                }
            }

            // === Schritt 3: TCP-Verbindung herstellen ===
            val stream = Libp2pManager.connectToPeer(peerAddress.host, peerAddress.port)
            if (stream == null) {
                return Result.failure(Exception("Verbindung zu $peerId (${peerAddress.host}:${peerAddress.port}) fehlgeschlagen"))
            }

            // === Schritt 4: Nachricht senden ===
            Libp2pManager.sendMessage(stream, encodedMessage)

            // Peer als verbunden markieren
            connectedPeers[peerId] = true
            peerAddressRegistry[peerId] = peerAddress

            Log.d(TAG, "Nachricht (${data.size} Bytes) an $peerId gesendet")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Senden an $peerId fehlgeschlagen: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Ermittelt die IP/Port eines Peers anhand seiner Peer-ID.
     *
     * Suchreihenfolge:
     * 1. **Lokale Registry** – Bereits bekannte Peers (aus vorherigen Verbindungen)
     * 2. **DHT (global)** – Kademlia Distributed Hash Table
     * 3. **mDNS (lokal)** – Nur für Geräte im selben Netzwerk
     *
     * @param peerId Die Peer-ID des gesuchten Peers
     * @return RemotePeerInfo mit IP/Port, oder null wenn nicht gefunden
     */
    private suspend fun resolvePeerAddress(peerId: String): RemotePeerInfo? {
        // 1. Lokale Registry prüfen
        peerAddressRegistry[peerId]?.let { info ->
            Log.d(TAG, "Peer $peerId in lokaler Registry gefunden: ${info.host}:${info.port}")
            return info
        }

        // 2. In der DHT suchen (globale Peer-Findung)
        val discovery = peerDiscovery
        if (discovery != null) {
            Log.d(TAG, "Suche Peer $peerId in der DHT...")
            try {
                val dhtPeerInfo = discovery.findPeer(peerId)
                if (dhtPeerInfo != null) {
                    Log.i(TAG, "Peer $peerId in der DHT gefunden: ${dhtPeerInfo.host}:${dhtPeerInfo.port}")
                    peerAddressRegistry[peerId] = dhtPeerInfo
                    return dhtPeerInfo
                }
            } catch (e: Exception) {
                Log.w(TAG, "DHT-Suche nach $peerId fehlgeschlagen: ${e.message}")
            }
        } else {
            Log.d(TAG, "PeerDiscovery noch nicht initialisiert, überspringe DHT-Suche")
        }

        // 3. Prüfen, ob die peerId selbst eine IP:Port ist (für manuelle Eingabe)
        val parts = peerId.split(":")
        if (parts.size == 2) {
            val host = parts[0]
            val port = parts[1].toIntOrNull()
            if (port != null && port > 0 && port < 65536) {
                Log.d(TAG, "Peer $peerId als IP:Port erkannt")
                val info = RemotePeerInfo(peerId = peerId, host = host, port = port)
                peerAddressRegistry[peerId] = info
                return info
            }
        }

        return null
    }

    /**
     * Registriert einen Listener für eingehende Nachrichten.
     *
     * Der Listener wird bei jeder eingehenden Nachricht aufgerufen.
     * Die Parameter sind (peerId, data).
     *
     * @param listener Die Callback-Funktion für eingehende Nachrichten
     */
    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Startet den Internet-Transport.
     *
     * Initialisiert:
     * 1. P2P-Manager (TCP-Server, Ed25519-Schlüssel)
     * 2. Peer-Discovery (DHT + mDNS)
     * 3. Nachrichtenempfangs-Loop
     * 4. Reconnect-Mechanismus
     */
    override suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "InternetTransport bereits gestartet")
            return
        }

        Log.i(TAG, "Starte InternetTransport (Gerät: $deviceName)")

        try {
            // 1. Schlüsselpaar laden oder neu generieren
            val prefs = context.getSharedPreferences("crisix_identity", Context.MODE_PRIVATE)
            val savedKeyBase64 = prefs.getString("private_key", null)
            
            val keyPair = if (savedKeyBase64 != null) {
                Log.d(TAG, "Lade gespeichertes Ed25519-Schlüsselpaar")
                val keyBytes = Base64.decode(savedKeyBase64, Base64.DEFAULT)
                CryptoHelper.keyPairFromBytes(keyBytes)
            } else {
                Log.i(TAG, "Generiere neues Ed25519-Schlüsselpaar")
                val newKeyPair = CryptoHelper.generateKeyPair()
                val keyBytes = CryptoHelper.keyPairToBytes(newKeyPair)
                val fingerprint = CryptoHelper.publicKeyToFingerprint(newKeyPair.publicKey)
                prefs.edit()
                    .putString("private_key", Base64.encodeToString(keyBytes, Base64.DEFAULT))
                    .putString("fingerprint", fingerprint)
                    .apply()
                newKeyPair
            }

            privateKey = CryptoHelper.keyPairToBytes(keyPair)

            // 2. P2P-Manager starten
            // Der deviceName wird als Platzhalter übergeben, aber Libp2pManager
            // setzt localPeerId IMMER als Fingerprint des Public Keys.
            Libp2pManager.start(deviceName, privateKey)
            localPeerId = Libp2pManager.localPeerId
            val localAddress = Libp2pManager.getLocalAddress()

            Log.i(TAG, "Lokale Peer-ID: $localPeerId")
            Log.i(TAG, "Lokale Adresse: $localAddress")

            // 3. NAT-Traversal initialisieren (wiederverwendbar)
            natTraversal = NatTraversal(Libp2pManager.localPort)

            // 5. Peer-Discovery starten (mit DHT + mDNS + NAT-Traversal)
            peerDiscovery = PeerDiscovery()
            peerDiscovery?.start(localPeerId ?: deviceName, keyPair.publicKey, Libp2pManager.localPort)

            // 6. Eingehende Verbindungen registrieren
            Libp2pManager.setOnIncomingConnection { stream ->
                Log.d(TAG, "Eingehende Verbindung von Peer: ${stream.peerId}")

                // WICHTIG: Die IP des Peers sofort in der Registry speichern!
                // Der Peer hat sich von seiner IP aus verbunden – das ist seine
                // erreichbare Adresse. So kann der Emulator (mit NAT-IP) zurück
                // zum echten Gerät verbinden.
                val peerHost = stream.socket.inetAddress.hostAddress ?: "unknown"
                val peerPort = stream.socket.port
                registerPeerAddress(stream.peerId, peerHost, peerPort)
                connectedPeers[stream.peerId] = true

                Log.i(TAG, "Peer-Adresse aus eingehender Verbindung gespeichert: ${stream.peerId} -> $peerHost:$peerPort")

                // Nachrichten von diesem Stream im Hintergrund lesen
                scope.launch {
                    readMessagesFromStream(stream)
                }
            }

            // 7. Reconnect-Mechanismus starten
            startReconnectLoop()

            isRunning = true
            Log.i(TAG, "InternetTransport gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten des InternetTransport: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Liest kontinuierlich Nachrichten aus einem PeerStream.
     *
     * @param stream Der Stream, aus dem gelesen werden soll
     */
    private suspend fun readMessagesFromStream(stream: PeerStream) {
        try {
            while (stream.isOpen) {
                val data = Libp2pManager.readMessage(stream)
                if (data != null) {
                    incomingMessageChannel.send(Pair(stream.peerId, data))
                } else {
                    // Stream geschlossen oder Fehler
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Lesen von ${stream.peerId}: ${e.message}")
        } finally {
            connectedPeers[stream.peerId] = false
            Log.d(TAG, "Stream zu ${stream.peerId} geschlossen")
        }
    }

    /**
     * Startet den Nachrichtenempfangs-Loop.
     *
     * Lauscht auf eingehende Nachrichten und leitet
     * sie an die registrierten Listener weiter.
     */
    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            Log.d(TAG, "Receive-Loop gestartet")

            for ((peerId, data) in incomingMessageChannel) {
                try {
                    // Nachricht dekodieren
                    val message = CrisixProtocol.decodeMessage(data)
                    if (message != null) {
                        Log.d(TAG, "Nachricht empfangen von ${message.senderId}: ${message.type}")

                        // Nur CHAT_MESSAGE an die App-Listener weiterleiten
                        // ACK, PING, PONG, TYPING sind reine Transport-Protokollnachrichten
                        if (message.type == CrisixProtocol.MessageType.CHAT_MESSAGE) {
                            listeners.forEach { listener ->
                                try {
                                    listener(message.senderId, message.payload)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Listener-Fehler: ${e.message}")
                                }
                            }
                        }

                        // ACK senden (außer für ACKs selbst)
                        if (message.type != CrisixProtocol.MessageType.ACK) {
                            sendAck(message)
                        }
                    } else {
                        Log.w(TAG, "Konnte Nachricht nicht dekodieren")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler bei Nachrichtenverarbeitung: ${e.message}")
                }
            }
        }
    }

    /**
     * Startet den Reconnect-Mechanismus.
     *
     * Versucht periodisch, die Verbindung zu verlorenen Peers
     * wiederherzustellen. Dies ist wichtig für mobile Geräte,
     * die häufig die Netzwerkverbindung wechseln.
     */
    private fun startReconnectLoop() {
        reconnectJob = scope.launch {
            val reconnectIntervalMs = 30_000L // 30 Sekunden

            while (isActive) {
                delay(reconnectIntervalMs)

                if (!isRunning) break

                // Versuche, Verbindungen zu bekannten Peers wiederherzustellen
                val disconnectedPeers = connectedPeers.filter { !it.value }.keys
                if (disconnectedPeers.isNotEmpty()) {
                    Log.d(TAG, "Reconnect-Versuch für ${disconnectedPeers.size} Peers")
                    for (peerId in disconnectedPeers) {
                        try {
                            // Versuche, die Peer-Adresse erneut aufzulösen
                            val peerAddress = resolvePeerAddress(peerId)
                            if (peerAddress != null) {
                                val stream = Libp2pManager.connectToPeer(peerAddress.host, peerAddress.port)
                                if (stream != null) {
                                    connectedPeers[peerId] = true
                                    Log.i(TAG, "Reconnect zu $peerId erfolgreich")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Reconnect zu $peerId fehlgeschlagen: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Sendet eine ACK-Bestätigung für eine empfangene Nachricht.
     *
     * @param message Die empfangene Nachricht, die bestätigt werden soll
     */
    private suspend fun sendAck(message: CrisixProtocol.CrisixMessage) {
        try {
            val senderId = localPeerId ?: deviceName
            val ack = CrisixProtocol.createAck(message, senderId)
            val encodedAck = CrisixProtocol.encodeMessage(ack)

            // Sende ACK zurück an den Sender
            val peerAddress = resolvePeerAddress(message.senderId)
            if (peerAddress != null) {
                val stream = Libp2pManager.connectToPeer(peerAddress.host, peerAddress.port)
                if (stream != null) {
                    Libp2pManager.sendMessage(stream, encodedAck)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACK senden fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Wird bei eingehenden Nachrichten aufgerufen.
     *
     * Leitet die Nachricht direkt an den Channel weiter,
     * wo sie vom Receive-Loop verarbeitet wird.
     *
     * @param peerId Die Peer-ID des Senders
     * @param data Die empfangenen Daten
     */
    internal fun onIncomingMessage(peerId: String, data: ByteArray) {
        incomingMessageChannel.trySend(Pair(peerId, data))
    }

    /**
     * Verarbeitet eine eingehende Nachricht direkt (für Tests).
     *
     * Diese Methode dekodiert die Nachricht und ruft alle
     * registrierten Listener auf. Sie wird vom Receive-Loop
     * aufgerufen, kann aber auch direkt für Tests verwendet werden.
     *
     * @param peerId Die Peer-ID des Senders
     * @param data Die empfangenen Daten
     */
    internal fun processIncomingMessage(peerId: String, data: ByteArray) {
        val message = CrisixProtocol.decodeMessage(data)
        if (message != null) {
            listeners.forEach { listener ->
                try {
                    listener(message.senderId, message.payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener-Fehler: ${e.message}")
                }
            }
        }
    }

    /**
     * Stellt eine manuelle Verbindung zu einem Peer über IP-Adresse her.
     * Verbindet sich über libp2p (TCP) zum Peer, führt Handshake durch
     * und speichert die Peer-Informationen für spätere Kommunikation.
     *
     * @param ipAddress Die IP-Adresse des Peers (optional mit Port, z.B. "192.168.178.51:43155")
     * @param displayName Optionaler Anzeigename für den Peer
     * @return Result mit dem Peer-Objekt bei Erfolg
     */
    suspend fun connectToPeer(ipAddress: String, displayName: String? = null): Result<Peer> {
        return try {
            // IP und Port parsen
            val (host, port) = if (ipAddress.contains(":")) {
                val parts = ipAddress.split(":")
                parts[0] to (parts[1].toIntOrNull() ?: Libp2pManager.localPort)
            } else {
                ipAddress to Libp2pManager.localPort
            }

            Log.i(TAG, "Verbinde zu Peer über Internet: $host:$port")

            // TCP-Verbindung über libp2p herstellen
            val stream = Libp2pManager.connectToPeer(host, port)
                ?: return Result.failure(Exception("Verbindung zu $host:$port fehlgeschlagen"))

            val remotePeerId = stream.peerId
            val peerName = displayName ?: remotePeerId.take(8)

            // Peer in der Address-Registry speichern
            val peerInfo = RemotePeerInfo(
                peerId = remotePeerId,
                host = host,
                port = port,
                isConnected = true
            )
            peerAddressRegistry[remotePeerId] = peerInfo
            connectedPeers[remotePeerId] = true

            // Peer zu den entdeckten Peers hinzufügen
            val peer = Peer(remotePeerId, peerName)
            _discoveredPeers.tryEmit(peer)

            // Nachrichten von diesem Stream lesen
            scope.launch {
                readMessagesFromStream(stream)
            }

            Log.i(TAG, "Manuelle Verbindung zu $peerName ($remotePeerId) über Internet hergestellt")
            Result.success(peer)
        } catch (e: Exception) {
            Log.e(TAG, "Manuelle Internet-Verbindung fehlgeschlagen: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Stellt eine Verbindung zu einem Peer über seine Peer-ID (Fingerprint) her.
     *
     * ## Serverlose Vision (oberste Priorität)
     * Der Fingerprint wird in der globalen Kademlia DHT gesucht.
     * Die DHT liefert die aktuelle IP/Port des Peers – kein zentraler Server nötig!
     *
     * ## Ablauf
     * 1. Peer in der DHT suchen (über Fingerprint)
     * 2. TCP-Verbindung zur gefundenen IP/Port herstellen
     * 3. Handshake durchführen und Peer-Informationen speichern
     *
     * @param peerId Der kryptografische Fingerprint des Peers (z.B. "12D3KooW...")
     * @param displayName Optionaler Anzeigename für den Peer
     * @return Result mit dem Peer-Objekt bei Erfolg
     */
    suspend fun connectToPeerById(peerId: String, displayName: String? = null): Result<Peer> {
        return try {
            Log.i(TAG, "Suche Peer $peerId in der DHT...")

            // 1. Peer in der DHT suchen
            val discovery = peerDiscovery
            if (discovery == null) {
                return Result.failure(Exception("PeerDiscovery nicht initialisiert"))
            }

            val dhtPeerInfo = discovery.findPeer(peerId)
            if (dhtPeerInfo == null) {
                return Result.failure(Exception("Peer $peerId nicht in der DHT gefunden"))
            }

            Log.i(TAG, "Peer $peerId in der DHT gefunden: ${dhtPeerInfo.host}:${dhtPeerInfo.port}")

            // 2. NAT-Traversal (falls nötig)
            val nat = natTraversal
            if (nat != null) {
                val directOk = nat.testDirectConnection(dhtPeerInfo.host, dhtPeerInfo.port)
                if (!directOk) {
                    Log.d(TAG, "Direkte Verbindung fehlgeschlagen, versuche Hole Punching...")
                    nat.performHolePunching(dhtPeerInfo.host, dhtPeerInfo.port)
                    delay(500)
                }
            }

            // 3. TCP-Verbindung herstellen
            val stream = Libp2pManager.connectToPeer(dhtPeerInfo.host, dhtPeerInfo.port)
                ?: return Result.failure(Exception("Verbindung zu ${dhtPeerInfo.host}:${dhtPeerInfo.port} fehlgeschlagen"))

            val remotePeerId = stream.peerId
            val peerName = displayName ?: remotePeerId.take(8)

            // Peer in der Address-Registry speichern
            val peerInfo = RemotePeerInfo(
                peerId = remotePeerId,
                host = dhtPeerInfo.host,
                port = dhtPeerInfo.port,
                isConnected = true
            )
            peerAddressRegistry[remotePeerId] = peerInfo
            connectedPeers[remotePeerId] = true

            // Peer zu den entdeckten Peers hinzufügen
            val peer = Peer(remotePeerId, peerName)
            _discoveredPeers.tryEmit(peer)

            // Nachrichten von diesem Stream lesen
            scope.launch {
                readMessagesFromStream(stream)
            }

            Log.i(TAG, "✅ DHT-Verbindung zu $peerName ($remotePeerId) hergestellt")
            Result.success(peer)
        } catch (e: Exception) {
            Log.e(TAG, "DHT-Verbindung zu $peerId fehlgeschlagen: ${e.message}")
            Result.failure(e)
        }
    }


    /**
     * Registriert eine Peer-Adresse in der Address-Registry.
     * Diese Methode wird verwendet, um die Peer-ID aus einem QR-Code
     * mit der IP/Port des Peers zu verknüpfen, damit sendMessage()
     * die Adresse finden kann.
     *
     * @param peerId Die Peer-ID (Fingerprint aus QR-Code)
     * @param host Die IP-Adresse des Peers
     * @param port Der Port des Peers
     */
    fun registerPeerAddress(peerId: String, host: String, port: Int) {
        val info = RemotePeerInfo(
            peerId = peerId,
            host = host,
            port = port,
            isConnected = true
        )
        peerAddressRegistry[peerId] = info
        Log.d(TAG, "Peer-Adresse registriert: $peerId -> $host:$port")
    }

    /**
     * Gibt detaillierten Status für die UI zurück.
     * Zeigt die Anzahl der verbundenen Peers und den DHT-Status.
     */
    override fun getStatusDetail(): Pair<Int, String> {
        val peerCount = connectedPeers.count { it.value }
        val dhtStatus = peerDiscovery?.let { discovery ->
            val dhtNodeCount = discovery.dhtNode?.knownNodesCount ?: 0
            if (dhtNodeCount > 0) "$dhtNodeCount DHT-Knoten" else "DHT aktiv"
        } ?: "DHT initialisiert..."
        return Pair(peerCount, dhtStatus)
    }

    /**
     * Stoppt den Internet-Transport.
     *
     * Fährt alle Komponenten herunter:
     * 1. Peer-Discovery stoppen
     * 2. Receive-Loop beenden
     * 3. Reconnect-Mechanismus beenden
     * 4. P2P-Manager stoppen
     */
    override suspend fun stop() {

        if (!isRunning) return

        Log.i(TAG, "Stoppe InternetTransport")

        isRunning = false

        // Peer-Discovery stoppen
        peerDiscovery?.stop()
        peerDiscovery = null

        // Jobs beenden
        receiveJob?.cancel()
        reconnectJob?.cancel()
        receiveJob = null
        reconnectJob = null

        // P2P-Manager stoppen
        Libp2pManager.stop()

        // Aufräumen
        listeners.clear()
        connectedPeers.clear()

        Log.i(TAG, "InternetTransport gestoppt")
    }
}

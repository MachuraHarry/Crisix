package com.messenger.crisix.transport.internet

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Serverloser Peer-Relay für Crisix.
 *
 * ## Übersicht
 * Wenn zwei Peers (A und B) keine direkte Verbindung herstellen können
 * (z.B. wegen symmetrischen NATs), sucht sich A einen dritten Peer C,
 * der als Relay fungiert. C leitet die Nachrichten zwischen A und B weiter.
 *
 * ## Wichtig: Kein eigener Server!
 * Der Relay ist ein **anderer Crisix-Nutzer**, der gerade online ist.
 * Es wird kein eigener Server betrieben – alles läuft über Peers.
 *
 * ## Sicherheit
 * Der Relay-Peer C kann die Nachrichten **nicht lesen**.
 * A und B verschlüsseln ihre Nachrichten Ende-zu-Ende mit Noise.
 * C sieht nur verschlüsselte Bytes, die er weiterleitet.
 *
 * ## Ablauf
 * 1. A will mit B reden, aber TCP + Hole-Punching scheitern
 * 2. A sucht in der DHT nach einem Relay-Peer C
 * 3. A fragt C: "Kannst du für mich und B relayen?"
 * 4. C akzeptiert und baut Verbindungen zu A und B auf
 * 5. A sendet Nachrichten an C, C leitet sie an B weiter (und umgekehrt)
 *
 * ## Verwendung
 * ```kotlin
 * val relay = PeerRelay(localPeerId, localPublicKey, localPrivateKey)
 * relay.start()
 *
 * // Als Relay anbieten
 * relay.offerRelayService()
 *
 * // Relay für Verbindung zu Peer anfragen
 * val sessionId = relay.requestRelay(targetPeerId)
 * ```
 */
class PeerRelay(
    private val localPeerId: String,
    private val localPublicKey: ByteArray,
    private val localPrivateKey: ByteArray
) {
    companion object {
        private const val TAG = "PeerRelay"

        /** Port für Relay-Verbindungen */
        const val RELAY_PORT = 49739

        /** Timeout für Relay-Anfragen */
        private const val RELAY_TIMEOUT_MS = 10000L

        /** Maximale Anzahl gleichzeitiger Relay-Sessions */
        private const val MAX_SESSIONS = 10

        /** Maximale Nachrichtengröße für Relay */
        private const val MAX_MESSAGE_SIZE = 65536

        /** Intervall für Relay-Heartbeat */
        private const val RELAY_HEARTBEAT_MS = 30000L
    }

    // =========================================================================
    // Datenklassen
    // =========================================================================

    /**
     * Status einer Relay-Session.
     */
    enum class RelaySessionState {
        /** Session wird aufgebaut */
        SETUP,
        /** Session ist aktiv – Nachrichten werden weitergeleitet */
        ACTIVE,
        /** Session wurde beendet */
        CLOSED
    }

    /**
     * Repräsentiert eine aktive Relay-Session.
     *
     * @property sessionId Eindeutige ID der Session
     * @property peerA Der Initiator (fragt Relay an)
     * @property peerB Der Ziel-Peer
     * @property state Aktueller Status
     * @property createdAt Zeitpunkt der Erstellung
     */
    data class RelaySession(
        val sessionId: String,
        val peerA: String,
        val peerB: String,
        val state: RelaySessionState = RelaySessionState.SETUP,
        val createdAt: Long = System.currentTimeMillis(),
        val bytesRelayed: Long = 0L
    )

    /**
     * Nachricht, die über den Relay weitergeleitet werden soll.
     */
    data class RelayMessage(
        val sessionId: String,
        val fromPeer: String,
        val toPeer: String,
        val data: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    // =========================================================================
    // Zustand
    // =========================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** TCP-Server-Socket für Relay-Verbindungen */
    private var relayServerSocket: ServerSocket? = null

    /** Aktive Relay-Sessions: sessionId -> RelaySession */
    private val relaySessions = ConcurrentHashMap<String, RelaySession>()

    /** TCP-Verbindungen für Sessions: sessionId -> Socket (pro Peer) */
    private val sessionConnections = ConcurrentHashMap<String, ConcurrentHashMap<String, Socket>>()

    /** Lese-Jobs pro Session/Peer */
    private val sessionReadJobs = ConcurrentHashMap<String, Job>()

    /** Ob dieser Peer Relay-Dienste anbietet */
    @Volatile
    var isRelayServiceActive: Boolean = false
        private set

    /** Laufzeitstatus */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Aktive Sessions als Flow */
    private val _activeSessions = MutableStateFlow<List<RelaySession>>(emptyList())
    val activeSessions: Flow<List<RelaySession>> = _activeSessions.asStateFlow()

    /** Callback für eingehende Relay-Nachrichten (für den Empfänger) */
    var onRelayMessageReceived: ((sessionId: String, fromPeer: String, data: ByteArray) -> Unit)? = null

    /** Callback wenn eine Relay-Session erstellt wurde */
    var onRelaySessionCreated: ((session: RelaySession) -> Unit)? = null

    // =========================================================================
    // Lebenszyklus
    // =========================================================================

    /**
     * Startet den PeerRelay.
     * Öffnet einen TCP-Server-Socket für eingehende Relay-Verbindungen.
     */
    suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "PeerRelay läuft bereits")
            return
        }
        isRunning = true

        Log.i(TAG, "Starte PeerRelay auf Port $RELAY_PORT")

        try {
            relayServerSocket = ServerSocket(RELAY_PORT)
            relayServerSocket?.soTimeout = 5000

            // Eingehende Relay-Verbindungen akzeptieren
            scope.launch { acceptRelayConnections() }

            Log.i(TAG, "PeerRelay gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten: ${e.message}", e)
            isRunning = false
            throw e
        }
    }

    /**
     * Stoppt den PeerRelay und schließt alle Sessions.
     */
    suspend fun stop() {
        if (!isRunning) return

        Log.i(TAG, "Stoppe PeerRelay")

        isRunning = false
        isRelayServiceActive = false

        // Alle Sessions schließen
        for (sessionId in relaySessions.keys) {
            closeSession(sessionId)
        }

        relayServerSocket?.close()
        relayServerSocket = null

        Log.i(TAG, "PeerRelay gestoppt")
    }

    // =========================================================================
    // Relay-Dienst anbieten
    // =========================================================================

    /**
     * Bietet diesen Peer als Relay für andere an.
     *
     * Der Peer registriert sich in der DHT als verfügbarer Relay.
     * Andere Peers können ihn dann als Relay anfragen.
     */
    fun offerRelayService() {
        isRelayServiceActive = true
        Log.i(TAG, "Biete Relay-Dienst an (Peer: $localPeerId)")

        // In einer vollständigen Implementierung würde man sich hier
        // in der DHT als Relay registrieren, z.B. unter dem Topic
        // "crisix-relay-available"
    }

    /**
     * Zieht das Relay-Angebot zurück.
     */
    fun withdrawRelayService() {
        isRelayServiceActive = false
        Log.i(TAG, "Relay-Dienst zurückgezogen")
    }

    // =========================================================================
    // Relay anfragen (für Client A)
    // =========================================================================

    /**
     * Fragt einen Relay für die Verbindung zu einem Ziel-Peer an.
     *
     * @param relayPeerId Die Peer-ID des Relay-Peers
     * @param relayHost Die IP des Relay-Peers
     * @param relayPort Der Port des Relay-Peers
     * @param targetPeerId Der Ziel-Peer, zu dem die Verbindung hergestellt werden soll
     * @return Die Session-ID, oder null bei Fehlschlag
     */
    suspend fun requestRelay(
        relayPeerId: String,
        relayHost: String,
        relayPort: Int = RELAY_PORT,
        targetPeerId: String
    ): String? {
        Log.i(TAG, "Fordere Relay an: $localPeerId -> $relayPeerId -> $targetPeerId")

        return try {
            withTimeout(RELAY_TIMEOUT_MS) {
                val socket = withContext(Dispatchers.IO) {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(relayHost, relayPort), 5000)
                    sock.soTimeout = 30000
                    sock
                }

                // Relay-Anfrage senden
                // Format: "RELAY_REQUEST|localPeerId|targetPeerId"
                val requestMsg = "RELAY_REQUEST|$localPeerId|$targetPeerId"
                val outputStream = socket.getOutputStream()
                outputStream.write(requestMsg.toByteArray(Charsets.UTF_8))
                outputStream.flush()

                // Antwort empfangen
                val buffer = ByteArray(256)
                val bytesRead = socket.getInputStream().read(buffer)
                val response = String(buffer, 0, bytesRead, Charsets.UTF_8)

                if (response.startsWith("RELAY_ACCEPT|")) {
                    val sessionId = response.removePrefix("RELAY_ACCEPT|").trim()
                    Log.i(TAG, "Relay akzeptiert: Session $sessionId")

                    // Verbindung für diese Session speichern
                    val peerMap = ConcurrentHashMap<String, Socket>()
                    peerMap[localPeerId] = socket
                    sessionConnections[sessionId] = peerMap

                    // Session erstellen
                    val session = RelaySession(
                        sessionId = sessionId,
                        peerA = localPeerId,
                        peerB = targetPeerId,
                        state = RelaySessionState.ACTIVE
                    )
                    relaySessions[sessionId] = session
                    updateSessionsList()

                    // Lese-Job für eingehende Relay-Nachrichten starten
                    startRelayReadLoop(sessionId, localPeerId, socket)

                    sessionId
                } else {
                    Log.w(TAG, "Relay abgelehnt: $response")
                    socket.close()
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay-Anfrage fehlgeschlagen: ${e.message}")
            null
        }
    }

    // =========================================================================
    // Nachrichten über Relay senden
    // =========================================================================

    /**
     * Sendet eine Nachricht über einen Relay-Peer.
     *
     * @param sessionId Die Session-ID
     * @param targetPeerId Der Ziel-Peer
     * @param data Die zu sendenden Daten (bereits verschlüsselt)
     * @return true bei Erfolg
     */
    suspend fun sendViaRelay(sessionId: String, targetPeerId: String, data: ByteArray): Boolean {
        val peerMap = sessionConnections[sessionId] ?: return false
        val socket = peerMap[localPeerId] ?: return false

        return try {
            withContext(Dispatchers.IO) {
                // Format: "RELAY_DATA|sessionId|fromPeer|toPeer|length|data"
                val header = "RELAY_DATA|$sessionId|$localPeerId|$targetPeerId|${data.size}|"
                val headerBytes = header.toByteArray(Charsets.UTF_8)
                val fullMessage = headerBytes + data

                val outputStream = socket.getOutputStream()
                // Längenpräfix
                val lengthPrefix = byteArrayOf(
                    ((fullMessage.size shr 24) and 0xFF).toByte(),
                    ((fullMessage.size shr 16) and 0xFF).toByte(),
                    ((fullMessage.size shr 8) and 0xFF).toByte(),
                    (fullMessage.size and 0xFF).toByte()
                )
                outputStream.write(lengthPrefix)
                outputStream.write(fullMessage)
                outputStream.flush()

                // Statistiken aktualisieren
                relaySessions[sessionId]?.let { session ->
                    relaySessions[sessionId] = session.copy(bytesRelayed = session.bytesRelayed + data.size)
                }

                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay-Senden fehlgeschlagen: ${e.message}")
            closeSession(sessionId)
            false
        }
    }

    // =========================================================================
    // Eingehende Relay-Verbindungen (für Relay C)
    // =========================================================================

    /**
     * Akzeptiert eingehende Relay-Verbindungen.
     * Dies läuft, wenn dieser Peer als Relay fungiert.
     */
    private suspend fun acceptRelayConnections() {
        while (isRunning) {
            try {
                val clientSocket = relayServerSocket?.accept() ?: break
                val remoteHost = clientSocket.inetAddress.hostAddress ?: "unknown"

                Log.d(TAG, "Eingehende Relay-Verbindung von $remoteHost")

                scope.launch {
                    handleRelayConnection(clientSocket, remoteHost)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Fehler bei Relay-Accept: ${e.message}")
            }
        }
    }

    /**
     * Verarbeitet eine eingehende Relay-Verbindung.
     */
    private suspend fun handleRelayConnection(socket: Socket, host: String) {
        try {
            socket.soTimeout = 30000
            val inputStream = socket.getInputStream()

            // Erste Nachricht lesen (Anfrage)
            val buffer = ByteArray(512)
            val bytesRead = inputStream.read(buffer)
            val request = String(buffer, 0, bytesRead, Charsets.UTF_8)

            when {
                request.startsWith("RELAY_REQUEST|") -> {
                    handleRelayRequest(socket, request)
                }
                request.startsWith("RELAY_DATA|") -> {
                    handleRelayData(socket, request)
                }
                else -> {
                    Log.w(TAG, "Unbekannte Relay-Nachricht: $request")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei Relay-Verbindung von $host: ${e.message}")
            socket.close()
        }
    }

    /**
     * Verarbeitet eine Relay-Anfrage (Peer A fragt Relay C an).
     *
     * Format: "RELAY_REQUEST|peerA|targetPeerB"
     */
    private suspend fun handleRelayRequest(socket: Socket, request: String) {
        val parts = request.split("|")
        if (parts.size < 3) {
            socket.close()
            return
        }

        val peerA = parts[1]
        val targetPeerB = parts[2]

        Log.i(TAG, "Relay-Anfrage: $peerA möchte mit $targetPeerB kommunizieren")

        // Prüfen, ob wir Relay-Dienste anbieten
        if (!isRelayServiceActive) {
            Log.w(TAG, "Relay-Dienst nicht aktiv, lehne ab")
            socket.outputStream.write("RELAY_REJECTED".toByteArray(Charsets.UTF_8))
            socket.close()
            return
        }

        // Prüfen, ob wir noch Kapazität haben
        if (relaySessions.size >= MAX_SESSIONS) {
            Log.w(TAG, "Maximale Sessions erreicht, lehne ab")
            socket.outputStream.write("RELAY_BUSY".toByteArray(Charsets.UTF_8))
            socket.close()
            return
        }

        // Session-ID generieren
        val sessionId = "relay-${peerA.take(8)}-${targetPeerB.take(8)}-${System.currentTimeMillis() % 10000}"

        // Session erstellen
        val session = RelaySession(
            sessionId = sessionId,
            peerA = peerA,
            peerB = targetPeerB,
            state = RelaySessionState.SETUP
        )
        relaySessions[sessionId] = session

        // Verbindung zu Peer A speichern
        val peerMap = ConcurrentHashMap<String, Socket>()
        peerMap[peerA] = socket
        sessionConnections[sessionId] = peerMap

        // Akzeptieren
        val acceptMsg = "RELAY_ACCEPT|$sessionId"
        socket.outputStream.write(acceptMsg.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()

        // Session als aktiv markieren
        relaySessions[sessionId] = session.copy(state = RelaySessionState.ACTIVE)
        updateSessionsList()

        // Lese-Job für Peer A starten
        startRelayReadLoop(sessionId, peerA, socket)

        // Callback
        onRelaySessionCreated?.invoke(session)

        Log.i(TAG, "Relay-Session $sessionId gestartet: $peerA <-> $targetPeerB")
    }

    /**
     * Verarbeitet weitergeleitete Relay-Daten.
     *
     * Format: "RELAY_DATA|sessionId|fromPeer|toPeer|length|data"
     */
    private suspend fun handleRelayData(socket: Socket, header: String) {
        try {
            val parts = header.split("|")
            if (parts.size < 6) return

            val sessionId = parts[1]
            val fromPeer = parts[2]
            val toPeer = parts[3]
            val dataLength = parts[4].toIntOrNull() ?: return

            // Daten lesen
            val data = ByteArray(dataLength)
            var bytesRead = 0
            while (bytesRead < dataLength) {
                val count = socket.inputStream.read(data, bytesRead, dataLength - bytesRead)
                if (count == -1) throw java.io.EOFException()
                bytesRead += count
            }

            // Session finden
            val session = relaySessions[sessionId]
            if (session == null || session.state != RelaySessionState.ACTIVE) {
                Log.w(TAG, "Session $sessionId nicht aktiv")
                return
            }

            // Empfänger finden und weiterleiten
            val peerMap = sessionConnections[sessionId] ?: return
            val targetSocket = peerMap[toPeer]

            if (targetSocket != null && targetSocket.isConnected && !targetSocket.isClosed) {
                // Nachricht weiterleiten (mit Längenpräfix)
                val forwardHeader = "RELAY_DATA|$sessionId|$fromPeer|$toPeer|${data.size}|"
                val forwardHeaderBytes = forwardHeader.toByteArray(Charsets.UTF_8)
                val forwardMessage = forwardHeaderBytes + data

                val lengthPrefix = byteArrayOf(
                    ((forwardMessage.size shr 24) and 0xFF).toByte(),
                    ((forwardMessage.size shr 16) and 0xFF).toByte(),
                    ((forwardMessage.size shr 8) and 0xFF).toByte(),
                    (forwardMessage.size and 0xFF).toByte()
                )

                val outputStream = targetSocket.getOutputStream()
                outputStream.write(lengthPrefix)
                outputStream.write(forwardMessage)
                outputStream.flush()

                // Statistiken aktualisieren
                relaySessions[sessionId] = session.copy(bytesRelayed = session.bytesRelayed + data.size)

                Log.d(TAG, "Relay: $fromPeer -> $toPeer (${data.size} Bytes)")
            } else {
                Log.w(TAG, "Empfänger $toPeer nicht verbunden für Session $sessionId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler bei Relay-Daten: ${e.message}")
        }
    }

    // =========================================================================
    // Relay-Lese-Schleife
    // =========================================================================

    /**
     * Startet eine Lese-Schleife für eine Relay-Verbindung.
     * Liest eingehende RELAY_DATA-Nachrichten und verarbeitet sie.
     */
    private fun startRelayReadLoop(sessionId: String, peerId: String, socket: Socket) {
        val jobKey = "$sessionId-$peerId"
        val job = scope.launch {
            try {
                val inputStream = socket.getInputStream()

                while (isRunning && socket.isConnected && !socket.isClosed) {
                    try {
                        // Längenpräfix lesen (4 Bytes)
                        val lengthBytes = ByteArray(4)
                        var bytesRead = 0
                        while (bytesRead < 4) {
                            val count = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead)
                            if (count == -1) break
                            bytesRead += count
                        }
                        if (bytesRead < 4) break

                        val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                                ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                                ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                                (lengthBytes[3].toInt() and 0xFF)

                        if (length > MAX_MESSAGE_SIZE) break

                        // Nachricht lesen
                        val messageData = ByteArray(length)
                        bytesRead = 0
                        while (bytesRead < length) {
                            val count = inputStream.read(messageData, bytesRead, length - bytesRead)
                            if (count == -1) break
                            bytesRead += count
                        }
                        if (bytesRead < length) break

                        // Nachricht parsen
                        val messageStr = String(messageData, 0, minOf(messageData.size, 512), Charsets.UTF_8)

                        if (messageStr.startsWith("RELAY_DATA|")) {
                            val parts = messageStr.split("|")
                            if (parts.size >= 6) {
                                val msgSessionId = parts[1]
                                val fromPeer = parts[2]
                                val toPeer = parts[3]
                                val dataLen = parts[4].toIntOrNull() ?: 0

                                // Daten extrahieren (nach dem Header)
                                val headerSize = "RELAY_DATA|$msgSessionId|$fromPeer|$toPeer|$dataLen|".toByteArray(Charsets.UTF_8).size
                                val relayData = messageData.copyOfRange(headerSize, messageData.size)

                                // Wenn wir der Empfänger sind, Callback aufrufen
                                if (toPeer == localPeerId) {
                                    onRelayMessageReceived?.invoke(msgSessionId, fromPeer, relayData)
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) { /* Normal */ }
                      catch (e: java.io.EOFException) { break }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay-Lese-Schleife für $peerId beendet: ${e.message}")
            } finally {
                // Session aufräumen bei Verbindungsabbruch
                closeSession(sessionId)
            }
        }
        sessionReadJobs[jobKey] = job
    }

    // =========================================================================
    // Session-Verwaltung
    // =========================================================================

    /**
     * Schließt eine Relay-Session.
     */
    private suspend fun closeSession(sessionId: String) {
        val session = relaySessions[sessionId] ?: return

        Log.i(TAG, "Schließe Relay-Session $sessionId")

        relaySessions[sessionId] = session.copy(state = RelaySessionState.CLOSED)

        // Alle Verbindungen der Session schließen
        val peerMap = sessionConnections[sessionId]
        peerMap?.values?.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        sessionConnections.remove(sessionId)

        // Lese-Jobs beenden
        sessionReadJobs.keys.filter { it.startsWith(sessionId) }.forEach { key ->
            sessionReadJobs[key]?.cancel()
            sessionReadJobs.remove(key)
        }

        relaySessions.remove(sessionId)
        updateSessionsList()
    }

    /**
     * Aktualisiert die Sessions-Liste im Flow.
     */
    private fun updateSessionsList() {
        _activeSessions.value = relaySessions.values.toList()
    }
}

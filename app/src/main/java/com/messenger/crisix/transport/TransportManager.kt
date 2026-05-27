package com.messenger.crisix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Verwaltet alle verfügbaren Transporte und wählt den besten aus.
 * Phase 1: WifiTransport (echtes P2P) + DummyTransport als Fallback.
 */
class TransportManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transports = mutableListOf<Transport>()
    private var reevaluateJob: Job? = null

    private val _activeTransport = MutableStateFlow<Transport?>(null)
    val activeTransport: StateFlow<Transport?> = _activeTransport.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    /** Status aller registrierten Transporte – für die UI */
    private val _connectionStatuses = MutableStateFlow<Map<TransportType, ConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<TransportType, ConnectionStatus>> = _connectionStatuses.asStateFlow()

    // Prioritätsreihenfolge für die Transportauswahl
    // WIFI_DIRECT hat höchste Priorität für lokale P2P-Kommunikation
    // INTERNET (DHT) ist Fallback für globale Kommunikation
    private val priorityOrder = listOf(
        TransportType.WIFI_DIRECT,
        TransportType.INTERNET,
        TransportType.BLUETOOTH_MESH,
        TransportType.SMS,
        TransportType.DNS_TUNNEL,
        TransportType.LORA
    )

    fun registerTransport(transport: Transport) {
        transports.add(transport)
        // Initial-Status setzen
        updateConnectionStatus(transport.type, ConnectionState.SEARCHING, detailText = "Registriert, wird gestartet...")
    }

    /**
     * Aktualisiert den Verbindungsstatus eines Transports und benachrichtigt die UI.
     */
    fun updateConnectionStatus(
        type: TransportType,
        state: ConnectionState,
        peerCount: Int = 0,
        detailText: String = "",
        errorMessage: String? = null
    ) {
        val currentMap = _connectionStatuses.value.toMutableMap()
        currentMap[type] = ConnectionStatus(
            transportType = type,
            state = state,
            peerCount = peerCount,
            detailText = detailText,
            errorMessage = errorMessage
        )
        _connectionStatuses.value = currentMap
        println("[TransportManager] Status $type → $state (Peers: $peerCount, Detail: $detailText)")
    }

    /**
     * Wählt den besten verfügbaren Transport aus.
     * Priorität: INTERNET > WIFI_DIRECT > BLUETOOTH_MESH > SMS > DNS_TUNNEL > LORA
     * RELAY wurde entfernt – die App ist komplett serverlos (P2P über Internet).
     */
    suspend fun selectBestTransport(): Transport? {
        for (type in priorityOrder) {
            val transport = transports.find { it.type == type }
            if (transport != null && transport.isAvailable()) {
                val currentTransport = _activeTransport.value
                // Nur wechseln, wenn der neue Transport eine höhere Priorität hat
                if (currentTransport == null || priorityOrder.indexOf(type) < priorityOrder.indexOf(currentTransport.type)) {
                    _activeTransport.value = transport
                    println("[TransportManager] Transport gewählt: ${transport.type}")
                }
                return transport
            }
        }
        return null
    }

    /**
     * Führt eine sofortige Reevaluation ALLER Transporte durch.
     * 
     * ═══════════════════════════════════════════════════════════════
     * REGELN FÜR DIE STATUS-FARBEN:
     * ═══════════════════════════════════════════════════════════════
     * 
     * 🟢 CONNECTED (Grün) – Transport ist BEREIT:
     *   - WifiTransport: WLAN/LAN ist verbunden (Broadcast-Interface vorhanden)
     *   - InternetTransport: Internet ist erreichbar (Socket zu 8.8.8.8:53)
     *   - DHT: Verbindungen zu Bootstrap-Knoten bestehen
     *   → isAvailable() = true bedeutet: Der Transport kann sofort senden/empfangen
     *   → Peers sind ein Bonus, aber nicht notwendig für CONNECTED
     * 
     * 🟡 SEARCHING (Gelb) – Transport startet oder sucht:
     *   - Wird nur während des Startvorgangs gezeigt
     *   - Oder wenn der Transport gerade initialisiert wird
     *   - Sobald isAvailable() = true → sofort CONNECTED
     * 
     * ⚪ UNAVAILABLE (Grau) – Kein Netzwerk:
     *   - WifiTransport: Kein WLAN/LAN verbunden (kein Broadcast-Interface)
     *   - InternetTransport: Kein Internet (Socket zu 8.8.8.8:53 fehlgeschlagen)
     *   - DHT: Keine Internetverbindung
     * 
     * 🔴 ERROR (Rot) – Technischer Fehler:
     *   - Transport.start() hat eine Exception geworfen
     *   - Socket-Bind fehlgeschlagen
     *   - Berechtigungen fehlen
     * 
     * ═══════════════════════════════════════════════════════════════
     */
    private suspend fun reevaluateAll() {
        val currentType = _activeTransport.value?.type

        // === 1. Verfügbarkeit ALLER Transporte prüfen ===
        for (transport in transports) {
            val isAvail = transport.isAvailable()
            val currentStatus = _connectionStatuses.value[transport.type]
            val (peerCount, detailText) = transport.getStatusDetail()

            if (isAvail) {
                // 🟢 Transport ist BEREIT → sofort CONNECTED
                // isAvailable() = true bedeutet: WLAN verbunden / Internet erreichbar
                // Der Transport kann sofort Nachrichten senden/empfangen
                if (currentStatus == null || currentStatus.state != ConnectionState.CONNECTED) {
                    updateConnectionStatus(
                        type = transport.type,
                        state = ConnectionState.CONNECTED,
                        peerCount = peerCount,
                        detailText = detailText
                    )
                } else {
                    // Bleibt verbunden, aktualisiere nur Details
                    if (currentStatus.peerCount != peerCount || currentStatus.detailText != detailText) {
                        updateConnectionStatus(
                            type = transport.type,
                            state = ConnectionState.CONNECTED,
                            peerCount = peerCount,
                            detailText = detailText
                        )
                    }
                }
            } else {
                // ⚪ Transport ist NICHT verfügbar
                if (currentStatus != null && currentStatus.state != ConnectionState.UNAVAILABLE && currentStatus.state != ConnectionState.ERROR) {
                    // War vorher verfügbar -> jetzt auf UNAVAILABLE setzen
                    updateConnectionStatus(
                        type = transport.type,
                        state = ConnectionState.UNAVAILABLE,
                        peerCount = 0,
                        detailText = "Kein Netzwerk"
                    )
                    println("[TransportManager] ${transport.type} nicht mehr verfügbar -> UNAVAILABLE")
                }
            }
        }

        // === 2. Aktiven Transport prüfen ===
        if (currentType != null) {
            val currentTransport = transports.find { it.type == currentType }
            val currentIsAvail = currentTransport?.isAvailable() ?: false

            if (!currentIsAvail) {
                // Aktiver Transport nicht mehr verfügbar -> neuen suchen
                println("[TransportManager] Aktiver Transport $currentType nicht mehr verfügbar, suche neuen...")
                _activeTransport.value = null
                selectBestTransport()
            } else {
                // Prüfe, ob ein Transport mit höherer Priorität verfügbar ist
                val currentPriority = priorityOrder.indexOf(currentType)
                for (type in priorityOrder) {
                    if (priorityOrder.indexOf(type) >= currentPriority) break
                    val transport = transports.find { it.type == type }
                    if (transport != null && transport.isAvailable()) {
                        println("[TransportManager] Besserer Transport gefunden: ${type} (war: $currentType)")
                        _activeTransport.value = transport
                        break
                    }
                }
            }
        } else {
            // Kein Transport aktiv -> versuche einen zu finden
            selectBestTransport()
        }
    }

    /**
     * Startet eine LIVE-Überprüfung aller Transporte.
     * 
     * ⚡ Echtzeit-Verhalten:
     * - Führt SOFORT eine Reevaluation durch (kein delay)
     * - Wiederholt alle 2 Sekunden (statt 10s)
     * - Reagiert auf Netzwerkänderungen via ConnectivityManager
     * - Jeder Vorgang (start/stop/connect/disconnect) wird sofort angezeigt
     */
    fun startPeriodicReevaluation(intervalMs: Long = 2_000) {
        reevaluateJob?.cancel()
        reevaluateJob = scope.launch {
            // ⚡ SOFORT beim Start reevaluieren (kein delay!)
            reevaluateAll()
            
            while (isActive) {
                delay(intervalMs)
                reevaluateAll()
            }
        }
    }

    /**
     * Gibt die Capabilities des aktuell aktiven Transports zurück.
     * Fallback auf volle Capabilities, falls kein Transport aktiv ist.
     */
    fun getCurrentCapabilities(): TransportCapabilities {
        return _activeTransport.value?.capabilities
            ?: TransportCapabilities() // Volle Capabilities als Fallback
    }

    /**
     * Sendet eine Nachricht über den passenden Transport.
     *
     * Strategie:
     * 1. Prüft, ob der WifiTransport den Peer kennt (lokale IP-basierte Verbindung)
     *    - Versucht zuerst die exakte Peer-ID
     *    - Falls das fehlschlägt, sucht nach einem Peer, dessen ID mit der UUID beginnt
     * 2. Wenn nein, sendet über den aktiven Transport (InternetTransport für globale Peers)
     */
    suspend fun sendMessage(peerId: String, data: ByteArray): Result<Unit> {
        // Prüfe zuerst, ob der WifiTransport den Peer kennt (lokale Verbindung)
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
        if (wifiTransport != null) {
            try {
                // Versuche zuerst mit der exakten Peer-ID
                val result = wifiTransport.send(peerId, data)
                if (result.isSuccess) {
                    return result
                }
            } catch (_: Exception) {
                // Exakte Peer-ID fehlgeschlagen
            }

            // Fallback: Suche nach einem Peer, dessen ID mit der UUID beginnt
            // (QR-Code-Peers haben nur UUID, aber WifiTransport braucht UUID@IP)
            try {
                val matchingPeer = _discoveredPeers.value.find { it.id.startsWith(peerId) && it.id != peerId }
                if (matchingPeer != null) {
                    val result = wifiTransport.send(matchingPeer.id, data)
                    if (result.isSuccess) {
                        return result
                    }
                }
            } catch (_: Exception) { }
        }

        // Fallback: Über den aktiven Transport senden
        val transport = _activeTransport.value
            ?: return Result.failure(Exception("Kein aktiver Transport"))

        // Wichtig: Versuche zuerst den InternetTransport direkt (nicht nur den aktiven),
        // da dieser die peerAddressRegistry mit QR-Code-Peer-IDs hat
        val internetTransport = transports.find { it is com.messenger.crisix.transport.internet.InternetTransport }
        if (internetTransport != null && internetTransport != transport) {
            try {
                val result = internetTransport.send(peerId, data)
                if (result.isSuccess) {
                    return result
                }
            } catch (_: Exception) { }
        }

        return transport.send(peerId, data)
    }



    /**
     * Registriert einen Listener für eingehende Nachrichten beim aktiven Transport.
     */
    fun registerMessageListener(listener: (String, ByteArray) -> Unit) {
        for (transport in transports) {
            transport.registerListener(listener)
        }
    }

    /**
     * Startet alle registrierten Transporte.
     * 
     * Der Status wird NICHT sofort auf CONNECTED gesetzt – das macht die
     * periodische Reevaluation (alle 2s) live und dynamisch.
     * So wird jeder Vorgang (starten, verbinden, trennen) sofort sichtbar.
     */
    suspend fun startAll() {
        for (transport in transports) {
            try {
                updateConnectionStatus(transport.type, ConnectionState.SEARCHING, detailText = "Starte...")
                transport.start()
                // Nach dem Start: SEARCHING lassen – die Reevaluation prüft live,
                // ob der Transport wirklich verfügbar ist und setzt den Status.
                updateConnectionStatus(transport.type, ConnectionState.SEARCHING, detailText = "Gestartet, prüfe Verbindung...")
            } catch (e: Exception) {
                updateConnectionStatus(transport.type, ConnectionState.ERROR, errorMessage = e.message)
            }
        }
    }

    /**
     * Stellt eine manuelle Verbindung zu einem Peer über IP-Adresse her.
     * Versucht zuerst den WifiTransport (lokales Netzwerk), dann InternetTransport (libp2p).
     * Funktioniert auch, wenn UDP-Broadcast nicht verfügbar ist (z.B. Emulator).
     *
     * @param ipAddress Die IP-Adresse des Peers (optional mit Port, z.B. "192.168.178.51:43155")
     * @param displayName Optionaler Anzeigename für den Peer
     * @return Result mit dem Peer-Objekt bei Erfolg
     */
    suspend fun connectToPeer(ipAddress: String, displayName: String? = null, port: Int? = null): Result<Peer> {
        // 1. Versuche WifiTransport (lokales Netzwerk) - höhere Priorität
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
        if (wifiTransport != null) {
            try {
                // Nur IP ohne Port für WifiTransport (nutzt festen messagePort, es sei denn Port wird explizit angegeben)
                val ipOnly = ipAddress.split(":")[0]
                val result = wifiTransport.connectToPeer(ipOnly, displayName, port)
                if (result.isSuccess) {
                    val peer = result.getOrNull()!!
                    println("[TransportManager] Verbindung über WifiTransport: ${peer.name} (${peer.id})")
                    // Peer in discoveredPeers aufnehmen (falls nicht bereits vorhanden)
                    val currentPeers = _discoveredPeers.value.toMutableList()
                    if (currentPeers.none { it.id == peer.id }) {
                        currentPeers.add(peer)
                        _discoveredPeers.value = currentPeers
                    }
                    return result
                }
            } catch (_: Exception) { }
        }

        // 2. Versuche InternetTransport (libp2p) - für P2P über das Internet
        val internetTransport = transports.find { it is com.messenger.crisix.transport.internet.InternetTransport }
        if (internetTransport != null) {
            try {
                // Port aus QR-Code an InternetTransport übergeben (ip:port Format)
                val addressWithPort = if (port != null) "$ipAddress:$port" else ipAddress
                val result = (internetTransport as com.messenger.crisix.transport.internet.InternetTransport).connectToPeer(addressWithPort, displayName)
                if (result.isSuccess) {
                    val peer = result.getOrNull()!!
                    println("[TransportManager] Verbindung über InternetTransport: ${peer.name} (${peer.id})")
                    // Peer in discoveredPeers aufnehmen (falls nicht bereits vorhanden)
                    val currentPeers = _discoveredPeers.value.toMutableList()
                    if (currentPeers.none { it.id == peer.id }) {
                        currentPeers.add(peer)
                        _discoveredPeers.value = currentPeers
                    }
                    return result
                }
            } catch (_: Exception) { }
        }

        return Result.failure(Exception("Kein Transport verfügbar für $ipAddress"))
    }


    /**
     * Fügt einen Kontakt-Peer zur Liste hinzu, ohne automatische Netzwerksuche.
     *
     * @param peerId Die Peer-ID (Fingerprint aus QR-Code)
     * @param displayName Optionaler Anzeigename
     */
    fun addContactPeer(peerId: String, displayName: String? = null) {
        val currentPeers = _discoveredPeers.value.toMutableList()
        val name = displayName ?: peerId.take(8)

        val alreadyExists = currentPeers.any { it.id == peerId || it.id.startsWith("$peerId@") }
        if (!alreadyExists) {
            currentPeers.add(Peer(id = peerId, name = name))
            _discoveredPeers.value = currentPeers
            println("[TransportManager] Kontakt-Peer hinzugefügt: $name ($peerId)")
        }
    }


    /**
     * Gibt einen registrierten Transport anhand seines Typs zurück.
     *
     * @param type Der gesuchte Transport-Typ
     * @return Der Transport, oder null wenn nicht registriert
     */
    fun getTransportByType(type: TransportType): Transport? {
        return transports.find { it.type == type }
    }

    /**
     * Stoppt alle registrierten Transporte.
     */
    suspend fun stopAll() {
        reevaluateJob?.cancel()
        reevaluateJob = null
        for (transport in transports) {
            transport.stop()
        }
    }
}


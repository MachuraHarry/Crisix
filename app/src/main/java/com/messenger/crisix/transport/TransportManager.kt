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
     * Startet eine periodische Überprüfung, ob ein besserer Transport verfügbar ist.
     * Prüft regelmäßig, ob ein Transport mit höherer Priorität verfügbar wird.
     */
    fun startPeriodicReevaluation(intervalMs: Long = 10_000) {
        reevaluateJob?.cancel()
        reevaluateJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val currentType = _activeTransport.value?.type
                if (currentType != null) {
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
                } else {
                    // Kein Transport aktiv -> versuche einen zu finden
                    selectBestTransport()
                }
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
     * Startet die Peer-Discovery für den aktiven Transport.
     * Sammelt Peers aus allen registrierten Transporten.
     */
    fun startPeerDiscovery() {
        scope.launch {
            val allPeers = mutableListOf<Peer>()

            for (transport in transports) {
                try {
                    transport.discoverPeers().collect { peer ->
                        // Peer zur Liste hinzufügen, wenn nicht bereits vorhanden
                        if (allPeers.none { it.id == peer.id }) {
                            allPeers.add(peer)
                            _discoveredPeers.value = allPeers.toList()
                        }
                    }
                } catch (e: Exception) {
                    // Discovery-Fehler ignorieren
                }
            }
        }
    }

    /**
     * Sendet eine Nachricht über den passenden Transport.
     *
     * Strategie:
     * 1. Prüft, ob der WifiTransport den Peer kennt (lokale IP-basierte Verbindung)
     * 2. Wenn ja, sendet über WifiTransport (für lokale Peers)
     * 3. Wenn nein, sendet über den aktiven Transport (InternetTransport für globale Peers)
     */
    suspend fun sendMessage(peerId: String, data: ByteArray): Result<Unit> {
        // Prüfe zuerst, ob der WifiTransport den Peer kennt (lokale Verbindung)
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
        if (wifiTransport != null) {
            try {
                val result = wifiTransport.send(peerId, data)
                if (result.isSuccess) {
                    return result
                }
            } catch (_: Exception) {
                // WifiTransport fehlgeschlagen, fahre mit aktivem Transport fort
            }
        }

        // Fallback: Über den aktiven Transport senden
        val transport = _activeTransport.value
            ?: return Result.failure(Exception("Kein aktiver Transport"))
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
     */
    suspend fun startAll() {
        for (transport in transports) {
            transport.start()
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
    suspend fun connectToPeer(ipAddress: String, displayName: String? = null): Result<Peer> {
        // 1. Versuche WifiTransport (lokales Netzwerk) - höhere Priorität
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
        if (wifiTransport != null) {
            try {
                // Nur IP ohne Port für WifiTransport (nutzt festen messagePort)
                val ipOnly = ipAddress.split(":")[0]
                val result = wifiTransport.connectToPeer(ipOnly, displayName)
                if (result.isSuccess) {
                    println("[TransportManager] Verbindung über WifiTransport: ${result.getOrNull()?.name}")
                    return result
                }
            } catch (_: Exception) { }
        }

        // 2. Versuche InternetTransport (libp2p) - für P2P über das Internet
        val internetTransport = transports.find { it is com.messenger.crisix.transport.internet.InternetTransport }
        if (internetTransport != null) {
            try {
                val result = (internetTransport as com.messenger.crisix.transport.internet.InternetTransport).connectToPeer(ipAddress, displayName)
                if (result.isSuccess) {
                    println("[TransportManager] Verbindung über InternetTransport: ${result.getOrNull()?.name}")
                    return result
                }
            } catch (_: Exception) { }
        }

        return Result.failure(Exception("Kein Transport verfügbar für $ipAddress"))
    }

    /**
     * Scannt das lokale Netzwerk nach anderen Crisix-Geräten.
     * Durchsucht das gesamte Subnetz nach offenen Ports 54230.
     */
    suspend fun scanLocalNetwork(): List<Peer> {
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
            ?: return emptyList()
        return wifiTransport.scanLocalNetwork()
    }

    /**
     * Fügt einen Peer zur Kontaktliste hinzu, ohne sofort zu verbinden.
     * Der Peer wird in der Chat-Liste angezeigt und später automatisch
     * verbunden, wenn er per mDNS/BLE/Netzwerkscan gefunden wird.
     *
     * @param peerId Die Peer-ID (z.B. aus QR-Code)
     * @param displayName Optionaler Anzeigename
     */
    fun addContactPeer(peerId: String, displayName: String? = null) {
        val currentPeers = _discoveredPeers.value.toMutableList()
        // Nur hinzufügen, wenn nicht bereits vorhanden
        if (currentPeers.none { it.id == peerId }) {
            val name = displayName ?: peerId.take(8)
            currentPeers.add(Peer(id = peerId, name = name))
            _discoveredPeers.value = currentPeers
            println("[TransportManager] Kontakt-Peer hinzugefügt: $name ($peerId)")
        }
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

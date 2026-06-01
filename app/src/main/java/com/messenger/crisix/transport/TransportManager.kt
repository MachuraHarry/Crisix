package com.messenger.crisix.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.crypto.EncryptedMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class MessageStatus {
    SENDING, SENT, DELIVERED, FAILED, PENDING
}

/**
 * Bekannte Fähigkeiten eines Peers über alle Transporte hinweg.
 * Wird beim Verbindungsaufbau ausgetauscht und bei State-Änderungen aktualisiert.
 */
data class PeerCapabilities(
    val peerId: String,
    val hasInternet: Boolean = false,
    val hasWifiDirect: Boolean = false,
    val hasBle: Boolean = false,
    val hasRelay: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class DeliveryUpdate(
    val uiMessageId: String,
    val peerId: String,
    val status: MessageStatus,
    val transport: TransportType?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Verwaltet alle verfügbaren Transporte und wählt den besten aus.
 * Phase 1: WifiTransport (echtes P2P) + DummyTransport als Fallback.
 */
class TransportManager {

    companion object {
        private const val TAG = "TransportManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transports = CopyOnWriteArrayList<Transport>()
    private var reevaluateJob: Job? = null

    private val _activeTransport = MutableStateFlow<Transport?>(null)
    val activeTransport: StateFlow<Transport?> = _activeTransport.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    /** Status aller registrierten Transporte – für die UI */
    private val _connectionStatuses = MutableStateFlow<Map<TransportType, ConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<TransportType, ConnectionStatus>> = _connectionStatuses.asStateFlow()

    /** Vom Benutzer aktivierte Transporte (Settings). Standard: alle außer SMS/LoRa */
    private var enabledTransports: Set<TransportType> = TransportType.entries.toSet() - setOf(TransportType.SMS, TransportType.LORA)

    // ═════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER: Pro Transport-Typ (nicht pro Peer)
    // ═════════════════════════════════════════════════════════════════════
    enum class CircuitBreakerState { CLOSED, OPEN, HALF_OPEN }

    private data class CircuitBreaker(
        val state: CircuitBreakerState = CircuitBreakerState.CLOSED,
        val failureCount: Int = 0,
        val lastFailureTime: Long = 0L,
        val timeoutMs: Long = 30_000L
    )

    private val circuitBreakers = ConcurrentHashMap<TransportType, CircuitBreaker>()
    private val CB_THRESHOLD = 3

    private val circuitBreakerTimeouts = mapOf(
        TransportType.WIFI_DIRECT to 10_000L,
        TransportType.INTERNET to 10_000L,
        TransportType.RELAY to 30_000L,
        TransportType.BLUETOOTH_MESH to 30_000L,
        TransportType.DNS_TUNNEL to 120_000L,
        TransportType.LORA to 120_000L
    )

    private fun isCircuitBreakerEnabled(type: TransportType): Boolean {
        return type != TransportType.SMS
    }

    private fun canTryTransport(type: TransportType): Boolean {
        if (!isCircuitBreakerEnabled(type)) return true
        val cb = circuitBreakers[type] ?: return true
        when (cb.state) {
            CircuitBreakerState.CLOSED -> return true
            CircuitBreakerState.OPEN -> {
                if (System.currentTimeMillis() - cb.lastFailureTime >= cb.timeoutMs) {
                    circuitBreakers[type] = cb.copy(state = CircuitBreakerState.HALF_OPEN)
                    return true
                }
                return false
            }
            CircuitBreakerState.HALF_OPEN -> return true
        }
    }

    private fun recordFailure(type: TransportType) {
        if (!isCircuitBreakerEnabled(type)) return
        val existing = circuitBreakers[type]
        val timeoutMs = circuitBreakerTimeouts[type] ?: 30_000L
        val cb = existing ?: CircuitBreaker(timeoutMs = timeoutMs)
        val newCount = cb.failureCount + 1
        val newState = if (newCount >= CB_THRESHOLD) {
            updateConnectionStatus(type, ConnectionState.ERROR, errorMessage = "Circuit-Breaker OPEN ($newCount failures)")
            CircuitBreakerState.OPEN
        } else {
            cb.state
        }
        circuitBreakers[type] = cb.copy(
            state = newState,
            failureCount = newCount,
            lastFailureTime = System.currentTimeMillis()
        )
        Log.w(TAG, "[CB] $type failure #$newCount, state=${newState.name}")
    }

    private fun recordSuccess(type: TransportType) {
        if (!isCircuitBreakerEnabled(type)) return
        val existing = circuitBreakers[type]
        if (existing != null && (existing.state != CircuitBreakerState.CLOSED || existing.failureCount > 0)) {
            circuitBreakers.remove(type)
            updateConnectionStatus(type, ConnectionState.CONNECTED)
            Log.i(TAG, "[CB] $type success → CLOSED")
        }
    }

    /** Delivery-Status-Updates für die UI */
    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
    val deliveryUpdates: SharedFlow<DeliveryUpdate> = _deliveryUpdates.asSharedFlow()

    /** Retry-Queue für fehlgeschlagene Nachrichten (thread-safe via CopyOnWriteArrayList) */
    data class RetryEntry(val uiMessageId: String, val peerId: String, val data: ByteArray, val retryCount: Int = 0)
    private val retryQueue = CopyOnWriteArrayList<RetryEntry>()
    private var retryJob: Job? = null
    private val RETRY_INTERVAL_MS = 10_000L
    private val MAX_RETRIES = 10

    /** Bekannte Capabilities pro Peer (peerId → Capabilities) */
    private val _peerCapabilities = MutableStateFlow<Map<String, PeerCapabilities>>(emptyMap())
    val peerCapabilities: StateFlow<Map<String, PeerCapabilities>> = _peerCapabilities.asStateFlow()

    /**
     * Route-Hints: Über welchen Transport war ein Peer das letzte Mal erreichbar.
     * Wird bei jeder eingehenden Nachricht aktualisiert.
     * TTL: 5 Minuten – danach wird wieder über Mutual Priority gesucht.
     */
    private data class RouteHint(val transportType: TransportType, val timestamp: Long = System.currentTimeMillis())
    private val routeHints = ConcurrentHashMap<String, RouteHint>()
    private val ROUTE_HINT_TTL_MS = 5 * 60 * 1000L

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** E2EE-Manager für Ende-zu-Ende-Verschlüsselung (optional) */
    private var e2eeManager: E2eeManager? = null

    /** Defragmenter für Chunk-Reassembly eingehender fragmentierter Nachrichten */
    private val defragmenter = Defragmenter()

    /** Callback für Benachrichtigungen bei eingehenden Nachrichten */
    var onIncomingMessage: ((peerId: String, peerName: String, messageText: String, unreadCount: Int) -> Unit)? = null

    /** Callback: Nachricht in Retry-Queue aufgenommen (zum Persistieren) */
    var onRetryAdd: ((uiMessageId: String, peerId: String, data: ByteArray, retryCount: Int) -> Unit)? = null

    /** Callback: Nachricht aus Retry-Queue entfernt (nach Erfolg oder Max-Retries) */
    var onRetryRemove: ((uiMessageId: String) -> Unit)? = null

    /**
     * Setzt den E2EE-Manager für transparente Verschlüsselung.
     *
     * Wenn gesetzt, werden alle ausgehenden Nachrichten vor dem Senden
     * verschlüsselt und alle eingehenden Nachrichten nach dem Empfang
     * entschlüsselt. Der Payload wird als JSON mit dem Feld "type"="crisix_e2ee" markiert.
     */
    fun setE2eeManager(manager: E2eeManager) {
        this.e2eeManager = manager
        Log.i(TAG, "E2EE-Manager registriert")
    }

    private fun updateRouteHint(peerId: String, transportType: TransportType) {
        routeHints[peerId] = RouteHint(transportType)
    }

    private fun getValidRouteHint(peerId: String): RouteHint? {
        val hint: RouteHint? = routeHints[peerId]
        if (hint == null) return null
        if (System.currentTimeMillis() - hint.timestamp > ROUTE_HINT_TTL_MS) {
            routeHints.remove(peerId)
            return null
        }
        return hint
    }

    fun isTransportEnabled(type: TransportType): Boolean = type in enabledTransports

    /**
     * Aktualisiert die aktivierten Transporte. Gestoppte/entfernte Transporte
     * werden gestoppt; neu aktivierte werden gestartet (wenn bereits registriert).
     */
    suspend fun setEnabledTransports(enabled: Set<TransportType>) {
        val previous = enabledTransports
        enabledTransports = enabled

        val added = enabled - previous

        // Neu aktivierte starten
        for (type in added) {
            val transport = transports.find { it.type == type }
            if (transport != null) {
                try {
                    transport.start()
                    updateConnectionStatus(type, ConnectionState.SEARCHING, detailText = "Gestartet")
                } catch (e: Exception) {
                    updateConnectionStatus(type, ConnectionState.ERROR, errorMessage = e.message)
                }
            }
        }

        // Deaktivierte stoppen
        for (type in previous - enabled) {
            val transport = transports.find { it.type == type }
            transport?.stop()
            updateConnectionStatus(type, ConnectionState.UNAVAILABLE, detailText = "Deaktiviert")
        }

        // Aktiven Transport reevaluieren
        val current = _activeTransport.value
        if (current != null && current.type !in enabled) {
            _activeTransport.value = null
            selectBestTransport()
        } else if (added.isNotEmpty()) {
            // Neu aktivierte Transporte → Upgrade auf besten verfügbaren
            selectBestTransport()
        }

        // Retry-Queue triggern falls neue Transporte verfügbar sind
        if (added.isNotEmpty()) {
            retryPendingMessages()
        }
    }

    /**
     * Registriert einen ConnectivityManager.NetworkCallback, der bei jeder
     * Netzwerkänderung (WLAN an/aus, Mobile Daten an/aus) feuert.
     * Löst Capability-Refresh via BLE + Retry der Pending-Queue aus.
     */
    fun initNetworkMonitor(context: Context) {
        appContext = context
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { onNetworkStateChanged() }
            }
            override fun onLost(network: Network) {
                scope.launch { onNetworkStateChanged() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scope.launch { onNetworkStateChanged() }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        connectivityCallback = callback
        Log.i(TAG, "Connectivity-Monitor registriert")
    }

    private suspend fun onNetworkStateChanged() {
        Log.i(TAG, "Netzwerkänderung → Capabilities + Retry")
        reevaluateAll()
        val bleTransport = getTransportByType(TransportType.BLUETOOTH_MESH) as? BleTransport
        bleTransport?.broadcastCapabilities()
        retryPendingMessages()
    }

    /**
     * Ping/Pong-Probe: Leichter Layer-2-Test bevor der echte Payload gesendet wird.
     * Ein Ping (JSON mit type=crisix_ping) wird gesendet, der Peer antwortet
     * automatisch mit einem Pong. Erst dann wird der Payload übertragen.
     * So wird verhindert, dass Nachrichten über Transporte mit falschem
     * Erfolg (z.B. Relay ohne Empfänger) verloren gehen.
     */
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val PING_TIMEOUT_MS = 2000L

    private suspend fun probeTransport(peerId: String, transport: Transport): Boolean {
        val pingId = UUID.randomUUID().toString()
        return try {
            val deferred = CompletableDeferred<Boolean>()
            pendingPings[pingId] = deferred

            val pingPayload = JSONObject().apply {
                put("type", "crisix_ping")
                put("id", pingId)
            }.toString().toByteArray()

            val sendResult = transport.send(peerId, pingPayload)
            if (sendResult.isFailure) {
                pendingPings.remove(pingId)
                return false
            }

            val result = withTimeout(PING_TIMEOUT_MS) {
                deferred.await()
            }
            pendingPings.remove(pingId)
            result
        } catch (e: Exception) {
            pendingPings.remove(pingId)
            false
        }
    }

    // Prioritätsreihenfolge für die Transportauswahl
    // WIFI_DIRECT hat höchste Priorität für lokale P2P-Kommunikation
    // INTERNET (DHT) ist Fallback für globale Kommunikation
    // RELAY kommt vor DNS_TUNNEL (DNS ist langsamer und unzuverlässiger)
    private val priorityOrder = listOf(
        TransportType.WIFI_DIRECT,
        TransportType.INTERNET,
        TransportType.RELAY,
        TransportType.BLUETOOTH_MESH,
        TransportType.SMS,
        TransportType.DNS_TUNNEL,
        TransportType.LORA
    )

    fun registerTransport(transport: Transport) {
        transports.add(transport)
        if (transport is DnsTunnelTransport) {
            transport.onDeliveryAck = { messageId, peerId ->
                _deliveryUpdates.tryEmit(DeliveryUpdate(
                    uiMessageId = messageId,
                    peerId = peerId,
                    status = MessageStatus.DELIVERED,
                    transport = TransportType.DNS_TUNNEL
                ))
            }
        }
        if (transport is RelayTransport) {
            transport.onDeliveryAck = { messageId, peerId ->
                _deliveryUpdates.tryEmit(DeliveryUpdate(
                    uiMessageId = messageId,
                    peerId = peerId,
                    status = MessageStatus.DELIVERED,
                    transport = TransportType.RELAY
                ))
            }
        }
        if (transport is BleTransport) {
            transport.onPeerCapabilities = { caps ->
                updatePeerCapabilities(caps)
            }
        }
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
        Log.i(TAG, "[TransportManager] Status $type → $state (Peers: $peerCount, Detail: $detailText)")
    }

    /**
     * Wählt den besten verfügbaren Transport aus.
     * Priorität: INTERNET > WIFI_DIRECT > BLUETOOTH_MESH > SMS > DNS_TUNNEL > LORA
     */
    suspend fun selectBestTransport(): Transport? {
        for (type in priorityOrder) {
            val transport = transports.find { it.type == type }
            if (transport != null && transport.isAvailable()) {
                val currentTransport = _activeTransport.value
                // Nur wechseln, wenn der neue Transport eine höhere Priorität hat
                if (currentTransport == null || priorityOrder.indexOf(type) < priorityOrder.indexOf(currentTransport.type)) {
                    _activeTransport.value = transport
                    Log.i(TAG, "[TransportManager] Transport gewählt: ${transport.type}")
                }
                return transport
            }
        }
        return null
    }

    /**
     * Führt eine sofortige Reevaluation ALLER Transporte durch.
     */
    private suspend fun reevaluateAll() {
        val currentType = _activeTransport.value?.type

        // === 1. Verfügbarkeit ALLER Transporte prüfen ===
        for (transport in transports) {
            val isAvail = transport.isAvailable()
            val currentStatus = _connectionStatuses.value[transport.type]
            val (peerCount, detailText) = transport.getStatusDetail()

            if (isAvail) {
                if (currentStatus == null || currentStatus.state != ConnectionState.CONNECTED) {
                    updateConnectionStatus(
                        type = transport.type,
                        state = ConnectionState.CONNECTED,
                        peerCount = peerCount,
                        detailText = detailText
                    )
                } else {
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
                if (currentStatus != null && currentStatus.state != ConnectionState.UNAVAILABLE && currentStatus.state != ConnectionState.ERROR) {
                    updateConnectionStatus(
                        type = transport.type,
                        state = ConnectionState.UNAVAILABLE,
                        peerCount = 0,
                        detailText = "Kein Netzwerk"
                    )
                    Log.i(TAG, "[TransportManager] ${transport.type} nicht mehr verfügbar -> UNAVAILABLE")
                }
            }
        }

        // === 2. Aktiven Transport prüfen ===
        if (currentType != null) {
            val currentTransport = transports.find { it.type == currentType }
            val currentIsAvail = currentTransport?.isAvailable() ?: false

            if (!currentIsAvail) {
                Log.i(TAG, "[TransportManager] Aktiver Transport $currentType nicht mehr verfügbar, suche neuen...")
                _activeTransport.value = null
                selectBestTransport()
            } else {
                val currentPriority = priorityOrder.indexOf(currentType)
                for (type in priorityOrder) {
                    if (priorityOrder.indexOf(type) >= currentPriority) break
                    val transport = transports.find { it.type == type }
                    if (transport != null && transport.isAvailable()) {
                        Log.i(TAG, "[TransportManager] Besserer Transport gefunden: ${type} (war: $currentType)")
                        _activeTransport.value = transport
                        break
                    }
                }
            }
        } else {
            selectBestTransport()
        }
    }

    /**
     * Startet eine LIVE-Überprüfung aller Transporte.
     */
    fun startPeriodicReevaluation(intervalMs: Long = 5_000) {
        reevaluateJob?.cancel()
        reevaluateJob = scope.launch {
            reevaluateAll()
            while (isActive) {
                delay(intervalMs)
                reevaluateAll()
                defragmenter.cleanupExpired()
            }
        }
    }

    /**
     * Gibt die Capabilities des aktuell aktiven Transports zurück.
     */
    fun getCurrentCapabilities(): TransportCapabilities {
        return _activeTransport.value?.capabilities
            ?: TransportCapabilities(
                supportsAudio = true,
                supportsImages = true,
            )
    }

    /**
     * Aktualisiert die Capabilities eines Peers.
     */
    fun updatePeerCapabilities(caps: PeerCapabilities) {
        val updated = _peerCapabilities.value.toMutableMap()
        updated[caps.peerId] = caps
        _peerCapabilities.value = updated
        Log.i(TAG, "[TransportManager] Capabilities aktualisiert für ${caps.peerId.take(8)}: internet=${caps.hasInternet}, wifi=${caps.hasWifiDirect}, ble=${caps.hasBle}, relay=${caps.hasRelay}")
        scope.launch { retryPendingMessages() }
    }

    /**
     * Sendet eine Nachricht über den passenden Transport.
     *
     * Mutual Priority: Wenn die Capabilities des Empfängers bekannt sind,
     * werden NUR Transporte probiert die BEIDE verfügbar sind.
     * Sonst Fallback auf alle Transporte.
     *
     * E2EE: Wenn eine E2EE-Session mit dem Peer existiert, wird der Payload
     * vor dem Senden transparent verschlüsselt.
     */
    suspend fun sendMessage(peerId: String, data: ByteArray, uiMessageId: String? = null): Result<Unit> {
        val normalizedPeerId = peerId.split("@").first()
        val caps = _peerCapabilities.value[normalizedPeerId]

        // Transporte in Mutual Priority bestimmen
        val triedTypes = if (caps != null) {
            priorityOrder.filter { type ->
                when (type) {
                    TransportType.INTERNET -> caps.hasInternet
                    TransportType.RELAY -> caps.hasRelay
                    TransportType.WIFI_DIRECT -> caps.hasWifiDirect
                    TransportType.BLUETOOTH_MESH -> caps.hasBle
                    TransportType.DNS_TUNNEL -> caps.hasInternet || caps.hasRelay
                    TransportType.SMS -> true
                    TransportType.LORA -> true
                }
            }
        } else {
            priorityOrder
        }

        // Route-Hint prüfen
        val routeHint = getValidRouteHint(normalizedPeerId)
        val orderedTypes = if (routeHint != null) {
            val withoutHint = triedTypes.filter { it != routeHint.transportType }
            listOf(routeHint.transportType) + withoutHint
        } else {
            triedTypes
        }

        // Settings-Filter: Nur aktivierte Transporte verwenden
        val filteredTypes = orderedTypes.filter { it in enabledTransports }

        // ═══════════════════════════════════════════════════════════════
        // E2EE: KEINE Verschlüsselung hier!
        //
        // WICHTIG: Die Verschlüsselung passiert NUR in CrisixApp.kt!
        // Der TransportManager darf NICHT selbst verschlüsseln, weil:
        // 1. CrisixApp.kt verschlüsselt bereits im onSendMessage-Handler
        // 2. Doppelte Verschlüsselung würde die Nachricht unlesbar machen
        // 3. CrisixApp.kt hat den isEncrypted-Flag und die Session-Prüfung
        // ═══════════════════════════════════════════════════════════════
        val e2eePayload = data

        // ═══════════════════════════════════════════════════════════════
        // HANDSHAKE/ACK-DETECTION: Prüfe ob die Nachricht ein E2EE-Handshake oder ACK ist
        // Handshakes + ACKs sollten NICHT geprobt werden (Henne-Ei-Problem)
        // Der Handshake ist das erste Signal zur Peer-Erkennung
        // Das ACK ist die Antwort auf den Handshake
        // ═══════════════════════════════════════════════════════════════
        val isHandshakeOrAck = try {
            val jsonStr = String(data)
            val json = JSONObject(jsonStr)
            val type = json.getString("type")
            type == "crisix_e2ee_handshake" || type == "crisix_e2ee_ack" || type == "crisix_e2ee"
        } catch (e: Exception) {
            false
        }

        var lastError: String? = null
        for (type in filteredTypes) {
            val transport = transports.find { it.type == type } ?: continue
            if (!transport.isAvailable()) continue

            // Circuit-Breaker-Check: OPEN-Transporte überspringen
            if (!canTryTransport(type)) {
                Log.i(TAG, "[TransportManager] CB OPEN für $type → skip")
                continue
            }

            // WICHTIG: Handshakes + ACKs + E2EE-Nachrichten BRAUCHEN KEINE PROBE
            // Der Handshake ist selbst das erste Kontakt-Signal
            // Das ACK ist die Antwort auf den Handshake
            // E2EE-Nachrichten sind bereits verschlüsselt und sollten nicht geprobt werden
            if (!isHandshakeOrAck && transport.capabilities.requiresProbing) {
                val probeOk = probeTransport(normalizedPeerId, transport)
                if (!probeOk) {
                    Log.i(TAG, "[TransportManager] Probe fehlgeschlagen für $type → skip")
                    continue
                }
                Log.i(TAG, "[TransportManager] Probe erfolgreich für $type → sende Payload")
            } else if (!transport.capabilities.requiresProbing) {
                Log.i(TAG, "[TransportManager] $type benötigt kein Probing → sende direkt")
            } else {
                Log.i(TAG, "[TransportManager] E2EE-Handshake/ACK/Message erkannt → überspringe Probe für $type")
            }

            try {
                // ═══════════════════════════════════════════════════════════════
                // FRAGMENTIERUNG: Payload in Chunks splitten falls nötig
                // ═══════════════════════════════════════════════════════════════
                val maxPayload = transport.capabilities.maxPayloadSize
                val payloadsToSend = if (e2eePayload.size > maxPayload) {
                    Log.i(TAG, "[TransportManager] Fragmentiere Payload (${e2eePayload.size} bytes > ${maxPayload} max) über $type")
                    val chunks = Fragmenter.split(e2eePayload, maxPayload)
                    chunks.map { it.toBytes() }
                } else {
                    listOf(e2eePayload)
                }

                // ═══════════════════════════════════════════════════════════════
                // WICHTIG: uiMessageId an alle Nachrichten anhängen (für ACK-Tracking)
                // Format: <payload>\u0000<uiMessageId>
                // Dies ermöglicht dem Empfänger, einen ACK mit der richtigen messageId zu senden
                // Chunks bekommen KEIN uiMessageId-Suffix (sie werden via Fragmenter-ID getrackt)
                // ═══════════════════════════════════════════════════════════════
                var allSent = true
                var lastTransportError: String? = null
                for ((chunkIdx, rawPayload) in payloadsToSend.withIndex()) {
                    val isChunk = Fragmenter.isChunk(rawPayload)
                    val payload = if (uiMessageId != null && !isChunk) {
                        rawPayload + "\u0000$uiMessageId".toByteArray()
                    } else {
                        rawPayload
                    }

                    val result = transport.send(normalizedPeerId, payload)
                    if (result.isSuccess) {
                        if (chunkIdx == payloadsToSend.lastIndex) {
                            recordSuccess(type)
                            updateRouteHint(normalizedPeerId, type)
                            emitDeliveryUpdate(uiMessageId, normalizedPeerId, MessageStatus.SENT, type)
                            return result
                        }
                    } else {
                        allSent = false
                        lastTransportError = result.exceptionOrNull()?.message
                        Log.w(TAG, "[TransportManager] $type Chunk $chunkIdx/${payloadsToSend.size} fehlgeschlagen: $lastTransportError")
                        break
                    }
                }
                if (!allSent) {
                    recordFailure(type)
                    lastError = lastTransportError
                }
            } catch (e: Exception) {
                recordFailure(type)
                lastError = e.message
                Log.w(TAG, "[TransportManager] $type Exception: $lastError")
            }
        }

        // Kein Transport verfügbar → in Retry-Queue
        if (uiMessageId != null) {
            emitDeliveryUpdate(uiMessageId, normalizedPeerId, MessageStatus.PENDING, null)
            retryQueue.add(RetryEntry(uiMessageId, normalizedPeerId, data))
            onRetryAdd?.invoke(uiMessageId, normalizedPeerId, data, 0)
            Log.i(TAG, "[TransportManager] Nachricht in Retry-Queue (${retryQueue.size} pending)")
        }
        return Result.failure(Exception(lastError ?: "Empfänger nicht erreichbar"))
    }

    private fun emitDeliveryUpdate(uiMessageId: String?, peerId: String, status: MessageStatus, transport: TransportType?) {
        if (uiMessageId != null) {
            _deliveryUpdates.tryEmit(DeliveryUpdate(
                uiMessageId = uiMessageId,
                peerId = peerId,
                status = status,
                transport = transport
            ))
        }
    }

    /**
     * Lädt persistierte Retry-Einträge aus der DB in die Retry-Queue.
     * Wird beim App-Start aufgerufen, nachdem die DB geladen wurde.
     */
    fun loadPendingEntries(entries: List<RetryEntry>) {
        retryQueue.clear()
        retryQueue.addAll(entries)
        Log.i(TAG, "[TransportManager] ${entries.size} persistierte Einträge in Retry-Queue geladen")
    }

    fun startRetryJob() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                retryPendingMessages()
            }
        }
    }

    fun stopRetryJob() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun retryPendingMessages() {
        if (retryQueue.isEmpty()) return
        val entries = retryQueue.toList()
        val failedEntries = mutableListOf<RetryEntry>()
        for (entry in entries) {
            val result = sendMessage(entry.peerId, entry.data, entry.uiMessageId)
            if (result.isFailure) {
                val nextCount = entry.retryCount + 1
                if (nextCount >= MAX_RETRIES) {
                    Log.w(TAG, "[TransportManager] Max retries ($MAX_RETRIES) erreicht für ${entry.uiMessageId.take(8)}, gebe auf")
                    emitDeliveryUpdate(entry.uiMessageId, entry.peerId, MessageStatus.FAILED, null)
                    onRetryRemove?.invoke(entry.uiMessageId)
                } else {
                    failedEntries.add(entry.copy(retryCount = nextCount))
                    onRetryAdd?.invoke(entry.uiMessageId, entry.peerId, entry.data, nextCount)
                }
            } else {
                onRetryRemove?.invoke(entry.uiMessageId)
            }
        }
        retryQueue.clear()
        retryQueue.addAll(failedEntries)
    }

    /**
     * Registriert einen Listener für eingehende Nachrichten beim aktiven Transport.
     *
     * E2EE: Wenn eine E2EE-Session mit dem Peer existiert, wird der Payload
     * nach dem Empfang transparent entschlüsselt.
     */
    fun registerMessageListener(listener: (String, ByteArray, TransportType) -> Unit) {
        for (transport in transports) {
            transport.registerListener { peerId, data ->
                val normalizedPeerId = peerId.split("@").first()
                updateRouteHint(normalizedPeerId, transport.type)

                // ═══════════════════════════════════════════════════════════════
                // PING/PONG-PROTOKOLL: Robustes Filtering (mit uiMessageId-Handling)
                // ═══════════════════════════════════════════════════════════════
                // WICHTIG: Nachrichten können mit \u0000<uiMessageId> suffixiert sein.
                // Wir müssen diesen Suffix ENTFERNEN, bevor wir die JSON parsen,
                // sonst bricht das JSON-Parsing und wir können nicht erkennen,
                // ob es ein Ping/Pong ist.
                // ═══════════════════════════════════════════════════════════════
                var isInternal = false
                
                try {
                    val messageText = String(data)
                    
                    // Schritt 1: uiMessageId entfernen (falls vorhanden)
                    val payloadText = if (messageText.contains('\u0000')) {
                        messageText.split('\u0000')[0]
                    } else {
                        messageText
                    }
                    
                    // Schritt 2: Als JSON parsen
                    try {
                        val json = JSONObject(payloadText)
                        when (json.optString("type")) {
                            "crisix_ping" -> {
                                Log.d(TAG, "[registerMessageListener] Ping empfangen von ${normalizedPeerId.take(8)}")
                                val pongPayload = JSONObject().apply {
                                    put("type", "crisix_pong")
                                    put("id", json.getString("id"))
                                }.toString().toByteArray()
                                scope.launch {
                                    transport.send(normalizedPeerId, pongPayload)
                                    Log.d(TAG, "[registerMessageListener] Pong versendet an ${normalizedPeerId.take(8)}")
                                }
                                isInternal = true
                            }
                            "crisix_pong" -> {
                                Log.d(TAG, "[registerMessageListener] Pong empfangen von ${normalizedPeerId.take(8)}")
                                val pingId = json.optString("id")
                                if (pingId.isNotEmpty()) {
                                    val deferred = pendingPings.remove(pingId)
                                    deferred?.complete(true)
                                    Log.d(TAG, "[registerMessageListener] Ping-Deferred komplett für $pingId")
                                }
                                isInternal = true
                            }
                        }
                    } catch (e: Exception) {
                        // JSON-Parsing fehlgeschlagen – nicht als Ping/Pong erkannt
                        Log.d(TAG, "[registerMessageListener] Keine gültige Ping/Pong JSON: ${e.message}")
                    }
                } catch (e: Exception) {
                    // String-Konvertierung fehlgeschlagen
                    Log.d(TAG, "[registerMessageListener] String-Konvertierung fehlgeschlagen: ${e.message}")
                }

                // Schritt 3: Defragmentierung prüfen
                if (!isInternal && Fragmenter.isChunk(data)) {
                    val chunk = Fragmenter.Chunk.fromBytes(data)
                    if (chunk != null) {
                        Log.d(TAG, "[Defrag] Chunk empfangen: ${chunk.messageId.toHex()}#${chunk.chunkIndex}/${chunk.totalChunks}")
                        val reassembled = defragmenter.addChunk(chunk)
                        if (reassembled != null) {
                            Log.i(TAG, "[Defrag] Nachricht reassembliert: ${reassembled.size} bytes")
                            listener(peerId, reassembled, transport.type)
                        }
                    }
                    isInternal = true
                }

                // Schritt 4: Wenn nicht intern, weitergeben an Listener
                if (!isInternal) {
                    // ═══════════════════════════════════════════════════════════════
                    // E2EE: Rohdaten an Listener weiterleiten (KEINE Entschlüsselung hier!)
                    //
                    // WICHTIG: Die Entschlüsselung passiert NUR in CrisixApp.kt!
                    // Der TransportManager darf NICHT selbst entschlüsseln, weil:
                    // 1. Die Session könnte noch nicht aufgebaut sein (Handshake läuft)
                    // 2. Doppelte Fehler (TransportManager + CrisixApp)
                    // 3. Der Listener braucht den Original-Payload, um den Typ zu erkennen
                    //    (z.B. "crisix_e2ee_handshake", "crisix_e2ee_ack", "crisix_e2ee")
                    // ═══════════════════════════════════════════════════════════════
                    listener(peerId, data, transport.type)
                }
            }
        }
    }

    /**
     * Startet alle registrierten Transporte.
     */
    suspend fun startAll() {
        for (transport in transports) {
            try {
                updateConnectionStatus(transport.type, ConnectionState.SEARCHING, detailText = "Starte...")
                transport.start()
                updateConnectionStatus(transport.type, ConnectionState.SEARCHING, detailText = "Gestartet, prüfe Verbindung...")
            } catch (e: Exception) {
                updateConnectionStatus(transport.type, ConnectionState.ERROR, errorMessage = e.message)
            }
        }
    }

    /**
     * Stellt eine manuelle Verbindung zu einem Peer über IP-Adresse her.
     */
    suspend fun connectToPeer(ipAddress: String, displayName: String? = null, port: Int? = null): Result<Peer> {
        val wifiTransport = transports.find { it is WifiTransport } as? WifiTransport
        if (wifiTransport != null) {
            try {
                val ipOnly = ipAddress.split(":")[0]
                val result = wifiTransport.connectToPeer(ipOnly, displayName, port)
                if (result.isSuccess) {
                    val peer = result.getOrNull() ?: return@try
                    Log.i(TAG, "[TransportManager] Verbindung über WifiTransport: ${peer.name} (${peer.id})")
                    val currentPeers = _discoveredPeers.value.toMutableList()
                    if (currentPeers.none { it.id == peer.id }) {
                        currentPeers.add(peer)
                        _discoveredPeers.value = currentPeers
                    }
                    return result
                }
            } catch (e: Exception) { Log.w(TAG, "TransportManager operation failed: ${e.message}", e) }
        }

        val internetTransport = transports.find { it is com.messenger.crisix.transport.internet.InternetTransport }
        if (internetTransport != null) {
            try {
                val addressWithPort = if (port != null) "$ipAddress:$port" else ipAddress
                val result = (internetTransport as com.messenger.crisix.transport.internet.InternetTransport).connectToPeer(addressWithPort, displayName)
                if (result.isSuccess) {
                    val peer = result.getOrNull() ?: return@try
                    Log.i(TAG, "[TransportManager] Verbindung über InternetTransport: ${peer.name} (${peer.id})")
                    val currentPeers = _discoveredPeers.value.toMutableList()
                    if (currentPeers.none { it.id == peer.id }) {
                        currentPeers.add(peer)
                        _discoveredPeers.value = currentPeers
                    }
                    return result
                }
            } catch (e: Exception) { Log.w(TAG, "TransportManager operation failed: ${e.message}", e) }
        }

        return Result.failure(Exception("Kein Transport verfügbar für $ipAddress"))
    }

    /**
     * Fügt einen Kontakt-Peer zur Liste hinzu, ohne automatische Netzwerksuche.
     */
    fun addContactPeer(peerId: String, displayName: String? = null) {
        val currentPeers = _discoveredPeers.value.toMutableList()
        val name = displayName ?: peerId.take(8)

        val alreadyExists = currentPeers.any { it.id == peerId || it.id.startsWith("$peerId@") }
        if (!alreadyExists) {
            currentPeers.add(Peer(id = peerId, name = name))
            _discoveredPeers.value = currentPeers
            Log.i(TAG, "[TransportManager] Kontakt-Peer hinzugefügt: $name ($peerId)")
        }
    }

    /**
     * Gibt einen registrierten Transport anhand seines Typs zurück.
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
        connectivityCallback?.let { cb ->
            try {
                val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (e: Exception) { Log.w(TAG, "TransportManager operation failed: ${e.message}", e) }
        }
        connectivityCallback = null
        appContext = null
        for (transport in transports) {
            transport.stop()
        }
    }
}

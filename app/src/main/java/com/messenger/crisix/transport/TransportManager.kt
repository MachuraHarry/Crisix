package com.messenger.crisix.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Verwaltet alle verfügbaren Transporte und wählt den besten aus.
 * In Phase 0 nur mit dem DummyTransport.
 */
class TransportManager {

    private val transports = mutableListOf<Transport>()

    private val _activeTransport = MutableStateFlow<Transport?>(null)
    val activeTransport: StateFlow<Transport?> = _activeTransport.asStateFlow()

    fun registerTransport(transport: Transport) {
        transports.add(transport)
    }

    /**
     * Wählt den besten verfügbaren Transport aus.
     * Priorität: INTERNET > WIFI_DIRECT > BLUETOOTH_MESH > SMS > DNS_TUNNEL > LORA
     */
    suspend fun selectBestTransport(): Transport? {
        val priorityOrder = listOf(
            TransportType.INTERNET,
            TransportType.WIFI_DIRECT,
            TransportType.BLUETOOTH_MESH,
            TransportType.SMS,
            TransportType.DNS_TUNNEL,
            TransportType.LORA
        )

        for (type in priorityOrder) {
            val transport = transports.find { it.type == type }
            if (transport != null && transport.isAvailable()) {
                _activeTransport.value = transport
                return transport
            }
        }
        return null
    }

    /**
     * Gibt die Capabilities des aktuell aktiven Transports zurück.
     * Fallback auf volle Capabilities, falls kein Transport aktiv ist.
     */
    fun getCurrentCapabilities(): TransportCapabilities {
        return _activeTransport.value?.capabilities
            ?: TransportCapabilities() // Volle Capabilities als Fallback
    }
}

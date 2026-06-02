package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionsState(
    val connectionStatuses: Map<TransportType, ConnectionStatus> = emptyMap(),
    val discoveredPeers: List<Peer> = emptyList(),
    val dnsTestResult: String? = null,
    val isDnsTestRunning: Boolean = false,
)

class ConnectionsViewModel(
    private val transportManager: TransportManager?,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionsState())
    val state: StateFlow<ConnectionsState> = _state.asStateFlow()

    fun collectFromTransportManager() {
        val tm = transportManager ?: return
        viewModelScope.launch {
            tm.connectionStatuses.collect { statuses ->
                _state.update { it.copy(connectionStatuses = statuses) }
            }
        }
        viewModelScope.launch {
            tm.discoveredPeers.collect { peers ->
                _state.update { it.copy(discoveredPeers = peers) }
            }
        }
    }

    fun refreshStatus() {
        val tm = transportManager ?: return
        viewModelScope.launch {
            _state.update { it.copy(connectionStatuses = tm.connectionStatuses.value) }
        }
    }

    fun setDnsTestRunning(running: Boolean) {
        _state.update { it.copy(isDnsTestRunning = running) }
    }

    fun setDnsTestResult(result: String?) {
        _state.update { it.copy(dnsTestResult = result, isDnsTestRunning = false) }
    }
}

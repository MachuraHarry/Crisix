package com.messenger.crisix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Creates a [Flow] of [Peer] from a [StateFlow] of peer lists by emitting
 * the latest peer each time the list changes.
 *
 * This is the standard implementation used by transports that track discovered
 * peers via a MutableStateFlow (BLE, Relay, DNS-Tunnel).
 */
fun stateFlowDiscoverPeers(
    scope: CoroutineScope?,
    peersState: StateFlow<List<Peer>>
): Flow<Peer> = callbackFlow {
    val job = scope?.launch {
        peersState.collect { peers ->
            val last = peers.lastOrNull()
            if (last != null) trySend(last)
        }
    }
    awaitClose { job?.cancel() }
}

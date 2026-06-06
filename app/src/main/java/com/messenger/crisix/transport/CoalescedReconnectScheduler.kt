package com.messenger.crisix.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CoalescedReconnectScheduler {

    companion object {
        private const val TAG = "CoalescedReconnect"
    }

    private val minIntervalPerTransport = mapOf(
        TransportType.RELAY to 2_000L,
        TransportType.INTERNET to 5_000L,
        TransportType.WIFI_DIRECT to 1_000L,
        TransportType.BLUETOOTH_MESH to 3_000L,
        TransportType.DNS_TUNNEL to 10_000L,
    )

    private val pendingReconnects = ConcurrentHashMap<TransportType, Long>()

    private val _permissions = MutableSharedFlow<TransportType>(extraBufferCapacity = 16)
    val permissions: SharedFlow<TransportType> = _permissions.asSharedFlow()

    fun scheduleReconnect(transport: TransportType, scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        val minInterval = minIntervalPerTransport[transport] ?: 5_000L
        val earliest = now + minInterval
        val planned = pendingReconnects[transport] ?: 0L
        if (planned > now) {
            Log.d(TAG, "${transport} Reconnect bereits geplant (in ${planned - now}ms)")
            return
        }
        pendingReconnects[transport] = earliest
        Log.i(TAG, "${transport} Reconnect geplant in ${minInterval}ms")
        scope.launch {
            delay(minInterval)
            if (pendingReconnects[transport] == earliest) {
                pendingReconnects.remove(transport)
                _permissions.emit(transport)
            }
        }
    }

    fun cancel(transport: TransportType) {
        pendingReconnects.remove(transport)
    }

    fun cancelAll() {
        pendingReconnects.clear()
    }
}

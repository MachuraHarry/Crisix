package com.messenger.crisix.e2ee

import android.util.Base64
import android.util.Log
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.crypto.E2eeSessionState
import com.messenger.crisix.crypto.HandshakeInitData
import com.messenger.crisix.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class E2EEHandshakeOrchestrator(
    private val transportManager: TransportManager,
    private val e2eeManager: E2eeManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HandshakeOrch"
    }

    private val pendingMap = ConcurrentHashMap<String, HandshakeInitData>()
    private val peerLocks = ConcurrentHashMap<String, Any>()

    private val _pendingHandshakes = MutableStateFlow<Map<String, HandshakeInitData>>(emptyMap())
    val pendingHandshakes: StateFlow<Map<String, HandshakeInitData>> = _pendingHandshakes.asStateFlow()

    fun initiateHandshake(peerId: String): Boolean = synchronized(peerLocks.computeIfAbsent(peerId) { Any() }) {
        if (e2eeManager.hasSession(peerId)) {
            val state = e2eeManager.getSessionState(peerId)
            if (state.state == E2eeSessionState.ACTIVE) {
                return@synchronized false
            }
            e2eeManager.closeSession(peerId)
        }
        if (e2eeManager.isHandshaking(peerId)) {
            return@synchronized false
        }
        if (pendingMap.containsKey(peerId)) {
            return@synchronized false
        }

        Log.i(TAG, "Initiiere E2EE-Handshake mit ${peerId.take(8)}")
        val handshakeData = e2eeManager.createHandshake()
        if (handshakeData == null) {
            Log.e(TAG, "Handshake-Erstellung fehlgeschlagen für ${peerId.take(8)}")
            return@synchronized false
        }

        e2eeManager.setHandshaking(peerId)
        putPending(peerId, handshakeData)
        sendHandshakePayload(peerId, handshakeData)
        return@synchronized true
    }

    fun completeHandshakeAsInitiator(peerId: String, preKeyMessageJson: String): Boolean {
        val pendingData = removePending(peerId) ?: return false

        val success = e2eeManager.completeHandshakeAsInitiator(
            peerId = peerId,
            peerBundle = pendingData.peerBundle,
            peerPreKeyMessageJson = preKeyMessageJson,
            ownEphemeralPrivateKey = pendingData.ownEphemeralPrivateKey,
            ownEphemeralPublicKey = pendingData.ownEphemeralPublicKey
        )

        if (success) {
            Log.i(TAG, "Handshake als Initiator vervollständigt mit ${peerId.take(8)}")
            e2eeManager.setSessionActive(peerId)
            e2eeManager.completeHandshakeRetry(peerId)
        } else {
            Log.e(TAG, "Handshake-Completion fehlgeschlagen für ${peerId.take(8)}")
            e2eeManager.startHandshakeRetry(peerId, pendingData, scope)
        }

        return success
    }

    fun removePending(peerId: String): HandshakeInitData? {
        val removed = pendingMap.remove(peerId) ?: return null
        publishPending()
        return removed
    }

    fun hasPending(peerId: String): Boolean = pendingMap.containsKey(peerId)

    fun scheduleRetry(peerId: String, scope: CoroutineScope) {
        val data = removePending(peerId) ?: return
        e2eeManager.startHandshakeRetry(peerId, data, scope)
    }

    fun resendHandshake(peerId: String, handshakeData: HandshakeInitData) {
        Log.i(TAG, "Idempotenter Retry-Handshake fuer $peerId")
        sendHandshakePayload(peerId, handshakeData)
    }

    private fun putPending(peerId: String, data: HandshakeInitData) {
        pendingMap[peerId] = data
        publishPending()
    }

    private fun publishPending() {
        _pendingHandshakes.value = pendingMap.toMap()
    }

    private fun sendHandshakePayload(peerId: String, handshakeData: HandshakeInitData) {
        val handshakePayload = JSONObject().apply {
            put("type", "crisix_e2ee_handshake")
            put("data", handshakeData.preKeyBundleJson)
            put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
        }.toString().toByteArray()

        scope.launch {
            transportManager.sendMessage(peerId, handshakePayload)
                .onSuccess {
                    Log.i(TAG, "E2EE-Handshake initiiert für ${peerId.take(8)}")
                }
                .onFailure { error ->
                    Log.w(TAG, "Handshake-Fehler: ${error.message} → starte Retry")
                    e2eeManager.startHandshakeRetry(peerId, handshakeData, scope)
                }
        }
    }
}

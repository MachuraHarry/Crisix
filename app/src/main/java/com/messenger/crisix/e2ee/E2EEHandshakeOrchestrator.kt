package com.messenger.crisix.e2ee

import android.util.Base64
import android.util.Log
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.crypto.HandshakeInitData
import com.messenger.crisix.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class E2EEHandshakeOrchestrator(
    private val transportManager: TransportManager,
    private val e2eeManager: E2eeManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HandshakeOrch"
    }

    /**
     * Initiiert einen E2EE-Handshake mit einem Peer.
     * @return true wenn der Handshake gestartet wurde, false bei Fehler
     */
    fun initiateHandshake(
        peerId: String,
        pendingHandshakes: MutableMap<String, HandshakeInitData>,
    ): Boolean {
        if (e2eeManager.hasSession(peerId) || e2eeManager.isHandshaking(peerId)) {
            return false
        }

        Log.i(TAG, "Initiiere E2EE-Handshake mit ${peerId.take(8)}")

        val handshakeData = e2eeManager.createHandshake()
        if (handshakeData == null) {
            Log.e(TAG, "Handshake-Erstellung fehlgeschlagen für ${peerId.take(8)}")
            return false
        }

        e2eeManager.setHandshaking(peerId)
        pendingHandshakes[peerId] = handshakeData

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
                    e2eeManager.startHandshakeRetry(peerId, scope)
                }
        }

        return true
    }

    /**
     * Vervollständigt einen Handshake als Initiator nach ACK-Empfang.
     */
    fun completeHandshakeAsInitiator(
        peerId: String,
        pendingHandshakes: MutableMap<String, HandshakeInitData>,
        preKeyMessageJson: String,
    ): Boolean {
        val pendingData = pendingHandshakes.remove(peerId) ?: return false

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
            e2eeManager.startHandshakeRetry(peerId, scope)
        }

        return success
    }
}

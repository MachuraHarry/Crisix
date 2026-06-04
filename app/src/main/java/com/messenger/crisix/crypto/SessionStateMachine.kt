package com.messenger.crisix.crypto

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

enum class E2eeSessionState {
    NONE,
    HANDSHAKING,
    ACTIVE,
    STALE,
    COMPROMISED
}

data class QueuedMessage(
    val payload: ByteArray,
    val uiMessageId: String?,
    val encryptDirectly: (ByteArray) -> String?,
    val onFlushed: (Boolean, String?) -> Unit
)

class SessionStateMachine(private val peerId: String) {

    companion object {
        private const val TAG = "SessionStateMachine"
    }

    var state: E2eeSessionState = E2eeSessionState.NONE
        private set

    var handshakeNonce: ByteArray? = null
        private set

    val establishedAt: Long
        get() = _establishedAt

    val lastUsedAt: Long
        get() = _lastUsedAt

    private var _establishedAt: Long = 0L
    private var _lastUsedAt: Long = 0L

    private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()

    fun transitionTo(newState: E2eeSessionState): Boolean {
        val oldState = state
        when {
            newState == oldState -> {
                Log.d(TAG, "[$peerId] State bereits $newState — no-op")
                return true
            }
            canTransition(oldState, newState) -> {
                Log.i(TAG, "[$peerId] Transition: $oldState → $newState")
                state = newState
                when (newState) {
                    E2eeSessionState.HANDSHAKING -> {
                        handshakeNonce = ByteArray(8).also {
                            SecureRandom().nextBytes(it)
                        }
                    }
                    E2eeSessionState.ACTIVE -> {
                        _establishedAt = System.currentTimeMillis()
                        _lastUsedAt = _establishedAt
                        flushQueue()
                    }
                    E2eeSessionState.COMPROMISED -> {
                        _establishedAt = 0L
                    }
                    else -> {}
                }
                return true
            }
            else -> {
                Log.w(TAG, "[$peerId] Ungueltige Transition: $oldState → $newState")
                return false
            }
        }
    }

    fun touch() {
        _lastUsedAt = System.currentTimeMillis()
    }

    fun isStale(maxAgeMs: Long = 7 * 24 * 3600_000L): Boolean {
        if (state != E2eeSessionState.ACTIVE) return false
        return System.currentTimeMillis() - _lastUsedAt > maxAgeMs
    }

    fun enqueueMessage(message: QueuedMessage) {
        messageQueue.add(message)
        Log.d(TAG, "[$peerId] Message queued — queue size: ${messageQueue.size}")
    }

    fun queueSize(): Int = messageQueue.size

    fun clearQueue(reason: String) {
        val count = messageQueue.size
        messageQueue.forEach { it.onFlushed(false, null) }
        messageQueue.clear()
        if (count > 0) {
            Log.w(TAG, "[$peerId] Queue geleert ($count messages) wegen: $reason")
        }
    }

    fun hasHandshakeNonce(): Boolean = handshakeNonce != null

    fun resolveConcurrentHandshakes(theirNonce: ByteArray): Boolean {
        val myNonce = handshakeNonce ?: return false
        for (i in 0 until 8) {
            val myByte = myNonce[i].toInt() and 0xFF
            val theirByte = theirNonce[i].toInt() and 0xFF
            if (myByte > theirByte) return true
            if (myByte < theirByte) return false
        }
        return true
    }

    fun reset() {
        state = E2eeSessionState.NONE
        handshakeNonce = null
        _establishedAt = 0L
        _lastUsedAt = 0L
        clearQueue("Session-Reset")
    }

    fun isReadyForEncryption(): Boolean {
        return state == E2eeSessionState.ACTIVE || state == E2eeSessionState.STALE
    }

    private fun flushQueue() {
        val count = messageQueue.size
        if (count == 0) return
        Log.i(TAG, "[$peerId] Flushe $count queued messages...")

        var msg = messageQueue.poll()
        while (msg != null) {
            try {
                val encrypted = msg.encryptDirectly(msg.payload)
                if (encrypted != null) {
                    msg.onFlushed(true, encrypted)
                    Log.d(TAG, "[$peerId] Queued message encrypted & flushed")
                } else {
                    msg.onFlushed(false, null)
                    Log.e(TAG, "[$peerId] Encryption failed for queued message")
                }
            } catch (e: Exception) {
                msg.onFlushed(false, null)
                Log.e(TAG, "[$peerId] Queue flush error", e)
            }
            msg = messageQueue.poll()
        }
    }

    private fun canTransition(from: E2eeSessionState, to: E2eeSessionState): Boolean {
        return when (from) {
            E2eeSessionState.NONE -> to in setOf(
                E2eeSessionState.HANDSHAKING,
                E2eeSessionState.ACTIVE
            )
            E2eeSessionState.HANDSHAKING -> to in setOf(
                E2eeSessionState.ACTIVE,
                E2eeSessionState.NONE,
                E2eeSessionState.COMPROMISED
            )
            E2eeSessionState.ACTIVE -> to in setOf(
                E2eeSessionState.STALE,
                E2eeSessionState.COMPROMISED,
                E2eeSessionState.NONE
            )
            E2eeSessionState.STALE -> to in setOf(
                E2eeSessionState.HANDSHAKING,
                E2eeSessionState.ACTIVE,
                E2eeSessionState.COMPROMISED,
                E2eeSessionState.NONE
            )
            E2eeSessionState.COMPROMISED -> to in setOf(
                E2eeSessionState.HANDSHAKING,
                E2eeSessionState.NONE
            )
        }
    }
}

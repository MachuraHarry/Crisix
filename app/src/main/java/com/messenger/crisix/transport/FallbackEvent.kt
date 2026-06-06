package com.messenger.crisix.transport

sealed class FallbackEvent {
    abstract val peerId: String
    abstract val timestamp: Long

    data class SwitchedTransport(
        override val peerId: String,
        val from: TransportType?,
        val to: TransportType,
        val reason: SwitchReason,
    ) : FallbackEvent() {
        override val timestamp: Long = System.currentTimeMillis()
    }

    data class HandshakeStarted(
        override val peerId: String,
        val transport: TransportType,
        val trigger: HandshakeTrigger,
    ) : FallbackEvent() {
        override val timestamp: Long = System.currentTimeMillis()
    }

    data class HandshakeSucceeded(
        override val peerId: String,
        val transport: TransportType,
        val attemptCount: Int,
    ) : FallbackEvent() {
        override val timestamp: Long = System.currentTimeMillis()
    }

    data class HandshakeFailed(
        override val peerId: String,
        val transport: TransportType,
        val reason: String,
    ) : FallbackEvent() {
        override val timestamp: Long = System.currentTimeMillis()
    }

    data class SessionMarkedCompromised(
        override val peerId: String,
        val reason: String,
    ) : FallbackEvent() {
        override val timestamp: Long = System.currentTimeMillis()
    }
}

enum class SwitchReason { Failure, UserPreference, FirstConnection, NoPeers }
enum class HandshakeTrigger { SendRequest, ReceiveRequest, ReHandshake }

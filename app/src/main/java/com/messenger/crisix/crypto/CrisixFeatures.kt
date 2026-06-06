package com.messenger.crisix.crypto

object CrisixFeatures {
    /**
     * Aktiviert die entschärfte Decrypt-Failure-Logik (Phase 1.1-1.3).
     * Default: true.
     */
    var softDecryptFailure: Boolean = true

    /**
     * Aktiviert EWMA-basiertes Transport-Scoring (Phase 3.1).
     * Default: true.
     */
    var adaptiveTransportScoring: Boolean = true

    /**
     * Aktiviert idempotente Handshake-Retries (Phase 1.8).
     * Default: true.
     */
    var idempotentHandshakeRetries: Boolean = true

    /**
     * Aktiviert sticky Session-Transport (Phase 3.4).
     * Default: true.
     */
    var stickySessionTransport: Boolean = true

    /**
     * Aktiviert adaptive ACK-Timeouts pro Transport (Phase 5.6).
     * Default: true.
     */
    var adaptiveAckTimeouts: Boolean = true

    /**
     * Aktiviert Pro-Peer-Send-Mutex (Phase 5.1).
     * Default: true.
     */
    var proPeerSendMutex: Boolean = true

    /**
     * Aktiviert Hash-basierte Cross-Transport-Dedup (Phase 5.3).
     * Default: true.
     */
    var crossTransportDedup: Boolean = true
}

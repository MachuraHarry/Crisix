package com.messenger.crisix.crypto

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verwaltet automatische Retry-Mechaniken für fehlgeschlagene E2EE-Handshakes.
 *
 * Features:
 * - Exponential Backoff: 1s → 2s → 4s → 8s → 16s → 30s
 * - Timeout pro Versuch: 20 Sekunden
 * - Max 5 Versuche
 * - Thread-safe via ConcurrentHashMap
 * - Cancellable via Coroutine-Scopes
 *
 * Flow:
 * 1. `initializeRetry(peerId)` - Startet Retry-Sequenz
 * 2. `onRetryAttempt()` - Callback: "Versuche Handshake erneut..."
 * 3. Wenn erfolgreich: `clearRetryState(peerId)`
 * 4. Wenn max retries: `onRetryExhausted()` - Benachrichtigung an UI
 */
class HandshakeRetryManager {

    companion object {
        private const val TAG = "HandshakeRetryManager"

        // ════════════════════════════════════════════════════════════════
        // RETRY-KONFIGURATION
        // ════════════════════════════════════════════════════════════════

        /** Timeout für einen einzelnen Handshake-Versuch (20 Sekunden) */
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L

        /** Startintervall für Backoff (1 Sekunde) */
        private const val INITIAL_BACKOFF_MS = 1_000L

        /** Maximales Backoff-Intervall (30 Sekunden) */
        private const val MAX_BACKOFF_MS = 30_000L

        /** Maximale Anzahl Retry-Versuche */
        private const val MAX_RETRY_ATTEMPTS = 5

        /** Backoff-Multiplikator (exponentiell) */
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    // ════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ════════════════════════════════════════════════════════════════

    /**
     * Tracks Retry-Status für jeden Peer.
     * Key: peerId (normalized), Value: RetryState
     */
    private val retryStates = ConcurrentHashMap<String, RetryState>()

    /**
     * Coroutine-Scopes für Timeout + Retry-Verzögerung
     * Ermöglicht Cancellation
     */
    private val retryScopes = ConcurrentHashMap<String, CoroutineScope>()

    // ════════════════════════════════════════════════════════════════
    // CALLBACKS (werden von außen gesetzt)
    // ════════════════════════════════════════════════════════════════

    var onRetryAttempt: ((peerId: String, attemptNumber: Int, delayMs: Long) -> Unit)? = null
    var onRetryExhausted: ((peerId: String) -> Unit)? = null
    var onRetrySuccess: ((peerId: String) -> Unit)? = null
    var onRetryTimeout: ((peerId: String, attemptNumber: Int) -> Unit)? = null

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Initialisiert Retry-Versuch für einen Handshake.
     * Wird aufgerufen, wenn:
     * - Initialer Handshake fehlschlägt
     * - ACK nicht empfangen wird (Timeout)
     * - Vorheriger Retry fehlgeschlagen
     */
    fun initializeRetry(peerId: String, scope: CoroutineScope) {
        val normalized = peerId.split("@").first()
        Log.d(TAG, "[initializeRetry] Starte Retry-Logik für $normalized")

        // Bestehenden Retry abbrechen
        retryScopes[normalized]?.cancel()

        // Neuen Scope für diesen Peer erstellen
        retryScopes[normalized] = scope

        // Retry-State initialisieren (falls noch nicht vorhanden)
        if (!retryStates.containsKey(normalized)) {
            retryStates[normalized] = RetryState(
                peerId = normalized,
                attemptCount = 0,
                startTimeMs = System.currentTimeMillis()
            )
        }

        // Ersten Retry-Versuch starten
        performRetry(normalized, scope)
    }

    /**
     * Markiert Handshake als erfolgreich.
     * Bereinigt Retry-State und stoppt weitere Versuche.
     */
    fun clearRetryState(peerId: String) {
        val normalized = peerId.split("@").first()
        Log.d(TAG, "[clearRetryState] Handshake erfolgreich für $normalized ✅")

        onRetrySuccess?.invoke(normalized)

        retryScopes[normalized]?.cancel()
        retryScopes.remove(normalized)
        retryStates.remove(normalized)
    }

    /**
     * Gibt aktuellen Retry-Status zurück.
     * Nützlich für UI-Updates.
     */
    fun getRetryStatus(peerId: String): RetryStatus? {
        val normalized = peerId.split("@").first()
        val state = retryStates[normalized] ?: return null

        return RetryStatus(
            peerId = normalized,
            attemptNumber = state.attemptCount,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            lastErrorTime = state.lastErrorTimeMs,
            nextRetryTime = state.nextRetryTimeMs
        )
    }

    /**
     * Beendet manuell alle Retry-Versuche für einen Peer.
     */
    fun cancelRetry(peerId: String) {
        val normalized = peerId.split("@").first()
        Log.d(TAG, "[cancelRetry] Breche Retry ab für $normalized")

        retryScopes[normalized]?.cancel()
        retryScopes.remove(normalized)
        retryStates.remove(normalized)
    }

    /**
     * Beendet alle Retry-Operationen (z.B. beim Logout).
     */
    fun cancelAllRetries() {
        Log.d(TAG, "[cancelAllRetries] Breche alle Retries ab")
        retryScopes.values.forEach { it.cancel() }
        retryScopes.clear()
        retryStates.clear()
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════

    private fun performRetry(peerId: String, scope: CoroutineScope) {
        val state = retryStates[peerId] ?: return

        // Attempt Counter erhöhen
        state.attemptCount++
        state.lastErrorTimeMs = System.currentTimeMillis()

        Log.d(TAG, "[performRetry] Versuch ${state.attemptCount}/$MAX_RETRY_ATTEMPTS für $peerId")

        // Max Versuche erreicht?
        if (state.attemptCount > MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "[performRetry] ❌ Max Versuche ($MAX_RETRY_ATTEMPTS) für $peerId erreicht")
            onRetryExhausted?.invoke(peerId)
            retryStates.remove(peerId)
            return
        }

        // Backoff-Verzögerung berechnen (exponentiell)
        val delayMs = calculateBackoffDelay(state.attemptCount)
        state.nextRetryTimeMs = System.currentTimeMillis() + delayMs

        Log.d(TAG, "[performRetry] Nächster Versuch in ${delayMs}ms...")

        // Callback: "Versuche erneut..."
        onRetryAttempt?.invoke(peerId, state.attemptCount, delayMs)

        // Starte asynchronen Retry nach Backoff-Delay
        scope.launch {
            try {
                // Warte Backoff-Verzögerung
                delay(delayMs)

                // HIER WIRD DER HANDSHAKE ERNEUT VERSUCHT
                // (externe Komponente ruft retry-Handler auf)

                // Timeout-Job für Handshake starten
                val timeoutJob = launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    Log.w(TAG, "[performRetry] ⏱️ Timeout für $peerId nach $HANDSHAKE_TIMEOUT_MS ms")
                    onRetryTimeout?.invoke(peerId, state.attemptCount)
                    // Nächster Retry wird automatisch ausgelöst
                    performRetry(peerId, scope)
                }

                // Wenn Handshake erfolgreich, timeout cancelen
                // (wird von außen via clearRetryState() aufgerufen)

            } catch (e: CancellationException) {
                Log.d(TAG, "[performRetry] Retry für $peerId abgebrochen")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[performRetry] Fehler bei Retry $peerId: ${e.message}")
                performRetry(peerId, scope)
            }
        }
    }

    /**
     * Berechnet exponentielles Backoff-Intervall.
     * 1s → 2s → 4s → 8s → 16s → 30s (max)
     */
    private fun calculateBackoffDelay(attemptNumber: Int): Long {
        val exponential = (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, (attemptNumber - 1).toDouble())).toLong()
        return exponential.coerceAtMost(MAX_BACKOFF_MS)
    }

    // ════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════════════════════════

    /**
     * Interner State für einen laufenden Handshake-Retry
     */
    data class RetryState(
        val peerId: String,
        var attemptCount: Int = 0,
        var startTimeMs: Long = 0,
        var lastErrorTimeMs: Long = 0,
        var nextRetryTimeMs: Long = 0
    )

    /**
     * Öffentlicher Status-Report für UI
     */
    data class RetryStatus(
        val peerId: String,
        val attemptNumber: Int,
        val maxAttempts: Int,
        val lastErrorTime: Long,
        val nextRetryTime: Long
    ) {
        fun isRetrying() = attemptNumber in 1..maxAttempts
        fun timeUntilNextRetry() = (nextRetryTime - System.currentTimeMillis()).coerceAtLeast(0)
    }
}

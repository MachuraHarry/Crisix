package com.messenger.crisix.crypto

import android.util.Log
import com.messenger.crisix.transport.internet.CryptoHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Verwaltet Out-of-Order-Message-Decryption mittels Chain-Key-Cache.
 *
 * Problem:
 * - Netzwerk kann Nachrichten umsortieren
 * - Wenn Message #50 vor Message #30 ankommt, können wir #30 nicht dekryptieren
 * - Wir müssen alte Chain-Keys speichern für Nachrichten, die wir überspringen
 *
 * Lösung:
 * - Speichere last 100 Chain-Keys mit ihrer Nachrichtennummer
 * - Versuche Out-of-Order-Decryption mit gespeicherten Keys
 * - Automatischer Cleanup nach 100 Messages
 *
 * Security:
 * - Alte Keys werden nach Nutzung sofort gelöscht (Forward Secrecy)
 * - Cache-Größe begrenzt (max 100 Messages)
 * - Keys werden aus Memory gelöscht wenn zu alt
 *
 * Flow:
 * 1. Message #50 ankommt → speichere Chain-Key[0..49] für zukünftige Nachrichten
 * 2. Message #30 ankommt später → versuche mit gespeichertem Key
 * 3. Erfolg → lösche Key, markiere Message als dekryptiert
 * 4. Cleanup → lösche Keys älter als 100 Messages
 */
class OutOfOrderMessageHandler {

    companion object {
        private const val TAG = "OutOfOrderMessageHandler"

        // ════════════════════════════════════════════════════════════════
        // CACHE CONFIGURATION
        // ════════════════════════════════════════════════════════════════

        /** Maximale Anzahl gecachter Chain-Keys (100 Messages) */
        private const val MAX_CACHE_SIZE = 100

        /** Nachrichten älter als dieses Fenster können nicht mehr dekryptiert werden */
        private const val MESSAGE_WINDOW = 100

        /** Maximale Anzahl Nachrichten, die ohne Zwischen-Nachrichten übersprungen werden dürfen */
        const val MAX_SKIP = 200
    }

    /**
     * Gekachte Chain-Keys für Out-of-Order-Decryption.
     * Key: messageIndex, Value: ChainKeyData
     */
    private val chainKeyCache = ConcurrentHashMap<Int, CachedChainKey>()

    /**
     * Aktuelle maximale Message-Index die wir gesehen haben.
     * Für Cleanup und Validation.
     */
    private var maxMessageIndexSeen = 0

    /**
     * Speichert einen Chain-Key für Out-of-Order-Decryption.
     *
     * Wird aufgerufen, wenn wir eine Nachricht entschlüsseln und
     * die nächste erwartete Nachricht nicht die nächste in der Reihenfolge ist.
     *
     * @param messageIndex Die Index der Nachricht
     * @param chainKey Der Chain-Key für diese Message-Index
     * @param peerId Für Logging
     */
    fun cacheChainKey(messageIndex: Int, chainKey: ByteArray, peerId: String) {
        try {
            if (messageIndex > maxMessageIndexSeen) {
                if (messageIndex - maxMessageIndexSeen > MAX_SKIP) {
                    Log.w(TAG, "MAX_SKIP überschritten: messageIndex=$messageIndex, maxSeen=$maxMessageIndexSeen — Chain-Key nicht gecacht")
                    return
                }
                maxMessageIndexSeen = messageIndex

                if (chainKeyCache.size >= MAX_CACHE_SIZE) {
                    cleanupOldKeys(peerId)
                }

                chainKeyCache[messageIndex] = CachedChainKey(
                    chainKey = chainKey.copyOf(),
                    cachedAt = System.currentTimeMillis()
                )

                Log.d(TAG, "✅ Chain-Key für Message #$messageIndex gecacht (Cache-Größe: ${chainKeyCache.size})")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Cachen des Chain-Keys: ${e.message}")
        }
    }

    /**
     * Versucht, eine Out-of-Order-Message zu dekryptieren.
     *
     * Sucht nach einem gecachten Chain-Key der die Nachricht dekryptieren kann.
     *
     * @param messageIndex Die Index der Nachricht
     * @param nonce Die Nonce der Nachricht
     * @param ciphertext Der verschlüsselte Text
     * @param peerId Für Logging
     * @return Dekryptierter Plaintext oder null wenn nicht möglich
     */
    fun tryDecryptOutOfOrder(
        messageIndex: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
        peerId: String
    ): ByteArray? {
        if (messageIndex - maxMessageIndexSeen > MAX_SKIP) {
            Log.w(TAG, "MAX_SKIP überschritten: messageIndex=$messageIndex, maxSeen=$maxMessageIndexSeen — Out-of-Order-Decryption abgelehnt")
            return null
        }
        if (messageIndex < maxMessageIndexSeen - MESSAGE_WINDOW) {
            Log.w(TAG, "⚠️ Message #$messageIndex ist zu alt (> $MESSAGE_WINDOW Messages älter)")
            return null
        }

        // Suche einen gecachten Chain-Key
        for (cachedIndex in messageIndex downTo (messageIndex - MESSAGE_WINDOW).coerceAtLeast(0)) {
            val cachedKey = chainKeyCache[cachedIndex] ?: continue

            try {
                // Versuche Decryption mit diesem Chain-Key
                val plaintext = CryptoHelper.aesGcmDecrypt(
                    ciphertext, cachedKey.chainKey, nonce
                )

                // Erfolg! Entferne verwendeten Key und Cache davor
                Log.i(TAG, "✅ Out-of-Order-Message #$messageIndex dekryptiert mit Key #$cachedIndex")
                
                // Cleanup: Entferne Cache-Einträge bis zu dieser Index
                cleanupUpTo(cachedIndex)

                return plaintext

            } catch (e: Exception) {
                // Dieser Key passt nicht, probiere nächsten
                continue
            }
        }

        Log.w(TAG, "⚠️ Konnte Out-of-Order-Message #$messageIndex nicht dekryptieren — kein passender Key im Cache")
        return null
    }

    /**
     * Gibt den höchsten bisher gesehenen Message-Index zurück.
     */
    fun getMaxMessageIndexSeen(): Int = maxMessageIndexSeen

    /**
     * Prüft, ob der angegebene messageIndex das MAX_SKIP-Limit überschreitet.
     */
    fun isSkipLimitExceeded(messageIndex: Int): Boolean {
        return messageIndex - maxMessageIndexSeen > MAX_SKIP
    }

    /**
     * Gibt den aktuellen Cache-Status zurück.
     */
    fun getCacheStatus(): CacheStatus {
        return CacheStatus(
            cacheSize = chainKeyCache.size,
            maxMessageIndexSeen = maxMessageIndexSeen,
            oldestCachedIndex = chainKeyCache.keys.minOrNull() ?: 0,
            newestCachedIndex = chainKeyCache.keys.maxOrNull() ?: 0
        )
    }

    /**
     * Löscht alle Cache-Einträge (z.B. bei Session-Cleanup).
     */
    fun clearCache() {
        chainKeyCache.clear()
        maxMessageIndexSeen = 0
        Log.i(TAG, "✅ Out-of-Order-Cache gelöscht")
    }

    /**
     * Löscht alle Chain-Keys, die älter als maxMessageIndexSeen - MESSAGE_WINDOW sind.
     */
    private fun cleanupOldKeys(peerId: String) {
        val cutoffIndex = maxMessageIndexSeen - MESSAGE_WINDOW
        val toDelete = chainKeyCache.keys.filter { it < cutoffIndex }

        toDelete.forEach { index ->
            chainKeyCache.remove(index)
            Log.d(TAG, "🗑️ Alter Chain-Key #$index gelöscht (> $MESSAGE_WINDOW Messages alt)")
        }
    }

    /**
     * Löscht alle Keys bis zu einer bestimmten Index (inclusive).
     * Wird nach erfolgreicher Out-of-Order-Decryption aufgerufen.
     */
    private fun cleanupUpTo(upToIndex: Int) {
        val toDelete = chainKeyCache.keys.filter { it <= upToIndex }
        
        toDelete.forEach { index ->
            val oldKey = chainKeyCache.remove(index)
            if (oldKey != null) {
                // Wipe the key bytes
                oldKey.chainKey.fill(0)
            }
        }

        Log.d(TAG, "🗑️ ${toDelete.size} alte Chain-Keys gelöscht (bis #$upToIndex)")
    }

    // ════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════════════════════════

    /**
     * Ein gecachter Chain-Key mit Timestamp.
     */
    data class CachedChainKey(
        val chainKey: ByteArray,
        val cachedAt: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedChainKey) return false
            if (!chainKey.contentEquals(other.chainKey)) return false
            if (cachedAt != other.cachedAt) return false
            return true
        }

        override fun hashCode(): Int {
            var result = chainKey.contentHashCode()
            result = 31 * result + cachedAt.hashCode()
            return result
        }
    }

    /**
     * Aktueller Cache-Status für Monitoring.
     */
    data class CacheStatus(
        val cacheSize: Int,
        val maxMessageIndexSeen: Int,
        val oldestCachedIndex: Int,
        val newestCachedIndex: Int
    ) {
        fun getLogMessage(): String {
            return "Cache-Status: ${cacheSize} Keys, Max-Msg: ${maxMessageIndexSeen}, " +
                    "Bereich: #${oldestCachedIndex}..#${newestCachedIndex}"
        }
    }
}

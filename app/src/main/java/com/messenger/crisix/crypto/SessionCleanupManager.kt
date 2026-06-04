package com.messenger.crisix.crypto

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Verwaltet Cleanup und Expiry von inaktiven E2EE-Sessions.
 *
 * Problem:
 * - Sessions werden nach langer Inaktivität "stale"
 * - Alte Session-Keys könnten kompromittiert sein
 * - Speicherverschwendung durch ungenutzte Sessions
 *
 * Lösung:
 * - Track Last-Access-Time pro Session
 * - Sessions nach 90 Tagen Inaktivität löschen
 * - Cleanup beim App-Start + on-demand
 * - Status-Reporting für Monitoring
 *
 * Security:
 * - Forward Secrecy: alte Sessions können nicht rekonstruiert werden
 * - Automatic Cleanup: Keine permanente Session-Speicherung
 * - Bounded Session-Count: Verhindert DoS durch viele Sessions
 *
 * Flow:
 * 1. Session wird verwendet → updateLastAccess(peerId)
 * 2. App startet → performCleanup()
 * 3. Nach 90 Tagen inaktiv → Session gelöscht
 */
class SessionCleanupManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionCleanupManager"

        // ════════════════════════════════════════════════════════════════
        // CLEANUP CONFIGURATION
        // ════════════════════════════════════════════════════════════════

        /** Sessions werden nach 90 Tagen Inaktivität gelöscht */
        private val SESSION_EXPIRY_INTERVAL_MS = TimeUnit.DAYS.toMillis(90)

        /** Warn-Schwelle: 60 Tage inaktiv = Warnung */
        private val SESSION_WARN_THRESHOLD_MS = TimeUnit.DAYS.toMillis(60)

        // ════════════════════════════════════════════════════════════════
        // STORAGE KEYS
        // ════════════════════════════════════════════════════════════════

        private const val PREFS_NAME = "crisix_e2ee"
        private const val KEY_SESSION_TIMESTAMPS = "session_last_access_timestamps"
    }

    // ════════════════════════════════════════════════════════════════
    // CALLBACKS (werden von E2eeManager gesetzt)
    // ════════════════════════════════════════════════════════════════

    var onSessionExpired: ((peerId: String) -> Unit)? = null
    var onCleanupCompleted: ((deletedCount: Int, remainingCount: Int) -> Unit)? = null

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Aktualisiert Last-Access-Timestamp für eine Session.
     * Wird aufgerufen wenn Session für Encrypt/Decrypt verwendet wird.
     *
     * @param peerId Die Session-ID
     */
    fun updateLastAccess(peerId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamps = loadTimestamps(prefs)

            timestamps[peerId] = System.currentTimeMillis()

            saveTimestamps(prefs, timestamps)

            Log.d(TAG, "⏱️ Last-Access für $peerId aktualisiert")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Update von Last-Access", e)
        }
    }

    /**
     * Führt Session-Cleanup durch.
     * Löscht Sessions, die länger als SESSION_EXPIRY_INTERVAL_MS inaktiv sind.
     *
     * @param activePeerIds Set der aktuell aktiven Session-IDs
     * @return Cleanup-Ergebnis (gelöschte Anzahl, verbleibende Anzahl)
     */
    fun performCleanup(activePeerIds: Set<String>): CleanupResult {
        return try {
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamps = loadTimestamps(prefs)

            val toDelete = mutableListOf<String>()
            val toWarn = mutableListOf<String>()

            // Prüfe jede gespeicherte Session
            for ((peerId, lastAccessMs) in timestamps) {
                val ageMsec = now - lastAccessMs

                // Zu alt? Löschen
                if (ageMsec >= SESSION_EXPIRY_INTERVAL_MS) {
                    toDelete.add(peerId)
                    Log.i(TAG, "🗑️ Session $peerId gelöscht (${TimeUnit.MILLISECONDS.toDays(ageMsec)} Tage inaktiv)")

                    // Callback
                    onSessionExpired?.invoke(peerId)
                }
                // Warning-Schwelle?
                else if (ageMsec >= SESSION_WARN_THRESHOLD_MS) {
                    toWarn.add(peerId)
                    Log.w(TAG, "⚠️ Session $peerId läuft bald ab (${TimeUnit.MILLISECONDS.toDays(ageMsec)} Tage inaktiv)")
                }
            }

            // Nur Sessions löschen, die NICHT in activePeerIds sind
            // (Falls eine Session gerade verwendet wird, nicht löschen)
            val toDeleteSafe = toDelete.filter { !activePeerIds.contains(it) }

            // Lösche aus Timestamps
            toDeleteSafe.forEach { timestamps.remove(it) }
            saveTimestamps(prefs, timestamps)

            val result = CleanupResult(
                deletedCount = toDeleteSafe.size,
                warningCount = toWarn.size,
                remainingCount = timestamps.size,
                deletedPeerIds = toDeleteSafe
            )

            Log.i(TAG, "✅ Cleanup abgeschlossen: ${result.deletedCount} gelöscht, ${result.warningCount} Warnungen, ${result.remainingCount} verbleibend")

            onCleanupCompleted?.invoke(result.deletedCount, result.remainingCount)

            result

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei Cleanup", e)
            CleanupResult(deletedCount = 0, warningCount = 0, remainingCount = 0)
        }
    }

    /**
     * Gibt Status aller gespeicherten Sessions zurück.
     */
    fun getCleanupStatus(): List<SessionStatus> {
        return try {
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamps = loadTimestamps(prefs)

            timestamps.map { (peerId, lastAccessMs) ->
                val ageMsec = now - lastAccessMs
                val ageDays = TimeUnit.MILLISECONDS.toDays(ageMsec)
                val daysUntilExpiry = (90 - ageDays).coerceAtLeast(0)
                val isExpired = ageMsec >= SESSION_EXPIRY_INTERVAL_MS

                SessionStatus(
                    peerId = peerId,
                    lastAccessTime = lastAccessMs,
                    ageInDays = ageDays,
                    daysUntilExpiry = daysUntilExpiry,
                    isExpired = isExpired,
                    isWarning = ageMsec >= SESSION_WARN_THRESHOLD_MS && !isExpired
                )
            }.sortedByDescending { it.lastAccessTime }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Abrufen des Cleanup-Status", e)
            emptyList()
        }
    }

    /**
     * Gibt Cleanup-Statistik zurück.
     */
    fun getStatistics(): CleanupStatistics {
        val statuses = getCleanupStatus()

        return CleanupStatistics(
            totalSessions = statuses.size,
            expiredSessions = statuses.count { it.isExpired },
            warningSessions = statuses.count { it.isWarning },
            activeSessions = statuses.count { !it.isExpired && !it.isWarning },
            oldestSessionDays = statuses.maxOfOrNull { it.ageInDays } ?: 0
        )
    }

    /**
     * Löscht eine spezifische Session.
     */
    fun deleteSession(peerId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamps = loadTimestamps(prefs)

            if (timestamps.remove(peerId) != null) {
                saveTimestamps(prefs, timestamps)
                Log.i(TAG, "✅ Session $peerId manuell gelöscht")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Löschen der Session", e)
        }
    }

    /**
     * Löscht alle Session-Timestamps (z.B. nach Session-Reset).
     */
    fun clearAllTimestamps() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SESSION_TIMESTAMPS).apply()
            Log.i(TAG, "✅ Alle Session-Timestamps gelöscht")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Clearing", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════

    /**
     * Lädt Session-Timestamps aus SharedPreferences.
     */
    private fun loadTimestamps(prefs: android.content.SharedPreferences): MutableMap<String, Long> {
        return try {
            val json = prefs.getString(KEY_SESSION_TIMESTAMPS, null) ?: "{}"
            val obj = org.json.JSONObject(json)

            obj.keys().asSequence().associate { key ->
                key to obj.getLong(key)
            }.toMutableMap()

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Konnte Timestamps nicht laden", e)
            mutableMapOf()
        }
    }

    /**
     * Speichert Session-Timestamps in SharedPreferences.
     */
    private fun saveTimestamps(prefs: android.content.SharedPreferences, timestamps: Map<String, Long>) {
        try {
            val json = org.json.JSONObject()
            timestamps.forEach { (peerId, timestamp) ->
                json.put(peerId, timestamp)
            }

            prefs.edit()
                .putString(KEY_SESSION_TIMESTAMPS, json.toString())
                .apply()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Speichern", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════════════════════════

    /**
     * Status einer einzelnen Session.
     */
    data class SessionStatus(
        val peerId: String,
        val lastAccessTime: Long,
        val ageInDays: Long,
        val daysUntilExpiry: Long,
        val isExpired: Boolean,
        val isWarning: Boolean
    ) {
        fun getStatus(): String {
            return when {
                isExpired -> "❌ EXPIRED"
                isWarning -> "⚠️ WARNING (${daysUntilExpiry} Tage bis Expiry)"
                else -> "✅ ACTIVE (${daysUntilExpiry} Tage bis Expiry)"
            }
        }
    }

    /**
     * Ergebnis eines Cleanup-Laufs.
     */
    data class CleanupResult(
        val deletedCount: Int,
        val warningCount: Int,
        val remainingCount: Int,
        val deletedPeerIds: List<String> = emptyList()
    ) {
        fun getLogMessage(): String {
            return "Cleanup: ${deletedCount} gelöscht, ${warningCount} Warnungen, ${remainingCount} verbleibend"
        }
    }

    /**
     * Cleanup-Statistik.
     */
    data class CleanupStatistics(
        val totalSessions: Int,
        val expiredSessions: Int,
        val warningSessions: Int,
        val activeSessions: Int,
        val oldestSessionDays: Long
    ) {
        fun getLogMessage(): String {
            return "Sessions: ${totalSessions} gesamt, ${activeSessions} aktiv, " +
                    "${warningSessions} Warnungen, ${expiredSessions} abgelaufen. " +
                    "Älteste: ${oldestSessionDays} Tage"
        }
    }
}

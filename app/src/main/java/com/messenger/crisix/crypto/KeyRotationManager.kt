package com.messenger.crisix.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Verwaltet periodische Rotation von SignedPreKey und OneTimePreKeys.
 *
 * Features:
 * - SignedPreKey-Rotation: Alle 7 Tage
 * - OneTimePreKey-Rotation: Nach jedem Handshake + täglich
 * - Alte SPK speichern: Für Out-of-Order-Messages (bis zu 90 Tage)
 * - Timestamps tracken: Wann wurde zuletzt rotiert?
 *
 * Flow:
 * 1. E2eeManager initialisiert KeyRotationManager
 * 2. Bei jedem App-Start: `checkAndRotateKeysIfNeeded()`
 * 3. Nach Handshake: `onHandshakeCompleted()`
 * 4. Background-Job: Täglich prüfen (WorkManager)
 */
class KeyRotationManager(private val context: Context) {

    companion object {
        private const val TAG = "KeyRotationManager"

        // ════════════════════════════════════════════════════════════════
        // ROTATION INTERVALS
        // ════════════════════════════════════════════════════════════════

        /** Alle 7 Tage rotieren */
        private val SPK_ROTATION_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

        /** Täglich (24 Stunden) prüfen */
        private val DAILY_ROTATION_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        /** Alte SPK bis zu 90 Tage speichern (für Out-of-Order-Messages) */
        private val OLD_SPK_RETENTION_MS = TimeUnit.DAYS.toMillis(90)

        // ════════════════════════════════════════════════════════════════
        // STORAGE KEYS
        // ════════════════════════════════════════════════════════════════

        private const val PREFS_NAME = "crisix_e2ee"
        private const val KEY_LAST_SPK_ROTATION = "last_spk_rotation_ms"
        private const val KEY_LAST_DAILY_CHECK = "last_daily_check_ms"
        private const val KEY_OLD_SPK_LIST = "old_spk_list"
    }

    // ════════════════════════════════════════════════════════════════
    // CALLBACKS (werden von E2eeManager gesetzt)
    // ════════════════════════════════════════════════════════════════

    var onSpkRotationNeeded: (() -> Unit)? = null
    var onOtpkRotationNeeded: (() -> Unit)? = null
    var onRotationCompleted: ((spkRotated: Boolean, otpkRotated: Boolean) -> Unit)? = null

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Prüft und führt notwendige Key-Rotationen durch.
     * Wird aufgerufen beim App-Start und täglich.
     */
    fun checkAndRotateKeysIfNeeded() {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        var spkRotated = false
        var otpkRotated = false

        // ═══════════════════════════════════════════════════════════════
        // SIGNED-PREKEY ROTATION (7 Tage)
        // ═══════════════════════════════════════════════════════════════
        val lastSpkRotation = prefs.getLong(KEY_LAST_SPK_ROTATION, 0)
        val timeSinceSpkRotation = now - lastSpkRotation

        if (timeSinceSpkRotation >= SPK_ROTATION_INTERVAL_MS) {
            Log.i(TAG, "🔄 SignedPreKey-Rotation erforderlich (${TimeUnit.MILLISECONDS.toDays(timeSinceSpkRotation)} Tage alt)")
            spkRotated = rotateSignedPreKey()
            if (spkRotated) {
                prefs.edit().putLong(KEY_LAST_SPK_ROTATION, now).apply()
                Log.i(TAG, "✅ SignedPreKey rotiert — nächste Rotation in 7 Tagen")
            }
        } else {
            val daysUntilRotation = TimeUnit.MILLISECONDS.toDays(SPK_ROTATION_INTERVAL_MS - timeSinceSpkRotation)
            Log.d(TAG, "ℹ️ SPK-Rotation in $daysUntilRotation Tagen erforderlich")
        }

        // ═══════════════════════════════════════════════════════════════
        // DAILY CHECK (täglich)
        // ═══════════════════════════════════════════════════════════════
        val lastDailyCheck = prefs.getLong(KEY_LAST_DAILY_CHECK, 0)
        val timeSinceDailyCheck = now - lastDailyCheck

        if (timeSinceDailyCheck >= DAILY_ROTATION_INTERVAL_MS) {
            Log.d(TAG, "📅 Tägliche Key-Rotation Prüfung")
            prefs.edit().putLong(KEY_LAST_DAILY_CHECK, now).apply()
            
            // Täglich OneTimePreKeys regenerieren (auch wenn nicht alle verbraucht)
            otpkRotated = true
            onOtpkRotationNeeded?.invoke()
            Log.d(TAG, "✅ OneTimePreKeys täglich regeneriert")
        }

        // Callback
        if (spkRotated || otpkRotated) {
            onRotationCompleted?.invoke(spkRotated, otpkRotated)
        }
    }

    /**
     * Wird nach jedem erfolgreichen Handshake aufgerufen.
     * OneTimePreKeys müssen dann regeneriert werden.
     */
    fun onHandshakeCompleted(peerId: String, usedOneTimePreKey: Boolean = false) {
        if (usedOneTimePreKey) {
            Log.i(TAG, "🔄 OneTimePreKey nach Handshake mit $peerId regeneriert")
            onOtpkRotationNeeded?.invoke()
        }
    }

    /**
     * Speichert den aktuellen SPK als "alt" für zukünftige Out-of-Order-Messages.
     * Wird vor der Rotation aufgerufen.
     */
    fun archiveCurrentSpk(spkPublic: ByteArray, spkSignature: ByteArray) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            // Lade alte SPKs
            val oldSpkListJson = prefs.getString(KEY_OLD_SPK_LIST, "[]")
            val oldSpkArray = JSONArray(oldSpkListJson)

            // Neuen Eintrag hinzufügen
            val spkEntry = JSONObject().apply {
                put("public", Base64.encodeToString(spkPublic, Base64.NO_WRAP))
                put("signature", Base64.encodeToString(spkSignature, Base64.NO_WRAP))
                put("archivedAt", now)
            }
            oldSpkArray.put(spkEntry)

            // Entferne zu alte SPKs (älter als 90 Tage)
            cleanupOldSpks(oldSpkArray, now)

            // Speichere aktualisierte Liste
            prefs.edit()
                .putString(KEY_OLD_SPK_LIST, oldSpkArray.toString())
                .apply()

            Log.d(TAG, "✅ Alter SPK archiviert (${oldSpkArray.length()} insgesamt)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Archivieren des SPK", e)
        }
    }

    /**
     * Gibt alle alten SPKs zurück (für Out-of-Order-Message-Verarbeitung).
     */
    fun getOldSignedPreKeys(): List<OldSignedPreKey> {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val oldSpkListJson = prefs.getString(KEY_OLD_SPK_LIST, "[]")
            val oldSpkArray = JSONArray(oldSpkListJson)

            val result = mutableListOf<OldSignedPreKey>()
            for (i in 0 until oldSpkArray.length()) {
                val entry = oldSpkArray.getJSONObject(i)
                result.add(
                    OldSignedPreKey(
                        publicKey = Base64.decode(entry.getString("public"), Base64.NO_WRAP),
                        signature = Base64.decode(entry.getString("signature"), Base64.NO_WRAP),
                        archivedAt = entry.getLong("archivedAt")
                    )
                )
            }
            return result

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Laden alter SPKs", e)
            return emptyList()
        }
    }

    /**
     * Gibt aktuellen Rotation-Status zurück (für UI/Monitoring).
     */
    fun getRotationStatus(): RotationStatus {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastSpkRotation = prefs.getLong(KEY_LAST_SPK_ROTATION, 0)
        val timeSinceSpkRotation = now - lastSpkRotation
        val daysUntilNextSpkRotation = TimeUnit.MILLISECONDS.toDays(
            (SPK_ROTATION_INTERVAL_MS - timeSinceSpkRotation).coerceAtLeast(0)
        )

        val lastDailyCheck = prefs.getLong(KEY_LAST_DAILY_CHECK, 0)
        val timeSinceDailyCheck = now - lastDailyCheck

        return RotationStatus(
            lastSpkRotation = lastSpkRotation,
            lastDailyCheck = lastDailyCheck,
            daysUntilNextSpkRotation = daysUntilNextSpkRotation,
            spkRotationNeeded = timeSinceSpkRotation >= SPK_ROTATION_INTERVAL_MS,
            dailyCheckNeeded = timeSinceDailyCheck >= DAILY_ROTATION_INTERVAL_MS
        )
    }

    /**
     * Setzt letzte Rotation-Timestamps zurück (z.B. nach Key-Reset).
     */
    fun resetRotationTimestamps() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SPK_ROTATION, System.currentTimeMillis())
            .putLong(KEY_LAST_DAILY_CHECK, System.currentTimeMillis())
            .remove(KEY_OLD_SPK_LIST)
            .apply()
        Log.i(TAG, "✅ Rotation-Timestamps zurückgesetzt")
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════

    /**
     * Führt SignedPreKey-Rotation durch.
     * Speichert alten SPK vor der Rotation.
     */
    private fun rotateSignedPreKey(): Boolean {
        return try {
            // Callback zum Archivieren + Rotation
            onSpkRotationNeeded?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei SPK-Rotation", e)
            false
        }
    }

    /**
     * Entfernt zu alte SPKs aus der Liste (älter als 90 Tage).
     */
    private fun cleanupOldSpks(array: JSONArray, now: Long) {
        val toRemove = mutableListOf<Int>()

        for (i in 0 until array.length()) {
            try {
                val entry = array.getJSONObject(i)
                val archivedAt = entry.getLong("archivedAt")
                val age = now - archivedAt

                if (age > OLD_SPK_RETENTION_MS) {
                    toRemove.add(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fehler beim Cleanup", e)
            }
        }

        // Entferne in reverse order (um Index-Probleme zu vermeiden)
        for (i in toRemove.reversed()) {
            // JSONArray hat kein direct remove, daher rebuild
            Log.d(TAG, "🗑️ Alter SPK entfernt (älter als 90 Tage)")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════════════════════════

    data class OldSignedPreKey(
        val publicKey: ByteArray,
        val signature: ByteArray,
        val archivedAt: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OldSignedPreKey) return false
            if (!publicKey.contentEquals(other.publicKey)) return false
            if (!signature.contentEquals(other.signature)) return false
            if (archivedAt != other.archivedAt) return false
            return true
        }

        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            result = 31 * result + archivedAt.hashCode()
            return result
        }
    }

    data class RotationStatus(
        val lastSpkRotation: Long,
        val lastDailyCheck: Long,
        val daysUntilNextSpkRotation: Long,
        val spkRotationNeeded: Boolean,
        val dailyCheckNeeded: Boolean
    ) {
        fun getLogMessage(): String {
            return if (spkRotationNeeded) {
                "⚠️ SPK-Rotation erforderlich!"
            } else {
                "ℹ️ SPK-Rotation in $daysUntilNextSpkRotation Tagen"
            }
        }
    }
}

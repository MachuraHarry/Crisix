package com.messenger.crisix.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sichere Persistierung von E2EE-Sessions mit Android Keystore Encryption.
 *
 * Features:
 * - Verschlüsselt mit Hardware-backed AES-256-GCM
 * - Fallback zu plaintext SharedPreferences bei Fehler (mit Warnung)
 * - Automatische Master-Key-Generierung
 * - Thread-safe
 *
 * Verwendung:
 * ```kotlin
 * val storage = EncryptedSessionStorage(context)
 * storage.saveSessionsJson(jsonString)
 * val json = storage.loadSessionsJson()
 * ```
 */
class EncryptedSessionStorage(private val context: Context) {

    companion object {
        private const val TAG = "EncryptedSessionStorage"
        private const val ENCRYPTED_PREFS_NAME = "crisix_e2ee_encrypted"
        private const val SESSIONS_KEY = "e2ee_sessions"
        private const val PLAINTEXT_PREFS_NAME = "crisix_e2ee"
    }

    private var encryptedPrefs: SharedPreferences? = null
    private var fallbackToPlaintext = false

    init {
        initializeEncryptedPrefs()
    }

    // ════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ════════════════════════════════════════════════════════════════

    /**
     * Initialisiert EncryptedSharedPreferences.
     * Fallback zu plaintext bei Fehler.
     */
    private fun initializeEncryptedPrefs() {
        try {
            // Master-Key erstellen (hardware-backed wenn möglich)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // EncryptedSharedPreferences initialisieren
            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            fallbackToPlaintext = false
            Log.i(TAG, "✅ EncryptedSharedPreferences erfolgreich initialisiert (Hardware-backed)")

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ EncryptedSharedPreferences fehlgeschlagen (${e.message}) → Fallback zu plaintext")
            fallbackToPlaintext = true
            // Fallback: Nutze unverschlüsselte SharedPreferences (mit Warnung!)
            encryptedPrefs = context.getSharedPreferences(PLAINTEXT_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════

    /**
     * Speichert Session-JSON verschlüsselt.
     */
    fun saveSessionsJson(json: String): Boolean {
        return try {
            if (encryptedPrefs == null) {
                Log.e(TAG, "❌ encryptedPrefs ist null")
                return false
            }

            encryptedPrefs?.edit()
                ?.putString(SESSIONS_KEY, json)
                ?.apply()

            val encryption = if (fallbackToPlaintext) "plaintext (⚠️)" else "encrypted ✅"
            Log.d(TAG, "Sessions gespeichert ($encryption): ${json.length} bytes")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Speichern der Sessions: ${e.message}")
            false
        }
    }

    /**
     * Lädt Session-JSON (verschlüsselt oder plaintext).
     */
    fun loadSessionsJson(): String? {
        return try {
            if (encryptedPrefs == null) {
                Log.e(TAG, "❌ encryptedPrefs ist null")
                return null
            }

            val json = encryptedPrefs?.getString(SESSIONS_KEY, null)
            if (json != null) {
                val encryption = if (fallbackToPlaintext) "plaintext (⚠️)" else "encrypted ✅"
                Log.d(TAG, "Sessions geladen ($encryption): ${json.length} bytes")
            }
            json

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Laden der Sessions: ${e.message}")
            null
        }
    }

    /**
     * Löscht alle Sessions.
     */
    fun clearSessions(): Boolean {
        return try {
            encryptedPrefs?.edit()?.remove(SESSIONS_KEY)?.apply()
            Log.i(TAG, "Sessions gelöscht")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Löschen der Sessions: ${e.message}")
            false
        }
    }

    /**
     * Migriert Sessions von plaintext zu encrypted storage.
     * Wird beim App-Start aufgerufen (Fallback wenn alte plaintext-Sessions existieren).
     */
    fun migrateFromPlaintextIfNeeded(): Boolean {
        return try {
            // Wenn bereits encrypted, nichts zu tun
            if (!fallbackToPlaintext) {
                Log.d(TAG, "Bereits mit Encryption gespeichert")
                return true
            }

            // Alte plaintext-Sessions laden
            val plaintextPrefs = context.getSharedPreferences(PLAINTEXT_PREFS_NAME, Context.MODE_PRIVATE)
            val oldJson = plaintextPrefs.getString("e2ee_sessions", null)

            if (oldJson != null && oldJson.isNotEmpty()) {
                Log.i(TAG, "🔄 Migriere alte plaintext-Sessions zu encrypted storage...")

                // Neuen Master-Key erstellen und encrypted prefs setup
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                // Alte Daten zu encrypted storage kopieren
                encryptedPrefs?.edit()
                    ?.putString(SESSIONS_KEY, oldJson)
                    ?.apply()

                // Alte plaintext-Daten löschen
                plaintextPrefs.edit().remove("e2ee_sessions").apply()

                fallbackToPlaintext = false
                Log.i(TAG, "✅ Migration abgeschlossen: Sessions sind jetzt encrypted")
                true

            } else {
                Log.d(TAG, "Keine alten plaintext-Sessions zum Migrieren")
                true
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler bei Migration: ${e.message}")
            false
        }
    }

    /**
     * Gibt Encryption-Status zurück.
     */
    fun isEncrypted(): Boolean = !fallbackToPlaintext

    /**
     * Gibt Warnung zurück wenn plaintext-Fallback aktiv.
     */
    fun getEncryptionStatus(): String {
        return if (fallbackToPlaintext) {
            "⚠️ PLAINTEXT (Fallback, Hardware-Keystore nicht verfügbar)"
        } else {
            "✅ ENCRYPTED (AES-256-GCM, Hardware-backed)"
        }
    }

    /**
     * Speichert ein Byte-Array verschlüsselt unter einem Key.
     */
    fun saveBytes(key: String, bytes: ByteArray): Boolean {
        return try {
            if (encryptedPrefs == null) {
                Log.e(TAG, "❌ encryptedPrefs ist null")
                return false
            }
            encryptedPrefs?.edit()
                ?.putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
                ?.apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Speichern von $key: ${e.message}")
            false
        }
    }

    /**
     * Lädt ein Byte-Array verschlüsselt unter einem Key.
     */
    fun loadBytes(key: String): ByteArray? {
        return try {
            if (encryptedPrefs == null) {
                Log.e(TAG, "❌ encryptedPrefs ist null")
                return null
            }
            val b64 = encryptedPrefs?.getString(key, null) ?: return null
            Base64.decode(b64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fehler beim Laden von $key: ${e.message}")
            null
        }
    }

    /**
     * Entfernt einen Eintrag.
     */
    fun removeKey(key: String) {
        encryptedPrefs?.edit()?.remove(key)?.apply()
    }

    /**
     * Speichert einen Integer-Wert (z.B. Zähler).
     */
    fun putInt(key: String, value: Int) {
        encryptedPrefs?.edit()?.putInt(key, value)?.apply()
    }

    /**
     * Lädt einen Integer-Wert.
     */
    fun getInt(key: String, default: Int): Int {
        return encryptedPrefs?.getInt(key, default) ?: default
    }
}

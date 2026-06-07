package com.messenger.crisix.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Zentrale Verwaltung aller Runtime-Permissions für Crisix.
 *
 * Bündelt die Berechtigungsabfragen für:
 * - CAMERA (QR-Scanner)
 * - RECORD_AUDIO (Sprachnachrichten)
 * - BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE (BLE, API 31+)
 * - ACCESS_FINE_LOCATION (BLE, API < 31)
 * - POST_NOTIFICATIONS (Benachrichtigungen, API 33+)
 *
 * Aufrufer können mit [check] prüfen, ob eine Permission bereits erteilt ist,
 * und mit [requiredPermissionsFor] die Liste der benötigten Permissions abrufen,
 * die dann via [ActivityResultContracts.RequestMultiplePermissions] angefragt werden.
 */
object PermissionManager {

    // ─────────────────────────────────────────────────────────────────
    // Permission-Gruppen
    // ─────────────────────────────────────────────────────────────────

    /** BLE-Permissions abhängig vom API-Level */
    fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    /** Einzelne RECORD_AUDIO-Permission */
    fun audioPermission(): String = Manifest.permission.RECORD_AUDIO

    /** Einzelne CAMERA-Permission */
    fun cameraPermission(): String = Manifest.permission.CAMERA

    /** Einzelne POST_NOTIFICATIONS-Permission (Android 13+) */
    fun notificationPermission(): String = Manifest.permission.POST_NOTIFICATIONS

    /** WiFi Aware (NAN) Permissions ab API 31 */
    fun wifiAwarePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // NEARBY_WIFI_DEVICES ist primär, aber Android fällt bei Verweigerung
        // auf ACCESS_FINE_LOCATION zurück — beides anfordern
        arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        emptyArray()
    }

    // ─────────────────────────────────────────────────────────────────
    // Prüfungen
    // ─────────────────────────────────────────────────────────────────

    /**
     * Prüft, ob eine einzelne Permission erteilt ist.
     */
    fun check(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Prüft, ob alle angegebenen Permissions erteilt sind.
     */
    fun checkAll(context: Context, vararg permissions: String): Boolean =
        permissions.all { check(context, it) }

    /**
     * Prüft, ob alle BLE-Permissions erteilt sind.
     */
    fun hasBlePermissions(context: Context): Boolean =
        checkAll(context, *blePermissions())

    /**
     * Prüft, ob RECORD_AUDIO erteilt ist.
     */
    fun hasAudioPermission(context: Context): Boolean =
        check(context, audioPermission())

    /**
     * Prüft, ob CAMERA erteilt ist.
     */
    fun hasCameraPermission(context: Context): Boolean =
        check(context, cameraPermission())

    /**
     * Prüft, ob POST_NOTIFICATIONS erteilt ist (Android 13+).
     * Auf älteren API-Leveln gibt es keine Runtime-Permission, daher wird true zurückgegeben.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            check(context, notificationPermission())
        } else {
            true
        }
    }

    /**
     * Prüft, ob WiFi-Aware-Permissions erteilt sind (API 31+).
     * Auf älteren API-Leveln wird true zurückgegeben.
     */
    fun hasWifiAwarePermissions(context: Context): Boolean {
        val perms = wifiAwarePermissions()
        if (perms.isEmpty()) return true
        return checkAll(context, *perms)
    }

    /**
     * Prüft, ob das Gerät WiFi Aware (NAN) überhaupt unterstützt.
     * Prüft Hardware-Feature und API-Level synchron.
     */
    fun isNanSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    /** SMS-Permissions (SEND_SMS + RECEIVE_SMS) */
    fun smsPermissions(): Array<String> = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    fun hasSmsPermissions(context: Context): Boolean =
        checkAll(context, *smsPermissions())

    // ─────────────────────────────────────────────────────────────────
    // Hilfsfunktionen für API-Level-Checks
    // ─────────────────────────────────────────────────────────────────

    /** Ist das API-Level hoch genug für Runtime-Permissions? */
    fun canRequestRuntimePermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}

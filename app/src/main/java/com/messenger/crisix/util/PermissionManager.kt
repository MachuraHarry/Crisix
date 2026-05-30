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
            // Vor Android 13: Keine Runtime-Permission nötig
            true
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Hilfsfunktionen für API-Level-Checks
    // ─────────────────────────────────────────────────────────────────

    /** Ist das API-Level hoch genug für Runtime-Permissions? */
    fun canRequestRuntimePermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}

package com.messenger.crisix.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

object PermissionHelper {
    /**
     * Prüft ob POST_NOTIFICATIONS Permission gewährt ist.
     * Android 13+ erfordert diese Permission zur Laufzeit.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Vor Android 13 ist die Permission automatisch gewährt
            true
        }
    }
}

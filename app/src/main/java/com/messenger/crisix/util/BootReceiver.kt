package com.messenger.crisix.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.messenger.crisix.service.CrisixForegroundService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Gerät gestartet — starte Crisix Foreground Service")
            CrisixForegroundService.createServiceChannel(context)
            CrisixForegroundService.start(context)
        }
    }
}

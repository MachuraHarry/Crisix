package com.messenger.crisix.service

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CrisixBootWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CrisixBootWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Boot-Worker gestartet — richte Foreground Service ein")

        CrisixForegroundService.createServiceChannel(applicationContext)
        CrisixForegroundService.start(applicationContext)

        Log.i(TAG, "Foreground Service gestartet — Worker beendet")
        return Result.success()
    }
}

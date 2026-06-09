package com.messenger.crisix.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.messenger.crisix.MainActivity
import com.messenger.crisix.R

class CrisixBootWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CrisixBootWorker"
        private const val WORKER_NOTIFICATION_ID = 2
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Boot-Worker gestartet — richte Foreground Service ein")

        setForeground(createWorkerForegroundInfo())

        CrisixForegroundService.createServiceChannel(applicationContext)
        CrisixForegroundService.start(applicationContext)

        Log.i(TAG, "Foreground Service gestartet — Worker beendet")
        return Result.success()
    }

    private fun createWorkerForegroundInfo(): ForegroundInfo {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            CrisixForegroundService.CHANNEL_ID_SERVICE
        )
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Crisix")
            .setContentText("Startet…")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        return ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
    }
}

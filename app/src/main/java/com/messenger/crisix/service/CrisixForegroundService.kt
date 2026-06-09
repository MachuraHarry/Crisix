package com.messenger.crisix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.messenger.crisix.MainActivity
import com.messenger.crisix.R
import com.messenger.crisix.ai.AiModelManager

class CrisixForegroundService : Service() {

    companion object {
        private const val TAG = "CrisixService"
        const val CHANNEL_ID_SERVICE = "crisix_service"
        private const val NOTIFICATION_ID = 1

        private var isRunning = false

        fun isRunning(): Boolean = isRunning

        fun createServiceChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Crisix Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält Crisix im Hintergrund aktiv für Nachrichtenempfang"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, CrisixForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CrisixForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "Foreground Service gestartet")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        AiModelManager.getInstance(this).close()
        Log.i(TAG, "Foreground Service beendet")
        super.onDestroy()
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Crisix")
            .setContentText("Läuft im Hintergrund — bereit für Nachrichten")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

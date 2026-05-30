package com.messenger.crisix.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.messenger.crisix.MainActivity
import com.messenger.crisix.R

object NotificationHelper {

    private const val CHANNEL_ID = "crisix_messages"
    private const val CHANNEL_NAME = "Crisix Nachrichten"
    private const val CHANNEL_DESC = "Eingehende Chat-Nachrichten"

    /**
     * Notification-Channel erstellen (wird einmalig beim App-Start aufgerufen).
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH // Hohe Priorität = Heads-Up-Notification
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            setShowBadge(true)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Notification für eine eingehende Nachricht anzeigen.
     *
     * @param context Android Context
     * @param peerName Anzeigename des Senders
     * @param messageText Der Nachrichtentext (gekürzt)
     * @param chatId Die Chat-ID (Peer-ID) für den DeepLink
     */
    fun showMessageNotification(
        context: Context,
        peerName: String,
        messageText: String,
        chatId: String,
    ) {
        // Prüfen ob POST_NOTIFICATIONS-Permission erteilt ist (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Keine Permission – Notification wird nicht angezeigt
                return
            }
        }

        // Intent zum Öffnen des Chats beim Tippen auf die Notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openChatId", chatId)
            putExtra("openChatName", peerName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Text kürzen für die Notification
        val previewText = if (messageText.length > 120) {
            messageText.take(120) + "…"
        } else {
            messageText
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(peerName)
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        // Eindeutige ID pro Chat, damit Notifications gruppiert werden
        val notificationId = chatId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

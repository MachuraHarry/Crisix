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

    const val CHANNEL_MESSAGES = "crisix_messages"
    const val CHANNEL_SERVICE = "crisix_service"
    const val CHANNEL_SPEECH_MODELS = "crisix_speech_models"

    private const val SUMMARY_GROUP = "crisix_messages_group"
    private const val SUMMARY_NOTIFICATION_ID = 0

    private data class PendingMessage(
        val chatId: String,
        val peerName: String,
        val messageText: String,
        val notificationId: Int,
    )

    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessage>>()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(msgChannel)

        val svcChannel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(svcChannel)

        val speechChannel = NotificationChannel(
            CHANNEL_SPEECH_MODELS,
            "Sprachmodelle",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Download-Fortschritt für Spracherkennungsmodelle"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(speechChannel)
    }

    fun showMessageNotification(
        context: Context,
        peerName: String,
        messageText: String,
        chatId: String,
    ) {
        if (!hasPermission(context)) return

        val notificationId = makeNotificationId(chatId)
        val message = PendingMessage(chatId, peerName, messageText, notificationId)

        synchronized(pendingMessages) {
            pendingMessages.getOrPut(chatId) { mutableListOf() }.add(message)
        }

        val count = synchronized(pendingMessages) {
            pendingMessages[chatId]?.size ?: 0
        }

        val normalizedChatId = chatId.split("@").first()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openChatId", normalizedChatId)
            putExtra("openChatName", peerName)
        }
        val openPending = PendingIntent.getActivity(
            context, normalizedChatId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.messenger.crisix.MARK_READ"
            putExtra("chatId", normalizedChatId)
            putExtra("notificationId", notificationId)
        }
        val markReadPending = PendingIntent.getBroadcast(
            context, normalizedChatId.hashCode() + 1, markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previewText = if (messageText.length > 100) {
            messageText.take(100) + "\u2026"
        } else {
            messageText
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(peerName)
            .setContentText(previewText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(SUMMARY_GROUP)
            .setNumber(count)
            .addAction(
                R.drawable.ic_chat,
                context.getString(R.string.notification_action_mark_read),
                markReadPending
            )

        if (count > 1) {
            val lines = synchronized(pendingMessages) {
                pendingMessages[chatId]?.takeLast(7)?.map { "${it.peerName}: ${it.messageText.take(60)}" }
                    ?: emptyList()
            }
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle("$peerName ($count)")
                .setSummaryText(context.getString(R.string.notification_new_messages_count, count))
            lines.forEach { inboxStyle.addLine(it) }
            builder.setStyle(inboxStyle)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        updateSummaryNotification(context)
    }

    fun cancelChatNotifications(context: Context, chatId: String) {
        val notificationId = makeNotificationId(chatId)
        synchronized(pendingMessages) {
            pendingMessages.remove(chatId)
        }
        NotificationManagerCompat.from(context).cancel(notificationId)
        updateSummaryNotification(context)
    }

    fun clearAllNotifications(context: Context) {
        synchronized(pendingMessages) {
            pendingMessages.clear()
        }
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun getUnreadMessagesForChat(chatId: String): Int {
        return synchronized(pendingMessages) {
            pendingMessages[chatId]?.size ?: 0
        }
    }

    private fun updateSummaryNotification(context: Context) {
        if (!hasPermission(context)) return

        val totalCount = synchronized(pendingMessages) {
            pendingMessages.values.sumOf { it.size }
        }

        if (totalCount == 0) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
            return
        }

        val allMessages = synchronized(pendingMessages) {
            pendingMessages.flatMap { (chatId, msgs) ->
                msgs.takeLast(1).map { Triple(chatId, it.peerName, it.messageText.take(60)) }
            }
        }

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_messages_count, totalCount))
            .setSummaryText(context.getString(R.string.notification_summary_title))
        for ((_, name, text) in allMessages.take(7)) {
            inboxStyle.addLine("$name: $text")
        }
        if (allMessages.size > 7) {
            inboxStyle.addLine(context.getString(R.string.notification_more_count, allMessages.size - 7))
        }

        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Crisix")
            .setContentText(context.getString(R.string.notification_new_messages_count, totalCount))
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(SUMMARY_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun makeNotificationId(chatId: String): Int {
        val normalized = chatId.split("@").first()
        return normalized.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    }
}

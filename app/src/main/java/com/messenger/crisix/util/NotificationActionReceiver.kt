package com.messenger.crisix.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.messenger.crisix.MARK_READ") {
            val chatId = intent.getStringExtra("chatId") ?: return
            val notificationId = intent.getIntExtra("notificationId", 0)

            Log.d(TAG, "Markiere Chat als gelesen: $chatId")

            NotificationHelper.cancelChatNotifications(context, chatId)

            // Auch die DB-Updates asynchron
            kotlinx.coroutines.runBlocking {
                try {
                    val db = com.messenger.crisix.data.AppDatabase.getInstance(context)
                    db.messageDao().markChatMessagesAsRead(chatId)
                    db.chatDao().resetUnreadCount(chatId)
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Mark-Read: ${e.message}")
                }
            }
        }
    }
}

package com.messenger.crisix.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.messenger.crisix.MainActivity
import com.messenger.crisix.R
import com.messenger.crisix.util.NotificationHelper

class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val reminderTitle = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "Erinnerung"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, reminderId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Crisix AI - Erinnerung")
            .setContentText(reminderTitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(reminderId.hashCode(), notification)
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TITLE = "reminder_title"
    }
}

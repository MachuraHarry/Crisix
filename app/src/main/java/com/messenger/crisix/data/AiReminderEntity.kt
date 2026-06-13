package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_reminders")
data class AiReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dueTime: Long,
    val isCompleted: Boolean = false,
    val createdAt: Long,
)

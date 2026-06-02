package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String,
    val timestampMillis: Long,
    val transportType: String?,
    val unreadCount: Int = 0,
    val disappearingTimerMs: Long = 0L,
)

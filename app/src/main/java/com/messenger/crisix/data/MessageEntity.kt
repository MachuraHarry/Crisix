package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: String,
    val timestampMillis: Long,
    val status: String,
    val transport: String?,
)

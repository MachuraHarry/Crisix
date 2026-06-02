package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestampMillis"]),
        Index(value = ["chatId", "uiMessageId"], unique = true),
        Index(value = ["timestampMillis"]),
    ]
)
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
    val imageUri: String? = null,
    val audioUri: String? = null,
    val audioDurationMs: Long = 0L,
    val isEncrypted: Boolean = false,
    val isRead: Boolean = false,
    val uiMessageId: String? = null,
    val isSystemMessage: Boolean = false,
    val hintStatus: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val disappearingTimerMs: Long = 0L,
)

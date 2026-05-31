package com.messenger.crisix.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey val uiMessageId: String,
    val peerId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_notes")
data class AiNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val createdAt: Long,
)

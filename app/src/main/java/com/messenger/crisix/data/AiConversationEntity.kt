package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.messenger.crisix.ai.AiConversation

@Entity(tableName = "ai_conversations")
data class AiConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: Long,
) {
    fun toDomain(): AiConversation = AiConversation(
        id = id, title = title, lastMessage = lastMessage, timestamp = timestamp,
    )

    companion object {
        fun fromDomain(conv: AiConversation): AiConversationEntity = AiConversationEntity(
            id = conv.id, title = conv.title, lastMessage = conv.lastMessage, timestamp = conv.timestamp,
        )
    }
}

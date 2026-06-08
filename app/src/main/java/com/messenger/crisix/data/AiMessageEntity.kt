package com.messenger.crisix.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiRole

@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = AiConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val timestamp: Long,
) {
    fun toDomain(): AiMessage = AiMessage(
        id = id,
        role = if (role == "USER") AiRole.USER else AiRole.ASSISTANT,
        text = text,
        timestamp = timestamp,
    )

    companion object {
        fun fromDomain(convId: String, msg: AiMessage): AiMessageEntity = AiMessageEntity(
            id = msg.id,
            conversationId = convId,
            role = if (msg.role == AiRole.USER) "USER" else "ASSISTANT",
            text = msg.text,
            timestamp = msg.timestamp,
        )
    }
}

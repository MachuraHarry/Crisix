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
        role = when (role) {
            "USER" -> AiRole.USER
            "TOOL_RESULT" -> AiRole.TOOL_RESULT
            else -> AiRole.ASSISTANT
        },
        text = text,
        timestamp = timestamp,
    )

    companion object {
        fun fromDomain(convId: String, msg: AiMessage): AiMessageEntity = AiMessageEntity(
            id = msg.id,
            conversationId = convId,
            role = when (msg.role) {
                AiRole.USER -> "USER"
                AiRole.TOOL_RESULT -> "TOOL_RESULT"
                else -> "ASSISTANT"
            },
            text = msg.text,
            timestamp = msg.timestamp,
        )
    }
}

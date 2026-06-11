package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {

    @Query("SELECT * FROM ai_conversations ORDER BY timestamp DESC")
    fun getAllConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations ORDER BY timestamp DESC")
    suspend fun getAllConversationsOnce(): List<AiConversationEntity>

    @Query("SELECT * FROM ai_conversations WHERE id = :convId")
    suspend fun getConversationById(convId: String): AiConversationEntity?

    @Query("SELECT * FROM ai_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessages(convId: String): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(convId: String): List<AiMessageEntity>

    @Query("UPDATE ai_conversations SET isAgentMode = 1 - isAgentMode WHERE id = :convId")
    suspend fun toggleAgentMode(convId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: AiConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<AiMessageEntity>)

    @Query("UPDATE ai_conversations SET title = :title, lastMessage = :lastMessage, timestamp = :timestamp WHERE id = :convId")
    suspend fun updateConversation(convId: String, title: String, lastMessage: String, timestamp: Long)

    @Query("DELETE FROM ai_conversations WHERE id = :convId")
    suspend fun deleteConversation(convId: String)

    @Query("DELETE FROM ai_messages WHERE conversationId = :convId")
    suspend fun deleteMessages(convId: String)

    @Query("DELETE FROM ai_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Transaction
    suspend fun insertConversationWithMessages(
        conversation: AiConversationEntity,
        messages: List<AiMessageEntity>,
    ) {
        insertConversation(conversation)
        insertMessages(messages)
    }

    @Transaction
    suspend fun rebuildConversationPreview(convId: String) {
        val messages = getMessagesOnce(convId)
        if (messages.isEmpty()) return
        val last = messages.last()
        val preview = last.text.take(80).replace("\n", " ")
        val title = getConversationById(convId)?.title
        val newTitle = if (title == null || title == "Neuer Chat") {
            messages.firstOrNull { it.role == "USER" }?.text?.take(30) ?: "Neuer Chat"
        } else title
        updateConversation(convId, newTitle, preview, last.timestamp)
    }

    @Transaction
    suspend fun upsertConversationWithMessage(
        conversation: AiConversationEntity,
        message: AiMessageEntity,
    ) {
        insertConversation(conversation)
        insertMessage(message)
    }
}

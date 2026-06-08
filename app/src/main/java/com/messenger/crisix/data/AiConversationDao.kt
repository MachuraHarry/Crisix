package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {

    @Query("SELECT * FROM ai_conversations ORDER BY timestamp DESC")
    fun getAllConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations ORDER BY timestamp DESC")
    suspend fun getAllConversationsOnce(): List<AiConversationEntity>

    @Query("SELECT * FROM ai_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessages(convId: String): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(convId: String): List<AiMessageEntity>

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
}

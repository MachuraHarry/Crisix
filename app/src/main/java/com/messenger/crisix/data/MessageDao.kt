package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestampMillis ASC")
    fun getMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestampMillis ASC")
    suspend fun getMessagesOnce(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestampMillis ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status, transport = :transport WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String, transport: String?)

    @Query("UPDATE messages SET imageUri = :imageUri WHERE id = :messageId")
    suspend fun updateImageUri(messageId: String, imageUri: String?)

    @Query("UPDATE messages SET audioUri = :audioUri, audioDurationMs = :durationMs WHERE id = :messageId")
    suspend fun updateAudioUri(messageId: String, audioUri: String?, durationMs: Long)

    @Query("UPDATE messages SET status = :status WHERE chatId = :chatId AND isFromMe = 1 AND status = :oldStatus")
    suspend fun updateAllSentToDelivered(chatId: String, oldStatus: String, status: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

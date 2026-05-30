package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY timestampMillis DESC")
    fun getAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chat: ChatEntity)

    @Query("UPDATE chats SET lastMessage = :lastMessage, timestamp = :timestamp, timestampMillis = :timestampMillis, transportType = :transportType WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, lastMessage: String, timestamp: String, timestampMillis: Long, transportType: String?)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)
}

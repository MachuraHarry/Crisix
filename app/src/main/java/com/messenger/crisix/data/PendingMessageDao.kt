package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages ORDER BY retryCount ASC, createdAt ASC")
    suspend fun loadAll(): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE uiMessageId = :uiMessageId")
    suspend fun delete(uiMessageId: String)

    @Query("DELETE FROM pending_messages")
    suspend fun deleteAll()
}

package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiReminderDao {

    @Query("SELECT * FROM ai_reminders ORDER BY dueTime ASC")
    fun getAllReminders(): Flow<List<AiReminderEntity>>

    @Query("SELECT * FROM ai_reminders ORDER BY dueTime ASC")
    suspend fun getAllRemindersOnce(): List<AiReminderEntity>

    @Query("SELECT * FROM ai_reminders WHERE id = :reminderId")
    suspend fun getReminderById(reminderId: String): AiReminderEntity?

    @Query("SELECT * FROM ai_reminders WHERE isCompleted = 0 AND dueTime <= :maxTime ORDER BY dueTime ASC")
    suspend fun getDueReminders(maxTime: Long): List<AiReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: AiReminderEntity)

    @Query("UPDATE ai_reminders SET isCompleted = 1 WHERE id = :reminderId")
    suspend fun markCompleted(reminderId: String)

    @Query("DELETE FROM ai_reminders WHERE id = :reminderId")
    suspend fun deleteReminder(reminderId: String)
}

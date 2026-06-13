package com.messenger.crisix.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiNoteDao {

    @Query("SELECT * FROM ai_notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<AiNoteEntity>>

    @Query("SELECT * FROM ai_notes ORDER BY updatedAt DESC")
    suspend fun getAllNotesOnce(): List<AiNoteEntity>

    @Query("SELECT * FROM ai_notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: String): AiNoteEntity?

    @Query("SELECT * FROM ai_notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchNotes(query: String): List<AiNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: AiNoteEntity)

    @Query("UPDATE ai_notes SET title = :title, content = :content, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun updateNote(noteId: String, title: String, content: String, updatedAt: Long)

    @Query("DELETE FROM ai_notes WHERE id = :noteId")
    suspend fun deleteNote(noteId: String)
}

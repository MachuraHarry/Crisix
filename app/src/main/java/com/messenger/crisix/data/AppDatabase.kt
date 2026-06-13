package com.messenger.crisix.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        ChatEntity::class,
        PendingMessageEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        AiNoteEntity::class,
        AiReminderEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun aiConversationDao(): AiConversationDao
    abstract fun aiNoteDao(): AiNoteDao
    abstract fun aiReminderDao(): AiReminderDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN disappearingTimerMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN disappearingTimerMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS idx_messages_chat_timestamp")
                db.execSQL("DROP INDEX IF EXISTS idx_messages_timestamp")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_timestampMillis ON messages (chatId, timestampMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestampMillis ON messages (timestampMillis)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN expiresAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET expiresAtMillis = timestampMillis + disappearingTimerMs WHERE disappearingTimerMs > 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_expiresAtMillis ON messages (chatId, expiresAtMillis)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, lastMessage TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY (conversationId) REFERENCES ai_conversations(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_messages_conversationId ON ai_messages (conversationId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_messages ADD COLUMN thinking TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_conversations ADD COLUMN isAgentMode INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_notes (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL, updatedAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ai_reminders (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, dueTime INTEGER NOT NULL, isCompleted INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        )

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crisix.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

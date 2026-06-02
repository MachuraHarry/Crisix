package com.messenger.crisix.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, ChatEntity::class, PendingMessageEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun pendingMessageDao(): PendingMessageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN disappearingTimerMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN disappearingTimerMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9,
        )

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crisix.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

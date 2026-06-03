package com.messenger.crisix.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao
    private lateinit var chatDao: ChatDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        dao = db.messageDao()
        chatDao = db.chatDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun addMessage_withoutTimer_hasDefaultExpiry() = runTest {
        val chatId = "chat-1"
        val msg = createMessage(chatId, "msg-1", "Hello", 0)
        dao.insertAll(listOf(msg))
        val messages = dao.getMessages(chatId).first()
        val loaded = messages.single()
        assertEquals(0, loaded.expiresAtMillis)
    }

    @Test
    fun addMessage_withTimer_setsExpiryInFuture() = runTest {
        val now = System.currentTimeMillis()
        val timerMs = 30_000L
        val msg = createMessage("chat-timer", "msg-timer", "Secret", timerMs)
        dao.insertAll(listOf(msg))
        val loaded = dao.getMessages("chat-timer").first().single()
        assertTrue(loaded.expiresAtMillis > now)
    }

    @Test
    fun deleteExpiredMessages_removesExpired() = runTest {
        val chatId = "chat-expired"
        val now = System.currentTimeMillis()
        val expiredMsg = createMessage(
            chatId, "expired", "Gone", 60_000,
            expiresAtMillis = now - 60_000,
        )
        val validMsg = createMessage(chatId, "valid", "Still here", 0)
        dao.insertAll(listOf(expiredMsg, validMsg))

        val deleted = dao.deleteExpiredMessages(now)
        assertEquals(1, deleted)

        val remaining = dao.getMessages(chatId).first()
        assertEquals(1, remaining.size)
        assertEquals("valid", remaining[0].id)
    }

    @Test
    fun deleteExpiredMessages_ignoresZeroExpiry() = runTest {
        val chatId = "chat-permanent"
        val now = System.currentTimeMillis()
        val msg = createMessage(
            chatId, "perm", "Permanent", 0,
            timestampMillis = now - 86_400_000,
        )
        dao.insertAll(listOf(msg))
        val deleted = dao.deleteExpiredMessages(now)
        assertEquals(0, deleted)
        assertEquals(1, dao.getMessages(chatId).first().size)
    }

    private fun createMessage(
        chatId: String,
        id: String,
        text: String,
        disappearingTimerMs: Long,
        timestampMillis: Long = System.currentTimeMillis(),
        expiresAtMillis: Long = System.currentTimeMillis() + disappearingTimerMs,
    ) = MessageEntity(
        id = id,
        chatId = chatId,
        text = text,
        isFromMe = true,
        timestamp = "12:00",
        timestampMillis = timestampMillis,
        status = "SENT",
        transport = null,
        disappearingTimerMs = disappearingTimerMs,
        expiresAtMillis = expiresAtMillis,
    )
}

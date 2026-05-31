package com.messenger.crisix.data

import android.content.Context
import com.messenger.crisix.transport.MessageStatus
import kotlinx.coroutines.flow.Flow

class MessageRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()
    private val chatDao = db.chatDao()
    private val pendingMessageDao = db.pendingMessageDao()

    val allChats: Flow<List<ChatEntity>> = chatDao.getAll()

    fun getMessages(chatId: String): Flow<List<MessageEntity>> = messageDao.getMessages(chatId)

    suspend fun addMessage(
        id: String,
        chatId: String,
        text: String,
        isFromMe: Boolean,
        timestamp: String,
        timestampMillis: Long,
        status: MessageStatus,
        transport: String?,
        imageUri: String? = null,
        audioUri: String? = null,
        audioDurationMs: Long = 0L,
        isEncrypted: Boolean = false,
        uiMessageId: String? = null,
    ) {
        val entity = MessageEntity(
            id = id,
            chatId = chatId,
            text = text,
            isFromMe = isFromMe,
            timestamp = timestamp,
            timestampMillis = timestampMillis,
            status = status.name,
            transport = transport,
            imageUri = imageUri,
            audioUri = audioUri,
            audioDurationMs = audioDurationMs,
            isEncrypted = isEncrypted,
            uiMessageId = uiMessageId,
        )
        messageDao.insert(entity)
    }

    suspend fun isDuplicateMessage(chatId: String, uiMessageId: String): Boolean {
        return messageDao.findExistingByUiMessageId(chatId, uiMessageId) != null
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus, transport: String?) {
        messageDao.updateStatus(messageId, status.name, transport)
    }

    suspend fun updateImageUri(messageId: String, imageUri: String?) {
        messageDao.updateImageUri(messageId, imageUri)
    }

    suspend fun updateAudioUri(messageId: String, audioUri: String?, durationMs: Long) {
        messageDao.updateAudioUri(messageId, audioUri, durationMs)
    }

    suspend fun markAllSentAsDelivered(chatId: String) {
        messageDao.updateAllSentToDelivered(chatId, MessageStatus.SENT.name, MessageStatus.DELIVERED.name)
    }

    suspend fun loadMessagesOnce(chatId: String): List<MessageEntity> {
        return messageDao.getMessagesOnce(chatId)
    }

    suspend fun loadAllMessages(): List<MessageEntity> {
        return messageDao.getAllMessages()
    }

    suspend fun upsertChat(chat: ChatEntity) {
        chatDao.insert(chat)
    }

    suspend fun updateChatLastMessage(chatId: String, lastMessage: String, timestamp: String, timestampMillis: Long, transportType: String?) {
        chatDao.updateLastMessage(chatId, lastMessage, timestamp, timestampMillis, transportType)
    }

    suspend fun incrementUnreadCount(chatId: String) {
        chatDao.incrementUnreadCount(chatId)
    }

    suspend fun resetUnreadCount(chatId: String) {
        chatDao.resetUnreadCount(chatId)
    }

    suspend fun getUnreadCount(chatId: String): Int {
        return chatDao.getUnreadCount(chatId)
    }

    suspend fun markChatAsRead(chatId: String) {
        messageDao.markChatMessagesAsRead(chatId)
        chatDao.resetUnreadCount(chatId)
    }

    suspend fun markMessageAsRead(messageId: String) {
        messageDao.markMessageAsRead(messageId)
    }

    suspend fun getMessageUnreadCount(chatId: String): Int {
        return messageDao.getUnreadCount(chatId)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Retry-Queue-Persistierung
    // ═════════════════════════════════════════════════════════════════════

    suspend fun loadPendingMessages(): List<PendingMessageEntity> {
        return pendingMessageDao.loadAll()
    }

    suspend fun savePendingMessage(entity: PendingMessageEntity) {
        pendingMessageDao.insert(entity)
    }

    suspend fun deletePendingMessage(uiMessageId: String) {
        pendingMessageDao.delete(uiMessageId)
    }

    suspend fun clearPendingMessages() {
        pendingMessageDao.deleteAll()
    }
}

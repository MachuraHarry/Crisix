package com.messenger.crisix.data

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.messenger.crisix.transport.MessageStatus
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()
    private val chatDao = db.chatDao()
    private val pendingMessageDao = db.pendingMessageDao()

    val allChats: Flow<List<ChatEntity>> = chatDao.getAll()

    fun getMessages(chatId: String): Flow<List<MessageEntity>> = messageDao.getMessages(chatId)

    fun getPagedMessages(chatId: String): Flow<PagingData<MessageEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 30,
                enablePlaceholders = false,
                initialLoadSize = 60,
            ),
            pagingSourceFactory = { messageDao.getMessagesPagingSource(chatId) }
        ).flow
    }

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
        isSystemMessage: Boolean = false,
        hintStatus: String? = null,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null,
        disappearingTimerMs: Long = 0L,
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
            isSystemMessage = isSystemMessage,
            hintStatus = hintStatus,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            disappearingTimerMs = disappearingTimerMs,
        )
        messageDao.insert(entity)
    }

    suspend fun isDuplicateMessage(chatId: String, uiMessageId: String): Boolean {
        return messageDao.findExistingByUiMessageId(chatId, uiMessageId) != null
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus, transport: String?) {
        messageDao.updateStatus(messageId, status.name, transport)
    }

    suspend fun updateHintMessage(messageId: String, text: String, hintStatus: String?) {
        messageDao.updateHintMessage(messageId, text, hintStatus)
    }

    suspend fun updateEncrypted(messageId: String) {
        messageDao.updateEncrypted(messageId)
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

    suspend fun deleteChat(chatId: String) {
        chatDao.delete(chatId)
        messageDao.deleteChat(chatId)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
    }

    suspend fun loadPendingMessages(): List<PendingMessageEntity> {
        return pendingMessageDao.loadAll()
    }

    suspend fun savePendingMessage(entity: PendingMessageEntity) {
        pendingMessageDao.insert(entity)
    }

    suspend fun deletePendingMessage(uiMessageId: String) {
        pendingMessageDao.delete(uiMessageId)
    }

    suspend fun getMediaMessages(chatId: String): List<MessageEntity> {
        return messageDao.getMediaMessages(chatId)
    }

    suspend fun cleanExpiredMessages(chatId: String): Int {
        val deleted = messageDao.deleteExpiredMessagesForChat(chatId, System.currentTimeMillis())
        if (deleted > 0) {
            clearChatMessages(chatId)
        }
        return deleted
    }

    private suspend fun clearChatMessages(chatId: String) {
        messageDao.deleteChat(chatId)
        val now = System.currentTimeMillis()
        val timeStamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
        val hintMsgId = "sys-timer-clear-$chatId-$now"
        val entity = MessageEntity(
            id = hintMsgId,
            chatId = chatId,
            text = context.getString(com.messenger.crisix.R.string.timer_chat_cleared),
            isFromMe = false,
            timestamp = timeStamp,
            timestampMillis = now,
            status = com.messenger.crisix.transport.MessageStatus.DELIVERED.name,
            transport = null,
            isSystemMessage = true,
            hintStatus = "SUCCESS",
        )
        messageDao.insert(entity)
    }

    suspend fun cleanAllExpiredMessages(): Int {
        val deleted = messageDao.deleteExpiredMessages(System.currentTimeMillis())
        if (deleted > 0) {
            val chatsWithTimer = chatDao.getChatsWithTimer()
            for (chat in chatsWithTimer) {
                val stillHasExpired = messageDao.deleteExpiredMessagesForChat(chat.id, System.currentTimeMillis())
                if (stillHasExpired > 0) {
                    clearChatMessages(chat.id)
                }
            }
        }
        return deleted
    }

    suspend fun updateChatDisappearingTimer(chatId: String, disappearingTimerMs: Long) {
        chatDao.updateDisappearingTimer(chatId, disappearingTimerMs)
    }

    suspend fun getChatDisappearingTimer(chatId: String): Long {
        return chatDao.getById(chatId)?.disappearingTimerMs ?: 0L
    }

    suspend fun clearPendingMessages() {
        pendingMessageDao.deleteAll()
    }
}

fun MessageEntity.toMessage(): com.messenger.crisix.ui.components.Message {
    return com.messenger.crisix.ui.components.Message(
        id = id,
        text = text,
        isFromMe = isFromMe,
        timestamp = timestamp,
        timestampMillis = timestampMillis,
        status = try { com.messenger.crisix.transport.MessageStatus.valueOf(status) } catch (_: Exception) { com.messenger.crisix.transport.MessageStatus.SENT },
        transport = transport?.let { try { com.messenger.crisix.transport.TransportType.valueOf(it) } catch (_: Exception) { null } },
        imageUri = imageUri,
        audioUri = audioUri,
        audioDurationMs = audioDurationMs,
        isEncrypted = isEncrypted,
        isRead = isRead,
        isSystemMessage = isSystemMessage,
        hintStatus = hintStatus?.let { try { com.messenger.crisix.ui.components.HintStatus.valueOf(it) } catch (_: Exception) { null } },
        replyToId = replyToId,
        replyToText = replyToText,
        replyToSender = replyToSender,
        disappearingTimerMs = disappearingTimerMs,
    )
}

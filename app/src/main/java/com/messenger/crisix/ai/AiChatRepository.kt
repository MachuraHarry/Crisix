package com.messenger.crisix.ai

import android.content.Context
import com.messenger.crisix.data.AiConversationDao
import com.messenger.crisix.data.AiConversationEntity
import com.messenger.crisix.data.AiMessageEntity
import com.messenger.crisix.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class AiChatRepository(
    private val modelManager: AiModelManager,
    context: Context,
) {
    private val dao: AiConversationDao = AppDatabase.getInstance(context).aiConversationDao()

    suspend fun generateResponse(
        messages: List<AiMessage>,
        newMessage: String,
    ): Flow<String> = callbackFlow {
        val prompt = buildChatPrompt(messages, newMessage)
        modelManager.predict(
            prompt = prompt,
            onToken = { trySend(it) },
            onDone = { close() },
            onError = { close(Exception(it)) },
        )
        awaitClose { modelManager.stopPrediction() }
    }.flowOn(Dispatchers.IO)

    suspend fun loadConversations(): List<AiConversation> = withContext(Dispatchers.IO) {
        dao.getAllConversationsOnce().map { it.toDomain() }
    }

    suspend fun loadMessages(conversationId: String): List<AiMessage> = withContext(Dispatchers.IO) {
        dao.getMessagesOnce(conversationId).map { it.toDomain() }
    }

    suspend fun saveConversation(conversation: AiConversation) = withContext(Dispatchers.IO) {
        dao.insertConversation(AiConversationEntity.fromDomain(conversation))
    }

    suspend fun saveMessage(conversationId: String, message: AiMessage) = withContext(Dispatchers.IO) {
        dao.insertMessage(AiMessageEntity.fromDomain(conversationId, message))
    }

    suspend fun saveMessages(conversationId: String, messages: List<AiMessage>) = withContext(Dispatchers.IO) {
        dao.insertMessages(messages.map { AiMessageEntity.fromDomain(conversationId, it) })
    }

    suspend fun updateConversation(convId: String, title: String, lastMessage: String, timestamp: Long) = withContext(Dispatchers.IO) {
        dao.updateConversation(convId, title, lastMessage, timestamp)
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        dao.deleteConversation(conversationId)
    }

    suspend fun deleteMessages(conversationId: String) = withContext(Dispatchers.IO) {
        dao.deleteMessages(conversationId)
    }

    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        dao.deleteMessage(messageId)
    }

    suspend fun deleteAllConversations() = withContext(Dispatchers.IO) {
        val all = dao.getAllConversationsOnce()
        for (conv in all) {
            dao.deleteMessages(conv.id)
            dao.deleteConversation(conv.id)
        }
    }

    private suspend fun buildChatPrompt(
        messages: List<AiMessage>,
        newMessage: String,
    ): String {
        val systemPrompt = modelManager.getSavedSystemPrompt()
        val sb = StringBuilder()
        var isFirstUser = true

        for (msg in messages) {
            when (msg.role) {
                AiRole.USER -> {
                    sb.append("<start_of_turn>user\n")
                    if (isFirstUser) {
                        sb.appendLine(systemPrompt)
                        sb.appendLine()
                        isFirstUser = false
                    }
                    sb.appendLine(msg.text)
                    sb.appendLine("<end_of_turn>")
                }
                AiRole.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n")
                    sb.appendLine(msg.text)
                    sb.appendLine("<end_of_turn>")
                }
                AiRole.TOOL_RESULT -> {
                    sb.append("<start_of_turn>user\n")
                    sb.appendLine("[Tool-Ergebnis: ${msg.text}]")
                    sb.appendLine("<end_of_turn>")
                }
            }
        }

        sb.append("<start_of_turn>user\n")
        if (isFirstUser) {
            sb.appendLine(systemPrompt)
            sb.appendLine()
        }
        sb.appendLine(newMessage)
        sb.append("<end_of_turn>\n<start_of_turn>model\n")

        return sb.toString()
    }
}

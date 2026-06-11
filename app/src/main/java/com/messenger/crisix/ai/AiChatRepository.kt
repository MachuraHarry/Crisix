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
    private val controller: AiInferenceController,
    private val modelManager: AiModelManager,
    private val context: Context,
) {
    private val dao: AiConversationDao = AppDatabase.getInstance(context).aiConversationDao()

    suspend fun generateChatResponse(
        messages: List<AiMessage>,
        newMessage: String,
    ): Flow<String> = callbackFlow {
        val enableThinking = modelManager.getSavedThinkingEnabled()
        val prompt = buildChatPrompt(messages, newMessage, enableThinking)
        controller.predict(
            prompt = prompt,
            onToken = { trySend(it) },
            onDone = { close() },
            onError = { close(Exception(it)) },
            enableThinking = enableThinking,
        )
        awaitClose { controller.cancel() }
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

    suspend fun upsertConversationWithMessage(
        conversation: AiConversation,
        message: AiMessage,
        preview: String,
    ) = withContext(Dispatchers.IO) {
        val convEntity = AiConversationEntity.fromDomain(conversation)
        val msgEntity = AiMessageEntity.fromDomain(conversation.id, message)
        dao.upsertConversationWithMessage(convEntity, msgEntity)
    }

    suspend fun rebuildConversationPreview(conversationId: String) = withContext(Dispatchers.IO) {
        dao.rebuildConversationPreview(conversationId)
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

    suspend fun toggleAgentMode(conversationId: String) = withContext(Dispatchers.IO) {
        dao.toggleAgentMode(conversationId)
    }

    private suspend fun buildChatPrompt(
        messages: List<AiMessage>,
        newMessage: String,
        enableThinking: Boolean,
    ): String {
        val baseSystemPrompt = modelManager.getSavedSystemPrompt()
        val fullSystemPrompt = AiPrompts.buildFullSystemPrompt(baseSystemPrompt, includeTools = false)

        val maxContextSize = modelManager.getSavedContextSize()
        val truncated = AiPromptTruncator.truncateMessages(
            messages = messages,
            systemPrompt = fullSystemPrompt,
            newMessage = newMessage,
            maxContextSize = maxContextSize,
            tokenCounter = modelManager::countTokens,
        )

        return AiPrompts.buildConversationPrompt(
            systemPrompt = fullSystemPrompt,
            messages = truncated,
            newMessage = newMessage,
            enableThinking = enableThinking,
        )
    }
}

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

    suspend fun generateResponse(
        messages: List<AiMessage>,
        newMessage: String,
    ): Flow<String> = callbackFlow {
        // Only use session for continuing conversations, not fresh ones
        if (messages.isEmpty()) {
            modelManager.clearSessionState()
        } else {
            modelManager.loadSessionState()
        }
        val prompt = buildChatPrompt(messages, newMessage)
        controller.predict(
            prompt = prompt,
            onToken = { trySend(it) },
            onDone = { close() },
            onError = { close(Exception(it)) },
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

    suspend fun updateConversation(convId: String, title: String, lastMessage: String, timestamp: Long) = withContext(Dispatchers.IO) {
        dao.updateConversation(convId, title, lastMessage, timestamp)
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

    private suspend fun buildChatPrompt(
        messages: List<AiMessage>,
        newMessage: String,
    ): String {
        // If KV-cache session is active, only send continuation (no history re-send)
        if (modelManager.isSessionActive) {
            val sb = StringBuilder()
            sb.append("<|turn>system\n")
            sb.appendLine("<|think|>")
            sb.appendLine("Du bist Crisix AI, der KI-Assistent der Crisix Messenger-App. Antworte immer auf Deutsch, es sei denn der Nutzer spricht dich auf einer anderen Sprache an. Nutze Markdown-Formatierung und Emojis.")
            sb.appendLine("<turn|>")
            sb.append("<|turn>user\n")
            sb.appendLine(newMessage)
            sb.append("<turn|>\n<|turn>model\n")
            return sb.toString()
        }

        val systemPrompt = modelManager.getSavedSystemPrompt()
        val maxContextSize = modelManager.getSavedContextSize()
        val fullSystemPrompt = """
$systemPrompt

MARKDOWN-FORMATIERUNG:
- Jedes Block-Element MUSS am Anfang einer neuen Zeile stehen: Überschriften (#), Listen (* - + 1.), Zitate (>), Code-Blöcke (```)
- Vor Überschriften und Code-Blöcken eine Leerzeile einfügen
- Code-Blöcke: ```sprache in eigener Zeile, dann Code, dann ``` in eigener Zeile
- Fett: **text**, Kursiv: *text*
""".trimIndent()
        val truncated = AiPromptTruncator.truncateMessages(
            messages = messages,
            systemPrompt = fullSystemPrompt,
            newMessage = newMessage,
            maxContextSize = maxContextSize,
            tokenCounter = modelManager::countTokens,
        )

        val sb = StringBuilder()

        // Gemma 4: system prompt in native system role with think token
        sb.append("<|turn>system\n")
        sb.appendLine("<|think|>")
        sb.appendLine(fullSystemPrompt)
        sb.appendLine("<turn|>")

        for (msg in truncated) {
            when (msg.role) {
                AiRole.USER -> {
                    sb.append("<|turn>user\n")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
                AiRole.ASSISTANT -> {
                    sb.append("<|turn>model\n")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
                AiRole.TOOL_RESULT -> {
                    sb.append("<|turn>user\n")
                    sb.appendLine("Tool-Ergebnis:")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
            }
        }

        sb.append("<|turn>user\n")
        sb.appendLine(newMessage)
        sb.append("<turn|>\n<|turn>model\n")

        return sb.toString()
    }
}

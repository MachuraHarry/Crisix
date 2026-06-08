package com.messenger.crisix.ai

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class AiChatRepository(private val modelManager: AiModelManager) {

    companion object {
        private const val SYSTEM_PROMPT = """
Du bist Crisix AI, ein hilfreicher KI-Assistent, der in der Crisix Messenger-App läuft.
Du bist freundlich, präzise und antwortest auf Deutsch.
Du läufst vollständig auf dem Gerät (on-device, offline).
"""
    }

    suspend fun generateResponse(
        messages: List<AiMessage>,
        newMessage: String,
    ): Flow<String> = callbackFlow {
        val engine = modelManager.getEngine()
            ?: throw IllegalStateException("LiteRT-LM Engine not initialized")

        val initialMessages = buildInitialMessages(messages)
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        )

        val conversation = engine.createConversation(config)
        try {
            conversation.sendMessageAsync(newMessage)
                .collect { partial -> trySend(partial.toString()) }
        } finally {
            conversation.close()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateFullResponse(
        messages: List<AiMessage>,
        newMessage: String,
    ): String = withContext(Dispatchers.IO) {
        val engine = modelManager.getEngine()
            ?: throw IllegalStateException("LiteRT-LM Engine not initialized")

        val initialMessages = buildInitialMessages(messages)
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        )

        engine.createConversation(config).use { conversation ->
            conversation.sendMessage(newMessage).toString()
        }
    }

    private fun buildInitialMessages(messages: List<AiMessage>): List<Message> {
        return messages.map { msg ->
            when (msg.role) {
                AiRole.USER -> Message.user(msg.text)
                AiRole.ASSISTANT -> Message.model(msg.text)
            }
        }
    }
}

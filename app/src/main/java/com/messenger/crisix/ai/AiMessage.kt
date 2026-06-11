package com.messenger.crisix.ai

import java.util.UUID

enum class AiRole { USER, ASSISTANT, TOOL_RESULT }

data class AiMessage(
    val id: String,
    val role: AiRole,
    val text: String,
    val thinking: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AiConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val lastMessage: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isAgentMode: Boolean = true,
)

data class AiStreamChunk(
    val text: String = "",
    val thinking: String = "",
)

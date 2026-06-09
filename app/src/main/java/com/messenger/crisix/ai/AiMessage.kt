package com.messenger.crisix.ai

import java.util.UUID

enum class AiRole { USER, ASSISTANT, TOOL_RESULT }

data class AiMessage(
    val id: String,
    val role: AiRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AiConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val lastMessage: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

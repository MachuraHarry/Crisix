package com.messenger.crisix.ai

import java.util.UUID

enum class AiRole { USER, ASSISTANT }

data class AiMessage(
    val id: String,
    val role: AiRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
)

data class AiConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Neuer Chat",
    val lastMessage: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

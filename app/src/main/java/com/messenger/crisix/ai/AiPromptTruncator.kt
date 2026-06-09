package com.messenger.crisix.ai

import kotlin.math.roundToInt

object AiPromptTruncator {

    fun estimateTokenCount(text: String): Int {
        return (text.length / 3.5f).roundToInt()
    }

    fun truncateMessages(
        messages: List<AiMessage>,
        systemPrompt: String,
        newMessage: String,
        maxContextSize: Int,
        reserveTokens: Int = 512,
    ): List<AiMessage> {
        if (maxContextSize <= 0) return emptyList()
        val systemTokens = estimateTokenCount(systemPrompt)
        val newMessageTokens = estimateTokenCount(newMessage)
        val budget = maxContextSize - systemTokens - newMessageTokens - reserveTokens
        if (budget <= 0) return emptyList()

        var used = 0
        val selected = mutableListOf<AiMessage>()
        for (msg in messages.reversed()) {
            val tokens = estimateTokenCount(msg.text) + 4
            if (used + tokens <= budget) {
                selected.add(msg)
                used += tokens
            } else {
                break
            }
        }
        return selected.reversed()
    }
}

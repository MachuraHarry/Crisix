package com.messenger.crisix.ai

import kotlin.math.roundToInt

object AiPromptTruncator {

    /** Fast character-based estimate: ~3.5 chars per token (German/English average). */
    fun estimateTokenCount(text: String): Int {
        return if (text.isEmpty()) 0 else ((text.length / 3.5f).roundToInt()).coerceAtLeast(1)
    }

    /**
     * Truncate messages to fit within [maxContextSize], using an optional
     * [tokenCounter] for exact token counts (e.g. llama.tokenize). Falls back
     * to [estimateTokenCount] when null or on error.
     */
    suspend fun truncateMessages(
        messages: List<AiMessage>,
        systemPrompt: String,
        newMessage: String,
        maxContextSize: Int,
        reserveTokens: Int = 512,
        tokenCounter: (suspend (String) -> Int)? = null,
    ): List<AiMessage> {
        if (maxContextSize <= 0) return emptyList()

        val count: suspend (String) -> Int = if (tokenCounter != null) {
            { text -> try { tokenCounter(text) } catch (_: Exception) { estimateTokenCount(text) } }
        } else {
            { text -> estimateTokenCount(text) }
        }

        val systemTokens = count(systemPrompt)
        val newMessageTokens = count(newMessage)
        val budget = maxContextSize - systemTokens - newMessageTokens - reserveTokens
        if (budget <= 0) return emptyList()

        var used = 0
        val selected = mutableListOf<AiMessage>()
        for (msg in messages.reversed()) {
            val tokens = count(msg.text) + 4
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

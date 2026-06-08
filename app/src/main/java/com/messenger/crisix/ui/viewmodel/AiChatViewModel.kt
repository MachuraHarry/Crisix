package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.ai.AiConversation
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatViewModel : ViewModel() {

    data class ListState(
        val conversations: List<AiConversation> = emptyList(),
    )

    data class DetailState(
        val messages: List<AiMessage> = emptyList(),
        val inputText: String = "",
        val isProcessing: Boolean = false,
    )

    private val _listState = MutableStateFlow(ListState())
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _detailStates = mutableMapOf<String, MutableStateFlow<DetailState>>()

    fun getDetailState(conversationId: String): StateFlow<DetailState> {
        return _detailStates.getOrPut(conversationId) {
            MutableStateFlow(DetailState())
        }.asStateFlow()
    }

    fun createConversation(): String {
        val conv = AiConversation()
        _detailStates[conv.id] = MutableStateFlow(DetailState())
        _listState.update { it.copy(conversations = it.conversations + conv) }
        return conv.id
    }

    fun onInputChange(conversationId: String, text: String) {
        _detailStates[conversationId]?.update { it.copy(inputText = text) }
    }

    fun sendMessage(conversationId: String) {
        val state = _detailStates[conversationId] ?: return
        val text = state.value.inputText.trim()
        if (text.isBlank() || state.value.isProcessing) return

        val userMessage = AiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = AiRole.USER,
            text = text,
        )

        _detailStates[conversationId]?.update {
            it.copy(messages = it.messages + userMessage, inputText = "", isProcessing = true)
        }

        updateConversationPreview(conversationId, text)

        // Mock-Antwort (wird später durch LiteRT-LM ersetzt)
        viewModelScope.launch {
            delay(800)
            val assistantMessage = AiMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = AiRole.ASSISTANT,
                text = "Crisix AI ist noch nicht mit einem KI-Modell verbunden. Sobald das Backend integriert ist, erhältst du hier echte Antworten.",
            )
            _detailStates[conversationId]?.update {
                it.copy(messages = it.messages + assistantMessage, isProcessing = false)
            }
            updateConversationPreview(conversationId, assistantMessage.text)
        }
    }

    fun clearChat(conversationId: String) {
        _detailStates[conversationId]?.update { DetailState() }
    }

    fun deleteConversation(conversationId: String) {
        _detailStates.remove(conversationId)
        _listState.update { it.copy(conversations = it.conversations.filter { it.id != conversationId }) }
    }

    private fun updateConversationPreview(conversationId: String, text: String) {
        val preview = text.take(80).replace("\n", " ")
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        _listState.update { state ->
            state.copy(
                conversations = state.conversations.map { conv ->
                    if (conv.id == conversationId) {
                        val title = if (conv.title == "Neuer Chat" && conv.lastMessage.isEmpty()) {
                            preview.take(30)
                        } else conv.title
                        conv.copy(
                            title = title,
                            lastMessage = preview,
                            timestamp = now,
                        )
                    } else conv
                }.sortedByDescending { it.timestamp }
            )
        }
    }
}

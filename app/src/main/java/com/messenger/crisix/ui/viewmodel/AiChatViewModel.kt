package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.ai.AiChatRepository
import com.messenger.crisix.ai.AiConversation
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiModelManager
import com.messenger.crisix.ai.AiRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatViewModel(
    private val modelManager: AiModelManager,
    private val repository: AiChatRepository,
) : ViewModel() {

    data class ListState(
        val conversations: List<AiConversation> = emptyList(),
    )

    data class DetailState(
        val messages: List<AiMessage> = emptyList(),
        val inputText: String = "",
        val isProcessing: Boolean = false,
        val streamingText: String = "",
    )

    private val _listState = MutableStateFlow(ListState())
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _detailStates = mutableMapOf<String, MutableStateFlow<DetailState>>()
    private val responseJobs = mutableMapOf<String, Job>()

    val modelStatus: StateFlow<AiModelManager.ModelStatus> = modelManager.status

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
            it.copy(messages = it.messages + userMessage, inputText = "", isProcessing = true, streamingText = "")
        }

        updateConversationPreview(conversationId, text)

        val history = state.value.messages

        responseJobs[conversationId] = viewModelScope.launch {
            try {
                val assistantId = java.util.UUID.randomUUID().toString()
                val fullText = StringBuilder()

                repository.generateResponse(history, text).collect { chunk ->
                    fullText.append(chunk)
                    _detailStates[conversationId]?.update {
                        it.copy(streamingText = fullText.toString())
                    }
                }

                val assistantMessage = AiMessage(
                    id = assistantId,
                    role = AiRole.ASSISTANT,
                    text = fullText.toString(),
                )
                _detailStates[conversationId]?.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        isProcessing = false,
                        streamingText = "",
                    )
                }
                updateConversationPreview(conversationId, assistantMessage.text)
            } catch (e: Exception) {
                val errorMsg = "Fehler: ${e.message ?: "Unbekannter Fehler"}"
                val errorMessage = AiMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = AiRole.ASSISTANT,
                    text = errorMsg,
                )
                _detailStates[conversationId]?.update {
                    it.copy(messages = it.messages + errorMessage, isProcessing = false, streamingText = "")
                }
            }
        }
    }

    fun clearChat(conversationId: String) {
        responseJobs[conversationId]?.cancel()
        responseJobs.remove(conversationId)
        _detailStates[conversationId]?.update { DetailState() }
    }

    fun deleteConversation(conversationId: String) {
        responseJobs[conversationId]?.cancel()
        responseJobs.remove(conversationId)
        _detailStates.remove(conversationId)
        _listState.update { it.copy(conversations = it.conversations.filter { it.id != conversationId }) }
    }

    override fun onCleared() {
        super.onCleared()
        modelManager.close()
    }

    private fun updateConversationPreview(conversationId: String, text: String) {
        val preview = text.take(80).replace("\n", " ")
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

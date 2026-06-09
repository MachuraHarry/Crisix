package com.messenger.crisix.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.ai.AiAgent
import com.messenger.crisix.ai.AiChatRepository
import com.messenger.crisix.ai.AiConversation
import com.messenger.crisix.ai.AiInferenceController
import com.messenger.crisix.ai.AiMessage
import com.messenger.crisix.ai.AiModelManager
import com.messenger.crisix.ai.AiRole
import com.messenger.crisix.ai.AiRuntimeState
import com.messenger.crisix.ai.DownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiChatViewModel(
    private val controller: AiInferenceController,
    private val modelManager: AiModelManager,
    private val repository: AiChatRepository,
    private val agent: AiAgent,
) : ViewModel() {

    data class ListState(
        val conversations: List<AiConversation> = emptyList(),
    )

    data class DetailState(
        val messages: List<AiMessage> = emptyList(),
        val inputText: String = "",
        val isProcessing: Boolean = false,
        val streamingText: String = "",
        val streamingThinking: String = "",
        val toolStatus: String = "",
    )

    private val _listState = MutableStateFlow(ListState())
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _detailStates = mutableMapOf<String, MutableStateFlow<DetailState>>()
    private val responseJobs = mutableMapOf<String, Job>()

    val runtimeState: StateFlow<AiRuntimeState> = controller.state
    val downloadState: StateFlow<DownloadProgress> = modelManager.downloadState

    fun getModelManager(): AiModelManager = modelManager
    fun getController(): AiInferenceController = controller

    val isModelLoaded: Boolean get() = controller.isReady

    fun toggleModel() {
        viewModelScope.launch {
            if (controller.isReady || controller.isGenerating) {
                controller.unload()
            } else if (modelManager.isDownloaded) {
                controller.load()
            }
        }
    }

    init {
        viewModelScope.launch {
            val convs = repository.loadConversations()
            _listState.update { it.copy(conversations = convs) }
            for (conv in convs) {
                val msgs = repository.loadMessages(conv.id)
                val existing = _detailStates[conv.id]
                if (existing != null) {
                    existing.update { it.copy(messages = msgs) }
                } else {
                    _detailStates[conv.id] = MutableStateFlow(DetailState(messages = msgs))
                }
            }
        }
        // Preload model in background if downloaded
        viewModelScope.launch {
            modelManager.preloadIfNeeded()
        }
    }

    fun getDetailState(conversationId: String): StateFlow<DetailState> {
        return _detailStates.getOrPut(conversationId) {
            MutableStateFlow(DetailState())
        }.asStateFlow()
    }

    fun downloadModel() {
        viewModelScope.launch {
            modelManager.downloadModel()
            if (modelManager.downloadState.value is DownloadProgress.Complete) {
                controller.load()
            }
        }
    }

    fun selectLocalModel(uri: Uri) {
        viewModelScope.launch {
            modelManager.selectLocalModel(uri)
            if (modelManager.downloadState.value is DownloadProgress.Complete) {
                controller.load()
            }
        }
    }

    fun createConversation(): String {
        val conv = AiConversation()
        _detailStates[conv.id] = MutableStateFlow(DetailState())
        _listState.update { it.copy(conversations = it.conversations + conv) }
        viewModelScope.launch {
            repository.saveConversation(conv)
        }
        return conv.id
    }

    fun ensureMessagesLoaded(conversationId: String) {
        val existing = _detailStates[conversationId]
        if (existing != null && existing.value.messages.isNotEmpty()) return
        viewModelScope.launch {
            val msgs = repository.loadMessages(conversationId)
            val state = _detailStates[conversationId]
            if (state != null) {
                state.update { it.copy(messages = msgs) }
            } else {
                _detailStates[conversationId] = MutableStateFlow(DetailState(messages = msgs))
            }
        }
    }

    fun onInputChange(conversationId: String, text: String) {
        _detailStates[conversationId]?.update { it.copy(inputText = text) }
    }

    fun sendMessage(conversationId: String) {
        val state = _detailStates[conversationId] ?: return
        val text = state.value.inputText.trim()
        if (text.isBlank() || state.value.isProcessing) return

        val history = state.value.messages

        val userMessage = AiMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = AiRole.USER,
            text = text,
        )

        _detailStates[conversationId]?.update {
            it.copy(messages = it.messages + userMessage, inputText = "", isProcessing = true, streamingText = "", streamingThinking = "", toolStatus = "")
        }

        responseJobs[conversationId] = viewModelScope.launch {
            if (!controller.isReady && modelManager.isDownloaded) {
                controller.load()
            }
            try {
                val conv = _listState.value.conversations.find { it.id == conversationId }
                if (conv != null) {
                    repository.upsertConversationWithMessage(conv, userMessage, text.take(80))
                }
                repository.rebuildConversationPreview(conversationId)

                val assistantId = java.util.UUID.randomUUID().toString()
                val fullText = StringBuilder()
                val fullThinking = StringBuilder()

                agent.generateResponse(
                    messages = history,
                    newMessage = text,
                    onToolStatus = { status ->
                        _detailStates[conversationId]?.update {
                            it.copy(toolStatus = status)
                        }
                    },
                ).collect { chunk ->
                    if (chunk.text.isNotEmpty()) fullText.append(chunk.text)
                    if (chunk.thinking.isNotEmpty()) fullThinking.append(chunk.thinking)
                    _detailStates[conversationId]?.update {
                        it.copy(
                            streamingText = fullText.toString(),
                            streamingThinking = fullThinking.toString().takeIf { it.isNotEmpty() } ?: "",
                        )
                    }
                }

                val assistantText = fullText.toString()
                val assistantThinking = fullThinking.toString().takeIf { it.isNotBlank() }
                Log.d("AiChatViewModel", "Assistant text: [$assistantText]")

                if (assistantText.isNotBlank()) {
                    val assistantMessage = AiMessage(
                        id = assistantId,
                        role = AiRole.ASSISTANT,
                        text = assistantText,
                        thinking = assistantThinking,
                    )
                    _detailStates[conversationId]?.update {
                        it.copy(
                            messages = it.messages + assistantMessage,
                            isProcessing = false,
                            streamingText = "",
                            streamingThinking = "",
                            toolStatus = "",
                        )
                    }
                    repository.saveMessage(conversationId, assistantMessage)
                    repository.rebuildConversationPreview(conversationId)
                    reloadConversationInList(conversationId)
                } else {
                    _detailStates[conversationId]?.update {
                        it.copy(isProcessing = false, streamingText = "", streamingThinking = "", toolStatus = "")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "Fehler: ${e.message ?: "Unbekannter Fehler"}"
                val errorMessage = AiMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = AiRole.ASSISTANT,
                    text = errorMsg,
                )
                _detailStates[conversationId]?.update {
                    it.copy(messages = it.messages + errorMessage, isProcessing = false, streamingText = "", streamingThinking = "", toolStatus = "")
                }
            }
        }
    }

    fun clearChat(conversationId: String) {
        responseJobs[conversationId]?.cancel()
        responseJobs.remove(conversationId)
        _detailStates[conversationId]?.update { DetailState() }
        viewModelScope.launch {
            repository.deleteMessages(conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        responseJobs[conversationId]?.cancel()
        responseJobs.remove(conversationId)
        _detailStates.remove(conversationId)
        _listState.update { it.copy(conversations = it.conversations.filter { it.id != conversationId }) }
        viewModelScope.launch {
            repository.deleteConversation(conversationId)
        }
    }

    fun stopPrediction() {
        controller.cancel()
    }

    fun cancelResponse(conversationId: String) {
        controller.cancel()
        responseJobs[conversationId]?.cancel()
        responseJobs.remove(conversationId)
        _detailStates[conversationId]?.update {
            it.copy(isProcessing = false, streamingText = "", streamingThinking = "", toolStatus = "")
        }
    }

    fun deleteMessage(conversationId: String, messageId: String) {
        _detailStates[conversationId]?.update { state ->
            state.copy(messages = state.messages.filter { it.id != messageId })
        }
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun deleteAllChats() {
        viewModelScope.launch {
            repository.deleteAllConversations()
            _detailStates.clear()
            _listState.update { it.copy(conversations = emptyList()) }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    private suspend fun reloadConversationInList(conversationId: String) {
        val updated = repository.loadConversations()
        _listState.update { it.copy(conversations = updated) }
    }
}

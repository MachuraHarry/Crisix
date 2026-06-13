package com.messenger.crisix.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.messenger.crisix.R
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
import com.messenger.crisix.ai.OverallDownloadState
import com.messenger.crisix.ai.SpeechManager
import com.messenger.crisix.ai.SpeechState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class PendingToolInfo(
    val toolName: String,
    val params: String,
    val deferred: CompletableDeferred<Boolean>,
)

class AiChatViewModel(
    private val controller: AiInferenceController,
    private val modelManager: AiModelManager,
    private val repository: AiChatRepository,
    private val agent: AiAgent,
    private val speechManager: SpeechManager,
    private val context: Context,
) : ViewModel() {

    data class ListState(
        val conversations: List<AiConversation> = emptyList(),
    )

    data class DetailState(
        val messages: List<AiMessage> = emptyList(),
        val inputText: String = "",
        val isProcessing: Boolean = false,
        val isVoiceActive: Boolean = false,
        val streamingText: String = "",
        val streamingThinking: String = "",
        val toolStatus: String = "",
    )

    private val _listState = MutableStateFlow(ListState())
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    private val _detailStates = mutableMapOf<String, MutableStateFlow<DetailState>>()
    private val responseJobs = mutableMapOf<String, Job>()

    private val _toolConfirmRequest = MutableStateFlow<PendingToolInfo?>(null)
    val toolConfirmRequest: StateFlow<PendingToolInfo?> = _toolConfirmRequest.asStateFlow()

    val runtimeState: StateFlow<AiRuntimeState> = controller.state
    val downloadState: StateFlow<DownloadProgress> = modelManager.downloadState
    val speechState: StateFlow<SpeechState> = speechManager.state
    val speechDownloadState: StateFlow<OverallDownloadState> = speechManager.downloadState

    private val _showDownloadDialog = MutableStateFlow(false)
    val showDownloadDialog: StateFlow<Boolean> = _showDownloadDialog.asStateFlow()

    private val _isNoWifi = MutableStateFlow(false)
    val isNoWifi: StateFlow<Boolean> = _isNoWifi.asStateFlow()

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
            if (modelManager.isDownloaded) {
                controller.load()
            }
        }
        // Auto-load speech whenever AI becomes Ready (z. B. nach lokalem Modell)
        viewModelScope.launch {
            controller.state.collect { state ->
                if (state is AiRuntimeState.Ready && speechManager.areModelsDownloaded) {
                    if (speechManager.state.value !is SpeechState.Ready && speechManager.state.value !is SpeechState.Loading) {
                        speechManager.load()
                    }
                }
            }
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
        val conv = _listState.value.conversations.find { it.id == conversationId }

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
                if (conv != null) {
                    repository.upsertConversationWithMessage(conv, userMessage, text.take(80))
                }
                repository.rebuildConversationPreview(conversationId)

                val isAgentMode = conv?.isAgentMode ?: true
                val assistantId = java.util.UUID.randomUUID().toString()
                val fullText = StringBuilder()
                val fullThinking = StringBuilder()

                if (isAgentMode) {
                    agent.generateResponse(
                        messages = history,
                        newMessage = text,
                        onToolStatus = { status ->
                            _detailStates[conversationId]?.update {
                                it.copy(toolStatus = status)
                            }
                        },
                        onToolConfirm = { toolName, params ->
                            val deferred = CompletableDeferred<Boolean>()
                            _toolConfirmRequest.value = PendingToolInfo(toolName, params, deferred)
                            val result = deferred.await()
                            _toolConfirmRequest.value = null
                            result
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
                } else {
                    repository.generateChatResponse(
                        messages = history,
                        newMessage = text,
                    ).collect { token ->
                        fullText.append(token)
                        _detailStates[conversationId]?.update {
                            it.copy(streamingText = fullText.toString())
                        }
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
                val errorMsg = context.getString(R.string.ai_error_prefix, e.message ?: context.getString(R.string.ai_error_unknown))
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

    fun toggleAgentMode(conversationId: String) {
        viewModelScope.launch {
            repository.toggleAgentMode(conversationId)
            val convs = repository.loadConversations()
            _listState.update { it.copy(conversations = convs) }
        }
    }

    fun isAgentMode(conversationId: String): Boolean {
        return _listState.value.conversations.find { it.id == conversationId }?.isAgentMode ?: true
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

    fun confirmTool() {
        _toolConfirmRequest.value?.deferred?.complete(true)
    }

    fun cancelTool() {
        _toolConfirmRequest.value?.deferred?.complete(false)
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

    private var voiceInputJob: Job? = null

    fun toggleVoiceInput(conversationId: String) {
        val state = _detailStates[conversationId]?.value ?: return
        if (state.isVoiceActive) {
            speechManager.stopListening()
            voiceInputJob?.cancel()
            voiceInputJob = null
            _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
            Log.d("toggleVoiceInput", "Voice input stopped")
        } else {
            Log.d("toggleVoiceInput", "Starting voice input")
            voiceInputJob?.cancel()
            _detailStates[conversationId]?.update { it.copy(isVoiceActive = true) }
            voiceInputJob = viewModelScope.launch {
                try {
                    speechManager.startListening()
                    Log.d("toggleVoiceInput", "startListening called")
                    val partialJob = launch {
                        speechManager.partialText.collect { partial ->
                            _detailStates[conversationId]?.update { it.copy(inputText = partial) }
                        }
                    }
                    try {
                        val finalText = withTimeout(30_000L) { speechManager.transcriptions.first() }
                        partialJob.cancel()
                        speechManager.stopListening()
                        _detailStates[conversationId]?.update {
                            it.copy(inputText = finalText, isVoiceActive = false)
                        }
                        Log.d("toggleVoiceInput", "Transcription complete: $finalText")
                    } catch (_: Exception) {
                        Log.w("toggleVoiceInput", "Timeout or error waiting for transcription")
                        partialJob.cancel()
                        speechManager.stopListening()
                        _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
                    }
                } catch (e: Exception) {
                    Log.e("toggleVoiceInput", "Voice input failed", e)
                    _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
                }
            }
        }
    }

    fun startOrDownloadVoice(conversationId: String) {
        val s = speechManager.state.value
        Log.d("startOrDownloadVoice", "speechState=$s, areModelsDownloaded=${speechManager.areModelsDownloaded}")
        if (s is SpeechState.Ready || s is SpeechState.Listening || s is SpeechState.Transcribing) {
            Log.d("startOrDownloadVoice", "Pipeline already ready, toggling voice input")
            toggleVoiceInput(conversationId)
        } else if (speechManager.areModelsDownloaded) {
            Log.d("startOrDownloadVoice", "Models on disk, loading + listening")
            confirmDownload(conversationId)
        } else {
            checkAndShowDownloadDialog()
        }
    }

    private fun checkAndShowDownloadDialog() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        _isNoWifi.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
        _showDownloadDialog.value = true
    }

    fun dismissDownloadDialog() {
        _showDownloadDialog.value = false
    }

    fun confirmDownload(conversationId: String) {
        Log.d("confirmDownload", "Starting, areModelsDownloaded=${speechManager.areModelsDownloaded}, speechState=${speechManager.state.value}")
        _showDownloadDialog.value = false
        voiceInputJob?.cancel()
        _detailStates[conversationId]?.update { it.copy(isVoiceActive = true) }
        voiceInputJob = viewModelScope.launch {
            try {
                if (!speechManager.areModelsDownloaded) {
                    Log.d("confirmDownload", "Models not downloaded, starting download")
                    if (!speechManager.downloadModels()) {
                        Log.e("confirmDownload", "Download failed")
                        _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
                        return@launch
                    }
                    Log.d("confirmDownload", "Download complete")
                }
                Log.d("confirmDownload", "Loading speech pipeline")
                if (!speechManager.load()) {
                    Log.e("confirmDownload", "Speech load failed")
                    _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
                    return@launch
                }
                Log.d("confirmDownload", "Speech loaded, starting listening")
                speechManager.startListening()

                val partialJob = launch {
                    speechManager.partialText.collect { partial ->
                        _detailStates[conversationId]?.update { it.copy(inputText = partial) }
                    }
                }
                try {
                    val finalText = withTimeout(30_000L) { speechManager.transcriptions.first() }
                    partialJob.cancel()
                    speechManager.stopListening()
                    _detailStates[conversationId]?.update {
                        it.copy(inputText = finalText, isVoiceActive = false)
                    }
                } catch (_: Exception) {
                    partialJob.cancel()
                    speechManager.stopListening()
                    _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
                }
            } catch (e: Exception) {
                Log.e("confirmDownload", "Voice input failed", e)
                _detailStates[conversationId]?.update { it.copy(isVoiceActive = false) }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch {
            speechManager.unload()
        }
        super.onCleared()
    }

    private suspend fun reloadConversationInList(conversationId: String) {
        val updated = repository.loadConversations()
        _listState.update { it.copy(conversations = updated) }
    }
}

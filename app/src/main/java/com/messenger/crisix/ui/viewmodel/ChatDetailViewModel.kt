package com.messenger.crisix.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.transport.TransportCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatDetailState(
    val chatPeerId: String = "",
    val chatName: String = "",
    val hasE2eeSession: Boolean = false,
    val isHandshaking: Boolean = false,
    val transportCapabilities: TransportCapabilities = TransportCapabilities(),
)

class ChatDetailViewModel(
    private val messageRepository: MessageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatDetailState())
    val state: StateFlow<ChatDetailState> = _state.asStateFlow()

    fun initialize(chatId: String, chatName: String) {
        _state.update { it.copy(chatPeerId = chatId, chatName = chatName) }
    }

    fun updateE2eeStatus(hasSession: Boolean, isHandshaking: Boolean) {
        _state.update { it.copy(hasE2eeSession = hasSession, isHandshaking = isHandshaking) }
    }

    fun updateCapabilities(capabilities: TransportCapabilities) {
        _state.update { it.copy(transportCapabilities = capabilities) }
    }

    fun getPagedMessages(chatId: String): kotlinx.coroutines.flow.Flow<PagingData<com.messenger.crisix.data.MessageEntity>> {
        return messageRepository.getPagedMessages(chatId)
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId)
        }
    }

    fun markAsRead(chatId: String) {
        viewModelScope.launch {
            messageRepository.markChatAsRead(chatId)
        }
    }
}

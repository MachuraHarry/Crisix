package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.screens.ChatPreview
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.state.ChatListUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    fun computeChats(
        discoveredPeers: List<Peer>,
        allMessages: Map<String, List<Message>>,
        incomingNames: Map<String, String>,
        savedContacts: List<Contact>,
        unreadCounts: Map<String, Int>,
        activeTransportType: TransportType?,
        nowText: String,
        defaultMessageText: String,
    ): List<ChatPreview> {
        val chatList = mutableListOf<ChatPreview>()
        val seenIds = mutableSetOf<String>()

        fun resolveDisplayName(peerId: String, fallback: String): String {
            val contact = savedContacts.find { it.peerId == peerId }
            if (contact != null && contact.name.isNotBlank()) return contact.name
            return fallback
        }

        for (peer in discoveredPeers) {
            val normId = peer.id.split("@").first()
            if (normId in seenIds) continue
            seenIds.add(normId)
            val peerMessages = allMessages[normId] ?: emptyList()
            val lastMsg = peerMessages.lastOrNull()
            val displayName = resolveDisplayName(normId, peer.name)
            chatList.add(
                ChatPreview(
                    id = normId,
                    name = displayName,
                    lastMessage = getMessagePreview(lastMsg).ifBlank { defaultMessageText },
                    timestamp = lastMsg?.timestamp ?: nowText,
                    timestampMillis = lastMsg?.timestampMillis ?: 0L,
                    unreadCount = unreadCounts[normId] ?: 0,
                    transportType = activeTransportType,
                )
            )
        }

        for ((peerId, messages) in allMessages) {
            val normId = peerId.split("@").first()
            if (normId in seenIds) continue
            if (messages.isEmpty()) continue
            seenIds.add(normId)
            val lastMsg = messages.last()
            val peerDisplayName = resolveDisplayName(normId, incomingNames[normId] ?: normId.take(8))
            chatList.add(
                ChatPreview(
                    id = normId,
                    name = peerDisplayName,
                    lastMessage = getMessagePreview(lastMsg),
                    timestamp = lastMsg.timestamp,
                    timestampMillis = lastMsg.timestampMillis,
                    unreadCount = unreadCounts[normId] ?: 0,
                    transportType = activeTransportType,
                )
            )
        }

        _uiState.value = _uiState.value.copy(
            chats = chatList,
            isEmpty = chatList.isEmpty(),
        )

        return chatList
    }

    fun deleteChat(chatId: String, messageRepository: MessageRepository) {
        viewModelScope.launch {
            messageRepository.deleteChat(chatId)
        }
    }

    private fun getMessagePreview(message: Message?): String {
        if (message == null) return ""

        if (message.isEncrypted) {
            return when {
                message.imageUri != null -> "\uD83D\uDDBC\uFE0F Bild"
                message.audioUri != null -> "\uD83C\uDFA4 Sprachnachricht"
                else -> message.text.ifBlank { "\uD83D\uDD12 Verschl\u00FCsselt" }
            }
        }

        return when {
            message.imageUri != null -> "\uD83D\uDDBC\uFE0F Bild"
            message.audioUri != null -> "\uD83C\uDFA4 Sprachnachricht"
            message.text.isNotEmpty() -> message.text
            else -> "\uD83D\uDCE8 Nachricht"
        }
    }
}

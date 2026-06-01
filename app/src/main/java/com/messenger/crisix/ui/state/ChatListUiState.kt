package com.messenger.crisix.ui.state

import com.messenger.crisix.ui.screens.ChatPreview

data class ChatListUiState(
    val chats: List<ChatPreview> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = true
)

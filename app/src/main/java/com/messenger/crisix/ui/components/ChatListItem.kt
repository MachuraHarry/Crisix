package com.messenger.crisix.ui.components

import androidx.compose.runtime.Stable

@Stable
sealed class ChatListItem {
    data class MessageItem(
        val message: Message,
        val isGrouped: Boolean
    ) : ChatListItem()

    data class DateHeaderItem(
        val dateGroupOrdinal: Int,
        val olderDateLabel: String?,
        val key: String
    ) : ChatListItem()
}

package com.messenger.crisix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.messenger.crisix.data.MessageEntity
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.data.toMessage
import com.messenger.crisix.ui.components.ChatListItem
import com.messenger.crisix.ui.components.Message
import com.messenger.crisix.util.DateGroup
import com.messenger.crisix.util.getDateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ChatDetailViewModel(
    private val messageRepository: MessageRepository,
    private val chatId: String
) : ViewModel() {

    companion object {
        fun factory(
            messageRepository: MessageRepository,
            chatId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatDetailViewModel(messageRepository, chatId) as T
            }
        }
    }

    val messages: Flow<PagingData<ChatListItem>> = messageRepository.getPagedMessages(chatId)
        .map { pagingData: PagingData<MessageEntity> ->
            var previousMessage: Message? = null
            pagingData
                .map { entity: MessageEntity ->
                    val dateGroup = getDateGroup(entity.timestampMillis)
                    val olderLabel = if (dateGroup == DateGroup.OLDER) {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = entity.timestampMillis
                        }
                        SimpleDateFormat("d. MMMM", Locale.getDefault()).format(cal.time)
                    } else {
                        null
                    }
                    val message = entity.toMessage().copy(
                        dateGroupOrdinal = dateGroup.ordinal,
                        olderDateLabel = olderLabel
                    )
                    val isGrouped = previousMessage?.let { prev ->
                        !message.isSystemMessage &&
                        prev.isFromMe == message.isFromMe &&
                        kotlin.math.abs(message.timestampMillis - prev.timestampMillis) < 60_000L
                    } ?: false
                    previousMessage = message
                    ChatListItem.MessageItem(message, isGrouped)
                }
                .insertSeparators { before, after ->
                    val msgBefore = before?.message
                    val msgAfter = after?.message
                    if (msgAfter != null && (msgBefore == null ||
                            (msgAfter.dateGroupOrdinal >= 0 &&
                             msgAfter.dateGroupOrdinal != msgBefore.dateGroupOrdinal))
                    ) {
                        ChatListItem.DateHeaderItem(
                            dateGroupOrdinal = msgAfter.dateGroupOrdinal,
                            olderDateLabel = msgAfter.olderDateLabel,
                            key = "date_header_${msgAfter.id}"
                        )
                    } else null
                }
        }
        .flowOn(Dispatchers.Default)
        .cachedIn(viewModelScope)
}

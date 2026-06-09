package com.messenger.crisix.ai

import android.content.Context
import com.messenger.crisix.R
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiToolExecutor(private val context: Context) {

    private val messageRepo = MessageRepository(context)
    private val contactRepo = ContactRepository(context)

    suspend fun execute(tool: AiTool): ToolResult = withContext(Dispatchers.IO) {
        when (tool) {
            is AiTool.GetChats -> executeGetChats()
            is AiTool.GetMessages -> executeGetMessages(tool.chatName, tool.limit)
            is AiTool.GetContacts -> executeGetContacts()
            is AiTool.SearchMessages -> executeSearchMessages(tool.query, tool.limit)
            is AiTool.GetSettings -> executeGetSettings()
            is AiTool.GetConversationStats -> executeGetConversationStats()
        }
    }

    private suspend fun executeGetChats(): ToolResult {
        val chats = messageRepo.allChats.first()
        val ctx = context
        if (chats.isEmpty()) return ToolResult("get_chats", ctx.getString(R.string.ai_tool_result_no_chats))

        val lines = chats.map { chat ->
            val unread = if (chat.unreadCount > 0) " (${ctx.getString(R.string.ai_tool_result_unread_count, chat.unreadCount)})" else ""
            val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(chat.timestampMillis))
            "- $date | ${chat.name}$unread: ${chat.lastMessage.take(60)}"
        }
        return ToolResult("get_chats", ctx.getString(R.string.ai_tool_result_chats_count, chats.size, lines.joinToString("\n")))
    }

    private suspend fun executeGetMessages(chatName: String, limit: Int): ToolResult {
        val chats = messageRepo.allChats.first()
        val ctx = context
        val chat = chats.find { it.name.equals(chatName, ignoreCase = true) }
            ?: chats.find { it.name.contains(chatName, ignoreCase = true) }
        if (chat == null) {
            val available = chats.take(10).joinToString(", ") { it.name }
            return ToolResult("get_messages", ctx.getString(R.string.ai_tool_result_chat_not_found, chatName, available))
        }

        val messages = messageRepo.loadMessagesOnce(chat.id)
        val recent = messages.takeLast(limit.coerceIn(1, 50))

        if (recent.isEmpty()) return ToolResult("get_messages", ctx.getString(R.string.ai_tool_result_no_messages, chat.name))

        val lines = recent.map { msg ->
            val sender = if (msg.isFromMe) "Du" else chat.name
            val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(msg.timestampMillis))
            "[$date] $sender: ${msg.text.take(200)}"
        }
        return ToolResult("get_messages", ctx.getString(R.string.ai_tool_result_messages_from, chat.name, recent.size, lines.joinToString("\n")))
    }

    private suspend fun executeGetContacts(): ToolResult {
        val contacts = contactRepo.loadContacts()
        val ctx = context
        if (contacts.isEmpty()) return ToolResult("get_contacts", ctx.getString(R.string.ai_tool_result_no_contacts))

        val lines = contacts.map { c ->
            val note = if (c.note.isNotBlank()) " - ${c.note}" else ""
            "- ${c.name}$note"
        }
        return ToolResult("get_contacts", ctx.getString(R.string.ai_tool_result_contacts_count, contacts.size, lines.joinToString("\n")))
    }

    private suspend fun executeSearchMessages(query: String, limit: Int): ToolResult {
        val all = messageRepo.loadAllMessages()
        val ctx = context
        val q = query.lowercase()
        val matches = all.filter { it.text.lowercase().contains(q) }
            .takeLast(limit.coerceIn(1, 50))

        if (matches.isEmpty()) return ToolResult("search_messages", ctx.getString(R.string.ai_tool_result_no_search_results, query))

        val chats = messageRepo.allChats.first()
        val chatNames = chats.associate { it.id to it.name }

        val lines = matches.map { msg ->
            val chatName = chatNames[msg.chatId] ?: msg.chatId
            val sender = if (msg.isFromMe) "Du" else chatName
            val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(msg.timestampMillis))
            "[$date] $sender in '$chatName': ${msg.text.take(200)}"
        }
        return ToolResult("search_messages", ctx.getString(R.string.ai_tool_result_search_found, matches.size, lines.joinToString("\n")))
    }

    private suspend fun executeGetSettings(): ToolResult {
        val prefs = context.settingsDataStore.data.first()
        val lines = mutableListOf<String>()
        prefs.asMap().forEach { (key, value) ->
            val keyName = key.name
            if (!keyName.startsWith("ai_model_") && !keyName.startsWith("ai_auto_")) {
                lines.add("- $keyName = $value")
            }
        }
        return ToolResult("get_settings", context.getString(R.string.ai_tool_result_settings_count, lines.size, lines.joinToString("\n")))
    }

    private suspend fun executeGetConversationStats(): ToolResult {
        val chats = messageRepo.allChats.first()
        val ctx = context
        if (chats.isEmpty()) return ToolResult("get_conversation_stats", ctx.getString(R.string.ai_tool_result_no_chats))

        val allMessages = messageRepo.loadAllMessages()
        val totalMessages = allMessages.size
        val fromMe = allMessages.count { it.isFromMe }
        val fromOthers = totalMessages - fromMe

        val chatStats = chats.map { chat ->
            val msgs = allMessages.count { it.chatId == chat.id }
            chat.name to msgs
        }.sortedByDescending { it.second }

        val topChats = chatStats.take(5).joinToString("\n") { "- ${it.first}: ${ctx.getString(R.string.ai_tool_result_stats_message_count, it.second)}" }
        val totalChats = chatStats.size

        return ToolResult("get_conversation_stats",
            ctx.getString(R.string.ai_tool_result_stats, totalMessages, totalChats, fromMe, fromOthers, topChats)
        )
    }
}

data class ToolResult(
    val toolName: String,
    val summary: String,
)

package com.messenger.crisix.ai

import android.content.Context
import android.provider.ContactsContract
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

    suspend fun execute(toolEntry: ToolEntry, args: Any?): ToolResult = withContext(Dispatchers.IO) {
        toolEntry.execute(this@AiToolExecutor, args)
    }

    suspend fun executeGetChats(): ToolResult {
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

    suspend fun executeGetMessages(chatName: String, limit: Int): ToolResult {
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

    suspend fun executeGetContacts(): ToolResult {
        val contacts = contactRepo.loadContacts()
        val ctx = context
        if (contacts.isEmpty()) return ToolResult("get_contacts", ctx.getString(R.string.ai_tool_result_no_contacts))

        val lines = contacts.map { c ->
            val note = if (c.note.isNotBlank()) " - ${c.note}" else ""
            "- ${c.name}$note"
        }
        return ToolResult("get_contacts", ctx.getString(R.string.ai_tool_result_contacts_count, contacts.size, lines.joinToString("\n")))
    }

    suspend fun executeSearchMessages(query: String, limit: Int): ToolResult {
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

    suspend fun executeGetSettings(): ToolResult {
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

    suspend fun executeGetConversationStats(): ToolResult {
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

    suspend fun executeGetCurrentTime(): ToolResult {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMAN)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)
        val dateStr = dateFormat.format(now)
        val timeStr = timeFormat.format(now)
        return ToolResult("get_current_time", "Aktuelles Datum: $dateStr\nAktuelle Uhrzeit: $timeStr")
    }

    suspend fun executeGetContactDetail(name: String): ToolResult {
        val contacts = contactRepo.loadContacts()
        val contact = contacts.find { it.name.equals(name, ignoreCase = true) }
            ?: contacts.find { it.name.contains(name, ignoreCase = true) }
        if (contact == null) {
            val available = contacts.take(10).joinToString(", ") { it.name }
            return ToolResult("get_contact_detail", "Kontakt '$name' nicht gefunden. Verfügbare Kontakte: $available")
        }

        val phone = getContactPhone(contact.name)
        val detailLines = mutableListOf("- Name: ${contact.name}")
        if (phone != null) detailLines.add("- Telefon: $phone")
        if (contact.note.isNotBlank()) detailLines.add("- Notiz: ${contact.note}")
        return ToolResult("get_contact_detail", detailLines.joinToString("\n"))
    }

    suspend fun executeSendMessage(chatName: String, text: String): ToolResult {
        val chats = messageRepo.allChats.first()
        val chat = chats.find { it.name.equals(chatName, ignoreCase = true) }
            ?: chats.find { it.name.contains(chatName, ignoreCase = true) }
        if (chat == null) {
            val available = chats.take(10).joinToString(", ") { it.name }
            return ToolResult("send_message", "Chat '$chatName' nicht gefunden. Verfügbare Chats: $available")
        }
        messageRepo.sendMessage(chat.id, text)
        return ToolResult("send_message", "Nachricht an '${chat.name}' gesendet: $text")
    }

    private fun getContactPhone(displayName: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        val cursor = context.contentResolver.query(uri, projection, selection, arrayOf(displayName), null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}

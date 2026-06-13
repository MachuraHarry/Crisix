package com.messenger.crisix.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import com.messenger.crisix.R
import com.messenger.crisix.data.AiNoteDao
import com.messenger.crisix.data.AiNoteEntity
import com.messenger.crisix.data.AiReminderDao
import com.messenger.crisix.data.AiReminderEntity
import com.messenger.crisix.data.AppDatabase
import com.messenger.crisix.data.ReminderAlarmReceiver
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class AiToolExecutor(private val context: Context) {

    private val messageRepo = MessageRepository(context)
    private val contactRepo = ContactRepository(context)
    private val noteDao: AiNoteDao = AppDatabase.getInstance(context).aiNoteDao()
    private val reminderDao: AiReminderDao = AppDatabase.getInstance(context).aiReminderDao()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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

    suspend fun executeWebSearch(query: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext ToolResult("web_search", "Keine Antwort vom Suchdienst.")
            val json = JSONObject(body)

            val parts = mutableListOf<String>()

            val answer = json.optString("Answer", "")
            if (answer.isNotBlank()) parts.add("Antwort: $answer")

            val abstractText = json.optString("AbstractText", "")
            if (abstractText.isNotBlank()) {
                val source = json.optString("AbstractSource", "")
                parts.add(abstractText + if (source.isNotBlank()) " (Quelle: $source)" else "")
            }

            val topics = json.optJSONArray("RelatedTopics")
            if (topics != null) {
                val results = mutableListOf<String>()
                for (i in 0 until topics.length().coerceAtMost(5)) {
                    val topic = topics.optJSONObject(i)
                    if (topic != null) {
                        val text = topic.optString("Text", "")
                        if (text.isNotBlank()) results.add(text)
                        val subTopics = topic.optJSONArray("Topics")
                        if (subTopics != null) {
                            for (j in 0 until subTopics.length().coerceAtMost(3)) {
                                val sub = subTopics.optJSONObject(j)
                                if (sub != null) {
                                    val st = sub.optString("Text", "")
                                    if (st.isNotBlank()) results.add("  - $st")
                                }
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    parts.add("Weitere Ergebnisse:")
                    parts.addAll(results)
                }
            }

            if (parts.isEmpty()) {
                val results = json.optJSONArray("Results")
                if (results != null && results.length() > 0) {
                    for (i in 0 until results.length().coerceAtMost(3)) {
                        val r = results.optJSONObject(i)
                        if (r != null) {
                            val text = r.optString("Text", "")
                            if (text.isNotBlank()) parts.add(text)
                        }
                    }
                }
            }

            if (parts.isEmpty()) return@withContext ToolResult("web_search", "Keine Suchergebnisse für '$query' gefunden.")
            ToolResult("web_search", parts.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult("web_search", "Fehler bei der Suche: ${e.message}")
        }
    }

    suspend fun executeCreateNote(title: String, content: String): ToolResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val note = AiNoteEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            updatedAt = now,
            createdAt = now,
        )
        noteDao.insertNote(note)
        ToolResult("create_note", "Notiz '${title.take(50)}' wurde erstellt.")
    }

    suspend fun executeGetNotes(search: String?): ToolResult = withContext(Dispatchers.IO) {
        val notes = if (search != null && search.isNotBlank()) {
            noteDao.searchNotes(search)
        } else {
            noteDao.getAllNotesOnce()
        }
        if (notes.isEmpty()) return@withContext ToolResult("get_notes", "Keine Notizen gefunden.")
        val lines = notes.map { n ->
            val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(n.updatedAt))
            val preview = n.content.take(80).replace("\n", " ")
            "- [${n.title.take(30)}] ($date): $preview"
        }
        ToolResult("get_notes", "${notes.size} Notizen:\n${lines.joinToString("\n")}")
    }

    suspend fun executeCreateReminder(title: String, due: String): ToolResult = withContext(Dispatchers.IO) {
        val dueTime = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(due)?.time
                ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(due)?.time
        } catch (_: Exception) { null }

        if (dueTime == null || dueTime <= System.currentTimeMillis()) {
            return@withContext ToolResult("create_reminder", "Ungültige oder bereits vergangene Zeit. Bitte Datum im ISO-Format angeben (z.B. 2026-06-15T14:30).")
        }

        val id = UUID.randomUUID().toString()
        val reminder = AiReminderEntity(
            id = id,
            title = title,
            dueTime = dueTime,
            createdAt = System.currentTimeMillis(),
        )
        reminderDao.insertReminder(reminder)

        val alarmIntent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, id)
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_TITLE, title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, id.hashCode(), alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmMgr.canScheduleExactAlarms()) {
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, dueTime, pendingIntent)
            } else {
                alarmMgr.set(AlarmManager.RTC_WAKEUP, dueTime, pendingIntent)
            }
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, dueTime, pendingIntent)
        }

        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(dueTime))
        ToolResult("create_reminder", "Erinnerung '${title.take(50)}' wurde für $dateStr erstellt.")
    }

    suspend fun executeGetReminders(status: String): ToolResult = withContext(Dispatchers.IO) {
        val all = reminderDao.getAllRemindersOnce()
        val filtered = when (status.lowercase()) {
            "completed" -> all.filter { it.isCompleted }
            "all" -> all
            else -> all.filter { !it.isCompleted }
        }
        if (filtered.isEmpty()) {
            return@withContext ToolResult("get_reminders", "Keine ${if (status == "completed") "erledigten" else "ausstehenden"} Erinnerungen gefunden.")
        }
        val lines = filtered.map { r ->
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(r.dueTime))
            val done = if (r.isCompleted) " ✓" else ""
            "- (${r.id.take(8)}) ${r.title.take(40)} — fällig am $date$done"
        }
        val header = "${filtered.size} Erinnerung(en):\n"
        ToolResult("get_reminders", header + lines.joinToString("\n"))
    }

    suspend fun executeCompleteReminder(reminderId: String): ToolResult = withContext(Dispatchers.IO) {
        val existing = reminderDao.getReminderById(reminderId)
        if (existing == null) return@withContext ToolResult("complete_reminder", "Erinnerung '$reminderId' nicht gefunden.")
        reminderDao.markCompleted(reminderId)
        ToolResult("complete_reminder", "Erinnerung '${existing.title.take(40)}' wurde als erledigt markiert.")
    }

    suspend fun executeDeleteReminder(reminderId: String): ToolResult = withContext(Dispatchers.IO) {
        val existing = reminderDao.getReminderById(reminderId)
        if (existing == null) return@withContext ToolResult("delete_reminder", "Erinnerung '$reminderId' nicht gefunden.")
        reminderDao.deleteReminder(reminderId)
        ToolResult("delete_reminder", "Erinnerung '${existing.title.take(40)}' wurde gelöscht.")
    }

    suspend fun executeRememberInfo(key: String, value: String): ToolResult {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.AI_REMEMBERED_KEY(key)] = value
        }
        ToolResult("remember_info", "Info '${key.take(30)}' wurde gemerkt.")
        // Re-read to confirm
        val stored = context.settingsDataStore.data.first()[SettingsKeys.AI_REMEMBERED_KEY(key)]
        return if (stored == value) {
            ToolResult("remember_info", "Info '${key.take(30)}' wurde erfolgreich gespeichert.")
        } else {
            ToolResult("remember_info", "Fehler beim Speichern von '${key.take(30)}'.")
        }
    }

    suspend fun executeGetRememberedInfo(key: String?): ToolResult {
        val prefs = context.settingsDataStore.data.first()
        if (key != null && key.isNotBlank()) {
            val value = prefs[SettingsKeys.AI_REMEMBERED_KEY(key)]
            if (value != null) return ToolResult("get_remembered_info", "$key = $value")
            return ToolResult("get_remembered_info", "Keine Info zu '$key' gefunden.")
        }
        val lines = mutableListOf<String>()
        prefs.asMap().forEach { (k, v) ->
            val name = k.name
            if (name.startsWith("ai_remembered_")) {
                val infoKey = name.removePrefix("ai_remembered_")
                lines.add("- $infoKey = $v")
            }
        }
        if (lines.isEmpty()) return ToolResult("get_remembered_info", "Keine gemerkten Infos vorhanden.")
        return ToolResult("get_remembered_info", lines.joinToString("\n"))
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

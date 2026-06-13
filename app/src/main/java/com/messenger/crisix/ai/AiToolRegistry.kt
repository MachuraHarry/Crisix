package com.messenger.crisix.ai

import java.util.regex.Pattern

object AiToolRegistry {

    private val toolParamRegex = Pattern.compile(
        "<param\\s+name=\"([^\"]+)\">(.*?)</param>",
        Pattern.DOTALL,
    )

    private val tools: List<ToolEntry> = listOf(
        ToolEntry(
            name = "get_chats",
            description = "Listet alle Chats/Konversationen mit letzter Nachricht und ungelesenen Nachrichten auf.",
            parse = {},
            execute = { executeGetChats() },
        ),
        ToolEntry(
            name = "get_messages",
            description = "Holt die letzten Nachrichten aus einem bestimmten Chat. Der chat_name muss möglichst exakt dem Chat-Namen entsprechen.",
            params = listOf(
                ToolParam("chat_name", "string", "Der genaue Name des Chats"),
                ToolParam("limit", "int", "Anzahl der Nachrichten (max 50)", required = false, default = "20"),
            ),
            parse = { p -> GetMessagesParams(p["chat_name"] ?: return@ToolEntry null, p["limit"]?.toIntOrNull() ?: 20) },
            execute = { args ->
                val p = args as? GetMessagesParams ?: return@ToolEntry ToolResult("get_messages", "Fehler: Ungültige Parameter")
                executeGetMessages(p.chatName, p.limit)
            },
        ),
        ToolEntry(
            name = "get_contacts",
            description = "Listet alle gespeicherten Kontakte auf.",
            parse = {},
            execute = { executeGetContacts() },
        ),
        ToolEntry(
            name = "search_messages",
            description = "Durchsucht alle Nachrichten mit einer Text-Suchanfrage.",
            params = listOf(
                ToolParam("query", "string", "Der Suchbegriff"),
                ToolParam("limit", "int", "Maximale Anzahl Ergebnisse (max 50)", required = false, default = "20"),
            ),
            parse = { p -> SearchMessagesParams(p["query"] ?: return@ToolEntry null, p["limit"]?.toIntOrNull() ?: 20) },
            execute = { args ->
                val p = args as? SearchMessagesParams ?: return@ToolEntry ToolResult("search_messages", "Fehler: Ungültige Parameter")
                executeSearchMessages(p.query, p.limit)
            },
        ),
        ToolEntry(
            name = "get_settings",
            description = "Liest die aktuellen App-Einstellungen aus (key=value Paare mit englischen snake_case-Namen).",
            parse = {},
            execute = { executeGetSettings() },
        ),
        ToolEntry(
            name = "get_conversation_stats",
            description = "Analysiert die Nachrichtenstatistiken aller Chats (Nachrichtenanzahl, aktivste Chats).",
            parse = {},
            execute = { executeGetConversationStats() },
        ),
        ToolEntry(
            name = "get_current_time",
            description = "Gibt das aktuelle Datum und die aktuelle Uhrzeit zurück.",
            parse = {},
            execute = { executeGetCurrentTime() },
        ),
        ToolEntry(
            name = "get_contact_detail",
            description = "Holt detaillierte Informationen zu einem bestimmten Kontakt (Name, Telefon, E-Mail, Notiz).",
            params = listOf(
                ToolParam("name", "string", "Der Name des Kontakts"),
            ),
            parse = { p -> ContactDetailParams(p["name"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? ContactDetailParams ?: return@ToolEntry ToolResult("get_contact_detail", "Fehler: Ungültige Parameter")
                executeGetContactDetail(p.name)
            },
        ),
        ToolEntry(
            name = "send_message",
            description = "Sendet eine Nachricht in einen bestehenden Chat.",
            params = listOf(
                ToolParam("chat_name", "string", "Der Name des Chats, in den gesendet werden soll"),
                ToolParam("text", "string", "Der Nachrichtentext"),
            ),
            parse = { p ->
                val chatName = p["chat_name"] ?: return@ToolEntry null
                val text = p["text"] ?: return@ToolEntry null
                SendMessageParams(chatName, text)
            },
            execute = { args ->
                val p = args as? SendMessageParams ?: return@ToolEntry ToolResult("send_message", "Fehler: Ungültige Parameter")
                executeSendMessage(p.chatName, p.text)
            },
        ),
        ToolEntry(
            name = "web_search",
            description = "Durchsucht das Internet mit einer Text-Suchanfrage und liefert Ergebnisse inklusive Quellenangabe.",
            params = listOf(
                ToolParam("query", "string", "Die Suchanfrage"),
            ),
            parse = { p -> WebSearchParams(p["query"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? WebSearchParams ?: return@ToolEntry ToolResult("web_search", "Fehler: Ungültige Parameter")
                executeWebSearch(p.query)
            },
        ),
        ToolEntry(
            name = "create_note",
            description = "Erstellt eine neue Notiz mit Titel und Inhalt. Notizen sind dauerhaft gespeichert und können später abgerufen werden.",
            params = listOf(
                ToolParam("title", "string", "Der Titel der Notiz"),
                ToolParam("content", "string", "Der Inhalt der Notiz"),
            ),
            parse = { p -> CreateNoteParams(p["title"] ?: return@ToolEntry null, p["content"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? CreateNoteParams ?: return@ToolEntry ToolResult("create_note", "Fehler: Ungültige Parameter")
                executeCreateNote(p.title, p.content)
            },
        ),
        ToolEntry(
            name = "get_notes",
            description = "Listet alle gespeicherten Notizen auf oder durchsucht sie mit einem Suchbegriff.",
            params = listOf(
                ToolParam("search", "string", "Suchbegriff zum Filtern (optional)", required = false, default = ""),
            ),
            parse = { p -> GetNotesParams(p["search"]?.takeIf { it.isNotBlank() }) },
            execute = { args ->
                val p = args as? GetNotesParams ?: return@ToolEntry ToolResult("get_notes", "Fehler: Ungültige Parameter")
                executeGetNotes(p.search)
            },
        ),
        ToolEntry(
            name = "create_reminder",
            description = "Erstellt eine Erinnerung mit Titel und Fälligkeitsdatum. Der Benutzer wird zum angegebenen Zeitpunkt benachrichtigt.",
            params = listOf(
                ToolParam("title", "string", "Der Titel der Erinnerung"),
                ToolParam("due", "string", "Fälligkeitsdatum im ISO-Format (z.B. 2026-06-15T14:30)"),
            ),
            parse = { p -> CreateReminderParams(p["title"] ?: return@ToolEntry null, p["due"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? CreateReminderParams ?: return@ToolEntry ToolResult("create_reminder", "Fehler: Ungültige Parameter")
                executeCreateReminder(p.title, p.due)
            },
        ),
        ToolEntry(
            name = "get_reminders",
            description = "Listet alle Erinnerungen auf, optional gefiltert nach Status (pending/completed/all).",
            params = listOf(
                ToolParam("status", "string", "Filter: 'pending' (ausstehend), 'completed' (erledigt) oder 'all' (alle)", required = false, default = "pending"),
            ),
            parse = { p -> GetRemindersParams(p["status"]?.takeIf { it.isNotBlank() } ?: "pending") },
            execute = { args ->
                val p = args as? GetRemindersParams ?: return@ToolEntry ToolResult("get_reminders", "Fehler: Ungültige Parameter")
                executeGetReminders(p.status)
            },
        ),
        ToolEntry(
            name = "complete_reminder",
            description = "Markiert eine Erinnerung als erledigt. Die reminder_id ist die ID der Erinnerung.",
            params = listOf(
                ToolParam("reminder_id", "string", "Die ID der zu erledigenden Erinnerung"),
            ),
            parse = { p -> ReminderIdParams(p["reminder_id"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? ReminderIdParams ?: return@ToolEntry ToolResult("complete_reminder", "Fehler: Ungültige Parameter")
                executeCompleteReminder(p.reminderId)
            },
        ),
        ToolEntry(
            name = "delete_reminder",
            description = "Löscht eine Erinnerung dauerhaft. Die reminder_id ist die ID der Erinnerung.",
            params = listOf(
                ToolParam("reminder_id", "string", "Die ID der zu löschenden Erinnerung"),
            ),
            parse = { p -> ReminderIdParams(p["reminder_id"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? ReminderIdParams ?: return@ToolEntry ToolResult("delete_reminder", "Fehler: Ungültige Parameter")
                executeDeleteReminder(p.reminderId)
            },
        ),
        ToolEntry(
            name = "remember_info",
            description = "Merkt sich eine Information (key=value) für zukünftige Unterhaltungen. Der Assistent kann diese Information später abrufen.",
            params = listOf(
                ToolParam("key", "string", "Der Name/Schlüssel der Information"),
                ToolParam("value", "string", "Der Wert der Information"),
            ),
            parse = { p -> RememberInfoParams(p["key"] ?: return@ToolEntry null, p["value"] ?: return@ToolEntry null) },
            execute = { args ->
                val p = args as? RememberInfoParams ?: return@ToolEntry ToolResult("remember_info", "Fehler: Ungültige Parameter")
                executeRememberInfo(p.key, p.value)
            },
        ),
        ToolEntry(
            name = "get_remembered_info",
            description = "Ruft gemerkte Informationen ab. Ohne Angabe eines Schlüssels werden alle gemerkten Informationen zurückgegeben.",
            params = listOf(
                ToolParam("key", "string", "Der Schlüssel der abzurufenden Information (optional)", required = false, default = ""),
            ),
            parse = { p -> GetRememberedInfoParams(p["key"]?.takeIf { it.isNotBlank() }) },
            execute = { args ->
                val p = args as? GetRememberedInfoParams ?: return@ToolEntry ToolResult("get_remembered_info", "Fehler: Ungültige Parameter")
                executeGetRememberedInfo(p.key)
            },
        ),
    )

    fun getToolDescriptionsXml(): String = buildString {
        appendLine("<tools>")
        for (tool in tools) {
            appendLine("  <tool name=\"${tool.name}\">")
            appendLine("    <description>${tool.description}</description>")
            if (tool.params.isEmpty()) {
                appendLine("    <parameters/>")
            } else {
                appendLine("    <parameters>")
                for (param in tool.params) {
                    val requiredAttr = if (param.required) "" else " required=\"false\""
                    val defaultAttr = if (param.default != null) " default=\"${param.default}\"" else ""
                    appendLine("      <param name=\"${param.name}\" type=\"${param.type}\"$requiredAttr$defaultAttr>${param.description}</param>")
                }
                appendLine("    </parameters>")
            }
            appendLine("  </tool>")
        }
        appendLine("</tools>")
    }

    fun findTool(name: String): ToolEntry? = tools.find { it.name == name }

    fun parseToolXml(name: String, paramsXml: String): Any? {
        val entry = findTool(name) ?: return null
        val matcher = toolParamRegex.matcher(paramsXml)
        val params = mutableMapOf<String, String>()
        while (matcher.find()) {
            params[matcher.group(1)] = matcher.group(2).trim()
        }
        return entry.parse(params)
    }

    private data class GetMessagesParams(val chatName: String, val limit: Int)
    private data class SearchMessagesParams(val query: String, val limit: Int)
    private data class ContactDetailParams(val name: String)
    private data class SendMessageParams(val chatName: String, val text: String)
    private data class WebSearchParams(val query: String)
    private data class CreateNoteParams(val title: String, val content: String)
    private data class GetNotesParams(val search: String?)
    private data class CreateReminderParams(val title: String, val due: String)
    private data class GetRemindersParams(val status: String)
    private data class ReminderIdParams(val reminderId: String)
    private data class RememberInfoParams(val key: String, val value: String)
    private data class GetRememberedInfoParams(val key: String?)
}

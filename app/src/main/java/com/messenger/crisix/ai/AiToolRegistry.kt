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
}

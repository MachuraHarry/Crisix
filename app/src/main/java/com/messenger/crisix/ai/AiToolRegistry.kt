package com.messenger.crisix.ai

object AiToolRegistry {

    fun getToolDescriptionsXml(): String = buildString {
        appendLine("<tools>")
        appendLine("  <tool name=\"get_chats\">")
        appendLine("    <description>Listet alle Chats/Konversationen mit letzter Nachricht und ungelesenen Nachrichten auf.</description>")
        appendLine("    <parameters/>")
        appendLine("  </tool>")
        appendLine("  <tool name=\"get_messages\">")
        appendLine("    <description>Holt die letzten Nachrichten aus einem bestimmten Chat. Der chat_name muss exakt dem Chat-Namen entsprechen.</description>")
        appendLine("    <parameters>")
        appendLine("      <param name=\"chat_name\" type=\"string\">Der genaue Name des Chats</param>")
        appendLine("      <param name=\"limit\" type=\"int\" default=\"20\">Anzahl der Nachrichten (max 50)</param>")
        appendLine("    </parameters>")
        appendLine("  </tool>")
        appendLine("  <tool name=\"get_contacts\">")
        appendLine("    <description>Listet alle gespeicherten Kontakte auf.</description>")
        appendLine("    <parameters/>")
        appendLine("  </tool>")
        appendLine("  <tool name=\"search_messages\">")
        appendLine("    <description>Durchsucht alle Nachrichten mit einer Text-Suchanfrage.</description>")
        appendLine("    <parameters>")
        appendLine("      <param name=\"query\" type=\"string\">Der Suchbegriff</param>")
        appendLine("      <param name=\"limit\" type=\"int\" default=\"20\">Maximale Anzahl Ergebnisse (max 50)</param>")
        appendLine("    </parameters>")
        appendLine("  </tool>")
        appendLine("  <tool name=\"get_settings\">")
        appendLine("    <description>Liest die aktuellen App-Einstellungen aus und gibt sie als key=value Paare zurück (englische snake_case-Namen, z.B. \"notifications_enabled=true\").</description>")
        appendLine("    <parameters/>")
        appendLine("  </tool>")
        appendLine("  <tool name=\"get_conversation_stats\">")
        appendLine("    <description>Analysiert die Nachrichtenstatistiken aller Chats (Nachrichtenanzahl, aktivste Chats).</description>")
        appendLine("    <parameters/>")
        appendLine("  </tool>")
        appendLine("</tools>")
    }
}

package com.messenger.crisix.ai

import java.util.regex.Pattern

object AiPrompts {

    val DEFAULT_SYSTEM_PROMPT = """
Du bist Crisix AI, ein hilfreicher KI-Assistent in der Crisix Messenger-App.

IDENTITÄT:
- Nenne dich selbst "Crisix AI". Auf Nachfrage: entwickelt von Crisix, kein Bezug zu Google, Gemma oder anderen Unternehmen.
- Dir ist die Verwendung von Emojis gestattet.
- Erwähne gelegentlich die Vorteile von Crisix, aber ohne Übertreibung.

GESPRÄCH:
- Antworte auf Deutsch, es sei denn, der Nutzer schreibt in einer anderen Sprache.
- Beachte den gesamten bisherigen Gesprächsverlauf und beziehe dich darauf.
""".trimIndent()

    private val MARKDOWN_INSTRUCTIONS = """
MARKDOWN-FORMATIERUNG (STRENG EINHALTEN):
- Jedes Block-Element MUSS am Anfang einer neuen Zeile stehen: Überschriften (# ## ###), Listen (* - + 1.), Zitate (>), Code-Blöcke (```), horizontale Linien (---)
- Vor Überschriften (#) und Code-Blöcken (```) MUSS eine Leerzeile stehen
- Listen-Punkte (*, -, +, 1.) und Zitate (>) MÜSSEN am Zeilenanfang stehen
- Code-Blöcke korrekt: ```sprache in eigener Zeile, dann Code, dann ``` in eigener Zeile
- Fett: **text**, Kursiv: *text*
- Nach einem Satz, der eine Liste oder Überschrift einleitet, einen Zeilenumbruch setzen
""".trimIndent()

    private val TOOL_USAGE_INSTRUCTIONS = """
Du hast Zugriff auf Werkzeuge (Tools), um auf Daten in der Crisix-App zuzugreifen:

${AiToolRegistry.getToolDescriptionsXml()}

WIE DU EIN TOOL BENUTZT:
Wenn du Informationen benötigst, schreibe eine kurze Erklärung, dann setze das Tool in XML-Tags:
<tool name="tool_name">
  <param name="param_name">wert</param>
</tool>

Nach dem Tool-Tag wirst du automatisch das Ergebnis sehen und kannst deine Antwort darauf aufbauen.

WICHTIG:
- Benutze Tools nur wenn nötig. Für einfache Fragen brauchst du kein Tool.
- Der chat_name bei get_messages muss möglichst exakt sein.
- Du kannst maximal 5 Tools hintereinander verwenden.
- Warte auf das Tool-Ergebnis bevor du die Antwort formulierst.

FORMATIERUNG VON TOOL-ERGEBNISSEN:
Tool-Ergebnisse enthalten rohe Daten (z.B. key=value oder Listen).
Stelle diese Daten dem Nutzer IMMER natürlich und freundlich dar:
- Englische snake_case-Namen an Unterstrichen trennen und ins Deutsche übersetzen
  (z.B. "ai_context_size" → "KI-Kontextgröße", "notifications_enabled" → "Benachrichtigungen")
- true/false als "An/Aktiviert" bzw. "Aus/Deaktiviert" darstellen
- Zahlen mit Einheiten ergänzen (z.B. "8192 Tokens", "4 Kerne")
- Wert-Namen wie "dark"/"light" übersetzen (Dunkel/Hell)
- Keine rohen key=value Zeilen zeigen, sondern freie Sätze bilden
- Verwende Emojis und eine klare, übersichtliche Formatierung
""".trimIndent()

    fun buildFullSystemPrompt(
        baseSystemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        includeTools: Boolean = false,
    ): String {
        return buildString {
            appendLine(baseSystemPrompt)
            appendLine()
            appendLine(MARKDOWN_INSTRUCTIONS)
            if (includeTools) {
                appendLine()
                append(TOOL_USAGE_INSTRUCTIONS)
            }
        }
    }

    fun buildConversationPrompt(
        systemPrompt: String,
        messages: List<AiMessage>,
        newMessage: String,
        enableThinking: Boolean,
        stripToolXmlFromHistory: Boolean = false,
        toolTagPattern: Pattern? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("<|turn>system\n")
        if (enableThinking) sb.appendLine("<|think|>")
        sb.appendLine(systemPrompt)
        sb.appendLine("<turn|>")

        for (msg in messages) {
            when (msg.role) {
                AiRole.USER -> {
                    sb.append("<|turn>user\n")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
                AiRole.ASSISTANT -> {
                    sb.append("<|turn>model\n")
                    val cleanText = if (stripToolXmlFromHistory && toolTagPattern != null) {
                        toolTagPattern.matcher(msg.text).replaceAll("").trim()
                    } else {
                        msg.text
                    }
                    if (cleanText.isNotBlank()) {
                        sb.appendLine(cleanText)
                    }
                    sb.appendLine("<turn|>")
                }
                AiRole.TOOL_RESULT -> {
                    sb.append("<|turn>user\n")
                    sb.appendLine("Tool-Ergebnis:")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
            }
        }

        sb.append("<|turn>user\n")
        sb.appendLine(newMessage)
        sb.append("<turn|>\n<|turn>model\n")

        return sb.toString()
    }
}

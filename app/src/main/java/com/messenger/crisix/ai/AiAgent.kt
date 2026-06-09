package com.messenger.crisix.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class AiAgent(
    private val controller: AiInferenceController,
    private val modelManager: AiModelManager,
    private val toolExecutor: AiToolExecutor,
) {
    private val toolTagPattern = Pattern.compile(
        "<tool\\s+name=\"([^\"]+)\">(.*?)</tool>",
        Pattern.DOTALL
    )

    fun generateResponse(
        messages: List<AiMessage>,
        newMessage: String,
        onToolStatus: (String) -> Unit = {},
    ): Flow<String> = callbackFlow {
        var currentMessages = messages
        var currentInput = newMessage
        var cycleCount = 0
        val maxCycles = 5

        while (cycleCount < maxCycles) {
            cycleCount++
            val prompt = buildAgentPrompt(currentMessages, currentInput)
            val fullResponse = StringBuilder()
            var sentLen = 0
            var toolXml: String? = null
            var toolDetected = false

            controller.predict(
                prompt = prompt,
                onToken = { token ->
                    Log.d("AiAgent", "token: [$token]")
                    fullResponse.append(token)

                    val full = fullResponse.toString()
                    val openIdx = full.indexOf("<tool ")

                    val display = if (openIdx >= 0) {
                        val closeIdx = full.indexOf("</tool>", openIdx)
                        if (closeIdx >= 0) {
                            toolTagPattern.matcher(full).replaceAll("")
                        } else {
                            full.substring(0, openIdx)
                        }
                    } else {
                        full
                    }

                    if (display.length > sentLen) {
                        val delta = display.substring(sentLen)
                        sentLen = display.length
                        if (delta.isNotBlank()) {
                            trySend(delta)
                        }
                    }
                },
                onDone = {
                    val responseText = fullResponse.toString()
                    Log.d("AiAgent", "Full response: [$responseText]")
                    val toolMatch = toolTagPattern.matcher(responseText)
                    if (toolMatch.find()) {
                        toolXml = toolMatch.group(0)
                        toolDetected = true
                    }
                },
                onError = { err ->
                    close(Exception(err))
                },
            )

            if (!toolDetected || toolXml == null) break

            val toolInfo = parseToolXml(toolXml!!) ?: break
            onToolStatus(toolInfo.second)
            Log.i("AiAgent", "Executing tool: ${toolInfo.first}")

            val toolInstance = createToolInstance(toolInfo)
            if (toolInstance == null) {
                Log.w("AiAgent", "Unknown tool: ${toolInfo.first}")
                break
            }

            val result = toolExecutor.execute(toolInstance)

            val toolCallStart = fullResponse.indexOf(toolXml!!)
            val preToolText = if (toolCallStart > 0) fullResponse.substring(0, toolCallStart) else ""

            val historyMessage = AiMessage(
                id = "agent-cycle-$cycleCount",
                role = AiRole.ASSISTANT,
                text = preToolText,
            )

            val toolResultMessage = AiMessage(
                id = "tool-result-${result.toolName}-$cycleCount",
                role = AiRole.TOOL_RESULT,
                text = "${result.toolName}: ${result.summary}",
            )

            currentMessages = currentMessages + historyMessage + toolResultMessage
            currentInput = "Fortsetzung basierend auf Tool-Ergebnis oben."
        }

        close()
        awaitClose { controller.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun parseToolXml(xml: String): Pair<String, String>? {
        try {
            val nameMatch = Regex("name=\"([^\"]+)\"").find(xml)
            val name = nameMatch?.groupValues?.get(1) ?: return null

            val params = xml.substringAfter(">").substringBeforeLast("</tool>").trim()
            return name to params
        } catch (e: Exception) {
            return null
        }
    }

    private fun createToolInstance(info: Pair<String, String>): AiTool? {
        val (name, paramsXml) = info
        return when (name) {
            "get_chats" -> AiTool.GetChats
            "get_contacts" -> AiTool.GetContacts
            "get_settings" -> AiTool.GetSettings
            "get_conversation_stats" -> AiTool.GetConversationStats
            "search_messages" -> {
                val query = extractParam(paramsXml, "query") ?: return null
                val limit = extractParam(paramsXml, "limit")?.toIntOrNull() ?: 20
                AiTool.SearchMessages(query, limit)
            }
            "get_messages" -> {
                val chatName = extractParam(paramsXml, "chat_name") ?: return null
                val limit = extractParam(paramsXml, "limit")?.toIntOrNull() ?: 20
                AiTool.GetMessages(chatName, limit)
            }
            else -> null
        }
    }

    private fun extractParam(xml: String, paramName: String): String? {
        val regex = Regex("<param\\s+name=\"$paramName\">(.*?)</param>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    private suspend fun buildAgentPrompt(
        messages: List<AiMessage>,
        newMessage: String,
    ): String {
        val systemPrompt = modelManager.getSavedSystemPrompt()
        val toolsXml = AiToolRegistry.getToolDescriptionsXml()

        val fullSystemPrompt = """
$systemPrompt

Du hast Zugriff auf Werkzeuge (Tools), um auf Daten in der Crisix-App zuzugreifen:

$toolsXml

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

        val maxContextSize = modelManager.getSavedContextSize()
        val truncated = AiPromptTruncator.truncateMessages(
            messages = messages,
            systemPrompt = fullSystemPrompt,
            newMessage = newMessage,
            maxContextSize = maxContextSize,
        )

        val sb = StringBuilder()
        var isFirstUser = true

        for (msg in truncated) {
            when (msg.role) {
                AiRole.USER -> {
                    sb.append("<start_of_turn>user\n")
                    if (isFirstUser) {
                        sb.appendLine(fullSystemPrompt)
                        sb.appendLine()
                        isFirstUser = false
                    }
                    sb.appendLine(msg.text)
                    sb.appendLine("<end_of_turn>")
                }
                AiRole.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n")
                    val cleanText = toolTagPattern.matcher(msg.text).replaceAll("").trim()
                    if (cleanText.isNotBlank()) {
                        sb.appendLine(cleanText)
                    }
                    sb.appendLine("<end_of_turn>")
                }
                AiRole.TOOL_RESULT -> {
                    sb.append("<start_of_turn>user\n")
                    sb.appendLine("Tool-Ergebnis:")
                    sb.appendLine(msg.text)
                    sb.appendLine("<end_of_turn>")
                }
            }
        }

        sb.append("<start_of_turn>user\n")
        if (isFirstUser) {
            sb.appendLine(fullSystemPrompt)
            sb.appendLine()
        }
        sb.appendLine(newMessage)
        sb.append("<end_of_turn>\n<start_of_turn>model\n")

        return sb.toString()
    }
}

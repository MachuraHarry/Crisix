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
    ): Flow<AiStreamChunk> = callbackFlow {
        var currentMessages = messages
        var currentInput = newMessage
        var cycleCount = 0
        val maxCycles = 5

        // Only use session for continuing conversations, not fresh ones
        if (messages.isEmpty()) {
            modelManager.clearSessionState()
        } else {
            modelManager.loadSessionState()
        }

        while (cycleCount < maxCycles) {
            cycleCount++
            val prompt = buildAgentPrompt(currentMessages, currentInput)
            val fullResponse = StringBuilder()
            var sentTextLen = 0
            var sentThinkLen = 0
            var toolXml: String? = null
            var toolDetected = false

            controller.predict(
                prompt = prompt,
                onToken = { token ->
                    Log.d("AiAgent", "token: [$token]")
                    fullResponse.append(token)

                    val full = fullResponse.toString()

                    // Parse Gemma 4 thinking channel
                    val thinkIdx = full.indexOf("<|channel>thought\n")
                    val (thinking, displayText) = if (thinkIdx >= 0) {
                        val endIdx = full.indexOf("<channel|>", thinkIdx)
                        if (endIdx >= 0) {
                            val thinkStart = thinkIdx + "<|channel>thought\n".length
                            val thinkContent = full.substring(thinkStart, endIdx)
                            val afterThink = full.substring(endIdx + "<channel|>".length)
                            thinkContent to afterThink
                        } else {
                            val thinkStart = thinkIdx + "<|channel>thought\n".length
                            val thinkContent = full.substring(thinkStart)
                            thinkContent to ""
                        }
                    } else {
                        // Check for partial thinking prefix at end (before \n arrives)
                        val partialIdx = indexOfPartialThinkStart(full)
                        if (partialIdx >= 0) {
                            "" to full.substring(0, partialIdx)
                        } else {
                            "" to full
                        }
                    }

                    // Filter tool XML from display
                    val openIdx = displayText.indexOf("<tool ")
                    val cleanDisplay = if (openIdx >= 0) {
                        val closeIdx = displayText.indexOf("</tool>", openIdx)
                        if (closeIdx >= 0) {
                            toolTagPattern.matcher(displayText).replaceAll("")
                        } else {
                            displayText.substring(0, openIdx)
                        }
                    } else {
                        displayText
                    }

                    // Emit thinking delta
                    if (thinking.length > sentThinkLen) {
                        val delta = thinking.substring(sentThinkLen)
                        sentThinkLen = thinking.length
                        if (delta.isNotBlank()) {
                            trySend(AiStreamChunk(thinking = delta))
                        }
                    }

                    // Emit text delta
                    if (cleanDisplay.length > sentTextLen) {
                        val delta = cleanDisplay.substring(sentTextLen)
                        sentTextLen = cleanDisplay.length
                        if (delta.isNotBlank()) {
                            trySend(AiStreamChunk(text = delta))
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

            // Parse thinking from the response for history
            val (thinkingText, cleanResponse) = parseThinking(fullResponse.toString())

            val historyMessage = AiMessage(
                id = "agent-cycle-$cycleCount",
                role = AiRole.ASSISTANT,
                text = cleanResponse,
                thinking = thinkingText,
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

    private fun parseThinking(full: String): Pair<String?, String> {
        val thinkIdx = full.indexOf("<|channel>thought\n")
        if (thinkIdx < 0) return null to full
        val endIdx = full.indexOf("<channel|>", thinkIdx)
        val thinkContent = if (endIdx >= 0) {
            full.substring(thinkIdx + "<|channel>thought\n".length, endIdx)
        } else {
            full.substring(thinkIdx + "<|channel>thought\n".length)
        }
        val cleanText = if (endIdx >= 0) {
            full.replaceRange(thinkIdx, endIdx + "<channel|>".length, "")
        } else {
            full.substring(0, thinkIdx)
        }
        return (thinkContent.takeIf { it.isNotBlank() }) to cleanText.trim()
    }

    private fun indexOfPartialThinkStart(text: String): Int {
        val prefixes = listOf("<|channel>thought", "<|channel>thoug", "<|channel>thou",
            "<|channel>tho", "<|channel>th", "<|channel>t", "<|channel>", "<|channel",
            "<|channe", "<|chann", "<|chan", "<|cha", "<|ch", "<|c", "<|")
        for (p in prefixes) {
            if (text.endsWith(p)) return text.length - p.length
        }
        return -1
    }

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
        // If KV-cache session is active and no tool results pending, only send continuation
        val hasToolResults = messages.any { it.role == AiRole.TOOL_RESULT }
        if (modelManager.isSessionActive && !hasToolResults) {
            val sb = StringBuilder()
            // System reminder: KV-cache doesn't reliably preserve full system prompt on this model
            sb.append("<|turn>system\n")
            sb.appendLine("<|think|>")
            sb.appendLine("Du bist Crisix AI, der KI-Assistent der Crisix Messenger-App. Antworte immer auf Deutsch, es sei denn der Nutzer spricht dich auf einer anderen Sprache an. Nutze Markdown-Formatierung und Emojis. Erwähne gelegentlich wie nützlich Crisix ist.")
            sb.appendLine("<turn|>")
            sb.append("<|turn>user\n")
            sb.appendLine(newMessage)
            sb.append("<turn|>\n<|turn>model\n")
            return sb.toString()
        }

        val systemPrompt = modelManager.getSavedSystemPrompt()
        val toolsXml = AiToolRegistry.getToolDescriptionsXml()

        val fullSystemPrompt = """
$systemPrompt

MARKDOWN-FORMATIERUNG (STRENG EINHALTEN):
- Jedes Block-Element MUSS am Anfang einer neuen Zeile stehen: Überschriften (# ## ###), Listen (* - + 1.), Zitate (>), Code-Blöcke (```), horizontale Linien (---)
- Vor Überschriften (#) und Code-Blöcken (```) MUSS eine Leerzeile stehen
- Listen-Punkte (*, -, +, 1.) und Zitate (>) MÜSSEN am Zeilenanfang stehen
- Code-Blöcke korrekt: ```sprache in eigener Zeile, dann der Code, dann ``` in eigener Zeile
- Fett: **text**, Kursiv: *text*
- Nach einem Satz, der eine Liste oder Überschrift einleitet, einen Zeilenumbruch setzen

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
            tokenCounter = modelManager::countTokens,
        )

        val sb = StringBuilder()

        // Gemma 4: system prompt in native system role with think token
        sb.append("<|turn>system\n")
        sb.appendLine("<|think|>")
        sb.appendLine(fullSystemPrompt)
        sb.appendLine("<turn|>")

        for (msg in truncated) {
            when (msg.role) {
                AiRole.USER -> {
                    sb.append("<|turn>user\n")
                    sb.appendLine(msg.text)
                    sb.appendLine("<turn|>")
                }
                AiRole.ASSISTANT -> {
                    sb.append("<|turn>model\n")
                    val cleanText = toolTagPattern.matcher(msg.text).replaceAll("").trim()
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

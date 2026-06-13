package com.messenger.crisix.ai

import android.util.Log
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.util.regex.Pattern

data class ToolConfirmInfo(
    val toolName: String,
    val params: String,
)

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
        onToolConfirm: suspend (String, String) -> Boolean = { _, _ -> true },
    ): Flow<AiStreamChunk> = callbackFlow {
        var currentMessages = messages
        var currentInput = newMessage
        var cycleCount = 0
        val maxCycles = 5
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 2

        while (cycleCount < maxCycles && consecutiveErrors < maxConsecutiveErrors) {
            cycleCount++
            val enableThinking = modelManager.getSavedThinkingEnabled()
            val prompt = buildAgentPrompt(currentMessages, currentInput, enableThinking)
            val fullResponse = StringBuilder()
            var sentTextLen = 0
            var sentThinkLen = 0
            var toolXml: String? = null
            var toolDetected = false

            controller.predict(
                prompt = prompt,
                enableThinking = enableThinking,
                onToken = { token ->
                    fullResponse.append(token)
                    val full = fullResponse.toString()

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
                        val partialIdx = indexOfPartialThinkStart(full)
                        if (partialIdx >= 0) {
                            "" to full.substring(0, partialIdx)
                        } else {
                            "" to full
                        }
                    }

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

                    if (thinking.length > sentThinkLen) {
                        val delta = thinking.substring(sentThinkLen)
                        sentThinkLen = thinking.length
                        if (delta.isNotBlank()) {
                            trySend(AiStreamChunk(thinking = delta))
                        }
                    }

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

            if (!toolDetected || toolXml == null) {
                consecutiveErrors = 0
                break
            }

            val toolInfo = parseToolXml(toolXml!!) ?: run {
                consecutiveErrors++
                Log.w("AiAgent", "Failed to parse tool XML, attempt $consecutiveErrors/$maxConsecutiveErrors")
                currentInput = "Das Tool-XML konnte nicht geparst werden. Bitte verwende das korrekte Format: <tool name=\"tool_name\">...</tool>"
                continue
            }

            val entry = AiToolRegistry.findTool(toolInfo.first)
            if (entry == null) {
                consecutiveErrors++
                Log.w("AiAgent", "Unknown tool: ${toolInfo.first}, attempt $consecutiveErrors/$maxConsecutiveErrors")
                currentInput = "Das Tool '${toolInfo.first}' ist nicht bekannt. Verfügbare Tools: ${AiToolRegistry.getToolDescriptionsXml()}"
                continue
            }

            onToolStatus(toolInfo.second)
            Log.i("AiAgent", "Awaiting user confirmation for tool: ${toolInfo.first}")

            val confirmed = onToolConfirm(toolInfo.first, toolInfo.second)
            if (!confirmed) {
                Log.i("AiAgent", "Tool cancelled by user: ${toolInfo.first}")
                consecutiveErrors = 0
                currentInput = "Die Ausführung des Tools '${toolInfo.first}' wurde vom Benutzer abgebrochen."
                continue
            }

            Log.i("AiAgent", "Executing tool: ${toolInfo.first}")

            val toolArgs = AiToolRegistry.parseToolXml(toolInfo.first, toolInfo.second)
            if (toolArgs == null && entry.params.isNotEmpty()) {
                consecutiveErrors++
                currentInput = "Das Tool '${toolInfo.first}' benötigt gültige Parameter: ${entry.params.joinToString { "${it.name} (${it.type})" }}"
                continue
            }

            val result = toolExecutor.execute(entry, toolArgs)
            Log.i("AiAgent", "Tool result: ${result.summary.take(200)}")

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
            consecutiveErrors = 0
        }

        if (consecutiveErrors >= maxConsecutiveErrors) {
            trySend(AiStreamChunk(text = "\n\n[Agent-Modus: Nach $maxConsecutiveErrors Fehlversuchen wurde auf einfachen Chat-Modus umgeschaltet.]"))
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
        val prefixes = listOf(
            "<|channel>thought", "<|channel>thoug", "<|channel>thou",
            "<|channel>tho", "<|channel>th", "<|channel>t", "<|channel>", "<|channel",
            "<|channe", "<|chann", "<|chan", "<|cha", "<|ch", "<|c", "<|"
        )
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

    private suspend fun buildAgentPrompt(
        messages: List<AiMessage>,
        newMessage: String,
        enableThinking: Boolean,
    ): String {
        val baseSystemPrompt = modelManager.getSavedSystemPrompt()

        val rememberedInfo = buildRememberedInfoContext()
        val fullSystemPrompt = AiPrompts.buildFullSystemPrompt(baseSystemPrompt, includeTools = true, rememberedInfo = rememberedInfo)

        val maxContextSize = modelManager.getSavedContextSize()
        val truncated = AiPromptTruncator.truncateMessages(
            messages = messages,
            systemPrompt = fullSystemPrompt,
            newMessage = newMessage,
            maxContextSize = maxContextSize,
            tokenCounter = modelManager::countTokens,
        )

        return AiPrompts.buildConversationPrompt(
            systemPrompt = fullSystemPrompt,
            messages = truncated,
            newMessage = newMessage,
            enableThinking = enableThinking,
            stripToolXmlFromHistory = true,
            toolTagPattern = toolTagPattern,
        )
    }

    private suspend fun buildRememberedInfoContext(): String? {
        return try {
            val ctx = modelManager.getAppContext()
            val prefs = ctx.settingsDataStore.data.first()
            val lines = mutableListOf<String>()
            for ((k, v) in prefs.asMap()) {
                val name = k.name
                if (name.startsWith("ai_remembered_")) {
                    val infoKey = name.removePrefix("ai_remembered_")
                    lines.add("- $infoKey: $v")
                }
            }
            if (lines.isEmpty()) null else lines.joinToString("\n")
        } catch (_: Exception) { null }
    }
}

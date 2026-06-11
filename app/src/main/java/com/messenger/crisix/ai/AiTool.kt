package com.messenger.crisix.ai

data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val default: String? = null,
)

data class ToolEntry(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val parse: (Map<String, String>) -> Any?,
    val execute: suspend AiToolExecutor.(Any?) -> ToolResult,
)

data class ToolResult(
    val toolName: String,
    val summary: String,
)

package com.messenger.crisix.ai

sealed class AiTool {
    data object GetChats : AiTool()

    data class GetMessages(
        val chatName: String,
        val limit: Int = 20,
    ) : AiTool()

    data object GetContacts : AiTool()

    data class SearchMessages(
        val query: String,
        val limit: Int = 20,
    ) : AiTool()

    data object GetSettings : AiTool()

    data object GetConversationStats : AiTool()
}

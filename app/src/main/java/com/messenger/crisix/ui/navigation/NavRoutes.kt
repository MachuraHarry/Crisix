package com.messenger.crisix.ui.navigation

/**
 * Navigationsrouten für Crisix.
 */
object NavRoutes {
    const val CHAT_LIST = "chat_list"
    const val CHAT_DETAIL = "chat_detail/{chatId}/{chatName}"

    fun chatDetail(chatId: String, chatName: String): String {
        return "chat_detail/$chatId/$chatName"
    }
}

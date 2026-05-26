package com.messenger.crisix.ui.navigation

/**
 * Navigationsrouten für Crisix.
 */
object NavRoutes {
    const val CHAT_LIST = "chat_list"
    const val CHAT_DETAIL = "chat_detail/{chatId}/{chatName}"
    const val SETTINGS = "settings"
    const val MY_ID = "my_id"
    const val ADD_CONTACT = "add_contact"
    const val CONNECTIONS = "connections"
    const val QR_SCANNER = "qr_scanner"

    fun chatDetail(chatId: String, chatName: String): String {
        return "chat_detail/$chatId/$chatName"
    }
}

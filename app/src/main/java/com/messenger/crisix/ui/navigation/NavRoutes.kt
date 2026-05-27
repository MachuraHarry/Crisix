package com.messenger.crisix.ui.navigation

/**
 * Navigationsrouten für Crisix.
 */
object NavRoutes {
    const val ONBOARDING = "onboarding"
    const val TRANSPORT_SETUP = "transport_setup"
    const val CHAT_LIST = "chat_list"
    const val CHAT_DETAIL = "chat_detail/{chatId}/{chatName}"
    const val SETTINGS = "settings"
    const val MY_ID = "my_id"
    const val ADD_CONTACT = "add_contact"
    const val CONNECTIONS = "connections"
    const val QR_SCANNER = "qr_scanner"
    const val LOG_VIEWER = "log_viewer"
    const val CONTACT_LIST = "contact_list"
    const val CONTACT_DETAIL = "contact_detail/{contactId}"

    fun chatDetail(chatId: String, chatName: String): String {
        return "chat_detail/$chatId/$chatName"
    }

    fun contactDetail(contactId: String): String {
        return "contact_detail/$contactId"
    }
}

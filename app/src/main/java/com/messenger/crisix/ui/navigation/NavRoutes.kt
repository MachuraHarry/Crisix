package com.messenger.crisix.ui.navigation

/**
 * Navigationsrouten für Crisix.
 */
object NavRoutes {
    const val ONBOARDING = "onboarding"
    const val TRANSPORT_SETUP = "transport_setup"
    const val PERMISSION_SETUP = "permission_setup"
    const val CHAT_LIST = "chat_list"
    const val AI_CHAT = "ai_chat"
    const val AI_CHAT_DETAIL = "ai_chat_detail/{conversationId}"
    const val CHAT_DETAIL = "chat_detail/{chatId}/{chatName}"
    const val SETTINGS = "settings"
    const val MY_ID = "my_id"
    const val ADD_CONTACT = "add_contact"
    const val CONNECTIONS = "connections"
    const val QR_SCANNER = "qr_scanner"
    const val LOG_VIEWER = "log_viewer"
    const val CONTACT_LIST = "contact_list"
    const val CONTACT_DETAIL = "contact_detail/{contactId}"

    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_PRIVACY = "settings_privacy"
    const val SETTINGS_CHAT = "settings_chat"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_ACCESSIBILITY = "settings_accessibility"
    const val SETTINGS_INFO = "settings_info"
    const val SETTINGS_TRANSPORT_PRIORITY = "settings_transport_priority"
    const val SETTINGS_RELAY_SERVERS = "settings_relay_servers"

    fun aiChatDetail(conversationId: String): String {
        return "ai_chat_detail/$conversationId"
    }

    fun chatDetail(chatId: String, chatName: String): String {
        return "chat_detail/$chatId/$chatName"
    }

    fun contactDetail(contactId: String): String {
        return "contact_detail/$contactId"
    }
}

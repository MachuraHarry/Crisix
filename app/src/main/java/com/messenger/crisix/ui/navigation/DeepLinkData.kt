package com.messenger.crisix.ui.navigation

data class DeepLinkData(
    val type: Type,
    val peerId: String?,
    val peerName: String,
    val ipAddress: String? = null,
    val port: Int? = null,
    val handshakeBundleB64: String? = null,
) {
    enum class Type { CONTACT, HANDSHAKE }
}

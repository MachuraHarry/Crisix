package com.messenger.crisix.ui.state

import com.messenger.crisix.crypto.HandshakeInitData
import com.messenger.crisix.data.Contact
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.Transport
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.screens.UserProfile

data class CrisixAppState(
    val allMessages: Map<String, List<Message>> = emptyMap(),
    val currentMessages: List<Message> = emptyList(),
    val currentChatPeerId: String = "",
    val discoveredPeers: List<Peer> = emptyList(),
    val connectionStatuses: Map<TransportType, ConnectionStatus> = emptyMap(),
    val activeTransport: Transport? = null,
    val incomingNames: Map<String, String> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val savedContacts: List<Contact> = emptyList(),
    val e2eeSessions: Map<String, Boolean> = emptyMap(),
    val pendingHandshakes: Map<String, HandshakeInitData> = emptyMap(),
    val transportSettings: Map<TransportType, Boolean> = emptyMap(),
    val userProfile: UserProfile = UserProfile(),
    val isSetupComplete: Boolean = false,
    val deviceId: String = "",
    val pinnedChatIds: Set<String> = emptySet(),
)

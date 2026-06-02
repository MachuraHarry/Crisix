package com.messenger.crisix.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.messenger.crisix.crypto.HandshakeInitData
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.Transport
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.screens.UserProfile
import com.messenger.crisix.ui.state.CrisixAppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CrisixViewModel(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(CrisixAppState())
    val state: StateFlow<CrisixAppState> = _state.asStateFlow()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("crisix_setup", Context.MODE_PRIVATE)

    fun updateMessages(peerId: String, messages: List<Message>) {
        _state.update { current ->
            current.copy(
                allMessages = current.allMessages + (peerId to messages),
                currentMessages = if (current.currentChatPeerId == peerId) messages else current.currentMessages,
            )
        }
    }

    fun addMessage(peerId: String, message: Message) {
        _state.update { current ->
            val updatedList = (current.allMessages[peerId] ?: emptyList()) + message
            current.copy(
                allMessages = current.allMessages + (peerId to updatedList),
                currentMessages = if (current.currentChatPeerId == peerId) updatedList else current.currentMessages,
            )
        }
    }

    fun removeMessage(messageId: String) {
        _state.update { current ->
            current.copy(
                currentMessages = current.currentMessages.filter { it.id != messageId },
            )
        }
    }

    fun setCurrentChat(peerId: String) {
        _state.update { current ->
            current.copy(
                currentChatPeerId = peerId,
                currentMessages = current.allMessages[peerId] ?: emptyList(),
            )
        }
    }

    fun updateDiscoveredPeers(peers: List<Peer>) {
        _state.update { it.copy(discoveredPeers = peers) }
    }

    fun updateConnectionStatuses(statuses: Map<TransportType, ConnectionStatus>) {
        _state.update { it.copy(connectionStatuses = statuses) }
    }

    fun setActiveTransport(transport: Transport?) {
        _state.update { it.copy(activeTransport = transport) }
    }

    fun updateIncomingName(peerId: String, name: String) {
        _state.update { it.copy(incomingNames = it.incomingNames + (peerId to name)) }
    }

    fun updateUnreadCounts(counts: Map<String, Int>) {
        _state.update { it.copy(unreadCounts = counts) }
    }

    fun setUnreadCount(peerId: String, count: Int) {
        _state.update { it.copy(unreadCounts = it.unreadCounts + (peerId to count)) }
    }

    fun updateSavedContacts(contacts: List<Contact>) {
        _state.update { it.copy(savedContacts = contacts) }
    }

    fun updateE2eeSession(peerId: String, hasSession: Boolean) {
        _state.update { it.copy(e2eeSessions = it.e2eeSessions + (peerId to hasSession)) }
    }

    fun addPendingHandshake(peerId: String, data: HandshakeInitData) {
        _state.update { it.copy(pendingHandshakes = it.pendingHandshakes + (peerId to data)) }
    }

    fun removePendingHandshake(peerId: String) {
        _state.update { it.copy(pendingHandshakes = it.pendingHandshakes - peerId) }
    }

    fun togglePinChat(chatId: String) {
        _state.update { current ->
            val updated = current.pinnedChatIds.toMutableSet()
            if (updated.contains(chatId)) updated.remove(chatId) else updated.add(chatId)
            current.copy(pinnedChatIds = updated)
        }
    }

    fun setPinnedChats(ids: Set<String>) {
        _state.update { it.copy(pinnedChatIds = ids) }
    }

    fun deleteChat(chatId: String) {
        _state.update { current ->
            current.copy(
                allMessages = current.allMessages - chatId,
                pinnedChatIds = current.pinnedChatIds - chatId,
                unreadCounts = current.unreadCounts - chatId,
            )
        }
    }

    fun updateTransportSettings(settings: Map<TransportType, Boolean>) {
        _state.update { it.copy(transportSettings = settings) }
    }

    fun updateUserProfile(profile: UserProfile) {
        _state.update { it.copy(userProfile = profile) }
    }

    fun setSetupComplete(deviceId: String) {
        _state.update { it.copy(isSetupComplete = true, deviceId = deviceId) }
    }

    fun loadFromPrefs() {
        val isSetupComplete = prefs.getBoolean("setup_complete", false)
        val deviceId = prefs.getString("device_id", "") ?: ""
        _state.update { it.copy(isSetupComplete = isSetupComplete, deviceId = deviceId) }
    }

    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString("device_id", deviceId).apply()
        _state.update { it.copy(deviceId = deviceId) }
    }
}

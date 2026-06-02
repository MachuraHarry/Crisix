package com.messenger.crisix.ui.viewmodel

import com.messenger.crisix.data.Contact
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.transport.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class ChatListViewModelTest {

    private val viewModel = ChatListViewModel()

    private fun createMessage(id: String, text: String, timestampMillis: Long, isFromMe: Boolean = false): Message =
        Message(
            id = id, text = text, isFromMe = isFromMe,
            timestamp = "12:00", timestampMillis = timestampMillis,
            status = MessageStatus.DELIVERED,
        )

    private fun createContact(peerId: String, name: String) = Contact(
        id = "c-$peerId", peerId = peerId, name = name,
    )

    private fun createPeer(id: String, name: String) = Peer(id = id, name = name)

    @Test
    fun `computeChats returns empty list when no data`() {
        val result = viewModel.computeChats(
            discoveredPeers = emptyList(),
            allMessages = emptyMap(),
            incomingNames = emptyMap(),
            savedContacts = emptyList(),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `computeChats sorts pinned chats first`() {
        val peerA = createPeer("aaa", "Peer A")
        val peerB = createPeer("bbb", "Peer B")
        val msg1 = createMessage("m1", "Hello", 1000)
        val msg2 = createMessage("m2", "World", 2000)

        val result = viewModel.computeChats(
            discoveredPeers = listOf(peerA, peerB),
            allMessages = mapOf("aaa" to listOf(msg1), "bbb" to listOf(msg2)),
            incomingNames = emptyMap(),
            savedContacts = emptyList(),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
            pinnedChatIds = setOf("bbb"),
        )

        assertEquals(2, result.size)
        assertTrue("First chat should be pinned", result[0].pinned)
        assertEquals("bbb", result[0].id)
        assertFalse("Second chat should not be pinned", result[1].pinned)
        assertEquals("aaa", result[1].id)
    }

    @Test
    fun `computeChats resolves contact names over peer names`() {
        val peer = createPeer("xxx", "PeerName")
        val contact = createContact("xxx", "ContactName")
        val msg = createMessage("m1", "Hi", 1000)

        val result = viewModel.computeChats(
            discoveredPeers = listOf(peer),
            allMessages = mapOf("xxx" to listOf(msg)),
            incomingNames = emptyMap(),
            savedContacts = listOf(contact),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )

        assertEquals(1, result.size)
        assertEquals("ContactName", result[0].name)
    }

    @Test
    fun `computeChats uses peer name when no contact exists`() {
        val peer = createPeer("xxx", "PeerName")
        val msg = createMessage("m1", "Hi", 1000)

        val result = viewModel.computeChats(
            discoveredPeers = listOf(peer),
            allMessages = mapOf("xxx" to listOf(msg)),
            incomingNames = emptyMap(),
            savedContacts = emptyList(),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )

        assertEquals(1, result.size)
        assertEquals("PeerName", result[0].name)
    }

    @Test
    fun `computeChats shows unread counts`() {
        val peer = createPeer("xxx", "Peer")
        val msg = createMessage("m1", "Hi", 1000)

        val result = viewModel.computeChats(
            discoveredPeers = listOf(peer),
            allMessages = mapOf("xxx" to listOf(msg)),
            incomingNames = emptyMap(),
            savedContacts = emptyList(),
            unreadCounts = mapOf("xxx" to 5),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )

        assertEquals(1, result.size)
        assertEquals(5, result[0].unreadCount)
    }

    @Test
    fun `computeChats includes chat-only peers from allMessages`() {
        val msg = createMessage("m1", "Secret chat", 1000)

        val result = viewModel.computeChats(
            discoveredPeers = emptyList(),
            allMessages = mapOf("hiddenPeer" to listOf(msg)),
            incomingNames = mapOf("hiddenPeer" to "Hidden"),
            savedContacts = emptyList(),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )

        assertEquals(1, result.size)
        assertEquals("Hidden", result[0].name)
    }

    @Test
    fun `computeChats sorts by timestamp descending after pinned`() {
        val peerA = createPeer("aaa", "Peer A")
        val peerB = createPeer("bbb", "Peer B")
        val msgOld = createMessage("old", "Old", 1000)
        val msgNew = createMessage("new", "New", 5000)

        val result = viewModel.computeChats(
            discoveredPeers = listOf(peerA, peerB),
            allMessages = mapOf("aaa" to listOf(msgOld), "bbb" to listOf(msgNew)),
            incomingNames = emptyMap(),
            savedContacts = emptyList(),
            unreadCounts = emptyMap(),
            activeTransportType = null,
            nowText = "Jetzt",
            defaultMessageText = "Keine Nachrichten",
        )

        assertEquals(2, result.size)
        assertTrue(result[0].timestampMillis >= result[1].timestampMillis)
    }
}

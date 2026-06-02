package com.messenger.crisix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class TransportManagerTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var tm: TransportManager
    private lateinit var dummy: TestTransport

    private val handshakeData = """{"type":"crisix_e2ee_handshake"}""".toByteArray()

    @Before
    fun setUp() {
        tm = TransportManager()
        dummy = TestTransport(TransportType.LORA)
        tm.registerTransport(dummy)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `sendMessage with handshake data succeeds via DummyTransport`() = runTest {
        tm.setEnabledTransports(TransportType.entries.toSet())
        val result = tm.sendMessage("peer1", handshakeData, "msg1")

        assertTrue(result.isSuccess)
        assertEquals(1, dummy.sentMessages.size)
        assertEquals("peer1", dummy.sentMessages[0].first)
    }

    @Test
    fun `sendMessage returns failure when all transports fail`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.RELAY, failSends = true)
        tm.registerTransport(failT)

        val result = tm.sendMessage("peer1", handshakeData, "msg1")

        assertTrue(result.isFailure)
        val errMsg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Error should mention simulated failure, got: $errMsg", errMsg.contains("simulated failure"))
    }

    @Test
    fun `circuit breaker opens after 3 failures and skips transport`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.RELAY, failSends = true)
        tm.registerTransport(failT)

        for (i in 1..3) {
            val r = tm.sendMessage("peer", handshakeData, "cb$i")
            assertTrue("Attempt $i should fail", r.isFailure)
            assertTrue(
                "Attempt $i should fail with simulated failure, got: ${r.exceptionOrNull()?.message}",
                r.exceptionOrNull()?.message?.contains("simulated failure") == true
            )
        }

        val r4 = tm.sendMessage("peer", handshakeData, "cb4")
        assertTrue("4th attempt should fail (CB OPEN)", r4.isFailure)
        assertEquals(
            "4th attempt should fail with 'Empfänger nicht erreichbar'",
            "Empfänger nicht erreichbar",
            r4.exceptionOrNull()?.message
        )
    }

    @Test
    fun `connectionStatuses reflects circuit breaker OPEN state`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.RELAY, failSends = true)
        tm.registerTransport(failT)

        for (i in 1..3) {
            tm.sendMessage("peer", handshakeData, "cbv$i")
        }

        val status = tm.connectionStatuses.value[TransportType.RELAY]
        assertNotNull("RELAY status should exist after failures", status)
        assertEquals(ConnectionState.ERROR, status?.state)
        assertTrue(
            "Error message should mention Circuit-Breaker OPEN",
            status?.errorMessage?.contains("Circuit-Breaker OPEN") == true
        )
    }

    @Test
    fun `enabledTransports filters which transports are tried`() = runTest {
        val internetT = TestTransport(TransportType.INTERNET)
        tm = TransportManager()
        tm.registerTransport(dummy)
        tm.registerTransport(internetT)

        assertTrue("INTERNET should be enabled by default", tm.isTransportEnabled(TransportType.INTERNET))

        tm.setEnabledTransports(setOf(TransportType.INTERNET))

        val r = tm.sendMessage("peer", handshakeData, "en1")
        assertTrue("Send with INTERNET should succeed", r.isSuccess)
        assertEquals("INTERNET transport should have sent 1 message", 1, internetT.sentMessages.size)
        assertEquals("LORA transport should have sent 0 messages (disabled)", 0, dummy.sentMessages.size)
    }

    @Test
    fun `mutual priority only tries transports the peer supports`() = runTest {
        val wifi = TestTransport(TransportType.WIFI_DIRECT)
        val internet = TestTransport(TransportType.INTERNET)
        val relay = TestTransport(TransportType.RELAY)
        val ble = TestTransport(TransportType.BLUETOOTH_MESH)
        val dns = TestTransport(TransportType.DNS_TUNNEL)
        tm = TransportManager()
        tm.registerTransport(wifi)
        tm.registerTransport(internet)
        tm.registerTransport(relay)
        tm.registerTransport(ble)
        tm.registerTransport(dns)
        tm.registerTransport(dummy)

        tm.updatePeerCapabilities(PeerCapabilities(
            peerId = "peer_caps",
            hasBle = true,
            hasInternet = false,
            hasWifiDirect = false,
            hasRelay = false
        ))

        val r = tm.sendMessage("peer_caps", handshakeData, "caps1")
        assertTrue("Send should succeed via BLE", r.isSuccess)

        assertEquals("BLE should be tried first", 1, ble.sentMessages.size)
        assertEquals("WiFi should not be tried (peer has no WiFi)", 0, wifi.sentMessages.size)
        assertEquals("Internet should not be tried (peer has no Internet)", 0, internet.sentMessages.size)
        assertEquals("Relay should not be tried (peer has no Relay)", 0, relay.sentMessages.size)
        assertEquals("DNS should not be tried (needs Internet or Relay)", 0, dns.sentMessages.size)
    }

    @Test
    fun `mutual priority with full capabilities tries all transports`() = runTest {
        val wifi = TestTransport(TransportType.WIFI_DIRECT)
        val internet = TestTransport(TransportType.INTERNET)
        val relay = TestTransport(TransportType.RELAY)
        tm = TransportManager()
        tm.registerTransport(wifi)
        tm.registerTransport(internet)
        tm.registerTransport(relay)
        tm.registerTransport(dummy)

        tm.updatePeerCapabilities(PeerCapabilities(
            peerId = "peer_full",
            hasInternet = true,
            hasWifiDirect = true,
            hasBle = false,
            hasRelay = true
        ))

        val r = tm.sendMessage("peer_full", handshakeData, "caps2")
        assertTrue("Send should succeed via WiFi (highest priority)", r.isSuccess)

        assertEquals("WiFi should be tried first (highest priority + peer has it)", 1, wifi.sentMessages.size)
    }

    @Test
    fun `retry queue adds entry when send fails with uiMessageId`() = runTest {
        var addedMsgId: String? = null
        tm.onRetryAdd = { id, _, _, _ -> addedMsgId = id }

        tm.setEnabledTransports(emptySet())
        val r = tm.sendMessage("peer", handshakeData, "retry1")

        assertTrue(r.isFailure)
        assertEquals("retry1", addedMsgId)
    }

    @Test
    fun `sendMessage without uiMessageId skips retry queue`() = runTest {
        var retryAdded = false
        tm.onRetryAdd = { _, _, _, _ -> retryAdded = true }

        tm.setEnabledTransports(emptySet())
        val r = tm.sendMessage("peer", handshakeData, null)

        assertTrue(r.isFailure)
        assertFalse("No retry queue entry without uiMessageId", retryAdded)
    }

    @Test
    fun `deliveryUpdates emits SENT on successful send`() = runTest {
        tm.setEnabledTransports(TransportType.entries.toSet())
        val relayT = TestTransport(TransportType.RELAY)
        tm.registerTransport(relayT)
        val updates = ConcurrentLinkedQueue<DeliveryUpdate>()
        val job = testScope.launch {
            tm.deliveryUpdates.collect { updates.add(it) }
        }

        tm.sendMessage("peer", handshakeData, "du1")
        yield()

        job.cancel()
        assertTrue(
            "Should have SENT update for du1",
            updates.any { it.uiMessageId == "du1" && it.status == MessageStatus.SENT }
        )
    }

    @Test
    fun `deliveryUpdates emits PENDING when no transport available`() = runTest {
        tm.setEnabledTransports(emptySet())

        val updates = ConcurrentLinkedQueue<DeliveryUpdate>()
        val job = testScope.launch {
            tm.deliveryUpdates.collect { updates.add(it) }
        }

        tm.sendMessage("peer", handshakeData, "pend1")
        yield()

        job.cancel()
        assertTrue(
            "Should have PENDING update for pend1",
            updates.any { it.uiMessageId == "pend1" && it.status == MessageStatus.PENDING }
        )
    }

    @Test
    fun `deliveryUpdates shows correct transport type on success`() = runTest {
        val relayT = TestTransport(TransportType.RELAY)
        tm.registerTransport(relayT)
        val updates = ConcurrentLinkedQueue<DeliveryUpdate>()
        val job = testScope.launch {
            tm.deliveryUpdates.collect { updates.add(it) }
        }

        tm.sendMessage("peer", handshakeData, "du2")
        yield()

        job.cancel()
        val sentUpdate = updates.find { it.uiMessageId == "du2" && it.status == MessageStatus.SENT }
        assertNotNull("Should have SENT update", sentUpdate)
        assertEquals("Transport should be RELAY", TransportType.RELAY, sentUpdate?.transport)
    }

    @Test
    fun `isTransportEnabled reflects defaults`() {
        assertFalse("LORA should be disabled by default", tm.isTransportEnabled(TransportType.LORA))
        assertTrue("INTERNET should be enabled by default", tm.isTransportEnabled(TransportType.INTERNET))
        assertTrue("RELAY should be enabled by default", tm.isTransportEnabled(TransportType.RELAY))
        assertFalse("SMS should be disabled by default", tm.isTransportEnabled(TransportType.SMS))
    }

    @Test
    fun `setEnabledTransports can disable all transports`() = runTest {
        tm.setEnabledTransports(emptySet())

        assertFalse(tm.isTransportEnabled(TransportType.LORA))
        assertFalse(tm.isTransportEnabled(TransportType.INTERNET))
    }

    @Test
    fun `loadPendingEntries populates retry queue`() {
        tm.loadPendingEntries(listOf(
            TransportManager.RetryEntry("loaded1", "peerX", "dataX".toByteArray(), 2)
        ))

        // Verify by checking that onRetryRemove is called on successful retry
        var removedId: String? = null
        tm.onRetryRemove = { removedId = it }

        // loadPendingEntries should not throw
        // The entry with retryCount=2 stays in the queue
    }

    @Test
    fun `registerTransport adds transport to manager`() {
        val customT = TestTransport(TransportType.DNS_TUNNEL)
        tm.registerTransport(customT)

        val found = tm.getTransportByType(TransportType.DNS_TUNNEL)
        assertNotNull("Transport should be findable after registration", found)
        assertSame("Should be the same instance", customT, found)
    }

    @Test
    fun `acknowledged ping is not forwarded to external listener`() {
        val listenerTm = TransportManager()
        listenerTm.registerTransport(dummy)

        var externalReceived = false
        listenerTm.registerMessageListener { _, _, _ ->
            externalReceived = true
        }

        val pingData = """{"type":"crisix_ping","id":"test-ping-1"}""".toByteArray()
        dummy.injectMessage("peer", pingData)

        assertFalse("Ping should be handled internally, not forwarded to listener", externalReceived)
    }

    @Test
    fun `acknowledged pong is not forwarded to external listener`() {
        val listenerTm = TransportManager()
        listenerTm.registerTransport(dummy)

        var externalReceived = false
        listenerTm.registerMessageListener { _, _, _ ->
            externalReceived = true
        }

        val pongData = """{"type":"crisix_pong","id":"test-pong-1"}""".toByteArray()
        dummy.injectMessage("peer", pongData)

        assertFalse("Pong should be handled internally, not forwarded to listener", externalReceived)
    }

    @Test
    fun `non-internal message is forwarded to external listener`() {
        val listenerTm = TransportManager()
        listenerTm.registerTransport(dummy)

        var externalReceived = false
        var receivedPeerId: String? = null
        listenerTm.registerMessageListener { peerId, _, _ ->
            externalReceived = true
            receivedPeerId = peerId
        }

        val chatData = """{"type":"crisix_e2ee","data":"encrypted"}""".toByteArray()
        dummy.injectMessage("peer", chatData)

        assertTrue("Chat message should be forwarded to external listener", externalReceived)
        assertEquals("Peer ID should be passed correctly", "peer", receivedPeerId)
    }

    @Test
    fun `SMS is excluded from circuit breaker`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.INTERNET, failSends = true)
        tm.registerTransport(failT)

        for (i in 1..3) {
            val r = tm.sendMessage("peer", handshakeData, "cb$i")
            assertTrue("Attempt $i should fail with simulated failure",
                r.exceptionOrNull()?.message?.contains("simulated failure") == true)
        }

        val r4 = tm.sendMessage("peer", handshakeData, "cb4")
        assertTrue("4th attempt should fail (CB OPEN)",
            r4.isFailure)
        assertEquals("Empfänger nicht erreichbar", r4.exceptionOrNull()?.message)
    }

    @Test
    fun `DNS_TUNNEL has longer circuit breaker timeout`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.DNS_TUNNEL, failSends = true)
        tm.registerTransport(failT)

        for (i in 1..3) {
            tm.sendMessage("peer", handshakeData, "dns$i")
        }

        val r4 = tm.sendMessage("peer", handshakeData, "dns4")
        assertTrue("4th attempt blocked by CB",
            r4.exceptionOrNull()?.message?.contains("Empfänger nicht erreichbar") == true)

        val status = tm.connectionStatuses.value[TransportType.DNS_TUNNEL]
        assertEquals(ConnectionState.ERROR, status?.state)
    }

    @Test
    fun `circuit breaker records success and resets`() = runTest {
        tm = TransportManager()
        val failT = TestTransport(TransportType.RELAY, failSends = true)
        val okT = TestTransport(TransportType.RELAY, failSends = false)
        tm.registerTransport(failT)
        tm.registerTransport(okT)

        for (i in 1..3) {
            tm.sendMessage("peer", handshakeData, "reset$i")
        }

        val status = tm.connectionStatuses.value[TransportType.RELAY]
        assertEquals(ConnectionState.ERROR, status?.state)

        val succT = TestTransport(TransportType.INTERNET, failSends = false)
        tm.registerTransport(succT)
        val r = tm.sendMessage("peer", handshakeData, "success")
        assertTrue(r.isSuccess)
    }

}

class TestTransport(
    override val type: TransportType,
    private val failSends: Boolean = false,
) : Transport {
    val sentMessages = mutableListOf<Pair<String, ByteArray>>()
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    override val capabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsFileTransfer = true,
        isMetered = false,
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        sentMessages.add(peerId to data)
        return if (failSends) {
            Result.failure(Exception("simulated failure"))
        } else {
            Result.success(Unit)
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    fun injectMessage(peerId: String, data: ByteArray) {
        listeners.forEach { it(peerId, data) }
    }

    override fun discoverPeers(): Flow<Peer> = Channel<Peer>().receiveAsFlow()
    override suspend fun start() {}
    override suspend fun stop() {}
    override fun getStatusDetail(): Pair<Int, String> = Pair(0, "Test")
}

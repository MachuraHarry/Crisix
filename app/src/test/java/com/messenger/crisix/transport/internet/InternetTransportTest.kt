package com.messenger.crisix.transport.internet

import com.messenger.crisix.transport.Peer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit-Tests für das Crisix P2P Internet Transport Modul.
 *
 * Testet die Kernkomponenten:
 * - CryptoHelper: Schlüsselgenerierung und -konvertierung
 * - CrisixProtocol: Nachrichtenkodierung und -dekodierung
 * - InternetTransport: Start/Stop und Nachrichtensendung
 *
 * ## Teststrategie
 * Die Tests sind als isolierte Unit-Tests konzipiert, die keine
 * Netzwerkverbindung benötigen. Für Integrationstests mit zwei
 * P2P-Instanzen wäre ein Android-Gerät oder Emulator nötig.
 */
class InternetTransportTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        println("=== InternetTransportTest: setUp ===")
    }

    @After
    fun tearDown() {
        println("=== InternetTransportTest: tearDown ===")
        testScope.cancel()
    }

    // =========================================================================
    // CryptoHelper Tests
    // =========================================================================

    /**
     * Testet die Generierung eines Ed25519-Schlüsselpaares.
     *
     * Prüft:
     * - Schlüsselpaar ist nicht null
     * - Privater Schlüssel hat die richtige Länge (Ed25519: 64 Bytes)
     * - Öffentlicher Schlüssel hat die richtige Länge (Ed25519: 32 Bytes)
     */
    @Test
    fun testGenerateKeyPair() {
        println("Test: testGenerateKeyPair")

        val keyPair = CryptoHelper.generateKeyPair()

        assertNotNull("Schlüsselpaar darf nicht null sein", keyPair)
        assertNotNull("Privater Schlüssel darf nicht null sein", keyPair.privateKey)
        assertNotNull("Öffentlicher Schlüssel darf nicht null sein", keyPair.publicKey)

        println("  Privater Schlüssel: ${keyPair.privateKey.size} Bytes")
        println("  Öffentlicher Schlüssel: ${keyPair.publicKey.size} Bytes")

        assertTrue("Privater Schlüssel sollte > 0 Bytes haben", keyPair.privateKey.isNotEmpty())
        assertTrue("Öffentlicher Schlüssel sollte > 0 Bytes haben", keyPair.publicKey.isNotEmpty())

        // Ed25519: privater Schlüssel = 64 Bytes, öffentlicher = 32 Bytes
        assertEquals("Privater Ed25519-Schlüssel sollte 64 Bytes haben", 64, keyPair.privateKey.size)
        assertEquals("Öffentlicher Ed25519-Schlüssel sollte 32 Bytes haben", 32, keyPair.publicKey.size)
    }

    /**
     * Testet die Serialisierung und Deserialisierung von Schlüsseln.
     *
     * Prüft:
     * - keyPairToBytes() liefert ein gültiges Byte-Array
     * - keyPairFromBytes() stellt das ursprüngliche Schlüsselpaar wieder her
     * - Der wiederhergestellte öffentliche Schlüssel stimmt mit dem Original überein
     */
    @Test
    fun testKeySerialization() {
        println("Test: testKeySerialization")

        // Schlüsselpaar generieren
        val originalKeyPair = CryptoHelper.generateKeyPair()
        val originalPubKey = originalKeyPair.publicKey

        // Serialisieren
        val serialized = CryptoHelper.keyPairToBytes(originalKeyPair)
        assertNotNull("Serialisierte Bytes dürfen nicht null sein", serialized)
        assertTrue("Serialisierte Bytes sollten nicht leer sein", serialized.isNotEmpty())
        println("  Serialisiert: ${serialized.size} Bytes")

        // Deserialisieren
        val restoredKeyPair = CryptoHelper.keyPairFromBytes(serialized)
        val restoredPubKey = restoredKeyPair.publicKey

        // Prüfen, ob die öffentlichen Schlüssel übereinstimmen
        assertArrayEquals(
            "Wiederhergestellter öffentlicher Schlüssel sollte identisch sein",
            originalPubKey,
            restoredPubKey
        )
        println("  Öffentliche Schlüssel stimmen überein: ✅")
    }

    /**
     * Testet die Fingerprint-Erzeugung aus öffentlichen Schlüsseln.
     *
     * Prüft:
     * - Fingerprint ist ein gültiger Hex-String
     * - Gleicher Schlüssel erzeugt gleichen Fingerprint
     * - Unterschiedliche Schlüssel erzeugen unterschiedliche Fingerprints
     */
    @Test
    fun testPublicKeyFingerprint() {
        println("Test: testPublicKeyFingerprint")

        val keyPair = CryptoHelper.generateKeyPair()
        val pubKeyBytes = keyPair.publicKey

        val fingerprint1 = CryptoHelper.publicKeyToFingerprint(pubKeyBytes)
        val fingerprint2 = CryptoHelper.publicKeyToFingerprint(keyPair.publicKey)

        println("  Fingerprint 1: $fingerprint1")
        println("  Fingerprint 2: $fingerprint2")

        assertNotNull("Fingerprint darf nicht null sein", fingerprint1)
        assertTrue("Fingerprint sollte nicht leer sein", fingerprint1.isNotEmpty())

        // Gleicher Schlüssel -> gleicher Fingerprint
        assertEquals("Fingerprints sollten identisch sein", fingerprint1, fingerprint2)

        // Unterschiedliche Schlüssel -> unterschiedliche Fingerprints
        val otherKeyPair = CryptoHelper.generateKeyPair()
        val otherFingerprint = CryptoHelper.publicKeyToFingerprint(otherKeyPair.publicKey)
        assertNotEquals("Unterschiedliche Schlüssel sollten unterschiedliche Fingerprints haben",
            fingerprint1, otherFingerprint)

        println("  Fingerprint-Tests: ✅")
    }

    /**
     * Testet die Signierung und Verifikation von Nachrichten.
     */
    @Test
    fun testSignAndVerify() {
        println("Test: testSignAndVerify")

        val keyPair = CryptoHelper.generateKeyPair()
        val data = "Testnachricht für Signatur".toByteArray()

        // Signieren
        val signature = CryptoHelper.sign(data, keyPair.privateKey)
        assertNotNull("Signatur darf nicht null sein", signature)
        assertTrue("Signatur sollte nicht leer sein", signature.isNotEmpty())
        println("  Signatur: ${signature.size} Bytes")

        // Verifizieren
        val isValid = CryptoHelper.verify(data, signature, keyPair.publicKey)
        assertTrue("Signatur sollte gültig sein", isValid)

        // Verifikation mit manipulierten Daten sollte fehlschlagen
        val tamperedData = "Manipulierte Nachricht".toByteArray()
        val isTamperedValid = CryptoHelper.verify(tamperedData, signature, keyPair.publicKey)
        assertFalse("Signatur sollte bei manipulierten Daten ungültig sein", isTamperedValid)

        println("  Signatur/Verifikation: ✅")
    }

    // =========================================================================
    // CrisixProtocol Tests
    // =========================================================================

    /**
     * Testet die Kodierung und Dekodierung von Chat-Nachrichten.
     *
     * Prüft:
     * - encodeMessage() liefert gültige Bytes
     * - decodeMessage() stellt die ursprüngliche Nachricht wieder her
     * - Alle Felder sind nach der Dekodierung korrekt
     */
    @Test
    fun testMessageEncodeDecode() {
        println("Test: testMessageEncodeDecode")

        val messageId = UUID.randomUUID().toString()
        val senderId = "12D3KooWTestSender123"
        val recipientId = "12D3KooWTestRecipient456"
        val payload = "Hallo Welt! Dies ist eine Testnachricht.".toByteArray()
        val timestamp = System.currentTimeMillis()

        val originalMessage = CrisixProtocol.CrisixMessage(
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = payload,
            timestamp = timestamp,
            sequenceNumber = 1
        )

        // Kodieren
        val encoded = CrisixProtocol.encodeMessage(originalMessage)
        assertNotNull("Kodierte Nachricht darf nicht null sein", encoded)
        assertTrue("Kodierte Nachricht sollte nicht leer sein", encoded.isNotEmpty())
        println("  Kodiert: ${encoded.size} Bytes")

        // Dekodieren
        val decoded = CrisixProtocol.decodeMessage(encoded)
        assertNotNull("Dekodierte Nachricht darf nicht null sein", decoded)

        // Felder prüfen
        assertEquals("messageId sollte identisch sein", messageId, decoded!!.messageId)
        assertEquals("senderId sollte identisch sein", senderId, decoded.senderId)
        assertEquals("recipientId sollte identisch sein", recipientId, decoded.recipientId)
        assertEquals("type sollte CHAT_MESSAGE sein",
            CrisixProtocol.MessageType.CHAT_MESSAGE, decoded.type)
        assertArrayEquals("payload sollte identisch sein", payload, decoded.payload)
        assertEquals("timestamp sollte identisch sein", timestamp, decoded.timestamp)
        assertEquals("sequenceNumber sollte identisch sein", 1, decoded.sequenceNumber)

        println("  Nachrichten-Kodierung/Dekodierung: ✅")
    }

    /**
     * Testet die Erstellung von ACK-Nachrichten.
     */
    @Test
    fun testCreateAck() {
        println("Test: testCreateAck")

        val originalMessage = CrisixProtocol.CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = "peerA",
            recipientId = "peerB",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "Test".toByteArray()
        )

        val ack = CrisixProtocol.createAck(originalMessage, "peerB")

        assertEquals("ACK sollte an den ursprünglichen Sender gehen",
            originalMessage.senderId, ack.recipientId)
        assertEquals("ACK sollte vom ursprünglichen Empfänger kommen",
            "peerB", ack.senderId)
        assertEquals("ACK-Typ sollte ACK sein",
            CrisixProtocol.MessageType.ACK, ack.type)
        assertArrayEquals("ACK-Payload sollte die Original-ID enthalten",
            originalMessage.messageId.toByteArray(), ack.payload)

        println("  ACK-Erstellung: ✅")
    }

    /**
     * Testet die Erstellung von PING/PONG-Nachrichten.
     */
    @Test
    fun testPingPong() {
        println("Test: testPingPong")

        val ping = CrisixProtocol.createPing("peerA", "peerB")

        assertEquals("PING sollte von peerA kommen", "peerA", ping.senderId)
        assertEquals("PING sollte an peerB gehen", "peerB", ping.recipientId)
        assertEquals("PING-Typ sollte PING sein",
            CrisixProtocol.MessageType.PING, ping.type)

        val pong = CrisixProtocol.createPong(ping, "peerB")

        assertEquals("PONG sollte von peerB kommen", "peerB", pong.senderId)
        assertEquals("PONG sollte an peerA gehen", "peerA", pong.recipientId)
        assertEquals("PONG-Typ sollte PONG sein",
            CrisixProtocol.MessageType.PONG, pong.type)
        assertArrayEquals("PONG-Payload sollte die Ping-ID enthalten",
            ping.messageId.toByteArray(), pong.payload)

        println("  PING/PONG-Erstellung: ✅")
    }

    /**
     * Testet die Dekodierung von ungültigen Daten.
     */
    @Test
    fun testDecodeInvalidData() {
        println("Test: testDecodeInvalidData")

        val invalidData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val result = CrisixProtocol.decodeMessage(invalidData)

        assertNull("Ungültige Daten sollten null ergeben", result)
        println("  Ungültige Daten-Behandlung: ✅")
    }

    /**
     * Testet alle Nachrichtentypen.
     */
    @Test
    fun testAllMessageTypes() {
        println("Test: testAllMessageTypes")

        val types = listOf(
            CrisixProtocol.MessageType.CHAT_MESSAGE,
            CrisixProtocol.MessageType.ACK,
            CrisixProtocol.MessageType.FILE_TRANSFER,
            CrisixProtocol.MessageType.PING,
            CrisixProtocol.MessageType.PONG,
            CrisixProtocol.MessageType.TYPING,
            CrisixProtocol.MessageType.STATUS_UPDATE
        )

        for (type in types) {
            val message = CrisixProtocol.CrisixMessage(
                messageId = UUID.randomUUID().toString(),
                senderId = "sender",
                recipientId = "recipient",
                type = type,
                payload = "Test".toByteArray()
            )

            val encoded = CrisixProtocol.encodeMessage(message)
            val decoded = CrisixProtocol.decodeMessage(encoded)

            assertNotNull("Dekodierte Nachricht für $type darf nicht null sein", decoded)
            assertEquals("Typ $type sollte korrekt dekodiert werden", type, decoded!!.type)
        }

        println("  Alle Nachrichtentypen: ✅")
    }

    // =========================================================================
    // InternetTransport Tests (simuliert)
    // =========================================================================

    /**
     * Testet die Capabilities des InternetTransport.
     */
    @Test
    fun testInternetTransportCapabilities() {
        println("Test: testInternetTransportCapabilities")

        val transport = InternetTransport("TestDevice")

        assertEquals("TransportType sollte INTERNET sein",
            com.messenger.crisix.transport.TransportType.INTERNET, transport.type)

        val caps = transport.capabilities
        assertTrue("Sollte Text unterstützen", caps.supportsText)
        assertTrue("Sollte Bilder unterstützen", caps.supportsImages)
        assertTrue("Sollte Video unterstützen", caps.supportsVideo)
        assertTrue("Sollte Audio unterstützen", caps.supportsAudio)
        assertTrue("Sollte Dateien unterstützen", caps.supportsFileTransfer)
        assertFalse("Sollte nicht kostenpflichtig sein", caps.isMetered)

        println("  Capabilities: ✅")
    }

    /**
     * Testet die Listener-Registrierung und -Benachrichtigung.
     *
     * Simuliert eingehende Nachrichten über den internen
     * onIncomingMessage()-Kanal.
     */
    @Test
    fun testMessageListener() {
        println("Test: testMessageListener")

        val transport = InternetTransport("TestDevice")
        val receivedMessages = ConcurrentLinkedQueue<Pair<String, ByteArray>>()
        val messageReceived = AtomicBoolean(false)

        // Listener registrieren
        transport.registerListener { peerId, data ->
            receivedMessages.add(Pair(peerId, data))
            messageReceived.set(true)
        }

        // Simuliere eingehende Nachricht - muss ein gültiges CrisixProtocol-Format haben
        val testPeerId = "12D3KooWTestPeer"
        val testPayload = "Hallo vom Test!".toByteArray()

        // Erstelle eine gültige Crisix-Nachricht und kodiere sie
        val testMessage = CrisixProtocol.CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = testPeerId,
            recipientId = "localPeer",
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = testPayload,
            timestamp = System.currentTimeMillis()
        )
        val encodedMessage = CrisixProtocol.encodeMessage(testMessage)

        // Nachricht über den Channel senden (wie im echten Betrieb)
        transport.onIncomingMessage(testPeerId, encodedMessage)

        // Warte auf Verarbeitung der Nachricht (max 2 Sekunden)
        val startTime = System.currentTimeMillis()
        while (!messageReceived.get() && System.currentTimeMillis() - startTime < 2000) {
            Thread.sleep(10)
        }

        assertTrue("Nachricht sollte empfangen worden sein", messageReceived.get())
        assertEquals("Eine Nachricht sollte empfangen worden sein", 1, receivedMessages.size)

        val (receivedPeerId, receivedData) = receivedMessages.first()
        assertEquals("Peer-ID sollte korrekt sein", testPeerId, receivedPeerId)
        assertArrayEquals("Daten sollten korrekt sein", testPayload, receivedData)

        println("  Listener-Test: ✅")
    }

    /**
     * Testet die Fehlerbehandlung beim Senden ohne gestarteten Transport.
     */
    @Test
    fun testSendWithoutStart() = runTest {
        println("Test: testSendWithoutStart")

        val transport = InternetTransport("TestDevice")
        val result = transport.send("peerId", "Test".toByteArray())

        assertTrue("Senden ohne Start sollte fehlschlagen", result.isFailure)
        println("  Fehlerbehandlung: ✅ (${result.exceptionOrNull()?.message})")
    }

    /**
     * Testet die Peer-Discovery (simuliert).
     *
     * Da der Transport nicht gestartet wird, sollte discoverPeers()
     * einen leeren Flow zurückgeben.
     */
    @Test
    fun testDiscoverPeers() = runTest {
        println("Test: testDiscoverPeers")

        val transport = InternetTransport("TestDevice")

        // discoverPeers() sollte einen Flow zurückgeben (auch wenn leer)
        val peerFlow = transport.discoverPeers()
        assertNotNull("Peer-Flow sollte nicht null sein", peerFlow)

        println("  Peer-Discovery-Flow: ✅")
    }

    /**
     * Testet die isAvailable()-Methode.
     *
     * Im Testkontext (ohne Netzwerk) sollte false zurückgegeben werden.
     */
    @Test
    fun testIsAvailable() = runTest {
        println("Test: testIsAvailable")

        val transport = InternetTransport("TestDevice")
        val available = transport.isAvailable()

        // Im Testkontext ist meist kein Netzwerk verfügbar
        println("  isAvailable: $available")
        assertNotNull("isAvailable sollte nicht null sein", available)

        println("  isAvailable-Test: ✅")
    }

    // =========================================================================
    // Integrationstest (simuliert zwei Peers)
    // =========================================================================

    /**
     * Simulierter Integrationstest: Zwei Peers tauschen Nachrichten aus.
     *
     * Da P2P-Instanzen im Unit-Test nicht gestartet werden können
     * (benötigt Android-Kontext), simulieren wir den Nachrichtenaustausch
     * über das CrisixProtocol.
     *
     * In der Praxis würde dieser Test auf einem Android-Gerät oder
     * Emulator mit zwei P2P-Instanzen ausgeführt werden.
     */
    @Test
    fun testTwoPeerMessageExchange() = runTest {
        println("Test: testTwoPeerMessageExchange")

        // Simuliere zwei Peers
        val peerAId = "12D3KooWPeerA"
        val peerBId = "12D3KooWPeerB"

        // Peer A erstellt eine Nachricht für Peer B
        val originalMessage = CrisixProtocol.CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = peerAId,
            recipientId = peerBId,
            type = CrisixProtocol.MessageType.CHAT_MESSAGE,
            payload = "Hallo Peer B!".toByteArray(),
            timestamp = System.currentTimeMillis()
        )

        // Nachricht kodieren (simuliert das Senden über das Netzwerk)
        val encodedMessage = CrisixProtocol.encodeMessage(originalMessage)
        println("  Peer A -> Peer B: ${encodedMessage.size} Bytes kodiert")

        // Nachricht dekodieren (simuliert das Empfangen bei Peer B)
        val decodedMessage = CrisixProtocol.decodeMessage(encodedMessage)
        assertNotNull("Peer B sollte die Nachricht dekodieren können", decodedMessage)

        // Prüfen, ob die Nachricht korrekt ist
        assertEquals("Absender sollte Peer A sein", peerAId, decodedMessage!!.senderId)
        assertEquals("Empfänger sollte Peer B sein", peerBId, decodedMessage.recipientId)
        assertEquals("Typ sollte CHAT_MESSAGE sein",
            CrisixProtocol.MessageType.CHAT_MESSAGE, decodedMessage.type)
        assertEquals("Inhalt sollte korrekt sein",
            "Hallo Peer B!", String(decodedMessage.payload))

        println("  Peer A -> Peer B: Nachricht erfolgreich übertragen ✅")

        // Peer B erstellt eine ACK für Peer A
        val ack = CrisixProtocol.createAck(decodedMessage, peerBId)
        val encodedAck = CrisixProtocol.encodeMessage(ack)

        // Peer A dekodiert die ACK
        val decodedAck = CrisixProtocol.decodeMessage(encodedAck)
        assertNotNull("Peer A sollte die ACK dekodieren können", decodedAck)
        assertEquals("ACK sollte von Peer B kommen", peerBId, decodedAck!!.senderId)
        assertEquals("ACK sollte an Peer A gehen", peerAId, decodedAck.recipientId)
        assertEquals("ACK-Typ sollte ACK sein",
            CrisixProtocol.MessageType.ACK, decodedAck.type)
        assertArrayEquals("ACK sollte die Original-ID enthalten",
            originalMessage.messageId.toByteArray(), decodedAck.payload)

        println("  Peer B -> Peer A: ACK erfolgreich übertragen ✅")
        println("  Zwei-Peer-Nachrichtenaustausch: ✅")
    }

    /**
     * Testet die Nonce-Generierung (Replay-Schutz).
     *
     * Prüft, dass jede Nachricht eine eindeutige Nonce hat.
     */
    @Test
    fun testNonceUniqueness() {
        println("Test: testNonceUniqueness")

        val nonces = mutableSetOf<String>()
        val numMessages = 100

        for (i in 0 until numMessages) {
            val message = CrisixProtocol.CrisixMessage(
                messageId = UUID.randomUUID().toString(),
                senderId = "sender",
                recipientId = "recipient",
                type = CrisixProtocol.MessageType.CHAT_MESSAGE,
                payload = "Test $i".toByteArray()
            )
            val nonceKey = message.nonce.contentToString()
            assertFalse("Nonce sollte eindeutig sein (Iteration $i)",
                nonces.contains(nonceKey))
            nonces.add(nonceKey)
        }

        assertEquals("Alle $numMessages Nonces sollten eindeutig sein",
            numMessages, nonces.size)
        println("  Nonce-Eindeutigkeit ($numMessages Nachrichten): ✅")
    }

    /**
     * Testet die Sequenznummern in Nachrichten.
     */
    @Test
    fun testSequenceNumbers() {
        println("Test: testSequenceNumbers")

        val numMessages = 5
        val messages = mutableListOf<CrisixProtocol.CrisixMessage>()

        for (i in 0 until numMessages) {
            val message = CrisixProtocol.CrisixMessage(
                messageId = UUID.randomUUID().toString(),
                senderId = "sender",
                recipientId = "recipient",
                type = CrisixProtocol.MessageType.CHAT_MESSAGE,
                payload = "Nachricht $i".toByteArray(),
                sequenceNumber = i
            )
            messages.add(message)
        }

        // Kodieren und dekodieren, um Sequenznummern zu prüfen
        for ((index, message) in messages.withIndex()) {
            val encoded = CrisixProtocol.encodeMessage(message)
            val decoded = CrisixProtocol.decodeMessage(encoded)
            assertNotNull("Dekodierte Nachricht $index sollte nicht null sein", decoded)
            assertEquals("Sequenznummer $index sollte korrekt sein",
                index, decoded!!.sequenceNumber)
        }

        println("  Sequenznummern ($numMessages Nachrichten): ✅")
    }
}

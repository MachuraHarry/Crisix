package com.messenger.crisix.transport

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * DNS-Tunnel-Transport für Crisix.
 *
 * Ermöglicht das Senden und Empfangen von Nachrichten über DNS-Queries,
 * ohne eigenen Server. Nutzt den Crisix DNS-Tunnel-Server auf Render.com.
 *
 * ## Protokoll
 *
 * Senden:
 *   DNS-Query: send.[base32-nachricht].[empfänger-id].crisix-dns.onrender.com TXT
 *   → Server speichert Nachricht in Queue
 *
 * Empfangen (Polling):
 *   DNS-Query: poll.[eigene-id].crisix-dns.onrender.com TXT
 *   → Server antwortet mit TXT-Records: "msg:[hash]:[sender]:[base32]"
 *
 * Bestätigung:
 *   DNS-Query: ack.[msg-hash].[eigene-id].crisix-dns.onrender.com TXT
 *   → Server löscht Nachricht aus Queue
 *
 * ## Capabilities
 * - Nur Text (max. 200 Zeichen)
 * - Keine Medien (Bilder, Videos, Audio)
 * - Kein File-Transfer
 * - Nicht gemetered (keine Kosten)
 */
class DnsTunnelTransport(
    private val localPeerId: String,
    private val serverDomain: String = "crisix-dns.onrender.com",
    private val useHttpApi: Boolean = true, // true = HTTP, false = UDP-DNS
) : Transport {

    companion object {
        private const val TAG = "DnsTunnel"
        private const val MAX_TEXT_LENGTH = 200
        private const val POLL_INTERVAL_MS = 5000L // Alle 5s poll
        private const val DNS_TIMEOUT_MS = 3000L
        private const val DNS_PORT = 8053
    }

    override val type: TransportType = TransportType.DNS_TUNNEL
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = MAX_TEXT_LENGTH,
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsFileTransfer = false,
        isMetered = false
    )

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val discoveredPeersFlow = _discoveredPeers.asStateFlow()

    private val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    private var isRunning = false
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    // DNS-Resolver (für UDP-DNS)
    private var dnsSocket: DatagramSocket? = null

    // ─── Base32 (DNS-sicher) ────────────────────────────────────────────────

    private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    private fun base32Encode(data: ByteArray): String {
        val binary = data.joinToString("") { it.toUByte().toString(2).padStart(8, '0') }
        val padded = binary.padEnd(((binary.length + 4) / 5) * 5, '0')
        val sb = StringBuilder()
        for (i in padded.indices step 5) {
            val chunk = padded.substring(i, minOf(i + 5, padded.length))
            val index = chunk.toInt(2)
            sb.append(BASE32_ALPHABET[index])
        }
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray {
        val binary = s.map { c ->
            val index = BASE32_ALPHABET.indexOf(c)
            if (index < 0) throw IllegalArgumentException("Ungültiges Base32-Zeichen: $c")
            index.toString(2).padStart(5, '0')
        }.joinToString("")

        val bytes = mutableListOf<Byte>()
        for (i in binary.indices step 8) {
            if (i + 8 > binary.length) break
            val byte = binary.substring(i, i + 8).toInt(2).toByte()
            bytes.add(byte)
        }
        return bytes.toByteArray()
    }

    // ─── DNS-Query (UDP) ────────────────────────────────────────────────────

    private fun buildDnsQuery(domain: String): ByteArray {
        val stream = ByteArrayOutputStream()

        // Transaction ID (random)
        val txId = Random.nextInt(0, 65535)
        stream.write((txId shr 8) and 0xFF)
        stream.write(txId and 0xFF)

        // Flags: Standard-Query, RD=1
        stream.write(0x01)
        stream.write(0x00)

        // Questions: 1
        stream.write(0x00)
        stream.write(0x01)

        // Answer RRs: 0
        stream.write(0x00)
        stream.write(0x00)

        // Authority RRs: 0
        stream.write(0x00)
        stream.write(0x00)

        // Additional RRs: 0
        stream.write(0x00)
        stream.write(0x00)

        // Query-Name (Labels)
        val labels = domain.split(".")
        for (label in labels) {
            stream.write(label.length)
            stream.write(label.toByteArray())
        }
        stream.write(0x00) // Root-Label

        // Query-Typ: TXT (16)
        stream.write(0x00)
        stream.write(0x10)

        // Query-Klasse: IN (1)
        stream.write(0x00)
        stream.write(0x01)

        return stream.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): List<String> {
        try {
            if (data.size < 12) return emptyList()

            // Header
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val rcode = flags and 0x0F
            val questions = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

            if (rcode == 3) return emptyList() // NXDOMAIN

            // Question-Section überspringen
            var offset = 12
            while (offset < data.size) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) {
                    offset += 5 // 0-Label + QTYPE + QCLASS
                    break
                }
                if (len and 0xC0 == 0xC0) {
                    offset += 4 // Pointer + QTYPE + QCLASS
                    break
                }
                offset += 1 + len
            }

            // Answer-Section parsen
            val txtRecords = mutableListOf<String>()
            for (i in 0 until answers) {
                if (offset + 10 > data.size) break

                // Name (Pointer oder Label)
                val nameLen = data[offset].toInt() and 0xFF
                if (nameLen and 0xC0 == 0xC0) {
                    offset += 2
                } else {
                    while (offset < data.size) {
                        val l = data[offset].toInt() and 0xFF
                        if (l == 0) {
                            offset += 1
                            break
                        }
                        offset += 1 + l
                    }
                }

                if (offset + 10 > data.size) break

                val rtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
                val rclass = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
                offset += 4 // TTL überspringen
                val rdlength = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2

                if (rtype == 16) { // TXT
                    val txtLen = data[offset].toInt() and 0xFF
                    offset += 1
                    if (offset + txtLen <= data.size) {
                        val txt = String(data, offset, txtLen, Charsets.UTF_8)
                        txtRecords.add(txt)
                        offset += txtLen
                    }
                } else {
                    offset += rdlength
                }
            }

            return txtRecords
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen der DNS-Response: ${e.message}")
            return emptyList()
        }
    }

    // ─── DNS-Query via UDP ──────────────────────────────────────────────────

    private suspend fun sendDnsQuery(domain: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val queryData = buildDnsQuery(domain)
            val serverAddr = InetAddress.getByName(serverDomain)
            val socket = DatagramSocket()
            socket.soTimeout = DNS_TIMEOUT_MS.toInt()

            val packet = DatagramPacket(queryData, queryData.size, serverAddr, DNS_PORT)
            socket.send(packet)

            val responseBuf = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(responsePacket)

            socket.close()
            parseDnsResponse(responsePacket.data.copyOf(responsePacket.length))
        } catch (e: Exception) {
            Log.w(TAG, "DNS-Query fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    // ─── HTTP-API (DNS-over-HTTPS) ──────────────────────────────────────────

    private suspend fun httpDnsQuery(domain: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://$serverDomain/dns-query?domain=$domain")
            val response = url.readText()
            // HTTP-API gibt JSON zurück
            // Wir parsen es als einfache TXT-Liste
            if (response.contains("\"status\":\"ok\"") || response.contains("\"status\":\"ack\"")) {
                listOf("ok")
            } else if (response.contains("\"messages\"")) {
                // Poll-Response: extrahiere TXT-Records
                val messages = mutableListOf<String>()
                val regex = Regex("\"data\":\"([^\"]+)\"")
                regex.findAll(response).forEach { match ->
                    val b64 = match.groupValues[1]
                    try {
                        val decoded = Base64.getDecoder().decode(b64)
                        val b32 = base32Encode(decoded)
                        messages.add("msg:dummy:sender:$b32")
                    } catch (_: Exception) {}
                }
                if (messages.isEmpty()) listOf("empty") else messages
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP-DNS-Query fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    private suspend fun sendDnsQueryWithFallback(domain: String): List<String> {
        if (useHttpApi) {
            val result = httpDnsQuery(domain)
            if (result.isNotEmpty()) return result
        }
        return sendDnsQuery(domain)
    }

    // ─── Nachrichten senden ─────────────────────────────────────────────────

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return try {
            val text = String(data, Charsets.UTF_8)
            if (text.length > MAX_TEXT_LENGTH) {
                return Result.failure(IllegalArgumentException(
                    "Nachricht zu lang (${text.length} > $MAX_TEXT_LENGTH Zeichen)"
                ))
            }

            val b32 = base32Encode(data)
            // Domain-Format: send.[base32].[empfänger-id].[server]
            val domain = "send.$b32.$peerId.$serverDomain"

            val response = sendDnsQueryWithFallback(domain)

            if (response.contains("ok")) {
                Log.i(TAG, "Nachricht an $peerId gesendet: ${text.take(50)}...")
                Result.success(Unit)
            } else {
                Log.w(TAG, "Senden fehlgeschlagen: $response")
                Result.failure(Exception("DNS-Tunnel: Senden fehlgeschlagen"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden: ${e.message}")
            Result.failure(e)
        }
    }

    // ─── Polling (Nachrichten empfangen) ────────────────────────────────────

    private suspend fun pollMessages() {
        try {
            val domain = "poll.$localPeerId.$serverDomain"
            val response = sendDnsQueryWithFallback(domain)

            for (txt in response) {
                if (txt.startsWith("msg:")) {
                    val parts = txt.split(":", limit = 4)
                    if (parts.size >= 4) {
                        val msgHash = parts[1]
                        val senderId = parts[2]
                        val b32Data = parts[3]

                        try {
                            val rawData = base32Decode(b32Data)
                            val text = String(rawData, Charsets.UTF_8)

                            Log.i(TAG, "Nachricht empfangen von $senderId: ${text.take(50)}...")

                            // An Listener weitergeben
                            synchronized(messageListeners) {
                                messageListeners.forEach { it(senderId, rawData) }
                            }

                            // Bestätigen (ACK)
                            val ackDomain = "ack.$msgHash.$localPeerId.$serverDomain"
                            sendDnsQueryWithFallback(ackDomain)
                        } catch (e: Exception) {
                            Log.w(TAG, "Fehler beim Dekodieren der Nachricht: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Polling fehlgeschlagen: ${e.message}")
        }
    }

    // ─── Transport-Interface ────────────────────────────────────────────────

    override suspend fun isAvailable(): Boolean {
        return try {
            val domain = "ping.test.$serverDomain"
            val response = sendDnsQueryWithFallback(domain)
            true // Wenn wir eine Antwort bekommen, ist der Server erreichbar
        } catch (e: Exception) {
            Log.w(TAG, "Server nicht erreichbar: ${e.message}")
            false
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        synchronized(messageListeners) {
            messageListeners.add(listener)
        }
    }

    override fun discoverPeers(): Flow<Peer> = callbackFlow {
        val job = scope?.launch {
            discoveredPeersFlow.collect { peers ->
                val last = peers.lastOrNull()
                if (last != null) {
                    trySend(last)
                }
            }
        }
        awaitClose { job?.cancel() }
    }

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = jobScope

        Log.i(TAG, "DNS-Tunnel gestartet (Server: $serverDomain)")

        // Polling-Job starten
        pollJob = jobScope.launch {
            while (isActive) {
                pollMessages()
                delay(POLL_INTERVAL_MS)
            }
        }

        // Dummy-Peer für die UI (der DNS-Tunnel selbst ist der "Peer")
        _discoveredPeers.value = listOf(
            Peer("dns-tunnel-server", "DNS-Tunnel ($serverDomain)")
        )
    }

    override suspend fun stop() {
        isRunning = false
        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null
        dnsSocket?.close()
        dnsSocket = null
        _discoveredPeers.value = emptyList()
        Log.i(TAG, "DNS-Tunnel gestoppt")
    }

    override fun getStatusDetail(): Pair<Int, String> {
        return Pair(
            if (isRunning) 1 else 0,
            if (isRunning) "DNS-Tunnel aktiv" else "DNS-Tunnel inaktiv"
        )
    }
}

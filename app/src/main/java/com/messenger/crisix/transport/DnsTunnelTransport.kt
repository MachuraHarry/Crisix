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

    // ─── Base32 (RFC 4648, kompatibel mit Python base64.b32encode) ──────────
    // Python verwendet: ABCDEFGHIJKLMNOPQRSTUVWXYZ234567
    // Wir verwenden lowercase für DNS-Sicherheit

    private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

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
            if (response.contains("\"status\": \"ok\"") || response.contains("\"status\":\"ok\"") ||
                response.contains("\"status\": \"ack\"") || response.contains("\"status\":\"ack\"")) {
                listOf("ok")
            } else if (response.contains("\"messages\"")) {
                // Poll-Response: extrahiere Nachrichten aus JSON
                // Server gibt: {"messages": [{"hash": "...", "sender": "...", "data": "<base64>"}]}
                val messages = mutableListOf<String>()
                // JSON manuell parsen: Array von Objekten mit hash, sender, data
                try {
                    // Einfaches JSON-Array-Parsing ohne org.json
                    // Python json.dumps() fügt Leerzeichen nach : ein: {"hash": "..."}
                    // Daher: optionale Leerzeichen \\s* nach jedem Doppelpunkt
                    val msgRegex = Regex("\\{\"hash\"\\s*:\\s*\"([^\"]+)\",\\s*\"sender\"\\s*:\\s*\"([^\"]+)\",\\s*\"data\"\\s*:\\s*\"([^\"]+)\"\\}")
                    msgRegex.findAll(response).forEach { match ->
                        val hash = match.groupValues[1]
                        val sender = match.groupValues[2]
                        val b64 = match.groupValues[3]
                        try {
                            val decoded = Base64.getDecoder().decode(b64)
                            val b32 = base32Encode(decoded)
                            messages.add("msg:$hash:$sender:$b32")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
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

    // ─── Test-Funktion ──────────────────────────────────────────────────────

    /**
     * Führt einen vollständigen DNS-Tunnel-Test durch:
     * 1. Health-Check: Server-Erreichbarkeit prüfen (HTTP /health)
     * 2. Send: Test-Nachricht an uns selbst senden
     * 3. Poll: Nachricht aus der Queue abholen
     * 4. Ack: Nachricht bestätigen
     *
     * @return Ein detaillierter Testbericht als String
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val testId = "test-${System.currentTimeMillis()}"
        val testMessage = "Crisix-DNS-Tunnel-Test-$testId"


        sb.appendLine("═══ DNS-Tunnel-Test ═══")
        sb.appendLine("Server: $serverDomain")
        sb.appendLine("Modus: ${if (useHttpApi) "HTTP-API" else "UDP-DNS"}")
        sb.appendLine("Test-ID: $testId")
        sb.appendLine()

        // === Schritt 1: Health-Check (Server-Erreichbarkeit) ===
        sb.appendLine("1️⃣ Health-Check (Server-Erreichbarkeit)...")
        try {
            val healthUrl = java.net.URL("https://$serverDomain/health")
            val conn = healthUrl.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val healthResponse = conn.inputStream.bufferedReader().readText()
                sb.appendLine("   ✅ Server antwortet (HTTP $responseCode): $healthResponse")
            } else {
                sb.appendLine("   ⚠️ Server antwortet mit HTTP $responseCode")
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Keine Fehlerdetails"
                sb.appendLine("   Fehler: $errorBody")
            }
            conn.disconnect()
        } catch (e: java.net.UnknownHostException) {
            sb.appendLine("   ❌ DNS-Auflösung fehlgeschlagen: ${e.message}")
            sb.appendLine("   ⚠️ Der Hostname '$serverDomain' kann nicht aufgelöst werden.")
            sb.appendLine("   ⚠️ Prüfe Internetverbindung im Emulator/Device.")
            sb.appendLine()
            sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══")
            return@withContext sb.toString()
        } catch (e: javax.net.ssl.SSLException) {
            sb.appendLine("   ❌ SSL-Fehler: ${e.message}")
            sb.appendLine("   ⚠️ Das SSL-Zertifikat des Servers konnte nicht verifiziert werden.")
            sb.appendLine()
            sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══")
            return@withContext sb.toString()
        } catch (e: java.net.SocketTimeoutException) {
            sb.appendLine("   ❌ Timeout: ${e.message}")
            sb.appendLine("   ⚠️ Server antwortet nicht innerhalb von 5 Sekunden.")
            sb.appendLine()
            sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══")
            return@withContext sb.toString()
        } catch (e: Exception) {
            sb.appendLine("   ❌ Health-Check fehlgeschlagen: ${e.message}")
            sb.appendLine("   📋 Typ: ${e.javaClass.simpleName}")
            sb.appendLine("   ⚠️ Server ist nicht erreichbar! Restlicher Test wird abgebrochen.")
            sb.appendLine()
            sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══")
            return@withContext sb.toString()

        }

        sb.appendLine()

        // === Schritt 2: Senden ===
        sb.appendLine("2️⃣ Senden (Nachricht an uns selbst)...")
        try {
            val b32 = base32Encode(testMessage.toByteArray())
            val sendDomain = "send.$b32.$localPeerId.$serverDomain"
            val sendResult = sendDnsQueryWithFallback(sendDomain)
            if (sendResult.any { it.contains("ok") }) {
                sb.appendLine("   ✅ Nachricht gesendet: \"${testMessage.take(50)}\"")
            } else {
                sb.appendLine("   ⚠️ Senden: Antwort: $sendResult")
            }
        } catch (e: Exception) {
            sb.appendLine("   ❌ Senden fehlgeschlagen: ${e.message}")
        }
        sb.appendLine()

        // === Schritt 3: Polling ===
        sb.appendLine("3️⃣ Polling (Nachricht abholen)...")
        try {
            // Kurz warten, damit der Server die Nachricht verarbeitet hat
            kotlinx.coroutines.delay(1000)
            val pollDomain = "poll.$localPeerId.$serverDomain"
            val pollResult = sendDnsQueryWithFallback(pollDomain)
            if (pollResult.any { it.startsWith("msg:") }) {
                sb.appendLine("   ✅ Nachricht empfangen!")
                pollResult.filter { it.startsWith("msg:") }.forEach { msg ->
                    val parts = msg.split(":", limit = 4)
                    if (parts.size >= 4) {
                        val senderId = parts[2]
                        val b32Data = parts[3]
                        try {
                            val decoded = base32Decode(b32Data)
                            val text = String(decoded, Charsets.UTF_8)
                            sb.appendLine("   📨 Von: $senderId")
                            sb.appendLine("   📝 Inhalt: \"$text\"")
                            if (text == testMessage) {
                                sb.appendLine("   ✅ Inhalt stimmt überein!")
                            } else {
                                sb.appendLine("   ⚠️ Inhalt weicht ab: erwartet \"$testMessage\"")
                            }
                        } catch (e: Exception) {
                            sb.appendLine("   ❌ Dekodierung fehlgeschlagen: ${e.message}")
                        }
                    }
                }
            } else {
                sb.appendLine("   ⚠️ Keine Nachrichten gefunden. Antwort: $pollResult")
            }
        } catch (e: Exception) {
            sb.appendLine("   ❌ Polling fehlgeschlagen: ${e.message}")
        }
        sb.appendLine()

        // === Schritt 4: ACK (Bestätigung) ===
        sb.appendLine("4️⃣ ACK (Nachricht bestätigen)...")
        try {
            // Die ACK wird bereits beim Polling automatisch gesendet,
            // daher prüfen wir nur, ob die Queue leer ist
            val pollDomain = "poll.$localPeerId.$serverDomain"
            val pollResult = sendDnsQueryWithFallback(pollDomain)
            if (pollResult.any { it == "empty" }) {
                sb.appendLine("   ✅ Nachricht erfolgreich bestätigt und gelöscht")
            } else {
                sb.appendLine("   ⚠️ Queue-Status: $pollResult")
            }
        } catch (e: Exception) {
            sb.appendLine("   ❌ ACK fehlgeschlagen: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("═══ Test abgeschlossen ═══")
        return@withContext sb.toString()
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
            // DNS-Domain max 253 Zeichen! Wir müssen kürzen.
            val suffix = ".$peerId.$serverDomain"
            val maxB32Length = 253 - suffix.length - 5 // 5 = "send."
            val truncatedB32 = if (b32.length > maxB32Length) {
                b32.take(maxB32Length)
            } else {
                b32
            }
            val domain = "send.$truncatedB32.$peerId.$serverDomain"

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
            // Health-Check via HTTP ist zuverlässiger als DNS-Query
            val healthUrl = URL("https://$serverDomain/health")
            val response = healthUrl.readText()
            response.contains("\"status\": \"ok\"") || response.contains("\"status\":\"ok\"")
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

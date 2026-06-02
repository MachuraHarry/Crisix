package com.messenger.crisix.transport

import android.util.Log
import com.messenger.crisix.ui.screens.InAppLogger
import kotlinx.coroutines.*
import timber.log.Timber
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * DNS-Tunnel-Transport für Crisix.
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
 * ## Chunking (für Nachrichten > DNS-Limit)
 *   Wenn die Nachricht nicht in eine einzelne DNS-Query passt,
 *   wird sie in Chunks gesplittet. Jeder Chunk hat einen minimalen
 *   Header mit Chunk-Metadaten. Der Empfänger setzt die Chunks
 *   wieder zusammen.
 *
 *   Header-Format (nach Dekompression):
 *     #<totalHex(2)><idxHex(2)><msgHashHex(8)>[,<uiMsgId>]\n<chunkData>
 *
 * ## JSON-Key-Minifizierung
 *   JSON-Keys werden vor dem Senden minimiert um DNS-Platz zu sparen:
 *     "type" → "t", "data" → "d", "sender" → "s", "timestamp" → "ts",
 *     "messageId" → "mid", "mime" → "m", "text" → "txt", "id" → "i",
 *     "name" → "n"
 *
 * ## Capabilities
 * - Nur Text (keine Medien)
 * - Kein File-Transfer
 * - Nicht gemetered
 */
class DnsTunnelTransport(
    private val localPeerId: String,
    private val serverDomain: String = "crisix-dns.onrender.com",
    private val useHttpApi: Boolean = true,
) : Transport {

    companion object {
        private const val TAG = "DnsTunnel"
        private const val MAX_TEXT_LENGTH = 200
        private const val POLL_INTERVAL_MS = 5000L
        private const val DNS_TIMEOUT_MS = 3000L
        private const val DNS_PORT = 8053
        private const val CHUNK_CLEANUP_INTERVAL_MS = 30_000L
    }

    override val type: TransportType = TransportType.DNS_TUNNEL
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 10000,
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsFileTransfer = false,
        isMetered = false,
        maxPayloadSize = 512,
        requiresProbing = false
    )

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val discoveredPeersFlow = _discoveredPeers.asStateFlow()

    private val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    private var isRunning = false
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var cleanupJob: Job? = null

    var onDeliveryAck: ((messageId: String, peerId: String) -> Unit)? = null

    private var dnsSocket: DatagramSocket? = null

    // ─── JSON-Key-Minifizierung ────────────────────────────────────────────

    private val longToShort = mapOf(
        "type" to "t",
        "data" to "d",
        "sender" to "s",
        "timestamp" to "ts",
        "messageId" to "mid",
        "mime" to "m",
        "text" to "txt",
        "id" to "i",
        "name" to "n"
    )

    private val shortToLong = longToShort.entries.associate { (k, v) -> v to k }

    private fun minifyJson(data: ByteArray): ByteArray {
        val text = String(data, Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) return data
        return try {
            val json = JSONObject(text)
            val minified = JSONObject()
            for (key in json.keys()) {
                val shortKey = longToShort[key] ?: key
                minified.put(shortKey, json.get(key))
            }
            minified.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "DNS tunnel JSON minify failed")
            data
        }
    }

    private fun expandJson(data: ByteArray): ByteArray {
        val text = String(data, Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) return data
        return try {
            val json = JSONObject(text)
            val expanded = JSONObject()
            for (key in json.keys()) {
                val longKey = shortToLong[key] ?: key
                expanded.put(longKey, json.get(key))
            }
            expanded.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "DNS tunnel JSON expand failed")
            data
        }
    }

    // ─── Chunk-Reassembly ───────────────────────────────────────────────────

    private data class ChunkMetadata(
        val totalChunks: Int,
        val chunkIndex: Int,
        val msgHash: String,
        val uiMessageId: String?
    )

    private val pendingChunks = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val chunkTimestamps = ConcurrentHashMap<String, Long>()

    private fun parseChunkHeader(data: ByteArray): Pair<ChunkMetadata, ByteArray>? {
        val text = String(data, Charsets.UTF_8)
        if (!text.startsWith("#")) return null
        val markerEnd = text.indexOf('\n')
        if (markerEnd < 0) return null
        val headerLine = text.substring(1, markerEnd)

        val totalHex = headerLine.substring(0, 2)
        val idxHex = headerLine.substring(2, 4)
        val hash = headerLine.substring(4, 12)

        val total = totalHex.toIntOrNull(16) ?: return null
        val idx = idxHex.toIntOrNull(16) ?: return null

        var uiMessageId: String? = null
        if (idx == 0 && headerLine.length > 13 && headerLine[12] == ',') {
            uiMessageId = headerLine.substring(13)
        }

        val chunkData = data.copyOfRange(markerEnd + 1, data.size)

        return ChunkMetadata(total, idx, hash, uiMessageId) to chunkData
    }

    private fun buildChunkHeader(total: Int, idx: Int, msgHash: String, uiMessageId: String?): String {
        val totalHex = total.toString(16).padStart(2, '0')
        val idxHex = idx.toString(16).padStart(2, '0')
        val header = "#$totalHex$idxHex$msgHash"
        return if (idx == 0 && uiMessageId != null) {
            "$header,$uiMessageId\n"
        } else {
            "$header\n"
        }
    }

    private fun generateMsgHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(data)
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun cleanupStaleChunks() {
        val now = System.currentTimeMillis()
        val stale = chunkTimestamps.filter { (now - it.value) > 120_000 }.keys
        for (key in stale) {
            pendingChunks.remove(key)
            chunkTimestamps.remove(key)
            InAppLogger.d(TAG, "Stale chunks aufgeräumt: $key")
        }
    }

    // ─── Base32 (RFC 4648) ─────────────────────────────────────────────────

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

    // ─── Gzip ───────────────────────────────────────────────────────────────

    private fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { gis ->
            val buffer = ByteArray(4096)
            var len: Int
            while (gis.read(buffer).also { len = it } != -1) {
                baos.write(buffer, 0, len)
            }
        }
        return baos.toByteArray()
    }

    // ─── DNS-Query (UDP) ────────────────────────────────────────────────────

    private fun buildDnsQuery(domain: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val txId = Random.nextInt(0, 65535)
        stream.write((txId shr 8) and 0xFF)
        stream.write(txId and 0xFF)
        stream.write(0x01)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x01)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x00)
        val labels = domain.split(".")
        for (label in labels) {
            stream.write(label.length)
            stream.write(label.toByteArray())
        }
        stream.write(0x00)
        stream.write(0x00)
        stream.write(0x10)
        stream.write(0x00)
        stream.write(0x01)
        return stream.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): List<String> {
        try {
            if (data.size < 12) return emptyList()
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val rcode = flags and 0x0F
            val questions = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (rcode == 3) return emptyList()
            var offset = 12
            while (offset < data.size) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) { offset += 5; break }
                if (len and 0xC0 == 0xC0) { offset += 4; break }
                offset += 1 + len
            }
            val txtRecords = mutableListOf<String>()
            for (i in 0 until answers) {
                if (offset + 10 > data.size) break
                val nameLen = data[offset].toInt() and 0xFF
                if (nameLen and 0xC0 == 0xC0) {
                    offset += 2
                } else {
                    while (offset < data.size) {
                        val l = data[offset].toInt() and 0xFF
                        if (l == 0) { offset += 1; break }
                        offset += 1 + l
                    }
                }
                if (offset + 10 > data.size) break
                val rtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
                offset += 2
                offset += 4
                val rdlength = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                offset += 2
                if (rtype == 16) {
                    val txtLen = data[offset].toInt() and 0xFF
                    offset += 1
                    if (offset + txtLen <= data.size) {
                        txtRecords.add(String(data, offset, txtLen, Charsets.UTF_8))
                        offset += txtLen
                    }
                } else {
                    offset += rdlength
                }
            }
            return txtRecords
        } catch (e: Exception) {
            InAppLogger.w(TAG, "Fehler beim Parsen der DNS-Response: ${e.message}")
            return emptyList()
        }
    }

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
            InAppLogger.w(TAG, "DNS-Query fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    private suspend fun httpDnsQuery(domain: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://$serverDomain/dns-query?domain=$domain")
            val response = url.readText()
            if (response.contains("\"status\": \"ok\"") || response.contains("\"status\":\"ok\"") ||
                response.contains("\"status\": \"ack\"") || response.contains("\"status\":\"ack\"")) {
                listOf("ok")
            } else if (response.contains("\"messages\"")) {
                val messages = mutableListOf<String>()
                try {
                    val msgRegex = Regex("\\{\"hash\"\\s*:\\s*\"([^\"]+)\",\\s*\"sender\"\\s*:\\s*\"([^\"]+)\",\\s*\"data\"\\s*:\\s*\"([^\"]+)\"\\}")
                    msgRegex.findAll(response).forEach { match ->
                        val hash = match.groupValues[1]
                        val sender = match.groupValues[2]
                        val b64 = match.groupValues[3]
                        try {
                            val decoded = Base64.getDecoder().decode(b64)
                            val b32 = base32Encode(decoded)
                            messages.add("msg:$hash:$sender:$b32")
                        } catch (e: Exception) { Log.w(TAG, "DNS operation failed: ${e.message}", e) }
                    }
                } catch (e: Exception) { Log.w(TAG, "DNS operation failed: ${e.message}", e) }
                if (messages.isEmpty()) listOf("empty") else messages
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            InAppLogger.w(TAG, "HTTP-DNS-Query fehlgeschlagen: ${e.message}")
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

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val testId = "test-${System.currentTimeMillis()}"
        val testMessage = "Crisix-DNS-Tunnel-Test-$testId"
        sb.appendLine("═══ DNS-Tunnel-Test ═══")
        sb.appendLine("Server: $serverDomain")
        sb.appendLine("Modus: ${if (useHttpApi) "HTTP-API" else "UDP-DNS"}")
        sb.appendLine("Test-ID: $testId")
        sb.appendLine()
        sb.appendLine("1️⃣ Health-Check (Server-Erreichbarkeit)...")
        try {
            val healthUrl = java.net.URL("https://$serverDomain/health")
            val conn = healthUrl.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                sb.appendLine("   ✅ Server antwortet (HTTP $responseCode): ${conn.inputStream.bufferedReader().readText()}")
            } else {
                sb.appendLine("   ⚠️ Server antwortet mit HTTP $responseCode")
                sb.appendLine("   Fehler: ${conn.errorStream?.bufferedReader()?.readText() ?: "Keine Fehlerdetails"}")
            }
            conn.disconnect()
        } catch (e: java.net.UnknownHostException) {
            sb.appendLine("   ❌ DNS-Auflösung fehlgeschlagen: ${e.message}")
            sb.appendLine("   ⚠️ Der Hostname '$serverDomain' kann nicht aufgelöst werden.")
            sb.appendLine(); sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══"); return@withContext sb.toString()
        } catch (e: javax.net.ssl.SSLException) {
            sb.appendLine("   ❌ SSL-Fehler: ${e.message}")
            sb.appendLine("   ⚠️ Das SSL-Zertifikat des Servers konnte nicht verifiziert werden.")
            sb.appendLine(); sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══"); return@withContext sb.toString()
        } catch (e: java.net.SocketTimeoutException) {
            sb.appendLine("   ❌ Timeout: ${e.message}")
            sb.appendLine("   ⚠️ Server antwortet nicht innerhalb von 5 Sekunden.")
            sb.appendLine(); sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══"); return@withContext sb.toString()
        } catch (e: Exception) {
            sb.appendLine("   ❌ Health-Check fehlgeschlagen: ${e.message}")
            sb.appendLine("   📋 Typ: ${e.javaClass.simpleName}")
            sb.appendLine("   ⚠️ Server ist nicht erreichbar! Restlicher Test wird abgebrochen.")
            sb.appendLine(); sb.appendLine("═══ Test abgeschlossen (fehlgeschlagen) ═══"); return@withContext sb.toString()
        }
        sb.appendLine()
        sb.appendLine("2️⃣ Senden (Nachricht an uns selbst)...")
        try {
            val b32 = base32Encode(testMessage.toByteArray())
            val sendResult = sendDnsQueryWithFallback("send.$b32.$localPeerId.$serverDomain")
            if (sendResult.any { it.contains("ok") }) sb.appendLine("   ✅ Nachricht gesendet: \"${testMessage.take(50)}\"")
            else sb.appendLine("   ⚠️ Senden: Antwort: $sendResult")
        } catch (e: Exception) { sb.appendLine("   ❌ Senden fehlgeschlagen: ${e.message}") }
        sb.appendLine()
        sb.appendLine("3️⃣ Polling (Nachricht abholen)...")
        try {
            delay(1000)
            val pollResult = sendDnsQueryWithFallback("poll.$localPeerId.$serverDomain")
            if (pollResult.any { it.startsWith("msg:") }) {
                sb.appendLine("   ✅ Nachricht empfangen!")
                pollResult.filter { it.startsWith("msg:") }.forEach { msg ->
                    val parts = msg.split(":", limit = 4)
                    if (parts.size >= 4) {
                        val senderId = parts[2]; val b32Data = parts[3]
                        try {
                            val decoded = base32Decode(b32Data)
                            val text = String(decoded, Charsets.UTF_8)
                            sb.appendLine("   📨 Von: $senderId")
                            sb.appendLine("   📝 Inhalt: \"$text\"")
                            if (text == testMessage) sb.appendLine("   ✅ Inhalt stimmt überein!")
                            else sb.appendLine("   ⚠️ Inhalt weicht ab: erwartet \"$testMessage\"")
                        } catch (e: Exception) { sb.appendLine("   ❌ Dekodierung fehlgeschlagen: ${e.message}") }
                    }
                }
            } else { sb.appendLine("   ⚠️ Keine Nachrichten gefunden. Antwort: $pollResult") }
        } catch (e: Exception) { sb.appendLine("   ❌ Polling fehlgeschlagen: ${e.message}") }
        sb.appendLine()
        sb.appendLine("4️⃣ ACK (Nachricht bestätigen)...")
        try {
            val pollResult = sendDnsQueryWithFallback("poll.$localPeerId.$serverDomain")
            if (pollResult.any { it == "empty" }) sb.appendLine("   ✅ Nachricht erfolgreich bestätigt und gelöscht")
            else sb.appendLine("   ⚠️ Queue-Status: $pollResult")
        } catch (e: Exception) { sb.appendLine("   ❌ ACK fehlgeschlagen: ${e.message}") }
        sb.appendLine()
        sb.appendLine("═══ Test abgeschlossen ═══")
        return@withContext sb.toString()
    }

    // ─── Nachrichten senden ─────────────────────────────────────────────────

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return try {
            val dataStr = String(data, Charsets.UTF_8)

            // \u0000-uiMessageId-Suffix extrahieren (für ACK-Tracking)
            val uiMessageId = if (dataStr.contains('\u0000')) {
                dataStr.substringAfter('\u0000')
            } else null
            val payloadData = if (dataStr.contains('\u0000')) {
                dataStr.substringBefore('\u0000').toByteArray(Charsets.UTF_8)
            } else {
                data
            }

            // JSON-Keys minimieren
            val minified = minifyJson(payloadData)

            // SenderId + Nutzdaten
            val withSender = "$localPeerId\n".toByteArray(Charsets.UTF_8) + minified

            // Gzip + Base32
            val compressed = gzipCompress(withSender)
            val b32 = base32Encode(compressed)
            InAppLogger.d(TAG, "Kompression: ${withSender.size} → ${compressed.size} bytes")

            val suffix = ".$peerId.$serverDomain"
            val maxB32Length = 253 - suffix.length - 5

            if (b32.length <= maxB32Length) {
                // ── Einzel-Query ──
                val domain = "send.$b32.$peerId.$serverDomain"
                val response = sendDnsQueryWithFallback(domain)
                if (response.contains("ok")) {
                    InAppLogger.i(TAG, "Nachricht an $peerId gesendet (${compressed.size}B)")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("DNS-Tunnel: Senden fehlgeschlagen"))
                }
            } else {
                // ── Chunked Send ──
                sendChunked(peerId, withSender, uiMessageId, maxB32Length)
            }
        } catch (e: Exception) {
            InAppLogger.e(TAG, "Fehler beim Senden: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun sendChunked(
        peerId: String,
        minifiedData: ByteArray,
        uiMessageId: String?,
        maxB32Length: Int
    ): Result<Unit> {
        val msgHash = generateMsgHash(minifiedData)

        // Dynamische Chunk-Größe: Max Bytes die in eine DNS-Query passen
        // Base32 expandiert 5 Bit → 8 Bit, also maxBytes = floor(maxB32Length * 5 / 8)
        // Davon den Header-Overhead abziehen (größter für Chunk 0 mit uiMessageId)
        val maxPayloadBytes = maxB32Length * 5 / 8
        val uiMsgIdLen = uiMessageId?.length ?: 0
        val headerOverhead = 1 + 2 + 2 + 8 + 1 + uiMsgIdLen + 1
        val chunkDataSize = maxOf(1, maxPayloadBytes - headerOverhead)

        val totalChunks = ceilDiv(minifiedData.size, chunkDataSize)

        InAppLogger.i(TAG, "Sende $totalChunks Chunks (${minifiedData.size}B total, msgHash=$msgHash, chunkSize=$chunkDataSize)")

        for (idx in 0 until totalChunks) {
            val start = idx * chunkDataSize
            val end = minOf(start + chunkDataSize, minifiedData.size)
            val chunkPart = minifiedData.copyOfRange(start, end)

            val header = buildChunkHeader(totalChunks, idx, msgHash, if (idx == 0) uiMessageId else null)
            val chunkPayload = header.toByteArray(Charsets.UTF_8) + chunkPart

            // Base32 (ohne Gzip – zu kleiner Nutzen für kleine Chunks)
            val b32 = base32Encode(chunkPayload)

            if (b32.length > maxB32Length) {
                InAppLogger.w(TAG, "Chunk $idx/${totalChunks - 1} zu groß (${b32.length}B > ${maxB32Length}B)")
                return Result.failure(IllegalArgumentException(
                    "Chunk $idx zu groß (${b32.length} > $maxB32Length Base32)"
                ))
            }

            val domain = "send.$b32.$peerId.$serverDomain"
            val response = sendDnsQueryWithFallback(domain)

            if (!response.contains("ok")) {
                InAppLogger.w(TAG, "Chunk $idx/${totalChunks - 1} fehlgeschlagen: $response")
                return Result.failure(Exception("DNS-Tunnel: Chunk $idx fehlgeschlagen"))
            }

            InAppLogger.d(TAG, "Chunk $idx/${totalChunks - 1} gesendet (${chunkPart.size}B)")
        }

        InAppLogger.i(TAG, "Alle $totalChunks Chunks an $peerId gesendet")
        return Result.success(Unit)
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    // ─── Polling (Nachrichten empfangen) ────────────────────────────────────

    private suspend fun pollMessages() {
        try {
            val domain = "poll.$localPeerId.$serverDomain"
            val response = sendDnsQueryWithFallback(domain)

            for (txt in response) {
                if (txt.startsWith("msg:")) {
                    val parts = txt.split(":", limit = 4)
                    if (parts.size >= 4) {
                        val senderId = parts[2]
                        val b32Data = parts[3]

                        try {
                            val compressedData = base32Decode(b32Data)
                            val rawData = try {
                                gzipDecompress(compressedData)
                            } catch (e: Exception) {
                                Timber.e(e, "DNS tunnel gzip decompress failed")
                                compressedData
                            }

                            val rawText = String(rawData, Charsets.UTF_8)

                            // Chunk-Detection: Daten beginnen mit '#'
                            val isChunked = rawText.startsWith("#")
                            val actualSenderId = if (isChunked) senderId else {
                                val sep = rawText.indexOf('\n')
                                if (sep > 0) rawText.substring(0, sep) else senderId
                            }
                            val actualData = if (isChunked) {
                                rawData
                            } else {
                                val sep = rawText.indexOf('\n')
                                if (sep > 0) rawText.substring(sep + 1).toByteArray(Charsets.UTF_8) else rawData
                            }

                            if (isChunked) {
                                handleChunkedMessage(actualSenderId, actualData)
                            } else {
                                handleSingleMessage(actualSenderId, actualData, rawData)
                            }

                            // Server-ACK (Nachricht aus Queue löschen)
                            val ackDomain = "ack.${parts[1]}.$localPeerId.$serverDomain"
                            sendDnsQueryWithFallback(ackDomain)
                        } catch (e: Exception) {
                            InAppLogger.w(TAG, "Fehler beim Dekodieren: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            InAppLogger.w(TAG, "Polling fehlgeschlagen: ${e.message}")
        }
    }

    private fun handleChunkedMessage(senderId: String, data: ByteArray) {
        val parsed = parseChunkHeader(data) ?: return
        val (meta, chunkData) = parsed

        val groupKey = "${senderId}_${meta.msgHash}"
        val chunks = pendingChunks.getOrPut(groupKey) { mutableMapOf() }
        chunks[meta.chunkIndex] = chunkData
        chunkTimestamps[groupKey] = System.currentTimeMillis()

        InAppLogger.d(TAG, "Chunk ${meta.chunkIndex + 1}/${meta.totalChunks} empfangen ($groupKey)")

        if (chunks.size >= meta.totalChunks) {
            val reassembled = ByteArrayOutputStream()
            for (i in 0 until meta.totalChunks) {
                chunks[i]?.let { reassembled.write(it) }
                    ?: run {
                        InAppLogger.w(TAG, "Chunk $i fehlt für $groupKey – Abbruch")
                        return
                    }
            }

            pendingChunks.remove(groupKey)
            chunkTimestamps.remove(groupKey)
            val completeData = reassembled.toByteArray()
            InAppLogger.i(TAG, "Nachricht reassembliert (${completeData.size}B) für $groupKey")

            // Sender aus reassemblierten Daten extrahieren (erste Zeile vor \n)
            val fullText = String(completeData, Charsets.UTF_8)
            val sep = fullText.indexOf('\n')
            val actualSender = if (sep > 0) fullText.substring(0, sep) else senderId
            val payloadData = if (sep > 0) {
                fullText.substring(sep + 1).toByteArray(Charsets.UTF_8)
            } else {
                completeData
            }

            InAppLogger.d(TAG, "Sender extrahiert: $actualSender (war: $senderId)")

            // JSON-Keys expandieren
            val expanded = expandJson(payloadData)

            // uiMessageId re-anhängen
            val finalData = if (meta.uiMessageId != null) {
                val str = String(expanded, Charsets.UTF_8)
                (str + '\u0000' + meta.uiMessageId).toByteArray(Charsets.UTF_8)
            } else {
                expanded
            }

            // Als normale Nachricht verarbeiten
            handleSingleMessage(actualSender, finalData, finalData)
        }
    }

    private fun handleSingleMessage(senderId: String, displayData: ByteArray, rawData: ByteArray) {
        val dataStr = String(displayData, Charsets.UTF_8)

        // JSON-Keys expandieren (falls noch minimiert)
        val expandedData = expandJson(displayData)
        val expandedStr = String(expandedData, Charsets.UTF_8)

        if (dataStr.startsWith("__ACK__:")) {
            val ackedMsgId = dataStr.removePrefix("__ACK__:")
            onDeliveryAck?.invoke(ackedMsgId, senderId)
            InAppLogger.i(TAG, "ACK empfangen von $senderId für Nachricht $ackedMsgId")
            return
        }

        // \x00-uiMessageId-Suffix behandeln
        val messageData = if (expandedStr.contains('\u0000')) {
            expandedStr.substringBefore('\u0000').toByteArray(Charsets.UTF_8)
        } else {
            expandedData
        }
        val uiMessageId = if (expandedStr.contains('\u0000')) {
            expandedStr.substringAfter('\u0000')
        } else null

        val messageStr = String(messageData, Charsets.UTF_8)
        InAppLogger.i(TAG, "Nachricht empfangen von ${senderId.take(8)}: ${messageStr.take(50)}")

        // Ping/Pong/ACK erkennen
        var isInternal = false
        try {
            val json = JSONObject(messageStr)
            when (json.optString("type")) {
                "crisix_ping" -> {
                    InAppLogger.d(TAG, "Ping empfangen von ${senderId.take(8)}")
                    val pong = JSONObject().apply {
                        put("type", "crisix_pong")
                        put("id", json.getString("id"))
                    }.toString().toByteArray(Charsets.UTF_8)
                    scope?.launch {
                        try {
                            send(senderId, pong)
                            InAppLogger.d(TAG, "Pong versendet an ${senderId.take(8)}")
                        } catch (e: Exception) {
                            InAppLogger.w(TAG, "Pong-Sendung fehlgeschlagen: ${e.message}")
                        }
                    }
                    isInternal = true
                }
                "crisix_pong" -> {
                    InAppLogger.d(TAG, "Pong empfangen von ${senderId.take(8)}")
                    isInternal = true
                }
                "crisix_ack" -> {
                    InAppLogger.d(TAG, "ACK empfangen von ${senderId.take(8)}")
                    isInternal = true
                }
            }
        } catch (e: Exception) { Log.w(TAG, "DNS operation failed: ${e.message}", e) }

        if (!isInternal) {
            synchronized(messageListeners) {
                messageListeners.forEach { it(senderId, messageData) }
            }
        }

        // Auto-ACK an Sender
        if (uiMessageId != null && !isInternal) {
            scope?.launch {
                try {
                    send(senderId, "__ACK__:$uiMessageId".toByteArray(Charsets.UTF_8))
                    InAppLogger.i(TAG, "Auto-ACK gesendet an ${senderId.take(8)} für $uiMessageId")
                } catch (e: Exception) {
                    InAppLogger.w(TAG, "Auto-ACK fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    // ─── Transport-Interface ────────────────────────────────────────────────

    override suspend fun isAvailable(): Boolean {
        return try {
            val healthUrl = URL("https://$serverDomain/health")
            val response = healthUrl.readText()
            response.contains("\"status\": \"ok\"") || response.contains("\"status\":\"ok\"")
        } catch (e: Exception) {
            InAppLogger.w(TAG, "Server nicht erreichbar: ${e.message}")
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
                if (last != null) trySend(last)
            }
        }
        awaitClose { job?.cancel() }
    }

    override suspend fun start() {
        if (isRunning) return
        isRunning = true
        val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = jobScope
        InAppLogger.i(TAG, "DNS-Tunnel gestartet (Server: $serverDomain)")

        pollJob = jobScope.launch {
            while (isActive) {
                pollMessages()
                delay(POLL_INTERVAL_MS)
            }
        }

        cleanupJob = jobScope.launch {
            while (isActive) {
                delay(CHUNK_CLEANUP_INTERVAL_MS)
                cleanupStaleChunks()
            }
        }

        _discoveredPeers.value = listOf(
            Peer("dns-tunnel-server", "DNS-Tunnel ($serverDomain)")
        )
    }

    override suspend fun stop() {
        isRunning = false
        pollJob?.cancel()
        pollJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        scope?.cancel()
        scope = null
        dnsSocket?.close()
        dnsSocket = null
        pendingChunks.clear()
        chunkTimestamps.clear()
        _discoveredPeers.value = emptyList()
        InAppLogger.i(TAG, "DNS-Tunnel gestoppt")
    }

    override fun getStatusDetail(): Pair<Int, String> {
        return Pair(
            if (isRunning) 1 else 0,
            if (isRunning) "DNS-Tunnel aktiv" else "DNS-Tunnel inaktiv"
        )
    }
}

package com.messenger.crisix.transport

import android.util.Log
import timber.log.Timber
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RelayTransport(
    private val localPeerId: String,
    relayUrlsArg: List<String> = listOf("wss://crisix-dns.onrender.com/ws")
) : Transport {

    @Volatile
    private var relayUrls: List<String> = relayUrlsArg

    companion object {
        private const val TAG = "RelayTransport"
    }

    override val type: TransportType = TransportType.RELAY
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = Int.MAX_VALUE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false
    )

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    private val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()

    var onDeliveryAck: ((messageId: String, peerId: String) -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isConnected = false

    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var reconnecting = false
    private val maxReconnectDelay = 30_000L
    private val baseReconnectDelay = 1_000L
    private val reconnectMutex = Any()

    @Volatile
    private var activeUrlIndex = 0

    @Volatile
    private var failedUrlIndex = -1

    fun updateUrls(newUrls: List<String>) {
        relayUrls = if (newUrls.isEmpty()) listOf("wss://crisix-dns.onrender.com/ws") else newUrls
        activeUrlIndex = 0
        failedUrlIndex = -1
        reconnectAttempt = 0
    }

    private fun getActiveUrl(): String {
        val idx = activeUrlIndex.coerceIn(0, relayUrls.lastIndex)
        return relayUrls[idx]
    }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            for ((index, url) in relayUrls.withIndex()) {
                try {
                    val baseUrl = url
                        .substringBeforeLast("/")
                        .replace("wss://", "https://")
                        .replace("ws://", "http://")
                    val client = OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder().url("$baseUrl/health").build()
                    val success = client.newCall(request).execute().use { it.isSuccessful }
                    if (success) {
                        if (index < activeUrlIndex) {
                            Log.i(TAG, "Höher priorisierter Server verfügbar: Index $index")
                        }
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Relay-Server $index nicht erreichbar: ${e.message}")
                }
            }
            false
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        val ws = webSocket
        if (ws == null || !isConnected) {
            return Result.failure(Exception("Relay nicht verbunden"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val b64 = Base64.getEncoder().encodeToString(data)
                if (ws.send("SEND:$peerId:$b64")) {
                    Result.success(Unit)
                } else {
                    isConnected = false
                    Result.failure(Exception("WebSocket send returned false"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay send fehlgeschlagen: ${e.message}")
                isConnected = false
                Result.failure(e)
            }
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        messageListeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = callbackFlow {
        val job = scope?.launch {
            _discoveredPeers.collect { peers ->
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

        connect()
        startReconnectLoop()
        startKeepalive()

        Log.i(TAG, "RelayTransport gestartet (${relayUrls.size} Server, aktiv: ${getActiveUrl()})")
    }

    private suspend fun connect() {
        synchronized(reconnectMutex) {
            if (reconnecting) {
                Log.i(TAG, "Reconnect läuft bereits, überspringe")
                return
            }
            reconnecting = true
        }

        val urls = relayUrls.toList()
        var connectedSuccessfully = false

        for (i in activeUrlIndex until urls.size) {
            val url = urls[i]
            try {
                connectToUrl(url, i)
                activeUrlIndex = i
                failedUrlIndex = -1
                connectedSuccessfully = true
                break
            } catch (e: Exception) {
                Log.e(TAG, "Relay-Verbindung fehlgeschlagen für Server $i ($url): ${e.message}")
                failedUrlIndex = i
            }
        }

        if (!connectedSuccessfully) {
            isConnected = false
            webSocket = null
            reconnecting = false
            activeUrlIndex = 0
        }
    }

    private suspend fun connectToUrl(url: String, index: Int) {
        val connected = CompletableDeferred<Result<Unit>>()

        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        okHttpClient = client

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("REGISTER:$localPeerId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text == "OK:registered" -> {
                        this@RelayTransport.webSocket = webSocket
                        isConnected = true
                        reconnectAttempt = 0
                        reconnecting = false
                        _discoveredPeers.value = listOf(
                            Peer("relay-server", "Relay")
                        )
                        if (!connected.isCompleted) {
                            connected.complete(Result.success(Unit))
                        }
                    }

                    text.startsWith("FROM:") -> {
                        val rest = text.substring("FROM:".length)
                        val sep = rest.indexOf(":")
                        if (sep < 0) return

                        val senderPeerId = rest.substring(0, sep)
                        val b64Data = rest.substring(sep + 1)

                        try {
                            val data = Base64.getDecoder().decode(b64Data)
                            Log.i(TAG, "Nachricht empfangen von $senderPeerId (${data.size} Bytes)")

                            val messageText = try { String(data) } catch (e: Exception) { Timber.e(e, "Relay data string conversion failed"); null }
                            var isInternal = false
                            if (messageText != null) {
                                try {
                                    val json = org.json.JSONObject(messageText)
                                    when (json.optString("type")) {
                                        "crisix_ack" -> {
                                            val messageId = json.optString("messageId", "")
                                            if (messageId.isNotEmpty()) {
                                                onDeliveryAck?.invoke(messageId, senderPeerId)
                                                Log.i(TAG, "[RelayTransport] ACK empfangen für $messageId von $senderPeerId")
                                                isInternal = true
                                            }
                                        }
                                        "crisix_ping" -> {
                                            Log.d(TAG, "[RelayTransport] Ping empfangen von ${senderPeerId.take(8)}")
                                            val pongPayload = org.json.JSONObject().apply {
                                                put("type", "crisix_pong")
                                                put("id", json.getString("id"))
                                            }.toString().toByteArray()
                                            scope?.launch {
                                                try {
                                                    send(senderPeerId, pongPayload)
                                                    Log.d(TAG, "[RelayTransport] Pong versendet an ${senderPeerId.take(8)}")
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "[RelayTransport] Pong-Sendung fehlgeschlagen: ${e.message}")
                                                }
                                            }
                                            isInternal = true
                                        }
                                        "crisix_pong" -> {
                                            Log.d(TAG, "[RelayTransport] Pong empfangen von ${senderPeerId.take(8)}")
                                            isInternal = true
                                        }
                                    }
                                } catch (e: Exception) { Log.w(TAG, "RelayTransport operation failed: ${e.message}", e) }
                            }

                            if (!isInternal) {
                                messageListeners.forEach { it(senderPeerId, data) }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Base64-Decode fehlgeschlagen: ${e.message}")
                        }
                    }

                    text.startsWith("ERROR:") -> {
                        Log.w(TAG, "Relay-Fehler: $text")
                        if (!connected.isCompleted) {
                            connected.complete(Result.failure(Exception(text)))
                        }
                    }

                    text == "OK:sent" -> {
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket-Fehler (Server $index): ${t.message}")
                isConnected = false
                this@RelayTransport.webSocket = null
                if (!connected.isCompleted) {
                    connected.complete(Result.failure(t))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket geschlossen (Server $index): $code $reason")
                isConnected = false
                this@RelayTransport.webSocket = null
            }
        }

        client.newWebSocket(request, listener)

        val result = connected.await()
        if (result.isFailure) {
            reconnecting = false
            throw result.exceptionOrNull() ?: Exception("Unknown error")
        }
    }

    private fun startReconnectLoop() {
        reconnectJob = scope?.launch {
            while (isActive && isRunning) {
                delay(5_000)
                if (!isConnected && !reconnecting) {
                    reconnectAttempt++
                    val delay = minOf(baseReconnectDelay * (1L shl (reconnectAttempt - 1)), maxReconnectDelay)
                    Log.i(TAG, "Reconnect-Loop: Versuch $reconnectAttempt in ${delay}ms")
                    delay(delay)
                    if (isRunning && !isConnected && !reconnecting) {
                        connect()
                    }
                }
            }
        }
    }

    private fun startKeepalive() {
        keepaliveJob = scope?.launch {
            while (isActive && isRunning) {
                delay(30_000)
                if (isConnected) {
                    try {
                        webSocket?.send("KEEPALIVE:ping")
                    } catch (e: Exception) { Log.w(TAG, "RelayTransport operation failed: ${e.message}", e) }
                }
            }
        }
    }

    override suspend fun stop() {
        isRunning = false
        reconnectJob?.cancel()
        reconnectJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        webSocket?.close(1000, "App stopping")
        webSocket = null
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient = null
        isConnected = false
        reconnecting = false
        reconnectAttempt = 0
        activeUrlIndex = 0
        scope?.cancel()
        scope = null
        _discoveredPeers.value = emptyList()
        Log.i(TAG, "RelayTransport gestoppt")
    }

    override fun getStatusDetail(): Pair<Int, String> {
        val activeUrl = if (relayUrls.isNotEmpty()) relayUrls.getOrElse(activeUrlIndex) { relayUrls.first() } else "none"
        return Pair(
            if (isConnected) 1 else 0,
            if (isConnected) "Relay verbunden (Server ${activeUrlIndex + 1}/${relayUrls.size})"
            else "Relay getrennt"
        )
    }
}

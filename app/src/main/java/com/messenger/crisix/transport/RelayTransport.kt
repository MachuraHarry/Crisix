package com.messenger.crisix.transport

import android.util.Log
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
    private val relayUrl: String = "wss://crisix-dns.onrender.com/ws"
) : Transport {

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
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isConnected = false

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = relayUrl
                    .substringBeforeLast("/")
                    .replace("wss://", "https://")
                    .replace("ws://", "http://")
                val client = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url("$baseUrl/health").build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "Relay-Server nicht erreichbar: ${e.message}")
                false
            }
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

        Log.i(TAG, "RelayTransport gestartet ($relayUrl)")
    }

    private suspend fun connect() {
        try {
            val connected = CompletableDeferred<Result<Unit>>()

            okHttpClient?.dispatcher?.executorService?.shutdown()
            val client = OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            okHttpClient = client

            val request = Request.Builder().url(relayUrl).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("REGISTER:$localPeerId")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        text == "OK:registered" -> {
                            this@RelayTransport.webSocket = webSocket
                            isConnected = true
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
                                messageListeners.forEach { it(senderPeerId, data) }
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
                            // Bestätigung für gesendete Nachricht
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket-Fehler: ${t.message}")
                    isConnected = false
                    this@RelayTransport.webSocket = null
                    if (!connected.isCompleted) {
                        connected.complete(Result.failure(t))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket geschlossen: $code $reason")
                    isConnected = false
                    this@RelayTransport.webSocket = null
                }
            }

            client.newWebSocket(request, listener)

            val result = connected.await()
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Unknown error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Relay-Verbindung fehlgeschlagen: ${e.message}")
            isConnected = false
            webSocket = null
        }
    }

    private fun startReconnectLoop() {
        reconnectJob = scope?.launch {
            while (isActive && isRunning) {
                delay(15_000)
                if (!isConnected) {
                    Log.i(TAG, "Reconnect zum Relay...")
                    connect()
                }
            }
        }
    }

    override suspend fun stop() {
        isRunning = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "App stopping")
        webSocket = null
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient = null
        isConnected = false
        scope?.cancel()
        scope = null
        _discoveredPeers.value = emptyList()
        Log.i(TAG, "RelayTransport gestoppt")
    }

    override fun getStatusDetail(): Pair<Int, String> {
        return Pair(
            if (isConnected) 1 else 0,
            if (isConnected) "Relay verbunden" else "Relay getrennt"
        )
    }
}

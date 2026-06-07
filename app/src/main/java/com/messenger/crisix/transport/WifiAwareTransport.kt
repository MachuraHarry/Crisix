package com.messenger.crisix.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class WifiAwareTransport(
    private val localPeerId: String,
    private val deviceName: String,
    private val appContext: Context,
    private val serviceName: String = "crisix-nan",
    private val tcpPort: Int = 54231
) : Transport {

    companion object {
        private const val TAG = "WifiAwareTransport"
        private const val ICM_MAX_SIZE = 2048
        private const val NETWORK_PASSPHRASE = "crisix-nan-session"
        private const val MAX_ATTACH_RETRIES = 3
    }

    override val type: TransportType = TransportType.WIFI_AWARE
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = ICM_MAX_SIZE,
        supportsImages = true,
        supportsVideo = true,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var wifiAwareManager: WifiAwareManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: android.net.wifi.aware.PublishDiscoverySession? = null
    private var subscribeSession: android.net.wifi.aware.SubscribeDiscoverySession? = null

    @Volatile private var isRunning = false
    @Volatile private var isAdvertising = false
    @Volatile private var isDiscovering = false
    private var attachRetries = 0

    // Falls NAN-Hardware nicht verfügbar → gesamten Transport als unavailable markieren
    @Volatile private var nanHardwareFailed = false

    private val connectedPeers = ConcurrentHashMap<String, Peer>()
    private val peerHandles = ConcurrentHashMap<String, PeerHandle>()
    private val handleToPeerId = ConcurrentHashMap<Int, String>()
    private var nextHandleId = AtomicInteger(1)

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    var onDeliveryAck: ((messageId: String, peerId: String) -> Unit)? = null

    // ICM pending messages: messageId -> (peerId, deferred result)
    private data class PendingIcmSend(
        val peerId: String,
        val handle: PeerHandle
    )
    private val pendingIcmSends = ConcurrentHashMap<Int, PendingIcmSend>()
    private var icmMessageIdCounter = AtomicInteger(0)

    // TCP server for large payloads over NAN
    @Volatile private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // TCP connections over NAN
    private val tcpSockets = ConcurrentHashMap<String, Socket>()
    private val pendingNetworks = ConcurrentHashMap<String, Job>()
    private val nanIpAddresses = ConcurrentHashMap<String, InetAddress>()

    // ConnectivityManager for NAN network setup
    private val connectivityManager: ConnectivityManager? by lazy {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    // ─── NAN Callbacks ─────────────────────────────────────────────────

    private val attachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            awareSession = session
            attachRetries = 0
            nanHardwareFailed = false
            Log.i(TAG, "NAN Session attached")
            startDiscoverySessions()
        }

        override fun onAttachFailed() {
            attachRetries++
            Log.w(TAG, "NAN Attach failed (attempt $attachRetries/$MAX_ATTACH_RETRIES)")
            if (attachRetries >= MAX_ATTACH_RETRIES) {
                Log.w(TAG, "NAN hardware not available — giving up after $MAX_ATTACH_RETRIES attempts")
                nanHardwareFailed = true
                return
            }
            scope.launch {
                if (isRunning) {
                    kotlinx.coroutines.delay(10_000)
                    attachToWifiAware()
                }
            }
        }
    }

    private val publishDiscoveryCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: android.net.wifi.aware.PublishDiscoverySession) {
            publishSession = session
            isAdvertising = true
            Log.i(TAG, "NAN Publish started")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            handleIcmMessage(peerHandle, message)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            Log.d(TAG, "ICM send succeeded: $messageId")
        }

        override fun onMessageSendFailed(messageId: Int) {
            val pending = pendingIcmSends.remove(messageId)
            if (pending != null) {
                Log.w(TAG, "ICM send failed for $messageId to ${pending.peerId}, falling back to TCP")
                val undelivered = pendingIcmSends.remove(messageId)
            }
            Log.w(TAG, "ICM send failed: $messageId")
        }
    }

    private val subscribeDiscoveryCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: android.net.wifi.aware.SubscribeDiscoverySession) {
            subscribeSession = session
            isDiscovering = true
            Log.i(TAG, "NAN Subscribe started")
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: List<ByteArray>?
        ) {
            val rawInfo = serviceSpecificInfo ?: return
            var remoteId: String? = null
            var remoteName: String? = null

            try {
                val infoText = String(rawInfo)
                val infoJson = JSONObject(infoText)
                remoteId = infoJson.optString("id", null)
                remoteName = infoJson.optString("n", null)
            } catch (e: Exception) {
                remoteId = String(rawInfo)
            }
            if (remoteId == null) return

            if (remoteId == localPeerId) {
                Log.d(TAG, "Ignoring self-discovery")
                return
            }

            val handleId = nextHandleId.getAndIncrement()
            handleToPeerId[handleId] = remoteId
            peerHandles[remoteId] = peerHandle
            val peerName = remoteName ?: remoteId.take(8)

            val existing = connectedPeers[remoteId]
            if (existing != null) {
                Log.d(TAG, "Peer $remoteId already known, updating handle")
                if (remoteName != null) {
                    connectedPeers[remoteId] = existing.copy(name = remoteName)
                }
                // Establish TCP network for large payloads
                establishNanNetwork(peerHandle, remoteId)
                return
            }

            val peer = Peer(remoteId, peerName)
            connectedPeers[remoteId] = peer
            peerChannel.trySend(peer)
            Log.i(TAG, "NAN Peer discovered: $peerName (${remoteId.take(8)})")

            establishNanNetwork(peerHandle, remoteId)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            handleIcmMessage(peerHandle, message)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            Log.d(TAG, "ICM (sub) send succeeded: $messageId")
        }

        override fun onMessageSendFailed(messageId: Int) {
            val pending = pendingIcmSends.remove(messageId)
            if (pending != null) {
                Log.w(TAG, "ICM (sub) send failed for message $messageId to ${pending.peerId}")
            }
            Log.w(TAG, "ICM (sub) send failed: $messageId")
        }
    }

    // ─── ICM Message Handling ────────────────────────────────────────

    private fun handleIcmMessage(peerHandle: PeerHandle, message: ByteArray) {
        val peerId = peerHandles.entries.firstOrNull { it.value == peerHandle }?.key ?: return
        val messageText = try { String(message) } catch (e: Exception) { null }

        if (messageText != null) {
            try {
                val json = JSONObject(messageText)
                when (json.optString("type", "")) {
                    "crisix_ack" -> {
                        val messageId = json.optString("messageId", "")
                        if (messageId.isNotEmpty()) {
                            onDeliveryAck?.invoke(messageId, peerId)
                            Log.d(TAG, "ICM ACK received for $messageId from ${peerId.take(8)}")
                        }
                        return
                    }
                    "crisix_nan_ip" -> {
                        val ipAddr = json.optString("ip", "")
                        if (ipAddr.isNotEmpty()) {
                            try {
                                nanIpAddresses[peerId] = InetAddress.getByName(ipAddr)
                                Log.i(TAG, "Received NAN IP for $peerId: $ipAddr")
                            } catch (e: Exception) {
                                Log.w(TAG, "Invalid NAN IP from $peerId: $ipAddr")
                            }
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                // Not JSON — raw message
            }
        }

        listeners.forEach { it(peerId, message) }
        sendIcmAck(peerHandle, peerId)
    }

    private fun sendIcmAck(peerHandle: PeerHandle, peerId: String) {
        val ackPayload = JSONObject().apply {
            put("type", "crisix_ack")
            put("messageId", "ack-${System.currentTimeMillis()}")
        }.toString()
        val session = publishSession ?: subscribeSession ?: return
        val messageId = icmMessageIdCounter.getAndIncrement()
        try {
            session.sendMessage(peerHandle, messageId, ackPayload.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "ICM ACK send failed to ${peerId.take(8)}: ${e.message}")
        }
    }

    // ─── NAN Network + TCP Setup ─────────────────────────────────────

    private fun establishNanNetwork(peerHandle: PeerHandle, peerId: String) {
        val discoverySession = publishSession ?: subscribeSession ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            .setPskPassphrase(NETWORK_PASSPHRASE)
            .setPort(tcpPort)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "NAN Network available for $peerId")
                scope.launch {

                    val nanIp = findMyNanIp(network)
                    if (nanIp != null) {
                        sendMyNanIp(peerHandle, peerId, nanIp.hostAddress ?: "")
                    }

                    val existing = nanIpAddresses[peerId]
                    if (existing != null) {
                        connectToPeerTcp(peerId, existing, network)
                    } else {
                        kotlinx.coroutines.delay(2000)
                        val later = nanIpAddresses[peerId]
                        if (later != null) {
                            connectToPeerTcp(peerId, later, network)
                        }
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                val myIp = findMyNanIp(network)
                if (myIp != null) {
                    Log.d(TAG, "NAN IP for $peerId: ${myIp.hostAddress}")
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "NAN Network lost for $peerId")
                disconnectPeerTcp(peerId)
            }

            override fun onUnavailable() {
                Log.w(TAG, "NAN Network unavailable for $peerId")
            }
        }

        connectivityManager?.requestNetwork(request, cb)
    }

    private fun findMyNanIp(network: Network): InetAddress? {
        return try {
            val props = connectivityManager?.getLinkProperties(network) ?: return null
            for (addr in props.linkAddresses) {
                val inet = addr.address
                if (inet is java.net.Inet4Address && inet.isLinkLocalAddress) {
                    return inet
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sendMyNanIp(peerHandle: PeerHandle, peerId: String, ip: String) {
        val ipPayload = JSONObject().apply {
            put("type", "crisix_nan_ip")
            put("ip", ip)
        }.toString()
        val session = publishSession ?: subscribeSession ?: return
        val messageId = icmMessageIdCounter.getAndIncrement()
        try {
            session.sendMessage(peerHandle, messageId, ipPayload.toByteArray())
            Log.d(TAG, "Sent NAN IP $ip to $peerId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send NAN IP to $peerId: ${e.message}")
        }
    }

    private suspend fun connectToPeerTcp(peerId: String, address: InetAddress, network: Network) {
        if (tcpSockets.containsKey(peerId)) return

        withContext(Dispatchers.IO) {
            try {
                val socket = network.socketFactory.createSocket() as Socket
                socket.connect(InetSocketAddress(address, tcpPort), 5000)

                val peer = performHandshake(socket, peerId)
                if (peer != null) {
                    tcpSockets[peerId] = socket
                    connectedPeers[peerId] = peer
                    socket.soTimeout = 0
                    startTcpListener(peerId, socket)
                    Log.i(TAG, "NAN TCP connected to $peerId")
                } else {
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "NAN TCP connect to $peerId failed: ${e.message}")
            }
        }
    }

    private fun performHandshake(socket: Socket, peerId: String): Peer? {
        return try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()

            val handshakeJson = JSONObject().apply {
                put("type", "handshake")
                put("deviceId", localPeerId)
                put("deviceName", deviceName)
                put("port", tcpPort)
            }
            sendViaSocket(socket, handshakeJson.toString().toByteArray())

            val responseData = readMessage(input) ?: return null
            val responseJson = JSONObject(String(responseData))

            if (responseJson.getString("type") == "handshake") {
                val remoteId = responseJson.getString("deviceId")
                val remoteName = responseJson.optString("deviceName", "NAN-${remoteId.take(8)}")

                if (remoteId == localPeerId) return null

                Peer(remoteId, remoteName)
            } else null
        } catch (e: Exception) {
            Timber.w(e, "NAN TCP handshake failed")
            null
        }
    }

    // ─── TCP Server ──────────────────────────────────────────────────

    private suspend fun startTcpServer() {
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(tcpPort)
                serverSocket = ss
                ss.soTimeout = 5000
                Log.i(TAG, "NAN TCP server started on port $tcpPort")

                while (isRunning) {
                    try {
                        val client = ss.accept()
                        scope.launch {
                            handleTcpConnection(client)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "NAN TCP server error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NAN TCP server failed to start: ${e.message}")
            }
        }
    }

    private suspend fun handleTcpConnection(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()

            val handshakeData = readMessage(input) ?: run {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            val json = JSONObject(String(handshakeData))
            if (!json.has("type") || json.getString("type") != "handshake") {
                try { socket.close() } catch (_: Exception) {}
                return
            }

            val remoteId = json.getString("deviceId")
            val remoteName = json.optString("deviceName", "NAN-${remoteId.take(8)}")

            if (remoteId == localPeerId) {
                try { socket.close() } catch (_: Exception) {}
                return
            }

            val responseJson = JSONObject().apply {
                put("type", "handshake")
                put("deviceId", localPeerId)
                put("deviceName", deviceName)
                put("port", tcpPort)
            }
            sendViaSocket(socket, responseJson.toString().toByteArray())

            tcpSockets[remoteId] = socket

            val peer = Peer(remoteId, remoteName)
            if (!connectedPeers.containsKey(remoteId)) {
                connectedPeers[remoteId] = peer
                peerChannel.trySend(peer)
                Log.i(TAG, "NAN TCP peer connected: $remoteId")
            }

            socket.soTimeout = 0
            startTcpListener(remoteId, socket)
        } catch (e: Exception) {
            Log.w(TAG, "NAN TCP connection handling failed: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun startTcpListener(peerId: String, socket: Socket) {
        scope.launch {
            try {
                val input = socket.getInputStream()
                while (isRunning && !socket.isClosed) {
                    val data = readMessage(input) ?: break
                    val messageText = try { String(data) } catch (_: Exception) { null }
                    if (messageText != null) {
                        try {
                            val json = JSONObject(messageText)
                            if (json.optString("type") == "crisix_ack") {
                                val messageId = json.optString("messageId", "")
                                if (messageId.isNotEmpty()) {
                                    onDeliveryAck?.invoke(messageId, peerId)
                                }
                                continue
                            }
                        } catch (_: Exception) {}
                    }
                    listeners.forEach { it(peerId, data) }

                    scope.launch {
                        try {
                            val ackPayload = JSONObject().apply {
                                put("type", "crisix_ack")
                                put("messageId", "ack-${System.currentTimeMillis()}")
                            }.toString().toByteArray()
                            sendViaSocket(socket, ackPayload)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "NAN TCP listener ended for $peerId: ${e.message}")
                }
            } finally {
                disconnectPeerTcp(peerId)
            }
        }
    }

    private fun disconnectPeerTcp(peerId: String) {
        val socket = tcpSockets.remove(peerId)
        if (socket != null) {
            try { socket.getInputStream().close() } catch (_: Exception) {}
            try { socket.getOutputStream().close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            Log.d(TAG, "NAN TCP disconnected: $peerId")
        }
    }

    // ─── Socket Protocol (same as WifiTransport) ─────────────────────

    private fun sendViaSocket(socket: Socket, data: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        val lengthStr = "${data.size}\n"
        out.write(lengthStr.toByteArray())
        out.write(data)
        out.flush()
    }

    private fun readMessage(input: InputStream): ByteArray? {
        val lengthBytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            lengthBytes.add(b.toByte())
        }
        val length = String(lengthBytes.toByteArray()).toIntOrNull() ?: return null
        val result = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(result, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return result
    }

    // ─── Transport Interface ─────────────────────────────────────────

    override fun getStatusDetail(): Pair<Int, String> {
        val peerCount = connectedPeers.size
        val detail = when {
            nanHardwareFailed -> "NAN-Hardware nicht verfuegbar"
            !isRunning -> "Nicht gestartet"
            isAdvertising && isDiscovering -> "NAN aktiv (${peerCount} Peer${if (peerCount != 1) "s" else ""})"
            isAdvertising -> "Advertising aktiv"
            isDiscovering -> "Suche aktiv"
            else -> "Initialisiere NAN..."
        }
        return Pair(peerCount, detail)
    }

    override suspend fun isAvailable(): Boolean {
        if (nanHardwareFailed) return false
        return withContext(Dispatchers.IO) {
            try {
                val packageManager = appContext.packageManager
                val hasFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
                if (!hasFeature) return@withContext false

                // API 31+: NEARBY_WIFI_DEVICES ist die bevorzugte Permission
                // Falls diese verweigert wurde, prüfe ACCESS_FINE_LOCATION als Fallback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasNearbyWifi = ContextCompat.checkSelfPermission(
                        appContext, Manifest.permission.NEARBY_WIFI_DEVICES
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasNearbyWifi) return@withContext true
                    val hasFineLocation = ContextCompat.checkSelfPermission(
                        appContext, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    return@withContext hasFineLocation
                }
                true
            } catch (e: Exception) {
                Timber.w(e, "WifiAware availability check failed")
                false
            }
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val normalizedPeerId = peerId.split("@").first()

            val targetPeerId = connectedPeers.keys.find {
                it == peerId || it.split("@").first() == normalizedPeerId
            } ?: peerId

            // Try ICM first for small payloads
            if (data.size <= ICM_MAX_SIZE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val handle = peerHandles[targetPeerId]
                if (handle != null) {
                    val session = publishSession ?: subscribeSession
                    if (session != null) {
                        try {
                            val messageId = icmMessageIdCounter.getAndIncrement()
                            pendingIcmSends[messageId] = PendingIcmSend(targetPeerId, handle)
                            session.sendMessage(handle, messageId, data)
                            return@withContext Result.success(Unit)
                        } catch (e: Exception) {
                            Log.w(TAG, "ICM send failed, trying TCP: ${e.message}")
                        }
                    }
                }
            }

            // Fallback: TCP over NAN
            val socket = tcpSockets[targetPeerId]
            if (socket != null && !socket.isClosed) {
                try {
                    sendViaSocket(socket, data)
                    return@withContext Result.success(Unit)
                } catch (e: Exception) {
                    disconnectPeerTcp(targetPeerId)
                }
            }

            // Auto-reconnect attempt
            val address = nanIpAddresses[targetPeerId]
            if (address != null) {
                try {
                    val networks = connectivityManager?.allNetworks ?: emptyArray()
                    for (net in networks) {
                        val caps = connectivityManager?.getNetworkCapabilities(net)
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) == true) {
                            try {
                                val newSocket = net.socketFactory.createSocket() as Socket
                                newSocket.connect(InetSocketAddress(address, tcpPort), 5000)
                                val peer = performHandshake(newSocket, targetPeerId)
                                if (peer != null) {
                                    tcpSockets[targetPeerId] = newSocket
                                    newSocket.soTimeout = 0
                                    sendViaSocket(newSocket, data)
                                    startTcpListener(targetPeerId, newSocket)
                                    return@withContext Result.success(Unit)
                                }
                                try { newSocket.close() } catch (_: Exception) {}
                            } catch (_: Exception) { continue }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NAN auto-reconnect failed: ${e.message}")
                }
            }

            Result.failure(Exception("Kein NAN-Pfad fuer Peer $peerId"))
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = peerChannel.receiveAsFlow()

    override suspend fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting WifiAwareTransport")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasNearbyWifi = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            val hasFineLocation = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNearbyWifi && !hasFineLocation) {
                Log.w(TAG, "Fehlende NAN-Berechtigung — NEARBY_WIFI_DEVICES und ACCESS_FINE_LOCATION beide verweigert")
                nanHardwareFailed = true
                return
            }
        }

        startTcpServer()
        attachToWifiAware()
    }

    private fun attachToWifiAware() {
        wifiAwareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            Log.w(TAG, "WifiAwareManager not available")
            return
        }

        if (!wifiAwareManager!!.isAvailable) {
            Timber.w("WifiAware not available on this device")
            return
        }

        try {
            wifiAwareManager!!.attach(attachCallback, handler)
        } catch (e: SecurityException) {
            Timber.w(e, "WifiAware attach: missing NEARBY_WIFI_DEVICES permission")
        }
    }

    private fun buildDiscoveryPayload(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        return if (nameBytes.size <= 128) {
            JSONObject().apply {
                put("id", localPeerId)
                put("n", deviceName)
            }.toString().toByteArray(Charsets.UTF_8)
        } else {
            localPeerId.toByteArray(Charsets.UTF_8)
        }
    }

    private fun startDiscoverySessions() {
        val session = awareSession ?: return
        val payload = buildDiscoveryPayload()

        try {
            val publishConfig = PublishConfig.Builder()
                .setServiceName(serviceName)
                .setServiceSpecificInfo(payload)
                .build()
            session.publish(publishConfig, publishDiscoveryCallback, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "NAN publish: fehlende Berechtigung — deaktiviere Transport")
            nanHardwareFailed = true
            return
        } catch (e: Exception) {
            Timber.w(e, "NAN publish failed")
        }

        try {
            val subscribeConfig = SubscribeConfig.Builder()
                .setServiceName(serviceName)
                .build()
            session.subscribe(subscribeConfig, subscribeDiscoveryCallback, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "NAN subscribe: fehlende Berechtigung — deaktiviere Transport")
            nanHardwareFailed = true
        } catch (e: Exception) {
            Timber.w(e, "NAN subscribe failed")
        }
    }

    override suspend fun stop() {
        if (!isRunning) return
        isRunning = false
        attachRetries = 0
        Log.i(TAG, "Stopping WifiAwareTransport")

        val toClose = tcpSockets.keys.toList()
        for (peerId in toClose) {
            disconnectPeerTcp(peerId)
        }

        try {
            publishSession?.close()
        } catch (_: Exception) {}
        try {
            subscribeSession?.close()
        } catch (_: Exception) {}
        try {
            awareSession?.close()
        } catch (_: Exception) {}

        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        connectedPeers.clear()
        peerHandles.clear()
        nanIpAddresses.clear()
        pendingIcmSends.clear()

        scope.cancel()
        Log.i(TAG, "WifiAwareTransport stopped")
    }
}

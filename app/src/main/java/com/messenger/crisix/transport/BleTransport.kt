package com.messenger.crisix.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleTransport(
    private val localPeerId: String,
    private val appContext: Context,
) : Transport {

    companion object {
        private const val TAG = "BleTransport"
        private val SERVICE_UUID = UUID.fromString("c510c510-c510-c510-c510-c510c510c510")
        private val MESSAGE_CHAR = UUID.fromString("c510c511-c510-c510-c510-c510c510c510")
        private val PEER_ID_CHAR = UUID.fromString("c510c512-c510-c510-c510-c510c510c510")
        private val CAP_CHAR = UUID.fromString("c510c513-c510-c510-c510-c510c510c510")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_PERIOD_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        // Chunking-Konstanten für große Nachrichten (Bilder etc.)
        private const val MAX_WRITE_SIZE = 475
        private const val CHUNK_SENTINEL: Byte = 0x01
        private const val CHUNK_FLAG_FIRST: Byte = 0x01
        private const val CHUNK_FLAG_LAST: Byte = 0x02
        private const val CHUNK_HEADER_SIZE = 6 // sentinel(1) + flags(1) + messageId(4)
    }

    override val type: TransportType = TransportType.BLUETOOTH_MESH
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 400,
        supportsImages = true,
        supportsVideo = false,
        supportsAudio = true,
        supportsFileTransfer = true,
        isMetered = false,
        maxPayloadSize = 400,
        requiresProbing = false
    )

    private data class BlePeerConnection(
        val device: BluetoothDevice,
        val peerId: String,
        var gatt: BluetoothGatt?,
        var messageChar: BluetoothGattCharacteristic?,
        var capChar: BluetoothGattCharacteristic? = null,
    )

    // Chunking: reassembly buffer [messageId → accumulated bytes]
    private val chunkBuffers = java.util.concurrent.ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val chunkMessageId = java.util.concurrent.atomic.AtomicInteger(0)

    // Peer connections: peerId → BlePeerConnection
    private val peerConnections = ConcurrentHashMap<String, BlePeerConnection>()
    // Reverse: device address → peerId
    private val addressToPeerId = ConcurrentHashMap<String, String>()
    // Pending client-connection attempts (address → true)
    private val pendingConnections = ConcurrentHashMap<String, Boolean>()

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())

    var onDeliveryAck: ((messageId: String, peerId: String) -> Unit)? = null
    var onPeerCapabilities: ((com.messenger.crisix.transport.PeerCapabilities) -> Unit)? = null

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    @Volatile private var isRunning = false
    @Volatile private var isAdvertising = false
    @Volatile private var isScanning = false

    // ─── BLE Callbacks ────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            Log.i(TAG, "BLE Advertising gestartet")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE Advertising fehlgeschlagen: errorCode=$errorCode, retry in 5s")
            // Automatischer Retry nach 5s (Samsung-Geräte haben oft temporäre Fehler)
            scope?.launch {
                delay(5000)
                if (isRunning && !isAdvertising) {
                    startAdvertising()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val addr = device.address
            if (addressToPeerId.containsKey(addr)) return
            // Scan-Record auf unseren Service prüfen
            val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
            if (!hasService) {
                // Unfiltered fallback: connect and read peer ID anyway
                // (Samsung-Geräte liefern oft keine serviceUuids im ScanRecord)
                if (unfilteredScan) {
                    connectToDevice(device)
                }
                return
            }
            Log.i(TAG, "BLE Peer gefunden via Scan: ${device.address} (${device.name})")
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE Scan fehlgeschlagen: errorCode=$errorCode")
            // Bei Fehler nach 3s neu starten
            scope?.launch {
                delay(3000)
                if (isRunning && !isScanning) {
                    startScanning()
                }
            }
        }
    }

    @Volatile private var unfilteredScan = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE Server-Verbindung von ${device.address}")
                connectToDevice(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peerId = addressToPeerId.remove(device.address)
                if (peerId != null) {
                    peerConnections.remove(peerId)?.gatt?.close()
                    Log.i(TAG, "BLE Peer getrennt (Server): $peerId")
                }
                pendingWrites.remove(device.address)
                pendingCapData.remove(device.address)
                pendingMessageChars.remove(device.address)
                pendingCapChars.remove(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device == null || characteristic == null || value == null) return

            // Capability-Exchange: eingehende Peer-Capabilities speichern
            if (characteristic.uuid == CAP_CHAR) {
                val peerId = addressToPeerId[device.address]
                val capsStr = String(value)
                if (peerId != null) {
                    val caps = parseCapabilities(peerId, capsStr)
                    if (caps != null) {
                        onPeerCapabilities?.invoke(caps)
                    }
                } else {
                    // Peer-ID noch nicht bekannt → für später vormerken
                    pendingCapData[device.address] = capsStr
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
                }
                return
            }

            if (characteristic.uuid != MESSAGE_CHAR) return

            if (preparedWrite || offset > 0) {
                // Long Write (Prepare Write + Execute Write) oder chunked Write
                synchronized(pendingWrites) {
                    pendingWrites.getOrPut(device.address) { ByteArrayOutputStream() }.write(value)
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
                }
                return
            }

            // Single Write Request (offset=0, nicht vorbereitet)
            processIncomingMessage(device, value)
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            if (device == null) return
            val data = synchronized(pendingWrites) {
                pendingWrites.remove(device.address)?.toByteArray() ?: return
            }
            if (execute) {
                processIncomingMessage(device, data)
            }
            // bei execute=false (Abbruch) verwerfen wir die Daten einfach
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?,
        ) {
            if (device == null || descriptor == null) return
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, descriptor.value)
            } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (device == null || descriptor == null) return
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null || characteristic == null) return
            val fullValue = when (characteristic.uuid) {
                PEER_ID_CHAR -> localPeerId.toByteArray()
                CAP_CHAR -> buildCapabilitiesString().toByteArray()
                else -> ByteArray(0)
            }
            val value = if (offset < fullValue.size) {
                fullValue.copyOfRange(offset, fullValue.size)
            } else {
                ByteArray(0)
            }
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            if (device != null) {
                Log.i(TAG, "BLE Server MTU geändert: $mtu (${device.address})")
            }
        }
    }

    // ─── Initialisierung ────────────────────────────────────────────────

    private fun hasBlePermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val scan = appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
            val connect = appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            val advertise = appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            scan == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                connect == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initBle(): Boolean {
        try {
            if (!hasBlePermissions()) {
                Log.w(TAG, "BLE-Berechtigungen fehlen")
                return false
            }
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return false
            val adapter = manager.adapter ?: return false
            if (!adapter.isEnabled) return false
            bluetoothAdapter = adapter
            bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
            bluetoothLeScanner = adapter.bluetoothLeScanner
            return true
        } catch (e: Exception) {
            Log.e(TAG, "BLE-Init fehlgeschlagen: ${e.message}")
            return false
        }
    }

    // ─── GATT Server ────────────────────────────────────────────────────

    private fun startGattServer(): Boolean {
        try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return false
            val server = manager.openGattServer(appContext, gattServerCallback) ?: return false

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val peerIdChar = BluetoothGattCharacteristic(
                PEER_ID_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )

            val capChar = BluetoothGattCharacteristic(
                CAP_CHAR,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
            )

            val messageChar = BluetoothGattCharacteristic(
                MESSAGE_CHAR,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )

            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
            messageChar.addDescriptor(cccd)

            service.addCharacteristic(peerIdChar)
            service.addCharacteristic(capChar)
            service.addCharacteristic(messageChar)

            if (!server.addService(service)) {
                server.close()
                return false
            }

            gattServer = server
            Log.i(TAG, "GATT Server gestartet")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "GATT Server fehlgeschlagen: ${e.message}")
            return false
        }
    }

    // ─── Advertising ────────────────────────────────────────────────────

    private fun startAdvertising() {
        val advertiser = bluetoothLeAdvertiser ?: return
        try {
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Advertising start fehlgeschlagen: ${e.message}")
        }
    }

    // ─── Scanning ───────────────────────────────────────────────────────

    private fun startScanning() {
        val scanner = bluetoothLeScanner ?: return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // Zuerst mit Filter versuchen (effizienter)
            try {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (_: Exception) {
                // Fallback: ohne Filter scannen
                unfilteredScan = true
                scanner.startScan(null, settings, scanCallback)
            }
            isScanning = true
            Log.i(TAG, "BLE Scan gestartet (filtered)")

            // Nach 10s ohne Treffer auf unfiltered umschalten (Samsung-Kompatibilität)
            scope?.launch {
                delay(10_000)
                if (!isScanning) return@launch
                if (peerConnections.isEmpty() && !unfilteredScan) {
                    Log.i(TAG, "BLE Scan: kein Peer gefunden, wechsle zu unfiltered")
                    try { scanner.stopScan(scanCallback) } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
                    unfilteredScan = true
                    scanner.startScan(null, settings, scanCallback)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scan start fehlgeschlagen: ${e.message}")
        }
    }

    // ─── Verbindungsaufbau ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val addr = device.address
        if (addressToPeerId.containsKey(addr)) return
        if (pendingConnections.put(addr, true) != null) return // bereits am Verbinden

        val gatt = device.connectGatt(
            appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (gatt == null) return
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT verbunden mit ${device.address}, entdecke Services...")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val peerId = addressToPeerId.remove(device.address)
                        if (peerId != null) {
                            peerConnections.remove(peerId)
                            Log.i(TAG, "BLE Peer getrennt (Client): $peerId")
                        }
                        pendingConnections.remove(device.address)
                        pendingMessageChars.remove(device.address)
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (gatt == null || status != BluetoothGatt.GATT_SUCCESS) return
                    val service = gatt.getService(SERVICE_UUID) ?: return

                    val peerIdChar = service.getCharacteristic(PEER_ID_CHAR) ?: return
                    val messageChar = service.getCharacteristic(MESSAGE_CHAR) ?: return

                    // Peer-ID lesen
                    if (!gatt.readCharacteristic(peerIdChar)) return

                    // Message-Charakteristik merken für späteres Senden
                    pendingMessageChars[device.address] = messageChar

                    // Capability-Charakteristik merken
                    val capChar = service.getCharacteristic(CAP_CHAR)
                    if (capChar != null) {
                        pendingCapChars[device.address] = capChar
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int,
                ) {
                    if (gatt == null || characteristic == null || status != BluetoothGatt.GATT_SUCCESS) return

                    when (characteristic.uuid) {
                        PEER_ID_CHAR -> {
                            val peerId = try {
                                String(characteristic.value ?: return)
                            } catch (e: Exception) { return }

                            if (peerId == localPeerId) {
                                pendingConnections.remove(device.address)
                                gatt.disconnect()
                                gatt.close()
                                return
                            }

                    val msgChar = pendingMessageChars.remove(device.address) ?: return
                    addressToPeerId[device.address] = peerId

                    // Ausstehende Capability-Daten verarbeiten
                    val pendingCap = pendingCapData.remove(device.address)
                    if (pendingCap != null) {
                        val caps = parseCapabilities(peerId, pendingCap)
                        if (caps != null) {
                            onPeerCapabilities?.invoke(caps)
                        }
                    }

                            enableNotifications(gatt, msgChar)

                            val conn = BlePeerConnection(
                                device = device,
                                peerId = peerId,
                                gatt = gatt,
                                messageChar = msgChar,
                            )
                    peerConnections[peerId] = conn
                    // Cache CAP_CHAR für spätere Capability-Broadcasts
                    val cachedCapChar = pendingCapChars[device.address]
                    if (cachedCapChar != null) {
                        conn.capChar = cachedCapChar
                    }
                    pendingConnections.remove(device.address)

                    // Ausstehende Sends auflösen (Auto-Reconnect in send())
                    val deferred = pendingSendDeferreds.remove(peerId)
                    deferred?.complete(true)

                            Log.i(TAG, "BLE Peer verbunden: $peerId (${device.address})")

                            val currentPeers = _discoveredPeers.value.toMutableList()
                            if (currentPeers.none { it.id == peerId }) {
                                currentPeers.add(Peer(peerId, "BLE-${peerId.take(8)}"))
                                _discoveredPeers.value = currentPeers
                            }

                            // Capabilities des Peers lesen (falls vorhanden)
                            val capChar = pendingCapChars[device.address]
                            if (capChar != null) {
                                gatt.readCharacteristic(capChar)
                            }
                        }

                        CAP_CHAR -> {
                            val peerId = addressToPeerId[device.address] ?: return
                            val capsStr = try {
                                String(characteristic.value ?: return)
                            } catch (e: Exception) { return }
                            val caps = parseCapabilities(peerId, capsStr)
                            if (caps != null) {
                                onPeerCapabilities?.invoke(caps)
                            }
                            pendingCapChars.remove(device.address)

                            // In peerConnections für spätere Broadcasts cachen
                            peerConnections[peerId]?.capChar = characteristic

                            // Eigene Capabilities schreiben
                            val capChar = characteristic
                            val ownCaps = buildCapabilitiesString()
                            capChar.value = ownCaps.toByteArray()
                            gatt.writeCharacteristic(capChar)
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                ) {
                    if (gatt == null || characteristic == null) return
                    val value = characteristic.value ?: return
                    val device = gatt.device
                    Log.i(TAG, "BLE Notification empfangen von ${device.address}")
                    processIncomingMessage(device, value)
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "BLE MTU verhandelt: $mtu")
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "BLE write bestätigt: ${characteristic?.uuid}")
                    } else {
                        Log.w(TAG, "BLE write fehlgeschlagen: status=$status")
                    }
                }
            }
        )

        // Request MTU 512 (max praktischer Wert auf Android)
        if (gatt == null) {
            pendingConnections.remove(addr)
            Log.w(TAG, "connectGatt fehlgeschlagen für ${device.address}")
            return
        }
        gatt.requestMtu(512)
    }

    // Pending send deferreds: peerId → CompletableDeferred (wird completed wenn Verbindung steht)
    private val pendingSendDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val pendingMessageChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val pendingCapChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    // Pending capability data: device address → capability string (wenn peerId noch nicht bekannt)
    private val pendingCapData = ConcurrentHashMap<String, String>()
    // Long-Write buffer: device address → accumulated bytes
    private val pendingWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID) ?: return
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
    }

    // ─── Transport Interface ────────────────────────────────────────────

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasBlePermissions()) return@withContext false
                val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    ?: return@withContext false
                val adapter = manager.adapter ?: return@withContext false
                adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                var conn = peerConnections[peerId]
                if (conn == null) {
                    Log.w(TAG, "BLE send: peerConnections hat $peerId nicht, versuche Reconnect...")
                    if (!isScanning) {
                        startScanning()
                    }
                    val deferred = CompletableDeferred<Boolean>()
                    pendingSendDeferreds[peerId] = deferred
                    val connected = try {
                        withTimeout(CONNECT_TIMEOUT_MS) {
                            deferred.await()
                        }
                    } catch (_: Exception) {
                        false
                    }
                    pendingSendDeferreds.remove(peerId)
                    if (!connected) {
                        Log.w(TAG, "BLE send: Keine Verbindung zu $peerId innerhalb ${CONNECT_TIMEOUT_MS}ms")
                        return@withContext Result.failure(Exception("BLE: Peer $peerId nicht in Reichweite"))
                    }
                    conn = peerConnections[peerId]
                    if (conn == null) {
                        return@withContext Result.failure(Exception("BLE: Peer $peerId nicht in Reichweite"))
                    }
                }

                val char = conn.messageChar
                if (char == null) {
                    Log.w(TAG, "BLE send: Kein Message-Charakteristik für $peerId")
                    return@withContext Result.failure(Exception("BLE: Kein Message-Charakteristik"))
                }
                val gatt = conn.gatt
                if (gatt == null) {
                    Log.w(TAG, "BLE send: Kein Gatt für $peerId")
                    return@withContext Result.failure(Exception("BLE: Keine Verbindung"))
                }

                val b64 = Base64.getEncoder().encodeToString(data)
                val fullPayload = "$localPeerId\u0000$b64"
                val fullBytes = fullPayload.toByteArray()

                if (fullBytes.size <= MAX_WRITE_SIZE) {
                    char.value = fullBytes
                    val success = gatt.writeCharacteristic(char)
                    if (success) {
                        Log.i(TAG, "BLE send erfolgreich an $peerId (${fullBytes.size} Bytes, single)")
                        return@withContext Result.success(Unit)
                    } else {
                        Log.e(TAG, "BLE writeCharacteristic fehlgeschlagen für $peerId")
                        return@withContext Result.failure(Exception("BLE writeCharacteristic fehlgeschlagen"))
                    }
                }

                // Large payload → chunked send
                val msgId = chunkMessageId.incrementAndGet()
                val maxChunkData = MAX_WRITE_SIZE - CHUNK_HEADER_SIZE
                var offset = 0
                var chunkIndex = 0
                val totalChunks = (fullBytes.size + maxChunkData - 1) / maxChunkData

                while (offset < fullBytes.size) {
                    val chunkEnd = minOf(offset + maxChunkData, fullBytes.size)
                    val chunkData = fullBytes.copyOfRange(offset, chunkEnd)
                    val flags = when {
                        chunkIndex == 0 && chunkIndex == totalChunks - 1 -> (CHUNK_FLAG_FIRST.toInt() or CHUNK_FLAG_LAST.toInt()).toByte()
                        chunkIndex == 0 -> CHUNK_FLAG_FIRST
                        chunkIndex == totalChunks - 1 -> CHUNK_FLAG_LAST
                        else -> 0
                    }

                    val header = ByteArray(CHUNK_HEADER_SIZE).apply {
                        this[0] = CHUNK_SENTINEL
                        this[1] = flags
                        val idBytes = java.nio.ByteBuffer.allocate(4).putInt(msgId).array()
                        System.arraycopy(idBytes, 0, this, 2, 4)
                    }
                    val chunkPacket = header + chunkData

                    char.value = chunkPacket
                    val ok = gatt.writeCharacteristic(char)
                    if (!ok) {
                        Log.e(TAG, "BLE chunk $chunkIndex/$totalChunks fehlgeschlagen für $peerId")
                        return@withContext Result.failure(Exception("BLE chunk send fehlgeschlagen"))
                    }

                    offset = chunkEnd
                    chunkIndex++
                }

                Log.i(TAG, "BLE send erfolgreich an $peerId ($totalChunks chunks, ${fullBytes.size} total Bytes)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "BLE send fehlgeschlagen: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /**
     * Sendet die aktuellen Capabilities an alle verbundenen BLE-Peers.
     * Wird vom TransportManager bei Netzwerkänderungen aufgerufen.
     */
    fun broadcastCapabilities() {
        val capsStr = buildCapabilitiesString()
        val data = capsStr.toByteArray()
        Log.i(TAG, "Broadcast-Capabilities an ${peerConnections.size} Peers: $capsStr")
        for ((peerId, conn) in peerConnections) {
            val gatt = conn.gatt ?: continue
            val capChar = conn.capChar ?: continue
            try {
                capChar.value = data
                gatt.writeCharacteristic(capChar)
                Log.i(TAG, "Capabilities pushed to $peerId")
            } catch (e: Exception) {
                Log.w(TAG, "Push-Capabilities fehlgeschlagen für $peerId: ${e.message}")
            }
        }
    }

    // ─── Eingehende Nachrichten verarbeiten ─────────────────────────────

    private fun processIncomingMessage(device: BluetoothDevice, value: ByteArray) {
        // Prüfen auf chunked message (beginnt mit CHUNK_SENTINEL)
        if (value.size >= CHUNK_HEADER_SIZE && value[0] == CHUNK_SENTINEL) {
            val flags = value[1]
            val msgId = java.nio.ByteBuffer.wrap(value, 2, 4).getInt()
            val chunkData = value.copyOfRange(CHUNK_HEADER_SIZE, value.size)

            val isFirst = (flags.toInt() and CHUNK_FLAG_FIRST.toInt()) != 0
            val isLast = (flags.toInt() and CHUNK_FLAG_LAST.toInt()) != 0


            if (isFirst && isLast) {
                // Single-chunk message — direkt verarbeiten
                deliverMessage(device, chunkData)
                return
            }

            // Multi-chunk: buffer and reassemble
            val buf = chunkBuffers.getOrPut(msgId) { ByteArrayOutputStream() }
            synchronized(buf) {
                buf.write(chunkData)
            }

            if (isLast) {
                chunkBuffers.remove(msgId)
                val fullData = buf.toByteArray()
                deliverMessage(device, fullData)
            }
            return
        }

        // Legacy single message (unchanged)
        val msg = String(value)
        val sep = msg.indexOf('\u0000')
        if (sep < 0) return

        val senderPeerId = msg.substring(0, sep)
        val b64Data = msg.substring(sep + 1)

        addressToPeerId[device.address] = senderPeerId

        try {
            val data = Base64.getDecoder().decode(b64Data)
            Log.i(TAG, "BLE Nachricht empfangen von $senderPeerId (${data.size} Bytes)")
            
            // ═══════════════════════════════════════════════════════════════
            // INTERNAL MESSAGE HANDLING: ACK, Ping, Pong
            // ═══════════════════════════════════════════════════════════════
            val messageText = try { String(data) } catch (_: Exception) { null }
            var isInternal = false
            if (messageText != null) {
                try {
                    val json = org.json.JSONObject(messageText)
                    when (json.optString("type")) {
                        "crisix_ack" -> {
                            val messageId = json.optString("messageId", "")
                            if (messageId.isNotEmpty()) {
                                onDeliveryAck?.invoke(messageId, senderPeerId)
                                Log.i(TAG, "[BleTransport] ACK empfangen für $messageId von $senderPeerId")
                                isInternal = true
                            }
                        }
                        "crisix_ping" -> {
                            Log.d(TAG, "[BleTransport] Ping empfangen von ${senderPeerId.take(8)}")
                            val pongPayload = org.json.JSONObject().apply {
                                put("type", "crisix_pong")
                                put("id", json.getString("id"))
                            }.toString().toByteArray()
                            scope?.launch {
                                try {
                                    send(senderPeerId, pongPayload)
                                    Log.d(TAG, "[BleTransport] Pong versendet an ${senderPeerId.take(8)}")
                                } catch (e: Exception) {
                                    Log.w(TAG, "[BleTransport] Pong-Sendung fehlgeschlagen: ${e.message}")
                                }
                            }
                            isInternal = true
                        }
                        "crisix_pong" -> {
                            Log.d(TAG, "[BleTransport] Pong empfangen von ${senderPeerId.take(8)}")
                            isInternal = true
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
            }
            
            // Nur weitergeben wenn nicht intern
            if (!isInternal) {
                messageListeners.forEach { it(senderPeerId, data) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE Base64-Decode fehlgeschlagen: ${e.message}")
        }
    }

    private fun deliverMessage(device: BluetoothDevice, rawPayload: ByteArray) {
         val msg = String(rawPayload)
         val sep = msg.indexOf('\u0000')
         if (sep < 0) return
         val senderPeerId = msg.substring(0, sep)
         val b64Data = msg.substring(sep + 1)
         addressToPeerId[device.address] = senderPeerId
         try {
             val data = Base64.getDecoder().decode(b64Data)
             Log.i(TAG, "BLE Nachricht empfangen von $senderPeerId (${data.size} Bytes, chunked)")
             
             // ═══════════════════════════════════════════════════════════════
             // INTERNAL MESSAGE HANDLING: ACK, Ping, Pong
             // ═══════════════════════════════════════════════════════════════
             val messageText = try { String(data) } catch (_: Exception) { null }
             var isInternal = false
             if (messageText != null) {
                 try {
                     val json = org.json.JSONObject(messageText)
                     when (json.optString("type")) {
                         "crisix_ack" -> {
                             val messageId = json.optString("messageId", "")
                             if (messageId.isNotEmpty()) {
                                 onDeliveryAck?.invoke(messageId, senderPeerId)
                                 Log.i(TAG, "[BleTransport] ACK empfangen für $messageId von $senderPeerId")
                                 isInternal = true
                             }
                         }
                         "crisix_ping" -> {
                             Log.d(TAG, "[BleTransport] Ping empfangen von ${senderPeerId.take(8)}")
                             val pongPayload = org.json.JSONObject().apply {
                                 put("type", "crisix_pong")
                                 put("id", json.getString("id"))
                             }.toString().toByteArray()
                             scope?.launch {
                                 try {
                                     send(senderPeerId, pongPayload)
                                     Log.d(TAG, "[BleTransport] Pong versendet an ${senderPeerId.take(8)}")
                                 } catch (e: Exception) {
                                     Log.w(TAG, "[BleTransport] Pong-Sendung fehlgeschlagen: ${e.message}")
                                 }
                             }
                             isInternal = true
                         }
                         "crisix_pong" -> {
                             Log.d(TAG, "[BleTransport] Pong empfangen von ${senderPeerId.take(8)}")
                             isInternal = true
                         }
                     }
                 } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
             }
             
             // Nur weitergeben wenn nicht intern
             if (!isInternal) {
                 messageListeners.forEach { it(senderPeerId, data) }
             }
         } catch (e: Exception) {
             Log.w(TAG, "BLE Base64-Decode fehlgeschlagen: ${e.message}")
         }
     }

    // ─── Capability-Exchange ────────────────────────────────────────────

    private fun buildCapabilitiesString(): String {
        val ctx = appContext
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val isOnline = activeNetwork != null
        return "hasInternet=$isOnline&hasBle=true&hasRelay=$isOnline&hasWifiDirect=false"
    }

    private fun parseCapabilities(peerId: String, raw: String): com.messenger.crisix.transport.PeerCapabilities? {
        return try {
            val parts = raw.split("&").map { it.split("=", limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
            com.messenger.crisix.transport.PeerCapabilities(
                peerId = peerId,
                hasInternet = parts["hasInternet"] == "true",
                hasBle = parts["hasBle"] == "true",
                hasRelay = parts["hasRelay"] == "true",
                hasWifiDirect = parts["hasWifiDirect"] == "true",
            )
        } catch (_: Exception) { null }
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
        if (isRunning) {
            Log.w(TAG, "BleTransport bereits gestartet — stop() zuerst")
            return
        }
        if (!initBle()) {
            Log.w(TAG, "BLE nicht verfügbar")
            return
        }

        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        if (!startGattServer()) {
            Log.w(TAG, "GATT Server konnte nicht gestartet werden")
        }

        startAdvertising()
        startScanning()

        Log.i(TAG, "BleTransport gestartet (peerId=$localPeerId)")
    }

    private fun stopScanning() {
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
            isScanning = false
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
            isAdvertising = false
        }
    }

    override suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        scanJob?.cancel()
        scanJob = null

        stopScanning()
        stopAdvertising()

        // Scope zuerst canceln → keine neuen Callbacks
        scope?.cancel()
        scope = null

        // alle GATT-Client-Verbindungen trennen & schließen
        peerConnections.values.forEach { conn ->
            try {
                conn.gatt?.disconnect()
                conn.gatt?.close()
            } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
        }
        peerConnections.clear()
        addressToPeerId.clear()
        pendingConnections.clear()
        pendingMessageChars.clear()
        pendingCapChars.clear()
        pendingCapData.clear()
        pendingSendDeferreds.clear()
        pendingWrites.clear()

        // Chunk-Buffer leeren
        chunkBuffers.clear()

        // GATT-Server schließen
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (e: Exception) { Log.w(TAG, "BLE operation failed: ${e.message}", e) }
        gattServer = null

        isScanning = false
        isAdvertising = false
        unfilteredScan = false
        bluetoothLeScanner = null
        bluetoothLeAdvertiser = null
        bluetoothAdapter = null

        _discoveredPeers.value = emptyList()
        Log.i(TAG, "BleTransport gestoppt")
    }

    override fun getStatusDetail(): Pair<Int, String> {
        return Pair(
            peerConnections.size,
            if (peerConnections.isNotEmpty()) "BLE: ${peerConnections.size} Peers" else "BLE aktiv"
        )
    }
}

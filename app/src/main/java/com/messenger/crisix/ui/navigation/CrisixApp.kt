package com.messenger.crisix.ui.navigation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.messenger.crisix.LocaleHelper
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.transport.BleTransport
import com.messenger.crisix.transport.DnsTunnelTransport
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.RelayTransport
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.transport.WifiTransport
import com.messenger.crisix.transport.internet.InternetTransport
import com.messenger.crisix.transport.internet.Libp2pManager
import com.messenger.crisix.ui.screens.AddContactScreen
import com.messenger.crisix.ui.screens.ChatDetailScreen
import com.messenger.crisix.ui.screens.ChatListScreen
import com.messenger.crisix.ui.screens.ChatPreview
import com.messenger.crisix.ui.screens.ContactDetailScreen
import com.messenger.crisix.ui.screens.ContactListScreen
import com.messenger.crisix.ui.screens.ConnectionsScreen
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.screens.MyIdScreen
import com.messenger.crisix.ui.screens.LogViewerScreen
import com.messenger.crisix.ui.screens.QrCodeScannerScreen
import com.messenger.crisix.ui.screens.OnboardingScreen
import com.messenger.crisix.ui.screens.SettingsScreen
import com.messenger.crisix.ui.screens.TransportSetupScreen
import com.messenger.crisix.ui.screens.UserProfile
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrisixApp(
    onLanguageChanged: (LocaleHelper.AppLanguage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val TAG = "CrisixApp"
    val navController = rememberNavController()
    val transportManager = remember { TransportManager() }
    val scope = rememberCoroutineScope()

    // Transport-Einstellungen
    var transportSettings by remember {
        mutableStateOf(
            mapOf(
                TransportType.INTERNET to true,
                TransportType.WIFI_DIRECT to true,
                TransportType.BLUETOOTH_MESH to true,
                TransportType.SMS to false,
                TransportType.DNS_TUNNEL to true,
                TransportType.LORA to false
            )
        )
    }

    val context = LocalContext.current

    // Netzwerk-Monitor für Capability-Refresh bei WLAN/Mobile-Änderungen
    transportManager.initNetworkMonitor(context)

    // =========================================================================
    // BLE Runtime-Permissions (API 31+: BLUETOOTH_SCAN/CONNECT/ADVERTISE)
    // =========================================================================
    var blePermissionsGranted by remember { mutableStateOf(false) }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        blePermissionsGranted = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            blePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            )
        } else {
            blePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }

    // =========================================================================
    // EINHEITLICHE IDENTITÄT: Ed25519-Schlüsselpaar als Single Source of Truth
    // =========================================================================
    // Wird beim ersten Start generiert und dauerhaft in SharedPreferences
    // gespeichert. Der Fingerprint (SHA-256 des Public Keys) ist die EINZIGE
    // Geräte-ID für ALLE Transporte (WifiTransport, InternetTransport,
    // DnsTunnelTransport). Kein pending-id-* Fallback mehr!
    // =========================================================================
    val deviceId = remember(context) {
        val cryptoHelper = com.messenger.crisix.transport.internet.CryptoHelper
        var keyPair = cryptoHelper.loadFromAndroidKeyStore("crisix_identity", context)
        if (keyPair == null) {
            keyPair = cryptoHelper.generateKeyPair()
            cryptoHelper.saveToAndroidKeyStore("crisix_identity", keyPair, context)
        }
        cryptoHelper.publicKeyToFingerprint(keyPair.publicKey)
    }
    
    // Standard-Anzeigename: erste 8 Zeichen der Geräte-ID
    val defaultDisplayName = deviceId.take(8)

    // First-Run-Erkennung
    val setupPrefs = context.getSharedPreferences("crisix_setup", Context.MODE_PRIVATE)
    var isSetupComplete by remember { mutableStateOf(setupPrefs.getBoolean("setup_complete", false)) }

    // Benutzerprofil
    var userProfile by remember { mutableStateOf(UserProfile()) }

    // States für Nachrichten und aktiven Chat
    val allMessages = remember { mutableStateMapOf<String, List<Message>>() }
    var currentMessages by remember { mutableStateOf(emptyList<Message>()) }
    var currentChatPeerId by remember { mutableStateOf("") }

    // Von Peers übermittelte Anzeigenamen (peerId → name)
    val incomingNames = remember { mutableStateMapOf<String, String>() }

    // State für Netzwerkscan

    // =========================================================================
    // ContactRepository für dauerhafte Kontaktspeicherung
    // =========================================================================
    val contactRepository = remember(context) {
        ContactRepository(context)
    }

    // Gespeicherte Kontakte (aus SharedPreferences)
    var savedContacts by remember { mutableStateOf(contactRepository.loadContacts()) }

    // =========================================================================
    // Transporte initialisieren und starten (nur nach Setup)
    // =========================================================================
    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect

        val displayName = userProfile.name.ifBlank { defaultDisplayName }

        val wifiTransport = WifiTransport(
            deviceId = deviceId,
            deviceName = displayName
        )
        transportManager.registerTransport(wifiTransport)

        val internetTransport = InternetTransport(
            context = context,
            deviceName = displayName
        )
        transportManager.registerTransport(internetTransport)

        // DNS-Tunnel-Transport (für den Fall, dass Internet/WLAN blockiert ist)
        val dnsTunnelTransport = DnsTunnelTransport(
            localPeerId = deviceId,
            serverDomain = "crisix-dns.onrender.com",
            useHttpApi = true // HTTP-API ist zuverlässiger als UDP-DNS
        )
        transportManager.registerTransport(dnsTunnelTransport)

        // TCP-Relay-Transport via WebSocket (für NAT↔NAT ohne 253-Zeichen-Limit)
        val relayTransport = RelayTransport(
            localPeerId = deviceId,
            relayUrl = "wss://crisix-dns.onrender.com/ws"
        )
        transportManager.registerTransport(relayTransport)

        // BLE-Transport (Nahbereich, ohne Internet)
        val bleTransport = BleTransport(
            localPeerId = deviceId,
            appContext = context
        )
        transportManager.registerTransport(bleTransport)

        // ⚠️ WICHTIG: Message-Listener VOR startAll() registrieren!
        // Sonst verpasst der Listener Nachrichten, die der DNS-Tunnel-Polling-Job
        // sofort nach dem Start empfängt.
        transportManager.registerMessageListener { peerId, data ->
            // Normalisieren: WifiTransport liefert "fingerprint@ip", Chats nutzen nur den Fingerprint
            val normalizedPeerId = peerId.split("@").first()

            val messageText = String(data)
            val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            var senderName: String? = null
            val displayText = try {
                val json = JSONObject(messageText)
                if (json.has("sender")) {
                    senderName = json.getString("sender")
                }
                if (json.has("type") && json.getString("type") == "message") {
                    json.getString("text")
                } else {
                    messageText
                }
            } catch (e: Exception) {
                messageText
            }

            if (senderName != null) {
                incomingNames[normalizedPeerId] = senderName
            }

            val newMessage = Message(
                id = "incoming-${System.currentTimeMillis()}",
                text = displayText,
                isFromMe = false,
                timestamp = timeStamp,
                status = MessageStatus.DELIVERED
            )

            // Nachricht unter der aufgelösten Peer-ID speichern
            val existingMessages = allMessages[normalizedPeerId] ?: emptyList()

            // Alle SENT-Nachrichten an diesen Peer auf DELIVERED setzen
            val withDelivered = existingMessages.map { msg ->
                if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                    msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                } else msg
            }
            allMessages[normalizedPeerId] = withDelivered + newMessage

            // 🔊 ALLE eingehenden Nachrichten auch im Echo-Chat anzeigen
            val echoMessages = allMessages["echo-self"] ?: emptyList()
            allMessages["echo-self"] = echoMessages + newMessage

            if (currentChatPeerId == normalizedPeerId || currentChatPeerId == "echo-self") {
                currentMessages = allMessages[currentChatPeerId] ?: emptyList()
            }
        }

        // Jetzt erst die Transporte starten (Listener ist bereits registriert)
        transportManager.startAll()
        transportManager.selectBestTransport()
        transportManager.startPeriodicReevaluation()
        transportManager.startRetryJob()
    }

    // Delivery-Updates abonnieren
    LaunchedEffect(Unit) {
        transportManager.deliveryUpdates.collect { update ->
            val normChatId = update.peerId.split("@").first()
            val existing = allMessages[normChatId]
            if (existing != null) {
                val updated = existing.map { msg ->
                    if (msg.id == update.uiMessageId) {
                        when {
                            update.status == MessageStatus.DELIVERED -> msg.copy(status = MessageStatus.DELIVERED, transport = update.transport)
                            update.status == MessageStatus.SENT && msg.status != MessageStatus.DELIVERED ->
                                msg.copy(status = MessageStatus.SENT, transport = update.transport)
                            update.status == MessageStatus.FAILED && msg.status == MessageStatus.SENDING ->
                                msg.copy(status = MessageStatus.FAILED, transport = update.transport)
                            else -> msg
                        }
                    } else msg
                }
                allMessages[normChatId] = updated
                if (currentChatPeerId == normChatId) {
                    currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                }
            }
        }
    }

    val activeTransport by transportManager.activeTransport.collectAsState()
    val discoveredPeers by transportManager.discoveredPeers.collectAsState()
    val connectionStatuses by transportManager.connectionStatuses.collectAsState()
    val capabilities = transportManager.getCurrentCapabilities()

    // Lokale Peer-ID und Port für die Anzeige im UI
    var localPeerId by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf(0) }

    // Peer-ID und Port aus dem InternetTransport abrufen
    LaunchedEffect(Unit) {
        // Warten bis der InternetTransport gestartet ist und die stabile Peer-ID hat
        while (com.messenger.crisix.transport.internet.Libp2pManager.localPeerId.isBlank()) {
            kotlinx.coroutines.delay(500)
        }
        localPeerId = com.messenger.crisix.transport.internet.Libp2pManager.localPeerId
        localPort = com.messenger.crisix.transport.internet.Libp2pManager.localPort

        // Wenn kein Benutzername gesetzt ist, nutzen wir die ersten 8 Zeichen der ECHTEN Peer-ID
        if (userProfile.name.isBlank()) {
            userProfile = userProfile.copy(name = localPeerId.take(8))
        }
    }

    // Chat-Liste generieren – reagiert auch auf Nachrichten von unbekannten Peers
    val chats by remember(discoveredPeers, activeTransport, incomingNames) {
        derivedStateOf {
            val chatList = mutableListOf<ChatPreview>()
            val seenIds = mutableSetOf<String>()

            for (peer in discoveredPeers) {
                val normId = peer.id.split("@").first()
                if (normId in seenIds) continue
                seenIds.add(normId)
                val peerMessages = allMessages[normId] ?: emptyList()
                val lastMsg = peerMessages.lastOrNull()
                chatList.add(
                    ChatPreview(
                        id = normId,
                        name = peer.name,
                        lastMessage = lastMsg?.text ?: "Verbunden via WLAN",
                        timestamp = lastMsg?.timestamp ?: "Jetzt",
                        timestampMillis = lastMsg?.timestampMillis ?: 0L,
                        unreadCount = 0,
                        transportType = activeTransport?.type
                    )
                )
            }

            // Unbekannte Peers (nicht in discoveredPeers) aus eingehenden Nachrichten
            for ((peerId, messages) in allMessages) {
                if (peerId == "echo-self") continue
                val normId = peerId.split("@").first()
                if (normId in seenIds) continue
                if (messages.isEmpty()) continue
                seenIds.add(normId)
                val lastMsg = messages.last()
                val peerDisplayName = incomingNames[normId] ?: normId.take(8)
                chatList.add(
                    ChatPreview(
                        id = normId,
                        name = peerDisplayName,
                        lastMessage = lastMsg.text,
                        timestamp = lastMsg.timestamp,
                        timestampMillis = lastMsg.timestampMillis,
                        unreadCount = 0,
                        transportType = activeTransport?.type
                    )
                )
            }

            // Echo-Chat für DNS-Tunnel-Tests (mit sich selbst schreiben)
            val echoMessages = allMessages["echo-self"] ?: emptyList()
            val echoLastMsg = echoMessages.lastOrNull()
            chatList.add(
                ChatPreview(
                    id = "echo-self",
                    name = "📡 Echo (DNS-Tunnel)",
                    lastMessage = echoLastMsg?.text ?: "Teste den DNS-Tunnel",
                    timestamp = echoLastMsg?.timestamp ?: "Jetzt",
                    timestampMillis = echoLastMsg?.timestampMillis ?: 0L,
                    unreadCount = 0,
                    transportType = TransportType.DNS_TUNNEL
                )
            )

            chatList
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isSetupComplete) NavRoutes.CHAT_LIST else NavRoutes.ONBOARDING,
        modifier = modifier
    ) {
        composable(NavRoutes.ONBOARDING) {
            OnboardingScreen(
                onComplete = { username ->
                    userProfile = userProfile.copy(name = username)
                    navController.navigate(NavRoutes.TRANSPORT_SETUP) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.TRANSPORT_SETUP) {
            TransportSetupScreen(
                transportSettings = transportSettings,
                onTransportToggle = { type, enabled ->
                    transportSettings = transportSettings + (type to enabled)
                },
                onComplete = {
                    setupPrefs.edit().putBoolean("setup_complete", true).apply()
                    isSetupComplete = true
                    navController.navigate(NavRoutes.CHAT_LIST) {
                        popUpTo(NavRoutes.TRANSPORT_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.CHAT_LIST) {
            ChatListScreen(
                chats = chats,
                onChatClick = { chatId, chatName ->
                    val normChatId = chatId.split("@").first()
                    val isEchoChat = chatId == "echo-self"
                    currentChatPeerId = normChatId
                    currentMessages = if (isEchoChat) {
                        allMessages["echo-self"] ?: emptyList()
                    } else {
                        allMessages[normChatId] ?: emptyList()
                    }
                    navController.navigate(NavRoutes.chatDetail(normChatId, chatName))
                },


                onSettingsClick = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onAddPeer = { ipAddress, displayName ->
                    scope.launch {
                        transportManager.connectToPeer(ipAddress, displayName.ifBlank { null })
                            .onSuccess { peer ->
                                Log.i(TAG, "[CrisixApp] Manuell verbunden mit: ${peer.name} (${peer.id})")
                            }
                            .onFailure { error ->
                                Log.i(TAG, "[CrisixApp] Fehler beim Verbinden: ${error.message}")
                            }
                    }
                },
                localPeerId = localPeerId,
                localPort = localPort,
                onMyIdClick = { navController.navigate(NavRoutes.MY_ID) },
                onAddContactClick = { navController.navigate(NavRoutes.ADD_CONTACT) },
                onConnectionsClick = { navController.navigate(NavRoutes.CONNECTIONS) },
                onContactsClick = { navController.navigate(NavRoutes.CONTACT_LIST) },
                connectionStatuses = connectionStatuses
            )
        }

        composable(
            route = NavRoutes.CHAT_DETAIL,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("chatName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            val chatName = backStackEntry.arguments?.getString("chatName") ?: ""

            ChatDetailScreen(
                chatId = chatId,
                chatName = chatName,
                transportType = activeTransport?.type,
                capabilities = capabilities,
                messages = currentMessages,
                onBackClick = { navController.popBackStack() },
                onSendMessage = { text ->
                    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val newMessage = Message(
                        id = "m${System.currentTimeMillis()}",
                        text = text,
                        isFromMe = true,
                        timestamp = timeStamp,
                        status = MessageStatus.SENDING
                    )

                    currentMessages = currentMessages + newMessage

                    val normChatId = chatId.split("@").first()
                    val existingMessages = allMessages[normChatId] ?: emptyList()
                    allMessages[normChatId] = existingMessages + newMessage

                    val isRealPeer = discoveredPeers.any { it.id.split("@").first() == normChatId }
                        || normChatId != "echo-self" && allMessages.containsKey(normChatId)
                    val isEchoChat = chatId == "echo-self"
                    if (isRealPeer || isEchoChat) {
                        val jsonMessage = JSONObject().apply {
                            put("type", "message")
                            put("text", text)
                            put("timestamp", timeStamp)
                            put("messageId", newMessage.id)
                            put("sender", userProfile.name.ifBlank { "Crisix-User" })
                        }

                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            if (isEchoChat) {
                                val dnsTransport = transportManager.getTransportByType(TransportType.DNS_TUNNEL)
                                if (dnsTransport != null) {
                                    dnsTransport.send(deviceId, text.toByteArray())
                                        .onSuccess {
                                            Log.i(TAG, "[CrisixApp] ✅ Echo-Nachricht via DNS-Tunnel gesendet: $text")
                                        }
                                        .onFailure { error ->
                                            Log.i(TAG, "[CrisixApp] ❌ Echo-Fehler: ${error.message}")
                                        }
                                } else {
                                    Log.i(TAG, "[CrisixApp] ❌ DNS-Tunnel-Transport nicht gefunden")
                                }
                            } else {
                                transportManager.sendMessage(normChatId, jsonMessage.toString().toByteArray(), uiMessageId = newMessage.id)
                                    .onFailure { error ->
                                        Log.i(TAG, "[CrisixApp] Fehler beim Senden: ${error.message}")
                                    }
                            }
                        }

                    }


                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                transportSettings = transportSettings,
                onTransportToggle = { type, enabled ->
                    transportSettings = transportSettings + (type to enabled)
                },
                userProfile = userProfile,
                onProfileUpdate = { updatedProfile ->
                    userProfile = updatedProfile
                },
                onLanguageChanged = onLanguageChanged,
                onBackClick = { navController.popBackStack() },
                onOpenLogViewer = { navController.navigate(NavRoutes.LOG_VIEWER) }
            )
        }

        composable(NavRoutes.LOG_VIEWER) {
            LogViewerScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.MY_ID) {
            MyIdScreen(
                displayName = userProfile.name.ifBlank { defaultDisplayName },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.ADD_CONTACT) { backStackEntry ->
            AddContactScreen(
                transportManager = transportManager,
                onBackClick = { navController.popBackStack() },
                onContactAdded = { peerId, name ->
                    Log.i(TAG, "[CrisixApp] Neuer Kontakt hinzugefügt: $name ($peerId)")
                    navController.popBackStack()
                },
                onOpenQrScanner = {
                    navController.navigate(NavRoutes.QR_SCANNER)
                }
            )
        }


        composable(NavRoutes.QR_SCANNER) {
            QrCodeScannerScreen(
                onQrCodeScanned = { qrContent ->
                    Log.i(TAG, "[CrisixApp] QR-Code gescannt: $qrContent")
                    // PeerId (Fingerprint), Name und IP aus dem QR-Code extrahieren
                    val peerId: String? = extractPeerIdFromQr(qrContent)
                    val name: String? = extractNameFromQr(qrContent)
                    val ip: String? = extractIpFromQr(qrContent)
                    val port: Int? = extractPortFromQr(qrContent)

                    if (peerId != null) {
                        val displayName = name ?: peerId.take(8)
                        Log.i(TAG, "[CrisixApp] QR-Kontakt: $displayName (Fingerprint: ${peerId.take(16)}..., IP: $ip, Port: $port)")

                        // ============================================================
                        // Schritt 1: Kontakt SOFORT speichern (synchron, vor der Coroutine)
                        // ============================================================
                        // Der Kontakt wird IMMER gespeichert, unabhängig vom Verbindungsstatus.
                        // So kann der Benutzer später jederzeit einen Chat starten.
                        // Wichtig: Das passiert synchron, bevor popBackStack() aufgerufen wird!
                        val newContact = contactRepository.createContact(
                            peerId = peerId,
                            name = displayName,
                            ipAddress = ip,
                            port = port
                        )
                        val updatedList = contactRepository.addOrUpdateContact(newContact)
                        savedContacts = updatedList
                        Log.i(TAG, "[CrisixApp] ✅ Kontakt gespeichert: $displayName ($peerId)")

                        // ============================================================
                        // Verbindungsaufbau (kontaktbasiert, keine automatische Suche)
                        // ============================================================
                        // 1. WifiTransport – Direkte IP-Verbindung (falls IP im QR-Code)
                        // 2. Internet (DHT) – Einmalige DHT-Suche als Fallback
                        // ============================================================

                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            var connected = false
                            var resolvedIp = ip
                            var resolvedPort = port

                            if (ip != null) {
                                try {
                                    Log.i(TAG, "[CrisixApp] Versuche IP-Verbindung zu $ip:${port ?: "Standard"} für $displayName")
                                    val result = transportManager.connectToPeer(ip, displayName, port)
                                    if (result.isSuccess) {
                                        val peer = result.getOrNull() as com.messenger.crisix.transport.Peer
                                        Log.i(TAG, "[CrisixApp] ✅ IP-Verbindung erfolgreich: ${peer.name} (${peer.id})")
                                        connected = true
                                    }
                                } catch (e: Exception) {
                                    Log.i(TAG, "[CrisixApp] IP-Verbindung fehlgeschlagen: ${e.message}")
                                }
                            }

                            // DHT-Fallback (ein Versuch)
                            if (!connected) {
                                val internetTransport = transportManager.getTransportByType(
                                    com.messenger.crisix.transport.TransportType.INTERNET
                                ) as? com.messenger.crisix.transport.internet.InternetTransport
                                if (internetTransport != null) {
                                    try {
                                        Log.i(TAG, "[CrisixApp] DHT-Suche für $displayName (Fingerprint: ${peerId.take(16)}...)")
                                        val result = internetTransport.connectToPeerById(peerId, displayName)
                                        if (result.isSuccess) {
                                            val peer = result.getOrNull() as com.messenger.crisix.transport.Peer
                                            Log.i(TAG, "[CrisixApp] ✅ DHT-Verbindung erfolgreich: ${peer.name} (${peer.id})")
                                            connected = true
                                        }
                                    } catch (e: Exception) {
                                        Log.i(TAG, "[CrisixApp] DHT-Suche fehlgeschlagen: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                    navController.popBackStack()
                },
                onBackClick = { navController.popBackStack() }
            )
        }



        composable(NavRoutes.CONNECTIONS) {
            ConnectionsScreen(
                transportManager = transportManager,
                onBackClick = { navController.popBackStack() },
                onPeerClick = { peerId, peerName ->
                    val normPeerId = peerId.split("@").first()
                    currentChatPeerId = normPeerId
                    currentMessages = allMessages[normPeerId] ?: emptyList()
                    navController.navigate(NavRoutes.chatDetail(normPeerId, peerName))
                }
            )
        }

        // =====================================================================
        // ContactListScreen – Alle gespeicherten Kontakte anzeigen/verwalten
        // =====================================================================
        composable(NavRoutes.CONTACT_LIST) {
            ContactListScreen(
                contacts = savedContacts,
                onBackClick = { navController.popBackStack() },
                onContactClick = { contact ->
                    navController.navigate(NavRoutes.contactDetail(contact.id))
                },
                onDeleteContact = { contactId ->
                    val updatedContacts = contactRepository.removeContact(contactId)
                    savedContacts = updatedContacts
                },
                onStartChat = { peerId, name ->
                    val normPeerId = peerId.split("@").first()
                    currentChatPeerId = normPeerId
                    currentMessages = allMessages[normPeerId] ?: emptyList()
                    navController.navigate(NavRoutes.chatDetail(normPeerId, name))
                },
                onAddContact = { peerId, name, ip, port ->
                    val newContact = contactRepository.createContact(
                        peerId = peerId,
                        name = name,
                        ipAddress = ip,
                        port = port
                    )
                    val updatedList = contactRepository.addOrUpdateContact(newContact)
                    savedContacts = updatedList
                    Log.i(TAG, "[CrisixApp] ✅ Kontakt manuell hinzugefügt: $name ($peerId)")
                }
            )
        }

        // =====================================================================
        // ContactDetailScreen – Einzelnen Kontakt bearbeiten
        // =====================================================================
        composable(
            route = NavRoutes.CONTACT_DETAIL,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            val contact = savedContacts.find { it.id == contactId }

            if (contact != null) {
                ContactDetailScreen(
                    contact = contact,
                    onBackClick = { navController.popBackStack() },
                    onSave = { updatedContact ->
                        val updatedList = contactRepository.addOrUpdateContact(updatedContact)
                        savedContacts = updatedList
                    },
                    onDelete = { id ->
                        val updatedList = contactRepository.removeContact(id)
                        savedContacts = updatedList
                        navController.popBackStack()
                    },
                    onStartChat = { peerId, name ->
                        val normPeerId = peerId.split("@").first()
                        currentChatPeerId = normPeerId
                        currentMessages = allMessages[normPeerId] ?: emptyList()
                        navController.navigate(NavRoutes.chatDetail(normPeerId, name))
                    }
                )
            }
        }
    }
}

// ============================================================
// Hilfsfunktionen für QR-Code-Parsing
// ============================================================

/**
 * Extrahiert die Peer-ID aus einem Crisix-QR-Code.
 * Format: "crisix://contact?key=<peerId>&name=<name>&ip=<ip>&port=<port>"
 */
private fun extractPeerIdFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("key")
    } catch (e: Exception) {
        null
    }
}

/**
 * Extrahiert den Namen aus einem Crisix-QR-Code.
 * Format: "crisix://contact?key=<peerId>&name=<name>&ip=<ip>&port=<port>"
 */
private fun extractNameFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("name")
    } catch (e: Exception) {
        null
    }
}

/**
 * Extrahiert die IP-Adresse aus einem Crisix-QR-Code (optional).
 * Format: "crisix://contact?key=<peerId>&name=<name>&ip=<ip>&port=<port>"
 */
private fun extractIpFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("ip")
    } catch (e: Exception) {
        null
    }
}

/**
 * Extrahiert den Port aus einem Crisix-QR-Code (optional).
 * Format: "crisix://contact?key=<peerId>&name=<name>&ip=<ip>&port=<port>"
 */
private fun extractPortFromQr(content: String): Int? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("port")?.toIntOrNull()
    } catch (e: Exception) {
        null
    }
}


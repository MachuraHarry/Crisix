package com.messenger.crisix.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.messenger.crisix.transport.DnsTunnelTransport
import com.messenger.crisix.transport.DummyTransport
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
import com.messenger.crisix.ui.screens.SettingsScreen
import com.messenger.crisix.ui.screens.UserProfile
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun CrisixApp(
    onLanguageChanged: (LocaleHelper.AppLanguage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val transportManager = remember { TransportManager() }

    // Transport-Einstellungen
    var transportSettings by remember {
        mutableStateOf(
            mapOf(
                TransportType.INTERNET to true,
                TransportType.WIFI_DIRECT to true,
                TransportType.BLUETOOTH_MESH to true,
                TransportType.SMS to false,
                TransportType.DNS_TUNNEL to false,
                TransportType.LORA to false
            )
        )
    }

    // Stabile Geräte-ID für die gesamte App-Session
    val deviceId = remember { UUID.randomUUID().toString() }
    // Standard-Anzeigename: erste 8 Zeichen der Geräte-ID (wenn kein Name gesetzt)
    val defaultDisplayName = deviceId.take(8)

    // Benutzerprofil
    var userProfile by remember { mutableStateOf(UserProfile()) }

    // States für Nachrichten und aktiven Chat
    val allMessages = remember { mutableStateMapOf<String, List<Message>>() }
    var currentMessages by remember { mutableStateOf(emptyList<Message>()) }
    var currentChatPeerId by remember { mutableStateOf("") }

    // State für Netzwerkscan
    var isScanning by remember { mutableStateOf(false) }

    // =========================================================================
    // ContactRepository für dauerhafte Kontaktspeicherung
    // =========================================================================
    val context = LocalContext.current
    val contactRepository = remember(context) {
        ContactRepository(context)
    }

    // Gespeicherte Kontakte (aus SharedPreferences)
    var savedContacts by remember { mutableStateOf(contactRepository.loadContacts()) }

    // =========================================================================
    // Transporte initialisieren und starten
    // =========================================================================
    LaunchedEffect(Unit) {
        val displayName = userProfile.name.ifBlank { defaultDisplayName }

        val wifiTransport = WifiTransport(
            deviceName = displayName
        )
        transportManager.registerTransport(wifiTransport)

        val internetTransport = InternetTransport(
            deviceName = displayName
        )
        transportManager.registerTransport(internetTransport)

        val dummyTransport = DummyTransport()
        transportManager.registerTransport(dummyTransport)

        // DNS-Tunnel-Transport (für den Fall, dass Internet/WLAN blockiert ist)
        val dnsTunnelTransport = DnsTunnelTransport(
            localPeerId = deviceId,
            serverDomain = "crisix-dns.onrender.com",
            useHttpApi = true // HTTP-API ist zuverlässiger als UDP-DNS
        )
        transportManager.registerTransport(dnsTunnelTransport)

        // ⚠️ WICHTIG: Message-Listener VOR startAll() registrieren!
        // Sonst verpasst der Listener Nachrichten, die der DNS-Tunnel-Polling-Job
        // sofort nach dem Start empfängt.
        transportManager.registerMessageListener { peerId, data ->
            val messageText = String(data)
            val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            val displayText = try {
                val json = JSONObject(messageText)
                if (json.has("type") && json.getString("type") == "message") {
                    json.getString("text")
                } else {
                    messageText
                }
            } catch (e: Exception) {
                messageText
            }

            val newMessage = Message(
                id = "incoming-${System.currentTimeMillis()}",
                text = displayText,
                isFromMe = false,
                timestamp = timeStamp
            )

            // Nachricht unter der Peer-ID speichern
            val existingMessages = allMessages[peerId] ?: emptyList()
            allMessages[peerId] = existingMessages + newMessage

            // Wenn die Nachricht von uns selbst kommt (Echo), auch im Echo-Chat anzeigen
            if (peerId == deviceId) {
                val echoMessages = allMessages["echo-self"] ?: emptyList()
                allMessages["echo-self"] = echoMessages + newMessage
            }

            if (currentChatPeerId == peerId || currentChatPeerId == "echo-self") {
                currentMessages = allMessages[currentChatPeerId] ?: emptyList()
            }
        }

        // Jetzt erst die Transporte starten (Listener ist bereits registriert)
        transportManager.startAll()
        transportManager.selectBestTransport()
        transportManager.startPeerDiscovery()
        transportManager.startPeriodicReevaluation()
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
        // Kurz warten bis der InternetTransport gestartet ist
        kotlinx.coroutines.delay(2000)
        localPeerId = com.messenger.crisix.transport.internet.Libp2pManager.localPeerId
        localPort = com.messenger.crisix.transport.internet.Libp2pManager.localPort
    }

    // Chat-Liste generieren
    val chats = remember(discoveredPeers, activeTransport) {
        val chatList = mutableListOf<ChatPreview>()

        for (peer in discoveredPeers) {
            val peerMessages = allMessages[peer.id] ?: emptyList()
            val lastMsg = peerMessages.lastOrNull()
            chatList.add(
                ChatPreview(
                    id = peer.id,
                    name = peer.name,
                    lastMessage = lastMsg?.text ?: "Verbunden via WLAN",
                    timestamp = lastMsg?.timestamp ?: "Jetzt",
                    unreadCount = 0,
                    transportType = activeTransport?.type
                )
            )
        }

        // Echo-Chat für DNS-Tunnel-Tests (mit sich selbst schreiben)
        val echoPeerId = "echo-self"
        val echoMessages = allMessages[echoPeerId] ?: emptyList()
        val echoLastMsg = echoMessages.lastOrNull()
        chatList.add(
            ChatPreview(
                id = echoPeerId,
                name = "📡 Echo (DNS-Tunnel)",
                lastMessage = echoLastMsg?.text ?: "Teste den DNS-Tunnel",
                timestamp = echoLastMsg?.timestamp ?: "Jetzt",
                unreadCount = 0,
                transportType = TransportType.DNS_TUNNEL
            )
        )

        if (discoveredPeers.isEmpty()) {
            chatList.addAll(
                listOf(
                    ChatPreview("dummy-1", "Max Mustermann", "Hey, wie geht's?", "12:30", 2, activeTransport?.type),
                    ChatPreview("dummy-2", "Erika Musterfrau", "Bin gleich da!", "11:15", 0, activeTransport?.type),
                    ChatPreview("dummy-3", "Familie", "Danke für die Nachricht!", "Gestern", 5, activeTransport?.type),
                    ChatPreview("dummy-4", "Arbeitskollegen", "Besprechung morgen um 10 Uhr", "Gestern", 0, activeTransport?.type)
                )
            )
        }


        chatList
    }

    // Dummy-Nachrichten
    val dummyMessages = remember {
        mapOf(
            "dummy-1" to listOf(
                Message("m1", "Hey, wie geht's?", false, "12:30"),
                Message("m2", "Mir geht's gut, und dir?", true, "12:31"),
                Message("m3", "Auch gut! Hast du den Plan gesehen?", false, "12:32"),
                Message("m4", "Ja, sieht super aus!", true, "12:33")
            ),
            "dummy-2" to listOf(
                Message("m5", "Bin gleich da!", false, "11:15"),
                Message("m6", "Super, ich warte!", true, "11:16")
            ),
            "dummy-3" to listOf(
                Message("m7", "Hallo zusammen!", false, "18:00"),
                Message("m8", "Hi, wie war euer Tag?", true, "18:05"),
                Message("m9", "Danke für die Nachricht!", false, "18:10")
            ),
            "dummy-4" to listOf(
                Message("m10", "Besprechung morgen um 10 Uhr", false, "15:00"),
                Message("m11", "Ja, ich bin dabei.", true, "15:05"),
                Message("m12", "Ich auch!", false, "15:10")
            )
        )
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.CHAT_LIST,
        modifier = modifier
    ) {
        composable(NavRoutes.CHAT_LIST) {
            ChatListScreen(
                chats = chats,
                onChatClick = { chatId, chatName ->
                    // Suche den richtigen Peer: zuerst exakte ID, dann UUID@IP (QR-Code)
                    val realPeer = discoveredPeers.find { it.id == chatId }
                        ?: discoveredPeers.find { it.id.startsWith("$chatId@") }
                    val resolvedPeerId = realPeer?.id ?: chatId
                    currentChatPeerId = resolvedPeerId

                    val isRealPeer = realPeer != null
                    val isEchoChat = chatId == "echo-self"
                    currentMessages = if (isRealPeer) {
                        allMessages[resolvedPeerId] ?: emptyList()
                    } else if (isEchoChat) {
                        allMessages["echo-self"] ?: emptyList()
                    } else {
                        dummyMessages[chatId] ?: emptyList()
                    }

                    navController.navigate(NavRoutes.chatDetail(resolvedPeerId, chatName))
                },


                onSettingsClick = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onAddPeer = { ipAddress, displayName ->
                    kotlinx.coroutines.MainScope().launch {
                        transportManager.connectToPeer(ipAddress, displayName.ifBlank { null })
                            .onSuccess { peer ->
                                println("[CrisixApp] Manuell verbunden mit: ${peer.name} (${peer.id})")
                            }
                            .onFailure { error ->
                                println("[CrisixApp] Fehler beim Verbinden: ${error.message}")
                            }
                    }
                },
                onScanNetwork = {
                    if (!isScanning) {
                        isScanning = true
                        kotlinx.coroutines.MainScope().launch {
                            println("[CrisixApp] Starte Netzwerkscan...")
                            val foundPeers = transportManager.scanLocalNetwork()
                            println("[CrisixApp] Netzwerkscan abgeschlossen: ${foundPeers.size} Peer(s) gefunden")
                            isScanning = false
                        }
                    }
                },
                isScanning = isScanning,
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
                        timestamp = timeStamp
                    )

                    currentMessages = currentMessages + newMessage

                    val existingMessages = allMessages[chatId] ?: emptyList()
                    allMessages[chatId] = existingMessages + newMessage

                    // Prüfe, ob der Peer ein echter Peer ist (auch UUID@IP für QR-Codes)
                    val isRealPeer = discoveredPeers.any { it.id == chatId }
                            || discoveredPeers.any { it.id.startsWith("$chatId@") }
                    val isEchoChat = chatId == "echo-self"
                    if (isRealPeer || isEchoChat) {
                        val jsonMessage = JSONObject().apply {
                            put("type", "message")
                            put("text", text)
                            put("timestamp", timeStamp)
                            put("sender", userProfile.name.ifBlank { "Crisix-User" })
                        }

                        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                            if (isEchoChat) {
                                // Echo-Chat: Sende NUR den Text (kein JSON) über DNS-Tunnel
                                // DNS hat max 253 Zeichen Domain-Länge, daher nur Kurznachrichten
                                val dnsTransport = transportManager.getTransportByType(TransportType.DNS_TUNNEL)
                                if (dnsTransport != null) {
                                    // Nur den reinen Text senden, kein JSON-Overhead
                                    dnsTransport.send(deviceId, text.toByteArray())
                                        .onSuccess {
                                            println("[CrisixApp] ✅ Echo-Nachricht via DNS-Tunnel gesendet: $text")
                                        }
                                        .onFailure { error ->
                                            println("[CrisixApp] ❌ Echo-Fehler: ${error.message}")
                                        }
                                } else {
                                    println("[CrisixApp] ❌ DNS-Tunnel-Transport nicht gefunden")
                                }
                            } else {
                                transportManager.sendMessage(chatId, jsonMessage.toString().toByteArray())
                                    .onFailure { error ->
                                        println("[CrisixApp] Fehler beim Senden: ${error.message}")
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
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.ADD_CONTACT) { backStackEntry ->
            AddContactScreen(
                transportManager = transportManager,
                onBackClick = { navController.popBackStack() },
                onContactAdded = { peerId, name ->
                    println("[CrisixApp] Neuer Kontakt hinzugefügt: $name ($peerId)")
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
                    println("[CrisixApp] QR-Code gescannt: $qrContent")
                    // PeerId (Fingerprint), Name und IP aus dem QR-Code extrahieren
                    val peerId: String? = extractPeerIdFromQr(qrContent)
                    val name: String? = extractNameFromQr(qrContent)
                    val ip: String? = extractIpFromQr(qrContent)
                    val port: Int? = extractPortFromQr(qrContent)

                    if (peerId != null) {
                        val displayName = name ?: peerId.take(8)
                        println("[CrisixApp] QR-Kontakt: $displayName (Fingerprint: ${peerId.take(16)}..., IP: $ip, Port: $port)")

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
                        println("[CrisixApp] ✅ Kontakt gespeichert: $displayName ($peerId)")

                        // ============================================================
                        // Serverlose Verbindungsstrategie (oberste Priorität)
                        // ============================================================
                        // 1. Internet (DHT) – Peer über Fingerprint in der globalen DHT suchen
                        //    → Kein Server nötig, die DHT ist selbstorganisierend
                        // 2. WifiTransport – Direkte IP-Verbindung (falls IP im QR-Code)
                        //    → Lokales Netzwerk, kein Internet nötig
                        // 3. Netzwerkscan – Peer im lokalen Netzwerk suchen
                        //    → Fallback, wenn IP nicht bekannt
                        // ============================================================

                        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                            var connected = false

                            // === Schritt 2: Internet (DHT) – Serverlos, globale P2P ===
                            val internetTransport = transportManager.getTransportByType(
                                com.messenger.crisix.transport.TransportType.INTERNET
                            ) as? com.messenger.crisix.transport.internet.InternetTransport
                            if (internetTransport != null) {
                                for (attempt in 1..3) {
                                    if (connected) break
                                    try {
                                        println("[CrisixApp] DHT-Suche Versuch $attempt/3 für $displayName (Fingerprint: ${peerId.take(16)}...)")
                                        val result = internetTransport.connectToPeerById(peerId, displayName)
                                        if (result.isSuccess) {
                                            val peer = result.getOrNull() as com.messenger.crisix.transport.Peer
                                            println("[CrisixApp] ✅ DHT-Verbindung erfolgreich (Versuch $attempt): ${peer.name} (${peer.id})")
                                            connected = true
                                        } else if (attempt < 3) {
                                            println("[CrisixApp] DHT-Versuch $attempt fehlgeschlagen, warte 2s auf DHT-Registrierung des Peers...")
                                            kotlinx.coroutines.delay(2000)
                                        }
                                    } catch (e: Exception) {
                                        println("[CrisixApp] DHT-Versuch $attempt fehlgeschlagen: ${e.message}")
                                        if (attempt < 3) {
                                            kotlinx.coroutines.delay(2000)
                                        }
                                    }
                                }
                            }

                            // === Schritt 3: WifiTransport (direkte IP) ===
                            if (!connected && ip != null) {
                                try {
                                    println("[CrisixApp] Versuche direkte IP-Verbindung zu $ip:$port für $displayName")
                                    val result = transportManager.connectToPeer(ip, displayName, port)
                                    if (result.isSuccess) {
                                        val peer = result.getOrNull() as com.messenger.crisix.transport.Peer
                                        println("[CrisixApp] ✅ IP-Verbindung erfolgreich: ${peer.name} (${peer.id})")
                                        connected = true
                                    }
                                } catch (e: Exception) {
                                    println("[CrisixApp] IP-Verbindung fehlgeschlagen: ${e.message}")
                                }
                            }

                            // === Peer-Adresse in Registry speichern ===
                            if (connected && ip != null) {
                                try {
                                    val internetTransport = transportManager.getTransportByType(
                                        com.messenger.crisix.transport.TransportType.INTERNET
                                    ) as? com.messenger.crisix.transport.internet.InternetTransport
                                    if (internetTransport != null) {
                                        internetTransport.registerPeerAddress(peerId, ip, port ?: 0)
                                        println("[CrisixApp] ✅ Peer-Adresse in Registry gespeichert: $peerId -> $ip:${port ?: 0}")
                                    }
                                } catch (e: Exception) {
                                    println("[CrisixApp] Fehler beim Speichern der Peer-Adresse: ${e.message}")
                                }
                            }

                            // === Schritt 4: Netzwerkscan (lokale Suche) ===
                            if (!connected) {
                                try {
                                    println("[CrisixApp] Starte Netzwerkscan für $displayName...")
                                    val foundPeers = transportManager.scanLocalNetwork()
                                    val matchedPeer = foundPeers.find { it.id.startsWith(peerId) }
                                    if (matchedPeer != null) {
                                        println("[CrisixApp] ✅ Peer via Scan gefunden: ${matchedPeer.name} (${matchedPeer.id})")
                                        connected = true
                                    } else {
                                        println("[CrisixApp] Peer $displayName nicht im lokalen Netzwerk gefunden")
                                    }
                                } catch (e: Exception) {
                                    println("[CrisixApp] Netzwerkscan fehlgeschlagen: ${e.message}")
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
                    currentChatPeerId = peerId
                    currentMessages = allMessages[peerId] ?: emptyList()
                    navController.navigate(NavRoutes.chatDetail(peerId, peerName))
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
                    currentChatPeerId = peerId
                    currentMessages = allMessages[peerId] ?: emptyList()
                    navController.navigate(NavRoutes.chatDetail(peerId, name))
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
                    println("[CrisixApp] ✅ Kontakt manuell hinzugefügt: $name ($peerId)")
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
                        currentChatPeerId = peerId
                        currentMessages = allMessages[peerId] ?: emptyList()
                        navController.navigate(NavRoutes.chatDetail(peerId, name))
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


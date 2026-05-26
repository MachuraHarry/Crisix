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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.messenger.crisix.LocaleHelper
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

    // Transporte initialisieren und starten
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

        transportManager.startAll()
        transportManager.selectBestTransport()
        transportManager.startPeerDiscovery()
        transportManager.startPeriodicReevaluation()

        // Message-Listener
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

            val existingMessages = allMessages[peerId] ?: emptyList()
            allMessages[peerId] = existingMessages + newMessage

            if (currentChatPeerId == peerId) {
                currentMessages = allMessages[peerId] ?: emptyList()
            }
        }
    }

    val activeTransport by transportManager.activeTransport.collectAsState()
    val discoveredPeers by transportManager.discoveredPeers.collectAsState()
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
                    currentChatPeerId = chatId

                    val isRealPeer = discoveredPeers.any { it.id == chatId }
                    currentMessages = if (isRealPeer) {
                        allMessages[chatId] ?: emptyList()
                    } else {
                        dummyMessages[chatId] ?: emptyList()
                    }

                    navController.navigate(NavRoutes.chatDetail(chatId, chatName))
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
                onConnectionsClick = { navController.navigate(NavRoutes.CONNECTIONS) }
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

                    val isRealPeer = discoveredPeers.any { it.id == chatId }
                    if (isRealPeer) {
                        val jsonMessage = JSONObject().apply {
                            put("type", "message")
                            put("text", text)
                            put("timestamp", timeStamp)
                            put("sender", userProfile.name.ifBlank { "Crisix-User" })
                        }

                        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                            transportManager.sendMessage(chatId, jsonMessage.toString().toByteArray())
                                .onFailure { error ->
                                    println("[CrisixApp] Fehler beim Senden: ${error.message}")
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
            val savedStateHandle = backStackEntry.savedStateHandle
            val qrPeerId = savedStateHandle.get<String>("qr_peer_id")
            val qrPeerName = savedStateHandle.get<String>("qr_peer_name")

            // QR-Code-Daten verarbeiten, sobald sie verfügbar sind
            LaunchedEffect(qrPeerId) {
                if (qrPeerId != null) {
                    val displayName = qrPeerName ?: qrPeerId.take(8)
                    println("[CrisixApp] QR-Kontakt wird hinzugefügt: $displayName ($qrPeerId)")
                    // Peer als Kontakt speichern (wird später per mDNS/BLE verbunden)
                    transportManager.addContactPeer(qrPeerId, displayName)
                    // Zurück zur Chat-Liste
                    navController.popBackStack()
                }
            }

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
                    // PeerId und Name aus dem QR-Code extrahieren
                    val peerId: String? = extractPeerIdFromQr(qrContent)
                    val name: String? = extractNameFromQr(qrContent)
                    if (peerId != null) {
                        val displayName = name ?: peerId.take(8)
                        println("[CrisixApp] Neuer Kontakt via QR: $displayName ($peerId)")
                        // Daten an AddContactScreen zurückgeben via SavedStateHandle
                        navController.previousBackStackEntry?.savedStateHandle?.set("qr_peer_id", peerId)
                        navController.previousBackStackEntry?.savedStateHandle?.set("qr_peer_name", displayName)
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
    }
}

// ============================================================
// Hilfsfunktionen für QR-Code-Parsing
// ============================================================

/**
 * Extrahiert die Peer-ID aus einem Crisix-QR-Code.
 * Format: "crisix://contact?key=<peerId>&name=<name>"
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
 * Format: "crisix://contact?key=<peerId>&name=<name>"
 */
private fun extractNameFromQr(content: String): String? {
    return try {
        val uri = android.net.Uri.parse(content)
        uri.getQueryParameter("name")
    } catch (e: Exception) {
        null
    }
}

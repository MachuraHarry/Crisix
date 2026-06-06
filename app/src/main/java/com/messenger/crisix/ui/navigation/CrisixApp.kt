package com.messenger.crisix.ui.navigation

import android.content.Context
import timber.log.Timber
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.messenger.crisix.LocaleHelper
import com.messenger.crisix.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.ui.viewmodel.ChatListViewModel
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.data.toMessage
import com.messenger.crisix.e2ee.E2EEHandshakeOrchestrator
import com.messenger.crisix.message.MessageProcessor
import com.messenger.crisix.message.MessageSender
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportInitializer
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.data.SettingsKeys
import com.messenger.crisix.data.settingsDataStore
import com.messenger.crisix.transport.RelayTransport
import com.messenger.crisix.transport.internet.CryptoHelper
import com.messenger.crisix.transport.internet.InternetTransport
import com.messenger.crisix.transport.internet.Libp2pManager
import com.messenger.crisix.crypto.X3DHSession
import com.messenger.crisix.ui.screens.AddContactScreen
import com.messenger.crisix.ui.screens.ChatDetailScreen
import com.messenger.crisix.ui.screens.ChatListScreen
import com.messenger.crisix.ui.screens.ContactDetailScreen
import com.messenger.crisix.ui.screens.ContactListScreen
import com.messenger.crisix.ui.screens.ConnectionsScreen
import com.messenger.crisix.ui.components.HintStatus
import com.messenger.crisix.ui.components.Message
import com.messenger.crisix.ui.screens.MyIdScreen
import com.messenger.crisix.ui.screens.LogViewerScreen
import com.messenger.crisix.ui.screens.QrCodeScannerScreen
import com.messenger.crisix.ui.screens.OnboardingScreen
import com.messenger.crisix.ui.screens.PermissionSetupScreen
import com.messenger.crisix.ui.screens.SettingsScreen
import com.messenger.crisix.ui.screens.TransportSetupScreen
import com.messenger.crisix.ui.screens.UserProfile
import com.messenger.crisix.update.UpdateManager
import com.messenger.crisix.util.NotificationHelper
import com.messenger.crisix.util.lruMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrisixApp(
    notificationOpenChatId: String? = null,
    notificationOpenChatName: String? = null,
    onNotificationHandled: () -> Unit = {},
    deepLinkData: DeepLinkData? = null,
    onDeepLinkHandled: () -> Unit = {},
    onLanguageChanged: (LocaleHelper.AppLanguage) -> Unit = {},
    onChatOpened: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val TAG = "CrisixApp"
    val navController = rememberNavController()
    val transportManager = remember { TransportManager() }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    // Netzwerk-Monitor für Capability-Refresh bei WLAN/Mobile-Änderungen
    transportManager.initNetworkMonitor(context)

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

    // Transport-Einstellungen (Persistierung via SharedPreferences)
    fun loadTransportSettings(prefs: android.content.SharedPreferences): Map<TransportType, Boolean> {
        val stored = prefs.getString("enabled_transports", null)
        return if (stored != null) {
            val enabled = stored.split(",").filter { it.isNotEmpty() }.toSet()
            TransportType.entries.associateWith { it.name in enabled }
        } else {
            TransportType.entries.associateWith { it !in setOf(TransportType.SMS, TransportType.LORA) }
        }
    }

    fun saveTransportSettings(prefs: android.content.SharedPreferences, settings: Map<TransportType, Boolean>) {
        val enabled = settings.filter { it.value }.keys.joinToString(",") { it.name }
        prefs.edit().putString("enabled_transports", enabled).apply()
    }

    var transportSettings by remember {
        mutableStateOf(loadTransportSettings(setupPrefs))
    }

    // Benutzerprofil (aus SharedPreferences laden)
    val savedName = setupPrefs.getString("profile_name", "") ?: ""
    val savedStatus = setupPrefs.getString("profile_status", "Hallo! Ich bin bei Crisix.") ?: "Hallo! Ich bin bei Crisix."
    val savedColorInt = setupPrefs.getInt("profile_color", 0xFF00475D.toInt())
    var userProfile by remember {
        mutableStateOf(UserProfile(
            name = savedName,
            status = savedStatus,
            avatarColor = Color(
                red = android.graphics.Color.red(savedColorInt) / 255f,
                green = android.graphics.Color.green(savedColorInt) / 255f,
                blue = android.graphics.Color.blue(savedColorInt) / 255f,
                alpha = android.graphics.Color.alpha(savedColorInt) / 255f
            )
        ))
    }

    // States für Nachrichten und aktiven Chat
    val allMessages = remember { mutableStateMapOf<String, List<Message>>() }
    var currentMessages by remember { mutableStateOf(emptyList<Message>()) }
    var currentChatPeerId by remember { mutableStateOf("") }

    // Von Peers übermittelte Anzeigenamen (peerId → name)
    val incomingNames = remember { mutableStateMapOf<String, String>() }

    // In-Memory-Dedup für eingehende Nachrichten: "peerId:uiMessageId" → true
    val processedIncomingIds = remember { lruMap<String, Boolean>(10_000) }

    // State für Netzwerkscan

    // =========================================================================
    // ContactRepository für dauerhafte Kontaktspeicherung
    val contactRepository = remember(context) {
        ContactRepository(context)
    }

     // MessageRepository für dauerhafte Nachrichtenspeicherung (Room-DB)
     val messageRepository = remember(context) {
         MessageRepository(context)
     }

     // =========================================================================
     // E2EE-Manager für Ende-zu-Ende-Verschlüsselung
     // =========================================================================
      val e2eeManager = remember(context) {
          com.messenger.crisix.crypto.E2eeManager(context).also { it.initialize() }
      }

      // ACK-Validator für strikte Handshake-Validierung
      val ackValidator = remember { com.messenger.crisix.crypto.AckValidator() }

      val handshakeOrchestrator = remember(transportManager, e2eeManager) {
          E2EEHandshakeOrchestrator(transportManager, e2eeManager, scope)
      }

      // Message-Sender (extrahiert aus CrisixApp)
      val messageSender = remember(scope, transportManager, e2eeManager, messageRepository, handshakeOrchestrator) {
          MessageSender(context, scope, transportManager, e2eeManager, messageRepository, handshakeOrchestrator)
      }
      messageSender.setUserProfile(userProfile)

      // E2EE-Sessions pro Peer (peerId → true wenn Session aktiv)
      val e2eeSessions = remember { mutableStateMapOf<String, Boolean>() }

      // Letzter bekannter Transport pro Peer für eingehende Nachrichten
      val incomingTransports = remember { mutableStateMapOf<String, TransportType>() }

      // Vorhandene Sessions aus E2eeManager laden
      LaunchedEffect(Unit) {
          // Warten bis E2eeManager initialisiert ist
          kotlinx.coroutines.delay(500)
          // Alle Peers mit aktiver Session aus dem E2eeManager übernehmen
          val activePeers = e2eeManager.getActiveSessionPeers()
          for (peerId in activePeers) {
              e2eeSessions[peerId] = true
          }
          Log.i(TAG, "[CrisixApp] ${activePeers.size} E2EE-Sessions aus Persistenz geladen")
          
          // Retry-Callbacks für Handshake-Fehler initialisieren
          e2eeManager.setRetryCallbacks(
              onRetryAttempt = { peerId, attemptNumber, delayMs ->
                  Log.d(TAG, "[CrisixApp] 🔄 Handshake Retry ${attemptNumber}/5 für $peerId in ${delayMs}ms...")
                  // UI-Snackbar wird hier später hinzugefügt
              },
              onRetryExhausted = { peerId ->
                  Log.e(TAG, "[CrisixApp] ❌ Handshake fehlgeschlagen nach 5 Versuchen: $peerId")
                  // UI-Snackbar wird hier später hinzugefügt
              },
              onRetrySuccess = { peerId ->
                  Log.i(TAG, "[CrisixApp] ✅ Handshake erfolgreich nach Retry: $peerId")
              },
              onRetryTimeout = { peerId, attemptNumber ->
                  Log.w(TAG, "[CrisixApp] ⏱️ Timeout auf Versuch ${attemptNumber}: $peerId")
              },
              onRetryResend = { peerId, data ->
                  handshakeOrchestrator.resendHandshake(peerId, data)
              }
          )
      }

     // Gespeicherte Kontakte (aus SharedPreferences)
     var savedContacts by remember { mutableStateOf(contactRepository.loadContacts()) }

    // Angeheftete Chats (aus SharedPreferences, via Compose-State für Reaktivität)
    var pinnedChatIds by remember {
        val json = setupPrefs.getString("pinned_chats", "[]") ?: "[]"
        val initial = try {
            org.json.JSONArray(json).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse pinned chats from SharedPreferences")
            emptySet<String>()
        }
        mutableStateOf(initial)
    }

    fun savePinnedChats() {
        setupPrefs.edit()
            .putString("pinned_chats", org.json.JSONArray(pinnedChatIds.toList()).toString())
            .apply()
    }

    // =========================================================================
    // UnreadCounts aus der DB laden (für Chat-Liste)
    // =========================================================================
    val unreadCounts = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect
        val allEntities = messageRepository.loadAllMessages()
        val chatIds = allEntities.map { it.chatId }.distinct()
        for (chatId in chatIds) {
            val count = messageRepository.getUnreadCount(chatId)
            if (count > 0) {
                unreadCounts[chatId] = count
            }
        }
    }

    // =========================================================================
    // DeepLink aus Notification verarbeiten (wenn App über Notification geöffnet)
    // =========================================================================
    LaunchedEffect(notificationOpenChatId, isSetupComplete) {
        if (notificationOpenChatId != null && isSetupComplete) {
            val chatId = notificationOpenChatId
            val chatName = notificationOpenChatName ?: chatId.take(8)
            val normChatId = chatId.split("@").first()
            currentChatPeerId = normChatId
            currentMessages = allMessages[normChatId] ?: emptyList()

            // Unread-Reset beim Öffnen über Notification
            scope.launch {
                messageRepository.resetUnreadCount(normChatId)
                unreadCounts[normChatId] = 0
            }

            navController.navigate(NavRoutes.chatDetail(normChatId, chatName))
            onNotificationHandled()
        }
    }

    // =========================================================================
    // Hilfsfunktion: Notification + UnreadCount für eingehende Nachrichten
    // =========================================================================
    fun handleIncomingNotification(
        normalizedPeerId: String,
        senderName: String?,
        notificationText: String
    ) {
        val peerDisplayName = senderName ?: incomingNames[normalizedPeerId] ?: normalizedPeerId.take(8)

        // UnreadCount in DB erhöhen (nur wenn Chat nicht gerade aktiv ist)
        if (currentChatPeerId != normalizedPeerId) {
            scope.launch {
                messageRepository.incrementUnreadCount(normalizedPeerId)
                val currentCount = messageRepository.getUnreadCount(normalizedPeerId)
                unreadCounts[normalizedPeerId] = currentCount
            }
        }

        // Notification anzeigen
        NotificationHelper.showMessageNotification(
            context = context,
            peerName = peerDisplayName,
            messageText = notificationText,
            chatId = normalizedPeerId,
        )
    }

    // =========================================================================
    // Helper: mark all SENT outgoing messages as DELIVERED
    // =========================================================================

    // =========================================================================
    // Transporte initialisieren und starten (nur nach Setup)
    // =========================================================================
    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect

        val displayName = userProfile.name.ifBlank { defaultDisplayName }
        val enabledTypes = transportSettings.filter { it.value }.keys

        val prefs = context.settingsDataStore.data.first()
        val relayServersJson = prefs[SettingsKeys.RELAY_SERVERS]
        val relayUrls = if (relayServersJson.isNullOrBlank()) {
            listOf("wss://crisix-dns.onrender.com/ws")
        } else {
            com.messenger.crisix.ui.screens.SettingsViewModel.parseRelayServers(relayServersJson).map { it.url }
        }

        // Transporte über TransportInitializer registrieren
        TransportInitializer.initializeTransports(
            transportManager = transportManager,
            deviceId = deviceId,
            displayName = displayName,
            context = context,
            relayUrls = relayUrls,
        )

        // ═══════════════════════════════════════════════════════════════
        // E2EE-Manager an TransportManager übergeben
        // ═══════════════════════════════════════════════════════════════
        transportManager.setE2eeManager(e2eeManager)

        // ⚠️ WICHTIG: Message-Listener VOR startAll() registrieren!
        // Sonst verpasst der Listener Nachrichten, die der DNS-Tunnel-Polling-Job
        // sofort nach dem Start empfängt.
        val messageProcessor = MessageProcessor(
            context = context, scope = scope, transportManager = transportManager,
            e2eeManager = e2eeManager, ackValidator = ackValidator,
            messageRepository = messageRepository,
            allMessages = allMessages,
            getCurrentMessages = { currentMessages },
            setCurrentMessages = { currentMessages = it },
            getCurrentChatPeerId = { currentChatPeerId }, incomingNames = incomingNames,
            incomingTransports = incomingTransports, e2eeSessions = e2eeSessions,
            processedIncomingIds = processedIncomingIds,
            handshakeOrchestrator = handshakeOrchestrator,
            unreadCounts = unreadCounts,
        ).apply {
            userProfileName = { userProfile.name }
            onNotificationNeeded = { peerId, senderName, preview ->
                handleIncomingNotification(peerId, senderName, preview)
            }
        }
        messageProcessor.registerListener()
        transportManager.onRetryAdd = { uiMessageId, peerId, data, retryCount ->
            scope.launch {
                messageRepository.savePendingMessage(
                    com.messenger.crisix.data.PendingMessageEntity(
                        uiMessageId = uiMessageId,
                        peerId = peerId,
                        data = data,
                        retryCount = retryCount,
                    )
                )
            }
        }
        transportManager.onRetryRemove = { uiMessageId ->
            scope.launch {
                messageRepository.deletePendingMessage(uiMessageId)
            }
        }

        // Persistierte Retry-Einträge aus der DB laden
        val pendingEntities = messageRepository.loadPendingMessages()
        if (pendingEntities.isNotEmpty()) {
            val retryEntries = pendingEntities.map { entity ->
                com.messenger.crisix.transport.TransportManager.RetryEntry(
                    uiMessageId = entity.uiMessageId,
                    peerId = entity.peerId,
                    data = entity.data,
                    retryCount = entity.retryCount,
                )
            }
            transportManager.loadPendingEntries(retryEntries)
        }

        // TransportManager über aktivierte Transporte informieren
        transportManager.setEnabledTransports(enabledTypes)

        // Jetzt erst die Transporte starten (Listener ist bereits registriert)
        transportManager.startAll()
        transportManager.selectBestTransport()
        transportManager.startPeriodicReevaluation()
        transportManager.startRetryJob()
        transportManager.startAckMonitor()
    }

    // Bestehende Nachrichten aus Room-DB laden
    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect
        val allEntities = messageRepository.loadAllMessages()
        val grouped = allEntities.groupBy { it.chatId }
        for ((chatId, entities) in grouped) {
            val messages = entities.map { it.toMessage() }
            allMessages[chatId] = messages
        }
    }

    // Delivery-Updates abonnieren
    LaunchedEffect(Unit) {
        transportManager.deliveryUpdates.collect { update ->
            val normChatId = update.peerId.split("@").first()
            val existing = allMessages[normChatId]
            if (existing != null) {
                val updated = existing.map { msg ->
                    if (msg.id == update.uiMessageId) {
                        val newStatus = when {
                            update.status == MessageStatus.DELIVERED && msg.status != MessageStatus.READ -> MessageStatus.DELIVERED
                            update.status == MessageStatus.SENT && msg.status != MessageStatus.DELIVERED && msg.status != MessageStatus.READ -> MessageStatus.SENT
                            update.status == MessageStatus.FAILED && msg.status == MessageStatus.SENDING -> MessageStatus.FAILED
                            else -> msg.status
                        }
                        scope.launch {
                            messageRepository.updateMessageStatus(msg.id, newStatus, update.transport?.name)
                        }
                        msg.copy(status = newStatus, transport = update.transport)
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

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        val autoUpdate = prefs[SettingsKeys.AUTO_UPDATE_ENABLED] ?: true
        if (autoUpdate) {
            UpdateManager.checkForUpdate(context)
        }
    }

    LaunchedEffect(Unit) {
        context.settingsDataStore.data.collect { prefs ->
            val json = prefs[SettingsKeys.RELAY_SERVERS]
            if (!json.isNullOrBlank()) {
                val urls = com.messenger.crisix.ui.screens.SettingsViewModel.parseRelayServers(json).map { it.url }
                if (urls.isNotEmpty()) {
                    val relayTransport = transportManager.getTransportByType(TransportType.RELAY) as? RelayTransport
                    relayTransport?.updateUrls(urls)
                }
            }
        }
    }

    LaunchedEffect(deepLinkData, isSetupComplete) {
        val data = deepLinkData ?: return@LaunchedEffect
        if (!isSetupComplete) return@LaunchedEffect

        delay(1000)

        when (data.type) {
            DeepLinkData.Type.CONTACT -> {
                val peerId = data.peerId ?: return@LaunchedEffect
                val contact = contactRepository.createContact(
                    peerId = peerId,
                    name = data.peerName,
                    ipAddress = data.ipAddress,
                    port = data.port,
                )
                savedContacts = contactRepository.addOrUpdateContact(contact)
                Log.i(TAG, "[CrisixApp] DeepLink: Kontakt gespeichert: ${data.peerName} ($peerId)")

                scope.launch(Dispatchers.IO) {
                    var connected = false
                    if (data.ipAddress != null) {
                        try {
                            val result = transportManager.connectToPeer(data.ipAddress, data.peerName, data.port)
                            connected = result.isSuccess
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to connect to peer via IP from deep link: ${data.ipAddress}")
                        }
                    }
                    if (!connected) {
                        val internetTransport = transportManager.getTransportByType(TransportType.INTERNET) as? InternetTransport
                        internetTransport?.connectToPeerById(peerId, data.peerName)
                    }
                }

                onChatOpened(peerId)
                currentChatPeerId = peerId
                currentMessages = allMessages[peerId] ?: emptyList()
                scope.launch {
                    delay(500)
                    navController.navigate(NavRoutes.chatDetail(peerId, data.peerName))
                }
            }
            DeepLinkData.Type.HANDSHAKE -> {
                val bundleB64 = data.handshakeBundleB64 ?: return@LaunchedEffect
                val peerId = data.peerId ?: return@LaunchedEffect
                try {
                    val bundleJson = String(Base64.decode(bundleB64, Base64.URL_SAFE))
                    val bundle = X3DHSession.PreKeyBundle.fromJson(bundleJson)
                    val contact = contactRepository.createContact(peerId = peerId, name = data.peerName)
                    savedContacts = contactRepository.addOrUpdateContact(contact)

                    scope.launch(Dispatchers.IO) {
                        val result = e2eeManager.processHandshakeAsResponder(peerId = peerId, peerBundle = bundle)
                        if (result != null) {
                            Log.i(TAG, "[CrisixApp] DeepLink: E2EE-Session per Handshake aufgebaut mit $peerId")
                            e2eeManager.completeHandshakeRetry(peerId)
                            transportManager.sendMessage(peerId, result.toByteArray())
                        } else {
                            Log.w(TAG, "[CrisixApp] DeepLink: Handshake fehlgeschlagen für $peerId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[CrisixApp] DeepLink: Fehler beim Handshake: ${e.message}")
                }
            }
        }

        onDeepLinkHandled()
    }

    CrisixNavHost(
        navController = navController,
        isSetupComplete = isSetupComplete,
        onSetupComplete = {
            setupPrefs.edit().putBoolean("setup_complete", true).apply()
            isSetupComplete = true
        },
        onUsernameSet = { username ->
            userProfile = userProfile.copy(name = username)
        },
        allMessages = allMessages,
        onCurrentChatPeerIdChange = { currentChatPeerId = it },
        onCurrentMessagesChange = { currentMessages = it },
        incomingNames = incomingNames,
        incomingTransports = incomingTransports,
        unreadCounts = unreadCounts,
        e2eeSessions = e2eeSessions,
        transportManager = transportManager,
        discoveredPeers = discoveredPeers,
        connectionStatuses = connectionStatuses,
        activeTransportType = activeTransport?.type,
        capabilities = capabilities,
        transportSettings = transportSettings,
        onTransportToggle = { type, enabled ->
            val newSettings = transportSettings + (type to enabled)
            transportSettings = newSettings
            saveTransportSettings(setupPrefs, newSettings)
            scope.launch {
                val enabledSet = newSettings.filter { it.value }.keys
                transportManager.setEnabledTransports(enabledSet)
            }
        },
        userProfile = userProfile,
        onProfileUpdate = { updatedProfile ->
            userProfile = updatedProfile
            val colorInt = android.graphics.Color.argb(
                (updatedProfile.avatarColor.alpha * 255).toInt(),
                (updatedProfile.avatarColor.red * 255).toInt(),
                (updatedProfile.avatarColor.green * 255).toInt(),
                (updatedProfile.avatarColor.blue * 255).toInt()
            )
            setupPrefs.edit()
                .putString("profile_name", updatedProfile.name)
                .putString("profile_status", updatedProfile.status)
                .putInt("profile_color", colorInt)
                .apply()
        },
        onLanguageChanged = onLanguageChanged,
        e2eeManager = e2eeManager,
        messageSender = messageSender,
        handshakeOrchestrator = handshakeOrchestrator,
        messageRepository = messageRepository,
        savedContacts = savedContacts,
        contactRepository = contactRepository,
        onSavedContactsChange = { savedContacts = it },
        pinnedChatIds = pinnedChatIds,
        onPinChat = { chatId ->
            pinnedChatIds = if (chatId in pinnedChatIds) {
                pinnedChatIds - chatId
            } else {
                pinnedChatIds + chatId
            }
            savePinnedChats()
        },
        localPeerId = localPeerId,
        localPort = localPort,
        defaultDisplayName = defaultDisplayName,
        onChatOpened = onChatOpened,
        scope = scope,
        TAG = TAG,
        modifier = modifier,
    )
}


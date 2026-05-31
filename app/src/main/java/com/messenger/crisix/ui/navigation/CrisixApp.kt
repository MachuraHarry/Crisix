package com.messenger.crisix.ui.navigation

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.messenger.crisix.LocaleHelper
import com.messenger.crisix.R
import com.messenger.crisix.data.ChatEntity
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
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
import com.messenger.crisix.ui.screens.HintStatus
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.screens.MyIdScreen
import com.messenger.crisix.ui.screens.LogViewerScreen
import com.messenger.crisix.ui.screens.QrCodeScannerScreen
import com.messenger.crisix.ui.screens.OnboardingScreen
import com.messenger.crisix.ui.screens.PermissionSetupScreen
import com.messenger.crisix.ui.screens.SettingsScreen
import com.messenger.crisix.ui.screens.TransportSetupScreen
import com.messenger.crisix.ui.screens.UserProfile
import com.messenger.crisix.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    onLanguageChanged: (LocaleHelper.AppLanguage) -> Unit = {},
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

    // Benutzerprofil
    var userProfile by remember { mutableStateOf(UserProfile()) }

    // States für Nachrichten und aktiven Chat
    val allMessages = remember { mutableStateMapOf<String, List<Message>>() }
    var currentMessages by remember { mutableStateOf(emptyList<Message>()) }
    var currentChatPeerId by remember { mutableStateOf("") }

    // Von Peers übermittelte Anzeigenamen (peerId → name)
    val incomingNames = remember { mutableStateMapOf<String, String>() }

    // In-Memory-Dedup für eingehende Nachrichten: "peerId:uiMessageId" → true
    val processedIncomingIds = remember { java.util.concurrent.ConcurrentHashMap<String, Boolean>() }

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

      // E2EE-Sessions pro Peer (peerId → true wenn Session aktiv)
      val e2eeSessions = remember { mutableStateMapOf<String, Boolean>() }

     // Pending Handshakes: peerId → HandshakeInitData (für completeHandshakeAsInitiator)
     // Wird gespeichert, wenn createHandshake() aufgerufen wird, und gelöscht,
     // wenn das ACK vom Responder kommt oder der Handshake fehlschlägt.
      val pendingHandshakes = remember { mutableStateMapOf<String, com.messenger.crisix.crypto.HandshakeInitData>() }

      // Letzter bekannter Transport pro Peer für eingehende Nachrichten
      val incomingTransports = remember { mutableMapOf<String, TransportType>() }

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
              }
          )
      }

     // Gespeicherte Kontakte (aus SharedPreferences)
     var savedContacts by remember { mutableStateOf(contactRepository.loadContacts()) }

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
    // Transporte initialisieren und starten (nur nach Setup)
    // =========================================================================
    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect

        val displayName = userProfile.name.ifBlank { defaultDisplayName }
        val enabledTypes = transportSettings.filter { it.value }.keys

        // Immer alle Transporte registrieren (für Empfang),
        // aber nur aktivierte werden in sendMessage() verwendet und gestartet.
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

        val dnsTunnelTransport = DnsTunnelTransport(
            localPeerId = deviceId,
            serverDomain = "crisix-dns.onrender.com",
            useHttpApi = true
        )
        transportManager.registerTransport(dnsTunnelTransport)

        val relayTransport = RelayTransport(
            localPeerId = deviceId,
            relayUrl = "wss://crisix-dns.onrender.com/ws"
        )
        transportManager.registerTransport(relayTransport)

        val bleTransport = BleTransport(
            localPeerId = deviceId,
            appContext = context
        )
        transportManager.registerTransport(bleTransport)

         // ═══════════════════════════════════════════════════════════════
         // E2EE-Manager an TransportManager übergeben
         // ═══════════════════════════════════════════════════════════════
         transportManager.setE2eeManager(e2eeManager)

         // ⚠️ WICHTIG: Message-Listener VOR startAll() registrieren!
         // Sonst verpasst der Listener Nachrichten, die der DNS-Tunnel-Polling-Job
         // sofort nach dem Start empfängt.
          transportManager.registerMessageListener { peerId, data, incomingTransport ->
             // Normalisieren: WifiTransport liefert "fingerprint@ip", Chats nutzen nur den Fingerprint
              val normalizedPeerId = peerId.split("@").first()
              incomingTransports[normalizedPeerId] = incomingTransport

             // ═══════════════════════════════════════════════════════════════
             // ACK-PROTOKOLL: Automatische Empfangsbestätigung
             // ═══════════════════════════════════════════════════════════════
             // Prüfe ob die Nachricht eine uiMessageId enthält (angehängt mit \u0000)
             // Wenn ja → Automatisch ACK zurückschicken
             var messageData = data
             var ackMessageId: String? = null
             
             val messageText = String(data)
             if (messageText.contains('\u0000')) {
                 try {
                     val parts = messageText.split('\u0000')
                     if (parts.size == 2) {
                         ackMessageId = parts[1]
                         messageData = parts[0].toByteArray()
                         Log.i(TAG, "[CrisixApp] ACK-MessageId extrahiert: $ackMessageId")
                     }
                 } catch (_: Exception) {}
             }
             
              // ACK zurückschicken (asynchron, blockiert nicht den Listener)
              if (ackMessageId != null) {
                  scope.launch {
                      try {
                          val ackPayload = JSONObject().apply {
                              put("type", "crisix_ack")
                              put("messageId", ackMessageId)
                          }.toString().toByteArray()
                          transportManager.sendMessage(normalizedPeerId, ackPayload)
                          Log.i(TAG, "[CrisixApp] ACK versendet für $ackMessageId an $normalizedPeerId")
                      } catch (e: Exception) {
                          Log.w(TAG, "[CrisixApp] Fehler beim Versenden von ACK: ${e.message}")
                      }
                  }
              }

              // ═══════════════════════════════════════════════════════════════
              // DEDUP: (peerId, uiMessageId) schon gesehen → ignorieren
              // ═══════════════════════════════════════════════════════════════
              if (ackMessageId != null) {
                  val dedupKey = "$normalizedPeerId:$ackMessageId"
                  if (processedIncomingIds.putIfAbsent(dedupKey, true) != null) {
                      Log.i(TAG, "[CrisixApp] Duplikat ignoriert: $dedupKey")
                      return@registerMessageListener
                  }
                  // Cleanup: Map alle 10k Einträge leeren (unwahrscheinlich, aber sicher)
                  if (processedIncomingIds.size > 10_000) {
                      processedIncomingIds.clear()
                  }
              }

              val messageTextFinal = String(messageData)
             val now = System.currentTimeMillis()
             val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

              var senderName: String? = null
              val messageType = try {
                  JSONObject(messageTextFinal).optString("type", "text")
              } catch (_: Exception) {
                  "text"
              }

              // ═══════════════════════════════════════════════════════════════
              // ACK-PROTOKOLL: Explizite Empfangsbestätigung verarbeiten
              // ═══════════════════════════════════════════════════════════════
              if (messageType == "crisix_ack") {
                  try {
                      val json = JSONObject(messageTextFinal)
                      val ackedMsgId = json.getString("messageId")
                      Log.i(TAG, "[CrisixApp] ACK empfangen für $ackedMsgId von $normalizedPeerId")

                      val peerMessages = allMessages[normalizedPeerId]
                      if (peerMessages != null) {
                          val updated = peerMessages.map { msg ->
                              if (msg.id == ackedMsgId && msg.isFromMe && msg.status != MessageStatus.DELIVERED) {
                                  scope.launch {
                                      messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                                  }
                                  msg.copy(status = MessageStatus.DELIVERED)
                              } else msg
                          }
                          allMessages[normalizedPeerId] = updated
                          if (currentChatPeerId == normalizedPeerId) {
                              currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                          }
                      }
                  } catch (e: Exception) {
                      Log.w(TAG, "[CrisixApp] Fehler beim Verarbeiten von ACK: ${e.message}")
                  }
                  return@registerMessageListener
              }

              // ═══════════════════════════════════════════════════════════════
              // E2EE-Handshake: Eingehende Session-Init-Anfrage verarbeiten
              // ═══════════════════════════════════════════════════════════════
              if (messageType == "crisix_e2ee_handshake") {
                  // Wenn bereits eine Session existiert → Session-Reset des Peers
                  if (e2eeSessions[normalizedPeerId] == true) {
                       Log.i(TAG, "[CrisixApp] Session-Reset von ${normalizedPeerId.take(8)} empfangen")
                       e2eeManager.closeSession(normalizedPeerId)
                       e2eeSessions.remove(normalizedPeerId)
                       val hintMsgId = "sys-reset-${normalizedPeerId}-peer"
                       val hintText = context.getString(R.string.e2ee_reset_peer_hint)
                       val hintMessage = Message(
                           id = hintMsgId,
                           text = hintText,
                           isFromMe = false,
                           timestamp = timeStamp,
                           timestampMillis = now,
                           status = MessageStatus.DELIVERED,
                           isSystemMessage = true,
                           hintStatus = HintStatus.LOADING,
                       )
                       val existingMsgs = allMessages[normalizedPeerId] ?: emptyList()
                       allMessages[normalizedPeerId] = existingMsgs + hintMessage
                       if (currentChatPeerId == normalizedPeerId) {
                           currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                       }
                       scope.launch {
                           messageRepository.addMessage(
                               id = hintMsgId,
                               chatId = normalizedPeerId,
                               text = hintText,
                               isFromMe = false,
                               timestamp = timeStamp,
                               timestampMillis = now,
                               status = MessageStatus.DELIVERED,
                               transport = null,
                               isSystemMessage = true,
                                hintStatus = HintStatus.LOADING.name,
                            )
                        }
                    }
                  try {
                      val json = JSONObject(messageTextFinal)
                      val handshakeData = json.getString("data")
                     // ═══════════════════════════════════════════════════════════════
                     // WICHTIG: Alices ephemeralen Key (EK_A) aus dem JSON extrahieren!
                     // Bob braucht EK_A für DH2 = DH(IK_B_priv, EK_A) und
                     // DH3 = DH(SPK_B_priv, EK_A).
                     // ═══════════════════════════════════════════════════════════════
                     val ephemeralKeyB64 = json.optString("ephemeralKey", null)
                     Log.i(TAG, "[CrisixApp] E2EE-Handshake empfangen von ${normalizedPeerId.take(8)}" +
                         if (ephemeralKeyB64 != null) " (mit EK_A)" else " (ohne EK_A)")

                      // Session als Responder starten
                      scope.launch(Dispatchers.IO) {
                          val preKeyMessageJson = e2eeManager.handleHandshake(normalizedPeerId, handshakeData, ephemeralKeyB64)
                           if (preKeyMessageJson != null) {
                               Log.i(TAG, "[CrisixApp] ✅ E2EE-Session mit ${normalizedPeerId.take(8)} aufgebaut")
                               e2eeSessions[normalizedPeerId] = true
                               e2eeManager.completeHandshakeRetry(normalizedPeerId)
                              
                              // Peer-initiated-Hint aktualisieren (falls vorhanden)
                              val peerHintMsgId = "sys-reset-${normalizedPeerId}-peer"
                              val peerSuccessText = context.getString(R.string.e2ee_reset_success)
                              messageRepository.updateHintMessage(peerHintMsgId, peerSuccessText, HintStatus.SUCCESS.name)
                              withContext(Dispatchers.Main) {
                                  allMessages[normalizedPeerId] = allMessages[normalizedPeerId]?.map {
                                      if (it.id == peerHintMsgId) it.copy(text = peerSuccessText, hintStatus = HintStatus.SUCCESS)
                                      else it
                                  } ?: emptyList()
                                  if (currentChatPeerId == normalizedPeerId) {
                                      currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                                  }
                              }

                              // ═══════════════════════════════════════════════════════════════
                              // WICHTIG: Die echte PreKeyMessage zurücksenden!
                              // Der Initiator braucht die PreKeyMessage, um
                              // completeHandshakeAsInitiator() aufzurufen.
                              // Ein leeres "session_established" reicht NICHT!
                              // ═══════════════════════════════════════════════════════════════
                              val ackPayload = JSONObject().apply {
                                  put("type", "crisix_e2ee_ack")
                                  put("data", preKeyMessageJson)
                              }.toString().toByteArray()
                              transportManager.sendMessage(normalizedPeerId, ackPayload)
                          } else {
                              Log.w(TAG, "[CrisixApp] ❌ E2EE-Handshake fehlgeschlagen für ${normalizedPeerId.take(8)}")
                              // Peer-initiated-Hint auf FAILURE setzen (falls vorhanden)
                              val peerHintMsgId = "sys-reset-${normalizedPeerId}-peer"
                              val peerFailText = context.getString(R.string.e2ee_reset_failed)
                              messageRepository.updateHintMessage(peerHintMsgId, peerFailText, HintStatus.FAILURE.name)
                              withContext(Dispatchers.Main) {
                                  allMessages[normalizedPeerId] = allMessages[normalizedPeerId]?.map {
                                      if (it.id == peerHintMsgId) it.copy(text = peerFailText, hintStatus = HintStatus.FAILURE)
                                      else it
                                  } ?: emptyList()
                                  if (currentChatPeerId == normalizedPeerId) {
                                      currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                                  }
                              }
                          }
                      }
                 } catch (e: Exception) {
                     Log.e(TAG, "[CrisixApp] Fehler beim E2EE-Handshake: ${e.message}", e)
                 }
                 return@registerMessageListener
             }

             // ═══════════════════════════════════════════════════════════════
             // E2EE-Nachricht: Verschlüsselte Double-Ratchet-Nachricht entschlüsseln
             // ═══════════════════════════════════════════════════════════════
             if (messageType == "crisix_e2ee") {
                 try {
                     val json = JSONObject(messageTextFinal)
                     val encryptedData = json.getString("data")
                     Log.i(TAG, "[CrisixApp] E2EE-Nachricht empfangen von ${normalizedPeerId.take(8)}, entschlüssele...")

                     val plaintext = e2eeManager.decryptMessage(normalizedPeerId, encryptedData)
                     if (plaintext != null) {
                         val decryptedText = String(plaintext)
                         Log.i(TAG, "[CrisixApp] ✅ E2EE-Nachricht entschlüsselt: ${decryptedText.take(50)}")

                          // Entschlüsselte Nachricht verarbeiten (Text, Bild, Voice)
                          try {
                              val decryptedJson = JSONObject(decryptedText)
                              val msgType = decryptedJson.optString("type", "message")
                              
                              if (decryptedJson.has("sender")) {
                                  senderName = decryptedJson.getString("sender")
                              }

                              if (senderName != null) {
                                  incomingNames[normalizedPeerId] = senderName
                              }

                              val msgId = "incoming-e2ee-$now"
                              
                              when (msgType) {
                                  "image" -> {
                                      // Bild-Nachricht: Dekodiere Base64 und speichere lokal
                                      val imageBase64 = decryptedJson.getString("data")
                                      val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                                      val imagesDir = java.io.File(context.filesDir, "images")
                                      imagesDir.mkdirs()
                                      val localFile = java.io.File(imagesDir, "$msgId.jpg")
                                      localFile.writeBytes(imageBytes)
                                      val localUri = androidx.core.content.FileProvider.getUriForFile(
                                          context, "${context.packageName}.fileprovider", localFile
                                      )
                                      
                                      val newMessage = Message(
                                          id = msgId,
                                          text = "",
                                          isFromMe = false,
                                          timestamp = timeStamp,
                                          timestampMillis = now,
                                          status = MessageStatus.DELIVERED,
                                          isEncrypted = true,
                                          imageUri = localUri.toString(),
                                      )

                                      scope.launch {
                                          messageRepository.addMessage(
                                              id = msgId,
                                              chatId = normalizedPeerId,
                                              text = "",
                                              isFromMe = false,
                                              timestamp = timeStamp,
                                              timestampMillis = now,
                                              status = MessageStatus.DELIVERED,
                                              transport = null,
                                              isEncrypted = true,
                                          )
                                          messageRepository.updateImageUri(msgId, localUri.toString())
                                      }
                                      
                                      val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                                      val withDelivered = existingMessages.map { msg ->
                                          if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                                              scope.launch {
                                                  messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                                              }
                                              msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                                          } else msg
                                      }
                                      allMessages[normalizedPeerId] = withDelivered + newMessage
                                      Log.i(TAG, "[CrisixApp] ✅ Bild-Nachricht entschlüsselt und gespeichert")
                                  }
                                  
                                  "voice" -> {
                                      // Voice-Nachricht: Dekodiere Base64 und speichere lokal
                                      val audioBase64 = decryptedJson.getString("data")
                                      val durationMs = decryptedJson.optLong("durationMs", 0)
                                      val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                                      val audioDir = java.io.File(context.filesDir, "audio")
                                      audioDir.mkdirs()
                                      val localFile = java.io.File(audioDir, "$msgId.aac")
                                      localFile.writeBytes(audioBytes)
                                      val localUri = androidx.core.content.FileProvider.getUriForFile(
                                          context, "${context.packageName}.fileprovider", localFile
                                      )
                                      
                                      val newMessage = Message(
                                          id = msgId,
                                          text = "",
                                          isFromMe = false,
                                          timestamp = timeStamp,
                                          timestampMillis = now,
                                          status = MessageStatus.DELIVERED,
                                          isEncrypted = true,
                                          audioUri = localUri.toString(),
                                          audioDurationMs = durationMs,
                                      )

                                      scope.launch {
                                          messageRepository.addMessage(
                                              id = msgId,
                                              chatId = normalizedPeerId,
                                              text = "",
                                              isFromMe = false,
                                              timestamp = timeStamp,
                                              timestampMillis = now,
                                              status = MessageStatus.DELIVERED,
                                              transport = null,
                                              isEncrypted = true,
                                          )
                                          messageRepository.updateAudioUri(msgId, localUri.toString(), durationMs)
                                      }
                                      
                                      val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                                      val withDelivered = existingMessages.map { msg ->
                                          if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                                              scope.launch {
                                                  messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                                              }
                                              msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                                          } else msg
                                      }
                                      allMessages[normalizedPeerId] = withDelivered + newMessage
                                      Log.i(TAG, "[CrisixApp] ✅ Voice-Nachricht entschlüsselt und gespeichert")
                                  }
                                  
                                  else -> {
                                      // Text-Nachricht
                                      val displayText = decryptedJson.optString("text", decryptedText)
                                      
                                      val newMessage = Message(
                                          id = msgId,
                                          text = displayText,
                                          isFromMe = false,
                                          timestamp = timeStamp,
                                          timestampMillis = now,
                                          status = MessageStatus.DELIVERED,
                                          isEncrypted = true,
                                      )

                                      scope.launch {
                                          messageRepository.addMessage(
                                              id = msgId,
                                              chatId = normalizedPeerId,
                                              text = displayText,
                                              isFromMe = false,
                                              timestamp = timeStamp,
                                              timestampMillis = now,
                                              status = MessageStatus.DELIVERED,
                                              transport = null,
                                              isEncrypted = true,
                                          )
                                      }
                                      
                                      val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                                      val withDelivered = existingMessages.map { msg ->
                                          if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                                              scope.launch {
                                                  messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                                              }
                                              msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                                          } else msg
                                      }
                                      allMessages[normalizedPeerId] = withDelivered + newMessage
                                      Log.i(TAG, "[CrisixApp] ✅ Text-Nachricht entschlüsselt")
                                  }
                              }
                          } catch (e: Exception) {
                              Log.e(TAG, "[CrisixApp] Fehler beim Parsen der entschlüsselten Nachricht: ${e.message}", e)
                              // Fallback: Zeige rohen Text
                              val displayText = decryptedText
                              
                              val msgId = "incoming-e2ee-$now"
                              val newMessage = Message(
                                  id = msgId,
                                  text = displayText,
                                  isFromMe = false,
                                  timestamp = timeStamp,
                                  timestampMillis = now,
                                  status = MessageStatus.DELIVERED,
                                  isEncrypted = true,
                              )

                              scope.launch {
                                  messageRepository.addMessage(
                                      id = msgId,
                                      chatId = normalizedPeerId,
                                      text = displayText,
                                      isFromMe = false,
                                      timestamp = timeStamp,
                                      timestampMillis = now,
                                      status = MessageStatus.DELIVERED,
                                      transport = null,
                                      isEncrypted = true,
                                  )
                              }

                              val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                              allMessages[normalizedPeerId] = existingMessages + newMessage
                          }

                         val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                         val withDelivered = existingMessages.map { msg ->
                             if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                                 scope.launch {
                                     messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                                 }
                                 msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                             } else msg
                          }
                          
                          if (currentChatPeerId == normalizedPeerId) {
                              currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                          }
                       } else {
                          Log.w(TAG, "[CrisixApp] ❌ E2EE-Entschlüsselung fehlgeschlagen für ${normalizedPeerId.take(8)} → initiiere Neu-Handshake")
                          // Session schließen (Ratchet out-of-sync)
                          e2eeManager.closeSession(normalizedPeerId)
                          e2eeSessions.remove(normalizedPeerId)
                          pendingHandshakes.remove(normalizedPeerId)

                           // System-Hinweis im Chat anzeigen
                           val hintMsgId = "sys-reset-$normalizedPeerId"
                           val hintText = context.getString(R.string.e2ee_reset_hint)
                           val hintMessage = Message(
                               id = hintMsgId,
                               text = hintText,
                               isFromMe = false,
                               timestamp = timeStamp,
                               timestampMillis = now,
                               status = MessageStatus.DELIVERED,
                               isSystemMessage = true,
                               hintStatus = HintStatus.LOADING,
                           )
                           val existingMsgs = allMessages[normalizedPeerId] ?: emptyList()
                           allMessages[normalizedPeerId] = existingMsgs + hintMessage
                           if (currentChatPeerId == normalizedPeerId) {
                               currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                           }
                           scope.launch {
                               messageRepository.addMessage(
                                   id = hintMsgId,
                                   chatId = normalizedPeerId,
                                   text = hintText,
                                   isFromMe = false,
                                   timestamp = timeStamp,
                                   timestampMillis = now,
                                   status = MessageStatus.DELIVERED,
                                   transport = null,
                                   isSystemMessage = true,
                                    hintStatus = HintStatus.LOADING.name,
                                )
                            }

                           // Neuen Handshake initiieren
                          scope.launch(Dispatchers.IO) {
                              val handshakeData = e2eeManager.createHandshake()
                              if (handshakeData != null) {
                                  pendingHandshakes[normalizedPeerId] = handshakeData
                                  val handshakePayload = JSONObject().apply {
                                      put("type", "crisix_e2ee_handshake")
                                      put("data", handshakeData.preKeyBundleJson)
                                      put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                                  }.toString().toByteArray()
                                  transportManager.sendMessage(normalizedPeerId, handshakePayload)
                                      .onSuccess {
                                          Log.i(TAG, "[CrisixApp] ✅ Neu-Handshake initiiert für ${normalizedPeerId.take(8)}")
                                      }
                                      .onFailure { error ->
                                          Log.w(TAG, "[CrisixApp] ⚠️ Neu-Handshake-Fehler: ${error.message}")
                                          e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                                      }
                              }
                          }
                      }
                 } catch (e: Exception) {
                     Log.e(TAG, "[CrisixApp] Fehler bei E2EE-Entschlüsselung: ${e.message}", e)
                 }
                 return@registerMessageListener
             }

             // ═══════════════════════════════════════════════════════════════
             // E2EE-ACK: Bestätigung, dass der Responder die Session aufgebaut hat
             // ═══════════════════════════════════════════════════════════════
             if (messageType == "crisix_e2ee_ack") {
                  Log.i(TAG, "[CrisixApp] E2EE-ACK empfangen von ${normalizedPeerId.take(8)}")
                  
                  // ═══════════════════════════════════════════════════════════════
                  // STRICT ACK VALIDATION (Downgrade-Protection)
                  // ═══════════════════════════════════════════════════════════════
                  val ackDataStr = String(data)
                  val validationResult = ackValidator.validateAckMessage(ackDataStr)
                  
                  Log.d(TAG, "[CrisixApp] ${validationResult.getLogMessage()}")
                  
                  if (!validationResult.valid) {
                      // ❌ ACK ist ungültig → Reject und Retry
                      Log.e(TAG, "[CrisixApp] ❌ ACK-Validierung fehlgeschlagen: ${validationResult.error}")
                      Log.w(TAG, "[CrisixApp] → Starte Handshake-Retry für ${normalizedPeerId.take(8)}")
                      
                      // Entferne pending Handshake (wurde fehlgeschlagen)
                      pendingHandshakes.remove(normalizedPeerId)
                      
                      // Starte Retry-Mechanismus
                      e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                      
                      return@registerMessageListener
                  }
                  
                  // ✅ ACK ist valid → Fahre mit Handshake-Completion fort
                  val preKeyMessageJson = validationResult.preKeyMessageJson!!
                  
                  // Handshake als Initiator vervollständigen
                  val pendingData = pendingHandshakes.remove(normalizedPeerId)
                  if (pendingData != null) {
                      scope.launch(Dispatchers.IO) {
                          Log.d(TAG, "[CrisixApp] Vervollständige Initiator-Handshake für ${normalizedPeerId.take(8)}")
                          
                          val success = e2eeManager.completeHandshakeAsInitiator(
                              peerId = normalizedPeerId,
                              peerBundle = pendingData.peerBundle,
                              peerPreKeyMessageJson = preKeyMessageJson,
                              ownEphemeralPrivateKey = pendingData.ownEphemeralPrivateKey,
                              ownEphemeralPublicKey = pendingData.ownEphemeralPublicKey
                          )
                           if (success) {
                                Log.i(TAG, "[CrisixApp] ✅ E2EE-Session mit ${normalizedPeerId.take(8)} komplett aufgebaut")
                                e2eeSessions[normalizedPeerId] = true
                                e2eeManager.completeHandshakeRetry(normalizedPeerId)
                                val hintMsgId = "sys-reset-$normalizedPeerId"
                                val successText = context.getString(R.string.e2ee_reset_success)
                                messageRepository.updateHintMessage(hintMsgId, successText, HintStatus.SUCCESS.name)
                                withContext(Dispatchers.Main) {
                                    allMessages[normalizedPeerId] = allMessages[normalizedPeerId]?.map {
                                        if (it.id == hintMsgId) it.copy(text = successText, hintStatus = HintStatus.SUCCESS)
                                        else it
                                    } ?: emptyList()
                                    if (currentChatPeerId == normalizedPeerId) {
                                        currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                                    }
                                }
                           } else {
                               Log.w(TAG, "[CrisixApp] ❌ completeHandshakeAsInitiator fehlgeschlagen — starte Retry")
                               e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                               val hintMsgId = "sys-reset-$normalizedPeerId"
                               val failText = context.getString(R.string.e2ee_reset_failed)
                               messageRepository.updateHintMessage(hintMsgId, failText, HintStatus.FAILURE.name)
                               withContext(Dispatchers.Main) {
                                   allMessages[normalizedPeerId] = allMessages[normalizedPeerId]?.map {
                                       if (it.id == hintMsgId) it.copy(text = failText, hintStatus = HintStatus.FAILURE)
                                       else it
                                   } ?: emptyList()
                                   if (currentChatPeerId == normalizedPeerId) {
                                       currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                                   }
                               }
                           }
                      }
                   } else {
                       // Kein pending Handshake vorhanden
                       // Könnte sein, dass wir bereits als Responder eine Session haben
                       Log.w(TAG, "[CrisixApp] ⚠️ ACK empfangen aber kein pending Handshake für ${normalizedPeerId.take(8)}")
                       e2eeSessions[normalizedPeerId] = true
                       e2eeManager.completeHandshakeRetry(normalizedPeerId)
                   }
                  return@registerMessageListener
              }

              if (messageType == "image") {
                 try {
                     val json = JSONObject(messageText)
                     val imageB64 = json.getString("data")
                     val imageBytes = Base64.decode(imageB64, Base64.DEFAULT)
                     val mime = json.optString("mime", "image/jpeg")
                     val isEncrypted = e2eeSessions[normalizedPeerId] == true
                     
                     if (json.has("sender")) {
                         senderName = json.getString("sender")
                     }

                     val msgId = json.optString("messageId", "incoming-img-$now")
                     val ext = if (mime.contains("png")) "png" else "jpg"
                     val imagesDir = File(context.filesDir, "images")
                     imagesDir.mkdirs()
                     val localFile = File(imagesDir, "$msgId.$ext")
                     localFile.writeBytes(imageBytes)

                     val localUri = androidx.core.content.FileProvider.getUriForFile(
                         context, "${context.packageName}.fileprovider", localFile
                     )

                     if (senderName != null) {
                         incomingNames[normalizedPeerId] = senderName
                     }

                     val newMessage = Message(
                         id = msgId,
                         text = "",
                         isFromMe = false,
                         timestamp = timeStamp,
                         timestampMillis = now,
                         status = MessageStatus.DELIVERED,
                         imageUri = localUri.toString(),
                         isEncrypted = isEncrypted,
                     )

                     scope.launch {
                         messageRepository.addMessage(
                             id = msgId,
                             chatId = normalizedPeerId,
                             text = "",
                             isFromMe = false,
                             timestamp = timeStamp,
                             timestampMillis = now,
                             status = MessageStatus.DELIVERED,
                             transport = null,
                             imageUri = localUri.toString(),
                             isEncrypted = isEncrypted,
                         )
                     }

                    val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                    val withDelivered = existingMessages.map { msg ->
                        if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                            scope.launch {
                                messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                            }
                            msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                        } else msg
                    }
                    allMessages[normalizedPeerId] = withDelivered + newMessage

                    val echoMessages = allMessages["echo-self"] ?: emptyList()
                    allMessages["echo-self"] = echoMessages + newMessage

                    if (currentChatPeerId == normalizedPeerId || currentChatPeerId == "echo-self") {
                        currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                    }

                    // Notification + UnreadCount für Bild
                    handleIncomingNotification(
                        normalizedPeerId, senderName,
                        context.getString(R.string.crisix_app_notification_image)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Verarbeiten des Bildes: ${e.message}", e)
                }
             } else if (messageType == "voice") {
                 try {
                     val json = JSONObject(messageText)
                     val audioB64 = json.getString("data")
                     val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                     val durationMs = json.optLong("durationMs", 0L)
                     val isEncrypted = e2eeSessions[normalizedPeerId] == true
                     
                     if (json.has("sender")) {
                         senderName = json.getString("sender")
                     }

                     val msgId = json.optString("messageId", "incoming-voice-$now")
                     val audioDir = File(context.filesDir, "audio")
                     audioDir.mkdirs()
                     val localFile = File(audioDir, "$msgId.aac")
                     localFile.writeBytes(audioBytes)

                     val localUri = androidx.core.content.FileProvider.getUriForFile(
                         context, "${context.packageName}.fileprovider", localFile
                     )

                     if (senderName != null) {
                         incomingNames[normalizedPeerId] = senderName
                     }

                     val newMessage = Message(
                         id = msgId,
                         text = "",
                         isFromMe = false,
                         timestamp = timeStamp,
                         timestampMillis = now,
                         status = MessageStatus.DELIVERED,
                         audioUri = localUri.toString(),
                         audioDurationMs = durationMs,
                         isEncrypted = isEncrypted,
                     )

                     scope.launch {
                         messageRepository.addMessage(
                             id = msgId,
                             chatId = normalizedPeerId,
                             text = "",
                             isFromMe = false,
                             timestamp = timeStamp,
                             timestampMillis = now,
                             status = MessageStatus.DELIVERED,
                             transport = null,
                             audioUri = localUri.toString(),
                             audioDurationMs = durationMs,
                             isEncrypted = isEncrypted,
                         )
                     }

                    val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                    val withDelivered = existingMessages.map { msg ->
                        if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                            scope.launch {
                                messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                            }
                            msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                        } else msg
                    }
                    allMessages[normalizedPeerId] = withDelivered + newMessage

                    val echoMessages = allMessages["echo-self"] ?: emptyList()
                    allMessages["echo-self"] = echoMessages + newMessage

                    if (currentChatPeerId == normalizedPeerId || currentChatPeerId == "echo-self") {
                        currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                    }

                    // Notification + UnreadCount für Sprachnachricht
                    handleIncomingNotification(
                        normalizedPeerId, senderName,
                        context.getString(R.string.crisix_app_notification_voice)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Verarbeiten der Sprachnachricht: ${e.message}", e)
                }
            } else {
                val displayText = try {
                    val json = JSONObject(messageText)
                    if (json.has("sender")) {
                        senderName = json.getString("sender")
                    }
                    json.getString("text")
                } catch (e: Exception) {
                    messageText
                }

                 if (senderName != null) {
                     incomingNames[normalizedPeerId] = senderName
                 }

                 val isEncrypted = e2eeSessions[normalizedPeerId] == true
                 val msgId = "incoming-$now"
                 val newMessage = Message(
                     id = msgId,
                     text = displayText,
                     isFromMe = false,
                     timestamp = timeStamp,
                     timestampMillis = now,
                     status = MessageStatus.DELIVERED,
                     isEncrypted = isEncrypted,
                 )

                 scope.launch {
                     messageRepository.addMessage(
                         id = msgId,
                         chatId = normalizedPeerId,
                         text = displayText,
                         isFromMe = false,
                         timestamp = timeStamp,
                         timestampMillis = now,
                         status = MessageStatus.DELIVERED,
                         transport = null,
                         isEncrypted = isEncrypted,
                     )
                 }

                val existingMessages = allMessages[normalizedPeerId] ?: emptyList()
                val withDelivered = existingMessages.map { msg ->
                    if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                        scope.launch {
                            messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                        }
                        msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
                    } else msg
                }
                allMessages[normalizedPeerId] = withDelivered + newMessage

                val echoMessages = allMessages["echo-self"] ?: emptyList()
                allMessages["echo-self"] = echoMessages + newMessage

                if (currentChatPeerId == normalizedPeerId || currentChatPeerId == "echo-self") {
                    currentMessages = allMessages[currentChatPeerId] ?: emptyList()
                }

                // Notification + UnreadCount für Text-Nachricht
                handleIncomingNotification(
                    normalizedPeerId, senderName,
                    displayText
                )
            }
        }

        // Retry-Queue-Callbacks für DB-Persistierung
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
                            update.status == MessageStatus.DELIVERED -> MessageStatus.DELIVERED
                            update.status == MessageStatus.SENT && msg.status != MessageStatus.DELIVERED -> MessageStatus.SENT
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

    // Chat-Liste generieren – reagiert auch auf Nachrichten von unbekannten Peers
    val chats by remember(discoveredPeers, activeTransport, incomingNames, savedContacts) {
        derivedStateOf {
            val chatList = mutableListOf<ChatPreview>()
            val seenIds = mutableSetOf<String>()

            // Hilfsfunktion: Kontaktname aus savedContacts suchen, sonst Fallback
            fun resolveDisplayName(peerId: String, fallback: String): String {
                val contact = savedContacts.find { it.peerId == peerId }
                if (contact != null && contact.name.isNotBlank()) {
                    return contact.name
                }
                return fallback
            }

            for (peer in discoveredPeers) {
                val normId = peer.id.split("@").first()
                if (normId in seenIds) continue
                seenIds.add(normId)
                 val peerMessages = allMessages[normId] ?: emptyList()
                 val lastMsg = peerMessages.lastOrNull()
                 val displayName = resolveDisplayName(normId, peer.name)
                 chatList.add(
                     ChatPreview(
                         id = normId,
                         name = displayName,
                         lastMessage = getMessagePreview(lastMsg).ifBlank { context.getString(R.string.crisix_app_connected_via_wifi) },
                         timestamp = lastMsg?.timestamp ?: context.getString(R.string.crisix_app_now),
                         timestampMillis = lastMsg?.timestampMillis ?: 0L,
                         unreadCount = unreadCounts[normId] ?: 0,
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
                 val peerDisplayName = resolveDisplayName(normId, incomingNames[normId] ?: normId.take(8))
                 chatList.add(
                     ChatPreview(
                         id = normId,
                         name = peerDisplayName,
                         lastMessage = getMessagePreview(lastMsg),
                         timestamp = lastMsg.timestamp,
                         timestampMillis = lastMsg.timestampMillis,
                         unreadCount = unreadCounts[normId] ?: 0,
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
                     name = context.getString(R.string.crisix_app_echo_chat_name),
                     lastMessage = getMessagePreview(echoLastMsg).ifBlank { context.getString(R.string.crisix_app_echo_chat_preview) },
                     timestamp = echoLastMsg?.timestamp ?: context.getString(R.string.crisix_app_now),
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
                    val newSettings = transportSettings + (type to enabled)
                    transportSettings = newSettings
                    saveTransportSettings(setupPrefs, newSettings)
                    scope.launch {
                        val enabledSet = newSettings.filter { it.value }.keys
                        transportManager.setEnabledTransports(enabledSet)
                    }
                },
                onComplete = {
                    navController.navigate(NavRoutes.PERMISSION_SETUP) {
                        popUpTo(NavRoutes.TRANSPORT_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.PERMISSION_SETUP) {
            PermissionSetupScreen(
                onComplete = {
                    setupPrefs.edit().putBoolean("setup_complete", true).apply()
                    isSetupComplete = true
                    navController.navigate(NavRoutes.CHAT_LIST) {
                        popUpTo(NavRoutes.PERMISSION_SETUP) { inclusive = true }
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

                    // Unread-Reset beim Öffnen eines Chats
                    if (!isEchoChat) {
                        scope.launch {
                            messageRepository.resetUnreadCount(normChatId)
                            unreadCounts[normChatId] = 0
                        }
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

             // ═══════════════════════════════════════════════════════════════
             // E2EE AUTO-HANDSHAKE beim Öffnen eines Chats
             // ═══════════════════════════════════════════════════════════════
             LaunchedEffect(chatId) {
                 val normChatId = chatId.split("@").first()
                 val hasSession = e2eeSessions[normChatId] == true
                 
                 if (!hasSession && normChatId != "echo-self") {
                     Log.i(TAG, "[CrisixApp] 🔐 Chat geöffnet: ${normChatId.take(8)} → initiiere E2EE-Handshake")
                     
                     delay(500) // Kurze Verzögerung, um UI zu aktualisieren
                     
                     val handshakeData = e2eeManager.createHandshake()
                     if (handshakeData != null) {
                         // Handshake-Daten speichern
                         pendingHandshakes[normChatId] = handshakeData
                         
                         // Sende Handshake an Peer
                         val handshakePayload = JSONObject().apply {
                             put("type", "crisix_e2ee_handshake")
                             put("data", handshakeData.preKeyBundleJson)
                             put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                         }.toString().toByteArray()
                         
                          transportManager.sendMessage(normChatId, handshakePayload)
                              .onSuccess {
                                  Log.i(TAG, "[CrisixApp] ✅ E2EE-Handshake initiiert beim Chat-Öffnen für ${normChatId.take(8)}")
                              }
                              .onFailure { error ->
                                  Log.w(TAG, "[CrisixApp] ⚠️ Handshake-Fehler beim Chat-Öffnen: ${error.message} → starte Retry")
                                  // Starte Retry-Mechanismus
                                  e2eeManager.startHandshakeRetry(normChatId, scope)
                              }
                     } else {
                         Log.e(TAG, "[CrisixApp] ❌ Handshake-Erstellung fehlgeschlagen")
                     }
                 }
             }

              ChatDetailScreen(
                  chatId = chatId,
                  chatName = chatName,
                  transportType = activeTransport?.type,
                  capabilities = capabilities,
                  messages = currentMessages,
                  incomingTransports = incomingTransports,
                  onBackClick = { navController.popBackStack() },
                onSendImage = { uri ->
                    val now = System.currentTimeMillis()
                    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                    val msgId = "img${now}"
                    val normChatId = chatId.split("@").first()
                    val hasSession = e2eeSessions[normChatId] == true
                    
                    val newMessage = Message(
                        id = msgId,
                        text = "",
                        isFromMe = true,
                        timestamp = timeStamp,
                        timestampMillis = now,
                        status = MessageStatus.SENDING,
                        imageUri = uri.toString(),
                        isEncrypted = hasSession,
                    )
                    currentMessages = currentMessages + newMessage
                    val existingMessages = allMessages[normChatId] ?: emptyList()
                    allMessages[normChatId] = existingMessages + newMessage
                    scope.launch {
                        messageRepository.addMessage(
                            id = msgId, chatId = normChatId, text = "",
                            isFromMe = true, timestamp = timeStamp,
                            timestampMillis = now, status = MessageStatus.SENDING,
                            transport = null,
                            isEncrypted = hasSession,
                        )
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            // DNS-Tunnel unterstützt keine Bilder
                            if (activeTransport?.type == TransportType.DNS_TUNNEL) {
                                Log.w(TAG, "[CrisixApp] DNS-Tunnel unterstützt keine Bilder")
                                return@launch
                            }
                            // Max image size abhängig vom aktiven Transport
                            val maxImageBytes = when (activeTransport?.type) {
                                TransportType.BLUETOOTH_MESH -> 50 * 1024      // 50KB für BLE (viele Chunks)
                                else -> 500 * 1024                              // 500KB für Internet/Relay/WiFi
                            }
                            val imageBytes = com.messenger.crisix.util.ImageCompressor.compress(
                                context, uri, maxSizeBytes = maxImageBytes
                            )

                            val imagesDir = java.io.File(context.filesDir, "images")
                            imagesDir.mkdirs()
                            val localFile = java.io.File(imagesDir, "$msgId.jpg")
                            localFile.writeBytes(imageBytes)
                            val localUri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", localFile
                            )
                            val localUriStr = localUri.toString()

                            allMessages[normChatId] = allMessages[normChatId]?.map {
                                if (it.id == msgId) it.copy(imageUri = localUriStr) else it
                            } ?: emptyList()
                            messageRepository.updateImageUri(msgId, localUriStr)

                            val b64 = Base64.encodeToString(imageBytes, Base64.DEFAULT)
                             val plainMessage = JSONObject().apply {
                                 put("type", "image")
                                 put("data", b64)
                                 put("mime", "image/jpeg")
                                 put("timestamp", timeStamp)
                                 put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                             }.toString().toByteArray()
                             
                             val messagePayload = if (hasSession) {
                                 // Nachricht mit E2EE verschlüsseln
                                 val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)
                                 if (encrypted != null) {
                                     Log.i(TAG, "[CrisixApp] ✅ Bild verschlüsselt für ${normChatId.take(8)}")
                                     JSONObject().apply {
                                         put("type", "crisix_e2ee")
                                         put("data", encrypted)
                                     }.toString().toByteArray()
                                 } else {
                                     Log.e(TAG, "[CrisixApp] ❌ Bild-Verschlüsselung fehlgeschlagen")
                                     return@launch
                                 }
                             } else {
                                 // Unverschlüsseltes Bild (vor Handshake)
                                 Log.i(TAG, "[CrisixApp] ⏳ Sende Bild unverschlüsselt (warte auf Handshake-Completion)...")
                                 JSONObject().apply {
                                     put("type", "image")
                                     put("data", b64)
                                     put("mime", "image/jpeg")
                                     put("timestamp", timeStamp)
                                     put("messageId", msgId)
                                     put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                                 }.toString().toByteArray()
                             }
                             
                             val isRealPeer = discoveredPeers.any { it.id.split("@").first() == normChatId }
                                 || normChatId != "echo-self" && allMessages.containsKey(normChatId)
                             if (isRealPeer) {
                                 transportManager.sendMessage(normChatId, messagePayload, uiMessageId = msgId)
                                     .onSuccess { Log.i(TAG, "[CrisixApp] ✅ Bild gesendet: $msgId") }
                                     .onFailure { e -> Log.i(TAG, "[CrisixApp] ❌ Bild-Fehler: ${e.message}") }
                             }
                        } catch (e: Exception) {
                            Log.e(TAG, "[CrisixApp] Fehler beim Bild-Senden: ${e.message}", e)
                        }
                    }
                },
                onSendVoice = { audioBytes, durationMs ->
                    val now = System.currentTimeMillis()
                    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                    val msgId = "voice${now}"
                    val normChatId = chatId.split("@").first()
                    val hasSession = e2eeSessions[normChatId] == true
                    
                    val newMessage = Message(
                        id = msgId,
                        text = "",
                        isFromMe = true,
                        timestamp = timeStamp,
                        timestampMillis = now,
                        status = MessageStatus.SENDING,
                        isEncrypted = hasSession,
                    )
                    currentMessages = currentMessages + newMessage
                    val existingMessages = allMessages[normChatId] ?: emptyList()
                    allMessages[normChatId] = existingMessages + newMessage
                    scope.launch {
                        messageRepository.addMessage(
                            id = msgId, chatId = normChatId, text = "",
                            isFromMe = true, timestamp = timeStamp,
                            timestampMillis = now, status = MessageStatus.SENDING,
                            transport = null,
                            isEncrypted = hasSession,
                        )
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val audioDir = java.io.File(context.filesDir, "audio")
                            audioDir.mkdirs()
                            val localFile = java.io.File(audioDir, "$msgId.aac")
                            localFile.writeBytes(audioBytes)
                            val localUri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", localFile
                            )
                            val localUriStr = localUri.toString()

                            allMessages[normChatId] = allMessages[normChatId]?.map {
                                if (it.id == msgId) it.copy(audioUri = localUriStr, audioDurationMs = durationMs) else it
                            } ?: emptyList()
                            messageRepository.updateAudioUri(msgId, localUriStr, durationMs)

                             val b64 = Base64.encodeToString(audioBytes, Base64.DEFAULT)
                             val plainMessage = JSONObject().apply {
                                 put("type", "voice")
                                 put("data", b64)
                                 put("mime", "audio/aac")
                                 put("durationMs", durationMs)
                                 put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                             }.toString().toByteArray()
                             
                             val messagePayload = if (hasSession) {
                                 // Nachricht mit E2EE verschlüsseln
                                 val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)
                                 if (encrypted != null) {
                                     Log.i(TAG, "[CrisixApp] ✅ Voice-Nachricht verschlüsselt für ${normChatId.take(8)}")
                                     JSONObject().apply {
                                         put("type", "crisix_e2ee")
                                         put("data", encrypted)
                                     }.toString().toByteArray()
                                 } else {
                                     Log.e(TAG, "[CrisixApp] ❌ Voice-Verschlüsselung fehlgeschlagen")
                                     return@launch
                                 }
                             } else {
                                 // Unverschlüsselte Voice-Nachricht (vor Handshake)
                                 Log.i(TAG, "[CrisixApp] ⏳ Sende Voice unverschlüsselt (warte auf Handshake-Completion)...")
                                 JSONObject().apply {
                                     put("type", "voice")
                                     put("data", b64)
                                     put("mime", "audio/aac")
                                     put("durationMs", durationMs)
                                     put("messageId", msgId)
                                     put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                                 }.toString().toByteArray()
                             }
                             
                             val isRealPeer = discoveredPeers.any { it.id.split("@").first() == normChatId }
                                 || normChatId != "echo-self" && allMessages.containsKey(normChatId)
                             if (isRealPeer) {
                                 transportManager.sendMessage(normChatId, messagePayload, uiMessageId = msgId)
                                     .onSuccess { Log.i(TAG, "[CrisixApp] ✅ Voice gesendet: $msgId") }
                                     .onFailure { e -> Log.i(TAG, "[CrisixApp] ❌ Voice-Fehler: ${e.message}") }
                             }
                        } catch (e: Exception) {
                            Log.e(TAG, "[CrisixApp] Fehler beim Voice-Senden: ${e.message}", e)
                        }
                    }
                },
                onSendMessage = { text ->
                    val now = System.currentTimeMillis()
                    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                    val msgId = "m${now}"
                    val normChatId = chatId.split("@").first()
                    
                    // ═══════════════════════════════════════════════════════════════
                    // E2EE-STATUS PRÜFEN
                    // ═══════════════════════════════════════════════════════════════
                    val hasSession = e2eeSessions[normChatId] == true
                    
                    val newMessage = Message(
                        id = msgId,
                        text = text,
                        isFromMe = true,
                        timestamp = timeStamp,
                        timestampMillis = now,
                        status = MessageStatus.SENDING,
                        isEncrypted = hasSession,
                    )

                    currentMessages = currentMessages + newMessage

                    val existingMessages = allMessages[normChatId] ?: emptyList()
                    allMessages[normChatId] = existingMessages + newMessage

                    // In Room-DB speichern
                    scope.launch {
                        messageRepository.addMessage(
                            id = msgId,
                            chatId = normChatId,
                            text = text,
                            isFromMe = true,
                            timestamp = timeStamp,
                            timestampMillis = now,
                            status = MessageStatus.SENDING,
                            transport = null,
                            isEncrypted = hasSession,
                        )
                    }

                    val isRealPeer = discoveredPeers.any { it.id.split("@").first() == normChatId }
                        || normChatId != "echo-self" && allMessages.containsKey(normChatId)
                    val isEchoChat = chatId == "echo-self"
                    if (isRealPeer || isEchoChat) {
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
                                // ═══════════════════════════════════════════════════════════════
                                // E2EE HANDSHAKE: Wenn KEINE Session existiert → Handshake starten
                                // ═══════════════════════════════════════════════════════════════
                                if (!hasSession) {
                                    Log.i(TAG, "[CrisixApp] ⏳ Keine E2EE-Session mit ${normChatId.take(8)} → initiiere Handshake")
                                    
                                    val handshakeData = e2eeManager.createHandshake()
                                    if (handshakeData != null) {
                                        // Handshake-Daten speichern für später (wenn ACK kommt)
                                        pendingHandshakes[normChatId] = handshakeData
                                        
                                        // Sende Handshake an Peer
                                        val handshakePayload = JSONObject().apply {
                                            put("type", "crisix_e2ee_handshake")
                                            put("data", handshakeData.preKeyBundleJson)
                                            put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                                        }.toString().toByteArray()
                                        
                                        transportManager.sendMessage(normChatId, handshakePayload)
                                            .onSuccess {
                                                Log.i(TAG, "[CrisixApp] ✅ E2EE-Handshake initiiert für ${normChatId.take(8)}")
                                            }
                                            .onFailure { error ->
                                                Log.w(TAG, "[CrisixApp] ❌ Handshake-Fehler: ${error.message}")
                                            }
                                    } else {
                                        Log.e(TAG, "[CrisixApp] ❌ Handshake-Erstellung fehlgeschlagen")
                                    }
                                }
                                
                                // ═══════════════════════════════════════════════════════════════
                                // MESSAGE ENCRYPTION: Nachricht verschlüsseln (falls Session existiert)
                                // ═══════════════════════════════════════════════════════════════
                                val messagePayload = if (hasSession) {
                                    // Nachricht mit E2EE verschlüsseln
                                    val plainMessage = JSONObject().apply {
                                        put("type", "message")
                                        put("text", text)
                                        put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                                        put("timestamp", timeStamp)
                                    }.toString().toByteArray()
                                    
                                    val encrypted = e2eeManager.encryptMessage(normChatId, plainMessage)
                                    if (encrypted != null) {
                                        Log.i(TAG, "[CrisixApp] ✅ Nachricht verschlüsselt für ${normChatId.take(8)}")
                                        JSONObject().apply {
                                            put("type", "crisix_e2ee")
                                            put("data", encrypted)
                                        }.toString().toByteArray()
                                    } else {
                                        Log.e(TAG, "[CrisixApp] ❌ Verschlüsselung fehlgeschlagen")
                                        return@launch
                                    }
                                } else {
                                    // Unverschlüsselte Nachricht (vor Handshake)
                                    Log.i(TAG, "[CrisixApp] ⏳ Sende Nachricht unverschlüsselt (warte auf Handshake-Completion)...")
                                    JSONObject().apply {
                                        put("type", "message")
                                        put("text", text)
                                        put("timestamp", timeStamp)
                                        put("messageId", newMessage.id)
                                        put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                                    }.toString().toByteArray()
                                }
                                
                                // SENDE NACHRICHT
                                transportManager.sendMessage(normChatId, messagePayload, uiMessageId = newMessage.id)
                                    .onSuccess {
                                        Log.i(TAG, "[CrisixApp] ✅ Nachricht gesendet an ${normChatId.take(8)}")
                                    }
                                    .onFailure { error ->
                                        Log.w(TAG, "[CrisixApp] ❌ Fehler beim Senden: ${error.message}")
                                    }
                            }
                        }
                    }
                },
                isE2eeEnabled = e2eeSessions[chatId.split("@").first()] == true
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
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
// Chat-Preview Hilfsfunktion
// ============================================================

/**
 * Generiert Preview-Text für verschiedene Message-Typen
 * - Text: Original-Text
 * - Bild: "🖼️ Bild"
 * - Sprachnachricht: "🎤 Sprachnachricht"
 * - Verschlüsselt: Zeige Icon basierend auf Message-Typ
 */
private fun getMessagePreview(message: Message?): String {
    if (message == null) return ""
    
    // Prüfe ob Nachricht verschlüsselt ist
    if (message.isEncrypted) {
        // Bei verschlüsselten Nachrichten: Versuche den Type aus dem Text zu extrahieren
        return when {
            message.imageUri != null -> "🖼️ Bild"
            message.audioUri != null -> "🎤 Sprachnachricht"
            else -> message.text.ifBlank { "🔒 Verschlüsselt" }
        }
    }
    
    // Bei unverschlüsselten Nachrichten: Text analysieren
    return when {
        message.imageUri != null -> "🖼️ Bild"
        message.audioUri != null -> "🎤 Sprachnachricht"
        message.text.isNotEmpty() -> message.text
        else -> "📨 Nachricht"
    }
}

// ============================================================
// Room-Entity ↔ UI-Message Konvertierung
// ============================================================

private fun com.messenger.crisix.data.MessageEntity.toMessage(): Message {
    return Message(
        id = id,
        text = text,
        isFromMe = isFromMe,
        timestamp = timestamp,
        timestampMillis = timestampMillis,
        status = try { com.messenger.crisix.transport.MessageStatus.valueOf(status) } catch (_: Exception) { com.messenger.crisix.transport.MessageStatus.SENT },
        transport = transport?.let { try { com.messenger.crisix.transport.TransportType.valueOf(it) } catch (_: Exception) { null } },
        imageUri = imageUri,
        audioUri = audioUri,
        audioDurationMs = audioDurationMs,
        isEncrypted = isEncrypted,
        isSystemMessage = isSystemMessage,
        hintStatus = hintStatus?.let { try { com.messenger.crisix.ui.screens.HintStatus.valueOf(it) } catch (_: Exception) { null } },
    )
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


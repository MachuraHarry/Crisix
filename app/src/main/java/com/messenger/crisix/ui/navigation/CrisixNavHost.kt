package com.messenger.crisix.ui.navigation

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.messenger.crisix.data.Contact
import com.messenger.crisix.data.ContactRepository
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.crypto.HandshakeInitData
import com.messenger.crisix.e2ee.E2EEHandshakeOrchestrator
import com.messenger.crisix.message.MessageSender
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.transport.ConnectionStatus
import com.messenger.crisix.transport.TransportCapabilities
import com.messenger.crisix.transport.Peer
import com.messenger.crisix.transport.internet.CryptoHelper
import com.messenger.crisix.transport.internet.InternetTransport
import com.messenger.crisix.ui.screens.AddContactScreen
import com.messenger.crisix.ui.screens.ChatDetailScreen
import com.messenger.crisix.ui.screens.ChatListScreen
import com.messenger.crisix.ui.screens.ContactDetailScreen
import com.messenger.crisix.ui.screens.ContactListScreen
import com.messenger.crisix.ui.screens.ConnectionsScreen
import com.messenger.crisix.ui.screens.LogViewerScreen
import com.messenger.crisix.ui.screens.Message
import com.messenger.crisix.ui.screens.MyIdScreen
import com.messenger.crisix.ui.screens.OnboardingScreen
import com.messenger.crisix.ui.screens.PermissionSetupScreen
import com.messenger.crisix.ui.screens.QrCodeScannerScreen
import com.messenger.crisix.ui.screens.SettingsScreen
import com.messenger.crisix.ui.screens.TransportSetupScreen
import com.messenger.crisix.ui.screens.UserProfile
import com.messenger.crisix.ui.viewmodel.ChatListViewModel
import com.messenger.crisix.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CrisixNavHost(
    navController: NavHostController,
    isSetupComplete: Boolean,
    onSetupComplete: () -> Unit,
    onUsernameSet: (String) -> Unit,
    allMessages: SnapshotStateMap<String, List<Message>>,
    onCurrentChatPeerIdChange: (String) -> Unit,
    onCurrentMessagesChange: (List<Message>) -> Unit,
    incomingNames: SnapshotStateMap<String, String>,
    incomingTransports: SnapshotStateMap<String, TransportType>,
    unreadCounts: SnapshotStateMap<String, Int>,
    e2eeSessions: SnapshotStateMap<String, Boolean>,
    pendingHandshakes: SnapshotStateMap<String, HandshakeInitData>,
    transportManager: TransportManager,
    discoveredPeers: List<Peer>,
    connectionStatuses: Map<TransportType, ConnectionStatus>,
    activeTransportType: TransportType?,
    capabilities: TransportCapabilities,
    transportSettings: Map<TransportType, Boolean>,
    onTransportToggle: (TransportType, Boolean) -> Unit,
    userProfile: UserProfile,
    onProfileUpdate: (UserProfile) -> Unit,
    onLanguageChanged: (com.messenger.crisix.LocaleHelper.AppLanguage) -> Unit,
    e2eeManager: E2eeManager,
    messageSender: MessageSender,
    handshakeOrchestrator: E2EEHandshakeOrchestrator,
    messageRepository: MessageRepository,
    savedContacts: List<Contact>,
    contactRepository: ContactRepository,
    onSavedContactsChange: (List<Contact>) -> Unit,
    pinnedChatIds: Set<String>,
    onPinChat: (String) -> Unit,
    localPeerId: String,
    localPort: Int,
    defaultDisplayName: String,
    onChatOpened: (String) -> Unit,
    scope: CoroutineScope,
    TAG: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val connectedViaWifiText = androidx.compose.ui.res.stringResource(com.messenger.crisix.R.string.crisix_app_connected_via_wifi)
    val nowText = androidx.compose.ui.res.stringResource(com.messenger.crisix.R.string.crisix_app_now)

    // Chat list via ViewModel
    val chatListViewModel = viewModel<ChatListViewModel>()
    val chats by androidx.compose.runtime.remember(discoveredPeers, activeTransportType, incomingNames, savedContacts, unreadCounts, pinnedChatIds) {
        androidx.compose.runtime.derivedStateOf {
            chatListViewModel.computeChats(
                discoveredPeers = discoveredPeers,
                allMessages = allMessages,
                incomingNames = incomingNames,
                savedContacts = savedContacts,
                unreadCounts = unreadCounts,
                activeTransportType = activeTransportType,
                nowText = nowText,
                defaultMessageText = connectedViaWifiText,
                pinnedChatIds = pinnedChatIds,
            )
        }
    }

    // Auto-update
    val updateState by UpdateManager.state.collectAsState()
    var showUpdateDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = if (isSetupComplete) NavRoutes.CHAT_LIST else NavRoutes.ONBOARDING,
        modifier = modifier
    ) {
        composable(NavRoutes.ONBOARDING) {
            OnboardingScreen(
                onComplete = { username ->
                    onUsernameSet(username)
                    navController.navigate(NavRoutes.TRANSPORT_SETUP) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.TRANSPORT_SETUP) {
            TransportSetupScreen(
                transportSettings = transportSettings,
                onTransportToggle = onTransportToggle,
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
                    onSetupComplete()
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
                    onCurrentChatPeerIdChange(normChatId)
                    onChatOpened(normChatId)
                    onCurrentMessagesChange(allMessages[normChatId] ?: emptyList())
                    scope.launch {
                        messageRepository.resetUnreadCount(normChatId)
                        unreadCounts[normChatId] = 0
                    }
                    navController.navigate(NavRoutes.chatDetail(normChatId, chatName))
                },
                onSettingsClick = { navController.navigate(NavRoutes.SETTINGS) },
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
                connectionStatuses = connectionStatuses,
                onDeleteChat = { chatId ->
                    scope.launch {
                        messageRepository.deleteChat(chatId)
                        allMessages.remove(chatId)
                    }
                },
                onRefresh = {
                    scope.launch {
                        transportManager.selectBestTransport()
                    }
                },
                onPinChat = onPinChat
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

            LaunchedEffect(chatId) {
                val normChatId = chatId.split("@").first()
                delay(500)
                handshakeOrchestrator.initiateHandshake(normChatId, pendingHandshakes)
            }

            val messagesFlow = remember(chatId) {
                messageRepository.getPagedMessages(chatId)
            }

            ChatDetailScreen(
                chatId = chatId,
                chatName = chatName,
                transportType = activeTransportType,
                capabilities = capabilities,
                messagesFlow = messagesFlow,
                incomingTransports = incomingTransports,
                onBackClick = { navController.popBackStack() },
                onSendImage = { uri ->
                    val normChatId = chatId.split("@").first()
                    messageSender.setUserProfile(userProfile)
                    messageSender.sendImage(
                        uri = uri,
                        ctx = buildSendContext(normChatId, e2eeManager, discoveredPeers, allMessages, activeTransportType),
                        callbacks = buildMessageCallbacks(normChatId, allMessages, messageRepository),
                        pendingHandshakes = pendingHandshakes,
                    )
                },
                onSendVoice = { audioBytes, durationMs ->
                    val normChatId = chatId.split("@").first()
                    messageSender.setUserProfile(userProfile)
                    messageSender.sendVoice(
                        audioBytes = audioBytes,
                        durationMs = durationMs,
                        ctx = buildSendContext(normChatId, e2eeManager, discoveredPeers, allMessages, activeTransportType),
                        callbacks = buildMessageCallbacks(normChatId, allMessages, messageRepository),
                        pendingHandshakes = pendingHandshakes,
                    )
                },
                onSendMessage = { text, replyToId, replyToText, replyToSender ->
                    val normChatId = chatId.split("@").first()
                    messageSender.setUserProfile(userProfile)
                    messageSender.sendText(
                        text = text,
                        replyToId = replyToId,
                        replyToText = replyToText,
                        replyToSender = replyToSender,
                        ctx = buildSendContext(normChatId, e2eeManager, discoveredPeers, allMessages, activeTransportType),
                        callbacks = buildMessageCallbacks(normChatId, allMessages, messageRepository),
                        pendingHandshakes = pendingHandshakes,
                    )
                },
                isE2eeEnabled = e2eeSessions[chatId.split("@").first()] == true,
                onDeleteMessage = { messageId ->
                    scope.launch {
                        messageRepository.deleteMessage(messageId)
                        val normId = chatId.split("@").first()
                        allMessages[normId] = allMessages[normId]?.filter { it.id != messageId } ?: emptyList()
                    }
                }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                transportSettings = transportSettings,
                onTransportToggle = onTransportToggle,
                userProfile = userProfile,
                onProfileUpdate = onProfileUpdate,
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
            val handshakeQr: String? = remember {
                e2eeManager.createHandshakeQrContent(
                    name = userProfile.name.ifBlank { defaultDisplayName }
                )
            }
            MyIdScreen(
                displayName = userProfile.name.ifBlank { defaultDisplayName },
                onBackClick = { navController.popBackStack() },
                handshakeQrContent = handshakeQr
            )
        }

        composable(NavRoutes.ADD_CONTACT) {
            AddContactScreen(
                transportManager = transportManager,
                onBackClick = { navController.popBackStack() },
                onContactAdded = { peerId, name ->
                    Log.i(TAG, "[CrisixApp] Neuer Kontakt hinzugefügt: $name ($peerId)")
                    navController.popBackStack()
                },
                onOpenQrScanner = { navController.navigate(NavRoutes.QR_SCANNER) }
            )
        }

        composable(NavRoutes.QR_SCANNER) {
            QrCodeScannerScreen(
                onQrCodeScanned = { qrContent ->
                    Log.i(TAG, "[CrisixApp] QR-Code gescannt: $qrContent")

                    if (isHandshakeQr(qrContent)) {
                        val bundle = extractBundleFromQr(qrContent)
                        val name = extractNameFromQr(qrContent) ?: "Unknown"
                        val peerId = extractPeerIdFromQr(qrContent)
                            ?: bundle?.let { CryptoHelper.publicKeyToFingerprint(it.identityKey) }

                        if (bundle != null && peerId != null) {
                            Log.i(TAG, "[CrisixApp] E2EE Handshake-QR gescannt: $name ($peerId)")
                            val newContact = contactRepository.createContact(peerId = peerId, name = name)
                            onSavedContactsChange(contactRepository.addOrUpdateContact(newContact))
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val result = e2eeManager.processHandshakeAsResponder(peerId = peerId, peerBundle = bundle)
                                if (result != null) {
                                    Log.i(TAG, "[CrisixApp] E2EE Session per QR-Handshake aufgebaut mit $peerId")
                                    e2eeManager.completeHandshakeRetry(peerId)
                                    transportManager.sendMessage(peerId, result.toByteArray())
                                } else {
                                    Log.w(TAG, "[CrisixApp] QR-Handshake fehlgeschlagen für $peerId")
                                }
                            }
                        }
                        navController.popBackStack()
                        return@QrCodeScannerScreen
                    }

                    val peerId: String? = extractPeerIdFromQr(qrContent)
                    val name: String? = extractNameFromQr(qrContent)
                    val ip: String? = extractIpFromQr(qrContent)
                    val port: Int? = extractPortFromQr(qrContent)

                    if (peerId != null) {
                        val displayName = name ?: peerId.take(8)
                        Log.i(TAG, "[CrisixApp] QR-Kontakt: $displayName (Fingerprint: ${peerId.take(16)}..., IP: $ip, Port: $port)")

                        val newContact = contactRepository.createContact(peerId = peerId, name = displayName, ipAddress = ip, port = port)
                        val updatedList = contactRepository.addOrUpdateContact(newContact)
                        onSavedContactsChange(updatedList)

                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            if (ip != null) {
                                try {
                                    transportManager.connectToPeer(ip, displayName, port)
                                } catch (_: Exception) { }
                            }
                            val internetTransport = transportManager.getTransportByType(TransportType.INTERNET) as? InternetTransport
                            if (internetTransport != null) {
                                try {
                                    internetTransport.connectToPeerById(peerId, displayName)
                                } catch (_: Exception) { }
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
                    onCurrentChatPeerIdChange(normPeerId)
                    onCurrentMessagesChange(allMessages[normPeerId] ?: emptyList())
                    navController.navigate(NavRoutes.chatDetail(normPeerId, peerName))
                }
            )
        }

        composable(NavRoutes.CONTACT_LIST) {
            ContactListScreen(
                contacts = savedContacts,
                onBackClick = { navController.popBackStack() },
                onContactClick = { contact ->
                    navController.navigate(NavRoutes.contactDetail(contact.id))
                },
                onDeleteContact = { contactId ->
                    onSavedContactsChange(contactRepository.removeContact(contactId))
                },
                onStartChat = { peerId, name ->
                    val normPeerId = peerId.split("@").first()
                    onCurrentChatPeerIdChange(normPeerId)
                    onCurrentMessagesChange(allMessages[normPeerId] ?: emptyList())
                    navController.navigate(NavRoutes.chatDetail(normPeerId, name))
                },
                onAddContact = { peerId, name, ip, port ->
                    val newContact = contactRepository.createContact(peerId = peerId, name = name, ipAddress = ip, port = port)
                    onSavedContactsChange(contactRepository.addOrUpdateContact(newContact))
                    Log.i(TAG, "[CrisixApp] Kontakt manuell hinzugefügt: $name ($peerId)")
                },
                onNavigateToAddContact = { navController.navigate(NavRoutes.ADD_CONTACT) }
            )
        }

        composable(
            route = NavRoutes.CONTACT_DETAIL,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            val contact = savedContacts.find { it.id == contactId }
            if (contact != null) {
                ContactDetailScreen(
                    contact = contact,
                    onBackClick = { navController.popBackStack() },
                    onSave = { updatedContact ->
                        onSavedContactsChange(contactRepository.addOrUpdateContact(updatedContact))
                    },
                    onDelete = { id ->
                        onSavedContactsChange(contactRepository.removeContact(id))
                        navController.popBackStack()
                    },
                    onStartChat = { peerId, name ->
                        val normPeerId = peerId.split("@").first()
                        onCurrentChatPeerIdChange(normPeerId)
                        onCurrentMessagesChange(allMessages[normPeerId] ?: emptyList())
                        navController.navigate(NavRoutes.chatDetail(normPeerId, name))
                    }
                )
            }
        }
    }

    if (showUpdateDialog && updateState is UpdateManager.UpdateState.UpdateAvailable) {
        val available = updateState as UpdateManager.UpdateState.UpdateAvailable
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(com.messenger.crisix.R.string.update_dialog_title)
                )
            },
            text = {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(
                        com.messenger.crisix.R.string.update_dialog_description, available.versionName
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showUpdateDialog = false
                    UpdateManager.reset()
                    navController.navigate(NavRoutes.SETTINGS) { launchSingleTop = true }
                }) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(com.messenger.crisix.R.string.update_dialog_open_settings)
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUpdateDialog = false }) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(com.messenger.crisix.R.string.update_dialog_later)
                    )
                }
            }
        )
    }
}

private fun buildSendContext(
    normChatId: String,
    e2eeManager: E2eeManager,
    discoveredPeers: List<Peer>,
    allMessages: Map<String, List<Message>>,
    activeTransportType: TransportType?,
) = MessageSender.SendContext(
    normChatId = normChatId,
    hasSession = e2eeManager.hasSession(normChatId),
    discoveredPeerIds = discoveredPeers.map { it.id.split("@").first() },
    knownChatIds = allMessages.keys,
    activeTransportType = activeTransportType,
)

private fun buildMessageCallbacks(
    normChatId: String,
    allMessages: SnapshotStateMap<String, List<Message>>,
    messageRepository: MessageRepository,
) = MessageSender.MessageAddedCallback(
    onAddToMessages = { peerId, msg ->
        val existing = allMessages[peerId] ?: emptyList()
        allMessages[peerId] = existing + msg
    },
    onPersistToDb = { msg ->
        messageRepository.addMessage(
            id = msg.id, chatId = normChatId, text = msg.text,
            isFromMe = msg.isFromMe, timestamp = msg.timestamp,
            timestampMillis = msg.timestampMillis, status = msg.status,
            transport = null, isEncrypted = msg.isEncrypted,
            replyToId = msg.replyToId,
            replyToText = msg.replyToText,
            replyToSender = msg.replyToSender,
        )
    },
    onUpdateEncrypted = { msgId ->
        val existing = allMessages[normChatId] ?: emptyList()
        allMessages[normChatId] = existing.map {
            if (it.id == msgId) it.copy(isEncrypted = true) else it
        }
    },
)

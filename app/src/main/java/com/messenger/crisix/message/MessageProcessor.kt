package com.messenger.crisix.message

import android.content.Context
import android.util.Base64
import android.util.Log
import com.messenger.crisix.R
import com.messenger.crisix.crypto.AckValidator
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.HintStatus
import com.messenger.crisix.ui.components.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MessageProcessor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transportManager: TransportManager,
    private val e2eeManager: E2eeManager,
    private val ackValidator: AckValidator,
    private val messageRepository: MessageRepository,
    private val allMessages: MutableMap<String, List<Message>>,
    private val getCurrentMessages: () -> List<Message>,
    private val setCurrentMessages: (List<Message>) -> Unit,
    private val getCurrentChatPeerId: () -> String,
    private val incomingNames: MutableMap<String, String>,
    private val incomingTransports: MutableMap<String, TransportType>,
    private val e2eeSessions: MutableMap<String, Boolean>,
    private val pendingHandshakes: MutableMap<String, com.messenger.crisix.crypto.HandshakeInitData>,
    private val processedIncomingIds: ConcurrentHashMap<String, Boolean>,
    private val unreadCounts: MutableMap<String, Int>,
) {
    companion object {
        private const val TAG = "MessageProcessor"
    }

    var userProfileName: () -> String = { "Crisix-User" }
    var onNotificationNeeded: ((String, String?, String) -> Unit)? = null

    fun registerListener() {
        transportManager.registerMessageListener { peerId, data, incomingTransport ->
            val normalizedPeerId = peerId.split("@").first()
            incomingTransports[normalizedPeerId] = incomingTransport

            var messageData = data
            var ackMessageId: String? = null

            val messageText = String(data)
            if (messageText.contains('\u0000')) {
                try {
                    val parts = messageText.split('\u0000')
                    if (parts.size == 2) {
                        ackMessageId = parts[1]
                        messageData = parts[0].toByteArray()
                        Log.i(TAG, "ACK-MessageId extrahiert: $ackMessageId")
                    }
                } catch (e: Exception) { Log.w(TAG, "message parse failed: ${e.message}", e) }
            }

            if (ackMessageId != null) {
                scope.launch {
                    try {
                        val ackPayload = JSONObject().apply {
                            put("type", "crisix_ack")
                            put("messageId", ackMessageId)
                        }.toString().toByteArray()
                        transportManager.sendMessage(normalizedPeerId, ackPayload)
                        Log.i(TAG, "ACK versendet für $ackMessageId an $normalizedPeerId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Fehler beim Versenden von ACK: ${e.message}")
                    }
                }
            }

            if (ackMessageId != null) {
                val dedupKey = "$normalizedPeerId:$ackMessageId"
                if (processedIncomingIds.putIfAbsent(dedupKey, true) != null) {
                    Log.i(TAG, "Duplikat ignoriert: $dedupKey")
                    return@registerMessageListener
                }
                if (processedIncomingIds.size > 10_000) {
                    val keys = processedIncomingIds.keys().toList()
                    keys.take(keys.size / 2).forEach { processedIncomingIds.remove(it) }
                }
            }

            val messageTextFinal = String(messageData)
            val now = System.currentTimeMillis()
            val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

            var senderName: String? = null
            val messageType = try {
                JSONObject(messageTextFinal).optString("type", "text")
            } catch (_: Exception) { "text" }

            Log.i(TAG, "Nachricht empfangen: type=$messageType von ${normalizedPeerId.take(8)} (via $incomingTransport)")

            // --- crisix_timer ---
            if (messageType == "crisix_timer") {
                try {
                    val json = JSONObject(messageTextFinal)
                    val timerMs = json.optLong("disappearingTimerMs", 0L)
                    if (json.has("sender")) senderName = json.getString("sender")
                    if (senderName != null) incomingNames[normalizedPeerId] = senderName
                    if (timerMs == 0L) return@registerMessageListener
                    val timerLabel = timerMsToLabel(context, timerMs)
                    val hintText = context.getString(R.string.timer_set_hint, timerLabel)
                    val hintMsgId = "sys-timer-hint-$normalizedPeerId-$now"
                    val hintMessage = Message(
                        id = hintMsgId, text = hintText, isFromMe = false,
                        timestamp = timeStamp, timestampMillis = now,
                        status = MessageStatus.DELIVERED,
                        isSystemMessage = true, hintStatus = HintStatus.SUCCESS,
                        disappearingTimerMs = timerMs,
                    )
                    addToMessageList(normalizedPeerId, hintMessage)
                    scope.launch {
                        messageRepository.addMessage(
                            id = hintMsgId, chatId = normalizedPeerId, text = hintText,
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null,
                            isSystemMessage = true, hintStatus = HintStatus.SUCCESS.name,
                            disappearingTimerMs = timerMs,
                        )
                        messageRepository.updateChatDisappearingTimer(normalizedPeerId, timerMs)
                    }
                    Log.i(TAG, "Timer-Notification empfangen: ${timerMs}ms von ${normalizedPeerId.take(8)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Fehler beim Verarbeiten von crisix_timer: ${e.message}")
                }
                return@registerMessageListener
            }

            // --- crisix_ack ---
            if (messageType == "crisix_ack") {
                val ackMsgType = try { JSONObject(messageTextFinal).optString("type") } catch (_: Exception) { "crisix_ack" }
                if (ackMsgType == "crisix_ping") { return@registerMessageListener }
                if (ackMsgType == "crisix_ack") {
                    try {
                        val ackJson = JSONObject(messageTextFinal)
                        val ackedMsgId = ackJson.optString("messageId", null)
                        if (ackedMsgId != null) {
                            scope.launch {
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
                                    if (getCurrentChatPeerId() == normalizedPeerId) {
                                        setCurrentMessages(allMessages[getCurrentChatPeerId()] ?: emptyList())
                                    }
                                }
                            }
                            Log.i(TAG, "ACK verarbeitet: $ackedMsgId (allMessages)")
                            return@registerMessageListener
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Fehler beim Verarbeiten von ACK: ${e.message}")
                    }
                    return@registerMessageListener
                }
            }

            // --- E2EE Handshake ---
            if (messageType == "crisix_e2ee_handshake") {
                if (e2eeManager.hasSession(normalizedPeerId)) {
                    Log.i(TAG, "Session-Reset von ${normalizedPeerId.take(8)} empfangen")
                    e2eeManager.closeSession(normalizedPeerId)
                    e2eeSessions.remove(normalizedPeerId)
                    pendingHandshakes.remove(normalizedPeerId)
                    addSystemHint(normalizedPeerId, R.string.e2ee_reset_peer_hint, HintStatus.LOADING, timeStamp, now)
                }
                try {
                    val json = JSONObject(messageTextFinal)
                    val handshakeData = json.getString("data")
                    val ephemeralKeyB64 = json.optString("ephemeralKey", null)

                    scope.launch(Dispatchers.IO) {
                        val preKeyMessageJson = e2eeManager.handleHandshake(normalizedPeerId, handshakeData, ephemeralKeyB64)
                        if (preKeyMessageJson != null) {
                            Log.i(TAG, "E2EE-Session mit ${normalizedPeerId.take(8)} aufgebaut")
                            e2eeSessions[normalizedPeerId] = true
                            e2eeManager.setSessionActive(normalizedPeerId)
                            e2eeManager.completeHandshakeRetry(normalizedPeerId)

                            val peerHintMsgId = "sys-reset-${normalizedPeerId}-peer"
                            messageRepository.updateHintMessage(peerHintMsgId,
                                context.getString(R.string.e2ee_reset_success), HintStatus.SUCCESS.name)
                            withContext(Dispatchers.Main) {
                                updateMessageInList(normalizedPeerId, peerHintMsgId,
                                    context.getString(R.string.e2ee_reset_success), HintStatus.SUCCESS)
                            }

                            val ackPayload = JSONObject().apply {
                                put("type", "crisix_e2ee_ack")
                                put("data", preKeyMessageJson)
                            }.toString().toByteArray()
                            transportManager.sendMessage(normalizedPeerId, ackPayload)
                        } else {
                            Log.w(TAG, "E2EE-Handshake fehlgeschlagen für ${normalizedPeerId.take(8)}")
                            val peerHintMsgId = "sys-reset-${normalizedPeerId}-peer"
                            messageRepository.updateHintMessage(peerHintMsgId,
                                context.getString(R.string.e2ee_reset_failed), HintStatus.FAILURE.name)
                            withContext(Dispatchers.Main) {
                                updateMessageInList(normalizedPeerId, peerHintMsgId,
                                    context.getString(R.string.e2ee_reset_failed), HintStatus.FAILURE)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim E2EE-Handshake: ${e.message}", e)
                }
                return@registerMessageListener
            }

            // --- E2EE encrypted ---
            if (messageType == "crisix_e2ee") {
                try {
                    val json = JSONObject(messageTextFinal)
                    val encryptedData = json.getString("data")
                    Log.i(TAG, "E2EE-Nachricht empfangen von ${normalizedPeerId.take(8)}, entschlüssele...")

                    val plaintext = e2eeManager.decryptMessage(normalizedPeerId, encryptedData)
                    if (plaintext != null) {
                        Log.i(TAG, "E2EE-Nachricht entschlüsselt: ${plaintext.size} bytes")

                        val isBinaryPayload = plaintext.isNotEmpty() &&
                            (plaintext[0] == 0x01.toByte() || plaintext[0] == 0x02.toByte()) &&
                            plaintext.size > 3

                        if (isBinaryPayload) {
                            processBinaryEncrypted(plaintext, normalizedPeerId, now, timeStamp, incomingTransport, senderName)
                            return@registerMessageListener
                        }

                        val decryptedText = String(plaintext)
                        processDecryptedJson(decryptedText, normalizedPeerId, now, timeStamp, senderName)
                        return@registerMessageListener
                    } else {
                        Log.w(TAG, "E2EE-Entschlüsselung fehlgeschlagen für ${normalizedPeerId.take(8)} -> initiiere Neu-Handshake")
                        e2eeManager.closeSession(normalizedPeerId)
                        e2eeSessions.remove(normalizedPeerId)
                        pendingHandshakes.remove(normalizedPeerId)
                        addSystemHint(normalizedPeerId, R.string.e2ee_reset_hint, HintStatus.LOADING, timeStamp, now)

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
                                    .onSuccess { Log.i(TAG, "Neu-Handshake initiiert für ${normalizedPeerId.take(8)}") }
                                    .onFailure { error ->
                                        Log.w(TAG, "Neu-Handshake-Fehler: ${error.message}")
                                        e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                                    }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler bei E2EE-Entschlüsselung: ${e.message}", e)
                }
                return@registerMessageListener
            }

            // --- E2EE-ACK ---
            if (messageType == "crisix_e2ee_ack") {
                Log.i(TAG, "E2EE-ACK empfangen von ${normalizedPeerId.take(8)}")
                val ackDataStr = String(data)
                val validationResult = ackValidator.validateAckMessage(ackDataStr)
                Log.d(TAG, validationResult.getLogMessage())

                if (!validationResult.valid) {
                    Log.e(TAG, "ACK-Validierung fehlgeschlagen: ${validationResult.error}")
                    Log.w(TAG, "→ Starte Handshake-Retry für ${normalizedPeerId.take(8)}")
                    pendingHandshakes.remove(normalizedPeerId)
                    e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                    return@registerMessageListener
                }

                val preKeyMessageJson = validationResult.preKeyMessageJson ?: return@registerMessageListener
                val pendingData = pendingHandshakes.remove(normalizedPeerId)
                if (pendingData != null) {
                    scope.launch(Dispatchers.IO) {
                        val success = e2eeManager.completeHandshakeAsInitiator(
                            peerId = normalizedPeerId,
                            peerBundle = pendingData.peerBundle,
                            peerPreKeyMessageJson = preKeyMessageJson,
                            ownEphemeralPrivateKey = pendingData.ownEphemeralPrivateKey,
                            ownEphemeralPublicKey = pendingData.ownEphemeralPublicKey
                        )
                        if (success) {
                            Log.i(TAG, "E2EE-Session als Initiator vervollständigt mit ${normalizedPeerId.take(8)}")
                            e2eeSessions[normalizedPeerId] = true
                            e2eeManager.setSessionActive(normalizedPeerId)
                            e2eeManager.completeHandshakeRetry(normalizedPeerId)

                            val hintMsgId = "sys-handshake-${normalizedPeerId}"
                            messageRepository.updateHintMessage(hintMsgId,
                                context.getString(R.string.e2ee_reset_success), HintStatus.SUCCESS.name)
                            withContext(Dispatchers.Main) {
                                updateMessageInList(normalizedPeerId, hintMsgId,
                                    context.getString(R.string.e2ee_reset_success), HintStatus.SUCCESS)
                            }
                        } else {
                            Log.e(TAG, "Handshake-Completion fehlgeschlagen für ${normalizedPeerId.take(8)}")
                            val hintMsgId = "sys-handshake-${normalizedPeerId}"
                            messageRepository.updateHintMessage(hintMsgId,
                                context.getString(R.string.e2ee_reset_failed), HintStatus.FAILURE.name)
                            withContext(Dispatchers.Main) {
                                updateMessageInList(normalizedPeerId, hintMsgId,
                                    context.getString(R.string.e2ee_reset_failed), HintStatus.FAILURE)
                            }
                            e2eeManager.startHandshakeRetry(normalizedPeerId, scope)
                        }
                    }
                }
                return@registerMessageListener
            }

            // --- Image (unencrypted) ---
            if (messageType == "image") {
                try {
                    val json = JSONObject(messageTextFinal)
                    val imageB64 = json.getString("data")
                    val imageBytes = Base64.decode(imageB64, Base64.DEFAULT)
                    val imagesDir = File(context.filesDir, "images")
                    imagesDir.mkdirs()
                    val msgId = "incoming-$now"
                    val localFile = File(imagesDir, "$msgId.jpg")
                    localFile.writeBytes(imageBytes)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )
                    if (json.has("sender")) senderName = json.getString("sender")
                    if (senderName != null) incomingNames[normalizedPeerId] = senderName

                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        imageUri = localUri.toString(),
                    )
                    addToMessageList(normalizedPeerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = normalizedPeerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null)
                        messageRepository.updateImageUri(msgId, localUri.toString())
                    }
                    onNotificationNeeded?.invoke(normalizedPeerId, senderName, context.getString(R.string.crisix_app_notification_image))
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Verarbeiten von Bild: ${e.message}", e)
                }
                return@registerMessageListener
            }

            // --- Voice (unencrypted) ---
            if (messageType == "voice") {
                try {
                    val json = JSONObject(messageTextFinal)
                    val audioB64 = json.getString("data")
                    val durationMs = json.optLong("durationMs", 0)
                    val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                    val audioDir = File(context.filesDir, "audio")
                    audioDir.mkdirs()
                    val msgId = "incoming-$now"
                    val localFile = File(audioDir, "$msgId.aac")
                    localFile.writeBytes(audioBytes)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )
                    if (json.has("sender")) senderName = json.getString("sender")
                    if (senderName != null) incomingNames[normalizedPeerId] = senderName

                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        audioUri = localUri.toString(), audioDurationMs = durationMs,
                    )
                    addToMessageList(normalizedPeerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = normalizedPeerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null)
                        messageRepository.updateAudioUri(msgId, localUri.toString(), durationMs)
                    }
                    onNotificationNeeded?.invoke(normalizedPeerId, senderName, context.getString(R.string.crisix_app_notification_voice))
                } catch (e: Exception) {
                    Log.e(TAG, "Fehler beim Verarbeiten von Voice: ${e.message}", e)
                }
                return@registerMessageListener
            }

            // --- Text (unencrypted, default) ---
            try {
                val json = JSONObject(messageTextFinal)
                val displayText = json.optString("text", messageTextFinal)
                if (json.has("sender")) senderName = json.getString("sender")
                if (senderName != null) incomingNames[normalizedPeerId] = senderName
                val disappearingTimerMs = json.optLong("disappearingTimerMs", 0L)

                val msgId = "incoming-$now"
                val newMessage = Message(
                    id = msgId, text = displayText, isFromMe = false,
                    timestamp = timeStamp, timestampMillis = now,
                    status = MessageStatus.DELIVERED,
                    disappearingTimerMs = disappearingTimerMs,
                )
                addToMessageList(normalizedPeerId, newMessage)
                scope.launch {
                    messageRepository.addMessage(id = msgId, chatId = normalizedPeerId,
                        text = displayText, isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED, transport = null,
                        disappearingTimerMs = disappearingTimerMs)
                }
                onNotificationNeeded?.invoke(normalizedPeerId, senderName, displayText)
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Verarbeiten von Text: ${e.message}", e)
            }
        }
    }

    private fun addSystemHint(peerId: String, stringResId: Int, status: HintStatus, timeStamp: String, now: Long) {
        val hintMsgId = "sys-reset-${peerId}-peer"
        val hintText = context.getString(stringResId)
        val hintMessage = Message(
            id = hintMsgId, text = hintText, isFromMe = false,
            timestamp = timeStamp, timestampMillis = now,
            status = MessageStatus.DELIVERED, isSystemMessage = true, hintStatus = status,
        )
        addToMessageList(peerId, hintMessage)
        scope.launch {
            messageRepository.addMessage(id = hintMsgId, chatId = peerId, text = hintText,
                isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                status = MessageStatus.DELIVERED, transport = null,
                isSystemMessage = true, hintStatus = status.name)
        }
    }

    private fun updateMessageInList(peerId: String, msgId: String, text: String, status: HintStatus) {
        allMessages[peerId] = allMessages[peerId]?.map {
            if (it.id == msgId) it.copy(text = text, hintStatus = status) else it
        } ?: emptyList()
        if (getCurrentChatPeerId() == peerId) {
            setCurrentMessages(allMessages[getCurrentChatPeerId()] ?: emptyList())
        }
    }

    private fun addToMessageList(peerId: String, message: Message) {
        val existingMessages = allMessages[peerId] ?: emptyList()
        val withDelivered = existingMessages.map { msg ->
            if (msg.isFromMe && msg.status == MessageStatus.SENT) {
                scope.launch {
                    messageRepository.updateMessageStatus(msg.id, MessageStatus.DELIVERED, msg.transport?.name)
                }
                msg.copy(status = MessageStatus.DELIVERED, transport = msg.transport)
            } else msg
        }
        allMessages[peerId] = withDelivered + message
        if (getCurrentChatPeerId() == peerId) {
            setCurrentMessages(allMessages[getCurrentChatPeerId()] ?: emptyList())
        }
    }

    private fun processBinaryEncrypted(plaintext: ByteArray, peerId: String, now: Long, timeStamp: String,
                                       incomingTransport: TransportType, senderName: String?) {
        try {
            val payloadType = plaintext[0]
            val metaLen = ((plaintext[1].toInt() and 0xFF) shl 8) or (plaintext[2].toInt() and 0xFF)
            val metaJson = String(plaintext, 3, metaLen)
            val rawData = plaintext.copyOfRange(3 + metaLen, plaintext.size)

            val meta = JSONObject(metaJson)
            val type = meta.optString("type", "unknown")

            if (meta.has("sender")) {
                incomingNames[peerId] = meta.getString("sender")
            }

            val msgId = "incoming-e2ee-$now"
            val disappearingTimerMs = meta.optLong("disappearingTimerMs", 0L)
            when (type) {
                "image" -> {
                    val imagesDir = File(context.filesDir, "images")
                    imagesDir.mkdirs()
                    val localFile = File(imagesDir, "$msgId.jpg")
                    localFile.writeBytes(rawData)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )

                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        isEncrypted = true, imageUri = localUri.toString(),
                        disappearingTimerMs = disappearingTimerMs,
                    )
                    addToMessageList(peerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = peerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null, isEncrypted = true,
                            disappearingTimerMs = disappearingTimerMs)
                        messageRepository.updateImageUri(msgId, localUri.toString())
                    }
                    Log.i(TAG, "Bild-Nachricht binär entschlüsselt")
                    onNotificationNeeded?.invoke(peerId, senderName, context.getString(R.string.crisix_app_notification_image))
                }
                "voice" -> {
                    val durationMs = meta.optLong("durationMs", 0)
                    val audioDir = File(context.filesDir, "audio")
                    audioDir.mkdirs()
                    val localFile = File(audioDir, "$msgId.aac")
                    localFile.writeBytes(rawData)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )

                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        isEncrypted = true, audioUri = localUri.toString(), audioDurationMs = durationMs,
                        disappearingTimerMs = disappearingTimerMs,
                    )
                    addToMessageList(peerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = peerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null, isEncrypted = true,
                            disappearingTimerMs = disappearingTimerMs)
                        messageRepository.updateAudioUri(msgId, localUri.toString(), durationMs)
                    }
                    Log.i(TAG, "Voice-Nachricht binär entschlüsselt")
                    onNotificationNeeded?.invoke(peerId, senderName, context.getString(R.string.crisix_app_notification_voice))
                }
                else -> {
                    Log.w(TAG, "Unbekannter binärer Typ: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei binärer E2EE-Verarbeitung: ${e.message}", e)
        }
    }

    private fun processDecryptedJson(decryptedText: String, peerId: String, now: Long,
                                      timeStamp: String, senderName: String?) {
        try {
            val decryptedJson = JSONObject(decryptedText)
            val msgType = decryptedJson.optString("type", "message")
            var sender = senderName

            if (decryptedJson.has("sender")) {
                sender = decryptedJson.getString("sender")
                incomingNames[peerId] = sender
            }

            val msgId = "incoming-e2ee-$now"
            when (msgType) {
                "image" -> {
                    val imageB64 = decryptedJson.getString("data")
                    val imageBytes = Base64.decode(imageB64, Base64.DEFAULT)
                    val imagesDir = File(context.filesDir, "images")
                    imagesDir.mkdirs()
                    val localFile = File(imagesDir, "$msgId.jpg")
                    localFile.writeBytes(imageBytes)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )
                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        isEncrypted = true, imageUri = localUri.toString(),
                    )
                    addToMessageList(peerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = peerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null, isEncrypted = true)
                        messageRepository.updateImageUri(msgId, localUri.toString())
                    }
                    onNotificationNeeded?.invoke(peerId, sender, context.getString(R.string.crisix_app_notification_image))
                }
                "voice" -> {
                    val audioB64 = decryptedJson.getString("data")
                    val durationMs = decryptedJson.optLong("durationMs", 0)
                    val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                    val audioDir = File(context.filesDir, "audio")
                    audioDir.mkdirs()
                    val localFile = File(audioDir, "$msgId.aac")
                    localFile.writeBytes(audioBytes)
                    val localUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", localFile
                    )
                    val newMessage = Message(
                        id = msgId, text = "", isFromMe = false, timestamp = timeStamp,
                        timestampMillis = now, status = MessageStatus.DELIVERED,
                        isEncrypted = true, audioUri = localUri.toString(), audioDurationMs = durationMs,
                    )
                    addToMessageList(peerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = peerId, text = "",
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null, isEncrypted = true)
                        messageRepository.updateAudioUri(msgId, localUri.toString(), durationMs)
                    }
                    onNotificationNeeded?.invoke(peerId, sender, context.getString(R.string.crisix_app_notification_voice))
                }
                else -> {
                    val displayText = decryptedJson.optString("text", decryptedText)
                    val replyToId = decryptedJson.optString("replyToId", null)
                    val replyToText = decryptedJson.optString("replyToText", null)
                    val replyToSender = decryptedJson.optString("replyToSender", null)
                    val disappearingTimerMs = decryptedJson.optLong("disappearingTimerMs", 0L)
                    val newMessage = Message(
                        id = msgId, text = displayText, isFromMe = false,
                        timestamp = timeStamp, timestampMillis = now,
                        status = MessageStatus.DELIVERED, isEncrypted = true,
                        replyToId = if (replyToId.isNullOrEmpty()) null else replyToId,
                        replyToText = if (replyToText.isNullOrEmpty()) null else replyToText,
                        replyToSender = if (replyToSender.isNullOrEmpty()) null else replyToSender,
                        disappearingTimerMs = disappearingTimerMs,
                    )
                    addToMessageList(peerId, newMessage)
                    scope.launch {
                        messageRepository.addMessage(id = msgId, chatId = peerId, text = displayText,
                            isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                            status = MessageStatus.DELIVERED, transport = null, isEncrypted = true,
                            replyToId = if (replyToId.isNullOrEmpty()) null else replyToId,
                            replyToText = if (replyToText.isNullOrEmpty()) null else replyToText,
                            replyToSender = if (replyToSender.isNullOrEmpty()) null else replyToSender,
                            disappearingTimerMs = disappearingTimerMs)
                    }
                    onNotificationNeeded?.invoke(peerId, sender, displayText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Parsen der entschlüsselten Nachricht: ${e.message}", e)
            val msgId = "incoming-e2ee-$now"
            val newMessage = Message(
                id = msgId, text = decryptedText, isFromMe = false,
                timestamp = timeStamp, timestampMillis = now,
                status = MessageStatus.DELIVERED, isEncrypted = true,
            )
            addToMessageList(peerId, newMessage)
            scope.launch {
                messageRepository.addMessage(id = msgId, chatId = peerId, text = decryptedText,
                    isFromMe = false, timestamp = timeStamp, timestampMillis = now,
                    status = MessageStatus.DELIVERED, transport = null, isEncrypted = true)
            }
            onNotificationNeeded?.invoke(peerId, senderName, decryptedText.take(100))
        }
    }
}

private fun timerMsToLabel(context: Context, ms: Long): String = when (ms) {
    0L -> context.getString(com.messenger.crisix.R.string.timer_off)
    30_000L -> context.getString(com.messenger.crisix.R.string.timer_30s)
    300_000L -> context.getString(com.messenger.crisix.R.string.timer_5m)
    3_600_000L -> context.getString(com.messenger.crisix.R.string.timer_1h)
    86_400_000L -> context.getString(com.messenger.crisix.R.string.timer_24h)
    604_800_000L -> context.getString(com.messenger.crisix.R.string.timer_7d)
    else -> "${ms / 1000}s"
}

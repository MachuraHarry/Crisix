package com.messenger.crisix.message

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.messenger.crisix.R
import com.messenger.crisix.crypto.E2eeManager
import com.messenger.crisix.data.MessageRepository
import com.messenger.crisix.transport.MessageStatus
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.transport.TransportType
import com.messenger.crisix.ui.components.Message
import com.messenger.crisix.ui.screens.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transportManager: TransportManager,
    private val e2eeManager: E2eeManager,
    private val messageRepository: MessageRepository,
) {
    companion object {
        private const val TAG = "MessageSender"
    }

    private var userProfile: UserProfile = UserProfile()

    fun setUserProfile(profile: UserProfile) {
        userProfile = profile
    }

    data class SendContext(
        val normChatId: String,
        val hasSession: Boolean,
        val discoveredPeerIds: List<String>,
        val knownChatIds: Set<String>,
        val activeTransportType: TransportType?,
    )

    data class MessageAddedCallback(
        val onAddToMessages: (String, Message) -> Unit,
        val onPersistToDb: suspend (Message) -> Unit,
        val onUpdateEncrypted: (String) -> Unit = {},
    )

    fun sendImage(
        uri: Uri,
        disappearingTimerMs: Long = 0L,
        ctx: SendContext,
        callbacks: MessageAddedCallback,
        pendingHandshakes: MutableMap<String, com.messenger.crisix.crypto.HandshakeInitData>,
    ) {
        val now = System.currentTimeMillis()
        val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val msgId = "img${now}"

        val newMessage = Message(
            id = msgId,
            text = "",
            isFromMe = true,
            timestamp = timeStamp,
            timestampMillis = now,
            status = MessageStatus.SENDING,
            imageUri = uri.toString(),
            isEncrypted = ctx.hasSession,
            disappearingTimerMs = disappearingTimerMs,
        )
        callbacks.onAddToMessages(ctx.normChatId, newMessage)
        scope.launch {
            callbacks.onPersistToDb(newMessage)
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (ctx.activeTransportType == TransportType.DNS_TUNNEL) {
                    Log.w(TAG, "DNS-Tunnel unterstützt keine Bilder")
                    return@launch
                }
                val maxImageBytes = when (ctx.activeTransportType) {
                    TransportType.BLUETOOTH_MESH -> 50 * 1024
                    else -> 500 * 1024
                }
                val imageBytes = com.messenger.crisix.util.ImageCompressor.compress(
                    context, uri, maxSizeBytes = maxImageBytes
                )
                val imagesDir = File(context.filesDir, "images")
                imagesDir.mkdirs()
                val localFile = File(imagesDir, "$msgId.jpg")
                localFile.writeBytes(imageBytes)
                val localUri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", localFile
                )
                val localUriStr = localUri.toString()

                messageRepository.updateImageUri(msgId, localUriStr)

                val metaJson = JSONObject().apply {
                    put("type", "image")
                    put("mime", "image/jpeg")
                    put("timestamp", timeStamp)
                    put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                    if (disappearingTimerMs > 0L) put("disappearingTimerMs", disappearingTimerMs)
                }.toString().toByteArray()
                val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()

                val binaryPayload = byteArrayOf(0x01.toByte()) + metaLen + metaJson + imageBytes

                if (!ctx.hasSession) {
                    Log.i(TAG, "Keine E2EE-Session — initiiere Handshake + Queue für Bild")
                    val handshakeData = e2eeManager.createHandshake()
                    if (handshakeData != null) {
                        pendingHandshakes[ctx.normChatId] = handshakeData
                        e2eeManager.setHandshaking(ctx.normChatId)
                        val handshakePayload = JSONObject().apply {
                            put("type", "crisix_e2ee_handshake")
                            put("data", handshakeData.preKeyBundleJson)
                            put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                        }.toString().toByteArray()
                        transportManager.sendMessage(ctx.normChatId, handshakePayload)
                            .onSuccess { Log.i(TAG, "E2EE-Handshake initiiert für ${ctx.normChatId.take(8)}") }
                            .onFailure { error -> Log.w(TAG, "Handshake-Fehler: ${error.message}") }
                    } else {
                        Log.e(TAG, "Handshake-Erstellung fehlgeschlagen")
                    }
                }

                val messagePayload = if (ctx.hasSession) {
                    val encrypted = e2eeManager.encryptOnce(ctx.normChatId, binaryPayload, "img-$msgId")
                    if (encrypted != null) {
                        Log.i(TAG, "Bild binär verschlüsselt für ${ctx.normChatId.take(8)}")
                        JSONObject().apply {
                            put("type", "crisix_e2ee")
                            put("data", encrypted)
                        }.toString().toByteArray()
                    } else {
                        Log.e(TAG, "Bild-Verschlüsselung fehlgeschlagen")
                        return@launch
                    }
                } else {
                    Log.i(TAG, "Bild in Queue (warte auf Handshake): $msgId")
                    e2eeManager.queueMessageForHandshake(
                        peerId = ctx.normChatId,
                        payload = binaryPayload,
                        uiMessageId = msgId,
                        onFlushed = { success, encryptedBase64 ->
                            if (success && encryptedBase64 != null) {
                                Log.i(TAG, "Queued image flushed: $msgId")
                                scope.launch {
                                    messageRepository.updateEncrypted(msgId)
                                    callbacks.onUpdateEncrypted(msgId)
                                    val e2eePayload = JSONObject().apply {
                                        put("type", "crisix_e2ee")
                                        put("data", encryptedBase64)
                                    }.toString().toByteArray()
                                    if (ctx.discoveredPeerIds.contains(ctx.normChatId) || ctx.knownChatIds.contains(ctx.normChatId)) {
                                        transportManager.sendMessage(ctx.normChatId, e2eePayload, uiMessageId = msgId)
                                            .onSuccess { Log.i(TAG, "Queued image sent: $msgId") }
                                            .onFailure { e -> Log.w(TAG, "Queued image send failed: ${e.message}") }
                                    }
                                }
                            } else {
                                Log.w(TAG, "Queued image failed: $msgId")
                            }
                        }
                    )
                    return@launch
                }

                if (ctx.discoveredPeerIds.contains(ctx.normChatId) || ctx.knownChatIds.contains(ctx.normChatId)) {
                    transportManager.sendMessage(ctx.normChatId, messagePayload, uiMessageId = msgId)
                        .onSuccess { Log.i(TAG, "Bild gesendet: $msgId") }
                        .onFailure { e -> Log.i(TAG, "Bild-Fehler: ${e.message}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Bild-Senden: ${e.message}", e)
            }
        }
    }

    fun sendVoice(
        audioBytes: ByteArray,
        durationMs: Long,
        disappearingTimerMs: Long = 0L,
        ctx: SendContext,
        callbacks: MessageAddedCallback,
        pendingHandshakes: MutableMap<String, com.messenger.crisix.crypto.HandshakeInitData>,
    ) {
        val now = System.currentTimeMillis()
        val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val msgId = "voice${now}"

        val newMessage = Message(
            id = msgId,
            text = "",
            isFromMe = true,
            timestamp = timeStamp,
            timestampMillis = now,
            status = MessageStatus.SENDING,
            isEncrypted = ctx.hasSession,
            disappearingTimerMs = disappearingTimerMs,
        )
        callbacks.onAddToMessages(ctx.normChatId, newMessage)
        scope.launch {
            callbacks.onPersistToDb(newMessage)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val audioDir = File(context.filesDir, "audio")
                audioDir.mkdirs()
                val localFile = File(audioDir, "$msgId.aac")
                localFile.writeBytes(audioBytes)
                val localUri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", localFile
                )
                val localUriStr = localUri.toString()

                messageRepository.updateAudioUri(msgId, localUriStr, durationMs)

                val metaJson = JSONObject().apply {
                    put("type", "voice")
                    put("mime", "audio/aac")
                    put("durationMs", durationMs)
                    put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                    if (disappearingTimerMs > 0L) put("disappearingTimerMs", disappearingTimerMs)
                }.toString().toByteArray()
                val metaLen = ByteBuffer.allocate(2).putShort(metaJson.size.toShort()).array()

                val binaryPayload = byteArrayOf(0x02.toByte()) + metaLen + metaJson + audioBytes

                if (!ctx.hasSession) {
                    Log.i(TAG, "Keine E2EE-Session — initiiere Handshake + Queue für Voice")
                    val handshakeData = e2eeManager.createHandshake()
                    if (handshakeData != null) {
                        pendingHandshakes[ctx.normChatId] = handshakeData
                        e2eeManager.setHandshaking(ctx.normChatId)
                        val handshakePayload = JSONObject().apply {
                            put("type", "crisix_e2ee_handshake")
                            put("data", handshakeData.preKeyBundleJson)
                            put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                        }.toString().toByteArray()
                        transportManager.sendMessage(ctx.normChatId, handshakePayload)
                            .onSuccess { Log.i(TAG, "E2EE-Handshake initiiert für ${ctx.normChatId.take(8)}") }
                            .onFailure { error -> Log.w(TAG, "Handshake-Fehler: ${error.message}") }
                    } else {
                        Log.e(TAG, "Handshake-Erstellung fehlgeschlagen")
                    }
                }

                val messagePayload = if (ctx.hasSession) {
                    val encrypted = e2eeManager.encryptOnce(ctx.normChatId, binaryPayload, "voice-$msgId")
                    if (encrypted != null) {
                        Log.i(TAG, "Voice binär verschlüsselt für ${ctx.normChatId.take(8)}")
                        JSONObject().apply {
                            put("type", "crisix_e2ee")
                            put("data", encrypted)
                        }.toString().toByteArray()
                    } else {
                        Log.e(TAG, "Voice-Verschlüsselung fehlgeschlagen")
                        return@launch
                    }
                } else {
                    Log.i(TAG, "Voice in Queue (warte auf Handshake): $msgId")
                    e2eeManager.queueMessageForHandshake(
                        peerId = ctx.normChatId,
                        payload = binaryPayload,
                        uiMessageId = msgId,
                        onFlushed = { success, encryptedBase64 ->
                            if (success && encryptedBase64 != null) {
                                Log.i(TAG, "Queued voice flushed: $msgId")
                                scope.launch {
                                    messageRepository.updateEncrypted(msgId)
                                    callbacks.onUpdateEncrypted(msgId)
                                    val e2eePayload = JSONObject().apply {
                                        put("type", "crisix_e2ee")
                                        put("data", encryptedBase64)
                                    }.toString().toByteArray()
                                    if (ctx.discoveredPeerIds.contains(ctx.normChatId) || ctx.knownChatIds.contains(ctx.normChatId)) {
                                        transportManager.sendMessage(ctx.normChatId, e2eePayload, uiMessageId = msgId)
                                            .onSuccess { Log.i(TAG, "Queued voice sent: $msgId") }
                                            .onFailure { e -> Log.w(TAG, "Queued voice send failed: ${e.message}") }
                                    }
                                }
                            } else {
                                Log.w(TAG, "Queued voice failed: $msgId")
                            }
                        }
                    )
                    return@launch
                }

                if (ctx.discoveredPeerIds.contains(ctx.normChatId) || ctx.knownChatIds.contains(ctx.normChatId)) {
                    transportManager.sendMessage(ctx.normChatId, messagePayload, uiMessageId = msgId)
                        .onSuccess { Log.i(TAG, "Voice gesendet: $msgId") }
                        .onFailure { e -> Log.i(TAG, "Voice-Fehler: ${e.message}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Voice-Senden: ${e.message}", e)
            }
        }
    }

    fun sendText(
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null,
        disappearingTimerMs: Long = 0L,
        ctx: SendContext,
        callbacks: MessageAddedCallback,
        pendingHandshakes: MutableMap<String, com.messenger.crisix.crypto.HandshakeInitData>,
    ) {
        val now = System.currentTimeMillis()
        val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val msgId = "m${now}"

        val hasSession = ctx.hasSession
        val newMessage = Message(
            id = msgId,
            text = text,
            isFromMe = true,
            timestamp = timeStamp,
            timestampMillis = now,
            status = MessageStatus.SENDING,
            isEncrypted = hasSession,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            disappearingTimerMs = disappearingTimerMs,
        )
        callbacks.onAddToMessages(ctx.normChatId, newMessage)
        scope.launch {
            callbacks.onPersistToDb(newMessage)
        }

        val isRealPeer = ctx.discoveredPeerIds.contains(ctx.normChatId) || ctx.knownChatIds.contains(ctx.normChatId)
        if (isRealPeer) {
            scope.launch(Dispatchers.IO) {
                if (!hasSession) {
                    Log.i(TAG, "Keine E2EE-Session mit ${ctx.normChatId.take(8)} -> initiiere Handshake + Queue")
                    val handshakeData = e2eeManager.createHandshake()
                    if (handshakeData != null) {
                        pendingHandshakes[ctx.normChatId] = handshakeData
                        e2eeManager.setHandshaking(ctx.normChatId)
                        val handshakePayload = JSONObject().apply {
                            put("type", "crisix_e2ee_handshake")
                            put("data", handshakeData.preKeyBundleJson)
                            put("ephemeralKey", Base64.encodeToString(handshakeData.ownEphemeralPublicKey, Base64.NO_WRAP))
                        }.toString().toByteArray()
                        transportManager.sendMessage(ctx.normChatId, handshakePayload)
                            .onSuccess { Log.i(TAG, "E2EE-Handshake initiiert für ${ctx.normChatId.take(8)}") }
                            .onFailure { error -> Log.w(TAG, "Handshake-Fehler: ${error.message}") }
                    } else {
                        Log.e(TAG, "Handshake-Erstellung fehlgeschlagen")
                    }
                }

                val messagePayload = if (hasSession) {
                    val plainMessage = JSONObject().apply {
                        put("type", "message")
                        put("text", text)
                        put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                        put("timestamp", timeStamp)
                        if (replyToId != null) put("replyToId", replyToId)
                        if (replyToText != null) put("replyToText", replyToText)
                        if (replyToSender != null) put("replyToSender", replyToSender)
                        if (disappearingTimerMs > 0L) put("disappearingTimerMs", disappearingTimerMs)
                    }.toString().toByteArray()
                    val encrypted = e2eeManager.encryptOnce(ctx.normChatId, plainMessage, "msg-$msgId")
                    if (encrypted != null) {
                        Log.i(TAG, "Nachricht verschlüsselt für ${ctx.normChatId.take(8)}")
                        JSONObject().apply {
                            put("type", "crisix_e2ee")
                            put("data", encrypted)
                        }.toString().toByteArray()
                    } else {
                        Log.e(TAG, "Verschlüsselung fehlgeschlagen")
                        return@launch
                    }
                } else {
                    Log.i(TAG, "Nachricht in Queue (warte auf Handshake): $msgId")
                    val plainMessage = JSONObject().apply {
                        put("type", "message")
                        put("text", text)
                        put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                        put("timestamp", timeStamp)
                        if (replyToId != null) put("replyToId", replyToId)
                        if (replyToText != null) put("replyToText", replyToText)
                        if (replyToSender != null) put("replyToSender", replyToSender)
                        if (disappearingTimerMs > 0L) put("disappearingTimerMs", disappearingTimerMs)
                    }.toString().toByteArray()
                    e2eeManager.queueMessageForHandshake(
                        peerId = ctx.normChatId,
                        payload = plainMessage,
                        uiMessageId = msgId,
                        onFlushed = { success, encryptedBase64 ->
                            if (success && encryptedBase64 != null) {
                                Log.i(TAG, "Queued message flushed: $msgId")
                                scope.launch {
                                    messageRepository.updateEncrypted(msgId)
                                    callbacks.onUpdateEncrypted(msgId)
                                    val e2eePayload = JSONObject().apply {
                                        put("type", "crisix_e2ee")
                                        put("data", encryptedBase64)
                                    }.toString().toByteArray()
                                    transportManager.sendMessage(ctx.normChatId, e2eePayload, uiMessageId = msgId)
                                        .onSuccess { Log.i(TAG, "Queued message sent: $msgId") }
                                        .onFailure { error -> Log.w(TAG, "Queued message send failed: ${error.message}") }
                                }
                            } else {
                                Log.w(TAG, "Queued message failed: $msgId")
                            }
                        }
                    )
                    return@launch
                }

                transportManager.sendMessage(ctx.normChatId, messagePayload, uiMessageId = msgId)
                    .onSuccess { Log.i(TAG, "Nachricht gesendet an ${ctx.normChatId.take(8)}") }
                    .onFailure { error -> Log.w(TAG, "Fehler beim Senden: ${error.message}") }
            }
        }
    }

    fun sendTimerNotification(
        chatId: String,
        disappearingTimerMs: Long,
        ctx: SendContext,
    ) {
        val timerLabel = when (disappearingTimerMs) {
            0L -> context.getString(R.string.timer_off)
            30_000L -> context.getString(R.string.timer_30s)
            300_000L -> context.getString(R.string.timer_5m)
            3_600_000L -> context.getString(R.string.timer_1h)
            86_400_000L -> context.getString(R.string.timer_24h)
            604_800_000L -> context.getString(R.string.timer_7d)
            else -> formatTimerMs(disappearingTimerMs)
        }
        val hintText = context.getString(R.string.timer_set_hint, timerLabel)

        val now = System.currentTimeMillis()
        val timeStamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
        val msgId = "sys-timer-notify-${chatId}-$now"

        val newMessage = Message(
            id = msgId,
            text = hintText,
            isFromMe = false,
            timestamp = timeStamp,
            timestampMillis = now,
            status = com.messenger.crisix.transport.MessageStatus.DELIVERED,
            isSystemMessage = true,
            hintStatus = com.messenger.crisix.ui.components.HintStatus.SUCCESS,
            disappearingTimerMs = disappearingTimerMs,
        )

        val isRealPeer = ctx.discoveredPeerIds.contains(chatId) || ctx.knownChatIds.contains(chatId)
        if (isRealPeer) {
            scope.launch(Dispatchers.IO) {
                val notificationJson = JSONObject().apply {
                    put("type", "crisix_timer")
                    put("disappearingTimerMs", disappearingTimerMs)
                    put("sender", userProfile.name.ifBlank { context.getString(R.string.crisix_app_default_sender) })
                }.toString().toByteArray()
                transportManager.sendMessage(chatId, notificationJson)
                    .onSuccess { Log.i(TAG, "Timer-Notification gesendet an ${chatId.take(8)}: ${disappearingTimerMs}ms") }
                    .onFailure { error -> Log.w(TAG, "Timer-Notification Fehler: ${error.message}") }
            }
        }
    }

    private fun formatTimerMs(ms: Long): String = when {
        ms < 60_000L -> "${ms / 1000}s"
        ms < 3_600_000L -> "${ms / 60_000L}m"
        else -> "${ms / 3_600_000L}h"
    }
}

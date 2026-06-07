package com.messenger.crisix.transport

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
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
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class SmsTransport(
    private val localPeerId: String,
    private val appContext: Context
) : Transport {

    companion object {
        private const val TAG = "SmsTransport"
        private const val CRISIX_PREFIX = "CRSX:"
    }

    override val type: TransportType = TransportType.SMS
    override val capabilities: TransportCapabilities = TransportCapabilities(
        supportsText = true,
        maxTextLength = 160,
        supportsImages = false,
        supportsVideo = false,
        supportsAudio = false,
        supportsFileTransfer = false,
        isMetered = true,
        maxPayloadSize = 140,
        requiresProbing = false,
        supportsUiMessageIdSuffix = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<(String, ByteArray) -> Unit>()

    var onDeliveryAck: ((messageId: String, peerId: String) -> Unit)? = null
    var phoneNumberResolver: ((peerId: String) -> String?)? = null

    @Volatile private var isRunning = false
    private var smsReceiver: SmsReceiver? = null
    @Volatile private var phoneNumber: String? = null
    private var permissionDenied = false

    private val peerChannel = Channel<Peer>(Channel.UNLIMITED)
    private val connectedPeers = ConcurrentHashMap<String, Peer>()
    private val pendingAcks = ConcurrentHashMap<String, Job>()

    private val smsManager: SmsManager?
        get() = runCatching { SmsManager.getDefault() }.getOrNull()

    override fun getStatusDetail(): Pair<Int, String> {
        val peerCount = connectedPeers.size
        val detail = when {
            !isRunning -> "Nicht gestartet"
            permissionDenied -> "Keine SMS-Berechtigung"
            phoneNumber == null -> "Keine SIM-Karte"
            else -> "SMS bereit (${peerCount} Peer${if (peerCount != 1) "s" else ""})"
        }
        return Pair(peerCount, detail)
    }

    override suspend fun isAvailable(): Boolean {
        if (permissionDenied) return false
        return withContext(Dispatchers.IO) {
            try {
                val hasTelephony = appContext.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY
                )
                if (!hasTelephony) return@withContext false

                val hasSendSms = ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED

                val hasReceiveSms = ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasSendSms || !hasReceiveSms) {
                    permissionDenied = true
                    return@withContext false
                }

                smsManager != null
            } catch (e: Exception) {
                Timber.w(e, "SMS availability check failed")
                false
            }
        }
    }

    override suspend fun send(peerId: String, data: ByteArray): Result<Unit> {
        if (permissionDenied) return Result.failure(Exception("Keine SMS-Berechtigung"))

        val normalizedPeerId = peerId.split("@").first()
        val phoneNumber = phoneNumberResolver?.invoke(normalizedPeerId)
            ?: return Result.failure(Exception("Keine Telefonnummer für Peer $normalizedPeerId hinterlegt"))

        return withContext(Dispatchers.IO) {
            val mgr = smsManager ?: return@withContext Result.failure(Exception("SmsManager nicht verfügbar"))
            val normalizedPhone = normalizePhoneNumber(phoneNumber)

            try {
                val base64Payload = Base64.encodeToString(data, Base64.NO_WRAP)
                val smsText = "$CRISIX_PREFIX$base64Payload"
                val totalParts = mgr.divideMessage(smsText)

                if (totalParts.size == 1) {
                    mgr.sendTextMessage(normalizedPhone, null, smsText, null, null)
                } else {
                    val sentIntents = ArrayList<PendingIntent?>()
                    val deliveryIntents = ArrayList<PendingIntent?>()
                    for (i in totalParts.indices) {
                        sentIntents.add(null)
                        deliveryIntents.add(null)
                    }
                    mgr.sendMultipartTextMessage(normalizedPhone, null, totalParts, sentIntents, deliveryIntents)
                }

                Log.i(TAG, "SMS sent to $normalizedPhone: ${data.size} bytes in ${totalParts.size} part(s)")
                Result.success(Unit)
            } catch (e: SecurityException) {
                permissionDenied = true
                Log.w(TAG, "SMS security exception: ${e.message}")
                Result.failure(Exception("SMS-Berechtigung fehlt: ${e.message}"))
            } catch (e: Exception) {
                Log.w(TAG, "SMS send failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override fun registerListener(listener: (String, ByteArray) -> Unit) {
        listeners.add(listener)
    }

    override fun discoverPeers(): Flow<Peer> = peerChannel.receiveAsFlow()

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        val hasSendSms = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val hasReceiveSms = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSendSms || !hasReceiveSms) {
            permissionDenied = true
            Log.w(TAG, "SMS permissions missing – SEND_SMS=$hasSendSms, RECEIVE_SMS=$hasReceiveSms")
            return
        }

        phoneNumber = getMyPhoneNumber()
        if (phoneNumber == null) {
            Log.w(TAG, "Keine eigene Telefonnummer ermittelbar — SMS-Empfang eingeschränkt")
        }

        registerSmsReceiver()
        Log.i(TAG, "SmsTransport gestartet (Tel: ${phoneNumber ?: "unbekannt"})")
    }

    override suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            smsReceiver?.let { appContext.unregisterReceiver(it) }
        } catch (_: Exception) {}
        smsReceiver = null

        connectedPeers.clear()
        pendingAcks.clear()
        scope.cancel()
        Log.i(TAG, "SmsTransport gestoppt")
    }

    // ─── SMS Receiver ────────────────────────────────────────────────

    private fun registerSmsReceiver() {
        smsReceiver = SmsReceiver().apply {
            onMessageReceived = { senderPhone, payload ->
                handleIncomingSms(senderPhone, payload)
            }
            onAckReceived = { senderPhone, messageId ->
                onDeliveryAck?.invoke("ack-$senderPhone-${messageId.take(16)}", senderPhone)
                Log.d(TAG, "SMS-ACK received from $senderPhone for $messageId")
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.provider.Telephony.SMS_RECEIVED")
            addAction("android.provider.Telephony.SMS_DELIVER")
        }
        appContext.registerReceiver(smsReceiver, filter)
    }

    private fun handleIncomingSms(senderPhone: String, payload: ByteArray) {
        val peer = connectedPeers.getOrPut(senderPhone) {
            Peer(senderPhone, senderPhone)
        }

        if (connectedPeers.size == 1 && !connectedPeers.containsKey(senderPhone)) {
            scope.launch {
                peerChannel.send(peer)
            }
        }

        listeners.forEach { it(senderPhone, payload) }

        scope.launch {
            sendAck(senderPhone)
        }
    }

    private suspend fun sendAck(phoneNumber: String) {
        try {
            val mgr = smsManager ?: return
            val normalizedPhone = normalizePhoneNumber(phoneNumber)
            val ackId = "ack-${System.currentTimeMillis()}"
            val ackText = "${CRISIX_PREFIX}ACK:$ackId"
            mgr.sendTextMessage(normalizedPhone, null, ackText, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "SMS ACK senden fehlgeschlagen: ${e.message}")
        }
    }

    // ─── Telefonnummer-Ermittlung ────────────────────────────────────

    @SuppressLint("HardwareIds")
    private fun getMyPhoneNumber(): String? {
        return try {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return null

            // API 33+: eigene Nummer über SubscriptionManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val subMgr = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val activeSub = subMgr?.activeSubscriptionInfoList?.firstOrNull {
                    it.subscriptionId == SubscriptionManager.getDefaultSubscriptionId()
                }
                activeSub?.number?.takeIf { it.isNotBlank() }
            } else {
                @Suppress("DEPRECATION")
                tm.line1Number?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get phone number")
            null
        }
    }

    // ─── Hilfsfunktionen ──────────────────────────────────────────────

    private fun normalizePhoneNumber(number: String): String {
        return number
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
    }
}

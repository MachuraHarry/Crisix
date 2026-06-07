package com.messenger.crisix.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val CRISIX_PREFIX = "CRSX:"
        private const val MULTIPART_TIMEOUT_MS = 30_000L
    }

    internal var onMessageReceived: ((senderPhone: String, payload: ByteArray) -> Unit)? = null
    internal var onAckReceived: ((senderPhone: String, messageId: String) -> Unit)? = null

    private data class MultipartBuffer(
        val parts: MutableMap<Int, String> = mutableMapOf(),
        val totalParts: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val multipartBuffers = ConcurrentHashMap<String, MultipartBuffer>()

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED" &&
            intent.action != "android.provider.Telephony.SMS_DELIVER"
        ) return

        val messages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("pdus", Array<SmsMessage>::class.java)?.toList()
            } else {
                @Suppress("DEPRECATION")
                val rawPdus = intent.getSerializableExtra("pdus") as? Array<*>
                rawPdus?.mapNotNull { it as? ByteArray }?.map { SmsMessage.createFromPdu(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SMS PDUs: ${e.message}")
            emptyList()
        } ?: emptyList()

        if (messages.isEmpty()) return

        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val body = sms.messageBody ?: continue

            if (!body.startsWith(CRISIX_PREFIX)) continue

            val content = body.removePrefix(CRISIX_PREFIX)
            if (content.isEmpty()) continue

            handleCrisixSms(sender, content, sms.indexOnIcc, sms.messageClass?.ordinal ?: 0)
        }

        cleanupStaleBuffers()
    }

    private fun handleCrisixSms(sender: String, content: String, partIndex: Int, totalHint: Int) {
        if (content.startsWith("ACK:")) {
            val msgId = content.removePrefix("ACK:")
            onAckReceived?.invoke(sender, msgId)
            return
        }

        // Multipart-SMS: Prüfe User-Data-Header auf Concatenation Info
        // Wenn keine Multipart-Info → direkte Nachricht
        val bufferKey = "$sender-${System.currentTimeMillis() / 10_000}"

        val existing = multipartBuffers[bufferKey]
        if (existing != null) {
            val timeSinceStart = System.currentTimeMillis() - existing.timestamp
            if (timeSinceStart > MULTIPART_TIMEOUT_MS) {
                multipartBuffers.remove(bufferKey)
                multipartBuffers[bufferKey] = MultipartBuffer(
                    parts = mutableMapOf(0 to content),
                    timestamp = System.currentTimeMillis()
                )
            } else {
                val newParts = existing.parts.toMutableMap()
                newParts[partIndex] = content
                multipartBuffers[bufferKey] = existing.copy(parts = newParts)
                // Alle Teile eines Multipart haben den gleichen concat-Header,
                // vereinfacht: nach Timeout oder vollständigem Empfang reassemblieren
            }
        } else {
            multipartBuffers[bufferKey] = MultipartBuffer(
                parts = mutableMapOf(0 to content),
                timestamp = System.currentTimeMillis()
            )
        }

        // Versuche Reassembly: alle gesammelten Teile für diesen Key zusammenführen
        val buffer = multipartBuffers[bufferKey] ?: return
        val reassembled = buffer.parts.entries
            .sortedBy { it.key }
            .joinToString("") { it.value }

        try {
            val payload = android.util.Base64.decode(reassembled, android.util.Base64.DEFAULT)
            onMessageReceived?.invoke(sender, payload)
            multipartBuffers.remove(bufferKey)
            Log.i(TAG, "SMS received from $sender: ${payload.size} bytes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode SMS payload from $sender: ${e.message}")
        }
    }

    private fun cleanupStaleBuffers() {
        val now = System.currentTimeMillis()
        val stale = multipartBuffers.entries.filter {
            now - it.value.timestamp > MULTIPART_TIMEOUT_MS
        }
        for (entry in stale) {
            multipartBuffers.remove(entry.key)
        }
    }
}

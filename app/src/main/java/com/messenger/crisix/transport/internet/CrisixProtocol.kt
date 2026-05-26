package com.messenger.crisix.transport.internet

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Versiegelte Klasse, die die verschiedenen Nachrichtentypen im Crisix-P2P-Netzwerk repräsentiert.
 *
 * Bietet eine typsichere API für die Arbeit mit verschiedenen Nachrichtenformaten.
 * Jeder Typ enthält die spezifischen Felder, die für diesen Nachrichtentyp relevant sind.
 *
 * ## Serialisierungsformat
 * Das Nachrichtenformat verwendet ein einfaches binäres TLV-ähnliches Format
 * (Type-Length-Value), das ohne externe Bibliotheken auskommt:
 *
 * ```
 * [Typ: 1 Byte] [Länge: 4 Bytes] [Wert: variable Länge]
 * ```
 *
 * ## Verwendung
 * ```kotlin
 * val message = CrisixProtocol.CrisixMessage(
 *     messageId = UUID.randomUUID().toString(),
 *     senderId = "me",
 *     recipientId = "peer",
 *     type = CrisixProtocol.MessageType.CHAT_MESSAGE,
 *     payload = "Hallo Welt".toByteArray()
 * )
 * val encoded = CrisixProtocol.encodeMessage(message)
 * val decoded = CrisixProtocol.decodeMessage(encoded)
 * ```
 */
object CrisixProtocol {

    private const val TAG = "CrisixProtocol"

    // =========================================================================
    // Binäre Protokollkonstanten
    // =========================================================================

    /** Magic Number zur Identifikation des Crisix-Protokolls (4 Bytes) */
    private const val PROTOCOL_MAGIC: Int = 0x43524958 // "CRIX"

    /** Aktuelle Protokollversion */
    private const val PROTOCOL_VERSION: Byte = 0x01

    /** Feld-Typ-IDs für die Serialisierung */
    private const val FIELD_MESSAGE_ID: Byte = 0x01
    private const val FIELD_SENDER_ID: Byte = 0x02
    private const val FIELD_RECIPIENT_ID: Byte = 0x03
    private const val FIELD_TYPE: Byte = 0x04
    private const val FIELD_PAYLOAD: Byte = 0x05
    private const val FIELD_TIMESTAMP: Byte = 0x06
    private const val FIELD_NONCE: Byte = 0x07
    private const val FIELD_SEQUENCE: Byte = 0x08

    /** Nachrichtentyp-IDs für die Serialisierung */
    private const val TYPE_CHAT_MESSAGE: Byte = 0x00
    private const val TYPE_ACK: Byte = 0x01
    private const val TYPE_FILE_TRANSFER: Byte = 0x02
    private const val TYPE_PING: Byte = 0x03
    private const val TYPE_PONG: Byte = 0x04
    private const val TYPE_TYPING: Byte = 0x05
    private const val TYPE_STATUS_UPDATE: Byte = 0x06

    // =========================================================================
    // Nachrichtentypen
    // =========================================================================

    /**
     * Versiegelte Klasse für typsichere Nachrichtenrepräsentation.
     * Erzwingt die vollständige Abdeckung aller Nachrichtentypen im when-Ausdruck.
     */
    sealed class MessageType {
        /** Normale Text-Chat-Nachricht */
        data object CHAT_MESSAGE : MessageType()

        /** Bestätigung für den Empfang einer Nachricht */
        data object ACK : MessageType()

        /** Dateiübertragung (Bild, Video, Audio, etc.) */
        data object FILE_TRANSFER : MessageType()

        /** Ping für Verbindungstest */
        data object PING : MessageType()

        /** Antwort auf einen Ping */
        data object PONG : MessageType()

        /** Typing-Indikator */
        data object TYPING : MessageType()

        /** Peer-Status-Update */
        data object STATUS_UPDATE : MessageType()

        /**
         * Konvertiert den versiegelten Typ in den binären Typ-ID-Wert.
         */
        fun toByte(): Byte = when (this) {
            CHAT_MESSAGE -> TYPE_CHAT_MESSAGE
            ACK -> TYPE_ACK
            FILE_TRANSFER -> TYPE_FILE_TRANSFER
            PING -> TYPE_PING
            PONG -> TYPE_PONG
            TYPING -> TYPE_TYPING
            STATUS_UPDATE -> TYPE_STATUS_UPDATE
        }

        companion object {
            /**
             * Konvertiert einen binären Typ-ID-Wert in den versiegelten Typ.
             */
            fun fromByte(value: Byte): MessageType = when (value) {
                TYPE_CHAT_MESSAGE -> CHAT_MESSAGE
                TYPE_ACK -> ACK
                TYPE_FILE_TRANSFER -> FILE_TRANSFER
                TYPE_PING -> PING
                TYPE_PONG -> PONG
                TYPE_TYPING -> TYPING
                TYPE_STATUS_UPDATE -> STATUS_UPDATE
                else -> {
                    Log.w(TAG, "Unbekannter Nachrichtentyp: $value, behandle als CHAT_MESSAGE")
                    CHAT_MESSAGE
                }
            }
        }
    }

    // =========================================================================
    // Nachrichten-Datenklasse
    // =========================================================================

    /**
     * Datenklasse für eine vollständige Crisix-Nachricht.
     *
     * @property messageId Eindeutige Nachrichten-ID (UUID v4)
     * @property senderId Peer-ID des Absenders (Base58-kodiert)
     * @property recipientId Peer-ID des Empfängers (Base58-kodiert)
     * @property type Typ der Nachricht
     * @property payload Nutzlast der Nachricht (je nach Typ unterschiedlich)
     * @property timestamp Unix-Zeitstempel in Millisekunden (UTC)
     * @property nonce Zufällige Nonce zur Replay-Prävention
     * @property sequenceNumber Sequenznummer für Nachrichtenreihenfolge
     */
    data class CrisixMessage(
        val messageId: String,
        val senderId: String,
        val recipientId: String,
        val type: MessageType,
        val payload: ByteArray,
        val timestamp: Long = System.currentTimeMillis(),
        val nonce: ByteArray = generateNonce(),
        val sequenceNumber: Int = 0
    ) {
        /**
         * Vergleicht zwei Nachrichten anhand ihrer IDs.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CrisixMessage) return false
            return messageId == other.messageId
        }

        override fun hashCode(): Int = messageId.hashCode()

        /**
         * Erzeugt eine lesbare String-Repräsentation der Nachricht.
         */
        override fun toString(): String {
            return "CrisixMessage(id='$messageId', type=$type, from='$senderId', to='$recipientId', " +
                    "size=${payload.size} bytes, ts=$timestamp)"
        }
    }

    // =========================================================================
    // Serialisierung
    // =========================================================================

    /**
     * Kodiert eine CrisixMessage in ein Byte-Array.
     *
     * Verwendet ein einfaches binäres Format:
     * - Magic Number (4 Bytes): "CRIX" zur Identifikation
     * - Protokollversion (1 Byte)
     * - Anzahl der Felder (2 Bytes)
     * - Felder im TLV-Format: [Typ: 1 Byte] [Länge: 4 Bytes] [Wert: variable Länge]
     *
     * @param message Die zu kodierende Nachricht
     * @return Die kodierten Bytes
     */
    fun encodeMessage(message: CrisixMessage): ByteArray {
        return try {
            ByteArrayOutputStream().use { baos ->
                DataOutputStream(baos).use { dos ->
                    // Header: Magic + Version
                    dos.writeInt(PROTOCOL_MAGIC)
                    dos.writeByte(PROTOCOL_VERSION.toInt())

                    // Temporären Buffer für Felder
                    val fieldBuffer = ByteArrayOutputStream()
                    val fieldStream = DataOutputStream(fieldBuffer)

                    // Felder im TLV-Format schreiben
                    writeStringField(fieldStream, FIELD_MESSAGE_ID, message.messageId)
                    writeStringField(fieldStream, FIELD_SENDER_ID, message.senderId)
                    writeStringField(fieldStream, FIELD_RECIPIENT_ID, message.recipientId)
                    writeByteField(fieldStream, FIELD_TYPE, message.type.toByte())
                    writeBytesField(fieldStream, FIELD_PAYLOAD, message.payload)
                    writeLongField(fieldStream, FIELD_TIMESTAMP, message.timestamp)
                    writeBytesField(fieldStream, FIELD_NONCE, message.nonce)
                    writeIntField(fieldStream, FIELD_SEQUENCE, message.sequenceNumber)

                    fieldStream.flush()
                    val fieldData = fieldBuffer.toByteArray()

                    // Anzahl der Felder + Felddaten
                    dos.writeShort(fieldData.size)
                    dos.write(fieldData)
                    dos.flush()

                    baos.toByteArray()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Kodieren der Nachricht: ${e.message}", e)
            throw RuntimeException("Kodierung fehlgeschlagen", e)
        }
    }

    /**
     * Dekodiert ein Byte-Array in eine CrisixMessage.
     *
     * @param data Die kodierten Bytes
     * @return Die dekodierte Nachricht, oder null bei Fehlern
     */
    fun decodeMessage(data: ByteArray): CrisixMessage? {
        return try {
            ByteArrayInputStream(data).use { bais ->
                DataInputStream(bais).use { dis ->
                    // Header prüfen: Magic + Version
                    val magic = dis.readInt()
                    if (magic != PROTOCOL_MAGIC) {
                        Log.w(TAG, "Ungültige Magic Number: ${Integer.toHexString(magic)}")
                        return null
                    }

                    val version = dis.readByte()
                    if (version != PROTOCOL_VERSION) {
                        Log.w(TAG, "Nicht unterstützte Protokollversion: $version")
                        return null
                    }

                    // Felddaten lesen
                    val fieldDataLength = dis.readUnsignedShort()
                    val fieldData = ByteArray(fieldDataLength)
                    dis.readFully(fieldData)

                    // Felder parsen
                    parseFields(fieldData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Dekodieren der Nachricht: ${e.message}", e)
            null
        }
    }

    // =========================================================================
    // Hilfsfunktionen für die Feld-Serialisierung
    // =========================================================================

    /**
     * Schreibt ein String-Feld im TLV-Format.
     */
    private fun writeStringField(stream: DataOutputStream, fieldType: Byte, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        stream.writeByte(fieldType.toInt())
        stream.writeInt(bytes.size)
        stream.write(bytes)
    }

    /**
     * Schreibt ein Byte-Feld im TLV-Format.
     */
    private fun writeByteField(stream: DataOutputStream, fieldType: Byte, value: Byte) {
        stream.writeByte(fieldType.toInt())
        stream.writeInt(1)
        stream.writeByte(value.toInt())
    }

    /**
     * Schreibt ein Integer-Feld im TLV-Format.
     */
    private fun writeIntField(stream: DataOutputStream, fieldType: Byte, value: Int) {
        stream.writeByte(fieldType.toInt())
        stream.writeInt(4)
        stream.writeInt(value)
    }

    /**
     * Schreibt ein Long-Feld im TLV-Format.
     */
    private fun writeLongField(stream: DataOutputStream, fieldType: Byte, value: Long) {
        stream.writeByte(fieldType.toInt())
        stream.writeInt(8)
        stream.writeLong(value)
    }

    /**
     * Schreibt ein Byte-Array-Feld im TLV-Format.
     */
    private fun writeBytesField(stream: DataOutputStream, fieldType: Byte, value: ByteArray) {
        stream.writeByte(fieldType.toInt())
        stream.writeInt(value.size)
        stream.write(value)
    }

    /**
     * Parst die TLV-Felder und erstellt eine CrisixMessage.
     */
    private fun parseFields(fieldData: ByteArray): CrisixMessage? {
        var messageId: String? = null
        var senderId: String? = null
        var recipientId: String? = null
        var type: MessageType? = null
        var payload: ByteArray? = null
        var timestamp: Long = System.currentTimeMillis()
        var nonce: ByteArray = generateNonce()
        var sequenceNumber: Int = 0

        try {
            ByteArrayInputStream(fieldData).use { bais ->
                DataInputStream(bais).use { dis ->
                    while (bais.available() > 0) {
                        val fieldType = dis.readByte()
                        val fieldLength = dis.readInt()

                        when (fieldType) {
                            FIELD_MESSAGE_ID -> {
                                val bytes = ByteArray(fieldLength)
                                dis.readFully(bytes)
                                messageId = String(bytes, Charsets.UTF_8)
                            }
                            FIELD_SENDER_ID -> {
                                val bytes = ByteArray(fieldLength)
                                dis.readFully(bytes)
                                senderId = String(bytes, Charsets.UTF_8)
                            }
                            FIELD_RECIPIENT_ID -> {
                                val bytes = ByteArray(fieldLength)
                                dis.readFully(bytes)
                                recipientId = String(bytes, Charsets.UTF_8)
                            }
                            FIELD_TYPE -> {
                                val typeByte = dis.readByte()
                                type = MessageType.fromByte(typeByte)
                            }
                            FIELD_PAYLOAD -> {
                                payload = ByteArray(fieldLength)
                                dis.readFully(payload)
                            }
                            FIELD_TIMESTAMP -> {
                                timestamp = dis.readLong()
                            }
                            FIELD_NONCE -> {
                                nonce = ByteArray(fieldLength)
                                dis.readFully(nonce)
                            }
                            FIELD_SEQUENCE -> {
                                sequenceNumber = dis.readInt()
                            }
                            else -> {
                                // Unbekanntes Feld überspringen
                                dis.skipBytes(fieldLength)
                            }
                        }
                    }
                }
            }

            // Prüfen, ob alle Pflichtfelder vorhanden sind
            if (messageId == null || senderId == null || recipientId == null || type == null || payload == null) {
                Log.w(TAG, "Unvollständige Nachricht: fehlende Pflichtfelder")
                return null
            }

            return CrisixMessage(
                messageId = messageId,
                senderId = senderId,
                recipientId = recipientId,
                type = type,
                payload = payload,
                timestamp = timestamp,
                nonce = nonce,
                sequenceNumber = sequenceNumber
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Parsen der Felder: ${e.message}", e)
            return null
        }
    }

    // =========================================================================
    // Hilfsfunktionen für Nachrichtenerstellung
    // =========================================================================

    /**
     * Erstellt eine ACK-Nachricht als Antwort auf eine empfangene Nachricht.
     *
     * @param originalMessage Die ursprüngliche Nachricht, die bestätigt wird
     * @param localPeerId Die eigene Peer-ID
     * @return Eine ACK-Nachricht
     */
    fun createAck(originalMessage: CrisixMessage, localPeerId: String): CrisixMessage {
        return CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = localPeerId,
            recipientId = originalMessage.senderId,
            type = MessageType.ACK,
            payload = originalMessage.messageId.toByteArray(), // Bestätige die Original-ID
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Erstellt eine PING-Nachricht für Verbindungstests.
     *
     * @param localPeerId Die eigene Peer-ID
     * @param targetPeerId Die Peer-ID des Ziels
     * @return Eine PING-Nachricht
     */
    fun createPing(localPeerId: String, targetPeerId: String): CrisixMessage {
        return CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = localPeerId,
            recipientId = targetPeerId,
            type = MessageType.PING,
            payload = "ping".toByteArray(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Erstellt eine PONG-Nachricht als Antwort auf einen Ping.
     *
     * @param pingMessage Die empfangene PING-Nachricht
     * @param localPeerId Die eigene Peer-ID
     * @return Eine PONG-Nachricht
     */
    fun createPong(pingMessage: CrisixMessage, localPeerId: String): CrisixMessage {
        return CrisixMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = localPeerId,
            recipientId = pingMessage.senderId,
            type = MessageType.PONG,
            payload = pingMessage.messageId.toByteArray(), // Referenz auf den Ping
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Generiert eine zufällige Nonce für Replay-Schutz.
     *
     * @return 16 zufällige Bytes
     */
    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonce)
        return nonce
    }
}

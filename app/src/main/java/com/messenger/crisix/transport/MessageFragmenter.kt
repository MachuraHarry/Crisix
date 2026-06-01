package com.messenger.crisix.transport

import java.security.SecureRandom

object Fragmenter {

    private val random = SecureRandom()

    fun split(data: ByteArray, maxChunkSize: Int): List<Chunk> {
        require(maxChunkSize >= MIN_CHUNK_SIZE) {
            "maxChunkSize must be at least $MIN_CHUNK_SIZE bytes, got $maxChunkSize"
        }
        val messageId = ByteArray(8).also { random.nextBytes(it) }
        val dataPerChunk = maxChunkSize - HEADER_SIZE
        val totalChunks = ((data.size + dataPerChunk - 1) / dataPerChunk).coerceAtLeast(1)

        return (0 until totalChunks).map { index ->
            val offset = index * dataPerChunk
            val end = minOf(offset + dataPerChunk, data.size)
            val chunkData = data.copyOfRange(offset, end)
            Chunk(
                messageId = messageId,
                totalChunks = totalChunks,
                chunkIndex = index,
                data = chunkData
            )
        }
    }

    fun isChunk(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1
    }

    data class Chunk(
        val messageId: ByteArray,
        val totalChunks: Int,
        val chunkIndex: Int,
        val data: ByteArray
    ) {
        fun toBytes(): ByteArray {
            val len = HEADER_SIZE + data.size
            val result = ByteArray(len)
            result[0] = MAGIC_0
            result[1] = MAGIC_1
            result[2] = VERSION
            for (i in 0 until 8) result[3 + i] = messageId[i]
            result[11] = ((totalChunks shr 8) and 0xFF).toByte()
            result[12] = (totalChunks and 0xFF).toByte()
            result[13] = ((chunkIndex shr 8) and 0xFF).toByte()
            result[14] = (chunkIndex and 0xFF).toByte()
            result[15] = ((data.size shr 8) and 0xFF).toByte()
            result[16] = (data.size and 0xFF).toByte()
            for (i in data.indices) result[HEADER_SIZE + i] = data[i]
            return result
        }

        companion object {
            fun fromBytes(bytes: ByteArray): Chunk? {
                if (bytes.size < HEADER_SIZE) return null
                if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
                if (bytes[2] != VERSION) return null
                val messageId = bytes.copyOfRange(3, 11)
                val totalChunks = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[12].toInt() and 0xFF)
                val chunkIndex = ((bytes[13].toInt() and 0xFF) shl 8) or (bytes[14].toInt() and 0xFF)
                val dataLen = ((bytes[15].toInt() and 0xFF) shl 8) or (bytes[16].toInt() and 0xFF)
                if (HEADER_SIZE + dataLen > bytes.size) return null
                val data = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + dataLen)
                return Chunk(messageId, totalChunks, chunkIndex, data)
            }
        }
    }

    internal const val MAGIC_0: Byte = 0x43
    internal const val MAGIC_1: Byte = 0x58
    internal const val VERSION: Byte = 0x01
    internal const val HEADER_SIZE: Int = 17
    internal const val MIN_CHUNK_SIZE: Int = HEADER_SIZE + 1
}

internal fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}

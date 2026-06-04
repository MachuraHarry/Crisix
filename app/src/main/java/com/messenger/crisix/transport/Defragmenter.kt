package com.messenger.crisix.transport

import android.util.Log
import com.messenger.crisix.util.toHex
import java.util.concurrent.ConcurrentHashMap

class Defragmenter(private val chunkTimeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    companion object {
        private const val TAG = "Defragmenter"
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val buffers = ConcurrentHashMap<String, ReassemblyBuffer>()

    data class ReassemblyBuffer(
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        val createdAt: Long = System.currentTimeMillis(),
        var receivedCount: Int = 0
    )

    fun addChunk(chunk: Fragmenter.Chunk): ByteArray? {
        val messageId = chunk.messageId.toHex()
        val buffer = buffers.getOrPut(messageId) {
            ReassemblyBuffer(totalChunks = chunk.totalChunks, chunks = arrayOfNulls(chunk.totalChunks))
        }

        if (buffer.totalChunks != chunk.totalChunks) {
            Log.w(TAG, "Chunk totalChunks mismatch for $messageId: expected=${buffer.totalChunks}, got=${chunk.totalChunks}")
            return null
        }

        if (chunk.chunkIndex !in 0 until buffer.totalChunks) {
            Log.w(TAG, "Chunk index out of range for $messageId: ${chunk.chunkIndex}/${buffer.totalChunks}")
            return null
        }

        if (buffer.chunks[chunk.chunkIndex] != null) {
            Log.d(TAG, "Duplicate chunk $messageId#${chunk.chunkIndex} — ignoring")
            return null
        }

        buffer.chunks[chunk.chunkIndex] = chunk.data
        val count = buffer.receivedCount + 1
        buffer.receivedCount = count

        if (count == buffer.totalChunks) {
            buffers.remove(messageId)
            val totalSize = buffer.chunks.sumOf { it?.size ?: 0 }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunkData in buffer.chunks) {
                val cd = chunkData ?: return null
                for (i in cd.indices) result[offset + i] = cd[i]
                offset += cd.size
            }
            Log.d(TAG, "Reassembly complete for $messageId: ${buffer.totalChunks} chunks, $totalSize bytes")
            return result
        }

        return null
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = buffers.filter { now - it.value.createdAt > chunkTimeoutMs }
        for ((messageId, buffer) in expired) {
            buffers.remove(messageId)
            Log.w(TAG, "Timeout: discarded ${buffer.receivedCount}/${buffer.totalChunks} chunks for $messageId")
        }
    }

    fun pendingCount(): Int = buffers.size

    fun clear() {
        val count = buffers.size
        buffers.clear()
        if (count > 0) Log.i(TAG, "Cleared $count pending reassembly buffers")
    }

}

package com.messenger.crisix.transport

import org.junit.Assert.*
import org.junit.Test

class FragmenterTest {

    @Test
    fun `split small data fits in single chunk`() {
        val data = "Hello".toByteArray()
        val chunks = Fragmenter.split(data, maxChunkSize = 512)
        assertEquals(1, chunks.size)
        assertArrayEquals(data, chunks[0].data)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[0].totalChunks)
    }

    @Test
    fun `split large data into multiple chunks`() {
        val data = ByteArray(500) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        assertTrue(chunks.size > 1)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(chunks.size, chunks[0].totalChunks)

        for (chunk in chunks) {
            assertEquals(chunks.size, chunk.totalChunks)
            assertTrue(chunk.chunkIndex >= 0 && chunk.chunkIndex < chunks.size)
            assertArrayEquals(chunks[0].messageId, chunk.messageId)
        }

        val reassembled = ByteArray(data.size)
        var offset = 0
        for (chunk in chunks.sortedBy { it.chunkIndex }) {
            for (i in chunk.data.indices) reassembled[offset + i] = chunk.data[i]
            offset += chunk.data.size
        }
        assertArrayEquals(data, reassembled)
    }

    @Test
    fun `split exact boundary chunk size`() {
        val dataPerChunk = 100 - Fragmenter.HEADER_SIZE
        val data = ByteArray(dataPerChunk * 3) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        assertEquals(3, chunks.size)
        for (chunk in chunks) {
            assertEquals(dataPerChunk, chunk.data.size)
        }
    }

    @Test
    fun `split single byte data`() {
        val data = ByteArray(1) { 42 }
        val chunks = Fragmenter.split(data, maxChunkSize = 512)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].data.size)
        assertEquals(42.toByte(), chunks[0].data[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `split with too small maxChunkSize throws`() {
        val data = "test".toByteArray()
        Fragmenter.split(data, maxChunkSize = Fragmenter.MIN_CHUNK_SIZE - 1)
    }

    @Test
    fun `split empty data produces single empty chunk`() {
        val data = ByteArray(0)
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].data.size)
    }

    @Test
    fun `isChunk detects valid chunk bytes`() {
        val data = "Hello World".toByteArray()
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        val chunkBytes = chunks[0].toBytes()

        assertTrue(Fragmenter.isChunk(chunkBytes))
    }

    @Test
    fun `isChunk rejects non-chunk data`() {
        assertFalse(Fragmenter.isChunk("Hello".toByteArray()))
        assertFalse(Fragmenter.isChunk(ByteArray(0)))
        assertFalse(Fragmenter.isChunk(ByteArray(1) { Fragmenter.MAGIC_0 }))
    }

    @Test
    fun `isChunk accepts valid magic bytes even with wrong version`() {
        assertTrue(Fragmenter.isChunk(byteArrayOf(Fragmenter.MAGIC_0, Fragmenter.MAGIC_1, 0x02)))
    }

    @Test
    fun `Chunk toBytes and fromBytes roundtrip`() {
        val data = "Hello World Test Data".toByteArray()
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        for (chunk in chunks) {
            val bytes = chunk.toBytes()
            val parsed = Fragmenter.Chunk.fromBytes(bytes)
            assertNotNull(parsed)
            assertArrayEquals(chunk.messageId, parsed!!.messageId)
            assertEquals(chunk.totalChunks, parsed.totalChunks)
            assertEquals(chunk.chunkIndex, parsed.chunkIndex)
            assertArrayEquals(chunk.data, parsed.data)
        }
    }

    @Test
    fun `Chunk fromBytes returns null for truncated data`() {
        val data = "Hello".toByteArray()
        val chunks = Fragmenter.split(data, maxChunkSize = 100)
        val bytes = chunks[0].toBytes()
        val truncated = bytes.copyOf(Fragmenter.HEADER_SIZE - 1)
        assertNull(Fragmenter.Chunk.fromBytes(truncated))
    }

    @Test
    fun `split produces unique messageIds per call`() {
        val data = "test".toByteArray()
        val chunks1 = Fragmenter.split(data, maxChunkSize = 100)
        val chunks2 = Fragmenter.split(data, maxChunkSize = 100)
        assertFalse(chunks1[0].messageId.contentEquals(chunks2[0].messageId))
    }

    @Test
    fun `chunk header format has correct constants`() {
        assertEquals(0x43.toByte(), Fragmenter.MAGIC_0)
        assertEquals(0x58.toByte(), Fragmenter.MAGIC_1)
        assertEquals(0x01.toByte(), Fragmenter.VERSION)
        assertEquals(17, Fragmenter.HEADER_SIZE)
        assertEquals(18, Fragmenter.MIN_CHUNK_SIZE)
    }
}

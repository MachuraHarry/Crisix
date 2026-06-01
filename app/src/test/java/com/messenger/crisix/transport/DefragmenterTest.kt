package com.messenger.crisix.transport

import org.junit.Assert.*
import org.junit.Test

class DefragmenterTest {

    @Test
    fun `reassembly in order returns complete data`() {
        val defrag = Defragmenter()
        val data = ByteArray(500) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 100)

        var result: ByteArray? = null
        for (chunk in chunks.sortedBy { it.chunkIndex }) {
            result = defrag.addChunk(chunk)
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `reassembly out of order returns complete data`() {
        val defrag = Defragmenter()
        val data = ByteArray(500) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 100).reversed()

        var result: ByteArray? = null
        for (chunk in chunks) {
            val r = defrag.addChunk(chunk)
            if (r != null) result = r
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `duplicate chunk does not corrupt reassembly`() {
        val defrag = Defragmenter()
        val data = ByteArray(50) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 50)

        assertTrue(chunks.size >= 2)
        val r0 = defrag.addChunk(chunks[0])
        assertNull(r0)
        val rDup = defrag.addChunk(chunks[0])
        assertNull(rDup)
        val result = defrag.addChunk(chunks[1])
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `missing chunk returns null until all chunks received`() {
        val defrag = Defragmenter()
        val data = ByteArray(300) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 100)

        assertTrue(chunks.size > 3)

        assertNull(defrag.addChunk(chunks[0]))
        assertNull(defrag.addChunk(chunks[2]))
        assertNull(defrag.addChunk(chunks[1]))

        val lastChunk = chunks.last()
        val result = defrag.addChunk(lastChunk)
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `single chunk message returns immediately`() {
        val defrag = Defragmenter()
        val data = "Single".toByteArray()
        val chunks = Fragmenter.split(data, maxChunkSize = 512)

        assertEquals(1, chunks.size)
        val result = defrag.addChunk(chunks[0])
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `pending count reflects active reassemblies`() {
        val defrag = Defragmenter()
        assertEquals(0, defrag.pendingCount())

        val chunks1 = Fragmenter.split(ByteArray(200) { 1 }, maxChunkSize = 50)
        val chunks2 = Fragmenter.split(ByteArray(200) { 2 }, maxChunkSize = 50)

        assertTrue(chunks1.size >= 2)
        assertTrue(chunks2.size >= 2)

        defrag.addChunk(chunks1[0])
        assertEquals(1, defrag.pendingCount())

        defrag.addChunk(chunks2[0])
        assertEquals(2, defrag.pendingCount())

        for (i in 1 until chunks1.size) defrag.addChunk(chunks1[i])
        assertEquals(1, defrag.pendingCount())

        for (i in 1 until chunks2.size) defrag.addChunk(chunks2[i])
        assertEquals(0, defrag.pendingCount())
    }

    @Test
    fun `cleanupExpired removes timed out buffers`() {
        val defrag = Defragmenter(chunkTimeoutMs = 1)
        val data = ByteArray(200) { 0 }
        val chunks = Fragmenter.split(data, maxChunkSize = 100)

        defrag.addChunk(chunks[0])
        assertEquals(1, defrag.pendingCount())

        Thread.sleep(10)

        defrag.cleanupExpired()
        assertEquals(0, defrag.pendingCount())
    }

    @Test
    fun `clear removes all pending buffers`() {
        val defrag = Defragmenter()
        val data1 = ByteArray(200) { 1 }
        val data2 = ByteArray(200) { 2 }
        val chunks1 = Fragmenter.split(data1, maxChunkSize = 100)
        val chunks2 = Fragmenter.split(data2, maxChunkSize = 100)

        defrag.addChunk(chunks1[0])
        defrag.addChunk(chunks2[0])
        assertEquals(2, defrag.pendingCount())

        defrag.clear()
        assertEquals(0, defrag.pendingCount())
    }

    @Test
    fun `independent messages with different messageIds do not interfere`() {
        val defrag = Defragmenter()
        val data1 = ByteArray(200) { 0xA }
        val data2 = ByteArray(200) { 0xB }
        val chunks1 = Fragmenter.split(data1, maxChunkSize = 50)
        val chunks2 = Fragmenter.split(data2, maxChunkSize = 50)

        assertTrue(chunks1.size >= 2)
        assertTrue(chunks2.size >= 2)

        assertNull(defrag.addChunk(chunks1[0]))
        assertNull(defrag.addChunk(chunks2[0]))

        for (i in 1 until chunks1.size) defrag.addChunk(chunks1[i])
        for (i in 1 until chunks2.size) defrag.addChunk(chunks2[i])

        assertEquals(0, defrag.pendingCount())
    }

    @Test
    fun `mismatched totalChunks returns null`() {
        val defrag = Defragmenter()
        val chunks1 = Fragmenter.split(ByteArray(200) { 1 }, maxChunkSize = 100)
        val chunks2 = Fragmenter.split(ByteArray(300) { 2 }, maxChunkSize = 100)

        defrag.addChunk(chunks1[0])

        val fakeChunk = Fragmenter.Chunk(
            messageId = chunks1[0].messageId,
            totalChunks = chunks2[0].totalChunks,
            chunkIndex = 1,
            data = ByteArray(10)
        )
        assertNull(defrag.addChunk(fakeChunk))
    }

    @Test
    fun `large file reassembly works correctly`() {
        val defrag = Defragmenter()
        val data = ByteArray(10000) { it.toByte() }
        val chunks = Fragmenter.split(data, maxChunkSize = 512)

        var result: ByteArray? = null
        for (chunk in chunks) {
            val r = defrag.addChunk(chunk)
            if (r != null) result = r
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
        assertEquals(0, defrag.pendingCount())
    }
}

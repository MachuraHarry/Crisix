package com.messenger.crisix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LruHashMapTest {

    @Test
    fun `evicts eldest entry when over maxSize`() {
        val map = LruHashMap<String, Int>(maxSize = 3)
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        map["d"] = 4
        assertEquals(3, map.size)
        assertFalse(map.containsKey("a"))
        assertNotNull(map["b"])
    }

    @Test
    fun `access order moves entry to end`() {
        val map = LruHashMap<String, Int>(maxSize = 3)
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        @Suppress("UNUSED_VALUE")
        val a = map["a"]
        map["d"] = 4
        assertTrue(map.containsKey("a"))
        assertFalse(map.containsKey("b"))
    }

    @Test
    fun `lruMap helper returns thread-safe map`() {
        val map = lruMap<String, Boolean>(10)
        map["k"] = true
        assertTrue(map.containsKey("k"))
    }
}

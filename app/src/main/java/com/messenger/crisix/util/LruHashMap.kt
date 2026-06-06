package com.messenger.crisix.util

import java.util.Collections
import java.util.LinkedHashMap

class LruHashMap<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(1024, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
}

fun <K, V> lruMap(maxSize: Int): MutableMap<K, V> = Collections.synchronizedMap(LruHashMap<K, V>(maxSize))

package com.messenger.crisix.util

/**
 * Converts a ByteArray to its lowercase hexadecimal string representation.
 */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

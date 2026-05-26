package com.messenger.crisix.ui.screens

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-App-Logger, der Log-Nachrichten sammelt und im UI anzeigen kann.
 * Thread-safe durch synchronized.
 */
object InAppLogger {

    private val maxEntries = 500
    private val _logs = mutableListOf<LogEntry>()
    val logs: List<LogEntry> get() = synchronized(this) { _logs.toList() }

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val level: String,
        val message: String
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Loggt eine Nachricht und gibt sie auch an Logcat weiter.
     */
    fun d(tag: String, message: String) {
        addEntry("D", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        addEntry("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        addEntry("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        addEntry("E", tag, "$message${if (throwable != null) " - ${throwable.message}" else ""}")
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    private fun addEntry(level: String, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            level = level,
            message = message
        )
        synchronized(this) {
            _logs.add(entry)
            if (_logs.size > maxEntries) {
                _logs.removeAt(0)
            }
        }
    }

    fun clear() {
        synchronized(this) { _logs.clear() }
    }
}

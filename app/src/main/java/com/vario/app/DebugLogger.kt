package com.vario.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe in-memory circular log buffer for runtime diagnostics.
 * Retains the most recent entries (up to [MAX_ENTRIES]) with timestamps and log levels.
 */
object DebugLogger {

    private const val MAX_ENTRIES = 150

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private val lock = Any()
    private val buffer = Array<String?>(MAX_ENTRIES) { null }
    private var head = 0
    private var count = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Appends a new log entry to the circular buffer and emits to Android Logcat.
     */
    @JvmOverloads
    fun log(tag: String, message: String, level: Level = Level.INFO) {
        // Mirror to Android Logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }

        val timestamp = synchronized(timeFormat) {
            timeFormat.format(Date())
        }
        val formattedEntry = "$timestamp [${level.name}] $tag: $message"

        synchronized(lock) {
            buffer[head] = formattedEntry
            head = (head + 1) % MAX_ENTRIES
            if (count < MAX_ENTRIES) {
                count++
            }
        }
    }

    /**
     * Returns a snapshot of logged messages in chronological order (oldest to newest).
     */
    fun getLogs(): List<String> {
        synchronized(lock) {
            val list = ArrayList<String>(count)
            val start = if (count < MAX_ENTRIES) 0 else head
            for (i in 0 until count) {
                val index = (start + i) % MAX_ENTRIES
                buffer[index]?.let { list.add(it) }
            }
            return list
        }
    }

    /**
     * Clears all log entries from the buffer.
     */
    fun clear() {
        synchronized(lock) {
            buffer.fill(null)
            head = 0
            count = 0
        }
    }
}

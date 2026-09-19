package com.vario.app

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugLoggerTest {

    @BeforeTest
    fun setUp() {
        DebugLogger.clear()
    }

    @Test
    fun logAndGetLogs_returnsMessagesInOrder() {
        DebugLogger.log("TAG1", "Message 1", DebugLogger.Level.INFO)
        DebugLogger.log("TAG2", "Message 2", DebugLogger.Level.WARN)

        val logs = DebugLogger.getLogs()
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("[INFO] TAG1: Message 1"))
        assertTrue(logs[1].contains("[WARN] TAG2: Message 2"))
    }

    @Test
    fun clear_resetsLogBuffer() {
        DebugLogger.log("TAG", "Some message")
        assertEquals(1, DebugLogger.getLogs().size)

        DebugLogger.clear()
        assertEquals(0, DebugLogger.getLogs().size)
    }

    @Test
    fun circularBuffer_wrapsAroundAt150Entries() {
        // Add 160 entries
        for (i in 1..160) {
            DebugLogger.log("TAG", "Entry $i")
        }

        val logs = DebugLogger.getLogs()
        assertEquals(150, logs.size)

        // Oldest 10 entries (1..10) should have been evicted
        assertTrue(logs.first().contains("Entry 11"))
        assertTrue(logs.last().contains("Entry 160"))
    }

    @Test
    fun threadSafety_concurrentLoggingDoesNotCrashOrCorrupt() {
        val threads = (1..10).map { threadIdx ->
            Thread {
                for (i in 1..50) {
                    DebugLogger.log("Thread$threadIdx", "Step $i")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val logs = DebugLogger.getLogs()
        assertEquals(150, logs.size) // Capped at max entries
    }
}

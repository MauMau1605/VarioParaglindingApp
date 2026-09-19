package com.vario.app

/**
 * Zero-allocation, stream-oriented LK8EX1 sentence parser.
 *
 * Designed to run on the USB reader fast path. Operates strictly on primitives
 * without allocating any JVM objects on the heap.
 *
 * Sentence format:
 * `$LK8EX1,<pressure>,<altitude>,<vario>,<temp>,<batt>*<checksum>\r\n`
 *
 * Field definitions:
 * - Field 0: Pressure in Pa (integer)
 * - Field 1: Altitude in meters (or 99999 if unknown)
 * - Field 2: Vario in cm/s (signed integer)
 * - Field 3: Temperature in °C * 10 (ignored in V1)
 * - Field 4: Battery status (ignored in V1)
 */
class Lk8ex1Parser(
    private val listener: Listener
) {

    var requireStrictChecksum: Boolean = false

    /**
     * Callback interface triggered when a sentence is fully parsed.
     * When implemented by a service or handler, passing `this` incurs 0 heap allocation.
     */
    fun interface Listener {
        fun onSentenceComplete(pressurePa: Long, altitudeM: Long, varioCmS: Long)
    }

    companion object {
        /** Header bytes after '$' */
        private val LK8EX1_HEADER = byteArrayOf(
            'L'.code.toByte(), 'K'.code.toByte(), '8'.code.toByte(),
            'E'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte()
        )
        private const val MAX_RAW_LEN = 128
    }

    // ── Zero-allocation counters ─────────────────────────────────────────────
    var validFramesCount: Long = 0L
        private set
    var errorCount: Long = 0L
        private set

    // ── Pre-allocated raw sentence storage (zero-allocation on fast path) ────
    private val rawBuffer = ByteArray(MAX_RAW_LEN)
    private var rawBufferLen = 0
    private val lastCompletedBuffer = ByteArray(MAX_RAW_LEN)
    private var lastCompletedLen = 0

    private var parserState = State.WAIT_DOLLAR
    private var headerIndex = 0
    private var fieldIndex = 0
    private var fieldValue = 0L
    private var fieldNegative = false
    private var fieldHasSign = false
    private var fieldHasDigits = false
    private var fieldHasDecimal = false

    private var parsedPressure = 0L
    private var parsedAltitude = 0L
    private var parsedVarioCmS = 0L

    private var checksumXor = 0
    private var receivedChecksum = 0
    private var checksumDigitIndex = 0

    private enum class State {
        WAIT_DOLLAR,
        HEADER,
        FIELD,
        CHECKSUM
    }

    /**
     * Returns the most recently completed raw LK8EX1 sentence as a String.
     * Only call from the slow path (UI / diagnostics) to avoid GC on the audio thread.
     */
    fun getLastRawSentence(): String {
        return if (lastCompletedLen > 0) {
            String(lastCompletedBuffer, 0, lastCompletedLen, Charsets.US_ASCII)
        } else {
            ""
        }
    }

    /**
     * Feeds an array slice into the parser.
     */
    fun parseBytes(buffer: ByteArray, length: Int) {
        for (i in 0 until length) {
            parseByte(buffer[i])
        }
    }

    /**
     * Feeds a single byte into the state machine.
     */
    fun parseByte(b: Byte) {
        val c = b.toInt() and 0xFF

        // Append to raw buffer if space permits
        if (parserState != State.WAIT_DOLLAR && rawBufferLen < MAX_RAW_LEN) {
            rawBuffer[rawBufferLen++] = b
        }

        when (parserState) {
            State.WAIT_DOLLAR -> {
                if (c == '$'.code) {
                    parserState = State.HEADER
                    headerIndex = 0
                    checksumXor = 0
                    rawBufferLen = 0
                    rawBuffer[rawBufferLen++] = b
                }
            }

            State.HEADER -> {
                checksumXor = checksumXor xor c
                if (headerIndex < LK8EX1_HEADER.size) {
                    if (b == LK8EX1_HEADER[headerIndex]) {
                        headerIndex++
                    } else {
                        logError(c)
                        reset()
                    }
                } else if (c == ','.code) {
                    // "LK8EX1," fully matched
                    parserState = State.FIELD
                    fieldIndex = 0
                    fieldValue = 0L
                    fieldNegative = false
                    fieldHasSign = false
                    fieldHasDigits = false
                    fieldHasDecimal = false
                } else {
                    logError(c)
                    reset()
                }
            }

            State.FIELD -> {
                if (c != '*'.code) {
                    checksumXor = checksumXor xor c
                }

                when {
                    c == ','.code -> {
                        commitField()
                        fieldIndex++
                        fieldValue = 0L
                        fieldNegative = false
                        fieldHasSign = false
                        fieldHasDigits = false
                        fieldHasDecimal = false
                    }

                    c == '*'.code -> {
                        commitField()
                        parserState = State.CHECKSUM
                        checksumDigitIndex = 0
                        receivedChecksum = 0
                    }

                    c == '\r'.code || c == '\n'.code -> {
                        commitField()
                        completeSentence(checksumValid = true)
                        reset()
                    }

                    c == ' '.code || c == '\t'.code -> {
                        // Whitespace ignored in field values
                    }

                    (c == '+'.code || c == '-'.code) && !fieldHasSign && !fieldHasDigits && !fieldHasDecimal -> {
                        fieldHasSign = true
                        if (c == '-'.code) {
                            fieldNegative = true
                        }
                    }

                    c == '.'.code -> {
                        if (!fieldHasDecimal) {
                            fieldHasDecimal = true
                        } else {
                            logError(c)
                            reset()
                        }
                    }

                    c >= '0'.code && c <= '9'.code -> {
                        if (!fieldHasDecimal) {
                            fieldValue = fieldValue * 10L + (c - '0'.code)
                        }
                        fieldHasDigits = true
                    }

                    else -> {
                        // Unexpected character in field
                        logError(c)
                        reset()
                    }
                }
            }

            State.CHECKSUM -> {
                when {
                    c == ' '.code || c == '\t'.code -> {
                        // Whitespace ignored in checksum area
                    }

                    checksumDigitIndex < 2 && c >= '0'.code && c <= '9'.code -> {
                        receivedChecksum = (receivedChecksum shl 4) or (c - '0'.code)
                        checksumDigitIndex++
                    }

                    checksumDigitIndex < 2 && c >= 'A'.code && c <= 'F'.code -> {
                        receivedChecksum = (receivedChecksum shl 4) or (c - 'A'.code + 10)
                        checksumDigitIndex++
                    }

                    checksumDigitIndex < 2 && c >= 'a'.code && c <= 'f'.code -> {
                        receivedChecksum = (receivedChecksum shl 4) or (c - 'a'.code + 10)
                        checksumDigitIndex++
                    }

                    c == '\r'.code || c == '\n'.code -> {
                        val valid = (checksumDigitIndex == 2 && receivedChecksum == checksumXor)
                        if (!valid) {
                            logError(c)
                        }
                        if (valid || !requireStrictChecksum) {
                            completeSentence(checksumValid = valid)
                        }
                        reset()
                    }

                    else -> {
                        // Malformed checksum
                        logError(c)
                        reset()
                    }
                }
            }
        }
    }

    private fun logError(c: Int) {
        errorCount++
        DebugLogger.log(
            "Lk8ex1Parser",
            "Parser error in state $parserState at byte '${c.toChar()}' (0x${c.toString(16)})",
            DebugLogger.Level.WARN
        )
    }

    private fun commitField() {
        val signedValue = if (fieldNegative) -fieldValue else fieldValue
        when (fieldIndex) {
            0 -> parsedPressure = signedValue
            1 -> parsedAltitude = signedValue
            2 -> parsedVarioCmS = signedValue
        }
    }

    private fun completeSentence(checksumValid: Boolean) {
        if (checksumValid) {
            validFramesCount++
        }
        System.arraycopy(rawBuffer, 0, lastCompletedBuffer, 0, rawBufferLen)
        lastCompletedLen = rawBufferLen
        listener.onSentenceComplete(parsedPressure, parsedAltitude, parsedVarioCmS)
    }

    /**
     * Resets parser state to wait for next '$'.
     */
    fun reset() {
        parserState = State.WAIT_DOLLAR
        headerIndex = 0
        fieldIndex = 0
        fieldValue = 0L
        fieldNegative = false
        fieldHasSign = false
        fieldHasDigits = false
        fieldHasDecimal = false
        checksumXor = 0
        receivedChecksum = 0
        checksumDigitIndex = 0
        rawBufferLen = 0
    }
}

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
class Lk8ex1Parser(private val listener: Listener) {

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
    }

    private var parserState = State.WAIT_DOLLAR
    private var headerIndex = 0
    private var fieldIndex = 0
    private var fieldValue = 0L
    private var fieldNegative = false

    private var parsedPressure = 0L
    private var parsedAltitude = 0L
    private var parsedVarioCmS = 0L

    private enum class State {
        WAIT_DOLLAR,
        HEADER,
        FIELD
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

        when (parserState) {
            State.WAIT_DOLLAR -> {
                if (c == '$'.code) {
                    parserState = State.HEADER
                    headerIndex = 0
                }
            }

            State.HEADER -> {
                if (headerIndex < LK8EX1_HEADER.size) {
                    if (b == LK8EX1_HEADER[headerIndex]) {
                        headerIndex++
                    } else {
                        reset()
                    }
                } else if (c == ','.code) {
                    // "LK8EX1," fully matched
                    parserState = State.FIELD
                    fieldIndex = 0
                    fieldValue = 0L
                    fieldNegative = false
                } else {
                    reset()
                }
            }

            State.FIELD -> {
                when {
                    c == ','.code || c == '*'.code || c == '\r'.code || c == '\n'.code -> {
                        val signedValue = if (fieldNegative) -fieldValue else fieldValue
                        when (fieldIndex) {
                            0 -> parsedPressure = signedValue
                            1 -> parsedAltitude = signedValue
                            2 -> parsedVarioCmS = signedValue
                        }

                        if (c == '*'.code || c == '\r'.code || c == '\n'.code) {
                            listener.onSentenceComplete(parsedPressure, parsedAltitude, parsedVarioCmS)
                            reset()
                        } else {
                            fieldIndex++
                            fieldValue = 0L
                            fieldNegative = false
                        }
                    }

                    c == '-'.code && fieldValue == 0L -> {
                        fieldNegative = true
                    }

                    c in '0'.code..'9'.code -> {
                        fieldValue = fieldValue * 10L + (c - '0'.code)
                    }

                    // Any unexpected character ignored
                }
            }
        }
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
    }
}

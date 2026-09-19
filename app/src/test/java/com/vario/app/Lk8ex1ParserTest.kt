package com.vario.app

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lk8ex1ParserTest {

    private var sentenceCount = 0
    private var lastPressure = 0L
    private var lastAltitude = 0L
    private var lastVario = 0L

    private lateinit var parser: Lk8ex1Parser

    @BeforeTest
    fun setUp() {
        sentenceCount = 0
        lastPressure = 0L
        lastAltitude = 0L
        lastVario = 0L

        parser = Lk8ex1Parser { p, a, v ->
            sentenceCount++
            lastPressure = p
            lastAltitude = a
            lastVario = v
        }
    }

    @Test
    fun parseNominalSentence_extractsCorrectValues() {
        // $LK8EX1,pressure,altitude,vario,temp,batt*checksum\r\n
        val sentence = "\$LK8EX1,101325,99999,150,220,999,*13\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)

        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(101325L, lastPressure)
        assertEquals(99999L, lastAltitude)
        assertEquals(150L, lastVario) // +1.5 m/s in cm/s
    }

    @Test
    fun parseNegativeVario_extractsSignedValue() {
        val sentence = "\$LK8EX1,95000,540,-350,180,999,*1A\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)

        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(95000L, lastPressure)
        assertEquals(540L, lastAltitude)
        assertEquals(-350L, lastVario) // -3.5 m/s
    }

    @Test
    fun parseFragmentedPackets_reconstructsSentence() {
        // Simulate USB streaming where data arrives in small chunks of 4 bytes
        val sentence = "\$LK8EX1,100000,120,45,210,850,*00\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)

        val chunkSize = 4
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunkSize, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + length)
            parser.parseBytes(chunk, length)
            offset += length
        }

        assertEquals(1, sentenceCount)
        assertEquals(100000L, lastPressure)
        assertEquals(120L, lastAltitude)
        assertEquals(45L, lastVario)
    }

    @Test
    fun parseByteByByte_handlesStreamCorrectly() {
        val sentence = "\$LK8EX1,101325,99999,0,200,999,*40\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)

        for (b in bytes) {
            parser.parseByte(b)
        }

        assertEquals(1, sentenceCount)
        assertEquals(0L, lastVario)
        assertEquals(99999L, lastAltitude)
    }

    @Test
    fun parseMultipleSentencesConsecutively() {
        val stream = "\$LK8EX1,101325,99999,100,200,999,*00\r\n\$LK8EX1,101300,99999,200,200,999,*00\r\n"
        val bytes = stream.toByteArray(Charsets.US_ASCII)

        parser.parseBytes(bytes, bytes.size)

        assertEquals(2, sentenceCount)
        assertEquals(101300L, lastPressure)
        assertEquals(200L, lastVario)
    }

    @Test
    fun ignoresGarbageBeforeDollar() {
        val stream = "XYZ123 random noise \$LK8EX1,101325,99999,75,200,999,*00\r\n"
        val bytes = stream.toByteArray(Charsets.US_ASCII)

        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(75L, lastVario)
    }

    @Test
    fun resetAbortsCurrentSentence() {
        val partial = "\$LK8EX1,101325,999"
        val bytes = partial.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        parser.reset()

        val full = "\$LK8EX1,98000,300,120,200,999,*00\r\n"
        val fullBytes = full.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(fullBytes, fullBytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(98000L, lastPressure)
        assertEquals(300L, lastAltitude)
        assertEquals(120L, lastVario)
    }

    @Test
    fun parsingLoop_zeroAllocationsAcross10000Packets() {
        // LK8EX1 XOR checksum for 'LK8EX1,101325,99999,150,220,999,' is 0x13
        val sentence = "\$LK8EX1,101325,99999,150,220,999,*13\r\n".toByteArray(Charsets.US_ASCII)

        // Warm up JIT
        repeat(1_000) {
            parser.parseBytes(sentence, sentence.size)
        }

        System.gc()
        Thread.sleep(50)

        val beforeAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // 10,000 sentences
        for (i in 0 until 10_000) {
            parser.parseBytes(sentence, sentence.size)
        }

        val afterAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val delta = afterAllocated - beforeAllocated

        assertEquals(11000, sentenceCount)
        assertEquals(150L, lastVario)
        assertEquals(0L, parser.errorCount, "Valid sentences must not trigger errorCount")
        assertTrue(delta < 100_000, "Heap growth detected ($delta bytes) during parsing loop!")
    }

    @Test
    fun corruptedHeader_incrementsErrorCount() {
        val badSentence = "\$LK8BAD,101325,99999,150,220,999,*00\r\n".toByteArray(Charsets.US_ASCII)
        val initialErrors = parser.errorCount
        parser.parseBytes(badSentence, badSentence.size)
        assertTrue(parser.errorCount > initialErrors, "Corrupted header should increment errorCount")
    }

    @Test
    fun rawSentence_capturedCorrectly() {
        val sentenceStr = "\$LK8EX1,101325,99999,150,220,999,*3F\r\n"
        val bytes = sentenceStr.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        val captured = parser.getLastRawSentence()
        assertTrue(captured.startsWith("\$LK8EX1,101325,99999,150,220,999"))
    }

    @Test
    fun parsePositiveSignVario_extractsPositiveValue() {
        val sentence = "\$LK8EX1,101325,99999,+120,220,999,*00\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(101325L, lastPressure)
        assertEquals(99999L, lastAltitude)
        assertEquals(120L, lastVario)
    }

    @Test
    fun parseLeadingAndTrailingWhitespace_extractsCorrectValues() {
        val sentence = "\$LK8EX1,  101325 , 99999 ,  +150 , 220 , 999 ,*00\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(101325L, lastPressure)
        assertEquals(99999L, lastAltitude)
        assertEquals(150L, lastVario)
    }

    @Test
    fun parseDecimalPressureAndAltitude_extractsIntegerPortion() {
        val sentence = "\$LK8EX1,1013.25,540.80,+215,22.5,999,*00\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(1013L, lastPressure)
        assertEquals(540L, lastAltitude)
        assertEquals(215L, lastVario)
    }

    @Test
    fun parseNegativeWithDecimals_extractsSignedInteger() {
        val sentence = "\$LK8EX1,95000,540.2,-350.5,180,999,*00\r\n"
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(95000L, lastPressure)
        assertEquals(540L, lastAltitude)
        assertEquals(-350L, lastVario)
    }

    @Test
    fun parseCombinedSpacesDecimalsAndSigns_withStrictChecksum() {
        // Calculate XOR checksum for string: LK8EX1, 1013.25, 99999, +120, 220, 999
        val payload = "LK8EX1, 1013.25, 99999, +120, 220, 999"
        var xor = 0
        for (c in payload) {
            xor = xor xor c.code
        }
        val hex = String.format("%02X", xor)
        val sentence = "\$$payload*$hex\r\n"

        parser.requireStrictChecksum = true
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        parser.parseBytes(bytes, bytes.size)

        assertEquals(1, sentenceCount)
        assertEquals(1013L, lastPressure)
        assertEquals(99999L, lastAltitude)
        assertEquals(120L, lastVario)
        assertEquals(1L, parser.validFramesCount)
        assertEquals(0L, parser.errorCount)
    }

    @Test
    fun duplicateDecimalPoint_triggersErrorCount() {
        val initialErrors = parser.errorCount
        val badSentence = "\$LK8EX1,10.13.25,99999,150,220,999,*00\r\n".toByteArray(Charsets.US_ASCII)
        parser.parseBytes(badSentence, badSentence.size)
        assertTrue(parser.errorCount > initialErrors, "Duplicate decimal points should trigger errorCount")
    }

    @Test
    fun parserError_emitsDiagnosticLog() {
        DebugLogger.clear()
        val badSentence = "\$LK8BAD,101325,99999,150,220,999,*00\r\n".toByteArray(Charsets.US_ASCII)
        parser.parseBytes(badSentence, badSentence.size)

        val logs = DebugLogger.getLogs()
        assertTrue(logs.any { it.contains("Lk8ex1Parser: Parser error in state HEADER at byte 'B' (0x42)") })
    }
}


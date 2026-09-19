package com.vario.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserLogReplayTest {

    private val userSentences = listOf(
        SentenceEntry(94997L, 540.7f, 255L, 2.55f, "\$LK8EX1,94997,99999,255,22,999,*1B\r\n"),
        SentenceEntry(94994L, 540.9f, 269L, 2.69f, "\$LK8EX1,94994,99999,269,22,999,*17\r\n"),
        SentenceEntry(94990L, 541.3f, 282L, 2.82f, "\$LK8EX1,94990,99999,282,22,999,*16\r\n"),
        SentenceEntry(94987L, 541.6f, 295L, 2.95f, "\$LK8EX1,94987,99999,295,22,999,*16\r\n"),
        SentenceEntry(94983L, 541.9f, 306L, 3.06f, "\$LK8EX1,94983,99999,306,22,999,*19\r\n"),
        SentenceEntry(94980L, 542.2f, 315L, 3.15f, "\$LK8EX1,94980,99999,315,22,999,*18\r\n"),
        SentenceEntry(94976L, 542.5f, 323L, 3.23f, "\$LK8EX1,94976,99999,323,22,999,*14\r\n"),
        SentenceEntry(94972L, 542.9f, 330L, 3.30f, "\$LK8EX1,94972,99999,330,22,999,*12\r\n"),
        SentenceEntry(94968L, 543.2f, 335L, 3.35f, "\$LK8EX1,94968,99999,335,22,999,*1C\r\n"),
        SentenceEntry(94964L, 543.6f, 338L, 3.38f, "\$LK8EX1,94964,99999,338,22,999,*1D\r\n"),
        SentenceEntry(94960L, 543.9f, 340L, 3.40f, "\$LK8EX1,94960,99999,340,22,999,*16\r\n"),
        SentenceEntry(94956L, 544.3f, 341L, 3.41f, "\$LK8EX1,94956,99999,341,22,999,*12\r\n"),
        SentenceEntry(94952L, 544.6f, 341L, 3.41f, "\$LK8EX1,94952,99999,341,22,999,*16\r\n"),
        SentenceEntry(94948L, 545.0f, 341L, 3.41f, "\$LK8EX1,94948,99999,341,22,999,*1D\r\n"),
        SentenceEntry(94944L, 545.3f, 340L, 3.40f, "\$LK8EX1,94944,99999,340,22,999,*10\r\n"),
        SentenceEntry(94940L, 545.7f, 340L, 3.40f, "\$LK8EX1,94940,99999,340,22,999,*14\r\n"),
        SentenceEntry(94936L, 546.0f, 339L, 3.39f, "\$LK8EX1,94936,99999,339,22,999,*1B\r\n"),
        SentenceEntry(94932L, 546.4f, 339L, 3.39f, "\$LK8EX1,94932,99999,339,22,999,*1F\r\n"),
        SentenceEntry(94928L, 546.7f, 340L, 3.40f, "\$LK8EX1,94928,99999,340,22,999,*1A\r\n"),
        SentenceEntry(94924L, 547.1f, 341L, 3.41f, "\$LK8EX1,94924,99999,341,22,999,*17\r\n"),
        SentenceEntry(94920L, 547.4f, 342L, 3.42f, "\$LK8EX1,94920,99999,342,22,999,*10\r\n"),
        SentenceEntry(94916L, 547.8f, 344L, 3.44f, "\$LK8EX1,94916,99999,344,22,999,*13\r\n"),
        SentenceEntry(94912L, 548.1f, 346L, 3.46f, "\$LK8EX1,94912,99999,346,22,999,*15\r\n"),
        SentenceEntry(94908L, 548.5f, 347L, 3.47f, "\$LK8EX1,94908,99999,347,22,999,*1F\r\n"),
        SentenceEntry(94904L, 548.8f, 348L, 3.48f, "\$LK8EX1,94904,99999,348,22,999,*1C\r\n"),
        SentenceEntry(94899L, 549.3f, 349L, 3.49f, "\$LK8EX1,94899,99999,349,22,999,*18\r\n"),
        SentenceEntry(94895L, 549.6f, 349L, 3.49f, "\$LK8EX1,94895,99999,349,22,999,*14\r\n"),
        SentenceEntry(94891L, 550.0f, 347L, 3.47f, "\$LK8EX1,94891,99999,347,22,999,*1E\r\n"),
        SentenceEntry(94887L, 550.3f, 344L, 3.44f, "\$LK8EX1,94887,99999,344,22,999,*1A\r\n"),
        SentenceEntry(94883L, 550.7f, 340L, 3.40f, "\$LK8EX1,94883,99999,340,22,999,*1A\r\n"),
        SentenceEntry(94879L, 551.0f, 334L, 3.34f, "\$LK8EX1,94879,99999,334,22,999,*1C\r\n"),
        SentenceEntry(94875L, 551.4f, 326L, 3.26f, "\$LK8EX1,94875,99999,326,22,999,*13\r\n"),
        SentenceEntry(94872L, 551.7f, 316L, 3.16f, "\$LK8EX1,94872,99999,316,22,999,*17\r\n"),
        SentenceEntry(94868L, 552.0f, 305L, 3.05f, "\$LK8EX1,94868,99999,305,22,999,*1E\r\n"),
        SentenceEntry(94865L, 552.3f, 293L, 2.93f, "\$LK8EX1,94865,99999,293,22,999,*1D\r\n"),
        SentenceEntry(94861L, 552.6f, 279L, 2.79f, "\$LK8EX1,94861,99999,279,22,999,*1D\r\n"),
        SentenceEntry(94858L, 552.9f, 265L, 2.65f, "\$LK8EX1,94858,99999,265,22,999,*1A\r\n"),
        SentenceEntry(94855L, 553.1f, 251L, 2.51f, "\$LK8EX1,94855,99999,251,22,999,*10\r\n"),
        SentenceEntry(94852L, 553.4f, 236L, 2.36f, "\$LK8EX1,94852,99999,236,22,999,*16\r\n"),
        SentenceEntry(94850L, 553.6f, 222L, 2.22f, "\$LK8EX1,94850,99999,222,22,999,*11\r\n"),
        SentenceEntry(94847L, 553.8f, 209L, 2.09f, "\$LK8EX1,94847,99999,209,22,999,*1E\r\n"),
        SentenceEntry(94845L, 554.0f, 196L, 1.96f, "\$LK8EX1,94845,99999,196,22,999,*19\r\n"),
        SentenceEntry(94843L, 554.2f, 185L, 1.85f, "\$LK8EX1,94843,99999,185,22,999,*1D\r\n"),
        SentenceEntry(94841L, 554.4f, 176L, 1.76f, "\$LK8EX1,94841,99999,176,22,999,*13\r\n"),
        SentenceEntry(94839L, 554.5f, 168L, 1.68f, "\$LK8EX1,94839,99999,168,22,999,*13\r\n"),
        SentenceEntry(94837L, 554.7f, 161L, 1.61f, "\$LK8EX1,94837,99999,161,22,999,*14\r\n"),
        SentenceEntry(94835L, 554.9f, 156L, 1.56f, "\$LK8EX1,94835,99999,156,22,999,*12\r\n"),
        SentenceEntry(94833L, 555.1f, 152L, 1.52f, "\$LK8EX1,94833,99999,152,22,999,*10\r\n"),
        SentenceEntry(94831L, 555.3f, 150L, 1.50f, "\$LK8EX1,94831,99999,150,22,999,*10\r\n"),
        SentenceEntry(94830L, 555.3f, 148L, 1.48f, "\$LK8EX1,94830,99999,148,22,999,*18\r\n"),
        SentenceEntry(94828L, 555.5f, 147L, 1.47f, "\$LK8EX1,94828,99999,147,22,999,*1E\r\n"),
        SentenceEntry(94826L, 555.7f, 146L, 1.46f, "\$LK8EX1,94826,99999,146,22,999,*11\r\n"),
        SentenceEntry(94825L, 555.8f, 146L, 1.46f, "\$LK8EX1,94825,99999,146,22,999,*12\r\n"),
        SentenceEntry(94823L, 556.0f, 145L, 1.45f, "\$LK8EX1,94823,99999,145,22,999,*17\r\n"),
        SentenceEntry(94821L, 556.1f, 144L, 1.44f, "\$LK8EX1,94821,99999,144,22,999,*14\r\n"),
        SentenceEntry(94819L, 556.3f, 143L, 1.43f, "\$LK8EX1,94819,99999,143,22,999,*18\r\n"),
        SentenceEntry(94818L, 556.4f, 141L, 1.41f, "\$LK8EX1,94818,99999,141,22,999,*1B\r\n"),
        SentenceEntry(94816L, 556.6f, 139L, 1.39f, "\$LK8EX1,94816,99999,139,22,999,*1A\r\n"),
        SentenceEntry(94814L, 556.7f, 136L, 1.36f, "\$LK8EX1,94814,99999,136,22,999,*17\r\n"),
        SentenceEntry(94813L, 556.8f, 133L, 1.33f, "\$LK8EX1,94813,99999,133,22,999,*15\r\n"),
        SentenceEntry(94811L, 557.0f, 131L, 1.31f, "\$LK8EX1,94811,99999,131,22,999,*15\r\n"),
        SentenceEntry(94810L, 557.1f, 128L, 1.28f, "\$LK8EX1,94810,99999,128,22,999,*1C\r\n"),
        SentenceEntry(94808L, 557.3f, 126L, 1.26f, "\$LK8EX1,94808,99999,126,22,999,*1B\r\n"),
        SentenceEntry(94807L, 557.4f, 124L, 1.24f, "\$LK8EX1,94807,99999,124,22,999,*16\r\n"),
        SentenceEntry(94805L, 557.5f, 124L, 1.24f, "\$LK8EX1,94805,99999,124,22,999,*14\r\n"),
        SentenceEntry(94804L, 557.6f, 125L, 1.25f, "\$LK8EX1,94804,99999,125,22,999,*14\r\n"),
        SentenceEntry(94802L, 557.8f, 127L, 1.27f, "\$LK8EX1,94802,99999,127,22,999,*10\r\n"),
        SentenceEntry(94801L, 557.9f, 131L, 1.31f, "\$LK8EX1,94801,99999,131,22,999,*14\r\n"),
        SentenceEntry(94799L, 558.1f, 137L, 1.37f, "\$LK8EX1,94799,99999,137,22,999,*1C\r\n"),
        SentenceEntry(94798L, 558.2f, 145L, 1.45f, "\$LK8EX1,94798,99999,145,22,999,*18\r\n"),
        SentenceEntry(94796L, 558.3f, 154L, 1.54f, "\$LK8EX1,94796,99999,154,22,999,*16\r\n"),
        SentenceEntry(94794L, 558.5f, 165L, 1.65f, "\$LK8EX1,94794,99999,165,22,999,*16\r\n"),
        SentenceEntry(94792L, 558.7f, 177L, 1.77f, "\$LK8EX1,94792,99999,177,22,999,*13\r\n"),
        SentenceEntry(94790L, 558.9f, 191L, 1.91f, "\$LK8EX1,94790,99999,191,22,999,*19\r\n"),
        SentenceEntry(94787L, 559.1f, 204L, 2.04f, "\$LK8EX1,94787,99999,204,22,999,*10\r\n"),
        SentenceEntry(94785L, 559.3f, 219L, 2.19f, "\$LK8EX1,94785,99999,219,22,999,*1E\r\n"),
        SentenceEntry(94782L, 559.6f, 233L, 2.33f, "\$LK8EX1,94782,99999,233,22,999,*11\r\n"),
        SentenceEntry(94779L, 559.8f, 247L, 2.47f, "\$LK8EX1,94779,99999,247,22,999,*16\r\n"),
        SentenceEntry(94776L, 560.1f, 260L, 2.60f, "\$LK8EX1,94776,99999,260,22,999,*1C\r\n"),
        SentenceEntry(94773L, 560.3f, 272L, 2.72f, "\$LK8EX1,94773,99999,272,22,999,*1A\r\n"),
        SentenceEntry(94769L, 560.7f, 283L, 2.83f, "\$LK8EX1,94769,99999,283,22,999,*1F\r\n"),
        SentenceEntry(94766L, 561.0f, 293L, 2.93f, "\$LK8EX1,94766,99999,293,22,999,*11\r\n"),
        SentenceEntry(94762L, 561.3f, 301L, 3.01f, "\$LK8EX1,94762,99999,301,22,999,*1F\r\n"),
        SentenceEntry(94759L, 561.6f, 307L, 3.07f, "\$LK8EX1,94759,99999,307,22,999,*11\r\n"),
        SentenceEntry(94755L, 561.9f, 313L, 3.13f, "\$LK8EX1,94755,99999,313,22,999,*18\r\n"),
        SentenceEntry(94751L, 562.3f, 317L, 3.17f, "\$LK8EX1,94751,99999,317,22,999,*18\r\n"),
        SentenceEntry(94747L, 562.6f, 320L, 3.20f, "\$LK8EX1,94747,99999,320,22,999,*1B\r\n"),
        SentenceEntry(94744L, 562.9f, 322L, 3.22f, "\$LK8EX1,94744,99999,322,22,999,*1A\r\n"),
        SentenceEntry(94740L, 563.2f, 324L, 3.24f, "\$LK8EX1,94740,99999,324,22,999,*18\r\n"),
        SentenceEntry(94736L, 563.6f, 325L, 3.25f, "\$LK8EX1,94736,99999,325,22,999,*18\r\n"),
        SentenceEntry(94732L, 564.0f, 327L, 3.27f, "\$LK8EX1,94732,99999,327,22,999,*1E\r\n"),
        SentenceEntry(94728L, 564.3f, 329L, 3.29f, "\$LK8EX1,94728,99999,329,22,999,*1B\r\n"),
        SentenceEntry(94724L, 564.7f, 331L, 3.31f, "\$LK8EX1,94724,99999,331,22,999,*1E\r\n"),
        SentenceEntry(94720L, 565.0f, 333L, 3.33f, "\$LK8EX1,94720,99999,333,22,999,*18\r\n"),
        SentenceEntry(94716L, 565.4f, 337L, 3.37f, "\$LK8EX1,94716,99999,337,22,999,*19\r\n"),
        SentenceEntry(94712L, 565.7f, 340L, 3.40f, "\$LK8EX1,94712,99999,340,22,999,*1D\r\n"),
        SentenceEntry(94708L, 566.1f, 344L, 3.44f, "\$LK8EX1,94708,99999,344,22,999,*12\r\n"),
        SentenceEntry(94704L, 566.4f, 348L, 3.48f, "\$LK8EX1,94704,99999,348,22,999,*12\r\n"),
        SentenceEntry(94700L, 566.8f, 352L, 3.52f, "\$LK8EX1,94700,99999,352,22,999,*1D\r\n"),
        SentenceEntry(94696L, 567.1f, 356L, 3.56f, "\$LK8EX1,94696,99999,356,22,999,*17\r\n"),
        SentenceEntry(94692L, 567.5f, 359L, 3.59f, "\$LK8EX1,94692,99999,359,22,999,*1C\r\n"),
        SentenceEntry(94687L, 567.9f, 362L, 3.62f, "\$LK8EX1,94687,99999,362,22,999,*10\r\n"),
        SentenceEntry(94683L, 568.3f, 363L, 3.63f, "\$LK8EX1,94683,99999,363,22,999,*15\r\n"),
        SentenceEntry(94679L, 568.6f, 362L, 3.62f, "\$LK8EX1,94679,99999,362,22,999,*11\r\n"),
        SentenceEntry(94675L, 569.0f, 361L, 3.61f, "\$LK8EX1,94675,99999,361,22,999,*1E\r\n"),
        SentenceEntry(94670L, 569.4f, 357L, 3.57f, "\$LK8EX1,94670,99999,357,22,999,*1E\r\n"),
        SentenceEntry(94666L, 569.8f, 351L, 3.51f, "\$LK8EX1,94666,99999,351,22,999,*1F\r\n"),
        SentenceEntry(94662L, 570.1f, 344L, 3.44f, "\$LK8EX1,94662,99999,344,22,999,*1F\r\n")
    )

    data class SentenceEntry(
        val expectedPressurePa: Long,
        val expectedAltitudeM: Float,
        val expectedVarioCmS: Long,
        val expectedVarioMs: Float,
        val rawSentence: String
    )

    @Test
    fun testReplayUserLogThroughParser() {
        var parsedCount = 0
        var lastPressure = 0L
        var lastAltitude = 0L
        var lastVario = 0L

        val parser = Lk8ex1Parser { p, a, v ->
            parsedCount++
            lastPressure = p
            lastAltitude = a
            lastVario = v
        }
        parser.requireStrictChecksum = true

        val qnh = 101325.0

        for (entry in userSentences) {
            val bytes = entry.rawSentence.toByteArray(Charsets.US_ASCII)
            parser.parseBytes(bytes, bytes.size)

            assertEquals(entry.expectedPressurePa, lastPressure)
            assertEquals(99999L, lastAltitude)
            assertEquals(entry.expectedVarioCmS, lastVario)

            // Verify Vz in m/s
            val vz = lastVario / 100f
            assertEquals(entry.expectedVarioMs, vz, 0.001f)

            // Verify baro altitude calculation
            val calculatedAlt = VarioMath.pressureToAltitude(lastPressure, qnh)
            // Allow 0.2m tolerance due to rounding in user log
            assertTrue(
                kotlin.math.abs(calculatedAlt - entry.expectedAltitudeM) < 0.2f,
                "Expected altitude ~${entry.expectedAltitudeM}m for P=${lastPressure}Pa, got ${calculatedAlt}m"
            )

            // Test arbitration logic
            val arbitratedAlt = VarioService.arbitrateAltitude(
                pressurePa = lastPressure,
                rawAltitudeM = lastAltitude,
                currentQnhPa = qnh,
                availableGpsAlt = null,
                previousAltitudeM = 0f
            )
            assertTrue(
                kotlin.math.abs(arbitratedAlt - entry.expectedAltitudeM) < 0.2f,
                "Arbitrated altitude should match calculated altitude"
            )
        }

        assertEquals(userSentences.size, parsedCount)
        assertEquals(userSentences.size.toLong(), parser.validFramesCount)
        assertEquals(0L, parser.errorCount)
    }

    @Test
    fun testReplayAsContinuousByteStream() {
        // Concatenate all sentences into one continuous byte stream (as received over USB)
        val allBytes = userSentences.joinToString(separator = "") { it.rawSentence }.toByteArray(Charsets.US_ASCII)

        var count = 0
        val parser = Lk8ex1Parser { _, _, _ ->
            count++
        }
        parser.requireStrictChecksum = true

        // Feed in 64-byte chunks like real USB CDC packets
        var offset = 0
        while (offset < allBytes.size) {
            val chunkSize = minOf(64, allBytes.size - offset)
            val chunk = allBytes.copyOfRange(offset, offset + chunkSize)
            parser.parseBytes(chunk, chunkSize)
            offset += chunkSize
        }

        assertEquals(userSentences.size, count)
        assertEquals(userSentences.size.toLong(), parser.validFramesCount)
        assertEquals(0L, parser.errorCount)
    }
}

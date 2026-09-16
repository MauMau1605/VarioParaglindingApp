package com.vario.app

import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VarioAudioEngineTest {

    private lateinit var engine: VarioAudioEngine
    private val testBuffer = ShortArray(1024)

    @BeforeTest
    fun setUp() {
        engine = VarioAudioEngine()
        engine.resetPhaseAndBeep()
    }

    @Test
    fun deadband_fillsBufferWithPureSilence() {
        // Test various Vz values within deadband [-2.0, +0.3]
        val deadbandValues = floatArrayOf(0.0f, 0.29f, -1.99f, -0.5f)

        for (vz in deadbandValues) {
            testBuffer.fill(42.toShort()) // pre-fill with non-zero
            engine.generateBuffer(vz, testBuffer)

            for (sample in testBuffer) {
                assertEquals(0, sample.toInt())
            }
        }
    }

    @Test
    fun climbTone_generatesValidPcmAndAlternatingSilence() {
        engine.resetPhaseAndBeep()

        // Large buffer to capture at least one beep period
        val largeBuffer = ShortArray(16000)
        engine.generateBuffer(2.0f, largeBuffer)

        var hasNonZero = false
        var hasZero = false
        var maxAmplitude = 0

        for (sample in largeBuffer) {
            val sampleInt = sample.toInt()
            val amp = abs(sampleInt)
            if (amp > maxAmplitude) maxAmplitude = amp

            if (sampleInt != 0) hasNonZero = true
            if (sampleInt == 0) hasZero = true
        }

        // Climb tone MUST have sound
        assertTrue(hasNonZero, "Climb tone should generate sound")
        // Climb tone MUST have beeping silence intervals (duty cycle)
        assertTrue(hasZero, "Climb tone should have silent intervals")
        // Amplitude must not exceed the specified maximum
        assertTrue(maxAmplitude <= VarioAudioEngine.AMPLITUDE, "Amplitude $maxAmplitude exceeds max")
        assertTrue(maxAmplitude > 10000, "Amplitude $maxAmplitude too low")
    }

    @Test
    fun sinkTone_generatesContinuousToneWithoutDutyCycleHoles() {
        engine.resetPhaseAndBeep()

        // A sink buffer of 500 samples
        val sinkBuffer = ShortArray(500)
        engine.generateBuffer(-3.0f, sinkBuffer)

        var maxAmplitude = 0
        var zeroCount = 0

        for (sample in sinkBuffer) {
            val amp = abs(sample.toInt())
            if (amp > maxAmplitude) maxAmplitude = amp
            if (sample.toInt() == 0) zeroCount++
        }

        // Must produce sound
        assertTrue(maxAmplitude > 10000, "Sink amplitude too low")
        assertTrue(maxAmplitude <= VarioAudioEngine.AMPLITUDE, "Sink amplitude exceeds max")

        // Only zero-crossings can be 0, not long silence intervals like in beeps
        assertTrue(zeroCount < 50, "Sink tone should not have long silence intervals")
    }

    @Test
    fun currentVz_volatilePropertyUpdatesCleanly() {
        engine.currentVz = 1.8f
        assertEquals(1.8f, engine.currentVz)

        engine.currentVz = -4.2f
        assertEquals(-4.2f, engine.currentVz)
    }

    @Test
    fun synthesisLoop_zeroAllocationsAcross10000Cycles() {
        // JIT warm-up
        repeat(1_000) {
            engine.generateBuffer(1.5f, testBuffer)
        }

        System.gc()
        Thread.sleep(50)

        val beforeAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // 10,000 audio synthesis cycles
        for (i in 0 until 10_000) {
            val vz = if (i % 2 == 0) 2.5f else -3.0f
            engine.generateBuffer(vz, testBuffer)
        }

        val afterAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val delta = afterAllocated - beforeAllocated

        assertTrue(delta < 100_000, "Heap growth detected ($delta bytes) during synthesis loop!")
    }
}

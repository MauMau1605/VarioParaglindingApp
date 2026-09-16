package com.vario.app

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VarioMathTest {

    private fun assertWithin(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "Expected $expected within $tolerance of $actual"
        )
    }

    private fun assertWithin(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "Expected $expected within $tolerance of $actual"
        )
    }

    @Test
    fun pressureToAltitude_standardAtmosphere_returnsAccurateElevations() {
        // Sea level
        val seaLevelAlt = VarioMath.pressureToAltitude(101325L)
        assertWithin(0.0f, seaLevelAlt, 0.1f)

        // ~1000m standard pressure is approx 89874 Pa
        val alt1000m = VarioMath.pressureToAltitude(89874L)
        assertWithin(1000.0f, alt1000m, 5.0f)

        // ~2000m standard pressure is approx 79495 Pa
        val alt2000m = VarioMath.pressureToAltitude(79495L)
        assertWithin(2000.0f, alt2000m, 5.0f)

        // High altitude: 55000 Pa is approx 4865m in ISA
        val altHigh = VarioMath.pressureToAltitude(55000L)
        assertWithin(4865.0f, altHigh, 10.0f)
    }

    @Test
    fun pressureToAltitude_zeroOrNegative_returnsZero() {
        assertEquals(0f, VarioMath.pressureToAltitude(0L))
        assertEquals(0f, VarioMath.pressureToAltitude(-500L))
    }

    @Test
    fun climbFrequency_scalesFrom400To1200Hz() {
        // At climb threshold (+0.3 m/s)
        assertWithin(400.0, VarioMath.climbFrequency(0.3f), 0.01)

        // At max climb (+5.0 m/s)
        assertWithin(1200.0, VarioMath.climbFrequency(5.0f), 0.01)

        // Mid-point: (0.3 + 5.0) / 2 = 2.65 m/s -> (400 + 1200) / 2 = 800 Hz
        assertWithin(800.0, VarioMath.climbFrequency(2.65f), 0.5)

        // Clamped outside bounds
        assertEquals(400.0, VarioMath.climbFrequency(0.0f))
        assertEquals(1200.0, VarioMath.climbFrequency(10.0f))
    }

    @Test
    fun climbPeriodSamples_scalesFrom10667To2667Samples() {
        // Low climb rate -> long period (~1.5 Hz beep)
        assertEquals(10667, VarioMath.climbPeriodSamples(0.3f))

        // High climb rate -> short period (~6 Hz beep)
        assertEquals(2667, VarioMath.climbPeriodSamples(5.0f))

        // Clamped
        assertEquals(10667, VarioMath.climbPeriodSamples(0.1f))
        assertEquals(2667, VarioMath.climbPeriodSamples(8.0f))
    }

    @Test
    fun climbDutyCycle_scalesFrom40PercentTo85Percent() {
        assertWithin(0.40, VarioMath.climbDutyCycle(0.3f), 0.001)
        assertWithin(0.85, VarioMath.climbDutyCycle(5.0f), 0.001)

        // Clamped
        assertWithin(0.40, VarioMath.climbDutyCycle(-1.0f), 0.001)
        assertWithin(0.85, VarioMath.climbDutyCycle(10.0f), 0.001)
    }

    @Test
    fun sinkFrequency_scalesFrom400DownTo200Hz() {
        // At sink threshold (-2.0 m/s)
        assertWithin(400.0, VarioMath.sinkFrequency(-2.0f), 0.01)

        // At max sink (-8.0 m/s)
        assertWithin(200.0, VarioMath.sinkFrequency(-8.0f), 0.01)

        // Mid-point: -5.0 m/s -> 300 Hz
        assertWithin(300.0, VarioMath.sinkFrequency(-5.0f), 0.5)

        // Clamped outside bounds
        assertEquals(400.0, VarioMath.sinkFrequency(0.0f))
        assertEquals(200.0, VarioMath.sinkFrequency(-15.0f))
    }
}

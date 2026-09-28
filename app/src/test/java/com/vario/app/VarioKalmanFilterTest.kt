package com.vario.app

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VarioKalmanFilterTest {

    private lateinit var filter: VarioKalmanFilter

    @BeforeTest
    fun setUp() {
        filter = VarioKalmanFilter(FilterPreset.BALANCED)
        filter.reset(500.0f)
    }

    @Test
    fun stationaryNoise_isDampedAndGatedToZero() {
        // High-frequency barometric sensor noise alternating around 500m (±0.12m jitter @ 10 Hz)
        // Without Kalman filtering, raw differentiation yields ±2.4 m/s!
        var maxGatedVzAfterSettling = 0.0f
        for (i in 0 until 100) {
            val noise = if (i % 2 == 0) 0.12f else -0.12f
            filter.predict(0.1f)
            filter.updateBaroAltitude(500.0f + noise)

            // Allow initial 1-second settling period
            if (i >= 10) {
                val gated = abs(filter.getGatedVz())
                if (gated > maxGatedVzAfterSettling) {
                    maxGatedVzAfterSettling = gated
                }
            }
        }

        assertEquals(0.0f, maxGatedVzAfterSettling, "Motionless sensor with pressure noise should be gated to 0.0 m/s after settling")
    }

    @Test
    fun stationaryWithNoisyNativeVario_isDampedAndGatedToZero() {
        // High-frequency native vario jitter (±0.08 m/s = ±8 cm/s) around 0 m/s at rest
        var maxGatedVzAfterSettling = 0.0f
        for (i in 0 until 100) {
            val noiseAlt = if (i % 2 == 0) 0.05f else -0.05f
            val noisyVario = if (i % 2 == 0) 0.08f else -0.08f

            filter.predict(0.1f)
            filter.updateAltitudeAndVario(500.0f + noiseAlt, noisyVario)

            if (i >= 10) {
                val gated = abs(filter.getGatedVz())
                if (gated > maxGatedVzAfterSettling) {
                    maxGatedVzAfterSettling = gated
                }
            }
        }

        assertEquals(0.0f, maxGatedVzAfterSettling, "Noisy native vario at rest must be suppressed by deadband gate")
    }

    @Test
    fun constantClimb_convergesAccurately() {
        val trueVz = 2.5f
        var currentAlt = 500.0f

        // Run 50 cycles @ 10 Hz (5 seconds of constant climb)
        for (i in 0 until 50) {
            currentAlt += trueVz * 0.1f
            // Add sensor noise
            val noise = (sin(i * 0.7) * 0.10).toFloat()
            filter.predict(0.1f)
            filter.updateBaroAltitude(currentAlt + noise)
        }

        assertTrue(
            abs(filter.vz - trueVz) < 0.25f,
            "Expected Vz to converge near $trueVz m/s, got ${filter.vz} m/s"
        )
        assertTrue(
            abs(filter.altitude - currentAlt) < 0.5f,
            "Expected altitude to track near $currentAlt m, got ${filter.altitude} m"
        )
    }

    @Test
    fun presetSwitching_updatesTuningParameters() {
        filter.preset = FilterPreset.SMOOTH
        assertEquals(0.4f, filter.accelVariance)
        assertEquals(0.60f, filter.baroAltVariance)
        assertEquals(0.12f, filter.deadbandEpsilon)

        filter.preset = FilterPreset.SENSITIVE
        assertEquals(2.5f, filter.accelVariance)
        assertEquals(0.15f, filter.baroAltVariance)
        assertEquals(0.06f, filter.deadbandEpsilon)
    }

    @Test
    fun zeroAllocations_across50000Cycles() {
        // JIT warm-up
        repeat(5_000) { i ->
            filter.predict(0.1f)
            filter.updateAltitudeAndVario(500.0f + (i % 10) * 0.1f, 1.2f)
            filter.getGatedVz()
        }

        System.gc()
        Thread.sleep(50)

        val beforeAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // 50,000 cycles on critical fast path
        for (i in 0 until 50_000) {
            filter.predict(0.1f)
            filter.updateAltitudeAndVario(500.0f + (i % 5) * 0.2f, 2.0f)
            filter.getGatedVz()
        }

        val afterAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val delta = afterAllocated - beforeAllocated

        assertTrue(delta < 150_000, "Heap growth detected ($delta bytes) during Kalman filter loop!")
    }

    @Test
    fun imuAssist_whenDisabled_ignoresAcceleration() {
        filter.isImuAssistEnabled = false
        val initialVz = filter.vz
        val initialAlt = filter.altitude

        filter.predictWithAcceleration(0.1f, 4.0f)

        assertEquals(initialVz, filter.vz, "Vz should remain unchanged when IMU assist is disabled")
        assertEquals(initialAlt, filter.altitude, "Altitude should remain unchanged when IMU assist is disabled")
    }

    @Test
    fun imuAssist_whenEnabled_filtersTremorsBelowDeadband() {
        filter.isImuAssistEnabled = true
        val initialVz = filter.vz

        // Harness vibration below 0.15 m/s²
        filter.predictWithAcceleration(0.1f, 0.12f)
        assertEquals(initialVz, filter.vz, "Tremor acceleration below 0.15 m/s² should be ignored")

        filter.predictWithAcceleration(0.1f, -0.10f)
        assertEquals(initialVz, filter.vz, "Negative tremor acceleration below 0.15 m/s² should be ignored")
    }

    @Test
    fun imuAssist_whenEnabled_acceleratesImmediately() {
        filter.isImuAssistEnabled = true
        assertEquals(0.0f, filter.vz)

        // Thermal entry acceleration: +2.0 m/s² for 0.5s -> expected vz = 1.0 m/s
        filter.predictWithAcceleration(0.5f, 2.0f)

        assertEquals(1.0f, filter.vz, 1e-4f, "Vz should immediately reflect integrated acceleration")
        assertTrue(filter.altitude > 500.0f, "Altitude should increase according to 0.5 * a * dt²")
    }

    @Test
    fun zeroAllocations_withImuAssist_across50000Cycles() {
        filter.isImuAssistEnabled = true

        // JIT warm-up
        repeat(5_000) { i ->
            filter.predictWithAcceleration(0.1f, 1.2f)
            filter.updateAltitudeAndVario(500.0f + (i % 10) * 0.1f, 1.2f)
            filter.getGatedVz()
        }

        System.gc()
        Thread.sleep(50)

        val beforeAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        for (i in 0 until 50_000) {
            filter.predictWithAcceleration(0.1f, (i % 10) * 0.2f)
            filter.updateAltitudeAndVario(500.0f + (i % 5) * 0.2f, 2.0f)
            filter.getGatedVz()
        }

        val afterAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val delta = afterAllocated - beforeAllocated

        assertTrue(delta < 150_000, "Heap growth detected ($delta bytes) during IMU Kalman filter loop!")
    }
}

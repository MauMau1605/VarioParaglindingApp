package com.vario.app

import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AltitudeFilterPipelineTest {

    private lateinit var pipeline: AltitudeFilterPipeline

    @BeforeTest
    fun setUp() {
        pipeline = AltitudeFilterPipeline()
        pipeline.configure(
            phase = SessionPhase.FLYING,
            mode = SensorMode.BARO_AND_GPS,
            preset = FilterPreset.BALANCED,
            activityType = ActivityType.SIMPLE_FLIGHT
        )
        pipeline.reset(1000f)
    }

    @Test
    fun initialization_setsInitialState() {
        assertTrue(pipeline.isInitialized)
        assertEquals(1000f, pipeline.filteredAltitudeM)
        assertEquals(0f, pipeline.filteredVzMs)
        assertEquals(0f, pipeline.rawVzMs)
        assertFalse(pipeline.isStationary)
    }

    @Test
    fun configure_adjustsParametersForDifferentPhasesAndModes() {
        // Flying Baro
        pipeline.configure(SessionPhase.FLYING, SensorMode.BARO_AND_GPS)
        assertEquals(20.0f, pipeline.maxAltitudeSlewRateMs)
        assertEquals(25.0f, pipeline.maxVzSlewRateMs2)
        assertEquals(1.0f, pipeline.emaAlpha)

        // Hiking Baro
        pipeline.configure(SessionPhase.HIKING, SensorMode.BARO_AND_GPS, activityType = ActivityType.HIKING)
        assertEquals(4.0f, pipeline.maxAltitudeSlewRateMs)
        assertEquals(5.0f, pipeline.maxVzSlewRateMs2)
        assertEquals(0.20f, pipeline.emaAlpha)

        // Running Baro
        pipeline.configure(SessionPhase.HIKING, SensorMode.BARO_AND_GPS, activityType = ActivityType.RUNNING)
        assertEquals(6.0f, pipeline.maxAltitudeSlewRateMs)
        assertEquals(6.0f, pipeline.maxVzSlewRateMs2)
        assertEquals(0.35f, pipeline.emaAlpha)

        // Flying GPS Only
        pipeline.configure(SessionPhase.FLYING, SensorMode.GPS_ONLY)
        assertEquals(15.0f, pipeline.maxAltitudeSlewRateMs)
        assertEquals(10.0f, pipeline.maxVzSlewRateMs2)
        assertEquals(0.40f, pipeline.emaAlpha)
    }

    @Test
    fun processBaro_clampsAltitudeSpikesViaSlewRateLimiter() {
        // Normal 10 Hz interval (dt = 0.1s)
        // With maxAltitudeSlewRateMs = 20 m/s, max allowed delta is 20 * 0.1 = 2.0m
        // Sensor glitch sends an impossible +50m jump in 0.1s
        pipeline.processBaro(
            measuredAltM = 1050f,
            hasNativeVario = false,
            nativeVarioMs = 0f,
            dtSec = 0.1f
        )

        // Filtered altitude should be clamped near 1000 + 2.0m rather than jumping to 1050m
        assertTrue(pipeline.filteredAltitudeM <= 1003f, "Spike should be slew-rate limited, was ${pipeline.filteredAltitudeM}")
    }

    @Test
    fun processBaro_stationaryJitterClampsVzToZero() {
        // Alternating small jitter around 1000m
        for (i in 0 until 50) {
            val noise = if (i % 2 == 0) 0.05f else -0.05f
            pipeline.processBaro(
                measuredAltM = 1000f + noise,
                hasNativeVario = false,
                nativeVarioMs = 0f,
                dtSec = 0.1f
            )
        }

        assertTrue(pipeline.isStationary, "Motionless sensor should be identified as stationary")
        assertEquals(0f, pipeline.filteredVzMs, "Stationary Vz should be clamped to 0.0 m/s")
    }

    @Test
    fun processBaro_sustainedClimbProducesPositiveVz() {
        // Sustained 2 m/s climb for 3 seconds (30 cycles @ 10 Hz)
        var alt = 1000f
        for (i in 0 until 30) {
            alt += 0.2f
            pipeline.processBaro(
                measuredAltM = alt,
                hasNativeVario = true,
                nativeVarioMs = 2.0f,
                dtSec = 0.1f
            )
        }

        assertFalse(pipeline.isStationary)
        assertTrue(pipeline.filteredVzMs > 1.5f && pipeline.filteredVzMs < 2.5f, "Filtered Vz should track ~2.0 m/s climb, was ${pipeline.filteredVzMs}")
    }

    @Test
    fun processGps_clampsStationaryWhenGroundSpeedIsLowInHikingPhase() {
        pipeline.configure(SessionPhase.HIKING, SensorMode.GPS_ONLY)
        pipeline.reset(1500f)

        // Pilot is stopped on the ground (groundSpeed = 0.1 m/s) with minor GPS altitude jitter
        pipeline.processGps(gpsAltM = 1500.5f, dtSec = 1.0f, groundSpeedMs = 0.1f)

        assertTrue(pipeline.isStationary)
        assertEquals(0f, pipeline.filteredVzMs)
    }

    @Test
    fun clampStationary_forcesZeroVelocity() {
        pipeline.clampStationary(freezeAltitude = true)
        assertTrue(pipeline.isStationary)
        assertEquals(0f, pipeline.filteredVzMs)
        assertEquals(0f, pipeline.rawVzMs)
    }
}

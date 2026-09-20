package com.vario.app

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class VarioServiceCompanionTest {

    @Test
    fun setUsbStatus_updatesDataFlow() {
        VarioService.setUsbStatus(true, "Test Dongle")
        var current = VarioService.dataFlow.value
        assertEquals(true, current.isUsbConnected)
        assertEquals(false, current.isUsbScanning)
        assertEquals(SensorMode.BARO_AND_GPS, current.sensorMode)
        assertEquals("Test Dongle", current.usbDeviceName)

        VarioService.setUsbStatus(false, null)
        current = VarioService.dataFlow.value
        assertEquals(false, current.isUsbConnected)
        assertEquals(SensorMode.GPS_ONLY, current.sensorMode)
        assertEquals(null, current.usbDeviceName)
    }

    @Test
    fun updateStandbyLocation_updatesCoordinatesAndAltitude() {
        VarioService.setUsbStatus(false, null)

        val mockLocation = mockk<Location>()
        every { mockLocation.latitude } returns 45.1885
        every { mockLocation.longitude } returns 5.7245
        every { mockLocation.altitude } returns 1350.0

        VarioService.updateStandbyLocation(mockLocation)
        val current = VarioService.dataFlow.value
        assertEquals(true, current.gpsFixAcquired)
        assertEquals(45.1885, current.latitude)
        assertEquals(5.7245, current.longitude)
        assertEquals(1350f, current.gpsAltitudeM)
        assertEquals(1350f, current.altitudeM)
    }

    @Test
    fun resolveEffectivePressurePa_convertsHpaToPa() {
        // Standard atmospheric pressure in hPa
        assertEquals(101300L, VarioService.resolveEffectivePressurePa(1013L))
        // 950 hPa
        assertEquals(95000L, VarioService.resolveEffectivePressurePa(950L))
        // Boundary values for hPa (300..1200)
        assertEquals(30000L, VarioService.resolveEffectivePressurePa(300L))
        assertEquals(120000L, VarioService.resolveEffectivePressurePa(1200L))

        // Values outside 300..1200 should remain untouched
        assertEquals(101325L, VarioService.resolveEffectivePressurePa(101325L))
        assertEquals(95432L, VarioService.resolveEffectivePressurePa(95432L))
        assertEquals(0L, VarioService.resolveEffectivePressurePa(0L))
        assertEquals(999999L, VarioService.resolveEffectivePressurePa(999999L))
    }

    @Test
    fun isValidPressurePa_checksRange() {
        assertEquals(true, VarioService.isValidPressurePa(101325L))
        assertEquals(true, VarioService.isValidPressurePa(30000L))
        assertEquals(true, VarioService.isValidPressurePa(115000L))

        assertEquals(false, VarioService.isValidPressurePa(0L))
        assertEquals(false, VarioService.isValidPressurePa(29999L))
        assertEquals(false, VarioService.isValidPressurePa(115001L))
        assertEquals(false, VarioService.isValidPressurePa(999999L))
    }

    @Test
    fun arbitrateAltitude_validBaroPressure_calculatesBaroAlt() {
        val qnh = 101325.0
        // Pressure 90000 Pa corresponds to roughly 988m altitude
        val alt = VarioService.arbitrateAltitude(
            pressurePa = 90000L,
            rawAltitudeM = 0L,
            currentQnhPa = qnh,
            availableGpsAlt = 500f,
            previousAltitudeM = 500f
        )
        val expected = VarioMath.pressureToAltitude(90000L, qnh)
        assertEquals(expected, alt)
    }

    @Test
    fun arbitrateAltitude_hpaPressure_correctlyConvertsAndCalculates() {
        val qnh = 101325.0
        // 900 hPa = 90000 Pa
        val alt = VarioService.arbitrateAltitude(
            pressurePa = 900L,
            rawAltitudeM = 0L,
            currentQnhPa = qnh,
            availableGpsAlt = 0f,
            previousAltitudeM = 0f
        )
        val expected = VarioMath.pressureToAltitude(90000L, qnh)
        assertEquals(expected, alt)
    }

    @Test
    fun arbitrateAltitude_invalidPressure_fallsBackToLk8ex1Altitude() {
        val alt = VarioService.arbitrateAltitude(
            pressurePa = 0L,
            rawAltitudeM = 1420L,
            currentQnhPa = 101325.0,
            availableGpsAlt = 1200f,
            previousAltitudeM = 1000f
        )
        assertEquals(1420f, alt)
    }

    @Test
    fun arbitrateAltitude_invalidPressureAndLk8ex1Alt99999_fallsBackToGps() {
        val alt = VarioService.arbitrateAltitude(
            pressurePa = 999999L,
            rawAltitudeM = 99999L,
            currentQnhPa = 101325.0,
            availableGpsAlt = 1550f,
            previousAltitudeM = 0f
        )
        assertEquals(1550f, alt)
    }

    @Test
    fun arbitrateAltitude_preservesPreviousAltitudeWhenCalculatedAltitudeIsZero() {
        // LK8EX1 sends 0 pressure, 0 altitude, no GPS altitude
        val alt = VarioService.arbitrateAltitude(
            pressurePa = 0L,
            rawAltitudeM = 0L,
            currentQnhPa = 101325.0,
            availableGpsAlt = 0f,
            previousAltitudeM = 1850f
        )
        // NEVER overwrite valid altitude with 0f!
        assertEquals(1850f, alt)
    }

    @Test
    fun resolveInitialFlightAltitude_prioritizesPositiveSources() {
        // When current altitude is positive
        assertEquals(
            1500f,
            VarioService.resolveInitialFlightAltitude(1500f, 1480f, 1475f)
        )

        // When current altitude is 0f, falls back to GPS altitude
        assertEquals(
            1480f,
            VarioService.resolveInitialFlightAltitude(0f, 1480f, 1475f)
        )

        // When current and GPS are 0f, falls back to lastLocationAltitude
        assertEquals(
            1475f,
            VarioService.resolveInitialFlightAltitude(0f, 0f, 1475f)
        )

        // When all are 0 or null, returns 0f
        assertEquals(
            0f,
            VarioService.resolveInitialFlightAltitude(0f, 0f, null)
        )
    }

    @Test
    fun accumulateDistanceTraveled_onlyAccumulatesWhenFlightIsActive() {
        val loc1 = mockk<Location>()
        val loc2 = mockk<Location>()
        every { loc1.distanceTo(loc2) } returns 250f

        // Flight NOT active: should NOT accumulate distance
        val stoppedDist = VarioService.accumulateDistanceTraveled(
            isFlightActive = false,
            currentTotalM = 1000f,
            prevLoc = loc1,
            newLoc = loc2
        )
        assertEquals(1000f, stoppedDist)

        // Flight active, prevLoc null: should retain total
        val activeNullPrev = VarioService.accumulateDistanceTraveled(
            isFlightActive = true,
            currentTotalM = 1000f,
            prevLoc = null,
            newLoc = loc2
        )
        assertEquals(1000f, activeNullPrev)

        // Flight active, prevLoc present: accumulates distance
        val activeDist = VarioService.accumulateDistanceTraveled(
            isFlightActive = true,
            currentTotalM = 1000f,
            prevLoc = loc1,
            newLoc = loc2
        )
        assertEquals(1250f, activeDist)
    }

    @Test
    fun computeDistanceToTakeoff_onlyUpdatesWhenFlightIsActive() {
        val currentLoc = mockk<Location>()
        val takeoffLoc = mockk<Location>()
        every { currentLoc.distanceTo(takeoffLoc) } returns 850f

        // Flight NOT active: retains current distance without updating
        val inactiveDist = VarioService.computeDistanceToTakeoff(
            isFlightActive = false,
            currentDistanceToTakeoffM = 500f,
            loc = currentLoc,
            takeoffLocation = takeoffLoc
        )
        assertEquals(500f, inactiveDist)

        // Flight NOT active, current distance null: stays null
        val inactiveNullDist = VarioService.computeDistanceToTakeoff(
            isFlightActive = false,
            currentDistanceToTakeoffM = null,
            loc = currentLoc,
            takeoffLocation = takeoffLoc
        )
        assertEquals(null, inactiveNullDist)

        // Flight active, takeoffLocation is null: retains current distance
        val activeNoTakeoff = VarioService.computeDistanceToTakeoff(
            isFlightActive = true,
            currentDistanceToTakeoffM = null,
            loc = currentLoc,
            takeoffLocation = null
        )
        assertEquals(null, activeNoTakeoff)

        // Flight active, takeoffLocation present: updates to new distance
        val activeDist = VarioService.computeDistanceToTakeoff(
            isFlightActive = true,
            currentDistanceToTakeoffM = 500f,
            loc = currentLoc,
            takeoffLocation = takeoffLoc
        )
        assertEquals(850f, activeDist)
    }

    @Test
    fun computeGpsVz_calculatesAndSmoothsCorrectly() {
        // Invalid lastAlt (NaN): retains previousVz
        assertEquals(2.0f, VarioService.computeGpsVz(1005.0, Double.NaN, 1.0f, 2.0f))

        // dtSec too small (< 0.1s): retains previousVz
        assertEquals(2.0f, VarioService.computeGpsVz(1005.0, 1000.0, 0.05f, 2.0f))

        // dtSec too large (> 10.0s): retains previousVz
        assertEquals(2.0f, VarioService.computeGpsVz(1005.0, 1000.0, 11.0f, 2.0f))

        // First calculation from 0: rawVz = (1005 - 1000) / 1.0 = 5.0 m/s
        val vzInitial = VarioService.computeGpsVz(1005.0, 1000.0, 1.0f, 0.0f)
        assertEquals(5.0f, vzInitial)

        // Subsequent calculation applies EMA: previous * 0.65 + raw * 0.35
        // rawVz = (1008 - 1005) / 1.0 = 3.0 m/s
        // expected = 5.0 * 0.65 + 3.0 * 0.35 = 3.25 + 1.05 = 4.30f
        val vzSmoothed = VarioService.computeGpsVz(1008.0, 1005.0, 1.0f, vzInitial)
        assertEquals(4.30f, vzSmoothed, 0.001f)

        // Positive clamping at 20 m/s: rawVz = (1500 - 1000) / 1.0 = 500 m/s -> clamped to 20 m/s
        val vzClampedMax = VarioService.computeGpsVz(1500.0, 1000.0, 1.0f, 0.0f)
        assertEquals(20.0f, vzClampedMax)

        // Negative clamping at -20 m/s: rawVz = (500 - 1000) / 1.0 = -500 m/s -> clamped to -20 m/s
        val vzClampedMin = VarioService.computeGpsVz(500.0, 1000.0, 1.0f, 0.0f)
        assertEquals(-20.0f, vzClampedMin)
    }

    @Test
    fun computeBaroVz_calculatesAndClampsCorrectly() {
        // Invalid lastAlt (NaN): returns previousVz (default 0f)
        assertEquals(0f, VarioService.computeBaroVz(1005f, Float.NaN, 1.0f))
        assertEquals(2.5f, VarioService.computeBaroVz(1005f, Float.NaN, 1.0f, 2.5f))

        // dtSec too small (< 0.05f): returns previousVz
        assertEquals(0f, VarioService.computeBaroVz(1005f, 1000f, 0.0005f))
        assertEquals(1.5f, VarioService.computeBaroVz(1005f, 1000f, 0.04f, 1.5f))

        // dtSec too large (> 3.0f): returns previousVz
        assertEquals(1.8f, VarioService.computeBaroVz(1005f, 1000f, 3.1f, 1.8f))

        // Normal climb (initial, previousVz = 0): (1005 - 1000) / 0.5s = +10.0 m/s
        assertEquals(10.0f, VarioService.computeBaroVz(1005f, 1000f, 0.5f))

        // Normal sink (initial, previousVz = 0): (998 - 1000) / 0.5s = -4.0 m/s
        assertEquals(-4.0f, VarioService.computeBaroVz(998f, 1000f, 0.5f))

        // Positive clamping at 20 m/s: (1050 - 1000) / 1.0s = +50 m/s -> clamped to 20 m/s
        assertEquals(20.0f, VarioService.computeBaroVz(1050f, 1000f, 1.0f))

        // Negative clamping at -20 m/s: (950 - 1000) / 1.0s = -50 m/s -> clamped to -20 m/s
        assertEquals(-20.0f, VarioService.computeBaroVz(950f, 1000f, 1.0f))

        // EMA smoothing: previousVz = 10.0f, rawVz = (1005 - 1000) / 1.0s = 5.0f
        // expected = 10.0 * 0.65 + 5.0 * 0.35 = 6.5 + 1.75 = 8.25f
        assertEquals(8.25f, VarioService.computeBaroVz(1005f, 1000f, 1.0f, 10.0f), 0.001f)
    }

    @Test
    fun computeElevationGain_accumulatesPositiveClimbAboveNoiseThreshold() {
        // Initial call when lastAlt is 0f: initializes lastAlt without gain
        val (g0, a0) = VarioService.computeElevationGain(
            currentGainM = 0f,
            lastAltM = 0f,
            newAltM = 1000f,
            noiseThresholdM = 1.0f
        )
        assertEquals(0f, g0)
        assertEquals(1000f, a0)

        // Small increase below noise threshold (0.5m < 1.0m): no gain, lastAlt unchanged
        val (g1, a1) = VarioService.computeElevationGain(
            currentGainM = g0,
            lastAltM = a0,
            newAltM = 1000.5f,
            noiseThresholdM = 1.0f
        )
        assertEquals(0f, g1)
        assertEquals(1000f, a1)

        // Clear climb: 1010m (+10m): gain increases by 10m, lastAlt updates to 1010m
        val (g2, a2) = VarioService.computeElevationGain(
            currentGainM = g1,
            lastAltM = a1,
            newAltM = 1010f,
            noiseThresholdM = 1.0f
        )
        assertEquals(10f, g2)
        assertEquals(1010f, a2)

        // Descent: 1005m (-5m): gain remains 10m, lastAlt updates to 1005m
        val (g3, a3) = VarioService.computeElevationGain(
            currentGainM = g2,
            lastAltM = a2,
            newAltM = 1005f,
            noiseThresholdM = 1.0f
        )
        assertEquals(10f, g3)
        assertEquals(1005f, a3)

        // Another climb: 1020m (+15m from 1005m): gain increases by 15m to 25m total
        val (g4, a4) = VarioService.computeElevationGain(
            currentGainM = g3,
            lastAltM = a3,
            newAltM = 1020f,
            noiseThresholdM = 1.0f
        )
        assertEquals(25f, g4)
        assertEquals(1020f, a4)
    }

    @Test
    fun setFlightMode_updatesDataFlow() {
        VarioService.setFlightMode(FlightMode.NORMAL)
        assertEquals(FlightMode.NORMAL, VarioService.dataFlow.value.flightMode)

        VarioService.setFlightMode(FlightMode.HIKE_AND_FLY)
        assertEquals(FlightMode.HIKE_AND_FLY, VarioService.dataFlow.value.flightMode)

        // Reset to normal
        VarioService.setFlightMode(FlightMode.NORMAL)
        assertEquals(FlightMode.NORMAL, VarioService.dataFlow.value.flightMode)
    }
}

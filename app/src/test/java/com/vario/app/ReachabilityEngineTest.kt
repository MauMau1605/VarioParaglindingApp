package com.vario.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReachabilityEngineTest {

    @Test
    fun aglToColor_unreachableIsTransparent() {
        assertEquals(ReachabilityEngine.COLOR_UNREACH, ReachabilityEngine.aglToColor(-10f))
        assertEquals(ReachabilityEngine.COLOR_UNREACH, ReachabilityEngine.aglToColor(0f))
    }

    @Test
    fun aglToColor_highAglReturnsDeepBlue() {
        assertEquals(ReachabilityEngine.COLOR_DEEP_BLUE, ReachabilityEngine.aglToColor(2000f))
        assertEquals(ReachabilityEngine.COLOR_DEEP_BLUE, ReachabilityEngine.aglToColor(1600f))
    }

    @Test
    fun aglToColor_differentThresholdsReturnDistinctColors() {
        val cLow = ReachabilityEngine.aglToColor(30f)   // Red zone
        val cMed = ReachabilityEngine.aglToColor(400f)  // Green zone
        val cHigh = ReachabilityEngine.aglToColor(1000f) // Blue zone

        assertNotEquals(ReachabilityEngine.COLOR_UNREACH, cLow)
        assertNotEquals(cLow, cMed)
        assertNotEquals(cMed, cHigh)
    }

    @Test
    fun lerpColor_endpointsAndMidpoint() {
        val c1 = 0x10203040
        val c2 = 0x50607080

        assertEquals(c1, ReachabilityEngine.lerpColor(c1, c2, 0f))
        assertEquals(c2, ReachabilityEngine.lerpColor(c1, c2, 1f))

        val mid = ReachabilityEngine.lerpColor(c1, c2, 0.5f)
        val a = (mid ushr 24) and 0xFF
        val r = (mid ushr 16) and 0xFF
        val g = (mid ushr 8) and 0xFF
        val b = mid and 0xFF

        assertEquals(0x30, a)
        assertEquals(0x40, r)
        assertEquals(0x50, g)
        assertEquals(0x60, b)
    }

    @Test
    fun computeReachabilityPixels_flatTerrainRadialGradient() {
        val centerLat = 45.0
        val centerLon = 6.0
        val pilotAlt = 2000f
        val groundElev = 500f

        // 5x5 grid with constant ground elevation of 500m
        val grid = ElevationGrid(
            minLat = 44.98,
            maxLat = 45.02,
            minLon = 5.98,
            maxLon = 6.02,
            rows = 5,
            cols = 5,
            elevations = FloatArray(25) { groundElev }
        )

        val pixels = IntArray(25)
        val config = ReachabilityConfig(glideRatio = 8.5f, safetyMarginM = 50f, checkLineOfSight = true)

        ReachabilityEngine.computeReachabilityPixels(
            centerLat, centerLon, pilotAlt, grid, config, pixels
        )

        // Center pixel (row 2, col 2) has highest AGL (pilotAlt - groundElev - safetyMargin = 1450m)
        val centerPixel = pixels[2 * 5 + 2]
        assertNotEquals(ReachabilityEngine.COLOR_UNREACH, centerPixel)

        // Center should be high clearance (blue/deep blue)
        val centerAlpha = (centerPixel ushr 24) and 0xFF
        assertTrue(centerAlpha > 0)
    }

    @Test
    fun computeReachabilityPixels_mountainRidgeBlocksBehind() {
        val centerLat = 45.0
        val centerLon = 6.0
        val pilotAlt = 1500f

        // 3x3 grid:
        // Center is at (1, 1).
        // Let col 2 have a high mountain ridge at (1, 2) that blocks col 2?
        // Let's create a 5x1 vertical strip (rows 0 to 4, col 0)
        // Center at row 2, col 0.
        // Row 3 has a huge mountain (2200m) while row 4 has a valley (300m).
        val elevations = floatArrayOf(
            500f,
            500f,
            500f, // Center at row 2
            2200f, // Mountain ridge at row 3
            300f   // Valley behind mountain at row 4
        )

        val grid = ElevationGrid(
            minLat = 44.96,
            maxLat = 45.04,
            minLon = 6.0,
            maxLon = 6.0,
            rows = 5,
            cols = 1,
            elevations = elevations
        )

        val pixels = IntArray(5)
        val configWithLos = ReachabilityConfig(glideRatio = 8f, safetyMarginM = 50f, checkLineOfSight = true)

        ReachabilityEngine.computeReachabilityPixels(
            centerLat, centerLon, pilotAlt, grid, configWithLos, pixels
        )

        // Row 3 (the mountain) is higher than pilotAlt (2200m > 1500m) -> unreachable
        assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[3])

        // Row 4 (valley behind mountain) must be BLOCKED by line-of-sight terrain collision!
        assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[4])
    }
}

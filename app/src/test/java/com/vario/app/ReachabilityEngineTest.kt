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

    @Test
    fun computeReachableRadiusM_coversFullGlideRange() {
        val gr = 8.5f

        // High mountain flight at 1500m MSL
        val r1500 = ReachabilityEngine.computeReachableRadiusM(1500f, gr)
        // 1500 * 8.5 * 1.15 = 14662.5m
        assertTrue(r1500 >= 14000f, "Radius at 1500m should cover entire glide range (>14km), got $r1500")

        // Flight at 500m MSL: 500 * 8.5 * 1.15 = 4887.5m
        val r500 = ReachabilityEngine.computeReachableRadiusM(500f, gr)
        assertTrue(r500 >= 4800f, "Radius at 500m should cover entire glide range (>4.8km), got $r500")

        // Low coastal takeoff at 50m MSL: should clamp to minimum 1500m
        val r50 = ReachabilityEngine.computeReachableRadiusM(50f, gr)
        assertEquals(1500f, r50)

        // Very high alpine altitude (4500m): should clamp to maximum 40000m
        val r4500 = ReachabilityEngine.computeReachableRadiusM(4500f, gr)
        assertEquals(40000f, r4500)
    }

    @Test
    fun computeReachabilityPixels_fullRangeGridPerimeterIsTransparent() {
        val centerLat = 45.0
        val centerLon = 6.0
        val pilotAlt = 1500f
        val gr = 8.5f
        val safetyMargin = 50f

        val radiusM = ReachabilityEngine.computeReachableRadiusM(pilotAlt, gr)
        val deltaLat = radiusM / 111139.0
        val deltaLon = radiusM / (111139.0 * Math.cos(Math.toRadians(centerLat)))

        val rows = 9
        val cols = 9
        val groundElev = 250f // Flat valley floor

        val grid = ElevationGrid(
            minLat = centerLat - deltaLat,
            maxLat = centerLat + deltaLat,
            minLon = centerLon - deltaLon,
            maxLon = centerLon + deltaLon,
            rows = rows,
            cols = cols,
            elevations = FloatArray(rows * cols) { groundElev }
        )

        val pixels = IntArray(rows * cols)
        val config = ReachabilityConfig(glideRatio = gr, safetyMarginM = safetyMargin, checkLineOfSight = true)

        ReachabilityEngine.computeReachabilityPixels(
            centerLat, centerLon, pilotAlt, grid, config, pixels
        )

        // Center must be reachable
        val centerIdx = (rows / 2) * cols + (cols / 2)
        assertNotEquals(ReachabilityEngine.COLOR_UNREACH, pixels[centerIdx])

        // ALL perimeter pixels (North, South, East, West borders) MUST be COLOR_UNREACH (transparent).
        // This confirms that the glide reach is fully contained within the grid, avoiding square edge truncation.
        for (c in 0 until cols) {
            assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[0 * cols + c], "Top edge col $c must be transparent")
            assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[(rows - 1) * cols + c], "Bottom edge col $c must be transparent")
        }
        for (r in 0 until rows) {
            assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[r * cols + 0], "Left edge row $r must be transparent")
            assertEquals(ReachabilityEngine.COLOR_UNREACH, pixels[r * cols + (cols - 1)], "Right edge row $r must be transparent")
        }
    }
}

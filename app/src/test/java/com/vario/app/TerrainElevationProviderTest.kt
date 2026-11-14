package com.vario.app

import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainElevationProviderTest {

    @BeforeTest
    fun setUp() {
        TerrainElevationProvider.clearCache()
    }

    @Test
    fun quantizeCoord_roundsToThreeDecimals() {
        assertEquals(45123L, TerrainElevationProvider.quantizeCoord(45.1234))
        assertEquals(45124L, TerrainElevationProvider.quantizeCoord(45.1236))
        assertEquals(6250L, TerrainElevationProvider.quantizeCoord(6.2501))
    }

    @Test
    fun cachedElevation_isReturnedImmediately() {
        val lat = 45.123
        val lon = 6.456
        val elev = 1850.5f

        TerrainElevationProvider.putElevationInCache(lat, lon, elev)
        val result = TerrainElevationProvider.getElevation(lat, lon)
        assertEquals(elev, result)
    }

    @Test
    fun getElevationBatch_usesCachedValues() = runTest {
        val lats = doubleArrayOf(45.100, 45.200, 45.300)
        val lons = doubleArrayOf(6.100, 6.200, 6.300)
        val expected = floatArrayOf(1200f, 1500f, 2100f)

        for (i in lats.indices) {
            TerrainElevationProvider.putElevationInCache(lats[i], lons[i], expected[i])
        }

        val results = TerrainElevationProvider.getElevationBatch(lats, lons)
        assertEquals(3, results.size)
        for (i in results.indices) {
            assertEquals(expected[i], results[i])
        }
    }

    @Test
    fun findPeakElevation_returnsMaxInRadius() = runTest {
        val centerLat = 45.000
        val centerLon = 6.000

        // Put surrounding elevations in cache
        TerrainElevationProvider.putElevationInCache(centerLat, centerLon, 1200f)

        // Put a peak slightly offset (approx 12m north, within 20x20m box)
        val peakLat = centerLat + (12.0 / 111139.0)
        TerrainElevationProvider.putElevationInCache(peakLat, centerLon, 1485f)

        val peak = TerrainElevationProvider.findPeakElevation(centerLat, centerLon, radiusM = 20f)
        assertTrue(peak >= 1485f, "Peak elevation should be at least the 1485m sample")
    }

    @Test
    fun elevationGrid_dimensionsAndAccessors() = runTest {
        val grid = ElevationGrid(
            minLat = 45.0,
            maxLat = 45.1,
            minLon = 6.0,
            maxLon = 6.1,
            rows = 2,
            cols = 2,
            elevations = floatArrayOf(100f, 200f, 300f, 400f)
        )

        assertEquals(100f, grid.getElevationAt(0, 0))
        assertEquals(200f, grid.getElevationAt(0, 1))
        assertEquals(300f, grid.getElevationAt(1, 0))
        assertEquals(400f, grid.getElevationAt(1, 1))
        assertEquals(TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M, grid.getElevationAt(-1, 0))
        assertEquals(TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M, grid.getElevationAt(2, 2))
    }
}

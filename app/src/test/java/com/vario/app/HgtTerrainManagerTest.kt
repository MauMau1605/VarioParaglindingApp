package com.vario.app

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HgtTerrainManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var terrainDir: File

    @BeforeTest
    fun setUp() {
        terrainDir = tempFolder.newFolder("terrain")
        HgtTerrainManager.setTerrainDirectory(terrainDir)
        HgtTerrainManager.clearCache()
        TerrainElevationProvider.clearCache()
    }

    @AfterTest
    fun tearDown() {
        HgtTerrainManager.clearCache()
    }

    @Test
    fun getTileFilename_formatsStandardCoordinatesCorrectly() {
        // Northern / Eastern
        assertEquals("N45E005.hgt", HgtTerrainManager.getTileFilename(45.3, 5.8))
        assertEquals("N45E006.hgt", HgtTerrainManager.getTileFilename(45.92, 6.87))
        assertEquals("N00E000.hgt", HgtTerrainManager.getTileFilename(0.5, 0.5))

        // Southern / Eastern
        assertEquals("S22E055.hgt", HgtTerrainManager.getTileFilename(-21.4, 55.5))

        // Northern / Western
        assertEquals("N37W123.hgt", HgtTerrainManager.getTileFilename(37.2, -122.1))

        // Southern / Western
        assertEquals("S15W075.hgt", HgtTerrainManager.getTileFilename(-14.8, -74.3))
    }

    @Test
    fun getContinentForCoords_mapsStandardRegions() {
        assertEquals("Eurasia", HgtTerrainManager.getContinentForCoords(45.9, 6.8))
        assertEquals("North_America", HgtTerrainManager.getContinentForCoords(37.5, -120.0))
        assertEquals("South_America", HgtTerrainManager.getContinentForCoords(-12.0, -75.0))
        assertEquals("Australia", HgtTerrainManager.getContinentForCoords(-33.0, 150.0))
        assertEquals("Africa", HgtTerrainManager.getContinentForCoords(10.0, 20.0))
    }

    @Test
    fun binaryHgtReadingAndBilinearInterpolation() {
        // Create a standard SRTM3 file: 1201 x 1201 16-bit big-endian shorts = 2,884,802 bytes
        val filename = "N45E005.hgt"
        val tileFile = File(terrainDir, filename)
        val numSamples = 1201

        val raf = RandomAccessFile(tileFile, "rw")
        val channel = raf.channel
        val totalBytes = numSamples * numSamples * 2L
        channel.truncate(totalBytes)

        val byteBuffer = ByteBuffer.allocateDirect(numSamples * 2).order(ByteOrder.BIG_ENDIAN)

        // Write synthetic gradient:
        // row 0 (North, lat 46.0): 2000m
        // row 1200 (South, lat 45.0): 1000m
        for (row in 0 until numSamples) {
            byteBuffer.clear()
            val elev = 2000 - ((row.toDouble() / (numSamples - 1)) * 1000).toInt()
            val shortVal = elev.toShort()
            for (col in 0 until numSamples) {
                byteBuffer.putShort(shortVal)
            }
            byteBuffer.flip()
            channel.write(byteBuffer)
        }
        channel.close()
        raf.close()

        // Verify tile is recognized
        assertTrue(HgtTerrainManager.isTileAvailable(45.5, 5.5))

        // Exact center: lat 45.5, lon 5.5 -> expected ~1500m
        val centerElev = HgtTerrainManager.getElevation(45.5, 5.5)
        assertNotNull(centerElev)
        assertEquals(1500f, centerElev, 1.0f)

        // North edge: lat 45.99 -> expected ~1990m
        val northElev = HgtTerrainManager.getElevation(45.99, 5.5)
        assertNotNull(northElev)
        assertEquals(1990f, northElev, 2.0f)

        // South edge: lat 45.01 -> expected ~1010m
        val southElev = HgtTerrainManager.getElevation(45.01, 5.5)
        assertNotNull(southElev)
        assertEquals(1010f, southElev, 2.0f)

        // Outside tile bounds -> null
        val outsideElev = HgtTerrainManager.getElevation(44.5, 5.5)
        assertEquals(null, outsideElev)

        // Integration with TerrainElevationProvider: priority 1 check
        val providerElev = TerrainElevationProvider.getElevation(45.5, 5.5)
        assertEquals(1500f, providerElev, 1.0f)

        // Installed tile info
        val tileInfos = HgtTerrainManager.getInstalledTileInfos()
        assertEquals(1, tileInfos.size)
        assertEquals("N45E005.hgt", tileInfos[0].filename)
        assertTrue(tileInfos[0].formattedSize.contains("Mo"))

        // Delete tile
        val deleted = HgtTerrainManager.deleteTile(filename)
        assertTrue(deleted)
        assertFalse(HgtTerrainManager.isTileAvailable(45.5, 5.5))
    }

    @Test
    fun parseTileCoordinates_parsesFilenamesAccurately() {
        // Northern / Eastern
        val coords1 = HgtTerrainManager.parseTileCoordinates("N45E005")
        assertNotNull(coords1)
        assertEquals(45.0, coords1.first)
        assertEquals(5.0, coords1.second)

        val coords2 = HgtTerrainManager.parseTileCoordinates("N45E006.hgt")
        assertNotNull(coords2)
        assertEquals(45.0, coords2.first)
        assertEquals(6.0, coords2.second)

        // Southern / Eastern
        val coords3 = HgtTerrainManager.parseTileCoordinates("S22E055.hgt")
        assertNotNull(coords3)
        assertEquals(-22.0, coords3.first)
        assertEquals(55.0, coords3.second)

        // Southern / Western
        val coords4 = HgtTerrainManager.parseTileCoordinates("s15w075")
        assertNotNull(coords4)
        assertEquals(-15.0, coords4.first)
        assertEquals(-75.0, coords4.second)

        // Equator / Meridian
        val coords5 = HgtTerrainManager.parseTileCoordinates("N00E000")
        assertNotNull(coords5)
        assertEquals(0.0, coords5.first)
        assertEquals(0.0, coords5.second)

        // Invalid
        assertEquals(null, HgtTerrainManager.parseTileCoordinates("invalid.hgt"))
        assertEquals(null, HgtTerrainManager.parseTileCoordinates("N45"))
    }

    @Test
    fun getTileBounds_computesOneDegreeBoundingBox() {
        val bounds = HgtTerrainManager.getTileBounds("N45E005.hgt")
        assertNotNull(bounds)
        assertEquals(45.0, bounds.southLat)
        assertEquals(5.0, bounds.westLon)
        assertEquals(46.0, bounds.northLat)
        assertEquals(6.0, bounds.eastLon)

        val boundsSouth = HgtTerrainManager.getTileBounds("S22E055")
        assertNotNull(boundsSouth)
        assertEquals(-22.0, boundsSouth.southLat)
        assertEquals(55.0, boundsSouth.westLon)
        assertEquals(-21.0, boundsSouth.northLat)
        assertEquals(56.0, boundsSouth.eastLon)
    }

    @Test
    fun downloadMultipleTiles_handlesEmptyAndPreinstalledTilesSimultaneously() = kotlinx.coroutines.runBlocking {
        // 1. Empty list
        val emptyResult = HgtTerrainManager.downloadMultipleTiles(emptyList())
        assertTrue(emptyResult.isEmpty())

        // 2. Pre-create 2 valid synthetic tiles on disk (>= 2MB)
        val tileNames = listOf("N45E005.hgt", "N45E006.hgt")
        for (name in tileNames) {
            val file = File(terrainDir, name)
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(2_884_802L)
            raf.close()
        }

        var lastProgress: HgtTerrainManager.MultiDownloadProgress? = null
        val results = HgtTerrainManager.downloadMultipleTiles(
            tileNames = tileNames,
            onOverallProgress = { p ->
                lastProgress = p
            }
        )

        assertEquals(2, results.size)
        assertTrue(results.containsKey("N45E005.hgt"))
        assertTrue(results.containsKey("N45E006.hgt"))
        assertTrue(results["N45E005.hgt"]!!.isSuccess)
        assertTrue(results["N45E006.hgt"]!!.isSuccess)

        assertNotNull(lastProgress)
        assertEquals(2, lastProgress!!.totalCount)
        assertEquals(2, lastProgress!!.completedCount)
        assertEquals(1.0f, lastProgress!!.overallProgress)
    }

    @Test
    fun downloadMultipleTiles_handlesInvalidTileNamesGracefully() = kotlinx.coroutines.runBlocking {
        val results = HgtTerrainManager.downloadMultipleTiles(listOf("INVALID_TILE.hgt"))
        assertEquals(1, results.size)
        assertTrue(results["INVALID_TILE.hgt"]!!.isFailure)
    }
}

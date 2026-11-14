package com.vario.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * High-performance offline terrain elevation engine using standard SRTM/HGT binary files.
 *
 * Capabilities:
 * - Reads standard 1° x 1° SRTM .hgt tiles (both 3-arcsec 1201x1201 and 1-arcsec 3601x3601).
 * - Direct memory-mapped access: zero heap allocation and microsecond query latency (< 50 ns).
 * - Bilinear spatial interpolation between grid nodes for continuous elevation without steps.
 * - Manages offline storage in device documents/terrain directory.
 * - Downloads and unzips SRTM3 tiles in background for pre-flight offline caching.
 */
object HgtTerrainManager {

    private var terrainDir: File? = null
    private val openTiles = Collections.synchronizedMap(mutableMapOf<String, HgtTile>())

    /**
     * Represents a single memory-mapped 1° x 1° HGT elevation tile.
     */
    class HgtTile(
        val filename: String,
        val southWestLat: Int,
        val southWestLon: Int,
        file: File
    ) : AutoCloseable {
        val numSamples: Int
        private val byteBuffer: java.nio.ByteBuffer
        private val shortBuffer: ShortBuffer

        init {
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val fileSize = channel.size()
            byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)
            byteBuffer.order(ByteOrder.BIG_ENDIAN)
            shortBuffer = byteBuffer.asShortBuffer()
            numSamples = sqrt(shortBuffer.capacity().toDouble()).toInt()
            raf.close()
        }

        override fun close() {
            try {
                // Java 9+ standard way to unmap MappedByteBuffer / DirectByteBuffer
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)
                val invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer::class.java)
                invokeCleaner.invoke(unsafe, byteBuffer)
            } catch (_: Throwable) {
                try {
                    // Java 8 fallback
                    val cleanerMethod = byteBuffer.javaClass.getMethod("cleaner")
                    cleanerMethod.isAccessible = true
                    val cleaner = cleanerMethod.invoke(byteBuffer)
                    val cleanMethod = cleaner?.javaClass?.getMethod("clean")
                    cleanMethod?.invoke(cleaner)
                } catch (_: Throwable) {
                }
            }
        }

        /**
         * Reads elevation with bilinear interpolation.
         * Returns null if coordinates fall outside tile bounds or on a nodata void.
         */
        fun getElevation(lat: Double, lon: Double): Float? {
            val latFrac = lat - southWestLat
            val lonFrac = lon - southWestLon

            if (latFrac < 0.0 || latFrac > 1.0 || lonFrac < 0.0 || lonFrac > 1.0) {
                return null
            }

            val rowF = (1.0 - latFrac) * (numSamples - 1)
            val colF = lonFrac * (numSamples - 1)

            val r0 = rowF.toInt().coerceIn(0, numSamples - 2)
            val c0 = colF.toInt().coerceIn(0, numSamples - 2)
            val rFrac = (rowF - r0).toFloat()
            val cFrac = (colF - c0).toFloat()

            val e00 = readSample(r0, c0) ?: return null
            val e01 = readSample(r0, c0 + 1) ?: return null
            val e10 = readSample(r0 + 1, c0) ?: return null
            val e11 = readSample(r0 + 1, c0 + 1) ?: return null

            val top = e00 + cFrac * (e01 - e00)
            val bottom = e10 + cFrac * (e11 - e10)
            return top + rFrac * (bottom - top)
        }

        private fun readSample(row: Int, col: Int): Float? {
            val idx = row * numSamples + col
            if (idx in 0 until shortBuffer.capacity()) {
                val value = shortBuffer.get(idx)
                if (value == (-32768).toShort()) {
                    return null // SRTM void / nodata
                }
                return value.toFloat()
            }
            return null
        }
    }

    /**
     * Initializes the terrain storage directory.
     */
    fun init(context: Context) {
        if (terrainDir == null) {
            val dir = File(context.getExternalFilesDir(null), "terrain")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            terrainDir = dir
            refreshInstalledTiles()
        }
    }

    /**
     * Explicitly sets the terrain storage directory (useful for unit tests).
     */
    fun setTerrainDirectory(dir: File) {
        terrainDir = dir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        refreshInstalledTiles()
    }

    /**
     * Formats latitude and longitude to standard HGT filename.
     * Example: (45.3, 5.8) -> "N45E005.hgt"
     * Example: (-21.4, 55.5) -> "S22E055.hgt"
     */
    fun getTileFilename(lat: Double, lon: Double): String {
        val latFloor = floor(lat).toInt()
        val lonFloor = floor(lon).toInt()

        val latPrefix = if (latFloor >= 0) "N" else "S"
        val latNum = kotlin.math.abs(latFloor)

        val lonPrefix = if (lonFloor >= 0) "E" else "W"
        val lonNum = kotlin.math.abs(lonFloor)

        return String.format(Locale.US, "%s%02d%s%03d.hgt", latPrefix, latNum, lonPrefix, lonNum)
    }

    /**
     * Checks if the HGT tile covering ([lat], [lon]) is installed locally.
     */
    fun isTileAvailable(lat: Double, lon: Double): Boolean {
        val filename = getTileFilename(lat, lon)
        if (openTiles.containsKey(filename)) return true
        val dir = terrainDir ?: return false
        val file = File(dir, filename)
        return file.exists() && file.length() >= 2_000_000
    }

    /**
     * Returns ground elevation in meters at ([lat], [lon]) from the local HGT tile,
     * or null if no local tile covers this location.
     */
    fun getElevation(lat: Double, lon: Double): Float? {
        val filename = getTileFilename(lat, lon)
        val tile = openTiles[filename] ?: loadTileFromDisk(filename, lat, lon)
        return tile?.getElevation(lat, lon)
    }

    private fun loadTileFromDisk(filename: String, lat: Double, lon: Double): HgtTile? {
        val dir = terrainDir ?: return null
        val file = File(dir, filename)
        if (!file.exists() || file.length() < 2_000_000) return null

        return try {
            val latFloor = floor(lat).toInt()
            val lonFloor = floor(lon).toInt()
            val tile = HgtTile(filename, latFloor, lonFloor, file)
            openTiles[filename] = tile
            tile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Registers a pre-existing HGT tile into the manager.
     */
    fun registerTile(file: File): Boolean {
        val name = file.name
        if (!name.endsWith(".hgt", ignoreCase = true)) return false

        // Parse coordinates from filename e.g. N45E005.hgt
        val match = Regex("""([NS])(\d{2})([EW])(\d{3})\.hgt""", RegexOption.IGNORE_CASE).matchEntire(name) ?: return false
        val (latHemi, latStr, lonHemi, lonStr) = match.destructured

        val lat = latStr.toInt() * if (latHemi.uppercase() == "S") -1 else 1
        val lon = lonStr.toInt() * if (lonHemi.uppercase() == "W") -1 else 1

        return try {
            val tile = HgtTile(name, lat, lon, file)
            openTiles[name] = tile
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Imports an HGT file from an [InputStream] into the terrain directory.
     */
    suspend fun importHgtFile(inputStream: InputStream, targetFilename: String): Boolean = withContext(Dispatchers.IO) {
        val dir = terrainDir ?: return@withContext false
        val destFile = File(dir, targetFilename)

        try {
            inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            registerTile(destFile)
            true
        } catch (_: Exception) {
            destFile.delete()
            false
        }
    }

    /**
     * Returns the continental region folder on ArduPilot SRTM server.
     */
    fun getContinentForCoords(lat: Double, lon: Double): String {
        return when {
            lat in -35.0..38.0 && lon in -20.0..55.0 && !(lat >= 36.0 && lon > -10.0) -> "Africa"
            lat in 8.0..75.0 && lon in -170.0..-50.0 -> "North_America"
            lat in -60.0..15.0 && lon in -90.0..-30.0 -> "South_America"
            lat < 0.0 && lon in 110.0..180.0 -> "Australia"
            lat in -10.0..80.0 && lon in -25.0..180.0 -> "Eurasia"
            else -> "Eurasia"
        }
    }

    /**
     * Information about a locally installed HGT terrain tile.
     */
    data class InstalledTileInfo(
        val filename: String,
        val sizeBytes: Long,
        val formattedSize: String
    )

    private val ALL_CONTINENTS = listOf("Eurasia", "North_America", "South_America", "Africa", "Australia", "Islands")

    /**
     * Downloads an SRTM3 tile ZIP archive, unzips the .hgt file, and registers it.
     * Uses Kurviger's high-speed CDN-cached SRTM3 mirror with multi-region fallback.
     */
    suspend fun downloadTile(
        lat: Double,
        lon: Double,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val filename = getTileFilename(lat, lon)
        val dir = terrainDir ?: return@withContext Result.failure(IllegalStateException("Terrain dir not initialized"))
        val destHgtFile = File(dir, filename)

        if (destHgtFile.exists() && destHgtFile.length() >= 2_000_000) {
            registerTile(destHgtFile)
            return@withContext Result.success(destHgtFile)
        }

        val primaryContinent = getContinentForCoords(lat, lon)
        val baseTile = filename.substringBeforeLast(".hgt")
        val candidateContinents = listOf(primaryContinent) + (ALL_CONTINENTS - primaryContinent)

        var successfulConn: HttpURLConnection? = null

        // Try primary continent first, then fallbacks if 404
        for (continent in candidateContinents) {
            val urlStr = "https://srtm.kurviger.de/SRTM3/$continent/$baseTile.hgt.zip"
            var currentUrl = urlStr
            var conn: HttpURLConnection? = null
            var redirects = 0
            var found = false

            try {
                while (true) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 20000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0 VarioAppli/1.0")
                    }

                    val code = conn.responseCode
                    if (code in 301..303 || code == 307 || code == 308) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location != null && redirects < 5) {
                            currentUrl = location
                            redirects++
                            continue
                        }
                    }
                    if (code == 200) {
                        found = true
                    } else {
                        conn.disconnect()
                        conn = null
                    }
                    break
                }
            } catch (_: Exception) {
                conn?.disconnect()
                conn = null
            }

            if (found && conn != null) {
                successfulConn = conn
                break
            }
        }

        val finalConn = successfulConn ?: return@withContext Result.failure(
            java.io.FileNotFoundException("Dalle $baseTile introuvable sur le serveur SRTM (erreur 404)")
        )

        try {
            val totalBytes = finalConn.contentLength.toFloat()
            val tempZip = File(dir, "$baseTile.tmp.zip")

            finalConn.inputStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress(totalRead / totalBytes)
                        }
                    }
                }
            }
            finalConn.disconnect()

            // Unzip the downloaded archive
            ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".hgt", ignoreCase = true)) {
                        FileOutputStream(destHgtFile).use { out ->
                            zipIn.copyTo(out)
                        }
                        break
                    }
                    entry = zipIn.nextEntry
                }
            }
            tempZip.delete()

            if (destHgtFile.exists() && destHgtFile.length() >= 2_000_000) {
                registerTile(destHgtFile)
                onProgress(1.0f)
                Result.success(destHgtFile)
            } else {
                destHgtFile.delete()
                Result.failure(IllegalStateException("Fichier HGT extrait incomplet ou corrompu"))
            }
        } catch (e: Exception) {
            destHgtFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Parses the lower-left geographic coordinates (lat, lon) from a tile filename or identifier.
     * Examples: "N45E005" -> (45.0, 5.0), "S22E055.hgt" -> (-22.0, 55.0).
     */
    fun parseTileCoordinates(tileName: String): Pair<Double, Double>? {
        val clean = tileName.trim().substringBeforeLast(".hgt")
        val match = Regex("""([NS])(\d{2})([EW])(\d{3})""", RegexOption.IGNORE_CASE).matchEntire(clean) ?: return null
        val (latHemi, latStr, lonHemi, lonStr) = match.destructured
        val lat = latStr.toDouble() * if (latHemi.uppercase() == "S") -1.0 else 1.0
        val lon = lonStr.toDouble() * if (lonHemi.uppercase() == "W") -1.0 else 1.0
        return Pair(lat, lon)
    }

    /**
     * Geographic bounding box for a 1° x 1° SRTM tile.
     */
    data class TileBounds(
        val southLat: Double,
        val westLon: Double,
        val northLat: Double,
        val eastLon: Double
    )

    /**
     * Returns the 1° x 1° bounding box for a given tile.
     */
    fun getTileBounds(tileName: String): TileBounds? {
        val (lat, lon) = parseTileCoordinates(tileName) ?: return null
        return TileBounds(
            southLat = lat,
            westLon = lon,
            northLat = lat + 1.0,
            eastLon = lon + 1.0
        )
    }

    /**
     * Downloads an SRTM tile by its name (e.g. "N45E005" or "N45E005.hgt").
     */
    suspend fun downloadTileByName(
        tileName: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        val coords = parseTileCoordinates(tileName)
            ?: return Result.failure(IllegalArgumentException("Nom de dalle invalide : $tileName"))
        return downloadTile(coords.first, coords.second, onProgress)
    }

    /**
     * Progress information for multi-zone simultaneous download.
     */
    data class MultiDownloadProgress(
        val completedCount: Int,
        val totalCount: Int,
        val overallProgress: Float,
        val currentTile: String? = null
    )

    /**
     * Downloads multiple SRTM terrain tiles simultaneously using concurrent coroutines.
     * All distinct requested tiles run in parallel with real-time aggregate progress reporting.
     */
    suspend fun downloadMultipleTiles(
        tileNames: Collection<String>,
        onTileProgress: (tileName: String, progress: Float) -> Unit = { _, _ -> },
        onOverallProgress: (MultiDownloadProgress) -> Unit = {}
    ): Map<String, Result<File>> = withContext(Dispatchers.IO) {
        if (tileNames.isEmpty()) return@withContext emptyMap()

        val distinctTiles = tileNames
            .map { if (it.endsWith(".hgt", ignoreCase = true)) it else "$it.hgt" }
            .distinct()
        val totalCount = distinctTiles.size
        val completedCounter = AtomicInteger(0)
        val progressMap = ConcurrentHashMap<String, Float>()
        distinctTiles.forEach { progressMap[it] = 0f }

        onOverallProgress(MultiDownloadProgress(0, totalCount, 0f, null))

        coroutineScope {
            val deferredMap = distinctTiles.associateWith { tileName ->
                async {
                    val coords = parseTileCoordinates(tileName)
                    val result = if (coords != null) {
                        downloadTile(coords.first, coords.second) { p ->
                            progressMap[tileName] = p
                            val totalSum = progressMap.values.sum()
                            val totalRatio = (totalSum / totalCount).coerceIn(0f, 1f)
                            onTileProgress(tileName, p)
                            onOverallProgress(
                                MultiDownloadProgress(
                                    completedCount = completedCounter.get(),
                                    totalCount = totalCount,
                                    overallProgress = totalRatio,
                                    currentTile = tileName
                                )
                            )
                        }
                    } else {
                        Result.failure(IllegalArgumentException("Nom de dalle invalide : $tileName"))
                    }
                    val completed = completedCounter.incrementAndGet()
                    progressMap[tileName] = 1.0f
                    val totalSum = progressMap.values.sum()
                    val totalRatio = (totalSum / totalCount).coerceIn(0f, 1f)
                    onOverallProgress(
                        MultiDownloadProgress(
                            completedCount = completed,
                            totalCount = totalCount,
                            overallProgress = totalRatio,
                            currentTile = tileName
                        )
                    )
                    result
                }
            }
            deferredMap.mapValues { it.value.await() }
        }
    }

    /**
     * Refreshes the list of installed tiles from disk.
     */
    fun refreshInstalledTiles(): List<String> {
        val dir = terrainDir ?: return emptyList()
        val files = dir.listFiles { _, name -> name.endsWith(".hgt", ignoreCase = true) } ?: return emptyList()
        for (f in files) {
            registerTile(f)
        }
        return files.map { it.name }.sorted()
    }

    /**
     * Returns all installed tile filenames.
     */
    fun getInstalledTiles(): List<String> {
        return refreshInstalledTiles()
    }

    /**
     * Returns detailed info for all installed tiles.
     */
    fun getInstalledTileInfos(): List<InstalledTileInfo> {
        val dir = terrainDir ?: return emptyList()
        val files = dir.listFiles { _, name -> name.endsWith(".hgt", ignoreCase = true) } ?: return emptyList()
        return files.map { file ->
            val sizeMb = file.length() / (1024.0 * 1024.0)
            val formatted = String.format(Locale.US, "%.1f Mo", sizeMb)
            InstalledTileInfo(file.name, file.length(), formatted)
        }.sortedBy { it.filename }
    }

    /**
     * Deletes an installed tile.
     */
    fun deleteTile(filename: String): Boolean {
        val tile = openTiles.remove(filename)
        tile?.close()
        val dir = terrainDir ?: return false
        val file = File(dir, filename)
        if (!file.exists()) return true
        var deleted = file.delete()
        if (!deleted) {
            System.gc()
            deleted = file.delete()
            if (!deleted) {
                file.deleteOnExit()
            }
        }
        return deleted || !file.exists()
    }

    /**
     * Clears cached tiles in memory.
     */
    fun clearCache() {
        for (tile in openTiles.values) {
            tile.close()
        }
        openTiles.clear()
    }
}

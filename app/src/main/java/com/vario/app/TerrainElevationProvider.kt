package com.vario.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Represents a regular 2D grid of terrain elevations covering a geographic bounding box.
 */
data class ElevationGrid(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val rows: Int,
    val cols: Int,
    val elevations: FloatArray
) {
    /**
     * Returns the terrain elevation at ([row], [col]), or the default valley baseline if out of bounds.
     */
    fun getElevationAt(row: Int, col: Int): Float {
        if (row in 0 until rows && col in 0 until cols) {
            return elevations[row * cols + col]
        }
        return TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ElevationGrid) return false
        return minLat == other.minLat &&
                maxLat == other.maxLat &&
                minLon == other.minLon &&
                maxLon == other.maxLon &&
                rows == other.rows &&
                cols == other.cols &&
                elevations.contentEquals(other.elevations)
    }

    override fun hashCode(): Int {
        var result = minLat.hashCode()
        result = 31 * result + maxLat.hashCode()
        result = 31 * result + minLon.hashCode()
        result = 31 * result + maxLon.hashCode()
        result = 31 * result + rows
        result = 31 * result + cols
        result = 31 * result + elevations.contentHashCode()
        return result
    }
}

/**
 * High-performance terrain elevation and relief dénivellation provider.
 *
 * Capabilities:
 * - Non-blocking local terrain relief estimation using known obstacle peaks
 *   from [ObstacleRoutingEngine] and regional valley baseline (250m default).
 * - Thread-safe in-memory LRU cache backed by asynchronous online elevation fetching
 *   via Open-Meteo elevation API.
 * - Accurate dénivellation calculation: Delta H = pilotAltM - groundElevation(target).
 * - Batch queries and regular grid generation for reachability heatmaps.
 * - Smart peak elevation detection within a configurable search radius (e.g. 50m).
 */
object TerrainElevationProvider {

    const val DEFAULT_VALLEY_BASELINE_M = 250f
    private const val MAX_CACHE_SIZE = 8192

    // Quantize coordinates to ~110m grid (3 decimals: 0.001 deg ~ 111m)
    fun quantizeCoord(coord: Double): Long = (coord * 1000.0).roundToInt().toLong()

    private val elevationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Pair<Long, Long>, Float>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Float>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private val pendingRequests = Collections.synchronizedSet(mutableSetOf<Pair<Long, Long>>())
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TerrainElevationFetcher").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Registers a listener triggered when online elevation data is fetched.
     */
    fun addListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Unregisters a listener.
     */
    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        mainHandler.post {
            val copy = synchronized(listeners) { ArrayList(listeners) }
            for (listener in copy) {
                listener()
            }
        }
    }

    /**
     * Estimates elevation locally using registered obstacle peaks and regional valley baseline.
     * Uses a smooth bell-shaped elevation decay from peak down to valley baseline.
     */
    fun estimateLocalElevation(lat: Double, lon: Double): Float {
        var estimatedAlt = DEFAULT_VALLEY_BASELINE_M
        val obstacles = ObstacleRoutingEngine.getObstacles()

        for (obs in obstacles) {
            val dLat = (lat - obs.latitude) * 111139.0
            val dLon = (lon - obs.longitude) * 111139.0 * cos(Math.toRadians(lat))
            val distM = sqrt(dLat * dLat + dLon * dLon).toFloat()

            // Peak influence radius (covers mountain flanks up to 2.5x peak radius or min 2500m)
            val radius = max(obs.radiusM * 2.5f, 2500f)
            if (distM < radius) {
                val normDist = distM / radius
                val factor = exp(-3.0 * normDist * normDist).toFloat()
                val peakDiff = max(0f, obs.peakElevationM - DEFAULT_VALLEY_BASELINE_M)
                val contribution = DEFAULT_VALLEY_BASELINE_M + peakDiff * factor
                if (contribution > estimatedAlt) {
                    estimatedAlt = contribution
                }
            }
        }
        return estimatedAlt
    }

    /**
     * Returns ground elevation in meters at [lat], [lon].
     * Checks local offline HGT terrain tiles first (instant, <50 ns).
     * If cached, returns immediately.
     * Otherwise returns immediate local relief estimation and triggers an asynchronous fetch.
     */
    fun getElevation(lat: Double, lon: Double): Float {
        // Priority 1: High-precision offline SRTM/HGT tile (zero network, instant)
        val hgtElev = HgtTerrainManager.getElevation(lat, lon)
        if (hgtElev != null) {
            return hgtElev
        }

        val key = Pair(quantizeCoord(lat), quantizeCoord(lon))
        val cached = elevationCache[key]
        if (cached != null) {
            return cached
        }

        // Enqueue async fetch if not already in flight
        if (pendingRequests.add(key)) {
            fetchOnlineElevationAsync(key, lat, lon)
        }

        return estimateLocalElevation(lat, lon)
    }

    /**
     * Computes dénivellation (vertical relief clearance / drop) in meters:
     * Delta H = pilotAltM - groundElevation(targetLat, targetLon).
     */
    fun getDenivellation(pilotAltM: Float, targetLat: Double, targetLon: Double): Float {
        val groundElev = getElevation(targetLat, targetLon)
        return pilotAltM - groundElev
    }

    /**
     * Batch-fetches elevations for [latitudes] and [longitudes].
     * Prioritizes local offline HGT tiles, then in-memory cache, then batches uncached queries
     * to Open-Meteo in chunks of up to 100 coordinates, and falls back to local terrain relief estimation.
     */
    suspend fun getElevationBatch(
        latitudes: DoubleArray,
        longitudes: DoubleArray
    ): FloatArray = withContext(Dispatchers.IO) {
        require(latitudes.size == longitudes.size) { "Latitudes and longitudes must have same size" }
        val size = latitudes.size
        val result = FloatArray(size)
        val missingIndices = mutableListOf<Int>()

        for (i in 0 until size) {
            val lat = latitudes[i]
            val lon = longitudes[i]

            // 1. Check offline HGT file
            val hgtElev = HgtTerrainManager.getElevation(lat, lon)
            if (hgtElev != null) {
                result[i] = hgtElev
                val key = Pair(quantizeCoord(lat), quantizeCoord(lon))
                elevationCache[key] = hgtElev
                continue
            }

            // 2. Check memory cache
            val key = Pair(quantizeCoord(lat), quantizeCoord(lon))
            val cached = elevationCache[key]
            if (cached != null) {
                result[i] = cached
            } else {
                missingIndices.add(i)
            }
        }

        if (missingIndices.isNotEmpty()) {
            var anyFetched = false
            // Open-Meteo accepts up to 100 coordinates per batch request
            val chunks = missingIndices.chunked(100)
            for (chunk in chunks) {
                try {
                    val latStr = chunk.joinToString(",") { String.format(Locale.US, "%.4f", latitudes[it]) }
                    val lonStr = chunk.joinToString(",") { String.format(Locale.US, "%.4f", longitudes[it]) }
                    val urlStr = "https://api.open-meteo.com/v1/elevation?latitude=$latStr&longitude=$lonStr"
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "VarioAppli/1.0")

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val elevationArray = json.optJSONArray("elevation")
                        if (elevationArray != null) {
                            val count = minOf(chunk.size, elevationArray.length())
                            for (k in 0 until count) {
                                val idx = chunk[k]
                                val fetchedAlt = elevationArray.getDouble(k).toFloat()
                                val key = Pair(quantizeCoord(latitudes[idx]), quantizeCoord(longitudes[idx]))
                                elevationCache[key] = fetchedAlt
                                result[idx] = fetchedAlt
                            }
                            anyFetched = true
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    // Fall back to local estimation for failed chunk
                }
            }

            // Fill any remaining unassigned missing indices with local relief estimation
            for (idx in missingIndices) {
                val key = Pair(quantizeCoord(latitudes[idx]), quantizeCoord(longitudes[idx]))
                if (!elevationCache.containsKey(key)) {
                    val fallback = estimateLocalElevation(latitudes[idx], longitudes[idx])
                    result[idx] = fallback
                }
            }

            if (anyFetched) {
                notifyListeners()
            }
        }

        result
    }

    /**
     * Fetches elevations for a regular 2D grid within a bounding box centered on ([centerLat], [centerLon])
     * with radius [radiusM] meters.
     * Returns an [ElevationGrid] with [gridSize] rows and [gridSize] columns.
     */
    suspend fun getElevationGrid(
        centerLat: Double,
        centerLon: Double,
        radiusM: Float,
        gridSize: Int = 64
    ): ElevationGrid = withContext(Dispatchers.Default) {
        val deltaLat = radiusM / 111139.0
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val deltaLon = radiusM / (111139.0 * cosLat)

        val minLat = centerLat - deltaLat
        val maxLat = centerLat + deltaLat
        val minLon = centerLon - deltaLon
        val maxLon = centerLon + deltaLon

        val totalPoints = gridSize * gridSize
        val lats = DoubleArray(totalPoints)
        val lons = DoubleArray(totalPoints)

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        val steps = (gridSize - 1).coerceAtLeast(1)

        // Row 0 is north (maxLat), row (gridSize-1) is south (minLat)
        // Col 0 is west (minLon), col (gridSize-1) is east (maxLon)
        for (r in 0 until gridSize) {
            val lat = maxLat - (r.toDouble() / steps) * latSpan
            val rowOffset = r * gridSize
            for (c in 0 until gridSize) {
                val lon = minLon + (c.toDouble() / steps) * lonSpan
                val idx = rowOffset + c
                lats[idx] = lat
                lons[idx] = lon
            }
        }

        val elevations = getElevationBatch(lats, lons)
        ElevationGrid(minLat, maxLat, minLon, maxLon, gridSize, gridSize, elevations)
    }

    /**
     * Finds the highest terrain elevation within [radiusM] meters around ([centerLat], [centerLon]).
     * Samples a 5x5 grid around the center, filters points within the circle/box (20m x 20m), and returns the peak elevation.
     */
    suspend fun findPeakElevation(
        centerLat: Double,
        centerLon: Double,
        radiusM: Float = 20f
    ): Float = withContext(Dispatchers.Default) {
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val deltaLat = radiusM / 111139.0
        val deltaLon = radiusM / (111139.0 * cosLat)

        val sampleLats = mutableListOf<Double>()
        val sampleLons = mutableListOf<Double>()

        for (i in -2..2) {
            val lat = centerLat + (i / 2.0) * deltaLat
            for (j in -2..2) {
                val lon = centerLon + (j / 2.0) * deltaLon
                val dLatM = (lat - centerLat) * 111139.0
                val dLonM = (lon - centerLon) * 111139.0 * cosLat
                val dist = sqrt(dLatM * dLatM + dLonM * dLonM)
                if (dist <= radiusM * 1.05) {
                    sampleLats.add(lat)
                    sampleLons.add(lon)
                }
            }
        }

        // Always include center point
        sampleLats.add(centerLat)
        sampleLons.add(centerLon)

        val elevations = getElevationBatch(sampleLats.toDoubleArray(), sampleLons.toDoubleArray())
        elevations.maxOrNull() ?: getElevation(centerLat, centerLon)
    }

    /**
     * Asynchronously fetches elevation from Open-Meteo elevation API.
     */
    private fun fetchOnlineElevationAsync(key: Pair<Long, Long>, lat: Double, lon: Double) {
        networkExecutor.execute {
            try {
                val urlStr = String.format(
                    java.util.Locale.US,
                    "https://api.open-meteo.com/v1/elevation?latitude=%.4f&longitude=%.4f",
                    lat,
                    lon
                )
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "VarioAppli/1.0")

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val elevationArray = json.optJSONArray("elevation")
                    if (elevationArray != null && elevationArray.length() > 0) {
                        val fetchedAlt = elevationArray.getDouble(0).toFloat()
                        elevationCache[key] = fetchedAlt
                        notifyListeners()
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Offline or timeout; local model serves as fallback
            } finally {
                pendingRequests.remove(key)
            }
        }
    }

    /**
     * Manually registers or overrides an elevation value in cache.
     */
    fun putElevationInCache(lat: Double, lon: Double, elevationM: Float) {
        val key = Pair(quantizeCoord(lat), quantizeCoord(lon))
        elevationCache[key] = elevationM
    }

    /**
     * Clears cached elevations and pending requests.
     */
    fun clearCache() {
        elevationCache.clear()
        pendingRequests.clear()
    }
}

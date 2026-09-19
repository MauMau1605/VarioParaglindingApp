package com.vario.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * High-performance terrain elevation and relief dénivellation provider.
 *
 * Capabilities:
 * - Non-blocking local terrain relief estimation using known obstacle peaks
 *   from [ObstacleRoutingEngine] and regional valley baseline (250m default).
 * - Thread-safe in-memory LRU cache backed by asynchronous online elevation fetching
 *   via Open-Meteo elevation API.
 * - Accurate dénivellation calculation: Delta H = pilotAltM - groundElevation(target).
 */
object TerrainElevationProvider {

    const val DEFAULT_VALLEY_BASELINE_M = 250f
    private const val MAX_CACHE_SIZE = 512

    // Quantize coordinates to ~110m grid (3 decimals: 0.001 deg ~ 111m)
    private fun quantizeCoord(coord: Double): Long = (coord * 1000.0).roundToInt().toLong()

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
     * If cached, returns immediately.
     * Otherwise returns immediate local relief estimation and triggers an asynchronous fetch.
     */
    fun getElevation(lat: Double, lon: Double): Float {
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

package com.vario.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Custom osmdroid overlay that renders a SpotAir-style terrain-aware reachability heatmap.
 *
 * Visual design:
 * - Colors every cell based on Above Ground Level (AGL) clearance.
 * - Deep Blue (>1200m) -> Green -> Yellow -> Red (<50m) -> Transparent (unreachable/obstructed).
 * - Takes into account terrain elevation along the line-of-sight glide path (mountain ridges cast glide shadows).
 * - Bilinearly filtered 64x64 bitmap provides smooth gradients without polygon rendering overhead.
 *
 * Fast path design:
 * - Zero dynamic memory allocations during [draw] (pre-allocated Bitmap, Matrix, Paint, Point, and arrays).
 * - Heavy grid calculations run asynchronously on [Dispatchers.Default].
 * - Updates are throttled using dirty-state thresholds (>100m translation, >30m altitude, >0.2 glide ratio).
 */
class ReachabilityHeatmapOverlay(
    private val gridSize: Int = 64
) : Overlay() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var computationJob: Job? = null

    // Pre-allocated drawing resources (Zero heap allocation in draw())
    private val bitmap = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888)
    private val pixelBuffer = IntArray(gridSize * gridSize)
    private val matrix = Matrix()
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    private val srcPts = floatArrayOf(
        0f, 0f,                           // Top-Left
        gridSize.toFloat(), 0f,           // Top-Right
        gridSize.toFloat(), gridSize.toFloat(), // Bottom-Right
        0f, gridSize.toFloat()            // Bottom-Left
    )
    private val dstPts = FloatArray(8)

    private val tlGeo = GeoPoint(0.0, 0.0)
    private val trGeo = GeoPoint(0.0, 0.0)
    private val brGeo = GeoPoint(0.0, 0.0)
    private val blGeo = GeoPoint(0.0, 0.0)

    private val p1 = Point()
    private val p2 = Point()
    private val p3 = Point()
    private val p4 = Point()

    // Overlay state
    private var currentPosition: GeoPoint? = null
    private var altitudeM: Float = 0f
    private var glideRatio: Float = 8.5f
    private var safetyMarginM: Float = 50f
    private var isEnabled: Boolean = true

    private var activeGrid: ElevationGrid? = null
    @Volatile
    private var hasBitmap: Boolean = false
    @Volatile
    private var isComputing: Boolean = false

    // Dirty-state thresholds
    private var lastCalculatedLat: Double = 0.0
    private var lastCalculatedLon: Double = 0.0
    private var lastCalculatedAlt: Float = -9999f
    private var lastCalculatedGr: Float = -1f

    private var attachedMapView: MapView? = null

    /**
     * Updates the pilot/departure state and triggers recomputation if dirty thresholds are exceeded.
     */
    fun updateState(
        position: GeoPoint?,
        altM: Float,
        gr: Float,
        safetyMargin: Float = 50f,
        enabled: Boolean,
        mapView: MapView? = null
    ) {
        currentPosition = position
        altitudeM = altM
        glideRatio = gr
        safetyMarginM = safetyMargin
        isEnabled = enabled
        if (mapView != null) {
            attachedMapView = mapView
        }

        if (!enabled || position == null || position.latitude == 0.0 && position.longitude == 0.0) {
            return
        }

        checkAndTriggerRecompute(position, altM, gr, safetyMargin)
    }

    private fun checkAndTriggerRecompute(
        pos: GeoPoint,
        altM: Float,
        gr: Float,
        marginM: Float
    ) {
        val distMoved = distanceM(pos.latitude, pos.longitude, lastCalculatedLat, lastCalculatedLon)
        val altDiff = abs(altM - lastCalculatedAlt)
        val grDiff = abs(gr - lastCalculatedGr)

        val isDirty = !hasBitmap || distMoved > 100f || altDiff > 30f || grDiff > 0.2f

        if (isDirty && !isComputing) {
            recomputeAsync(pos.latitude, pos.longitude, altM, gr, marginM)
        }
    }

    private fun recomputeAsync(
        centerLat: Double,
        centerLon: Double,
        altM: Float,
        gr: Float,
        marginM: Float
    ) {
        computationJob?.cancel()
        computationJob = scope.launch {
            isComputing = true
            try {
                // Compute reachable radius based on altitude and glide ratio
                val deltaH = (altM - TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M).coerceAtLeast(100f)
                val radiusM = (deltaH * gr).coerceIn(1500f, 35000f)

                // Fetch elevation grid (uses cache when available)
                val grid = TerrainElevationProvider.getElevationGrid(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusM = radiusM,
                    gridSize = gridSize
                )

                // Compute SpotAir-style reachability colors into pre-allocated buffer
                val config = ReachabilityConfig(
                    glideRatio = gr,
                    safetyMarginM = marginM,
                    checkLineOfSight = true
                )
                ReachabilityEngine.computeReachabilityPixels(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    startAltM = altM,
                    grid = grid,
                    config = config,
                    pixelBuffer = pixelBuffer
                )

                // Update bitmap and dirty markers
                withContext(Dispatchers.Main) {
                    bitmap.setPixels(pixelBuffer, 0, gridSize, 0, 0, gridSize, gridSize)
                    activeGrid = grid
                    hasBitmap = true
                    lastCalculatedLat = centerLat
                    lastCalculatedLon = centerLon
                    lastCalculatedAlt = altM
                    lastCalculatedGr = gr
                    attachedMapView?.postInvalidate()
                }
            } catch (_: Exception) {
                // Ignore cancellation or transient computation failures
            } finally {
                isComputing = false
            }
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled || !hasBitmap) return
        val grid = activeGrid ?: return
        val pj = mapView.projection ?: return

        attachedMapView = mapView

        // Set geographic coordinates of the 4 grid corners
        tlGeo.latitude = grid.maxLat
        tlGeo.longitude = grid.minLon

        trGeo.latitude = grid.maxLat
        trGeo.longitude = grid.maxLon

        brGeo.latitude = grid.minLat
        brGeo.longitude = grid.maxLon

        blGeo.latitude = grid.minLat
        blGeo.longitude = grid.minLon

        // Project geographic coordinates to screen pixels
        pj.toPixels(tlGeo, p1)
        dstPts[0] = p1.x.toFloat()
        dstPts[1] = p1.y.toFloat()

        pj.toPixels(trGeo, p2)
        dstPts[2] = p2.x.toFloat()
        dstPts[3] = p2.y.toFloat()

        pj.toPixels(brGeo, p3)
        dstPts[4] = p3.x.toFloat()
        dstPts[5] = p3.y.toFloat()

        pj.toPixels(blGeo, p4)
        dstPts[6] = p4.x.toFloat()
        dstPts[7] = p4.y.toFloat()

        // Map bitmap quadrangle to projected screen quadrangle (handles arbitrary map rotation/zoom)
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        // Draw heatmap bitmap
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        if (lat1 == 0.0 && lon1 == 0.0) return Float.MAX_VALUE
        val dLat = (lat2 - lat1) * 111139.0
        val cosLat = cos(Math.toRadians((lat1 + lat2) / 2.0))
        val dLon = (lon2 - lon1) * 111139.0 * cosLat
        return sqrt(dLat * dLat + dLon * dLon).toFloat()
    }
}

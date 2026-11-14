package com.vario.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Configuration parameters for paraglider reachability calculation.
 */
data class ReachabilityConfig(
    val glideRatio: Float = 8.5f,
    val safetyMarginM: Float = 50f,
    val checkLineOfSight: Boolean = true
)

/**
 * Pure computational engine for SpotAir-style terrain-aware reachability calculations.
 *
 * Responsibilities:
 * - Maps Above Ground Level (AGL) clearance to SpotAir-compliant color palette.
 * - Computes AGL clearance for regular geographic grids taking into account:
 *   - Pilot/departure altitude MSL
 *   - Distance-dependent glide sink: glideAlt = startAlt - (distance / glideRatio)
 *   - Terrain ground elevation from [ElevationGrid]
 *   - Safety margin
 *   - Line-of-sight terrain collision / ridge shadowing
 * - Completely decoupled from Android UI / osmdroid lifecycle for fast JVM testability.
 */
object ReachabilityEngine {

    // SpotAir-style Color Constants (ARGB format with crisp alpha for discrete raster visibility)
    const val COLOR_DEEP_BLUE = 0x901565C0.toInt()   // >= 1200m AGL (Very high clearance)
    const val COLOR_BLUE      = 0x901E88E5.toInt()   // 800 - 1200m AGL
    const val COLOR_CYAN      = 0x8800ACC1.toInt()   // 500 - 800m AGL
    const val COLOR_GREEN     = 0x8543A047.toInt()   // 300 - 500m AGL
    const val COLOR_YELLOW_GR = 0x859E9D24.toInt()   // 150 - 300m AGL
    const val COLOR_YELLOW    = 0x80F9A825.toInt()   // 80 - 150m AGL
    const val COLOR_ORANGE    = 0x80EF6C00.toInt()   // 50 - 80m AGL
    const val COLOR_RED       = 0x80C62828.toInt()   // 0 - 50m AGL (Critical reachability limit)
    const val COLOR_UNREACH   = 0x00000000           // Unreachable / obstructed / masked (100% transparent)

    /**
     * Computes the maximum reachable horizontal radius in meters covering the entire glide range.
     * Based on start altitude MSL and wing glide ratio, ensuring the bounding grid encompasses
     * the full potential reach down to sea level / valley floors, eliminating square edge truncation.
     */
    fun computeReachableRadiusM(
        startAltM: Float,
        glideRatio: Float,
        safetyFactor: Float = 1.15f
    ): Float {
        val deltaH = startAltM.coerceAtLeast(100f)
        return (deltaH * glideRatio * safetyFactor).coerceIn(1500f, 40000f)
    }

    /**
     * Maps an Above Ground Level clearance in meters to an ARGB color.
     * Uses smooth linear interpolation between thresholds.
     */
    fun aglToColor(aglM: Float): Int {
        if (aglM <= 0f) return COLOR_UNREACH

        return when {
            aglM < 50f -> {
                // Solid, distinct red for reachable terrain near glide limit (0-50m AGL)
                COLOR_RED
            }
            aglM < 80f -> {
                lerpColor(COLOR_RED, COLOR_ORANGE, (aglM - 50f) / 30f)
            }
            aglM < 150f -> {
                lerpColor(COLOR_ORANGE, COLOR_YELLOW, (aglM - 80f) / 70f)
            }
            aglM < 300f -> {
                lerpColor(COLOR_YELLOW, COLOR_YELLOW_GR, (aglM - 150f) / 150f)
            }
            aglM < 500f -> {
                lerpColor(COLOR_YELLOW_GR, COLOR_GREEN, (aglM - 300f) / 200f)
            }
            aglM < 800f -> {
                lerpColor(COLOR_GREEN, COLOR_CYAN, (aglM - 500f) / 300f)
            }
            aglM < 1200f -> {
                lerpColor(COLOR_CYAN, COLOR_BLUE, (aglM - 800f) / 400f)
            }
            aglM < 1600f -> {
                lerpColor(COLOR_BLUE, COLOR_DEEP_BLUE, (aglM - 1200f) / 400f)
            }
            else -> COLOR_DEEP_BLUE
        }
    }

    /**
     * Linearly interpolates each ARGB channel between two colors.
     */
    fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val clampedT = t.coerceIn(0f, 1f)
        val a1 = (c1 ushr 24) and 0xFF
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF

        val a2 = (c2 ushr 24) and 0xFF
        val r2 = (c2 ushr 16) and 0xFF
        val g2 = (c2 ushr 8) and 0xFF
        val b2 = c2 and 0xFF

        val a = (a1 + clampedT * (a2 - a1)).toInt()
        val r = (r1 + clampedT * (r2 - r1)).toInt()
        val g = (g1 + clampedT * (g2 - g1)).toInt()
        val b = (b1 + clampedT * (b2 - b1)).toInt()

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Computes AGL and reachability for each cell of the elevation grid, writing ARGB colors
     * into [pixelBuffer].
     *
     * @param centerLat Center reference latitude (pilot or departure point)
     * @param centerLon Center reference longitude
     * @param startAltM Pilot or departure reference altitude MSL
     * @param grid Terrain elevation grid covering the bounding box
     * @param config Flight parameters (glide ratio, safety margin, line-of-sight toggle)
     * @param pixelBuffer Output buffer of size >= grid.rows * grid.cols
     */
    fun computeReachabilityPixels(
        centerLat: Double,
        centerLon: Double,
        startAltM: Float,
        grid: ElevationGrid,
        config: ReachabilityConfig,
        pixelBuffer: IntArray
    ) {
        val rows = grid.rows
        val cols = grid.cols
        require(pixelBuffer.size >= rows * cols) { "Buffer size (${pixelBuffer.size}) smaller than required ($rows x $cols)" }

        val latSpan = grid.maxLat - grid.minLat
        val lonSpan = grid.maxLon - grid.minLon
        val rSteps = (rows - 1).coerceAtLeast(1)
        val cSteps = (cols - 1).coerceAtLeast(1)

        val centerR = if (latSpan > 0.0) {
            (((grid.maxLat - centerLat) / latSpan) * rSteps).toInt().coerceIn(0, rows - 1)
        } else {
            rows / 2
        }

        val centerC = if (lonSpan > 0.0) {
            (((centerLon - grid.minLon) / lonSpan) * cSteps).toInt().coerceIn(0, cols - 1)
        } else {
            cols / 2
        }

        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)

        for (r in 0 until rows) {
            val lat = grid.maxLat - (r.toDouble() / rSteps) * latSpan
            val dLatM = (lat - centerLat) * 111139.0
            val rowOffset = r * cols

            for (c in 0 until cols) {
                val lon = grid.minLon + (c.toDouble() / cSteps) * lonSpan
                val dLonM = (lon - centerLon) * 111139.0 * cosLat
                val distM = sqrt(dLatM * dLatM + dLonM * dLonM).toFloat()

                val glideAltM = startAltM - (distM / config.glideRatio)
                val terrainM = grid.getElevationAt(r, c)
                val rawAglM = glideAltM - terrainM - config.safetyMarginM

                if (rawAglM <= 0f) {
                    pixelBuffer[rowOffset + c] = COLOR_UNREACH
                    continue
                }

                // Check line-of-sight terrain collision (ridge shadowing / masking)
                var blocked = false
                if (config.checkLineOfSight) {
                    val dRow = r - centerR
                    val dCol = c - centerC
                    val steps = maxOf(abs(dRow), abs(dCol))
                    if (steps > 1) {
                        for (s in 1 until steps) {
                            val stepR = (centerR + (dRow.toDouble() * s) / steps).toInt().coerceIn(0, rows - 1)
                            val stepC = (centerC + (dCol.toDouble() * s) / steps).toInt().coerceIn(0, cols - 1)
                            val frac = s.toFloat() / steps
                            val stepDistM = distM * frac
                            val stepGlideAlt = startAltM - (stepDistM / config.glideRatio)
                            val stepTerrain = grid.getElevationAt(stepR, stepC)
                            // Physical ridge blockage: glide slope collides with intermediate mountain terrain
                            if (stepGlideAlt <= stepTerrain) {
                                blocked = true
                                break
                            }
                        }
                    }
                }

                if (blocked) {
                    // Masked terrain is strictly uncolored (treated purely as out of reach)
                    pixelBuffer[rowOffset + c] = COLOR_UNREACH
                } else {
                    pixelBuffer[rowOffset + c] = aglToColor(rawAglM)
                }
            }
        }
    }
}

package com.vario.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Settings for paraglider glide calculations and map overlays.
 */
data class GlideSettings(
    val glideRatio: Float = 8.5f,
    val isGlideAreaEnabled: Boolean = true,
    val safetyMarginM: Float = 50f
)

/**
 * Persistence manager for glide parameters.
 */
object GlideSettingsManager {
    private const val PREFS_NAME = "vario_glide_prefs"
    private const val KEY_GLIDE_RATIO = "glide_ratio"
    private const val KEY_GLIDE_ENABLED = "glide_area_enabled"
    private const val KEY_SAFETY_MARGIN = "safety_margin_m"

    fun getSettings(context: Context): GlideSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GlideSettings(
            glideRatio = prefs.getFloat(KEY_GLIDE_RATIO, 8.5f),
            isGlideAreaEnabled = prefs.getBoolean(KEY_GLIDE_ENABLED, true),
            safetyMarginM = prefs.getFloat(KEY_SAFETY_MARGIN, 50f)
        )
    }

    fun saveSettings(context: Context, settings: GlideSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_GLIDE_RATIO, settings.glideRatio)
            .putBoolean(KEY_GLIDE_ENABLED, settings.isGlideAreaEnabled)
            .putFloat(KEY_SAFETY_MARGIN, settings.safetyMarginM)
            .apply()
    }
}

/**
 * Custom osmdroid overlay that renders reachable glide areas around the pilot taking
 * terrain dénivellation (altitude - ground elevation) into account.
 *
 * Reachable radius: R = Delta H * GlideRatio where Delta H = (altitudeM - groundElevationM).
 *
 * Difficulty color gradient:
 * - 0.00 to 0.25 (GR / 4): Green (Safe, high altitude margin)
 * - 0.25 to 0.50: Light green / Lime
 * - 0.50 to 0.75: Yellow
 * - 0.75 to 0.90: Orange
 * - 0.90 to 1.00 (within 10% of wing glide capacity): Red
 * - 1.00: Theoretical reach limit
 *
 * Includes concentric safety rings at 25% (Finesse / 4 - Green), 50% (Yellow), and 90% (Red - 10% limit)
 * with distance labels in meters/km.
 *
 * Fast path design: Zero heap allocation in draw loop.
 */
class GlideOverlay(
    private var currentPosition: GeoPoint? = null,
    private var altitudeM: Float = 0f,
    private var groundElevationM: Float = 0f,
    private var glideRatio: Float = 8.5f,
    private var isEnabled: Boolean = true
) : Overlay() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(230, 239, 68, 68) // Outer perimeter red
    }

    private val ringPaint25 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.argb(200, 34, 197, 94) // 25% GR / 4 Green
    }

    private val ringPaint50 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.argb(200, 234, 179, 8) // 50% Yellow
    }

    private val ringPaint90 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.argb(220, 239, 68, 68) // 90% Red
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.WHITE
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val centerPixel = Point()
    private val edgePixel = Point()

    private val gradientColors = intArrayOf(
        Color.argb(90, 34, 197, 94),   // 0.00: Green (Safe, high altitude margin)
        Color.argb(80, 132, 204, 22),  // 0.25: Light green / Lime
        Color.argb(70, 234, 179, 8),   // 0.50: Yellow
        Color.argb(60, 249, 115, 22),  // 0.75: Orange
        Color.argb(55, 239, 68, 68),   // 0.90: Red (within 10% of wing glide capacity)
        Color.argb(0, 239, 68, 68)     // 1.00: Theoretical reach limit / edge fade
    )
    private val gradientStops = floatArrayOf(0.0f, 0.25f, 0.50f, 0.75f, 0.90f, 1.0f)

    private var lastShaderCx = -1f
    private var lastShaderCy = -1f
    private var lastShaderRadius = -1f

    private var lastRadiusM = -1f
    private var lastWingGr = -1f
    private var cachedLabel25 = ""
    private var cachedLabel50 = ""
    private var cachedLabel90 = ""
    private var cachedLabel100 = ""

    fun updateState(
        position: GeoPoint?,
        altM: Float,
        groundAltM: Float,
        gr: Float,
        enabled: Boolean
    ) {
        currentPosition = position
        altitudeM = altM
        groundElevationM = groundAltM
        glideRatio = gr
        isEnabled = enabled
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val pos = currentPosition ?: return

        val deltaH = (altitudeM - groundElevationM).coerceAtLeast(0f)
        if (deltaH < 10f) {
            // Insufficient dénivellation to project a glide area
            return
        }

        // Reachable horizontal radius in meters: R = Delta H * GlideRatio
        val reachableRadiusM = deltaH * glideRatio

        val pj = mapView.projection ?: return
        pj.toPixels(pos, centerPixel)

        // Project an offset point to accurately compute screen radius regardless of zoom/latitude
        val edgeGeoPoint = pos.destinationPoint(reachableRadiusM.toDouble(), 90.0)
        pj.toPixels(edgeGeoPoint, edgePixel)

        val radiusPx = hypot(
            (edgePixel.x - centerPixel.x).toDouble(),
            (edgePixel.y - centerPixel.y).toDouble()
        ).toFloat()

        if (radiusPx < 5f) return

        val cx = centerPixel.x.toFloat()
        val cy = centerPixel.y.toFloat()

        // Cache RadialGradient to avoid object allocations in hot draw path
        if (cx != lastShaderCx || cy != lastShaderCy || radiusPx != lastShaderRadius) {
            lastShaderCx = cx
            lastShaderCy = cy
            lastShaderRadius = radiusPx
            fillPaint.shader = RadialGradient(
                cx,
                cy,
                radiusPx,
                gradientColors,
                gradientStops,
                Shader.TileMode.CLAMP
            )
        }

        // Update cached label strings only when radius or glide ratio changes by > 5m
        if (abs(reachableRadiusM - lastRadiusM) > 5f || lastWingGr != glideRatio) {
            lastRadiusM = reachableRadiusM
            lastWingGr = glideRatio
            cachedLabel25 = "25% (Fin/4): " + formatDistance(reachableRadiusM * 0.25f)
            cachedLabel50 = "50%: " + formatDistance(reachableRadiusM * 0.50f)
            cachedLabel90 = "90% (Limite): " + formatDistance(reachableRadiusM * 0.90f)
            cachedLabel100 = "Max (Fin. " + String.format(Locale.US, "%.1f", glideRatio) + "): " + formatDistance(reachableRadiusM)
        }

        // 1. Draw radial gradient fill
        canvas.drawCircle(cx, cy, radiusPx, fillPaint)

        // 2. Draw outer perimeter stroke (100% reach)
        canvas.drawCircle(cx, cy, radiusPx, strokePaint)

        // 3. Draw concentric safety rings: 90%, 50%, 25%
        canvas.drawCircle(cx, cy, radiusPx * 0.90f, ringPaint90)
        canvas.drawCircle(cx, cy, radiusPx * 0.50f, ringPaint50)
        canvas.drawCircle(cx, cy, radiusPx * 0.25f, ringPaint25)

        // 4. Render distance labels along east radial axis if screen radius allows
        if (radiusPx > 60f) {
            canvas.drawText(cachedLabel25, cx + (radiusPx * 0.25f) + 8f, cy - 6f, textPaint)
        }
        if (radiusPx > 110f) {
            canvas.drawText(cachedLabel50, cx + (radiusPx * 0.50f) + 8f, cy - 6f, textPaint)
        }
        if (radiusPx > 160f) {
            canvas.drawText(cachedLabel90, cx + (radiusPx * 0.90f) + 8f, cy - 6f, textPaint)
        }
        if (radiusPx > 220f) {
            canvas.drawText(cachedLabel100, cx + radiusPx + 8f, cy - 6f, textPaint)
        }
    }

    private fun formatDistance(distM: Float): String {
        return if (distM >= 1000f) {
            String.format(Locale.US, "%.1f km", distM / 1000f)
        } else {
            String.format(Locale.US, "%d m", distM.toInt())
        }
    }
}

package com.vario.app

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Navigation mode on the Map.
 */
enum class MapNavigationMode {
    LIVE_PILOT,         // From my current position to target
    PLANNING_ITINERARY, // Setup 2 points (Start & Finish) + optional intermediate waypoints
    ZONE_DOWNLOAD       // Interactive cartographic selection for multi-zone terrain download
}

/**
 * Point type being placed when tapping/long-pressing in Planning mode.
 */
enum class PlanningAction {
    SET_START,
    ADD_WAYPOINT,
    SET_FINISH
}

/**
 * Formats vertical speed with explicit sign and one decimal place.
 */
private fun formatVzLocal(vz: Float): String = String.format(Locale.US, "%+.1f", vz)

/**
 * Generates a clean custom pilot location icon (vibrant cyan beacon).
 */
private fun createPilotIcon(context: Context): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer glow halo
    paint.color = AndroidColor.argb(80, 14, 165, 233)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner bright cyan circle
    paint.color = AndroidColor.rgb(14, 165, 233)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Center white dot
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 6f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates a high-visibility target landing / goal waypoint icon (crimson ring with gold bullseye).
 */
private fun createTargetIcon(context: Context): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer red glow halo
    paint.color = AndroidColor.argb(90, 239, 68, 68)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner red circle
    paint.color = AndroidColor.rgb(239, 68, 68)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Center gold bullseye
    paint.color = AndroidColor.rgb(250, 204, 21)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 6f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates an Emerald green start waypoint icon with letter 'D'.
 */
private fun createStartIcon(context: Context): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer glow halo
    paint.color = AndroidColor.argb(80, 34, 197, 94)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner emerald green circle
    paint.color = AndroidColor.rgb(34, 197, 94)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Letter D
    paint.color = AndroidColor.WHITE
    paint.textSize = 24f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    val yPos = (sizePx / 2f - (paint.descent() + paint.ascent()) / 2f)
    canvas.drawText("D", sizePx / 2f, yPos, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates an Amber waypoint icon with step number (1, 2, ...).
 */
private fun createWaypointIcon(context: Context, number: Int): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer amber halo
    paint.color = AndroidColor.argb(80, 245, 158, 11)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner amber circle
    paint.color = AndroidColor.rgb(245, 158, 11)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Number
    paint.color = AndroidColor.BLACK
    paint.textSize = 22f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    val yPos = (sizePx / 2f - (paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(number.toString(), sizePx / 2f, yPos, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates a Gold/Crimson Finish / Goal waypoint icon with letter 'A'.
 */
private fun createFinishIcon(context: Context): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer red glow halo
    paint.color = AndroidColor.argb(90, 239, 68, 68)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner red circle
    paint.color = AndroidColor.rgb(239, 68, 68)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Inner gold ring
    paint.color = AndroidColor.rgb(250, 204, 21)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 3.4f, paint)

    // Letter A
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.rgb(250, 204, 21)
    paint.textSize = 24f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    val yPos = (sizePx / 2f - (paint.descent() + paint.ascent()) / 2f)
    canvas.drawText("A", sizePx / 2f, yPos, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates an interactive scrubber beacon icon (pulsing amber/cyan target with center dot).
 */
private fun createScrubberIcon(context: Context): android.graphics.drawable.Drawable {
    val sizePx = 64
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer glow halo (Amber/Gold)
    paint.color = AndroidColor.argb(130, 245, 158, 11)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Mid circle (Bright Cyan)
    paint.color = AndroidColor.rgb(14, 165, 233)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Inner bright white center dot
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 5.5f, paint)

    // Dark stroke border
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    paint.color = AndroidColor.argb(200, 15, 23, 42)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Generates an icon for Hike / Fly mode transition waypoints.
 * isHike = true -> Emerald Green with 'R' (Rando / Montée)
 * isHike = false -> Sky Blue with 'V' (Vol / Décollage)
 */
private fun createHikeWaypointIcon(context: Context, isHike: Boolean): android.graphics.drawable.Drawable {
    val sizePx = 54
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer glow halo
    val haloColor = if (isHike) AndroidColor.argb(90, 34, 197, 94) else AndroidColor.argb(90, 56, 189, 248)
    paint.color = haloColor
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    // Inner circle
    val circleColor = if (isHike) AndroidColor.rgb(22, 163, 74) else AndroidColor.rgb(2, 132, 199)
    paint.color = circleColor
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)

    // Text letter
    paint.color = AndroidColor.WHITE
    paint.textSize = 22f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    val text = if (isHike) "R" else "V"
    val yPos = (sizePx / 2f - (paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(text, sizePx / 2f, yPos, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Interactive cartographic overlay rendering the 1° x 1° SRTM terrain tile grid.
 *
 * Capabilities:
 * - Highlights already installed tiles in emerald green ("✓ Installé").
 * - Highlights selected tiles in vibrant sky blue ("📥 Sélectionné").
 * - Displays uninstalled tiles with subtle slate outline ("+ Toucher").
 * - Pre-allocates Paint, RectF, Point, and GeoPoint objects to ensure 0 dynamic heap allocations in draw().
 */
class ZoneGridOverlay(
    private val isEnabled: () -> Boolean,
    private val selectedTiles: () -> Set<String>,
    private val installedTiles: () -> Set<String>
) : org.osmdroid.views.overlay.Overlay() {

    private val fillInstalled = Paint().apply {
        color = AndroidColor.argb(55, 34, 197, 94) // Green
        style = Paint.Style.FILL
    }
    private val strokeInstalled = Paint().apply {
        color = AndroidColor.argb(220, 74, 222, 128)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val fillSelected = Paint().apply {
        color = AndroidColor.argb(95, 2, 132, 199) // Sky Blue
        style = Paint.Style.FILL
    }
    private val strokeSelected = Paint().apply {
        color = AndroidColor.argb(255, 56, 189, 248)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val fillNormal = Paint().apply {
        color = AndroidColor.argb(25, 30, 41, 59) // Slate
        style = Paint.Style.FILL
    }
    private val strokeNormal = Paint().apply {
        color = AndroidColor.argb(130, 148, 163, 184)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 34f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, AndroidColor.BLACK)
    }

    private val subTextPaint = Paint().apply {
        textSize = 22f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, AndroidColor.BLACK)
    }

    // Reusable objects for zero-allocation rendering in draw loop
    private val pTopLeft = android.graphics.Point()
    private val pBottomRight = android.graphics.Point()
    private val rectF = android.graphics.RectF()
    private val geoTl = GeoPoint(0.0, 0.0)
    private val geoBr = GeoPoint(0.0, 0.0)

    override fun draw(canvas: android.graphics.Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null || !isEnabled()) return

        val proj = mapView.projection ?: return
        val bbox = mapView.boundingBox ?: return

        val minLat = floor(bbox.latSouth).toInt().coerceIn(-85, 84)
        val maxLat = floor(bbox.latNorth).toInt().coerceIn(-85, 84)
        val minLon = floor(bbox.lonWest).toInt().coerceIn(-180, 179)
        val maxLon = floor(bbox.lonEast).toInt().coerceIn(-180, 179)

        // Safety clamp: if zoomed out excessively, restrict draw extent to vicinity
        val centerLat = floor(mapView.mapCenter.latitude).toInt()
        val centerLon = floor(mapView.mapCenter.longitude).toInt()
        val drawMinLat = (centerLat - 6).coerceAtLeast(minLat)
        val drawMaxLat = (centerLat + 6).coerceAtMost(maxLat)
        val drawMinLon = (centerLon - 8).coerceAtLeast(minLon)
        val drawMaxLon = (centerLon + 8).coerceAtMost(maxLon)

        val selected = selectedTiles()
        val installed = installedTiles()

        for (lat in drawMinLat..drawMaxLat) {
            for (lon in drawMinLon..drawMaxLon) {
                geoTl.setCoords(lat + 1.0, lon.toDouble())
                geoBr.setCoords(lat.toDouble(), lon + 1.0)

                proj.toPixels(geoTl, pTopLeft)
                proj.toPixels(geoBr, pBottomRight)

                val left = pTopLeft.x.toFloat()
                val top = pTopLeft.y.toFloat()
                val right = pBottomRight.x.toFloat()
                val bottom = pBottomRight.y.toFloat()

                rectF.set(left, top, right, bottom)

                val tileName = HgtTerrainManager.getTileFilename(lat.toDouble(), lon.toDouble())
                val isSel = selected.contains(tileName)
                val isInst = installed.contains(tileName)

                when {
                    isSel -> {
                        canvas.drawRect(rectF, fillSelected)
                        canvas.drawRect(rectF, strokeSelected)
                    }
                    isInst -> {
                        canvas.drawRect(rectF, fillInstalled)
                        canvas.drawRect(rectF, strokeInstalled)
                    }
                    else -> {
                        canvas.drawRect(rectF, fillNormal)
                        canvas.drawRect(rectF, strokeNormal)
                    }
                }

                val width = right - left
                val height = bottom - top
                if (width > 80f && height > 60f) {
                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    val baseName = tileName.substringBeforeLast(".hgt")

                    canvas.drawText(baseName, cx, cy - 8f, textPaint)

                    val statusMsg = when {
                        isSel -> "📥 SÉLECTIONNÉ"
                        isInst -> "✓ INSTALLÉ"
                        else -> "+ TOUCHER"
                    }
                    subTextPaint.color = when {
                        isSel -> AndroidColor.rgb(125, 211, 252)
                        isInst -> AndroidColor.rgb(134, 239, 172)
                        else -> AndroidColor.rgb(203, 213, 225)
                    }
                    canvas.drawText(statusMsg, cx, cy + 22f, subTextPaint)
                }
            }
        }
    }
}

/**
 * High-performance Map Tab for VarioAppli.
 *
 * Features:
 * - Live pilot position, free navigation with drag detection and prominent target re-center button.
 * - Dynamic reachable glide area cone taking relief and terrain dénivellation into account.
 * - Quick-access wing glide ratio (finesse) configuration with paraglider category presets.
 * - Terrain elevation provider integration for obstacle-aware route calculations.
 * - Modular basemaps and GPX track visualization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    varioData: VarioData,
    onBackToVario: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Settings state
    var glideSettings by remember { mutableStateOf(GlideSettingsManager.getSettings(context)) }
    var activeBasemap by remember { mutableStateOf(BasemapRegistry.getActiveProvider(context)) }

    // Navigation mode
    var navMode by remember { mutableStateOf(MapNavigationMode.LIVE_PILOT) }
    var planningAction by remember { mutableStateOf(PlanningAction.SET_START) }

    // Live Pilot mode state
    var targetPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var routeResult by remember { mutableStateOf<RouteResult?>(null) }

    // Itinerary Planning mode state (2 points + intermediate waypoints)
    var startPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var finishPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var intermediateWaypoints by remember { mutableStateOf(listOf<GeoPoint>()) }
    var startAltitudeM by remember { mutableFloatStateOf(1500f) }
    var isStartAltAuto by remember { mutableStateOf(true) }
    var itineraryResult by remember { mutableStateOf<ItineraryResult?>(null) }

    // Map tracking & loaded tracks
    var loadedPastTrack by remember { mutableStateOf<List<TrackPoint>?>(null) }
    var loadedPastTrackWaypoints by remember { mutableStateOf<List<GpxWaypoint>>(emptyList()) }
    var loadedPastTrackName by remember { mutableStateOf<String?>(null) }
    var lastCenteredTrackName by remember { mutableStateOf<String?>(null) }
    var isFollowingPilot by remember { mutableStateOf(true) }

    val trackProfileData = remember(loadedPastTrack) {
        loadedPastTrack?.let { GpxTrackManager.computeTrackProfile(it) }
    }
    var showTrackDetails by remember { mutableStateOf(false) }
    var scrubbedPointIndex by remember { mutableStateOf<Int?>(null) }

    // Sheets
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }

    // Multi-zone download state
    var selectedTilesForDownload by remember { mutableStateOf(setOf<String>()) }
    var installedTilesSet by remember { mutableStateOf(HgtTerrainManager.getInstalledTiles().toSet()) }
    var isMultiDownloading by remember { mutableStateOf(false) }
    var multiDownloadProgress by remember { mutableFloatStateOf(0f) }
    var multiDownloadCount by remember { mutableStateOf(Pair(0, 0)) }
    var multiDownloadStatusMsg by remember { mutableStateOf<String?>(null) }

    // References to map objects
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val glideOverlay = remember { GlideOverlay() }
    val heatmapOverlay = remember { ReachabilityHeatmapOverlay() }
    val zoneGridOverlay = remember {
        ZoneGridOverlay(
            isEnabled = { navMode == MapNavigationMode.ZONE_DOWNLOAD },
            selectedTiles = { selectedTilesForDownload },
            installedTiles = { installedTilesSet }
        )
    }
    var pilotMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var targetMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var startMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var finishMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var scrubberMarkerRef by remember { mutableStateOf<Marker?>(null) }
    val waypointMarkersRef = remember { mutableListOf<Marker>() }
    val trackWaypointsMarkersRef = remember { mutableListOf<Marker>() }

    val liveTrackPolyline = remember {
        Polyline().apply {
            outlinePaint.color = AndroidColor.argb(230, 14, 165, 233) // Vibrant Cyan
            outlinePaint.strokeWidth = 7f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
    }
    val pastTrackPolyline = remember {
        Polyline().apply {
            outlinePaint.color = AndroidColor.argb(220, 245, 158, 11) // Amber Gold
            outlinePaint.strokeWidth = 6f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }
    }
    val routePolyline = remember {
        Polyline().apply {
            outlinePaint.strokeWidth = 6f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }
    }

    // Capture latest state for MapEventsReceiver callback
    val currentNavMode by rememberUpdatedState(navMode)
    val currentPlanningAction by rememberUpdatedState(planningAction)
    val currentIntermediateWps by rememberUpdatedState(intermediateWaypoints)
    val currentFinishPoint by rememberUpdatedState(finishPoint)
    val currentIsStartAltAuto by rememberUpdatedState(isStartAltAuto)

    val startMultiZoneDownload: () -> Unit = {
        val tilesToDownload = selectedTilesForDownload.toList()
        if (tilesToDownload.isNotEmpty() && !isMultiDownloading) {
            isMultiDownloading = true
            multiDownloadProgress = 0f
            multiDownloadCount = Pair(0, tilesToDownload.size)
            multiDownloadStatusMsg = "Téléchargement de ${tilesToDownload.size} zones..."

            // Requirement 3: Retour automatique à la zone de carte après sélection!
            navMode = MapNavigationMode.LIVE_PILOT
            val firstTile = tilesToDownload.first()
            HgtTerrainManager.parseTileCoordinates(firstTile)?.let { (lat, lon) ->
                mapViewRef?.controller?.animateTo(GeoPoint(lat + 0.5, lon + 0.5))
            }

            coroutineScope.launch {
                val results = HgtTerrainManager.downloadMultipleTiles(
                    tileNames = tilesToDownload,
                    onOverallProgress = { progress ->
                        multiDownloadProgress = progress.overallProgress
                        multiDownloadCount = Pair(progress.completedCount, progress.totalCount)
                        multiDownloadStatusMsg = if (progress.currentTile != null) {
                            "Téléchargement : ${progress.completedCount}/${progress.totalCount} (${progress.currentTile.substringBeforeLast(".hgt")})"
                        } else {
                            "Téléchargement : ${progress.completedCount}/${progress.totalCount}"
                        }
                    }
                )
                isMultiDownloading = false
                val successCount = results.values.count { it.isSuccess }
                installedTilesSet = HgtTerrainManager.getInstalledTiles().toSet()
                mapViewRef?.invalidate()

                if (successCount == tilesToDownload.size) {
                    multiDownloadStatusMsg = "✓ $successCount zones prêtes pour le vol hors-ligne !"
                    Toast.makeText(context, "✓ $successCount zones téléchargées avec succès !", Toast.LENGTH_SHORT).show()
                } else {
                    multiDownloadStatusMsg = "Téléchargement partiel : $successCount/${tilesToDownload.size} réussis"
                    Toast.makeText(context, "Téléchargement terminé : $successCount/${tilesToDownload.size} réussis", Toast.LENGTH_LONG).show()
                }
                selectedTilesForDownload = emptySet()
            }
        }
    }

    // Register elevation provider listener to invalidate map upon online elevation retrieval
    DisposableEffect(Unit) {
        val elevationListener: () -> Unit = {
            mapViewRef?.invalidate()
            Unit
        }
        TerrainElevationProvider.addListener(elevationListener)
        onDispose {
            TerrainElevationProvider.removeListener(elevationListener)
            scrubberMarkerRef?.let { marker ->
                mapViewRef?.overlays?.remove(marker)
            }
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
        }
    }

    // Compute route in LIVE_PILOT mode
    LaunchedEffect(
        navMode,
        varioData.latitude,
        varioData.longitude,
        varioData.altitudeM,
        targetPoint,
        glideSettings.glideRatio,
        glideSettings.safetyMarginM
    ) {
        if (navMode == MapNavigationMode.LIVE_PILOT) {
            val target = targetPoint
            if (target != null && (varioData.latitude != 0.0 || varioData.longitude != 0.0)) {
                val pilot = GeoPoint(varioData.latitude, varioData.longitude)
                val targetElevation = TerrainElevationProvider.getElevation(target.latitude, target.longitude)
                val result = ObstacleRoutingEngine.computeRoute(
                    start = pilot,
                    target = target,
                    pilotAltM = varioData.altitudeM,
                    targetAltM = targetElevation,
                    wingGlideRatio = glideSettings.glideRatio,
                    safetyMarginM = glideSettings.safetyMarginM
                )
                routeResult = result

                routePolyline.setPoints(result.waypoints)
                routePolyline.outlinePaint.color = if (result.isReachable) {
                    AndroidColor.argb(230, 34, 197, 94) // Green
                } else {
                    AndroidColor.argb(230, 239, 68, 68) // Red
                }
                mapViewRef?.invalidate()
            } else {
                routeResult = null
                routePolyline.setPoints(emptyList())
                mapViewRef?.invalidate()
            }
        }
    }

    // Compute itinerary in PLANNING_ITINERARY mode
    LaunchedEffect(
        navMode,
        startPoint,
        finishPoint,
        intermediateWaypoints,
        startAltitudeM,
        glideSettings.glideRatio,
        glideSettings.safetyMarginM
    ) {
        if (navMode == MapNavigationMode.PLANNING_ITINERARY) {
            val start = startPoint
            val finish = finishPoint
            if (start != null && finish != null) {
                val allPoints = mutableListOf<GeoPoint>()
                allPoints.add(start)
                allPoints.addAll(intermediateWaypoints)
                allPoints.add(finish)

                val finishElev = TerrainElevationProvider.getElevation(finish.latitude, finish.longitude)
                val itResult = ObstacleRoutingEngine.computeItinerary(
                    points = allPoints,
                    startAltM = startAltitudeM,
                    finishAltM = finishElev,
                    wingGlideRatio = glideSettings.glideRatio,
                    safetyMarginM = glideSettings.safetyMarginM
                )
                itineraryResult = itResult

                routePolyline.setPoints(itResult.polylinePoints)
                routePolyline.outlinePaint.color = if (itResult.isReachable) {
                    AndroidColor.argb(230, 34, 197, 94) // Green
                } else {
                    AndroidColor.argb(230, 239, 68, 68) // Red
                }
                mapViewRef?.invalidate()
            } else {
                itineraryResult = null
                routePolyline.setPoints(emptyList())
                mapViewRef?.invalidate()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── OsmDroid MapView ─────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().load(
                    ctx,
                    ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
                )
                Configuration.getInstance().userAgentValue = ctx.packageName

                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setTileSource(activeBasemap.createTileSource())

                    controller.setZoom(14.5)
                    val initialPos = if (varioData.latitude != 0.0 || varioData.longitude != 0.0) {
                        GeoPoint(varioData.latitude, varioData.longitude)
                    } else {
                        GeoPoint(45.3022, 5.8564) // St Hilaire du Touvet (Alps Paragliding Mecca)
                    }
                    controller.setCenter(initialPos)

                    // Target marker styling
                    val tMarker = Marker(this).apply {
                        icon = createTargetIcon(ctx)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Point Cible"
                    }
                    targetMarkerRef = tMarker

                    // Pilot marker styling
                    val pMarker = Marker(this).apply {
                        icon = createPilotIcon(ctx)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Pilote"
                        position = initialPos
                    }
                    pilotMarkerRef = pMarker

                    // Start marker styling
                    val sMarker = Marker(this).apply {
                        icon = createStartIcon(ctx)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Départ"
                    }
                    startMarkerRef = sMarker

                    // Finish marker styling
                    val fMarker = Marker(this).apply {
                        icon = createFinishIcon(ctx)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "Arrivée"
                    }
                    finishMarkerRef = fMarker

                    // Map drag detection: release pilot lock when user drags map > 12px
                    var touchStartX = 0f
                    var touchStartY = 0f
                    var isDragDetected = false

                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchStartX = event.x
                                touchStartY = event.y
                                isDragDetected = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - touchStartX
                                val dy = event.y - touchStartY
                                if (!isDragDetected && hypot(dx.toDouble(), dy.toDouble()) > 12.0) {
                                    isDragDetected = true
                                    if (isFollowingPilot) {
                                        isFollowingPilot = false
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                isDragDetected = false
                            }
                        }
                        false
                    }

                    // Touch and long-press handling
                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p == null) return false
                            if (currentNavMode == MapNavigationMode.ZONE_DOWNLOAD) {
                                val tile = HgtTerrainManager.getTileFilename(p.latitude, p.longitude)
                                selectedTilesForDownload = if (selectedTilesForDownload.contains(tile)) {
                                    selectedTilesForDownload - tile
                                } else {
                                    selectedTilesForDownload + tile
                                }
                                invalidate()
                                return true
                            }
                            if (currentNavMode == MapNavigationMode.PLANNING_ITINERARY) {
                                when (currentPlanningAction) {
                                    PlanningAction.SET_START -> {
                                        startPoint = p
                                        if (currentIsStartAltAuto) {
                                            val ground = TerrainElevationProvider.getElevation(p.latitude, p.longitude)
                                            startAltitudeM = ground + 100f
                                            coroutineScope.launch {
                                                val peakElev = TerrainElevationProvider.findPeakElevation(p.latitude, p.longitude, 50f)
                                                startAltitudeM = peakElev + 100f
                                                Toast.makeText(
                                                    ctx,
                                                    "Altitude Départ ajustée au sommet (50m) : ${peakElev.roundToInt()}m (Vol : ${(peakElev + 100f).roundToInt()}m)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        if (currentFinishPoint == null) {
                                            planningAction = PlanningAction.SET_FINISH
                                            Toast.makeText(ctx, "Départ défini ! Touchez l'Arrivée", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(ctx, "Départ mis à jour", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    PlanningAction.SET_FINISH -> {
                                        finishPoint = p
                                        Toast.makeText(ctx, "Arrivée définie", Toast.LENGTH_SHORT).show()
                                    }
                                    PlanningAction.ADD_WAYPOINT -> {
                                        intermediateWaypoints = currentIntermediateWps + p
                                        Toast.makeText(ctx, "Étape ${currentIntermediateWps.size + 1} ajoutée", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                invalidate()
                                return true
                            }
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p == null) return false
                            if (currentNavMode == MapNavigationMode.ZONE_DOWNLOAD) {
                                return singleTapConfirmedHelper(p)
                            }
                            if (currentNavMode == MapNavigationMode.LIVE_PILOT) {
                                targetPoint = p
                                tMarker.position = p
                                if (!overlays.contains(tMarker)) {
                                    overlays.add(tMarker)
                                }
                                Toast.makeText(ctx, "Cible définie !", Toast.LENGTH_SHORT).show()
                                invalidate()
                                return true
                            } else {
                                return singleTapConfirmedHelper(p)
                            }
                        }
                    }

                    overlays.add(MapEventsOverlay(mapEventsReceiver))
                    overlays.add(heatmapOverlay)
                    overlays.add(glideOverlay)
                    overlays.add(zoneGridOverlay)
                    overlays.add(pastTrackPolyline)
                    overlays.add(liveTrackPolyline)
                    overlays.add(routePolyline)
                    overlays.add(pMarker)

                    onResume()
                    mapViewRef = this
                }
            },
            update = { mapView ->
                // Update basemap tile source if changed
                if (mapView.tileProvider.tileSource.name() != activeBasemap.createTileSource().name()) {
                    mapView.setTileSource(activeBasemap.createTileSource())
                }

                if (navMode == MapNavigationMode.LIVE_PILOT) {
                    // In LIVE mode: remove itinerary markers
                    startMarkerRef?.let { mapView.overlays.remove(it) }
                    finishMarkerRef?.let { mapView.overlays.remove(it) }
                    waypointMarkersRef.forEach { mapView.overlays.remove(it) }
                    waypointMarkersRef.clear()

                    // Update pilot marker and live glide cone / heatmap
                    if (varioData.latitude != 0.0 || varioData.longitude != 0.0) {
                        val pilotGeo = GeoPoint(varioData.latitude, varioData.longitude)
                        pilotMarkerRef?.let { pMarker ->
                            pMarker.position = pilotGeo
                            if (!mapView.overlays.contains(pMarker)) {
                                mapView.overlays.add(pMarker)
                            }
                            Unit
                        }

                        val groundRefAlt = if (targetPoint != null) {
                            TerrainElevationProvider.getElevation(targetPoint!!.latitude, targetPoint!!.longitude)
                        } else {
                            minOf(
                                TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M,
                                TerrainElevationProvider.getElevation(varioData.latitude, varioData.longitude)
                            )
                        }
                        val showHeatmap = glideSettings.isGlideAreaEnabled && glideSettings.useHeatmapMode
                        val showCircle = glideSettings.isGlideAreaEnabled && !glideSettings.useHeatmapMode

                        glideOverlay.updateState(
                            position = pilotGeo,
                            altM = varioData.altitudeM,
                            groundAltM = groundRefAlt,
                            gr = glideSettings.glideRatio,
                            enabled = showCircle
                        )
                        heatmapOverlay.updateState(
                            position = pilotGeo,
                            altM = varioData.altitudeM,
                            gr = glideSettings.glideRatio,
                            safetyMargin = glideSettings.safetyMarginM,
                            enabled = showHeatmap,
                            groundAltM = groundRefAlt,
                            mapView = mapView
                        )

                        if (isFollowingPilot) {
                            mapView.setExpectedCenter(pilotGeo)
                        }
                    }

                    // Update target marker
                    val t = targetPoint
                    targetMarkerRef?.let { tMarker ->
                        if (t != null) {
                            tMarker.position = t
                            if (!mapView.overlays.contains(tMarker)) {
                                mapView.overlays.add(tMarker)
                            }
                        } else {
                            mapView.overlays.remove(tMarker)
                        }
                        Unit
                    }
                } else {
                    // In PLANNING mode: remove live target marker
                    targetMarkerRef?.let { mapView.overlays.remove(it) }

                    // Start marker
                    val sp = startPoint
                    startMarkerRef?.let { sMarker ->
                        if (sp != null) {
                            sMarker.position = sp
                            if (!mapView.overlays.contains(sMarker)) {
                                mapView.overlays.add(sMarker)
                            }
                        } else {
                            mapView.overlays.remove(sMarker)
                        }
                        Unit
                    }

                    // Finish marker
                    val fp = finishPoint
                    finishMarkerRef?.let { fMarker ->
                        if (fp != null) {
                            fMarker.position = fp
                            if (!mapView.overlays.contains(fMarker)) {
                                mapView.overlays.add(fMarker)
                            }
                        } else {
                            mapView.overlays.remove(fMarker)
                        }
                        Unit
                    }

                    // Waypoint markers
                    waypointMarkersRef.forEach { mapView.overlays.remove(it) }
                    waypointMarkersRef.clear()
                    intermediateWaypoints.forEachIndexed { idx, wp ->
                        val m = Marker(mapView).apply {
                            icon = createWaypointIcon(context, idx + 1)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Étape ${idx + 1}"
                            position = wp
                        }
                        waypointMarkersRef.add(m)
                        mapView.overlays.add(m)
                    }

                    // Glide overlay cone radiating from departure point
                    if (sp != null) {
                        val groundRefAlt = if (fp != null) {
                            TerrainElevationProvider.getElevation(fp.latitude, fp.longitude)
                        } else {
                            minOf(
                                TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M,
                                TerrainElevationProvider.getElevation(sp.latitude, sp.longitude)
                            )
                        }
                        val showHeatmap = glideSettings.isGlideAreaEnabled && glideSettings.useHeatmapMode
                        val showCircle = glideSettings.isGlideAreaEnabled && !glideSettings.useHeatmapMode

                        glideOverlay.updateState(
                            position = sp,
                            altM = startAltitudeM,
                            groundAltM = groundRefAlt,
                            gr = glideSettings.glideRatio,
                            enabled = showCircle
                        )
                        heatmapOverlay.updateState(
                            position = sp,
                            altM = startAltitudeM,
                            gr = glideSettings.glideRatio,
                            safetyMargin = glideSettings.safetyMarginM,
                            enabled = showHeatmap,
                            groundAltM = groundRefAlt,
                            mapView = mapView
                        )
                    } else {
                        glideOverlay.updateState(
                            position = GeoPoint(0.0, 0.0),
                            altM = 0f,
                            groundAltM = 0f,
                            gr = glideSettings.glideRatio,
                            enabled = false
                        )
                        heatmapOverlay.updateState(
                            position = null,
                            altM = 0f,
                            gr = glideSettings.glideRatio,
                            safetyMargin = glideSettings.safetyMarginM,
                            enabled = false
                        )
                    }
                }

                // Update active flight track points
                val activePts = GpxTrackManager.getCurrentTrackPoints()
                if (activePts.isNotEmpty()) {
                    val geoPts = activePts.map { GeoPoint(it.latitude, it.longitude) }
                    liveTrackPolyline.setPoints(geoPts)
                } else {
                    liveTrackPolyline.setPoints(emptyList())
                }

                // Update past track points if loaded
                val pastPts = loadedPastTrack
                if (pastPts != null && pastPts.isNotEmpty()) {
                    val pastGeoPts = pastPts.map { GeoPoint(it.latitude, it.longitude) }
                    pastTrackPolyline.setPoints(pastGeoPts)

                    // Auto-center map on track start if newly loaded
                    if (loadedPastTrackName != null && loadedPastTrackName != lastCenteredTrackName) {
                        lastCenteredTrackName = loadedPastTrackName
                        isFollowingPilot = false
                        val startPt = pastPts.first()
                        val startGeo = GeoPoint(startPt.latitude, startPt.longitude)
                        mapView.setExpectedCenter(startGeo)
                        mapView.controller.setZoom(15.0)
                        mapView.controller.setCenter(startGeo)
                        mapView.controller.animateTo(startGeo)
                    }
                } else {
                    pastTrackPolyline.setPoints(emptyList())
                    if (loadedPastTrackName == null) {
                        lastCenteredTrackName = null
                    }
                }

                // Update track waypoints markers (for loaded past track or live active session)
                val waypointsToRender = when {
                    loadedPastTrack != null -> loadedPastTrackWaypoints
                    varioData.isFlightActive -> GpxTrackManager.getCurrentWaypoints()
                    else -> emptyList()
                }

                trackWaypointsMarkersRef.forEach { mapView.overlays.remove(it) }
                trackWaypointsMarkersRef.clear()

                for ((idx, wpt) in waypointsToRender.withIndex()) {
                    val isHike = wpt.symbol == "Trailhead" || wpt.name.contains("Rando", ignoreCase = true)
                    val isFly = wpt.symbol == "Paraglider" || wpt.name.contains("Vol", ignoreCase = true) || wpt.name.contains("Décol", ignoreCase = true)
                    val isFinish = wpt.symbol == "Finish" || wpt.symbol == "Landing" || wpt.name.contains("Fin", ignoreCase = true) || wpt.name.contains("Atterri", ignoreCase = true)

                    val iconDrawable = when {
                        isHike -> createHikeWaypointIcon(context, isHike = true)
                        isFly -> createHikeWaypointIcon(context, isHike = false)
                        isFinish -> createFinishIcon(context)
                        else -> createWaypointIcon(context, idx + 1)
                    }

                    val m = Marker(mapView).apply {
                        position = GeoPoint(wpt.latitude, wpt.longitude)
                        title = "${wpt.name} (${wpt.altitudeM.toInt()} m)"
                        snippet = wpt.description
                        icon = iconDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    trackWaypointsMarkersRef.add(m)
                    mapView.overlays.add(m)
                }

                // Update scrubber marker if scrubbing on profile graph
                val profile = trackProfileData
                val sIndex = scrubbedPointIndex
                if (showTrackDetails && profile != null && sIndex != null && sIndex in profile.points.indices) {
                    val sPt = profile.points[sIndex]
                    val sGeo = GeoPoint(sPt.latitude, sPt.longitude)
                    var sMarker = scrubberMarkerRef
                    if (sMarker == null) {
                        sMarker = Marker(mapView).apply {
                            icon = createScrubberIcon(context)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        scrubberMarkerRef = sMarker
                    }
                    sMarker.position = sGeo
                    sMarker.title = "Alt: ${sPt.altitudeM.toInt()}m | Dist: ${String.format(Locale.US, "%.2f", sPt.distanceM / 1000f)}km"
                    if (!mapView.overlays.contains(sMarker)) {
                        mapView.overlays.add(sMarker)
                    }
                } else {
                    scrubberMarkerRef?.let { marker ->
                        mapView.overlays.remove(marker)
                    }
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top Bar Container (HUD Bar + Mode Switcher + Planning Toolbar) ────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapTopHudBar(
                varioData = varioData,
                glideRatio = glideSettings.glideRatio,
                onBackToVario = onBackToVario,
                onOpenSettings = { showSettingsSheet = true },
                onOpenTracks = { showTracksSheet = true }
            )

            MapModeSwitcherBar(
                navMode = navMode,
                onSelectMode = { newMode ->
                    navMode = newMode
                    mapViewRef?.invalidate()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (navMode == MapNavigationMode.PLANNING_ITINERARY) {
                PlanningActionBar(
                    activeAction = planningAction,
                    hasStart = startPoint != null,
                    hasFinish = finishPoint != null,
                    waypointCount = intermediateWaypoints.size,
                    onSelectAction = { planningAction = it },
                    onReverse = {
                        val s = startPoint
                        val f = finishPoint
                        if (s != null && f != null) {
                            startPoint = f
                            finishPoint = s
                            intermediateWaypoints = intermediateWaypoints.reversed()
                            val ground = TerrainElevationProvider.getElevation(f.latitude, f.longitude)
                            startAltitudeM = ground + 100f
                            coroutineScope.launch {
                                val peakElev = TerrainElevationProvider.findPeakElevation(f.latitude, f.longitude, 50f)
                                startAltitudeM = peakElev + 100f
                                mapViewRef?.invalidate()
                            }
                            mapViewRef?.invalidate()
                        }
                    },
                    onClearAll = {
                        startPoint = null
                        finishPoint = null
                        intermediateWaypoints = emptyList()
                        itineraryResult = null
                        planningAction = PlanningAction.SET_START
                        mapViewRef?.invalidate()
                    }
                )
            }
        }

        // ── Multi-Zone Mode Top Guidance Banner ──────────────────────────────
        if (navMode == MapNavigationMode.ZONE_DOWNLOAD) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp, start = 14.dp, end = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF00F172A))
                    .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🗺️ Mode Sélection Multi-Zones",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "Touchez les dalles 1°×1° pour sélectionner les zones hors-ligne.",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Surface(
                        onClick = {
                            navMode = MapNavigationMode.LIVE_PILOT
                            mapViewRef?.invalidate()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF334155),
                        border = BorderStroke(1.dp, Color(0xFF475569))
                    ) {
                        Text(
                            text = "✕ Retour Carte",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // ── Floating Multi-Zone Download Progress HUD Banner ─────────────────
        if (isMultiDownloading || (multiDownloadStatusMsg != null && navMode == MapNavigationMode.LIVE_PILOT)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (loadedPastTrackName != null) 210.dp else 145.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF20F172A))
                    .border(1.dp, if (isMultiDownloading) Color(0xFF0284C7) else Color(0xFF22C55E), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(if (isMultiDownloading) "⏳" else "✓", fontSize = 13.sp)
                            Text(
                                text = multiDownloadStatusMsg ?: "Téléchargement multi-zones...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        if (!isMultiDownloading) {
                            Text(
                                text = "✕",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { multiDownloadStatusMsg = null }
                                    .padding(4.dp)
                            )
                        }
                    }
                    if (isMultiDownloading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { multiDownloadProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF38BDF8),
                            trackColor = Color(0xFF334155)
                        )
                    }
                }
            }
        }

        // ── Route, Itinerary or Multi-Zone Download Cards ───────────────────
        when (navMode) {
            MapNavigationMode.LIVE_PILOT -> {
                val currentRoute = routeResult
                val currentTarget = targetPoint
                if (currentTarget != null && currentRoute != null) {
                    val targetElev = TerrainElevationProvider.getElevation(currentTarget.latitude, currentTarget.longitude)
                    val deniv = TerrainElevationProvider.getDenivellation(varioData.altitudeM, currentTarget.latitude, currentTarget.longitude)
                    TargetRouteCard(
                        route = currentRoute,
                        targetElevationM = targetElev,
                        denivellationM = deniv,
                        nominalGlide = glideSettings.glideRatio,
                        onClearTarget = {
                            targetPoint = null
                            routeResult = null
                            targetMarkerRef?.let { tMarker ->
                                mapViewRef?.overlays?.remove(tMarker)
                            }
                            mapViewRef?.invalidate()
                        },
                        onOpenSettings = { showSettingsSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 14.dp, bottom = 86.dp, end = 80.dp)
                    )
                }
            }
            MapNavigationMode.PLANNING_ITINERARY -> {
                if (startPoint != null || finishPoint != null) {
                    ItineraryRouteCard(
                        itinerary = itineraryResult,
                        startPoint = startPoint,
                        finishPoint = finishPoint,
                        intermediateWaypoints = intermediateWaypoints,
                        startAltitudeM = startAltitudeM,
                        nominalGlide = glideSettings.glideRatio,
                        onAdjustStartAlt = { delta ->
                            isStartAltAuto = false
                            startAltitudeM = (startAltitudeM + delta).coerceIn(0f, 6000f)
                        },
                        onOpenSettings = { showSettingsSheet = true },
                        onClearItinerary = {
                            startPoint = null
                            finishPoint = null
                            intermediateWaypoints = emptyList()
                            itineraryResult = null
                            planningAction = PlanningAction.SET_START
                            mapViewRef?.invalidate()
                        },
                        onRemoveWaypoint = { index ->
                            intermediateWaypoints = intermediateWaypoints.filterIndexed { i, _ -> i != index }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 14.dp, bottom = 86.dp, end = 80.dp)
                    )
                }
            }
            MapNavigationMode.ZONE_DOWNLOAD -> {
                val curLat = if (varioData.latitude != 0.0) varioData.latitude else 45.92
                val curLon = if (varioData.longitude != 0.0) varioData.longitude else 6.87
                val curTile = HgtTerrainManager.getTileFilename(curLat, curLon)

                MultiZoneSelectionBottomCard(
                    selectedTiles = selectedTilesForDownload,
                    installedTiles = installedTilesSet,
                    currentTileName = curTile,
                    onToggleTile = { tile ->
                        selectedTilesForDownload = if (selectedTilesForDownload.contains(tile)) {
                            selectedTilesForDownload - tile
                        } else {
                            selectedTilesForDownload + tile
                        }
                        mapViewRef?.invalidate()
                    },
                    onClearSelection = {
                        selectedTilesForDownload = emptySet()
                        mapViewRef?.invalidate()
                    },
                    onAddCurrentTile = {
                        selectedTilesForDownload = selectedTilesForDownload + curTile
                        mapViewRef?.invalidate()
                    },
                    onAddAlpsPreset = {
                        selectedTilesForDownload = selectedTilesForDownload + setOf("N45E005.hgt", "N45E006.hgt", "N44E006.hgt")
                        mapViewRef?.invalidate()
                    },
                    onStartDownload = startMultiZoneDownload,
                    onBackToMap = {
                        navMode = MapNavigationMode.LIVE_PILOT
                        mapViewRef?.invalidate()
                    },
                    isDownloading = isMultiDownloading,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 86.dp, end = 80.dp)
                )
            }
        }

        // ── Loaded Past Track Banner (if viewing archive) ───────────────────
        if (loadedPastTrackName != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xE61E293B))
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Trace : $loadedPastTrackName",
                        color = Color(0xFFFDE68A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Small Details button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (showTrackDetails) Color(0xFF0284C7) else Color(0xFF334155),
                        border = BorderStroke(1.dp, if (showTrackDetails) Color(0xFF38BDF8) else Color(0xFF475569)),
                        modifier = Modifier.clickable {
                            showTrackDetails = !showTrackDetails
                            if (!showTrackDetails) {
                                scrubbedPointIndex = null
                                mapViewRef?.invalidate()
                            }
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = "📈", fontSize = 12.sp)
                            Text(
                                text = "Détails",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                loadedPastTrack = null
                                loadedPastTrackName = null
                                loadedPastTrackWaypoints = emptyList()
                                lastCenteredTrackName = null
                                showTrackDetails = false
                                scrubbedPointIndex = null
                                isFollowingPilot = true
                                mapViewRef?.invalidate()
                            }
                            .padding(4.dp)
                    )
                }
            }
        }

        // ── Prominent Floating Re-center Button (appears when pilot lock is released) ──
        if (navMode == MapNavigationMode.LIVE_PILOT && !isFollowingPilot) {
            RecenterPilotButton(
                onClick = {
                    isFollowingPilot = true
                    val pilotGeo = if (varioData.latitude != 0.0 || varioData.longitude != 0.0) {
                        GeoPoint(varioData.latitude, varioData.longitude)
                    } else {
                        GeoPoint(45.3022, 5.8564)
                    }
                    mapViewRef?.controller?.animateTo(pilotGeo)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }

        // ── Floating Action Buttons (Right) ──────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 24.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Re-center on pilot
            FloatingCircleButton(
                iconText = "📍",
                isActive = isFollowingPilot,
                onClick = {
                    isFollowingPilot = true
                    if (varioData.latitude != 0.0 || varioData.longitude != 0.0) {
                        mapViewRef?.controller?.animateTo(
                            GeoPoint(varioData.latitude, varioData.longitude)
                        )
                    }
                }
            )

            // Zoom in
            FloatingCircleButton(
                iconText = "+",
                onClick = { mapViewRef?.controller?.zoomIn() }
            )

            // Zoom out
            FloatingCircleButton(
                iconText = "−",
                onClick = { mapViewRef?.controller?.zoomOut() }
            )

            // Toggle Glide Cone
            FloatingCircleButton(
                iconText = "📐",
                isActive = glideSettings.isGlideAreaEnabled,
                onClick = {
                    val updated = glideSettings.copy(isGlideAreaEnabled = !glideSettings.isGlideAreaEnabled)
                    glideSettings = updated
                    GlideSettingsManager.saveSettings(context, updated)
                    mapViewRef?.invalidate()
                }
            )

            // Open Relief & Glide Settings
            FloatingCircleButton(
                iconText = "⚙",
                onClick = { showSettingsSheet = true }
            )
        }

        // ── GPX Track Profile & Finger Scrubber Card ────────────────────────
        if (showTrackDetails && trackProfileData != null) {
            TrackProfileCard(
                profileData = trackProfileData,
                scrubbedIndex = scrubbedPointIndex,
                onScrub = { index ->
                    scrubbedPointIndex = index
                    mapViewRef?.invalidate()
                },
                onCenterOnPoint = { index ->
                    val pt = trackProfileData.points.getOrNull(index)
                    if (pt != null) {
                        mapViewRef?.controller?.animateTo(GeoPoint(pt.latitude, pt.longitude))
                    }
                },
                onClose = {
                    showTrackDetails = false
                    scrubbedPointIndex = null
                    mapViewRef?.invalidate()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }

        // ── Settings Bottom Sheet ────────────────────────────────────────────
        if (showSettingsSheet) {
            GlideSettingsBottomSheet(
                currentSettings = glideSettings,
                activeBasemap = activeBasemap,
                onDismiss = { showSettingsSheet = false },
                onUpdateSettings = { newSettings ->
                    glideSettings = newSettings
                    GlideSettingsManager.saveSettings(context, newSettings)
                    mapViewRef?.invalidate()
                },
                onSelectBasemap = { newProvider ->
                    activeBasemap = newProvider
                    BasemapRegistry.setActiveProvider(context, newProvider.id)
                    mapViewRef?.setTileSource(newProvider.createTileSource())
                },
                currentLat = if (varioData.latitude != 0.0) varioData.latitude else 45.92,
                currentLon = if (varioData.longitude != 0.0) varioData.longitude else 6.87,
                onOpenZoneMapSelector = {
                    navMode = MapNavigationMode.ZONE_DOWNLOAD
                    mapViewRef?.invalidate()
                }
            )
        }

        // ── Tracks Archive Bottom Sheet ──────────────────────────────────────
        if (showTracksSheet) {
            TrackHistoryBottomSheet(
                currentlySelectedTrackName = loadedPastTrackName,
                onDismiss = { showTracksSheet = false },
                onSelectTrackToView = { file ->
                    val points = GpxTrackManager.loadTrackPoints(file)
                    val waypoints = GpxTrackManager.loadTrackWaypoints(file)
                    if (points.isNotEmpty()) {
                        loadedPastTrack = points
                        loadedPastTrackWaypoints = waypoints
                        loadedPastTrackName = file.name
                        showTrackDetails = true
                        scrubbedPointIndex = null
                        isFollowingPilot = false
                        val startPt = points.first()
                        val startGeo = GeoPoint(startPt.latitude, startPt.longitude)
                        mapViewRef?.let { mv ->
                            mv.controller.setZoom(15.0)
                            mv.controller.setCenter(startGeo)
                            mv.controller.animateTo(startGeo)
                        }
                        showTracksSheet = false
                    } else {
                        Toast.makeText(context, "Trace vide ou format non supporté", Toast.LENGTH_SHORT).show()
                    }
                },
                onClearSelectedTrack = {
                    loadedPastTrack = null
                    loadedPastTrackName = null
                    loadedPastTrackWaypoints = emptyList()
                    lastCenteredTrackName = null
                    showTrackDetails = false
                    scrubbedPointIndex = null
                    isFollowingPilot = true
                    mapViewRef?.invalidate()
                }
            )
        }
    }
}

// ── Top HUD Bar ──────────────────────────────────────────────────────────────

@Composable
private fun MapTopHudBar(
    varioData: VarioData,
    glideRatio: Float,
    onBackToVario: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTracks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEB0F172A)),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Button back to Vario + Finesse Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onBackToVario,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("⬅ Vario", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }

                // Discoverable Wing Glide Ratio Pill
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.6f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                    ) {
                        Text("🪂", fontSize = 12.sp)
                        Text(
                            text = String.format(Locale.US, "Fin. %.1f", glideRatio),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4ADE80)
                        )
                        Text("✎", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // Central mini-telemetry readouts
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Vz
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatVzLocal(varioData.vzMs),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (varioData.vzMs >= 0.2f) Color(0xFF4ADE80) else if (varioData.vzMs <= -0.5f) Color(0xFFF87171) else Color.White
                    )
                    Text("m/s", fontSize = 9.sp, color = Color(0xFF94A3B8))
                }

                // Altitude
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${varioData.altitudeM.toInt()} m",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("Altitude", fontSize = 9.sp, color = Color(0xFF94A3B8))
                }

                // Flight Time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val min = varioData.flightDurationSec / 60
                    val sec = varioData.flightDurationSec % 60
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", min, sec),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            varioData.isFlightPaused -> Color(0xFFF59E0B)
                            varioData.isFlightActive -> Color(0xFF4ADE80)
                            else -> Color(0xFF94A3B8)
                        }
                    )
                    Text(
                        text = if (varioData.isFlightPaused) "Pause ⏸️" else "Temps",
                        fontSize = 9.sp,
                        color = if (varioData.isFlightPaused) Color(0xFFF59E0B) else Color(0xFF94A3B8)
                    )
                }
            }

            // Right icons: GPX Tracks & Settings
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onOpenTracks) {
                    Text("📁", fontSize = 16.sp)
                }

                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("⚙", fontSize = 13.sp, color = Color(0xFF38BDF8))
                        Text("Param.", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

// ── Target & Obstacle Route Card ─────────────────────────────────────────────

@Composable
private fun TargetRouteCard(
    route: RouteResult,
    targetElevationM: Float,
    denivellationM: Float,
    nominalGlide: Float,
    onClearTarget: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (route.isReachable) Color(0xFF22C55E) else Color(0xFFEF4444)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎯 Navigation & Finesse",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Effacer",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.clickable { onClearTarget() }
                )
            }

            // Distance & Reachable Status
            val distKm = route.totalDistanceM / 1000f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "Distance : %.2f km", distKm),
                    fontSize = 14.sp,
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (route.isReachable) Color(0x3322C55E) else Color(0x33EF4444))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (route.isReachable) "ATTEIGNABLE" else "TROP BAS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (route.isReachable) Color(0xFF4ADE80) else Color(0xFFF87171)
                    )
                }
            }

            // Relief & Dénivellation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "Alt. cible : %d m", targetElevationM.toInt()),
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = String.format(Locale.US, "Dénivelé (ΔH) : %+d m", denivellationM.toInt()),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (denivellationM > 0f) Color(0xFF38BDF8) else Color(0xFFF87171)
                )
            }

            // Required Glide Ratio readout & Clickable Wing Glide Pill
            val reqGlideText = if (route.requiredGlideRatio.isInfinite() || route.requiredGlideRatio <= 0f) {
                "Impossible"
            } else {
                String.format(Locale.US, "%.1f", route.requiredGlideRatio)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Finesse requise : $reqGlideText",
                    fontSize = 13.sp,
                    color = if (route.isReachable) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "Aile : %.1f ✎ Modifier", nominalGlide),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4ADE80)
                    )
                }
            }

            // Obstacle detour info if avoided
            if (route.hasAvoidedObstacles) {
                Text(
                    text = "⛰ Contournement relief : ${route.avoidedObstacleNames.joinToString()}",
                    fontSize = 11.sp,
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

// ── Mode Switcher Bar ────────────────────────────────────────────────────────

@Composable
private fun MapModeSwitcherBar(
    navMode: MapNavigationMode,
    onSelectMode: (MapNavigationMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF20F172A),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (navMode == MapNavigationMode.LIVE_PILOT) Color(0xFF0284C7) else Color.Transparent)
                    .clickable { onSelectMode(MapNavigationMode.LIVE_PILOT) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "🪂 En Vol (Direct)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (navMode == MapNavigationMode.LIVE_PILOT) Color.White else Color(0xFF94A3B8)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (navMode == MapNavigationMode.PLANNING_ITINERARY) Color(0xFF0284C7) else Color.Transparent)
                    .clickable { onSelectMode(MapNavigationMode.PLANNING_ITINERARY) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "🗺 Itinéraire",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (navMode == MapNavigationMode.PLANNING_ITINERARY) Color.White else Color(0xFF94A3B8)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (navMode == MapNavigationMode.ZONE_DOWNLOAD) Color(0xFF0284C7) else Color.Transparent)
                    .clickable { onSelectMode(MapNavigationMode.ZONE_DOWNLOAD) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "📥 Relief (Multi)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (navMode == MapNavigationMode.ZONE_DOWNLOAD) Color.White else Color(0xFF94A3B8)
                )
            }
        }
    }
}

// ── Planning Action Toolbar ──────────────────────────────────────────────────

@Composable
private fun PlanningActionBar(
    activeAction: PlanningAction,
    hasStart: Boolean,
    hasFinish: Boolean,
    waypointCount: Int,
    onSelectAction: (PlanningAction) -> Unit,
    onReverse: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xF20F172A),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlanningActionChip(
                    label = if (hasStart) "Départ ✓" else "+ Départ",
                    icon = "🟢",
                    isSelected = activeAction == PlanningAction.SET_START,
                    activeColor = Color(0xFF22C55E),
                    onClick = { onSelectAction(PlanningAction.SET_START) }
                )

                PlanningActionChip(
                    label = if (waypointCount > 0) "+ Étape ($waypointCount)" else "+ Étape",
                    icon = "🟡",
                    isSelected = activeAction == PlanningAction.ADD_WAYPOINT,
                    activeColor = Color(0xFFF59E0B),
                    onClick = { onSelectAction(PlanningAction.ADD_WAYPOINT) }
                )

                PlanningActionChip(
                    label = if (hasFinish) "Arrivée ✓" else "+ Arrivée",
                    icon = "🏁",
                    isSelected = activeAction == PlanningAction.SET_FINISH,
                    activeColor = Color(0xFFEF4444),
                    onClick = { onSelectAction(PlanningAction.SET_FINISH) }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasStart && hasFinish) {
                    IconButton(
                        onClick = onReverse,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("⇄", fontSize = 16.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    }
                }
                if (hasStart || hasFinish || waypointCount > 0) {
                    IconButton(
                        onClick = onClearAll,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("🗑", fontSize = 14.sp, color = Color(0xFFF87171))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanningActionChip(
    label: String,
    icon: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) activeColor.copy(alpha = 0.25f) else Color(0xFF1E293B),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) activeColor else Color(0xFF475569)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, fontSize = 11.sp)
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) activeColor else Color(0xFFE2E8F0)
            )
        }
    }
}

// ── Multi-point Itinerary Route Card ─────────────────────────────────────────

@Composable
private fun ItineraryRouteCard(
    itinerary: ItineraryResult?,
    startPoint: GeoPoint?,
    finishPoint: GeoPoint?,
    intermediateWaypoints: List<GeoPoint>,
    startAltitudeM: Float,
    nominalGlide: Float,
    onAdjustStartAlt: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onClearItinerary: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (itinerary != null && itinerary.isReachable) Color(0xFF22C55E)
                else if (itinerary != null) Color(0xFFEF4444)
                else Color(0xFF334155)
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🗺 Itinéraire & Portée",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Effacer",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.clickable { onClearItinerary() }
                )
            }

            if (startPoint == null) {
                Text(
                    text = "👉 Touchez la carte pour définir le point de Départ (🟢)",
                    fontSize = 13.sp,
                    color = Color(0xFF86EFAC)
                )
            } else if (finishPoint == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "🟢 Départ fixé (${startAltitudeM.toInt()} m)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF86EFAC)
                    )
                    Text(
                        text = "👉 Touchez la carte pour définir l'Arrivée (🏁) ou ajoutez des étapes (🟡)",
                        fontSize = 12.sp,
                        color = Color(0xFFFDE68A)
                    )
                }
            } else if (itinerary != null) {
                val distKm = itinerary.totalDistanceM / 1000f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "Distance : %.2f km", distKm),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (itinerary.isReachable) Color(0x3322C55E) else Color(0x33EF4444))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (itinerary.isReachable) "ATTEIGNABLE" else "TROP BAS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (itinerary.isReachable) Color(0xFF4ADE80) else Color(0xFFF87171)
                        )
                    }
                }

                // Altitudes & Déco controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Déco : ${itinerary.startAltM.toInt()} m",
                            fontSize = 12.sp,
                            color = Color(0xFF86EFAC),
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF334155))
                                .clickable { onAdjustStartAlt(-50f) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("-", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF334155))
                                .clickable { onAdjustStartAlt(50f) }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text("+", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "Arrivée : %d m", itinerary.finishAltM.toInt()),
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Text(
                        text = String.format(Locale.US, "ΔH : %+d m", (-itinerary.totalDenivM).toInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (itinerary.totalDenivM > 0f) Color(0xFF38BDF8) else Color(0xFFF87171)
                    )
                }

                // Required Glide & Nominal Wing Glide
                val reqGlideText = if (itinerary.requiredGlideRatio.isInfinite() || itinerary.requiredGlideRatio <= 0f) {
                    "Impossible"
                } else {
                    String.format(Locale.US, "%.1f", itinerary.requiredGlideRatio)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Finesse requise : $reqGlideText",
                        fontSize = 13.sp,
                        color = if (itinerary.isReachable) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .clickable { onOpenSettings() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "Aile : %.1f ✎", nominalGlide),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }

                // If multi-leg itinerary (intermediate waypoints exist), display leg breakdown
                if (itinerary.legs.size > 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF131B2E))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itinerary.legs.forEachIndexed { idx, leg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${leg.fromLabel} ➔ ${leg.toLabel}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFCBD5E1)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f km", leg.distanceM / 1000f),
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                    val legGlideText = if (leg.requiredGlide.isInfinite() || leg.requiredGlide <= 0f) {
                                        "Inf."
                                    } else {
                                        String.format(Locale.US, "%.1f", leg.requiredGlide)
                                    }
                                    Text(
                                        text = "Fin. $legGlideText",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (leg.isReachable) Color(0xFF4ADE80) else Color(0xFFF87171)
                                    )
                                    if (idx < intermediateWaypoints.size) {
                                        Text(
                                            text = "✕",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8),
                                            modifier = Modifier
                                                .clickable { onRemoveWaypoint(idx) }
                                                .padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Obstacle detour info if any
                if (itinerary.hasAvoidedObstacles) {
                    Text(
                        text = "⛰ Contournement : ${itinerary.avoidedObstacleNames.joinToString()}",
                        fontSize = 11.sp,
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ── Prominent Recenter Button ────────────────────────────────────────────────

@Composable
private fun RecenterPilotButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
        border = BorderStroke(2.dp, Color(0xFF38BDF8)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TargetBullseyeIcon(modifier = Modifier.size(20.dp), tint = Color.White)
            Text(
                text = "Recibler Pilote",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * High-contrast target bullseye crosshair icon.
 */
@Composable
private fun TargetBullseyeIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val outerRadius = minOf(w, h) * 0.44f
        val innerRadius = outerRadius * 0.52f

        // Outer circle
        drawCircle(
            color = tint,
            radius = outerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.8.dp.toPx())
        )
        // Inner circle
        drawCircle(
            color = tint,
            radius = innerRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.4.dp.toPx())
        )
        // Center dot
        drawCircle(
            color = tint,
            radius = 1.8.dp.toPx(),
            center = Offset(cx, cy)
        )
        // Crosshair: horizontal
        drawLine(
            color = tint,
            start = Offset(cx - outerRadius - 2.dp.toPx(), cy),
            end = Offset(cx + outerRadius + 2.dp.toPx(), cy),
            strokeWidth = 1.5.dp.toPx()
        )
        // Crosshair: vertical
        drawLine(
            color = tint,
            start = Offset(cx, cy - outerRadius - 2.dp.toPx()),
            end = Offset(cx, cy + outerRadius + 2.dp.toPx()),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

// ── Floating Round Button ────────────────────────────────────────────────────

@Composable
private fun FloatingCircleButton(
    iconText: String,
    onClick: () -> Unit,
    isActive: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF0284C7) else Color(0xD91E293B))
            .border(1.dp, Color(0xFF475569), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = iconText,
            fontSize = 18.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Paraglider Category Presets ──────────────────────────────────────────────

private data class WingCategoryPreset(val label: String, val category: String, val ratio: Float)

private val WING_PRESETS = listOf(
    WingCategoryPreset("Initiation", "Débutant", 6.5f),
    WingCategoryPreset("EN-A", "Loisir", 8.0f),
    WingCategoryPreset("EN-B", "Progression", 9.0f),
    WingCategoryPreset("EN-C", "Performance", 10.5f),
    WingCategoryPreset("CCC / Compétition", "Race", 12.0f)
)

/**
 * Interactive bottom control card for multi-zone terrain tile selection and download.
 */
@Composable
private fun MultiZoneSelectionBottomCard(
    selectedTiles: Set<String>,
    installedTiles: Set<String>,
    currentTileName: String,
    onToggleTile: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAddCurrentTile: () -> Unit,
    onAddAlpsPreset: () -> Unit,
    onStartDownload: () -> Unit,
    onBackToMap: () -> Unit,
    isDownloading: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
        border = BorderStroke(1.dp, Color(0xFF0284C7))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: count and estimated size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "📥 Téléchargement Multi-Zones",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (selectedTiles.isEmpty()) "Aucune zone sélectionnée. Touchez la carte !"
                               else "${selectedTiles.size} zone${if (selectedTiles.size > 1) "s" else ""} (~${String.format(Locale.US, "%.1f", selectedTiles.size * 1.5)} Mo)",
                        fontSize = 12.sp,
                        color = if (selectedTiles.isEmpty()) Color(0xFF94A3B8) else Color(0xFF38BDF8),
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    onClick = onBackToMap,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Text(
                        text = "Fermer",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Quick preset chips
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = onAddCurrentTile,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Text(
                        text = "+ Zone actuelle (${currentTileName.substringBeforeLast(".hgt")})",
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    onClick = onAddAlpsPreset,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(1.dp, Color(0xFF4ADE80))
                ) {
                    Text(
                        text = "+ Pack Alpes (3 zones)",
                        fontSize = 11.sp,
                        color = Color(0xFF86EFAC),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (selectedTiles.isNotEmpty()) {
                    Surface(
                        onClick = onClearSelection,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF334155)
                    ) {
                        Text(
                            text = "✕ Tout effacer",
                            fontSize = 11.sp,
                            color = Color(0xFFFCA5A5),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Selected tiles chip row
            if (selectedTiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedTiles.forEach { tile ->
                        val isAlreadyInstalled = installedTiles.contains(tile)
                        val baseName = tile.substringBeforeLast(".hgt")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isAlreadyInstalled) Color(0xFF14532D) else Color(0xFF0369A1),
                            border = BorderStroke(1.dp, if (isAlreadyInstalled) Color(0xFF4ADE80) else Color(0xFF38BDF8))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$baseName ${if (isAlreadyInstalled) "(installée)" else ""}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "✕",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1),
                                    modifier = Modifier.clickable { onToggleTile(tile) }
                                )
                            }
                        }
                    }
                }
            }

            // Primary Download Button (Triggers concurrent download and automatic return to map)
            Button(
                onClick = onStartDownload,
                enabled = selectedTiles.isNotEmpty() && !isDownloading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0284C7),
                    disabledContainerColor = Color(0xFF334155)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (isDownloading) "⏳" else "📥", fontSize = 16.sp)
                    Text(
                        text = if (isDownloading) "Téléchargement simultané en cours..."
                               else if (selectedTiles.isEmpty()) "Sélectionnez au moins une zone sur la carte"
                               else "Télécharger les ${selectedTiles.size} zones sélectionnées",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ── Unified Glide & Map Settings Bottom Sheet ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideSettingsBottomSheet(
    currentSettings: GlideSettings,
    activeBasemap: BasemapProvider? = null,
    onDismiss: () -> Unit,
    onUpdateSettings: (GlideSettings) -> Unit,
    onSelectBasemap: ((BasemapProvider) -> Unit)? = null,
    currentLat: Double = 45.92,
    currentLon: Double = 6.87,
    onOpenZoneMapSelector: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var glideRatio by remember { mutableFloatStateOf(currentSettings.glideRatio) }
    var glideEnabled by remember { mutableStateOf(currentSettings.isGlideAreaEnabled) }
    var useHeatmap by remember { mutableStateOf(currentSettings.useHeatmapMode) }
    val availableBasemaps = remember { BasemapRegistry.getAvailableProviders() }

    // Offline SRTM Terrain state
    val currentTileName = remember(currentLat, currentLon) {
        HgtTerrainManager.getTileFilename(currentLat, currentLon)
    }
    var installedTiles by remember { mutableStateOf(HgtTerrainManager.getInstalledTileInfos()) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadTileTarget by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatusMsg by remember { mutableStateOf<String?>(null) }

    val hgtFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri) ?: "imported.hgt"
            scope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val ok = HgtTerrainManager.importHgtFile(stream, fileName)
                        if (ok) {
                            installedTiles = HgtTerrainManager.getInstalledTileInfos()
                            Toast.makeText(context, "Dalle $fileName importée avec succès !", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Fichier invalide. Nom attendu: ex: N45E005.hgt", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Erreur d'import : ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Paramètres Finesse & Navigation",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // ── Section: Prominent Glide Ratio ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Finesse de l'aile (Glide Ratio)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Définit le rayon de plané atteignable en tenant compte du relief et du dénivelé terrain (ΔH).",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f", glideRatio),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF4ADE80),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }

                    // Quick paraglider category presets
                    Text(
                        text = "Catégorie de voile :",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFCBD5E1)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WING_PRESETS.forEach { preset ->
                            val isSelected = abs(glideRatio - preset.ratio) < 0.1f
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF166534) else Color(0xFF0F172A))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF4ADE80) else Color(0xFF334155),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        glideRatio = preset.ratio
                                        onUpdateSettings(currentSettings.copy(glideRatio = preset.ratio, isGlideAreaEnabled = glideEnabled, useHeatmapMode = useHeatmap))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = preset.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF86EFAC) else Color(0xFFCBD5E1)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f", preset.ratio),
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }

                    // Fine slider (4.0 to 14.0)
                    Slider(
                        value = glideRatio,
                        onValueChange = {
                            glideRatio = (it * 10f).roundToInt() / 10f
                            onUpdateSettings(currentSettings.copy(glideRatio = glideRatio, isGlideAreaEnabled = glideEnabled, useHeatmapMode = useHeatmap))
                        },
                        valueRange = 4.0f..14.0f,
                        steps = 99,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4ADE80),
                            activeTrackColor = Color(0xFF4ADE80),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }

            // ── Section: Difficulty Color Gradient Legend ───────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (useHeatmap) "Légende Carte thermique SpotAir (Hauteur / Sol) :" else "Légende du gradient de difficulté :",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    // Gradient bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    if (useHeatmap) {
                                        listOf(
                                            Color(0xFF1565C0), // Bleu foncé (>1200m)
                                            Color(0xFF1E88E5), // Bleu
                                            Color(0xFF00ACC1), // Cyan
                                            Color(0xFF43A047), // Vert
                                            Color(0xFF9E9D24), // Jaune-vert
                                            Color(0xFFF9A825), // Jaune
                                            Color(0xFFEF6C00), // Orange
                                            Color(0xFFC62828)  // Rouge (<50m)
                                        )
                                    } else {
                                        listOf(
                                            Color(0xFF22C55E),
                                            Color(0xFF84CC16),
                                            Color(0xFFEAB308),
                                            Color(0xFFF97316),
                                            Color(0xFFEF4444)
                                        )
                                    }
                                )
                            )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (useHeatmap) {
                            LegendBullet(color = Color(0xFF1E88E5), text = "Bleu : AGL > 800m (Marge très confortable)")
                            LegendBullet(color = Color(0xFF43A047), text = "Vert : AGL 300–500m (Marge confortable)")
                            LegendBullet(color = Color(0xFFF9A825), text = "Jaune : AGL 80–150m (Prudence)")
                            LegendBullet(color = Color(0xFFC62828), text = "Orange / Rouge : AGL < 80m (Zone critique, finesse max)")
                            LegendBullet(color = Color(0xFF38BDF8), text = "⛰ Ombrage relief : Crêtes bloquantes et masquage direct")
                        } else {
                            LegendBullet(color = Color(0xFF22C55E), text = "Vert : Finesse requise ≤ Finesse / 4 (Marge maximale x4)")
                            LegendBullet(color = Color(0xFFEAB308), text = "Jaune : Finesse requise ~ 50%")
                            LegendBullet(color = Color(0xFFF97316), text = "Orange : Finesse requise ~ 75%")
                            LegendBullet(color = Color(0xFFEF4444), text = "Rouge : Limite de finesse (dans les 10% de la portée max)")
                            LegendBullet(color = Color(0xFF38BDF8), text = "⛰ Calcul tenant compte du dénivelé du relief")
                        }
                    }
                }
            }

            // ── Section: Reachable Glide Area Toggle & Mode Selector ────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Zone d'accessibilité",
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Affiche la portée théorique (sans vent)",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = glideEnabled,
                    onCheckedChange = {
                        glideEnabled = it
                        onUpdateSettings(currentSettings.copy(isGlideAreaEnabled = it, glideRatio = glideRatio, useHeatmapMode = useHeatmap))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4ADE80),
                        checkedTrackColor = Color(0xFF166534)
                    )
                )
            }

            if (glideEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // SpotAir Heatmap mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (useHeatmap) Color(0xFF166534) else Color(0xFF1E293B))
                            .border(
                                1.dp,
                                if (useHeatmap) Color(0xFF4ADE80) else Color(0xFF334155),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                useHeatmap = true
                                onUpdateSettings(currentSettings.copy(isGlideAreaEnabled = glideEnabled, glideRatio = glideRatio, useHeatmapMode = true))
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "🗺️ Carte thermique",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (useHeatmap) Color(0xFF86EFAC) else Color(0xFFCBD5E1)
                            )
                            Text(
                                text = "SpotAir (Relief & AGL)",
                                fontSize = 10.sp,
                                color = if (useHeatmap) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Classic Circle mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!useHeatmap) Color(0xFF166534) else Color(0xFF1E293B))
                            .border(
                                1.dp,
                                if (!useHeatmap) Color(0xFF4ADE80) else Color(0xFF334155),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                useHeatmap = false
                                onUpdateSettings(currentSettings.copy(isGlideAreaEnabled = glideEnabled, glideRatio = glideRatio, useHeatmapMode = false))
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "⭕ Cercle simple",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!useHeatmap) Color(0xFF86EFAC) else Color(0xFFCBD5E1)
                            )
                            Text(
                                text = "Gradient radial (Legacy)",
                                fontSize = 10.sp,
                                color = if (!useHeatmap) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // ── Section: Offline SRTM Terrain Relief ─────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF334155))
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⛰️ Relief Terrain Hors-ligne (SRTM)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Téléchargez en avance (Wi-Fi) pour un calcul 100% hors-ligne, ultra-rapide (<1 ms) et sans connexion en vol.",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Zone status badge
                    val isTileInstalled = installedTiles.any { it.filename.equals(currentTileName, ignoreCase = true) }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isTileInstalled) Color(0xFF14532D) else Color(0xFF78350F),
                        border = BorderStroke(1.dp, if (isTileInstalled) Color(0xFF4ADE80) else Color(0xFFF59E0B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isTileInstalled) "✓ Dalle de la zone installée : $currentTileName" else "⚠️ Dalle de la zone non installée : $currentTileName",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTileInstalled) Color(0xFF86EFAC) else Color(0xFFFDE68A)
                                )
                                Text(
                                    text = if (isTileInstalled) "Calcul de relief 100% hors-ligne actif (ultra-rapide)" else "Recommandé : téléchargez la dalle pour voler sans internet",
                                    fontSize = 11.sp,
                                    color = if (isTileInstalled) Color(0xFFBBF7D0) else Color(0xFFFCD34D)
                                )
                            }
                        }
                    }

                    // Single-tap download button for current tile
                    Button(
                        onClick = {
                            if (!isDownloading) {
                                isDownloading = true
                                downloadTileTarget = currentTileName
                                downloadProgress = 0f
                                downloadStatusMsg = "Téléchargement de $currentTileName..."
                                scope.launch {
                                    val res = HgtTerrainManager.downloadTile(currentLat, currentLon) { p ->
                                        downloadProgress = p
                                    }
                                    isDownloading = false
                                    downloadTileTarget = null
                                    if (res.isSuccess) {
                                        installedTiles = HgtTerrainManager.getInstalledTileInfos()
                                        downloadStatusMsg = "✓ Dalle $currentTileName prête !"
                                        Toast.makeText(context, "Dalle $currentTileName installée !", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val err = res.exceptionOrNull()?.message ?: "Erreur réseau"
                                        downloadStatusMsg = "❌ Échec : $err"
                                        Toast.makeText(context, "Erreur téléchargement : $err", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isDownloading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTileInstalled) Color(0xFF334155) else Color(0xFF0284C7)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(if (isTileInstalled) "🔄" else "📥", fontSize = 16.sp)
                            Text(
                                text = if (isDownloading && downloadTileTarget == currentTileName) "Téléchargement en cours..." else if (isTileInstalled) "Mettre à jour la dalle $currentTileName (~1.5 Mo)" else "Télécharger la zone actuelle $currentTileName (~1.5 Mo)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    // Interactive Multi-Zone map selector button
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenZoneMapSelector?.invoke()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0369A1)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🗺️", fontSize = 16.sp)
                            Text(
                                text = "Sélectionner des zones sur la carte (Multi-zones)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    // Download progress indicator
                    if (isDownloading) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF334155)
                            )
                            Text(
                                text = "Progression : ${(downloadProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    } else if (downloadStatusMsg != null) {
                        Text(
                            text = downloadStatusMsg!!,
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }

                    // Preset buttons for major flying spots
                    Text(
                        text = "Zones populaires de vol libre :",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFCBD5E1)
                    )

                    val popularZones = listOf(
                        Triple("🏔️ Annecy / Chamonix", 45.9, 6.8), // N45E006
                        Triple("⛰️ Dauphiné / Vercors", 45.1, 5.7), // N45E005
                        Triple("☀️ Alpes Sud (St-André)", 44.1, 6.5), // N44E006
                        Triple("🦅 Pyrénées", 42.8, 0.5), // N42E000
                        Triple("🌲 Vosges / Jura", 47.0, 6.5) // N47E006
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        popularZones.forEach { (name, lat, lon) ->
                            val tile = HgtTerrainManager.getTileFilename(lat, lon)
                            val isDownloaded = installedTiles.any { it.filename.equals(tile, ignoreCase = true) }

                            Surface(
                                onClick = {
                                    if (!isDownloading) {
                                        isDownloading = true
                                        downloadTileTarget = tile
                                        downloadProgress = 0f
                                        downloadStatusMsg = "Téléchargement de $tile ($name)..."
                                        scope.launch {
                                            val res = HgtTerrainManager.downloadTile(lat, lon) { p ->
                                                downloadProgress = p
                                            }
                                            isDownloading = false
                                            downloadTileTarget = null
                                            if (res.isSuccess) {
                                                installedTiles = HgtTerrainManager.getInstalledTileInfos()
                                                downloadStatusMsg = "✓ Dalle $tile ($name) prête !"
                                                Toast.makeText(context, "$name installé !", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val err = res.exceptionOrNull()?.message ?: "Erreur réseau"
                                                downloadStatusMsg = "❌ Échec : $err"
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isDownloaded) Color(0xFF14532D) else Color(0xFF131B2E),
                                border = BorderStroke(1.dp, if (isDownloaded) Color(0xFF4ADE80) else Color(0xFF334155))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDownloaded) Color(0xFF86EFAC) else Color.White
                                    )
                                    Text(
                                        text = if (isDownloaded) "✓" else "📥",
                                        fontSize = 11.sp,
                                        color = if (isDownloaded) Color(0xFF4ADE80) else Color(0xFF38BDF8)
                                    )
                                }
                            }
                        }
                    }

                    // Import local HGT file
                    Button(
                        onClick = {
                            hgtFilePickerLauncher.launch(arrayOf("*/*"))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("📂", fontSize = 15.sp)
                            Text(
                                text = "Importer un fichier .HGT local",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    // List of installed tiles
                    if (installedTiles.isNotEmpty()) {
                        Text(
                            text = "Dalles installées (${installedTiles.size}) :",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFCBD5E1)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            installedTiles.forEach { tileInfo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("⛰️", fontSize = 12.sp)
                                        Text(
                                            text = tileInfo.filename,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "(${tileInfo.formattedSize})",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            HgtTerrainManager.deleteTile(tileInfo.filename)
                                            installedTiles = HgtTerrainManager.getInstalledTileInfos()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text("🗑️", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Section: Basemap Selection (if provided) ─────────────────────
            if (activeBasemap != null && onSelectBasemap != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Fournisseur de Fond de Carte (Hors-ligne / Relief)",
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    availableBasemaps.forEach { provider ->
                        val isSelected = provider.id == activeBasemap.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectBasemap(provider) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF1E293B) else Color(0xFF131B2E)
                            ),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color(0xFF38BDF8) else Color.White
                                    )
                                    Text(
                                        text = provider.description,
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                if (isSelected) {
                                    Text("✓", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

@Composable
private fun LegendBullet(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            fontSize = 11.sp,
            color = Color(0xFFCBD5E1)
        )
    }
}

// ── Track History Bottom Sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackHistoryBottomSheet(
    currentlySelectedTrackName: String? = null,
    onDismiss: () -> Unit,
    onSelectTrackToView: (File) -> Unit,
    onClearSelectedTrack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tracks by remember { mutableStateOf(GpxTrackManager.getSavedTracks(context)) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val imported = GpxTrackManager.importTrackFromUri(context, uri)
            if (imported != null) {
                val pts = GpxTrackManager.loadTrackPoints(imported)
                if (pts.isNotEmpty()) {
                    tracks = GpxTrackManager.getSavedTracks(context)
                    onSelectTrackToView(imported)
                } else {
                    Toast.makeText(context, "Le fichier GPX sélectionné ne contient aucun point valide.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Erreur lors de l'importation du fichier GPX.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Traces GPX",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${tracks.size} traces enregistrées",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("📂 Ouvrir fichier...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Aucune trace enregistrée pour le moment.\nLes traces sont créées lors des vols.",
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("📂 Importer un fichier GPX")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tracks, key = { it.id }) { track ->
                        val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
                        val dateStr = dateFmt.format(Date(track.startTimeMs))
                        val durationMin = track.durationSec / 60
                        val durationSec = track.durationSec % 60
                        val isSelected = track.file.name == currentlySelectedTrackName

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isSelected) {
                                        onClearSelectedTrack?.invoke()
                                    } else {
                                        onSelectTrackToView(track.file)
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            border = if (isSelected) BorderStroke(1.5.dp, Color(0xFF38BDF8)) else null
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${track.activityType.emoji} $dateStr",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        if (isSelected) {
                                            Surface(
                                                color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(1.dp, Color(0xFF38BDF8))
                                            ) {
                                                Text(
                                                    text = "✓ Affichée",
                                                    color = Color(0xFF38BDF8),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = String.format(Locale.US, "%02d:%02d", durationMin, durationSec),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF4ADE80)
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Plafond : ${track.maxAltitudeM.toInt()} m",
                                        fontSize = 13.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Text(
                                        text = "${track.pointCount} points",
                                        fontSize = 13.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Actions: View/Hide, Share, Delete
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelected) {
                                        Button(
                                            onClick = { onClearSelectedTrack?.invoke() },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Masquer", fontSize = 12.sp)
                                        }
                                    } else {
                                        Button(
                                            onClick = { onSelectTrackToView(track.file) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Afficher", fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = { GpxTrackManager.shareTrack(context, track.file) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Partager", fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    IconButton(
                                        onClick = {
                                            if (isSelected) {
                                                onClearSelectedTrack?.invoke()
                                            }
                                            GpxTrackManager.deleteTrack(track.file)
                                            tracks = GpxTrackManager.getSavedTracks(context)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("🗑", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

// ── GPX Track Profile & Finger Scrubber Card ────────────────────────────────

@Composable
fun TrackProfileCard(
    profileData: TrackProfileData,
    scrubbedIndex: Int?,
    onScrub: (Int) -> Unit,
    onCenterOnPoint: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF20F172A)),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Title, point count, center button, and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "📈", fontSize = 16.sp)
                    Text(
                        text = "Profil du vol",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "(${profileData.points.size} pts)",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (scrubbedIndex != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0369A1),
                            modifier = Modifier.clickable { onCenterOnPoint(scrubbedIndex) }
                        ) {
                            Text(
                                text = "📍 Centrer",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "✕",
                        color = Color(0xFF94A3B8),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onClose() }
                            .padding(4.dp)
                    )
                }
            }

            // Summary stats chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val totalKm = profileData.totalDistanceM / 1000f
                ProfileStatBadge("Distance", String.format(Locale.US, "%.1f km", totalKm), Color(0xFF38BDF8))
                ProfileStatBadge("Dénivelé", "+${profileData.elevationGainM.toInt()}m / -${profileData.elevationLossM.toInt()}m", Color(0xFF4ADE80))
                ProfileStatBadge("Altitude", "${profileData.minAltitudeM.toInt()}m – ${profileData.maxAltitudeM.toInt()}m", Color(0xFFFBBF24))
                val durMin = profileData.durationSec / 60
                val durSec = profileData.durationSec % 60
                ProfileStatBadge("Durée", "${durMin}m ${durSec}s", Color(0xFFA78BFA))
                if (profileData.maxSpeedKmh > 0f) {
                    ProfileStatBadge("Vitesse max", "${profileData.maxSpeedKmh.toInt()} km/h", Color(0xFFF472B6))
                }
                if (profileData.maxClimbVz > 0f || profileData.maxSinkVz < 0f) {
                    ProfileStatBadge("Vz max/min", "${formatVzLocal(profileData.maxClimbVz)} / ${formatVzLocal(profileData.maxSinkVz)}", Color(0xFF34D399))
                }
            }

            // Interactive Elevation Graph with Finger Scrubbing
            TrackElevationProfileGraph(
                profileData = profileData,
                scrubbedIndex = scrubbedIndex,
                onScrub = onScrub,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
            )
        }
    }
}

@Composable
fun TrackElevationProfileGraph(
    profileData: TrackProfileData,
    scrubbedIndex: Int?,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val points = profileData.points
    if (points.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Pas assez de points pour afficher le profil", color = Color(0xFF64748B), fontSize = 12.sp)
        }
        return
    }

    val totalDist = profileData.totalDistanceM.coerceAtLeast(1f)
    val minAlt = profileData.minAltitudeM
    val maxAlt = profileData.maxAltitudeM
    val altRange = (maxAlt - minAlt).coerceAtLeast(10f)

    // Helper to find closest point given an x coordinate in pixels
    fun updateFromX(xPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        val fraction = (xPx / widthPx).coerceIn(0f, 1f)
        val targetDist = fraction * totalDist
        var low = 0
        var high = points.size - 1
        var bestIndex = 0
        var bestDiff = Float.MAX_VALUE
        while (low <= high) {
            val mid = (low + high) ushr 1
            val p = points[mid]
            val diff = kotlin.math.abs(p.distanceM - targetDist)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = mid
            }
            if (p.distanceM < targetDist) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        onScrub(bestIndex)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF090D14))
            .pointerInput(profileData) {
                detectTapGestures { offset ->
                    updateFromX(offset.x, size.width.toFloat())
                }
            }
            .pointerInput(profileData) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromX(change.position.x, size.width.toFloat())
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val w = size.width
            val h = size.height
            val graphBottom = h - 16f
            val graphTop = 18f
            val graphH = (graphBottom - graphTop).coerceAtLeast(10f)

            val textPaint = Paint().apply {
                color = AndroidColor.argb(130, 148, 163, 184)
                textSize = 22f
                isAntiAlias = true
            }

            // High line
            drawLine(
                color = Color(0x22FFFFFF),
                start = Offset(0f, graphTop),
                end = Offset(w, graphTop),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText("${maxAlt.toInt()}m", 4f, graphTop + 16f, textPaint)

            // Low line
            drawLine(
                color = Color(0x22FFFFFF),
                start = Offset(0f, graphBottom),
                end = Offset(w, graphBottom),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText("${minAlt.toInt()}m", 4f, graphBottom - 4f, textPaint)

            // Construct elevation curve path
            val linePath = Path()
            val fillPath = Path()

            val step = (points.size / 400).coerceAtLeast(1)

            for (i in 0 until points.size step step) {
                val pt = points[i]
                val x = (pt.distanceM / totalDist) * w
                val normAlt = ((pt.altitudeM - minAlt) / altRange).coerceIn(0f, 1f)
                val y = graphBottom - (normAlt * graphH)

                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, graphBottom)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // Ensure last point is connected
            val lastPt = points.last()
            val lastX = w
            val lastNormAlt = ((lastPt.altitudeM - minAlt) / altRange).coerceIn(0f, 1f)
            val lastY = graphBottom - (lastNormAlt * graphH)
            linePath.lineTo(lastX, lastY)
            fillPath.lineTo(lastX, lastY)
            fillPath.lineTo(lastX, graphBottom)
            fillPath.close()

            // Draw translucent area fill below curve
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x550EA5E9), Color(0x050EA5E9)),
                    startY = graphTop,
                    endY = graphBottom
                )
            )

            // Draw profile curve
            drawPath(
                path = linePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF38BDF8), Color(0xFFFBBF24))
                ),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw interactive Scrubber indicator if finger active
            if (scrubbedIndex != null && scrubbedIndex in points.indices) {
                val sPt = points[scrubbedIndex]
                val sX = ((sPt.distanceM / totalDist) * w).coerceIn(0f, w)
                val sNormAlt = ((sPt.altitudeM - minAlt) / altRange).coerceIn(0f, 1f)
                val sY = graphBottom - (sNormAlt * graphH)

                // Vertical hairline indicator
                drawLine(
                    color = Color(0xFFF59E0B),
                    start = Offset(sX, graphTop),
                    end = Offset(sX, graphBottom),
                    strokeWidth = 2.dp.toPx()
                )

                // Outer beacon glow
                drawCircle(
                    color = Color(0xFFF59E0B),
                    radius = 6.dp.toPx(),
                    center = Offset(sX, sY)
                )
                // Inner bright center dot
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = Offset(sX, sY)
                )
            }
        }

        // Floating tooltip badge when scrubbing
        if (scrubbedIndex != null && scrubbedIndex in points.indices) {
            val sPt = points[scrubbedIndex]
            val kmStr = String.format(Locale.US, "%.2f", sPt.distanceM / 1000f)
            val altStr = "${sPt.altitudeM.toInt()}m"
            val vzStr = String.format(Locale.US, "%+.1f m/s", sPt.vzMs)
            val spdStr = "${sPt.speedKmh.toInt()} km/h"

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xEE1E293B))
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "📍 $kmStr km  ▲ $altStr  ⚡ $vzStr  ✈ $spdStr",
                    fontSize = 11.sp,
                    color = Color(0xFFFDE68A),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ProfileStatBadge(label: String, value: String, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 10.sp, color = Color(0xFF94A3B8))
            Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accentColor)
        }
    }
}

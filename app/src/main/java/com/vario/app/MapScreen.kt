package com.vario.app

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.Toast
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
    PLANNING_ITINERARY  // Setup 2 points (Start & Finish) + optional intermediate waypoints
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
    var loadedPastTrackName by remember { mutableStateOf<String?>(null) }
    var isFollowingPilot by remember { mutableStateOf(true) }

    val trackProfileData = remember(loadedPastTrack) {
        loadedPastTrack?.let { GpxTrackManager.computeTrackProfile(it) }
    }
    var showTrackDetails by remember { mutableStateOf(false) }
    var scrubbedPointIndex by remember { mutableStateOf<Int?>(null) }

    // Sheets
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }

    // References to map objects
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val glideOverlay = remember { GlideOverlay() }
    var pilotMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var targetMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var startMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var finishMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var scrubberMarkerRef by remember { mutableStateOf<Marker?>(null) }
    val waypointMarkersRef = remember { mutableListOf<Marker>() }

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
                            if (currentNavMode == MapNavigationMode.PLANNING_ITINERARY) {
                                when (currentPlanningAction) {
                                    PlanningAction.SET_START -> {
                                        startPoint = p
                                        if (currentIsStartAltAuto) {
                                            val ground = TerrainElevationProvider.getElevation(p.latitude, p.longitude)
                                            startAltitudeM = ground + 100f
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
                    overlays.add(glideOverlay)
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

                    // Update pilot marker and live glide cone
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
                            TerrainElevationProvider.getElevation(varioData.latitude, varioData.longitude)
                        }
                        glideOverlay.updateState(
                            position = pilotGeo,
                            altM = varioData.altitudeM,
                            groundAltM = groundRefAlt,
                            gr = glideSettings.glideRatio,
                            enabled = glideSettings.isGlideAreaEnabled
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
                            TerrainElevationProvider.getElevation(sp.latitude, sp.longitude)
                        }
                        glideOverlay.updateState(
                            position = sp,
                            altM = startAltitudeM,
                            groundAltM = groundRefAlt,
                            gr = glideSettings.glideRatio,
                            enabled = glideSettings.isGlideAreaEnabled
                        )
                    } else {
                        glideOverlay.updateState(
                            position = GeoPoint(0.0, 0.0),
                            altM = 0f,
                            groundAltM = 0f,
                            gr = glideSettings.glideRatio,
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
                } else {
                    pastTrackPolyline.setPoints(emptyList())
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

        // ── Route & Target Information Card ─────────────────────────────────
        if (navMode == MapNavigationMode.LIVE_PILOT) {
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
        } else {
            // Planning Itinerary Mode bottom card
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
                                showTrackDetails = false
                                scrubbedPointIndex = null
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
                }
            )
        }

        // ── Tracks Archive Bottom Sheet ──────────────────────────────────────
        if (showTracksSheet) {
            TrackHistoryBottomSheet(
                onDismiss = { showTracksSheet = false },
                onSelectTrackToView = { file ->
                    val points = GpxTrackManager.loadTrackPoints(file)
                    loadedPastTrack = points
                    loadedPastTrackName = file.name
                    showTrackDetails = true
                    scrubbedPointIndex = null
                    if (points.isNotEmpty()) {
                        mapViewRef?.controller?.animateTo(
                            GeoPoint(points.first().latitude, points.first().longitude)
                        )
                    }
                    showTracksSheet = false
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
                        color = if (varioData.isFlightActive) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                    )
                    Text("Temps", fontSize = 9.sp, color = Color(0xFF94A3B8))
                }
            }

            // Right icons: GPX Tracks & Settings
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onOpenTracks) {
                    Text("📁", fontSize = 16.sp)
                }
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", fontSize = 16.sp)
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
                    text = "🗺 Itinéraire (2 pts / Étapes)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (navMode == MapNavigationMode.PLANNING_ITINERARY) Color.White else Color(0xFF94A3B8)
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

// ── Unified Glide & Map Settings Bottom Sheet ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideSettingsBottomSheet(
    currentSettings: GlideSettings,
    activeBasemap: BasemapProvider? = null,
    onDismiss: () -> Unit,
    onUpdateSettings: (GlideSettings) -> Unit,
    onSelectBasemap: ((BasemapProvider) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var glideRatio by remember { mutableFloatStateOf(currentSettings.glideRatio) }
    var glideEnabled by remember { mutableStateOf(currentSettings.isGlideAreaEnabled) }
    val availableBasemaps = remember { BasemapRegistry.getAvailableProviders() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                                        onUpdateSettings(currentSettings.copy(glideRatio = preset.ratio, isGlideAreaEnabled = glideEnabled))
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
                            onUpdateSettings(currentSettings.copy(glideRatio = glideRatio, isGlideAreaEnabled = glideEnabled))
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
                        text = "Légende du gradient de difficulté :",
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
                                    listOf(
                                        Color(0xFF22C55E),
                                        Color(0xFF84CC16),
                                        Color(0xFFEAB308),
                                        Color(0xFFF97316),
                                        Color(0xFFEF4444)
                                    )
                                )
                            )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LegendBullet(color = Color(0xFF22C55E), text = "Vert : Finesse requise ≤ Finesse / 4 (Marge de sécurité maximale x4)")
                        LegendBullet(color = Color(0xFFEAB308), text = "Jaune : Finesse requise ~ 50%")
                        LegendBullet(color = Color(0xFFF97316), text = "Orange : Finesse requise ~ 75%")
                        LegendBullet(color = Color(0xFFEF4444), text = "Rouge : Limite de finesse (dans les 10% de la portée max)")
                        LegendBullet(color = Color(0xFF38BDF8), text = "⛰ Calcul tenant compte du dénivelé du relief")
                    }
                }
            }

            // ── Section: Reachable Glide Area Toggle ────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cône d'atteignabilité (Gradient)",
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
                        onUpdateSettings(currentSettings.copy(isGlideAreaEnabled = it, glideRatio = glideRatio))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4ADE80),
                        checkedTrackColor = Color(0xFF166534)
                    )
                )
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
    onDismiss: () -> Unit,
    onSelectTrackToView: (File) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tracks by remember { mutableStateOf(GpxTrackManager.getSavedTracks(context)) }

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
                Text(
                    text = "Traces GPX Enregistrées",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${tracks.size} vols",
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune trace enregistrée pour le moment.\nLes traces sont créées lors des vols.",
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
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

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateStr,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
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

                                // Actions: View, Share, Delete
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { onSelectTrackToView(track.file) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Afficher", fontSize = 12.sp)
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

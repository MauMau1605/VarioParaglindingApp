package com.vario.app

import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Representation of an impassable terrain obstacle (peak, ridge, or restricted airspace).
 */
data class TerrainObstacle(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val peakElevationM: Float,
    val radiusM: Float
)

/**
 * Result of the navigation & obstacle routing calculation.
 */
data class RouteResult(
    val waypoints: List<GeoPoint>,
    val totalDistanceM: Float,
    val directDistanceM: Float,
    val requiredGlideRatio: Float,
    val isReachable: Boolean,
    val hasAvoidedObstacles: Boolean,
    val avoidedObstacleNames: List<String> = emptyList()
)

/**
 * Single leg of an itinerary between two waypoints.
 */
data class ItineraryLeg(
    val legIndex: Int,
    val from: GeoPoint,
    val to: GeoPoint,
    val fromLabel: String,
    val toLabel: String,
    val distanceM: Float,
    val fromAltM: Float,
    val toAltM: Float,
    val denivM: Float,
    val requiredGlide: Float,
    val isReachable: Boolean,
    val waypoints: List<GeoPoint>
)

/**
 * Full multi-point itinerary calculation result.
 */
data class ItineraryResult(
    val points: List<GeoPoint>,
    val legs: List<ItineraryLeg>,
    val totalDistanceM: Float,
    val startAltM: Float,
    val finishAltM: Float,
    val totalDenivM: Float,
    val requiredGlideRatio: Float,
    val isReachable: Boolean,
    val polylinePoints: List<GeoPoint>,
    val hasAvoidedObstacles: Boolean,
    val avoidedObstacleNames: List<String> = emptyList()
)

/**
 * Obstacle-aware navigation and theoretical glide routing engine.
 *
 * Checks direct line-of-sight glide slope against terrain obstacles:
 *   Glide slope: Alt(d) = Alt_pilot - (d / GlideRatio)
 * If an obstacle peak exceeds glide slope altitude, it plans a safe detour
 * around the obstacle boundary to reach the target point.
 */
object ObstacleRoutingEngine {

    // Common registered alpine/mountain obstacles & high ridges
    private val registeredObstacles = mutableListOf<TerrainObstacle>()

    init {
        // Sample baseline terrain obstacles (high peaks)
        // Pilots can also define custom obstacles
        registeredObstacles.add(
            TerrainObstacle(
                id = "dent_de_crolles",
                name = "Dent de Crolles (Sommet)",
                latitude = 45.3022,
                longitude = 5.8564,
                peakElevationM = 2062f,
                radiusM = 600f
            )
        )
        registeredObstacles.add(
            TerrainObstacle(
                id = "chamechaude",
                name = "Chamechaude",
                latitude = 45.2872,
                longitude = 5.7906,
                peakElevationM = 2082f,
                radiusM = 750f
            )
        )
        registeredObstacles.add(
            TerrainObstacle(
                id = "grand_som",
                name = "Grand Som",
                latitude = 45.3622,
                longitude = 5.8111,
                peakElevationM = 2026f,
                radiusM = 650f
            )
        )
        registeredObstacles.add(
            TerrainObstacle(
                id = "mont_aime",
                name = "Relief / Crête Locale",
                latitude = 45.2150,
                longitude = 5.8050,
                peakElevationM = 1450f,
                radiusM = 500f
            )
        )
    }

    /**
     * Registers a custom or detected obstacle.
     */
    fun addObstacle(obstacle: TerrainObstacle) {
        registeredObstacles.removeAll { it.id == obstacle.id }
        registeredObstacles.add(obstacle)
    }

    /**
     * Clears all registered obstacles.
     */
    fun clearObstacles() {
        registeredObstacles.clear()
    }

    /**
     * Returns a copy of registered obstacles.
     */
    fun getObstacles(): List<TerrainObstacle> = ArrayList(registeredObstacles)

    /**
     * Calculates the most direct navigable route between current pilot location and target waypoint.
     * If high terrain pierces the glide slope, routes around the obstacle perimeter.
     *
     * @param start Pilot current position
     * @param target Landing / goal waypoint
     * @param pilotAltM Current pilot altitude in meters
     * @param targetAltM Target elevation in meters
     * @param wingGlideRatio Wing nominal glide ratio
     * @param safetyMarginM Vertical clearance above obstacle peak (default 50m)
     */
    fun computeRoute(
        start: GeoPoint,
        target: GeoPoint,
        pilotAltM: Float,
        targetAltM: Float,
        wingGlideRatio: Float,
        safetyMarginM: Float = 50f
    ): RouteResult {
        val directDist = start.distanceToAsDouble(target).toFloat()
        val deltaAlt = pilotAltM - targetAltM

        // Identify obstacles intersecting direct path where peak > glide slope
        val blockingObstacles = mutableListOf<Pair<TerrainObstacle, Double>>()

        for (obs in registeredObstacles) {
            val obsPoint = GeoPoint(obs.latitude, obs.longitude)
            val distToStart = start.distanceToAsDouble(obsPoint)
            val distToTarget = target.distanceToAsDouble(obsPoint)

            // Check if within segment bounding box
            if (distToStart > directDist + obs.radiusM || distToTarget > directDist + obs.radiusM) {
                continue
            }

            // Perpendicular distance from obstacle center to line segment (start -> target)
            val perpDist = computePerpendicularDistance(start, target, obsPoint)
            if (perpDist <= obs.radiusM) {
                // Projection distance along line from start
                val alongDist = computeAlongTrackDistance(start, target, obsPoint)
                if (alongDist in 0.0..directDist.toDouble()) {
                    // Glide slope altitude at obstacle location:
                    // Alt(d) = pilotAlt - (alongDist / wingGlideRatio)
                    val expectedGlideAlt = pilotAltM - (alongDist.toFloat() / wingGlideRatio)
                    if (obs.peakElevationM + safetyMarginM >= expectedGlideAlt) {
                        // High terrain blocks the direct line!
                        blockingObstacles.add(Pair(obs, alongDist))
                    }
                }
            }
        }

        if (blockingObstacles.isEmpty()) {
            // Unobstructed direct line
            val reqGlide = if (deltaAlt > 0f) directDist / deltaAlt else Float.POSITIVE_INFINITY
            val reachable = reqGlide in 0f..wingGlideRatio

            return RouteResult(
                waypoints = listOf(start, target),
                totalDistanceM = directDist,
                directDistanceM = directDist,
                requiredGlideRatio = reqGlide,
                isReachable = reachable,
                hasAvoidedObstacles = false
            )
        }

        // Sort blocking obstacles along route from start to target
        blockingObstacles.sortBy { it.second }

        val routePoints = mutableListOf<GeoPoint>()
        routePoints.add(start)
        val avoidedNames = mutableListOf<String>()

        for ((obs, _) in blockingObstacles) {
            avoidedNames.add(obs.name)
            val obsCenter = GeoPoint(obs.latitude, obs.longitude)

            // Calculate bearing from start to target
            val bearing = start.bearingTo(target)
            // Detour waypoint: Offset perpendicular to left or right by (radius + clearance)
            val detourRadius = (obs.radiusM + 120.0)

            // Candidate 1: 90 degrees to the right
            val detourRight = obsCenter.destinationPoint(detourRadius, (bearing + 90.0) % 360.0)
            // Candidate 2: 90 degrees to the left
            val detourLeft = obsCenter.destinationPoint(detourRadius, (bearing - 90.0 + 360.0) % 360.0)

            // Choose detour giving shortest path from previous waypoint to target
            val lastPoint = routePoints.last()
            val distViaRight = lastPoint.distanceToAsDouble(detourRight) + detourRight.distanceToAsDouble(target)
            val distViaLeft = lastPoint.distanceToAsDouble(detourLeft) + detourLeft.distanceToAsDouble(target)

            val chosenDetour = if (distViaRight <= distViaLeft) detourRight else detourLeft
            routePoints.add(chosenDetour)
        }

        routePoints.add(target)

        // Compute total routed path distance
        var totalDist = 0f
        for (i in 0 until routePoints.size - 1) {
            totalDist += routePoints[i].distanceToAsDouble(routePoints[i + 1]).toFloat()
        }

        val requiredGlide = if (deltaAlt > 0f) totalDist / deltaAlt else Float.POSITIVE_INFINITY
        val isReachable = requiredGlide in 0f..wingGlideRatio

        return RouteResult(
            waypoints = routePoints,
            totalDistanceM = totalDist,
            directDistanceM = directDist,
            requiredGlideRatio = requiredGlide,
            isReachable = isReachable,
            hasAvoidedObstacles = true,
            avoidedObstacleNames = avoidedNames
        )
    }

    /**
     * Calculates an itinerary over a sequence of waypoints:
     * [Start (Départ), WP1, WP2, ..., Finish (Arrivée)].
     *
     * Computes individual legs, verifies obstacle clearance, and reports total distance,
     * total dénivellation (ΔH), and the required glide ratio.
     */
    fun computeItinerary(
        points: List<GeoPoint>,
        startAltM: Float,
        finishAltM: Float,
        wingGlideRatio: Float,
        safetyMarginM: Float = 50f
    ): ItineraryResult {
        if (points.size < 2) {
            return ItineraryResult(
                points = points,
                legs = emptyList(),
                totalDistanceM = 0f,
                startAltM = startAltM,
                finishAltM = finishAltM,
                totalDenivM = 0f,
                requiredGlideRatio = 0f,
                isReachable = true,
                polylinePoints = points,
                hasAvoidedObstacles = false
            )
        }

        // Calculate direct distances between consecutive waypoints
        val legDirectDistances = mutableListOf<Double>()
        var totalDirectDist = 0.0
        for (i in 0 until points.size - 1) {
            val d = points[i].distanceToAsDouble(points[i + 1])
            legDirectDistances.add(d)
            totalDirectDist += d
        }

        // Estimate intermediate waypoints glide altitudes linearly between startAltM and finishAltM
        val totalDeltaAlt = startAltM - finishAltM
        val pointAltitudes = mutableListOf<Float>()
        pointAltitudes.add(startAltM)
        var cumulativeDist = 0.0
        for (i in 1 until points.size - 1) {
            cumulativeDist += legDirectDistances[i - 1]
            val frac = if (totalDirectDist > 0.0) (cumulativeDist / totalDirectDist).toFloat() else 0f
            val interpolatedAlt = startAltM - (frac * totalDeltaAlt)
            pointAltitudes.add(interpolatedAlt)
        }
        pointAltitudes.add(finishAltM)

        val legs = mutableListOf<ItineraryLeg>()
        val polylinePoints = mutableListOf<GeoPoint>()
        val allAvoidedObstacles = mutableListOf<String>()
        var totalDistanceM = 0f
        var allLegsReachable = true

        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            val fromAlt = pointAltitudes[i]
            val toAlt = pointAltitudes[i + 1]

            val fromLabel = when (i) {
                0 -> "Départ"
                else -> "Étape $i"
            }
            val toLabel = when (i + 1) {
                points.size - 1 -> "Arrivée"
                else -> "Étape ${i + 1}"
            }

            val route = computeRoute(
                start = from,
                target = to,
                pilotAltM = fromAlt,
                targetAltM = toAlt,
                wingGlideRatio = wingGlideRatio,
                safetyMarginM = safetyMarginM
            )

            if (route.hasAvoidedObstacles) {
                allAvoidedObstacles.addAll(route.avoidedObstacleNames)
            }
            if (!route.isReachable) {
                allLegsReachable = false
            }

            totalDistanceM += route.totalDistanceM

            // Combine polyline points avoiding duplicates at joints
            if (polylinePoints.isEmpty()) {
                polylinePoints.addAll(route.waypoints)
            } else {
                if (route.waypoints.size > 1) {
                    polylinePoints.addAll(route.waypoints.subList(1, route.waypoints.size))
                }
            }

            val legDeniv = fromAlt - toAlt
            legs.add(
                ItineraryLeg(
                    legIndex = i + 1,
                    from = from,
                    to = to,
                    fromLabel = fromLabel,
                    toLabel = toLabel,
                    distanceM = route.totalDistanceM,
                    fromAltM = fromAlt,
                    toAltM = toAlt,
                    denivM = legDeniv,
                    requiredGlide = route.requiredGlideRatio,
                    isReachable = route.isReachable,
                    waypoints = route.waypoints
                )
            )
        }

        val totalDenivM = startAltM - finishAltM
        val requiredGlide = if (totalDenivM > 0f) totalDistanceM / totalDenivM else Float.POSITIVE_INFINITY
        val isOverallReachable = allLegsReachable && (requiredGlide in 0f..wingGlideRatio)

        return ItineraryResult(
            points = points,
            legs = legs,
            totalDistanceM = totalDistanceM,
            startAltM = startAltM,
            finishAltM = finishAltM,
            totalDenivM = totalDenivM,
            requiredGlideRatio = requiredGlide,
            isReachable = isOverallReachable,
            polylinePoints = polylinePoints,
            hasAvoidedObstacles = allAvoidedObstacles.isNotEmpty(),
            avoidedObstacleNames = allAvoidedObstacles.distinct()
        )
    }

    /**
     * Cross-track / perpendicular distance from point P to line segment (A -> B).
     */
    private fun computePerpendicularDistance(a: GeoPoint, b: GeoPoint, p: GeoPoint): Double {
        val abDist = a.distanceToAsDouble(b)
        if (abDist < 1.0) return a.distanceToAsDouble(p)

        // Convert lat/lon to local Cartesian approximations in meters
        val latMid = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val mPerDegLat = 111132.954 - 559.822 * cos(2 * latMid)
        val mPerDegLon = 111412.84 * cos(latMid)

        val ax = (a.longitude) * mPerDegLon
        val ay = (a.latitude) * mPerDegLat
        val bx = (b.longitude) * mPerDegLon
        val by = (b.latitude) * mPerDegLat
        val px = (p.longitude) * mPerDegLon
        val py = (p.latitude) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy

        if (lengthSq == 0.0) return Math.hypot(px - ax, py - ay)

        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy

        return Math.hypot(px - projX, py - projY)
    }

    /**
     * Along-track distance of point P projected on segment (A -> B) from A.
     */
    private fun computeAlongTrackDistance(a: GeoPoint, b: GeoPoint, p: GeoPoint): Double {
        val latMid = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val mPerDegLat = 111132.954 - 559.822 * cos(2 * latMid)
        val mPerDegLon = 111412.84 * cos(latMid)

        val ax = (a.longitude) * mPerDegLon
        val ay = (a.latitude) * mPerDegLat
        val bx = (b.longitude) * mPerDegLon
        val by = (b.latitude) * mPerDegLat
        val px = (p.longitude) * mPerDegLon
        val py = (p.latitude) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return 0.0

        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return sqrt(lengthSq) * t
    }
}

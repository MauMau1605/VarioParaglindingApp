package com.vario.app

import java.io.File

/**
 * Filter themes for categorizing multi-sport GPS recordings.
 *
 * @property label User-facing French name of the category.
 * @property emoji Pictogram associated with the category.
 */
enum class ActivityTheme(val label: String, val emoji: String) {
    ALL("Tous", "🌐"),
    FLIGHT("Vol", "\uD83E\uDE82"),
    HIKING("Randonnée", "\uD83E\uDD7E"),
    RUNNING("Course", "\uD83C\uDFC3"),
    SKI("Ski", "\u26F7\uFE0F"),
    OTHER("Autre", "\uD83D\uDCCC");

    /**
     * Determines whether the given [ActivityType] matches this theme filter.
     */
    fun matches(activityType: ActivityType): Boolean = when (this) {
        ALL -> true
        FLIGHT -> activityType == ActivityType.SIMPLE_FLIGHT || activityType == ActivityType.HIKE_AND_FLY
        HIKING -> activityType == ActivityType.HIKING || activityType == ActivityType.HIKE_AND_FLY
        RUNNING -> activityType == ActivityType.RUNNING
        SKI -> activityType == ActivityType.SKI_TOURING
        OTHER -> false
    }

    companion object {
        /**
         * Resolves the primary [ActivityTheme] from an [ActivityType].
         */
        fun fromActivityType(activityType: ActivityType): ActivityTheme = when (activityType) {
            ActivityType.SIMPLE_FLIGHT -> FLIGHT
            ActivityType.HIKE_AND_FLY -> FLIGHT
            ActivityType.HIKING -> HIKING
            ActivityType.RUNNING -> RUNNING
            ActivityType.SKI_TOURING -> SKI
        }
    }
}

/**
 * Sorting orders available for the activity summary list.
 */
enum class ActivitySortOrder(val label: String) {
    DATE_DESC("Date (récent)"),
    DATE_ASC("Date (ancien)"),
    DISTANCE_DESC("Distance (max)"),
    DURATION_DESC("Durée (max)"),
    ALTITUDE_DESC("Plafond (max)")
}

/**
 * Aggregated telemetry statistics for a specific [ActivityTheme].
 *
 * @property theme Theme associated with this aggregation.
 * @property totalActivities Total number of activities recorded under this category.
 * @property totalDurationSec Cumulative duration of all activities in seconds.
 * @property totalDistanceM Cumulative distance covered across all activities in meters.
 * @property averageDistanceM Mean distance covered per activity in meters.
 * @property totalFlightTimeSec Cumulative time spent airborne (specifically for Ski & Flight) in seconds.
 * @property totalAirDistanceM Cumulative gliding/flight distance covered in meters.
 * @property maxAltitudeM Peak ceiling (maximum altitude reached across all activities) in meters.
 * @property totalElevationGainM Cumulative elevation gain (D+) across all activities in meters.
 */
data class ActivityStats(
    val theme: ActivityTheme,
    val totalActivities: Int,
    val totalDurationSec: Long,
    val totalDistanceM: Float,
    val averageDistanceM: Float,
    val totalFlightTimeSec: Long = 0L,
    val totalAirDistanceM: Float = 0f,
    val maxAltitudeM: Float = 0f,
    val totalElevationGainM: Float = 0f
)

/**
 * Decomposed metrics for a Hike & Fly activity, separating hiking and flying phases
 * based on GPX track point phase annotations.
 */
data class SplitActivityMetrics(
    val hikeDurationSec: Long = 0L,
    val hikeDistanceM: Float = 0f,
    val hikeElevationGainM: Float = 0f,
    val flyDurationSec: Long = 0L,
    val flyDistanceM: Float = 0f,
    val flyMaxAltitudeM: Float = 0f
)

/**
 * Calculator and filtering engine for archived GPX tracks and activity statistics.
 */
object ActivityStatsCalculator {

    /**
     * Splits a Hike & Fly track's metrics into hiking and flying phases.
     * Uses the `phase` field from GPX track points. Falls back to speed/vz heuristics
     * for older GPX files without phase annotations.
     */
    fun splitHikeAndFlyMetrics(points: List<TrackPoint>): SplitActivityMetrics {
        if (points.size < 2) return SplitActivityMetrics()

        var hikeDurSec = 0L
        var hikeDistM = 0f
        var hikeGainM = 0f
        var flyDurSec = 0L
        var flyDistM = 0f
        var flyMaxAlt = 0f

        fun resolvePhase(point: TrackPoint): String {
            if (point.phase.equals("HIKING", ignoreCase = true)) return "HIKING"
            if (point.phase.equals("FLYING", ignoreCase = true)) return "FLYING"
            // Heuristic fallback for tracks without phase annotations
            return if (point.speedKmh >= 20f && (kotlin.math.abs(point.vzMs) > 0.2f || point.speedKmh >= 32f)) {
                "FLYING"
            } else {
                "HIKING"
            }
        }

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dtSec = ((curr.timeMs - prev.timeMs) / 1000L).coerceIn(0L, 60L)
            val distM = VarioMath.distanceBetweenM(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )
            val phase = if (resolvePhase(curr) == "FLYING" || resolvePhase(prev) == "FLYING") {
                "FLYING"
            } else {
                "HIKING"
            }

            when (phase) {
                "HIKING" -> {
                    hikeDurSec += dtSec
                    hikeDistM += distM
                    val elevDelta = curr.altitudeM - prev.altitudeM
                    if (elevDelta > 0f) hikeGainM += elevDelta
                }
                "FLYING" -> {
                    flyDurSec += dtSec
                    flyDistM += distM
                    if (curr.altitudeM > flyMaxAlt) flyMaxAlt = curr.altitudeM
                    if (prev.altitudeM > flyMaxAlt) flyMaxAlt = prev.altitudeM
                }
            }
        }

        return SplitActivityMetrics(
            hikeDurationSec = hikeDurSec,
            hikeDistanceM = hikeDistM,
            hikeElevationGainM = hikeGainM,
            flyDurationSec = flyDurSec,
            flyDistanceM = flyDistM,
            flyMaxAltitudeM = flyMaxAlt
        )
    }

    /**
     * Calculates flight time (in seconds) and air distance (in meters) from points where
     * points are marked with phase FLYING or exhibit airborne flight characteristics (speed & vz).
     */
    fun calculateAirMetricsFromPoints(points: List<TrackPoint>): Pair<Long, Float> {
        if (points.size < 2) return Pair(0L, 0f)

        var totalAirSec = 0L
        var totalAirDistM = 0f

        fun isAirborne(point: TrackPoint): Boolean {
            return point.phase.equals("FLYING", ignoreCase = true) ||
                (point.speedKmh >= 20f && (kotlin.math.abs(point.vzMs) > 0.2f || point.speedKmh >= 32f))
        }

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (isAirborne(prev) && isAirborne(curr)) {
                val dtSec = ((curr.timeMs - prev.timeMs) / 1000L).coerceIn(0L, 30L)
                totalAirSec += dtSec
                val distM = VarioMath.distanceBetweenM(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude
                )
                totalAirDistM += distM
            }
        }

        return Pair(totalAirSec, totalAirDistM)
    }

    /**
     * Aggregates telemetry statistics across the provided track collection for a given [theme].
     */
    fun calculateStats(theme: ActivityTheme, tracks: List<TrackSummary>): ActivityStats {
        val filtered = if (theme == ActivityTheme.ALL) {
            tracks
        } else {
            tracks.filter { theme.matches(it.activityType) }
        }

        val totalActivities = filtered.size
        var totalDurationSec = 0L
        var totalDistanceM = 0f
        var totalFlightTimeSec = 0L
        var totalAirDistanceM = 0f
        var maxAltitudeM = 0f

        for (track in filtered) {
            // For Hike & Fly tracks, use split metrics when filtering by specific theme
            if (track.activityType == ActivityType.HIKE_AND_FLY && theme != ActivityTheme.ALL) {
                when (theme) {
                    ActivityTheme.HIKING -> {
                        totalDurationSec += track.hikeDurationSec
                        totalDistanceM += track.hikeDistanceM
                        // No flight time contribution for hiking stats
                    }
                    ActivityTheme.FLIGHT -> {
                        totalDurationSec += track.flyDurationSec
                        totalDistanceM += track.flyDistanceM
                        totalFlightTimeSec += track.flyDurationSec
                        totalAirDistanceM += track.flyDistanceM
                        if (track.flyMaxAltitudeM > maxAltitudeM) {
                            maxAltitudeM = track.flyMaxAltitudeM
                        }
                    }
                    else -> {
                        totalDurationSec += track.durationSec
                        totalDistanceM += track.totalDistanceM
                    }
                }
            } else {
                totalDurationSec += track.durationSec
                totalDistanceM += track.totalDistanceM

                val flightTime = when {
                    track.airDurationSec > 0L -> track.airDurationSec
                    track.activityType == ActivityType.SIMPLE_FLIGHT -> track.durationSec
                    else -> 0L
                }
                val airDist = when {
                    track.airDistanceM > 0f -> track.airDistanceM
                    track.activityType == ActivityType.SIMPLE_FLIGHT -> track.totalDistanceM
                    else -> 0f
                }

                totalFlightTimeSec += flightTime
                totalAirDistanceM += airDist

                if (track.maxAltitudeM > maxAltitudeM) {
                    maxAltitudeM = track.maxAltitudeM
                }
            }
        }

        val averageDistanceM = if (totalActivities > 0) totalDistanceM / totalActivities else 0f

        return ActivityStats(
            theme = theme,
            totalActivities = totalActivities,
            totalDurationSec = totalDurationSec,
            totalDistanceM = totalDistanceM,
            averageDistanceM = averageDistanceM,
            totalFlightTimeSec = totalFlightTimeSec,
            totalAirDistanceM = totalAirDistanceM,
            maxAltitudeM = maxAltitudeM,
            totalElevationGainM = 0f
        )
    }

    /**
     * Filters and sorts tracks according to the requested [ActivityTheme] and [ActivitySortOrder].
     */
    fun filterAndSort(
        tracks: List<TrackSummary>,
        theme: ActivityTheme,
        sortOrder: ActivitySortOrder
    ): List<TrackSummary> {
        val filtered = if (theme == ActivityTheme.ALL) {
            tracks
        } else {
            tracks.filter { theme.matches(it.activityType) }
        }

        return when (sortOrder) {
            ActivitySortOrder.DATE_DESC -> filtered.sortedByDescending { it.startTimeMs }
            ActivitySortOrder.DATE_ASC -> filtered.sortedBy { it.startTimeMs }
            ActivitySortOrder.DISTANCE_DESC -> filtered.sortedByDescending { it.totalDistanceM }
            ActivitySortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationSec }
            ActivitySortOrder.ALTITUDE_DESC -> filtered.sortedByDescending { it.maxAltitudeM }
        }
    }
}

package com.vario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActivityStatsManagerTest {

    @Test
    fun activityTheme_matchesLogic() {
        // ALL matches everything
        assertTrue(ActivityTheme.ALL.matches(ActivityType.SIMPLE_FLIGHT))
        assertTrue(ActivityTheme.ALL.matches(ActivityType.HIKE_AND_FLY))
        assertTrue(ActivityTheme.ALL.matches(ActivityType.HIKING))
        assertTrue(ActivityTheme.ALL.matches(ActivityType.RUNNING))
        assertTrue(ActivityTheme.ALL.matches(ActivityType.SKI_TOURING))

        // FLIGHT matches SIMPLE_FLIGHT and HIKE_AND_FLY
        assertTrue(ActivityTheme.FLIGHT.matches(ActivityType.SIMPLE_FLIGHT))
        assertTrue(ActivityTheme.FLIGHT.matches(ActivityType.HIKE_AND_FLY))
        assertFalse(ActivityTheme.FLIGHT.matches(ActivityType.HIKING))
        assertFalse(ActivityTheme.FLIGHT.matches(ActivityType.RUNNING))
        assertFalse(ActivityTheme.FLIGHT.matches(ActivityType.SKI_TOURING))

        // HIKING matches HIKING and HIKE_AND_FLY
        assertTrue(ActivityTheme.HIKING.matches(ActivityType.HIKING))
        assertTrue(ActivityTheme.HIKING.matches(ActivityType.HIKE_AND_FLY))
        assertFalse(ActivityTheme.HIKING.matches(ActivityType.SIMPLE_FLIGHT))
        assertFalse(ActivityTheme.HIKING.matches(ActivityType.RUNNING))
        assertFalse(ActivityTheme.HIKING.matches(ActivityType.SKI_TOURING))

        // RUNNING matches only RUNNING
        assertTrue(ActivityTheme.RUNNING.matches(ActivityType.RUNNING))
        assertFalse(ActivityTheme.RUNNING.matches(ActivityType.SIMPLE_FLIGHT))
        assertFalse(ActivityTheme.RUNNING.matches(ActivityType.HIKING))

        // SKI matches only SKI_TOURING
        assertTrue(ActivityTheme.SKI.matches(ActivityType.SKI_TOURING))
        assertFalse(ActivityTheme.SKI.matches(ActivityType.SIMPLE_FLIGHT))
        assertFalse(ActivityTheme.SKI.matches(ActivityType.HIKING))
    }

    @Test
    fun activityTheme_fromActivityTypeMapping() {
        assertEquals(ActivityTheme.FLIGHT, ActivityTheme.fromActivityType(ActivityType.SIMPLE_FLIGHT))
        assertEquals(ActivityTheme.FLIGHT, ActivityTheme.fromActivityType(ActivityType.HIKE_AND_FLY))
        assertEquals(ActivityTheme.HIKING, ActivityTheme.fromActivityType(ActivityType.HIKING))
        assertEquals(ActivityTheme.RUNNING, ActivityTheme.fromActivityType(ActivityType.RUNNING))
        assertEquals(ActivityTheme.SKI, ActivityTheme.fromActivityType(ActivityType.SKI_TOURING))
    }

    @Test
    fun calculateAirMetricsFromPoints_accumulatesFlightTimeAndDistance() {
        val baseTime = 1700000000000L
        val points = listOf(
            // Ground point
            TrackPoint(
                latitude = 45.0, longitude = 6.0, altitudeM = 1000f,
                vzMs = 0f, speedKmh = 4f, timeMs = baseTime, phase = "HIKING"
            ),
            // Airborne point 1
            TrackPoint(
                latitude = 45.001, longitude = 6.001, altitudeM = 1200f,
                vzMs = 2.5f, speedKmh = 35f, timeMs = baseTime + 10_000L, phase = "FLYING"
            ),
            // Airborne point 2
            TrackPoint(
                latitude = 45.002, longitude = 6.002, altitudeM = 1400f,
                vzMs = 1.8f, speedKmh = 38f, timeMs = baseTime + 20_000L, phase = "FLYING"
            ),
            // Landed point
            TrackPoint(
                latitude = 45.0025, longitude = 6.0025, altitudeM = 900f,
                vzMs = 0f, speedKmh = 2f, timeMs = baseTime + 35_000L, phase = "HIKING"
            )
        )

        val (airSec, airDistM) = ActivityStatsCalculator.calculateAirMetricsFromPoints(points)

        // Only between point 1 and point 2 (both FLYING): 10 seconds dt
        assertEquals(10L, airSec)
        assertTrue("Air distance should be > 0", airDistM > 50f)
    }

    @Test
    fun calculateStats_forSki_calculatesAirMetricsProperly() {
        val dummyFile = File("skitouring_test.gpx")
        val tracks = listOf(
            TrackSummary(
                id = "ski1",
                fileName = "skitouring_1.gpx",
                file = dummyFile,
                startTimeMs = 1000L,
                durationSec = 7200L,
                maxAltitudeM = 2800f,
                totalDistanceM = 15000f,
                pointCount = 500,
                activityType = ActivityType.SKI_TOURING,
                theme = ActivityTheme.SKI,
                airDurationSec = 600L, // 10 min in air
                airDistanceM = 4000f
            ),
            TrackSummary(
                id = "ski2",
                fileName = "skitouring_2.gpx",
                file = dummyFile,
                startTimeMs = 2000L,
                durationSec = 5400L,
                maxAltitudeM = 3100f,
                totalDistanceM = 12000f,
                pointCount = 400,
                activityType = ActivityType.SKI_TOURING,
                theme = ActivityTheme.SKI,
                airDurationSec = 900L, // 15 min in air
                airDistanceM = 6000f
            ),
            TrackSummary(
                id = "flight1",
                fileName = "flight_1.gpx",
                file = dummyFile,
                startTimeMs = 3000L,
                durationSec = 1800L,
                maxAltitudeM = 2200f,
                totalDistanceM = 8000f,
                pointCount = 200,
                activityType = ActivityType.SIMPLE_FLIGHT,
                theme = ActivityTheme.FLIGHT,
                airDurationSec = 1800L,
                airDistanceM = 8000f
            )
        )

        // Ski theme stats
        val skiStats = ActivityStatsCalculator.calculateStats(ActivityTheme.SKI, tracks)
        assertEquals(2, skiStats.totalActivities)
        assertEquals(7200L + 5400L, skiStats.totalDurationSec)
        assertEquals(27000f, skiStats.totalDistanceM, 0.1f)
        assertEquals(13500f, skiStats.averageDistanceM, 0.1f)
        assertEquals(1500L, skiStats.totalFlightTimeSec)
        assertEquals(10000f, skiStats.totalAirDistanceM, 0.1f)
        assertEquals(3100f, skiStats.maxAltitudeM, 0.1f)

        // ALL theme stats
        val allStats = ActivityStatsCalculator.calculateStats(ActivityTheme.ALL, tracks)
        assertEquals(3, allStats.totalActivities)
        assertEquals(3100f, allStats.maxAltitudeM, 0.1f)
    }

    @Test
    fun filterAndSort_sortsCorrectly() {
        val dummyFile = File("track.gpx")
        val t1 = TrackSummary(
            id = "t1", fileName = "f1.gpx", file = dummyFile,
            startTimeMs = 1000L, durationSec = 100L, maxAltitudeM = 1500f, totalDistanceM = 5000f,
            activityType = ActivityType.SIMPLE_FLIGHT, theme = ActivityTheme.FLIGHT
        )
        val t2 = TrackSummary(
            id = "t2", fileName = "f2.gpx", file = dummyFile,
            startTimeMs = 2000L, durationSec = 300L, maxAltitudeM = 2500f, totalDistanceM = 2000f,
            activityType = ActivityType.HIKE_AND_FLY, theme = ActivityTheme.FLIGHT
        )
        val t3 = TrackSummary(
            id = "t3", fileName = "f3.gpx", file = dummyFile,
            startTimeMs = 3000L, durationSec = 200L, maxAltitudeM = 2000f, totalDistanceM = 8000f,
            activityType = ActivityType.HIKING, theme = ActivityTheme.HIKING
        )

        val list = listOf(t1, t2, t3)

        // Sort by Date Descending
        val byDateDesc = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.ALL, ActivitySortOrder.DATE_DESC)
        assertEquals(listOf("t3", "t2", "t1"), byDateDesc.map { it.id })

        // Sort by Date Ascending
        val byDateAsc = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.ALL, ActivitySortOrder.DATE_ASC)
        assertEquals(listOf("t1", "t2", "t3"), byDateAsc.map { it.id })

        // Sort by Distance Descending
        val byDist = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.ALL, ActivitySortOrder.DISTANCE_DESC)
        assertEquals(listOf("t3", "t1", "t2"), byDist.map { it.id })

        // Sort by Duration Descending
        val byDur = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.ALL, ActivitySortOrder.DURATION_DESC)
        assertEquals(listOf("t2", "t3", "t1"), byDur.map { it.id })

        // Sort by Altitude Descending
        val byAlt = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.ALL, ActivitySortOrder.ALTITUDE_DESC)
        assertEquals(listOf("t2", "t3", "t1"), byAlt.map { it.id })

        // Filter Hiking (matches HIKING and HIKE_AND_FLY)
        val hikingTracks = ActivityStatsCalculator.filterAndSort(list, ActivityTheme.HIKING, ActivitySortOrder.DATE_DESC)
        assertEquals(listOf("t3", "t2"), hikingTracks.map { it.id })
    }

    @Test
    fun calculateStats_emptyTracksList_returnsZeroStats() {
        val emptyList = emptyList<TrackSummary>()

        for (theme in ActivityTheme.values()) {
            val stats = ActivityStatsCalculator.calculateStats(theme, emptyList)
            assertEquals(theme, stats.theme)
            assertEquals(0, stats.totalActivities)
            assertEquals(0L, stats.totalDurationSec)
            assertEquals(0f, stats.totalDistanceM, 0.001f)
            assertEquals(0f, stats.averageDistanceM, 0.001f)
            assertEquals(0L, stats.totalFlightTimeSec)
            assertEquals(0f, stats.totalAirDistanceM, 0.001f)
            assertEquals(0f, stats.maxAltitudeM, 0.001f)
            assertEquals(0f, stats.totalElevationGainM, 0.001f)
        }
    }

    @Test
    fun filterAndSort_emptyTracksList_returnsEmptyList() {
        for (sortOrder in ActivitySortOrder.values()) {
            for (theme in ActivityTheme.values()) {
                val result = ActivityStatsCalculator.filterAndSort(emptyList(), theme, sortOrder)
                assertTrue(result.isEmpty())
            }
        }
    }

    @Test
    fun calculateAirMetricsFromPoints_emptyAndSinglePoint_returnsZero() {
        // Empty list
        val (airSecEmpty, airDistEmpty) = ActivityStatsCalculator.calculateAirMetricsFromPoints(emptyList())
        assertEquals(0L, airSecEmpty)
        assertEquals(0f, airDistEmpty, 0.001f)

        // Single point
        val singlePoint = TrackPoint(
            latitude = 45.0, longitude = 6.0, altitudeM = 2000f,
            vzMs = 3.0f, speedKmh = 40f, timeMs = 1700000000000L, phase = "FLYING"
        )
        val (airSecSingle, airDistSingle) = ActivityStatsCalculator.calculateAirMetricsFromPoints(listOf(singlePoint))
        assertEquals(0L, airSecSingle)
        assertEquals(0f, airDistSingle, 0.001f)
    }

    @Test
    fun calculateStats_singlePointAndZeroDistanceTracks_handlesCorrectly() {
        val dummyFile = File("track.gpx")
        val stationaryTrack1 = TrackSummary(
            id = "s1", fileName = "stat1.gpx", file = dummyFile,
            startTimeMs = 1000L, durationSec = 120L, maxAltitudeM = 1500f, totalDistanceM = 0f,
            pointCount = 1, activityType = ActivityType.HIKING, theme = ActivityTheme.HIKING
        )
        val stationaryTrack2 = TrackSummary(
            id = "s2", fileName = "stat2.gpx", file = dummyFile,
            startTimeMs = 2000L, durationSec = 180L, maxAltitudeM = 1600f, totalDistanceM = 0f,
            pointCount = 1, activityType = ActivityType.HIKING, theme = ActivityTheme.HIKING
        )

        val stats = ActivityStatsCalculator.calculateStats(ActivityTheme.HIKING, listOf(stationaryTrack1, stationaryTrack2))
        assertEquals(2, stats.totalActivities)
        assertEquals(300L, stats.totalDurationSec)
        assertEquals(0f, stats.totalDistanceM, 0.001f)
        assertEquals(0f, stats.averageDistanceM, 0.001f)
        assertEquals(1600f, stats.maxAltitudeM, 0.001f)
        assertEquals(0L, stats.totalFlightTimeSec)
        assertEquals(0f, stats.totalAirDistanceM, 0.001f)

        // Ensure filterAndSort handles zero distance tracks stably without crashing
        val sorted = ActivityStatsCalculator.filterAndSort(
            listOf(stationaryTrack1, stationaryTrack2),
            ActivityTheme.ALL,
            ActivitySortOrder.DISTANCE_DESC
        )
        assertEquals(2, sorted.size)
    }

    @Test
    fun calculateAirMetricsFromPoints_airborneDetectionWithoutPhaseTag_viaSpeedAndVzFallback() {
        val baseTime = 1700000000000L

        // Ground ascent without phase tag: speed 4 km/h, vz 0.3 m/s -> NOT airborne
        val groundPoints = listOf(
            TrackPoint(45.0, 6.0, 1000f, vzMs = 0.3f, speedKmh = 4.0f, timeMs = baseTime, phase = ""),
            TrackPoint(45.0001, 6.0001, 1003f, vzMs = 0.3f, speedKmh = 4.2f, timeMs = baseTime + 10_000L, phase = "")
        )
        val (groundSec, groundDist) = ActivityStatsCalculator.calculateAirMetricsFromPoints(groundPoints)
        assertEquals(0L, groundSec)
        assertEquals(0f, groundDist, 0.001f)

        // Moderate speed with vertical speed (|vz| > 0.2 m/s, speed >= 20 km/h) -> Airborne!
        val climbPoints = listOf(
            TrackPoint(45.0, 6.0, 1000f, vzMs = 1.2f, speedKmh = 22f, timeMs = baseTime, phase = ""),
            TrackPoint(45.001, 6.001, 1012f, vzMs = 1.0f, speedKmh = 24f, timeMs = baseTime + 10_000L, phase = "")
        )
        val (climbSec, climbDist) = ActivityStatsCalculator.calculateAirMetricsFromPoints(climbPoints)
        assertEquals(10L, climbSec)
        assertTrue("Climb distance should be > 50m", climbDist > 50f)

        // Moderate speed sink (|vz| > 0.2 m/s, speed >= 20 km/h) -> Airborne!
        val sinkPoints = listOf(
            TrackPoint(45.0, 6.0, 1500f, vzMs = -1.5f, speedKmh = 25f, timeMs = baseTime, phase = ""),
            TrackPoint(45.001, 6.001, 1485f, vzMs = -1.3f, speedKmh = 26f, timeMs = baseTime + 10_000L, phase = "")
        )
        val (sinkSec, sinkDist) = ActivityStatsCalculator.calculateAirMetricsFromPoints(sinkPoints)
        assertEquals(10L, sinkSec)
        assertTrue("Sink distance should be > 50m", sinkDist > 50f)

        // High speed glide without vz (speed >= 32 km/h, vz = 0) -> Airborne!
        val fastGlidePoints = listOf(
            TrackPoint(45.0, 6.0, 1200f, vzMs = 0.0f, speedKmh = 35f, timeMs = baseTime, phase = ""),
            TrackPoint(45.001, 6.001, 1200f, vzMs = 0.05f, speedKmh = 36f, timeMs = baseTime + 10_000L, phase = "")
        )
        val (fastSec, fastDist) = ActivityStatsCalculator.calculateAirMetricsFromPoints(fastGlidePoints)
        assertEquals(10L, fastSec)
        assertTrue("Fast glide distance should be > 50m", fastDist > 50f)

        // Moderate speed flat glide (speed 25 km/h, |vz| <= 0.2 m/s, speed < 32 km/h) -> NOT airborne
        val flatSkiPoints = listOf(
            TrackPoint(45.0, 6.0, 1200f, vzMs = 0.05f, speedKmh = 25f, timeMs = baseTime, phase = ""),
            TrackPoint(45.001, 6.001, 1200f, vzMs = 0.10f, speedKmh = 26f, timeMs = baseTime + 10_000L, phase = "")
        )
        val (flatSec, flatDist) = ActivityStatsCalculator.calculateAirMetricsFromPoints(flatSkiPoints)
        assertEquals(0L, flatSec)
        assertEquals(0f, flatDist, 0.001f)
    }

    @Test
    fun calculateAirMetricsFromPoints_dtClampingAndZeroDistance() {
        val baseTime = 1700000000000L

        // Points airborne but at identical coordinates (zero distance)
        val zeroDistAirborne = listOf(
            TrackPoint(45.0, 6.0, 1500f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime, phase = "FLYING"),
            TrackPoint(45.0, 6.0, 1520f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime + 10_000L, phase = "FLYING")
        )
        val (airSecZero, airDistZero) = ActivityStatsCalculator.calculateAirMetricsFromPoints(zeroDistAirborne)
        assertEquals(10L, airSecZero)
        assertEquals(0f, airDistZero, 0.001f)

        // Large time gap > 30s should be clamped to 30s max per segment
        val largeGapAirborne = listOf(
            TrackPoint(45.0, 6.0, 1500f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime, phase = "FLYING"),
            TrackPoint(45.01, 6.01, 1520f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime + 120_000L, phase = "FLYING")
        )
        val (airSecClamped, _) = ActivityStatsCalculator.calculateAirMetricsFromPoints(largeGapAirborne)
        assertEquals(30L, airSecClamped)

        // Negative or zero time difference clamped to 0s
        val backwardsTimeAirborne = listOf(
            TrackPoint(45.0, 6.0, 1500f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime + 10_000L, phase = "FLYING"),
            TrackPoint(45.01, 6.01, 1520f, vzMs = 2.0f, speedKmh = 35f, timeMs = baseTime, phase = "FLYING")
        )
        val (airSecBackwards, _) = ActivityStatsCalculator.calculateAirMetricsFromPoints(backwardsTimeAirborne)
        assertEquals(0L, airSecBackwards)
    }

    @Test
    fun calculateStats_simpleFlightVsOtherActivities_flightMetricsFallback() {
        val dummyFile = File("flight.gpx")

        // SIMPLE_FLIGHT without explicit airDuration/airDistance -> falls back to durationSec and totalDistanceM
        val simpleFlight = TrackSummary(
            id = "f1", fileName = "flight.gpx", file = dummyFile,
            startTimeMs = 1000L, durationSec = 1800L, maxAltitudeM = 2000f, totalDistanceM = 9000f,
            activityType = ActivityType.SIMPLE_FLIGHT, theme = ActivityTheme.FLIGHT,
            airDurationSec = 0L, airDistanceM = 0f
        )
        val statsFlight = ActivityStatsCalculator.calculateStats(ActivityTheme.FLIGHT, listOf(simpleFlight))
        assertEquals(1800L, statsFlight.totalFlightTimeSec)
        assertEquals(9000f, statsFlight.totalAirDistanceM, 0.001f)

        // HIKING without airDuration -> 0 flight time and 0 air distance
        val hiking = TrackSummary(
            id = "h1", fileName = "hike.gpx", file = dummyFile,
            startTimeMs = 2000L, durationSec = 3600L, maxAltitudeM = 1800f, totalDistanceM = 10000f,
            activityType = ActivityType.HIKING, theme = ActivityTheme.HIKING,
            airDurationSec = 0L, airDistanceM = 0f
        )
        val statsHike = ActivityStatsCalculator.calculateStats(ActivityTheme.HIKING, listOf(hiking))
        assertEquals(0L, statsHike.totalFlightTimeSec)
        assertEquals(0f, statsHike.totalAirDistanceM, 0.001f)
    }
}


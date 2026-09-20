package com.vario.app

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.io.File

class MapAndTrackTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "vario_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testBasemapRegistryHasReliefAndModularProviders() {
        val providers = BasemapRegistry.getAvailableProviders()
        assertThat(providers).isNotEmpty()

        val topo = providers.firstOrNull { it.id == "opentopomap" }
        assertThat(topo).isNotNull()
        assertThat(topo!!.isTopographic).isTrue()
        assertThat(topo.displayName).contains("OpenTopoMap")

        val cyclOsm = providers.firstOrNull { it.id == "cyclosm" }
        assertThat(cyclOsm).isNotNull()
        assertThat(cyclOsm!!.isTopographic).isTrue()

        val osm = providers.firstOrNull { it.id == "osm_standard" }
        assertThat(osm).isNotNull()
    }

    @Test
    fun testGpxTrackManagerAddAndClearPoints() {
        GpxTrackManager.clearCurrentTrack()
        assertThat(GpxTrackManager.getCurrentTrackPoints()).isEmpty()

        val pt1 = TrackPoint(45.123, 5.456, 1200f, 1.5f, 32f, 1000L)
        val pt2 = TrackPoint(45.124, 5.457, 1210f, 1.2f, 34f, 2000L)
        GpxTrackManager.addPoint(pt1)
        GpxTrackManager.addPoint(pt2)

        val points = GpxTrackManager.getCurrentTrackPoints()
        assertThat(points).hasSize(2)
        assertThat(points[0].latitude).isEqualTo(45.123)
        assertThat(points[1].altitudeM).isEqualTo(1210f)

        GpxTrackManager.clearCurrentTrack()
        assertThat(GpxTrackManager.getCurrentTrackPoints()).isEmpty()
    }

    @Test
    fun testObstacleRoutingEngineDirectFlight() {
        // Pilot at 1500m, target at 500m (drop = 1000m)
        val pilot = GeoPoint(45.0, 5.0)
        val target = GeoPoint(45.05, 5.0) // ~5.5 km north
        val wingGlide = 8.5f

        val result = ObstacleRoutingEngine.computeRoute(
            start = pilot,
            target = target,
            pilotAltM = 1500f,
            targetAltM = 500f,
            wingGlideRatio = wingGlide,
            safetyMarginM = 50f
        )

        assertThat(result.waypoints).hasSize(2)
        assertThat(result.hasAvoidedObstacles).isFalse()
        assertThat(result.directDistanceM).isGreaterThan(5000f)
        assertThat(result.requiredGlideRatio).isGreaterThan(0f)
        // With ~5550m distance and 1000m drop, required glide ~5.55 <= 8.5 -> reachable
        assertThat(result.isReachable).isTrue()
    }

    @Test
    fun testObstacleRoutingEngineUphillUnreachable() {
        // Pilot at 500m, target at 1500m (uphill)
        val pilot = GeoPoint(45.0, 5.0)
        val target = GeoPoint(45.05, 5.0)

        val result = ObstacleRoutingEngine.computeRoute(
            start = pilot,
            target = target,
            pilotAltM = 500f,
            targetAltM = 1500f,
            wingGlideRatio = 8.5f
        )

        assertThat(result.requiredGlideRatio.isInfinite()).isTrue()
        assertThat(result.isReachable).isFalse()
    }

    @Test
    fun testObstacleRoutingEngineAvoidsHighTerrain() {
        // Obstacle right in the middle between pilot and target
        val obs = TerrainObstacle(
            id = "test_mountain",
            name = "Test Peak",
            latitude = 45.025,
            longitude = 5.0,
            peakElevationM = 1800f, // Higher than pilot glide slope
            radiusM = 500f
        )
        ObstacleRoutingEngine.addObstacle(obs)

        val pilot = GeoPoint(45.0, 5.0)
        val target = GeoPoint(45.05, 5.0)

        val result = ObstacleRoutingEngine.computeRoute(
            start = pilot,
            target = target,
            pilotAltM = 1200f,
            targetAltM = 400f,
            wingGlideRatio = 8.5f,
            safetyMarginM = 50f
        )

        // Must route around obstacle
        assertThat(result.hasAvoidedObstacles).isTrue()
        assertThat(result.waypoints.size).isGreaterThan(2)
        assertThat(result.avoidedObstacleNames).contains("Test Peak")
        assertThat(result.totalDistanceM).isGreaterThan(result.directDistanceM)

        // Cleanup
        ObstacleRoutingEngine.clearObstacles()
    }

    @Test
    fun testTerrainElevationProviderBaselineAndRelief() {
        // Register sample peak
        val peak = TerrainObstacle(
            id = "test_peak",
            name = "Test Sommet",
            latitude = 45.3022,
            longitude = 5.8564,
            peakElevationM = 2062f,
            radiusM = 600f
        )
        ObstacleRoutingEngine.addObstacle(peak)

        // Near peak: estimated elevation should be significantly above valley baseline
        val peakElev = TerrainElevationProvider.estimateLocalElevation(45.3022, 5.8564)
        assertThat(peakElev).isGreaterThan(1800f)

        // Far away in valley: should decay towards default valley baseline (250m)
        val valleyElev = TerrainElevationProvider.estimateLocalElevation(44.0, 4.0)
        assertThat(valleyElev).isEqualTo(TerrainElevationProvider.DEFAULT_VALLEY_BASELINE_M)
    }

    @Test
    fun testTerrainElevationProviderCacheAndDenivellation() {
        TerrainElevationProvider.clearCache()

        // Inject elevation into cache
        val lat = 45.1885
        val lon = 5.7245
        TerrainElevationProvider.putElevationInCache(lat, lon, 420f)

        val retrieved = TerrainElevationProvider.getElevation(lat, lon)
        assertThat(retrieved).isEqualTo(420f)

        // Pilot at 1500m, target at 420m -> denivellation should be 1080m
        val deniv = TerrainElevationProvider.getDenivellation(1500f, lat, lon)
        assertThat(deniv).isEqualTo(1080f)

        // Dénivellation below target terrain
        val negativeDeniv = TerrainElevationProvider.getDenivellation(300f, lat, lon)
        assertThat(negativeDeniv).isEqualTo(-120f)
    }

    @Test
    fun testRouteComputationWithTerrainElevation() {
        TerrainElevationProvider.clearCache()
        val pilot = GeoPoint(45.30, 5.85)
        val target = GeoPoint(45.32, 5.86)

        TerrainElevationProvider.putElevationInCache(target.latitude, target.longitude, 350f)
        val targetAlt = TerrainElevationProvider.getElevation(target.latitude, target.longitude)

        val result = ObstacleRoutingEngine.computeRoute(
            start = pilot,
            target = target,
            pilotAltM = 1600f,
            targetAltM = targetAlt,
            wingGlideRatio = 8.5f,
            safetyMarginM = 50f
        )

        assertThat(result.isReachable).isTrue()
        assertThat(result.requiredGlideRatio).isGreaterThan(0f)
        assertThat(result.requiredGlideRatio).isLessThan(8.5f)
    }

    @Test
    fun testComputeItineraryTwoPointsReachable() {
        val start = GeoPoint(45.0, 5.0)
        val finish = GeoPoint(45.05, 5.0) // ~5.5 km north
        val wingGlide = 8.5f

        val result = ObstacleRoutingEngine.computeItinerary(
            points = listOf(start, finish),
            startAltM = 1600f,
            finishAltM = 500f,
            wingGlideRatio = wingGlide,
            safetyMarginM = 50f
        )

        assertThat(result.points).hasSize(2)
        assertThat(result.legs).hasSize(1)
        assertThat(result.legs[0].fromLabel).isEqualTo("Départ")
        assertThat(result.legs[0].toLabel).isEqualTo("Arrivée")
        assertThat(result.totalDistanceM).isGreaterThan(5000f)
        assertThat(result.totalDenivM).isEqualTo(1100f)
        assertThat(result.requiredGlideRatio).isGreaterThan(0f)
        assertThat(result.requiredGlideRatio).isLessThan(wingGlide)
        assertThat(result.isReachable).isTrue()
    }

    @Test
    fun testComputeItineraryMultiWaypoints() {
        val start = GeoPoint(45.0, 5.0)
        val wp1 = GeoPoint(45.03, 5.03)
        val finish = GeoPoint(45.06, 5.0)
        val wingGlide = 9.0f

        val result = ObstacleRoutingEngine.computeItinerary(
            points = listOf(start, wp1, finish),
            startAltM = 2000f,
            finishAltM = 400f,
            wingGlideRatio = wingGlide,
            safetyMarginM = 50f
        )

        assertThat(result.points).hasSize(3)
        assertThat(result.legs).hasSize(2)
        assertThat(result.legs[0].fromLabel).isEqualTo("Départ")
        assertThat(result.legs[0].toLabel).isEqualTo("Étape 1")
        assertThat(result.legs[1].fromLabel).isEqualTo("Étape 1")
        assertThat(result.legs[1].toLabel).isEqualTo("Arrivée")
        assertThat(result.totalDenivM).isEqualTo(1600f)
        assertThat(result.isReachable).isTrue()
        assertThat(result.polylinePoints.size).isAtLeast(3)
    }

    @Test
    fun testComputeItineraryUnreachableUphill() {
        val start = GeoPoint(45.0, 5.0)
        val finish = GeoPoint(45.05, 5.0)

        val result = ObstacleRoutingEngine.computeItinerary(
            points = listOf(start, finish),
            startAltM = 400f,
            finishAltM = 1200f, // Finish is higher than start
            wingGlideRatio = 8.0f
        )

        assertThat(result.isReachable).isFalse()
        assertThat(result.requiredGlideRatio.isInfinite()).isTrue()
    }

    @Test
    fun testComputeTrackProfile_emptyOrSinglePoint() {
        val emptyProfile = GpxTrackManager.computeTrackProfile(emptyList())
        assertThat(emptyProfile.points).isEmpty()
        assertThat(emptyProfile.totalDistanceM).isEqualTo(0f)

        val singlePoint = listOf(TrackPoint(45.0, 5.0, 1000f, 0f, 0f, 1000L))
        val singleProfile = GpxTrackManager.computeTrackProfile(singlePoint)
        assertThat(singleProfile.points).hasSize(1)
        assertThat(singleProfile.totalDistanceM).isEqualTo(0f)
        assertThat(singleProfile.minAltitudeM).isEqualTo(1000f)
        assertThat(singleProfile.maxAltitudeM).isEqualTo(1000f)
    }

    @Test
    fun testComputeTrackProfile_multiPointsStatsAndScrubber() {
        // Point 0: 45.000, 5.000, 1000m, vz = 0.0, speed = 0 km/h, t = 0s
        // Point 1: 45.009 (~1km), 5.000, 1050m (+50m climb), vz = 2.5, speed = 36 km/h (10 m/s), t = 100s
        // Point 2: 45.018 (~2km), 5.000, 950m (-100m descent), vz = -3.0, speed = 40 km/h, t = 200s
        // Point 3: 45.027 (~3km), 5.000, 980m (+30m climb), vz = 1.0, speed = 30 km/h, t = 300s
        val pts = listOf(
            TrackPoint(45.000, 5.000, 1000f, 0.0f, 0f, 1000L),
            TrackPoint(45.009, 5.000, 1050f, 2.5f, 36f, 101000L),
            TrackPoint(45.018, 5.000, 950f, -3.0f, 40f, 201000L),
            TrackPoint(45.027, 5.000, 980f, 1.0f, 30f, 301000L)
        )

        val profile = GpxTrackManager.computeTrackProfile(pts)

        assertThat(profile.points).hasSize(4)
        assertThat(profile.totalDistanceM).isGreaterThan(2900f)
        assertThat(profile.totalDistanceM).isLessThan(3100f)

        // Min / max altitude
        assertThat(profile.minAltitudeM).isEqualTo(950f)
        assertThat(profile.maxAltitudeM).isEqualTo(1050f)

        // Elevation gains and losses
        // 1000 -> 1050 (+50)
        // 1050 -> 950 (-100)
        // 950 -> 980 (+30)
        // Total D+ = 80m, Total D- = 100m
        assertThat(profile.elevationGainM).isEqualTo(80f)
        assertThat(profile.elevationLossM).isEqualTo(100f)

        // Speed extremes
        assertThat(profile.maxSpeedKmh).isEqualTo(40f)
        assertThat(profile.avgSpeedKmh).isGreaterThan(0f)

        // Vz extremes
        assertThat(profile.maxClimbVz).isEqualTo(2.5f)
        assertThat(profile.maxSinkVz).isEqualTo(-3.0f)

        // Duration (301000 - 1000 = 300000 ms = 300s)
        assertThat(profile.durationSec).isEqualTo(300L)

        // Points progression
        assertThat(profile.points[0].distanceM).isEqualTo(0f)
        assertThat(profile.points[0].altitudeM).isEqualTo(1000f)
        assertThat(profile.points[1].distanceM).isGreaterThan(900f)
        assertThat(profile.points[3].distanceM).isEqualTo(profile.totalDistanceM)
    }
}


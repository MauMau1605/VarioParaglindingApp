package com.vario.app

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for GpxTrackManager, specifically for the new updateTrackMetadata functionality.
 */
class GpxTrackManagerTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "test_tracks_${System.currentTimeMillis()}")
        testDir.mkdirs()
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns testDir
        every { context.cacheDir } returns testDir
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `updateTrackMetadata should update activity type`() {
        // Create a test GPX file
        val testFile = createTestGpxFile(
            activityType = ActivityType.SIMPLE_FLIGHT,
            distance = 1000f,
            altitude = 500f
        )

        // Create new metadata with different activity
        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.HIKING,
            totalDistanceM = 1500f,
            maxAltitudeM = 800f
        )

        // Update the track
        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()
        assertThat(updatedFile?.exists()).isTrue()

        // Verify the content was updated
        val content = updatedFile?.readText()
        assertThat(content).contains("Type: Randonnée")
        assertThat(content).contains("keywords>Hike</keywords>")
    }

    @Test
    fun `updateTrackMetadata should update distance`() {
        // Create a test GPX file
        val testFile = createTestGpxFile(
            activityType = ActivityType.SIMPLE_FLIGHT,
            distance = 1000f,
            altitude = 500f
        )

        // Create new metadata with updated distance
        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.SIMPLE_FLIGHT,
            totalDistanceM = 2500f,
            maxAltitudeM = 500f
        )

        // Update the track
        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()

        // Verify the distance was updated in the description
        val content = updatedFile?.readText()
        assertThat(content).contains("Distance ajustée: 2500m")
    }

    @Test
    fun `updateTrackMetadata should update altitude`() {
        // Create a test GPX file
        val testFile = createTestGpxFile(
            activityType = ActivityType.SIMPLE_FLIGHT,
            distance = 1000f,
            altitude = 500f
        )

        // Create new metadata with updated altitude
        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.SIMPLE_FLIGHT,
            totalDistanceM = 1000f,
            maxAltitudeM = 1200f
        )

        // Update the track
        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()

        // Verify the altitude was updated in the description
        val content = updatedFile?.readText()
        assertThat(content).contains("Plafond ajusté: 1200m")
    }

    @Test
    fun `updateTrackMetadata should preserve track points`() {
        // Create a test GPX file with multiple points
        val testFile = createTestGpxFileWithMultiplePoints(
            activityType = ActivityType.SIMPLE_FLIGHT,
            numPoints = 5
        )

        val pointsBefore = GpxTrackManager.loadTrackPoints(testFile)
        assertThat(pointsBefore.size).isEqualTo(5)

        // Update the track metadata
        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.HIKE_AND_FLY,
            totalDistanceM = 1500f,
            maxAltitudeM = 800f
        )

        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()

        // Verify all points are preserved
        val pointsAfter = GpxTrackManager.loadTrackPoints(updatedFile!!)
        assertThat(pointsAfter.size).isEqualTo(5)
        assertThat(pointsAfter).isEqualTo(pointsBefore)
    }

    @Test
    fun `updateTrackMetadata should preserve waypoints`() {
        // Create a test GPX file with waypoints
        val testFile = createTestGpxFileWithWaypoints(
            activityType = ActivityType.SIMPLE_FLIGHT,
            numWaypoints = 3
        )

        val waypointsBefore = GpxTrackManager.loadTrackWaypoints(testFile)
        assertThat(waypointsBefore.size).isEqualTo(3)

        // Update the track metadata
        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.HIKING,
            totalDistanceM = 2000f,
            maxAltitudeM = 600f
        )

        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()

        // Verify all waypoints are preserved
        val waypointsAfter = GpxTrackManager.loadTrackWaypoints(updatedFile!!)
        assertThat(waypointsAfter.size).isEqualTo(3)
        assertThat(waypointsAfter).isEqualTo(waypointsBefore)
    }

    @Test
    fun `updateTrackMetadata should handle empty points gracefully`() {
        // Create a test GPX file with no points
        val testFile = createEmptyTestGpxFile()

        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.HIKING,
            totalDistanceM = 1000f,
            maxAltitudeM = 500f
        )

        // Should return null for empty tracks
        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNull()
    }

    @Test
    fun `updateTrackMetadata should update all activity types correctly`() {
        // Test all activity types
        ActivityType.values().forEach { activityType ->
            val testFile = createTestGpxFile(
                activityType = ActivityType.SIMPLE_FLIGHT,
                distance = 1000f,
                altitude = 500f
            )

            val newMetadata = EditableTrackMetadata(
                activityType = activityType,
                totalDistanceM = 1500f,
                maxAltitudeM = 800f
            )

            val updatedFile = GpxTrackManager.updateTrackMetadata(
                context = context,
                file = testFile,
                newMetadata = newMetadata
            )

            assertThat(updatedFile).isNotNull()
            val content = updatedFile?.readText()
            val expectedLabel = if (activityType.label.contains("&")) activityType.label.replace("&", "&amp;") else activityType.label
            assertThat(content).contains(expectedLabel)
            assertThat(content).contains(activityType.stravaType)
        }
    }

    @Test
    fun `updateTrackMetadata should maintain XML structure`() {
        val testFile = createTestGpxFile(
            activityType = ActivityType.SIMPLE_FLIGHT,
            distance = 1000f,
            altitude = 500f
        )

        val newMetadata = EditableTrackMetadata(
            activityType = ActivityType.HIKING,
            totalDistanceM = 1500f,
            maxAltitudeM = 800f
        )

        val updatedFile = GpxTrackManager.updateTrackMetadata(
            context = context,
            file = testFile,
            newMetadata = newMetadata
        )

        assertThat(updatedFile).isNotNull()

        // Verify XML structure is maintained
        val content = updatedFile?.readText()
        assertThat(content).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(content).contains("<gpx version=\"1.1\"")
        assertThat(content).contains("<trk>")
        assertThat(content).contains("</gpx>")
    }

    // Helper methods to create test GPX files

    private fun createTestGpxFile(
        activityType: ActivityType,
        distance: Float,
        altitude: Float
    ): File {
        val file = File(testDir, "test_${System.currentTimeMillis()}.gpx")
        
        FileWriter(file).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"VarioAppli\"\n")
            writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            writer.write("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            writer.write("  <metadata>\n")
            writer.write("    <name>${activityType.label} test</name>\n")
            writer.write("    <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("    <desc>Type: ${activityType.label} | Duree: 100s | Plafond: ${altitude.toInt()}m | Distance: ${distance.toInt()}m</desc>\n")
            writer.write("    <keywords>${activityType.stravaType}</keywords>\n")
            writer.write("  </metadata>\n")
            writer.write("  <trk>\n")
            writer.write("    <name>Track test</name>\n")
            writer.write("    <trkseg>\n")
            
            // Add one track point
            writer.write("      <trkpt lat=\"45.0\" lon=\"5.0\">\n")
            writer.write("        <ele>$altitude</ele>\n")
            writer.write("        <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("        <extensions>\n")
            writer.write("          <vz>2.5</vz>\n")
            writer.write("          <speed>15.0</speed>\n")
            writer.write("        </extensions>\n")
            writer.write("      </trkpt>\n")
            
            writer.write("    </trkseg>\n")
            writer.write("  </trk>\n")
            writer.write("</gpx>\n")
        }
        
        return file
    }

    private fun createTestGpxFileWithMultiplePoints(
        activityType: ActivityType,
        numPoints: Int
    ): File {
        val file = File(testDir, "multi_${System.currentTimeMillis()}.gpx")
        
        FileWriter(file).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"VarioAppli\"\n")
            writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            writer.write("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            writer.write("  <metadata>\n")
            writer.write("    <name>${activityType.label} multi</name>\n")
            writer.write("    <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("    <desc>Type: ${activityType.label}</desc>\n")
            writer.write("  </metadata>\n")
            writer.write("  <trk>\n")
            writer.write("    <name>Track multi</name>\n")
            writer.write("    <trkseg>\n")
            
            // Add multiple track points
            for (i in 0 until numPoints) {
                writer.write("      <trkpt lat=\"${45.0 + i * 0.01}\" lon=\"${5.0 + i * 0.01}\">\n")
                writer.write("        <ele>${500 + i * 10}</ele>\n")
                writer.write("        <time>${isoDateFormat.format(System.currentTimeMillis() + i * 1000)}</time>\n")
                writer.write("        <extensions>\n")
                writer.write("          <vz>2.5</vz>\n")
                writer.write("          <speed>15.0</speed>\n")
                writer.write("        </extensions>\n")
                writer.write("      </trkpt>\n")
            }
            
            writer.write("    </trkseg>\n")
            writer.write("  </trk>\n")
            writer.write("</gpx>\n")
        }
        
        return file
    }

    private fun createTestGpxFileWithWaypoints(
        activityType: ActivityType,
        numWaypoints: Int
    ): File {
        val file = File(testDir, "waypoints_${System.currentTimeMillis()}.gpx")
        
        FileWriter(file).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"VarioAppli\"\n")
            writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            writer.write("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            writer.write("  <metadata>\n")
            writer.write("    <name>${activityType.label} with waypoints</name>\n")
            writer.write("    <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("    <desc>Type: ${activityType.label}</desc>\n")
            writer.write("  </metadata>\n")
            writer.write("  <trk>\n")
            writer.write("    <name>Track with waypoints</name>\n")
            writer.write("    <trkseg>\n")
            
            // Add one track point
            writer.write("      <trkpt lat=\"45.0\" lon=\"5.0\">\n")
            writer.write("        <ele>500</ele>\n")
            writer.write("        <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("        <extensions>\n")
            writer.write("          <vz>2.5</vz>\n")
            writer.write("          <speed>15.0</speed>\n")
            writer.write("        </extensions>\n")
            writer.write("      </trkpt>\n")
            
            writer.write("    </trkseg>\n")
            writer.write("  </trk>\n")
            
            // Add waypoints
            for (i in 0 until numWaypoints) {
                writer.write("  <wpt lat=\"${45.5 + i * 0.1}\" lon=\"${5.5 + i * 0.1}\">\n")
                writer.write("    <ele>${600 + i * 50}</ele>\n")
                writer.write("    <time>${isoDateFormat.format(System.currentTimeMillis() + i * 2000)}</time>\n")
                writer.write("    <name>Waypoint ${i + 1}</name>\n")
                writer.write("  </wpt>\n")
            }
            
            writer.write("</gpx>\n")
        }
        
        return file
    }

    private fun createEmptyTestGpxFile(): File {
        val file = File(testDir, "empty_${System.currentTimeMillis()}.gpx")
        
        FileWriter(file).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"VarioAppli\"\n")
            writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            writer.write("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            writer.write("  <metadata>\n")
            writer.write("    <name>Empty track</name>\n")
            writer.write("    <time>${isoDateFormat.format(System.currentTimeMillis())}</time>\n")
            writer.write("  </metadata>\n")
            writer.write("  <trk>\n")
            writer.write("    <name>Track empty</name>\n")
            writer.write("    <trkseg>\n")
            writer.write("    </trkseg>\n")
            writer.write("  </trk>\n")
            writer.write("</gpx>\n")
        }
        
        return file
    }
}

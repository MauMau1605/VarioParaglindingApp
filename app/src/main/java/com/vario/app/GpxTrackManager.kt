package com.vario.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Single GPS waypoint recorded during flight.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val vzMs: Float = 0f,
    val speedKmh: Float = 0f,
    val timeMs: Long = System.currentTimeMillis()
)

/**
 * Summary metadata for an archived GPX track.
 */
data class TrackSummary(
    val id: String,
    val fileName: String,
    val file: File,
    val startTimeMs: Long,
    val durationSec: Long,
    val maxAltitudeM: Float,
    val totalDistanceM: Float,
    val pointCount: Int
)

/**
 * Manager for recording, serializing, parsing, and sharing GPX flight tracks.
 */
object GpxTrackManager {

    private const val TAG = "GpxTrackManager"
    private const val TRACK_DIR_NAME = "tracks"

    private val currentPoints = Collections.synchronizedList(mutableListOf<TrackPoint>())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Appends a real-time point to the active flight track.
     */
    fun addPoint(point: TrackPoint) {
        currentPoints.add(point)
    }

    /**
     * Clears all in-memory points for the current flight track.
     */
    fun clearCurrentTrack() {
        currentPoints.clear()
    }

    /**
     * Snapshot copy of current active track points.
     */
    fun getCurrentTrackPoints(): List<TrackPoint> {
        synchronized(currentPoints) {
            return ArrayList(currentPoints)
        }
    }

    /**
     * Returns the dedicated directory for storing GPX track files.
     */
    fun getTracksDirectory(context: Context): File {
        val dir = File(context.filesDir, TRACK_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Persists the active flight track into a standard GPX 1.1 file.
     */
    fun saveCurrentTrack(
        context: Context,
        startTimeMs: Long,
        durationSec: Long,
        maxAltitudeM: Float,
        totalDistanceM: Float
    ): File? {
        val points = getCurrentTrackPoints()
        if (points.isEmpty()) {
            Log.d(TAG, "No track points recorded; skipping GPX generation.")
            return null
        }

        try {
            val dir = getTracksDirectory(context)
            val dateStr = fileDateFormat.format(Date(if (startTimeMs > 0) startTimeMs else System.currentTimeMillis()))
            val file = File(dir, "flight_$dateStr.gpx")

            FileWriter(file).use { writer ->
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                writer.write("<gpx version=\"1.1\" creator=\"VarioAppli\"\n")
                writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
                writer.write("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
                writer.write("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
                writer.write("  <metadata>\n")
                writer.write("    <name>Vol Parapente $dateStr</name>\n")
                val startIso = isoDateFormat.format(Date(if (startTimeMs > 0) startTimeMs else points.first().timeMs))
                writer.write("    <time>$startIso</time>\n")
                writer.write("    <desc>Duree: ${durationSec}s | Plafond: ${maxAltitudeM.toInt()}m | Distance: ${totalDistanceM.toInt()}m</desc>\n")
                writer.write("  </metadata>\n")
                writer.write("  <trk>\n")
                writer.write("    <name>Track $dateStr</name>\n")
                writer.write("    <trkseg>\n")

                for (pt in points) {
                    val ptIso = isoDateFormat.format(Date(pt.timeMs))
                    writer.write(
                        String.format(
                            Locale.US,
                            "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n" +
                            "        <ele>%.1f</ele>\n" +
                            "        <time>%s</time>\n" +
                            "        <extensions>\n" +
                            "          <vz>%.2f</vz>\n" +
                            "          <speed>%.1f</speed>\n" +
                            "        </extensions>\n" +
                            "      </trkpt>\n",
                            pt.latitude,
                            pt.longitude,
                            pt.altitudeM,
                            ptIso,
                            pt.vzMs,
                            pt.speedKmh
                        )
                    )
                }

                writer.write("    </trkseg>\n")
                writer.write("  </trk>\n")
                writer.write("</gpx>\n")
            }

            Log.i(TAG, "Saved GPX track with ${points.size} points to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save GPX track", e)
            return null
        }
    }

    /**
     * Lists all recorded GPX files sorted newest first.
     */
    fun getSavedTracks(context: Context): List<TrackSummary> {
        val dir = getTracksDirectory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
            ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.mapNotNull { parseTrackSummary(it) }
    }

    /**
     * Parses metadata summary from a saved GPX file without loading all points.
     */
    fun parseTrackSummary(file: File): TrackSummary? {
        return try {
            var pointCount = 0
            var maxAlt = 0f
            var firstTimeMs = file.lastModified()
            var lastTimeMs = file.lastModified()

            file.inputStream().use { stream ->
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(stream, "UTF-8")

                var eventType = parser.eventType
                var inEle = false
                var inTime = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "trkpt" -> pointCount++
                                "ele" -> inEle = true
                                "time" -> inTime = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text.trim()
                            if (inEle) {
                                text.toFloatOrNull()?.let { ele ->
                                    if (ele > maxAlt) maxAlt = ele
                                }
                            } else if (inTime && pointCount <= 1) {
                                try {
                                    isoDateFormat.parse(text)?.let { d ->
                                        firstTimeMs = d.time
                                    }
                                } catch (_: Exception) {}
                            } else if (inTime) {
                                try {
                                    isoDateFormat.parse(text)?.let { d ->
                                        lastTimeMs = d.time
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "ele" -> inEle = false
                                "time" -> inTime = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }

            val durationSec = ((lastTimeMs - firstTimeMs) / 1000L).coerceAtLeast(0L)
            TrackSummary(
                id = file.name,
                fileName = file.name,
                file = file,
                startTimeMs = firstTimeMs,
                durationSec = durationSec,
                maxAltitudeM = maxAlt,
                totalDistanceM = 0f,
                pointCount = pointCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing track summary for ${file.name}", e)
            null
        }
    }

    /**
     * Parses all track points from a GPX file.
     */
    fun loadTrackPoints(file: File): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        if (!file.exists()) return result

        try {
            file.inputStream().use { stream ->
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(stream, "UTF-8")

                var eventType = parser.eventType
                var curLat = 0.0
                var curLon = 0.0
                var curEle = 0f
                var curVz = 0f
                var curSpeed = 0f
                var curTime = 0L

                var currentTag = ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                            if (parser.name == "trkpt") {
                                curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                curEle = 0f
                                curVz = 0f
                                curSpeed = 0f
                                curTime = 0L
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text.trim()
                            when (currentTag) {
                                "ele" -> curEle = text.toFloatOrNull() ?: curEle
                                "vz" -> curVz = text.toFloatOrNull() ?: curVz
                                "speed" -> curSpeed = text.toFloatOrNull() ?: curSpeed
                                "time" -> {
                                    try {
                                        isoDateFormat.parse(text)?.let { d ->
                                            curTime = d.time
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "trkpt" && (curLat != 0.0 || curLon != 0.0)) {
                                result.add(
                                    TrackPoint(
                                        latitude = curLat,
                                        longitude = curLon,
                                        altitudeM = curEle,
                                        vzMs = curVz,
                                        speedKmh = curSpeed,
                                        timeMs = curTime
                                    )
                                )
                            }
                            currentTag = ""
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading track points from ${file.name}", e)
        }
        return result
    }

    /**
     * Deletes a recorded GPX file.
     */
    fun deleteTrack(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete ${file.name}", e)
            false
        }
    }

    /**
     * Shares a GPX file via standard Android share sheet.
     */
    fun shareTrack(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Trace Vol - ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Partager la trace GPX"))
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing track", e)
            Toast.makeText(context, "Erreur lors du partage : ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

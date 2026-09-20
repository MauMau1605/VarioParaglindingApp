package com.vario.app

/**
 * Operating mode of the variometer sensors.
 */
enum class SensorMode {
    BARO_AND_GPS,
    GPS_ONLY
}

/**
 * Operating flight mode selected by the pilot.
 */
enum class FlightMode {
    NORMAL,
    HIKE_AND_FLY
}

/**
 * Current lifecycle phase of a flight / hike session.
 */
enum class SessionPhase {
    IDLE,       // Standby / not recording
    HIKING,     // Recording ascent (way up: walking, skiing, climbing) - Vz beep muted, D+ displayed
    FLYING      // Recording flight (way down) - Vz beep active, Vz ladder displayed
}

/**
 * Immutable data class representing the variometer state.
 * Exposed via [StateFlow] from [VarioService] to the UI layer.
 *
 * This object is created only when data changes (off the audio fast-path),
 * so its allocation does not impact the zero-GC audio thread.
 *
 * @property altitudeM Altitude in meters, calibrated via GPS/QNH or standard atmosphere.
 * @property vzMs Vertical speed (Vz) in meters per second. Positive = ascending.
 * @property distanceToTakeoffM Distance in meters to the takeoff site (straight line), or null if no fix yet.
 * @property totalDistanceTraveledM Cumulative distance traveled along the flight path, in meters.
 * @property gpsFixAcquired Whether a GPS fix has been acquired.
 * @property isCalibrated Whether the barometer reference (QNH) has been calibrated via GPS.
 * @property latitude Current GPS latitude in degrees.
 * @property longitude Current GPS longitude in degrees.
 * @property gpsAltitudeM Current GPS altitude in meters.
 * @property isFlightActive Whether the flight has been started by the pilot.
 * @property isFlightPaused Whether the flight/hike recording is currently paused.
 * @property flightDurationSec Elapsed flight time in seconds.
 * @property maxAltitudeM Maximum altitude (ceiling) reached during this flight.
 * @property isMuted Whether the variometer beeper audio is muted.
 * @property isUsbConnected Whether the USB sensor module is connected.
 * @property isUsbScanning Whether the service is actively scanning for a USB connection (30s timeout).
 * @property sensorMode Current sensor operating mode ([SensorMode.BARO_AND_GPS] or [SensorMode.GPS_ONLY]).
 * @property flightMode Current flight mode ([FlightMode.NORMAL] or [FlightMode.HIKE_AND_FLY]).
 * @property sessionPhase Current session phase ([SessionPhase.IDLE], [SessionPhase.HIKING], or [SessionPhase.FLYING]).
 * @property elevationGainM Cumulative positive elevation gain (D+) from the beginning of the session, in meters.
 * @property elevationLossM Cumulative elevation loss (D-) from the beginning of the session, in meters.
 * @property flightElevationGainM Cumulative elevation gain (flight D+) strictly since flight takeoff, in meters.
 * @property flightElevationLossM Cumulative elevation loss (flight D-) strictly since flight takeoff, in meters.
 * @property flightMaxClimbRateMs Maximum positive vertical speed (climb rate) recorded since flight takeoff, in m/s.
 * @property hikeStartAltitudeM Altitude in meters at which the hike ascent began.
 * @property usbDeviceName Description/name of connected USB device, or null if disconnected.
 * @property gpsAccuracyM Estimated horizontal GPS accuracy in meters.
 * @property gpsVerticalAccuracyM Estimated vertical GPS accuracy in meters.
 * @property lastGpsFixAgeSec Time in seconds since the last valid GPS fix, or -1f if never fixed.
 * @property isGpsAvailable Whether the GPS hardware is actively available and reporting locations.
 * @property usbPermissionGranted Whether USB device permission has been granted by the user.
 * @property usbPortOpen Whether the USB serial port is currently open and communicating.
 * @property currentBaudRate Configured serial baud rate (e.g. 115200).
 * @property usbVid USB Vendor ID of the connected hardware.
 * @property usbPid USB Product ID of the connected hardware.
 * @property totalBytesRead Total count of raw bytes read from the USB interface.
 * @property validFramesCount Count of successfully parsed LK8EX1 frames.
 * @property crcErrorsCount Count of checksum mismatches or malformed frames.
 * @property lastRawSentence Most recently received raw LK8EX1 sentence string.
 */
data class VarioData(
    val altitudeM: Float = 0f,
    val vzMs: Float = 0f,
    val distanceToTakeoffM: Float? = null,
    val totalDistanceTraveledM: Float = 0f,
    val gpsFixAcquired: Boolean = false,
    val isCalibrated: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsAltitudeM: Float = 0f,
    val isFlightActive: Boolean = false,
    val isFlightPaused: Boolean = false,
    val flightDurationSec: Long = 0L,
    val maxAltitudeM: Float = 0f,
    val isMuted: Boolean = false,
    val isUsbConnected: Boolean = false,
    val isUsbScanning: Boolean = false,
    val sensorMode: SensorMode = SensorMode.GPS_ONLY,
    val flightMode: FlightMode = FlightMode.NORMAL,
    val sessionPhase: SessionPhase = SessionPhase.IDLE,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
    val flightElevationGainM: Float = 0f,
    val flightElevationLossM: Float = 0f,
    val flightMaxClimbRateMs: Float = 0f,
    val hikeStartAltitudeM: Float = 0f,
    val usbDeviceName: String? = null,
    val gpsAccuracyM: Float = 0f,
    val gpsVerticalAccuracyM: Float = 0f,
    val lastGpsFixAgeSec: Float = -1f,
    val isGpsAvailable: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val usbPortOpen: Boolean = false,
    val currentBaudRate: Int = 115200,
    val usbVid: Int = 0,
    val usbPid: Int = 0,
    val totalBytesRead: Long = 0L,
    val validFramesCount: Long = 0L,
    val crcErrorsCount: Long = 0L,
    val lastRawSentence: String = "",
    val lastRawPressurePa: Long = 0L,
    val lastRawVarioCmS: Long = 0L
)

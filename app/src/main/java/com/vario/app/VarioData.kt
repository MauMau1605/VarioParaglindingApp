package com.vario.app

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
 * @property flightDurationSec Elapsed flight time in seconds.
 * @property maxAltitudeM Maximum altitude (ceiling) reached during this flight.
 * @property isMuted Whether the variometer beeper audio is muted.
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
    val flightDurationSec: Long = 0L,
    val maxAltitudeM: Float = 0f,
    val isMuted: Boolean = false
)

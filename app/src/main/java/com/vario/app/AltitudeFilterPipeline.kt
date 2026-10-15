package com.vario.app

import kotlin.math.abs

/**
 * Unified, zero-allocation multi-regime filtering pipeline for variometer altitude and vertical speed (Vz).
 *
 * Implements a four-stage signal processing chain:
 * 1. **Slew-Rate Limiter (Spike Rejection):** Clamps impossible vertical accelerations caused by
 *    packet corruption, dynamic pressure gusts, or GPS multipath leaps.
 * 2. **State-Space Estimator ([VarioKalmanFilter]):** Fuses altitude with native vario and optional IMU vertical
 *    acceleration in continuous-discrete scalar form.
 * 3. **Pedestrian EMA Damping:** Optional low-pass post-filter smoothing footstep mechanical impacts
 *    during hiking and running.
 * 4. **Zero-Velocity Clamp & Stationary Gate:** Suppresses stationary digital jitter and prevents acoustic chatter.
 *
 * ## Zero-Allocation Contract:
 * All calculations execute purely on scalar primitive variables. Zero heap allocations occur during [processBaro]
 * and [processGps].
 */
class AltitudeFilterPipeline(
    val kalmanFilter: VarioKalmanFilter = VarioKalmanFilter()
) {
    // ── Configuration fields ─────────────────────────────────────────────────
    var maxAltitudeSlewRateMs: Float = 20.0f
    var maxVzSlewRateMs2: Float = 25.0f
    var vzDeadbandClampMs: Float = 0.08f
    var emaAlpha: Float = 1.0f // 1.0 = bypass EMA, < 1.0 = smooth Vz

    private var currentPhase: SessionPhase = SessionPhase.IDLE
    private var currentMode: SensorMode = SensorMode.GPS_ONLY
    private var currentPreset: FilterPreset = FilterPreset.BALANCED
    private var currentActivity: ActivityType = ActivityType.SIMPLE_FLIGHT

    // ── Output primitives (zero allocation getters) ──────────────────────────
    var filteredAltitudeM: Float = 0f
        private set
    var filteredVzMs: Float = 0f
        private set
    var rawVzMs: Float = 0f
        private set
    var isStationary: Boolean = false
        private set
    var isInitialized: Boolean = false
        private set

    /**
     * Dynamically reconfigures pipeline physical limits and filter parameters based on
     * flight phase, active sensor modality, preset tuning, and multi-sport activity type.
     */
    fun configure(
        phase: SessionPhase,
        mode: SensorMode,
        preset: FilterPreset = FilterPreset.BALANCED,
        activityType: ActivityType = ActivityType.SIMPLE_FLIGHT
    ) {
        currentPhase = phase
        currentMode = mode
        currentPreset = preset
        currentActivity = activityType
        kalmanFilter.preset = preset

        when (mode) {
            SensorMode.BARO_AND_GPS -> {
                when (phase) {
                    SessionPhase.FLYING -> {
                        maxAltitudeSlewRateMs = 20.0f
                        maxVzSlewRateMs2 = 25.0f
                        vzDeadbandClampMs = kalmanFilter.deadbandEpsilon
                        emaAlpha = 1.0f // Fast direct response for thermal centering
                    }
                    SessionPhase.HIKING, SessionPhase.IDLE -> {
                        val isRunning = activityType == ActivityType.RUNNING
                        maxAltitudeSlewRateMs = if (isRunning) 6.0f else 4.0f
                        maxVzSlewRateMs2 = if (isRunning) 6.0f else 5.0f
                        vzDeadbandClampMs = 0.15f
                        emaAlpha = if (isRunning) 0.35f else 0.20f // Pedestrian EMA damping
                    }
                }
            }
            SensorMode.GPS_ONLY -> {
                when (phase) {
                    SessionPhase.FLYING -> {
                        maxAltitudeSlewRateMs = 15.0f
                        maxVzSlewRateMs2 = 10.0f
                        vzDeadbandClampMs = 0.30f
                        emaAlpha = 0.40f
                    }
                    SessionPhase.HIKING, SessionPhase.IDLE -> {
                        val isRunning = activityType == ActivityType.RUNNING
                        maxAltitudeSlewRateMs = if (isRunning) 4.5f else 3.0f
                        maxVzSlewRateMs2 = if (isRunning) 4.5f else 3.0f
                        vzDeadbandClampMs = 0.25f
                        emaAlpha = if (isRunning) 0.25f else 0.15f
                    }
                }
            }
        }
    }

    /**
     * Resets filter state and internal Kalman estimator to [initialAltitudeM] with zero velocity.
     */
    fun reset(initialAltitudeM: Float) {
        filteredAltitudeM = initialAltitudeM
        filteredVzMs = 0f
        rawVzMs = 0f
        isStationary = false
        kalmanFilter.reset(initialAltitudeM)
        isInitialized = initialAltitudeM > 0f
    }

    /**
     * Clamps vertical speed to stationary zero (e.g. during flight pause or stationary periods).
     * Optionally freezes current altitude.
     */
    fun clampStationary(freezeAltitude: Boolean = false) {
        isStationary = true
        filteredVzMs = 0f
        rawVzMs = 0f
        if (freezeAltitude) {
            kalmanFilter.reset(filteredAltitudeM)
        }
    }

    /**
     * Fast-path processing for 10 Hz LK8EX1 barometric telemetry.
     * ZERO HEAP ALLOCATIONS.
     */
    fun processBaro(
        measuredAltM: Float,
        hasNativeVario: Boolean,
        nativeVarioMs: Float,
        dtSec: Float,
        accelZMs2: Float = 0f
    ) {
        if (!isInitialized) {
            reset(measuredAltM)
            return
        }

        val dt = dtSec.coerceIn(0.01f, 3.0f)

        // 1. Altitude Slew-Rate Limiter (Spike Rejection)
        val maxAltDelta = maxAltitudeSlewRateMs * dt
        val altDiff = measuredAltM - filteredAltitudeM
        val slewLimitedAlt = when {
            altDiff > maxAltDelta -> filteredAltitudeM + maxAltDelta
            altDiff < -maxAltDelta -> filteredAltitudeM - maxAltDelta
            else -> measuredAltM
        }

        // 2. State-Space Estimator (Kalman Filter)
        kalmanFilter.predictWithAcceleration(dt, accelZMs2)
        if (hasNativeVario) {
            // Slew-rate limit native vario
            val maxVzDelta = maxVzSlewRateMs2 * dt
            val vzDiff = nativeVarioMs - kalmanFilter.vz
            val slewLimitedVz = when {
                vzDiff > maxVzDelta -> kalmanFilter.vz + maxVzDelta
                vzDiff < -maxVzDelta -> kalmanFilter.vz - maxVzDelta
                else -> nativeVarioMs
            }
            rawVzMs = slewLimitedVz
            kalmanFilter.updateAltitudeAndVario(slewLimitedAlt, slewLimitedVz)
        } else {
            kalmanFilter.updateBaroAltitude(slewLimitedAlt)
            rawVzMs = kalmanFilter.vz
        }

        filteredAltitudeM = kalmanFilter.altitude
        var estimatedVz = kalmanFilter.vz

        // 3. Optional Pedestrian EMA smoothing
        if (emaAlpha < 0.99f) {
            estimatedVz = filteredVzMs + emaAlpha * (estimatedVz - filteredVzMs)
        }

        // 4. Zero-Velocity Clamp (Stationary Deadband)
        isStationary = abs(estimatedVz) < vzDeadbandClampMs
        filteredVzMs = if (isStationary) 0.0f else estimatedVz
    }

    /**
     * 1 Hz processing for GPS altitude fallback.
     * ZERO HEAP ALLOCATIONS.
     */
    fun processGps(gpsAltM: Float, dtSec: Float, groundSpeedMs: Float = 1.0f) {
        if (!isInitialized) {
            reset(gpsAltM)
            return
        }

        val dt = dtSec.coerceIn(0.1f, 5.0f)

        // 1. Slew-rate limit GPS altitude jump
        val maxAltDelta = maxAltitudeSlewRateMs * dt
        val diff = gpsAltM - filteredAltitudeM
        val slewLimitedAlt = when {
            diff > maxAltDelta -> filteredAltitudeM + maxAltDelta
            diff < -maxAltDelta -> filteredAltitudeM - maxAltDelta
            else -> gpsAltM
        }

        // 2. Kalman GPS update
        kalmanFilter.predict(dt)
        kalmanFilter.updateGpsAltitude(slewLimitedAlt)
        rawVzMs = kalmanFilter.vz
        filteredAltitudeM = kalmanFilter.altitude

        var estimatedVz = kalmanFilter.vz
        if (emaAlpha < 0.99f) {
            estimatedVz = filteredVzMs + emaAlpha * (estimatedVz - filteredVzMs)
        }

        // 3. Stationary ground speed check: lock Vz to 0 if pilot is stopped
        val stationarySpeed = (currentPhase != SessionPhase.FLYING && groundSpeedMs < 0.5f)
        val stationaryVz = abs(estimatedVz) < vzDeadbandClampMs
        isStationary = stationarySpeed || stationaryVz
        filteredVzMs = if (isStationary) 0.0f else estimatedVz
    }
}

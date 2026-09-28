package com.vario.app

/**
 * Filter preset configuring the balance between noise rejection and responsiveness.
 */
enum class FilterPreset(val label: String, val description: String) {
    SMOOTH("Amorti", "Filtrage doux et stable, idéal capteurs très bruités ou air calme"),
    BALANCED("Équilibré", "Compromis optimal réactivité / suppression du bruit"),
    SENSITIVE("Réactif", "Faible latence pour centrage de thermiques faibles")
}

/**
 * Zero-allocation 1D continuous-discrete Kalman Filter for variometer altitude and vertical speed (Vz).
 *
 * State vector:
 *   x = [ altitude (m), vertical speed (m/s) ]^T
 *
 * Physical Model:
 *   Tracks altitude and vertical velocity under continuous acceleration variance (Q).
 *   Updates with barometric altitude (R_alt), GPS altitude, and/or LK8EX1 native vario (R_vario).
 *
 * ## Zero-Allocation Contract:
 * All calculations operate strictly on scalar primitive [Float] fields.
 * No matrix objects, no arrays, no boxing, and no heap allocations occur during [predict] or [update].
 */
class VarioKalmanFilter(
    initialPreset: FilterPreset = FilterPreset.BALANCED
) {

    // ── State variables ──────────────────────────────────────────────────────
    var altitude: Float = 0f
        private set

    var vz: Float = 0f
        private set

    var isInitialized: Boolean = false
        private set

    // ── Covariance matrix P = [[p00, p01], [p10, p11]] (symmetric: p01 == p10)
    private var p00: Float = 0.2f
    private var p01: Float = 0f
    private var p11: Float = 0.2f

    // ── Tuning parameters (configured per FilterPreset) ──────────────────────
    var preset: FilterPreset = initialPreset
        set(value) {
            field = value
            applyPresetParameters(value)
        }

    /** Process noise variance on vertical acceleration (m²/s⁴) */
    var accelVariance: Float = 1.0f
        private set

    /** Measurement noise variance on barometric altitude (m²) */
    var baroAltVariance: Float = 0.35f
        private set

    /** Measurement noise variance on LK8EX1 native vario (m²/s²) */
    var nativeVarioVariance: Float = 0.18f
        private set

    /** Measurement noise variance on GPS altitude (m²) */
    var gpsAltVariance: Float = 4.0f
        private set

    /** Deadband threshold in m/s below which motionless Vz jitter is snapped to 0.0 m/s */
    var deadbandEpsilon: Float = 0.10f

    init {
        applyPresetParameters(initialPreset)
    }

    private fun applyPresetParameters(targetPreset: FilterPreset) {
        when (targetPreset) {
            FilterPreset.SMOOTH -> {
                accelVariance = 0.4f
                baroAltVariance = 0.60f
                nativeVarioVariance = 0.35f
                gpsAltVariance = 6.0f
                deadbandEpsilon = 0.12f
            }
            FilterPreset.BALANCED -> {
                accelVariance = 1.0f
                baroAltVariance = 0.35f
                nativeVarioVariance = 0.18f
                gpsAltVariance = 4.0f
                deadbandEpsilon = 0.10f
            }
            FilterPreset.SENSITIVE -> {
                accelVariance = 2.5f
                baroAltVariance = 0.15f
                nativeVarioVariance = 0.08f
                gpsAltVariance = 2.0f
                deadbandEpsilon = 0.06f
            }
        }
    }

    /**
     * Resets filter state to a known altitude with zero vertical velocity.
     */
    fun reset(initialAltitudeM: Float) {
        altitude = initialAltitudeM
        vz = 0f
        p00 = 0.2f
        p01 = 0f
        p11 = 0.2f
        isInitialized = initialAltitudeM > 0f
    }

    /** Whether experimental IMU acceleration assist is enabled */
    var isImuAssistEnabled: Boolean = false

    /**
     * Prediction step over elapsed time [dtSec].
     */
    fun predict(dtSec: Float) {
        predictWithAcceleration(dtSec, 0.0f)
    }

    /**
     * Prediction step over elapsed time [dtSec] with world-frame vertical acceleration [accelZMs2].
     *
     * State extrapolation with control input u = accelZ:
     *   h_pred = h + vz * dt + 0.5 * accelZ * dt²
     *   vz_pred = vz + accelZ * dt
     *
     * Covariance extrapolation:
     *   P_pred = F * P * F^T + Q
     */
    fun predictWithAcceleration(dtSec: Float, accelZMs2: Float) {
        if (!isInitialized || dtSec <= 0f) return
        val dt = dtSec.coerceIn(0.01f, 3.0f)
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2

        val effectiveAccel = if (isImuAssistEnabled) {
            val clamped = accelZMs2.coerceIn(-15.0f, 15.0f)
            // Filter out small harness tremors (< 0.15 m/s²)
            if (kotlin.math.abs(clamped) < 0.15f) 0.0f else clamped
        } else {
            0.0f
        }

        // Extrapolate state
        altitude += vz * dt + 0.5f * effectiveAccel * dt2
        vz += effectiveAccel * dt

        // Process noise matrix Q terms (integrated acceleration variance)
        val q00 = accelVariance * 0.25f * dt4
        val q01 = accelVariance * 0.5f * dt3
        val q11 = accelVariance * dt2

        // P_pred = F * P * F^T + Q
        val newP00 = p00 + 2f * dt * p01 + dt2 * p11 + q00
        val newP01 = p01 + dt * p11 + q01
        val newP11 = p11 + q11

        p00 = newP00
        p01 = newP01
        p11 = newP11
    }

    /**
     * Measurement update with barometric altitude.
     */
    fun updateBaroAltitude(measuredAltM: Float) {
        if (!isInitialized) {
            reset(measuredAltM)
            return
        }
        updateAltitudeInternal(measuredAltM, baroAltVariance)
    }

    /**
     * Measurement update with GPS altitude (higher measurement variance).
     */
    fun updateGpsAltitude(measuredGpsAltM: Float) {
        if (!isInitialized) {
            reset(measuredGpsAltM)
            return
        }
        updateAltitudeInternal(measuredGpsAltM, gpsAltVariance)
    }

    /**
     * Measurement update with direct vertical speed (e.g. LK8EX1 native vario).
     */
    fun updateNativeVario(measuredVzMs: Float) {
        if (!isInitialized) return

        // Innovation y = z - Hx (H = [0, 1])
        val y = measuredVzMs - vz
        val s = p11 + nativeVarioVariance
        if (s <= 1e-6f) return

        // Kalman gains
        val k0 = p01 / s
        val k1 = p11 / s

        // State update
        altitude += k0 * y
        vz += k1 * y

        // Covariance update: P = (I - K*H) * P with prior values
        val p00Old = p00
        val p01Old = p01
        val p11Old = p11

        p00 = p00Old - k0 * p01Old
        p01 = p01Old - k1 * p01Old
        p11 = p11Old - k1 * p11Old
    }

    /**
     * Joint measurement update for both altitude and native vario (from single LK8EX1 sentence).
     */
    fun updateAltitudeAndVario(measuredAltM: Float, measuredVzMs: Float) {
        updateBaroAltitude(measuredAltM)
        updateNativeVario(measuredVzMs)
    }

    private fun updateAltitudeInternal(measuredAltM: Float, rVariance: Float) {
        // Innovation y = z - Hx (H = [1, 0])
        val y = measuredAltM - altitude
        val s = p00 + rVariance
        if (s <= 1e-6f) return

        // Kalman gains
        val k0 = p00 / s
        val k1 = p01 / s

        // State update
        altitude += k0 * y
        vz += k1 * y

        // Covariance update: P = (I - K*H) * P with prior values
        val p00Old = p00
        val p01Old = p01

        p00 = p00Old - k0 * p00Old
        p01 = p01Old - k0 * p01Old
        p11 -= k1 * p01Old
    }

    /**
     * Returns vertical speed clamped by deadband gate.
     * When |Vz| is below [deadbandEpsilon], returns 0.0 m/s to prevent stationary jitter.
     */
    fun getGatedVz(): Float {
        val currentVz = vz
        return if (kotlin.math.abs(currentVz) < deadbandEpsilon) 0.0f else currentVz
    }
}

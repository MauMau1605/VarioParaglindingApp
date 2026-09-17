package com.vario.app

import kotlin.math.pow

/**
 * Pure mathematical functions for variometer calculations:
 * - Barometric altitude conversion (standard atmosphere model)
 * - Frequency, duty cycle, and period mappings for audio synthesis
 *
 * All functions operate on primitives with zero heap allocation.
 */
object VarioMath {

    // ── Barometric constants ──────────────────────────────────────────────────
    /** Sea-level standard atmospheric pressure in Pascals. */
    const val P0_PA = 101325.0
    /** Barometric exponent for the troposphere: (R * L) / (g * M) ≈ 0.190263. */
    const val BARO_EXPONENT = 0.190263
    /** Standard troposphere temperature gradient coefficient in meters. */
    const val BARO_SCALE = 44330.0

    // ── Vz thresholds (m/s) ───────────────────────────────────────────────────
    const val VZ_CLIMB_THRESHOLD = 0.3f
    const val VZ_SINK_THRESHOLD = -2.0f

    // ── Climb tone parameters ─────────────────────────────────────────────────
    const val CLIMB_FREQ_MIN = 400.0
    const val CLIMB_FREQ_MAX = 1200.0
    const val CLIMB_VZ_MAX = 5.0f

    const val CLIMB_PERIOD_MAX = 10667
    const val CLIMB_PERIOD_MIN = 2667

    const val CLIMB_DUTY_MIN = 0.4
    const val CLIMB_DUTY_MAX = 0.85

    // ── Sink tone parameters ──────────────────────────────────────────────────
    const val SINK_FREQ_MAX = 400.0
    const val SINK_FREQ_MIN = 200.0
    const val SINK_VZ_MAX = -8.0f

    /**
     * Converts atmospheric pressure in Pascals to barometric altitude in meters,
     * based on the ICAO International Standard Atmosphere (ISA) model.
     *
     * @param pressurePa Pressure in Pascals (e.g. 101325 for standard sea level).
     * @param qnhPa Sea-level reference pressure in Pascals (default: standard atmosphere [P0_PA]).
     * @return Altitude in meters, or 0f if pressure is non-positive.
     */
    fun pressureToAltitude(pressurePa: Long, qnhPa: Double = P0_PA): Float {
        if (pressurePa <= 0L || qnhPa <= 0.0) return 0f
        val pressureRatio = pressurePa / qnhPa
        return (BARO_SCALE * (1.0 - pressureRatio.pow(BARO_EXPONENT))).toFloat()
    }

    /**
     * Calculates the QNH (sea-level equivalent pressure in Pascals) given a known
     * altitude in meters (e.g. from GPS) and current atmospheric pressure in Pascals.
     *
     * Inverting the ISA barometric formula:
     * P0 = P / (1 - h / 44330)^(1 / 0.190263)
     *
     * @param pressurePa Measured atmospheric pressure in Pascals.
     * @param gpsAltitudeM Ground/takeoff altitude in meters from GPS.
     * @return Calibrated sea-level pressure (QNH) in Pascals, or [P0_PA] if inputs are invalid.
     */
    fun calculateQnh(pressurePa: Long, gpsAltitudeM: Float): Double {
        if (pressurePa <= 0L) return P0_PA
        val term = 1.0 - (gpsAltitudeM / BARO_SCALE)
        if (term <= 0.0) return P0_PA
        val invExponent = 1.0 / BARO_EXPONENT
        return pressurePa / term.pow(invExponent)
    }

    /**
     * Clamps a value to the range [0.0, 1.0], returned as [Double] without autoboxing.
     */
    fun clamp01(value: Float): Double {
        return when {
            value <= 0f -> 0.0
            value >= 1f -> 1.0
            else -> value.toDouble()
        }
    }

    /**
     * Normalized climb ratio in [0.0, 1.0] between threshold (+0.3 m/s) and max (+5.0 m/s).
     */
    fun climbRatio(vz: Float): Double {
        return clamp01((vz - VZ_CLIMB_THRESHOLD) / (CLIMB_VZ_MAX - VZ_CLIMB_THRESHOLD))
    }

    /**
     * Frequency in Hz for climb tone given vertical speed [vz] in m/s.
     */
    fun climbFrequency(vz: Float): Double {
        return CLIMB_FREQ_MIN + (CLIMB_FREQ_MAX - CLIMB_FREQ_MIN) * climbRatio(vz)
    }

    /**
     * Cycle period in audio samples at 16 kHz for climb beep given [vz] in m/s.
     */
    fun climbPeriodSamples(vz: Float): Int {
        val ratio = climbRatio(vz)
        return (CLIMB_PERIOD_MAX - (CLIMB_PERIOD_MAX - CLIMB_PERIOD_MIN) * ratio).toInt()
    }

    /**
     * Duty cycle (ratio of sound vs silence) for climb beep given [vz] in m/s.
     */
    fun climbDutyCycle(vz: Float): Double {
        val ratio = climbRatio(vz)
        return CLIMB_DUTY_MIN + (CLIMB_DUTY_MAX - CLIMB_DUTY_MIN) * ratio
    }

    /**
     * Normalized sink ratio in [0.0, 1.0] between threshold (-2.0 m/s) and max sink (-8.0 m/s).
     */
    fun sinkRatio(vz: Float): Double {
        return clamp01((vz - VZ_SINK_THRESHOLD) / (SINK_VZ_MAX - VZ_SINK_THRESHOLD))
    }

    /**
     * Frequency in Hz for sink alarm given vertical speed [vz] in m/s.
     */
    fun sinkFrequency(vz: Float): Double {
        return SINK_FREQ_MAX - (SINK_FREQ_MAX - SINK_FREQ_MIN) * sinkRatio(vz)
    }
}

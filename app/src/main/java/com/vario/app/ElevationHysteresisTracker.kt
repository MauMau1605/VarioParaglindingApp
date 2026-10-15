package com.vario.app

/**
 * High-performance, zero-allocation elevation gain (D+) and loss (D-) accumulator
 * using a Peak-to-Valley Reversal Schmitt Trigger (Preisach hysteresis deadband).
 *
 * ## Noise-Immunity Guarantees:
 * Eliminates the stationary ratchet failure where alternating ±1m sensor noise or
 * footstep step-bounce accumulates false elevation gain and loss. For any noise sequence
 * bounded by peak-to-peak amplitude < [hysteresisThresholdM], accumulated gain and loss
 * are strictly 0.0 m.
 *
 * ## Zero-Allocation Contract:
 * All state is held in primitive fields. Runtime updates [update] execute strictly in O(1)
 * with zero heap allocations, zero boxing, and no object instantiations.
 *
 * @property hysteresisThresholdM Deadband threshold in meters required to confirm an altitude reversal.
 */
class ElevationHysteresisTracker(
    var hysteresisThresholdM: Float = 2.5f
) {
    /**
     * Active elevation trend state.
     */
    enum class Trend {
        /** Trend not yet established (waiting for first excursion >= hysteresisThresholdM) */
        UNDECIDED,
        /** Confirmed climbing phase; actively accumulating D+ on new highs */
        CLIMBING,
        /** Confirmed descending phase; actively accumulating D- on new lows */
        DESCENDING
    }

    /** Cumulative positive elevation gain (D+) in meters */
    var cumulativeGainM: Float = 0f
        private set

    /** Cumulative elevation loss (D-) in meters */
    var cumulativeLossM: Float = 0f
        private set

    /** Current trend direction */
    var trend: Trend = Trend.UNDECIDED
        private set

    /** Baseline pivot altitude established at initialization */
    var lastPivotAltM: Float = 0f
        private set

    /** Running peak during CLIMBING or running valley during DESCENDING */
    var tentativeExtremumAltM: Float = 0f
        private set

    /** True once a valid initial altitude (> 0m) has been provided */
    var isInitialized: Boolean = false
        private set

    /**
     * Resets tracker metrics and locks reference altitude to [initialAltitudeM].
     */
    fun reset(initialAltitudeM: Float = 0f) {
        cumulativeGainM = 0f
        cumulativeLossM = 0f
        lastPivotAltM = initialAltitudeM
        tentativeExtremumAltM = initialAltitudeM
        trend = Trend.UNDECIDED
        isInitialized = initialAltitudeM > 0f
    }

    /**
     * Updates elevation metrics with a new altitude sample.
     * ZERO HEAP ALLOCATIONS: strictly primitive float arithmetic and branch evaluations.
     */
    fun update(altitudeM: Float) {
        if (altitudeM <= 0f) return

        if (!isInitialized) {
            reset(altitudeM)
            return
        }

        when (trend) {
            Trend.UNDECIDED -> {
                val diff = altitudeM - lastPivotAltM
                if (diff >= hysteresisThresholdM) {
                    cumulativeGainM += diff
                    tentativeExtremumAltM = altitudeM
                    trend = Trend.CLIMBING
                } else if (-diff >= hysteresisThresholdM) {
                    cumulativeLossM += -diff
                    tentativeExtremumAltM = altitudeM
                    trend = Trend.DESCENDING
                }
            }
            Trend.CLIMBING -> {
                if (altitudeM > tentativeExtremumAltM) {
                    // Continuing ascent: credit new high
                    cumulativeGainM += (altitudeM - tentativeExtremumAltM)
                    tentativeExtremumAltM = altitudeM
                } else {
                    val drop = tentativeExtremumAltM - altitudeM
                    if (drop >= hysteresisThresholdM) {
                        // Reversal confirmed: peak committed, switch to descent
                        cumulativeLossM += drop
                        tentativeExtremumAltM = altitudeM
                        trend = Trend.DESCENDING
                    }
                    // Else: drop < threshold -> ignore noise dip
                }
            }
            Trend.DESCENDING -> {
                if (altitudeM < tentativeExtremumAltM) {
                    // Continuing descent: credit new low
                    cumulativeLossM += (tentativeExtremumAltM - altitudeM)
                    tentativeExtremumAltM = altitudeM
                } else {
                    val rise = altitudeM - tentativeExtremumAltM
                    if (rise >= hysteresisThresholdM) {
                        // Reversal confirmed: valley committed, switch to climb
                        cumulativeGainM += rise
                        tentativeExtremumAltM = altitudeM
                        trend = Trend.CLIMBING
                    }
                    // Else: rise < threshold -> ignore noise bump
                }
            }
        }
    }

    companion object {
        /**
         * One-shot calculation of elevation gain and loss over a sequence of altitudes.
         * Returns Pair(gainM, lossM).
         */
        fun computeGainAndLoss(
            altitudes: FloatArray,
            hysteresisThresholdM: Float = 2.5f
        ): Pair<Float, Float> {
            if (altitudes.isEmpty()) return Pair(0f, 0f)
            val tracker = ElevationHysteresisTracker(hysteresisThresholdM)
            tracker.reset(altitudes[0])
            for (i in 1 until altitudes.size) {
                tracker.update(altitudes[i])
            }
            return Pair(tracker.cumulativeGainM, tracker.cumulativeLossM)
        }
    }
}

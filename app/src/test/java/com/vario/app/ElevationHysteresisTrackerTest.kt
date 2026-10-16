package com.vario.app

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationHysteresisTrackerTest {

    private lateinit var tracker: ElevationHysteresisTracker

    @BeforeTest
    fun setUp() {
        tracker = ElevationHysteresisTracker(hysteresisThresholdM = 2.5f)
        tracker.reset(1000f)
    }

    @Test
    fun initialState_hasZeroGainAndLoss() {
        assertEquals(0f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)
        assertEquals(1000f, tracker.lastPivotAltM)
        assertEquals(1000f, tracker.tentativeExtremumAltM)
        assertEquals(ElevationHysteresisTracker.Trend.UNDECIDED, tracker.trend)
        assertTrue(tracker.isInitialized)
    }

    @Test
    fun smallJitterWithinThreshold_doesNotAccumulateGainOrLoss() {
        // Fluctuations within ±2.5m around 1000m
        val jitterAltitudes = listOf(1001.0f, 999.0f, 1002.0f, 998.5f, 1001.5f, 1000.0f)
        for (alt in jitterAltitudes) {
            tracker.update(alt)
            assertEquals(0f, tracker.cumulativeGainM, "Jitter should not accumulate D+")
            assertEquals(0f, tracker.cumulativeLossM, "Jitter should not accumulate D-")
            assertEquals(ElevationHysteresisTracker.Trend.UNDECIDED, tracker.trend)
        }
    }

    @Test
    fun sustainedClimb_accumulatesGainCorrectly() {
        // Climb past threshold of 2.5m: 1000 -> 1005 (+5m)
        tracker.update(1005f)
        assertEquals(5f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.CLIMBING, tracker.trend)

        // Continue climb: 1005 -> 1020 (+15m)
        tracker.update(1020f)
        assertEquals(20f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)
    }

    @Test
    fun sustainedDescent_accumulatesLossCorrectly() {
        // Descent past threshold of 2.5m: 1000 -> 990 (-10m)
        tracker.update(990f)
        assertEquals(0f, tracker.cumulativeGainM)
        assertEquals(10f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.DESCENDING, tracker.trend)

        // Continue descent: 990 -> 975 (-15m)
        tracker.update(975f)
        assertEquals(0f, tracker.cumulativeGainM)
        assertEquals(25f, tracker.cumulativeLossM)
    }

    @Test
    fun directionReversal_requiresCrossingHysteresisThreshold() {
        // 1. Climb from 1000 to 1050m (+50m)
        tracker.update(1050f)
        assertEquals(50f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)

        // 2. Minor drop of 1.5m (within 2.5m hysteresis): should not register descent or drop gain
        tracker.update(1048.5f)
        assertEquals(50f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.CLIMBING, tracker.trend)

        // 3. Significant drop reversing direction: 1050 -> 1040 (-10m from turning point)
        tracker.update(1040f)
        assertEquals(50f, tracker.cumulativeGainM)
        assertEquals(10f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.DESCENDING, tracker.trend)

        // 4. Minor rise of 1.0m (within 2.5m hysteresis): should not register climb or drop loss
        tracker.update(1041f)
        assertEquals(50f, tracker.cumulativeGainM)
        assertEquals(10f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.DESCENDING, tracker.trend)

        // 5. Significant climb reversing direction: 1040 -> 1060 (+20m from turning point)
        tracker.update(1060f)
        assertEquals(70f, tracker.cumulativeGainM)
        assertEquals(10f, tracker.cumulativeLossM)
        assertEquals(ElevationHysteresisTracker.Trend.CLIMBING, tracker.trend)
    }

    @Test
    fun reset_clearsCumulativeMetrics() {
        tracker.update(1050f)
        assertEquals(50f, tracker.cumulativeGainM)

        tracker.reset(2000f)
        assertEquals(0f, tracker.cumulativeGainM)
        assertEquals(0f, tracker.cumulativeLossM)
        assertEquals(2000f, tracker.lastPivotAltM)
        assertEquals(ElevationHysteresisTracker.Trend.UNDECIDED, tracker.trend)
    }

    @Test
    fun companionComputeGainAndLoss_matchesSequentialUpdates() {
        val altitudes = floatArrayOf(1000f, 1001f, 999f, 1020f, 1018f, 1005f)
        val (gain, loss) = ElevationHysteresisTracker.computeGainAndLoss(altitudes, 2.5f)
        // 1000 -> 1001, 999 (ignored noise)
        // 999/1000 -> 1020 (+20m climb, peak = 1020)
        // 1020 -> 1018 (drop of 2m < 2.5m, ignored noise)
        // 1020 -> 1005 (drop of 15m >= 2.5m, reversal to descent, D- = 15m)
        assertEquals(20f, gain)
        assertEquals(15f, loss)
    }
}

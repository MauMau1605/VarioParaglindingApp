package com.vario.app

/**
 * Immutable data class representing the variometer state.
 * Exposed via [StateFlow] from [VarioService] to the UI layer.
 *
 * This object is created only when data changes (off the audio fast-path),
 * so its allocation does not impact the zero-GC audio thread.
 *
 * @property altitudeM Altitude in meters, parsed from the LK8EX1 sentence.
 * @property vzMs Vertical speed (Vz) in meters per second. Positive = ascending.
 */
data class VarioData(
    val altitudeM: Float = 0f,
    val vzMs: Float = 0f
)

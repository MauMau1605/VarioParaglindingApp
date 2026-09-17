package com.vario.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.sin

/**
 * Zero-allocation audio engine for the variometer.
 *
 * Generates a continuous PCM sine wave whose frequency and on/off duty cycle
 * vary according to the current vertical speed (Vz).
 *
 * ## Audio behavior
 * - **Climb (Vz > +0.3 m/s):** Beeping sine wave. Frequency ramps from 400 Hz to 1200 Hz,
 *   beep rate from ~1.5 Hz to ~6 Hz, and duty cycle from 40 % to 85 % as Vz increases.
 * - **Sink (Vz < −2.0 m/s):** Continuous low-frequency tone (400 → 200 Hz).
 * - **Dead zone:** Silence.
 *
 * ## Zero-allocation contract
 * The [audioLoop] method and all functions it calls **must not allocate any JVM objects**.
 * All buffers are pre-allocated at construction time. The only inter-thread communication
 * is via a single `@Volatile var` ([currentVz]) — no locks, no queues, no boxing.
 *
 * @see <a href="https://developer.android.com/reference/android/media/AudioTrack">AudioTrack</a>
 */
class VarioAudioEngine {

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        /** Sample rate in Hz. 16 kHz is a good trade-off between quality and CPU cost. */
        const val SAMPLE_RATE = 16000
        private const val TWO_PI = 2.0 * PI

        /** PCM amplitude — ~73 % of Short.MAX_VALUE to avoid clipping. */
        const val AMPLITUDE = 24000

        /** Chunk size in samples. 512 samples @ 16 kHz = 32 ms latency. */
        const val BUFFER_SIZE = 512
    }

    // ── Inter-thread communication ───────────────────────────────────────────

    /**
     * Current vertical speed in m/s.
     * Written by the USB parser thread, read by the audio thread.
     * A single volatile field avoids any lock or allocation.
     */
    @Volatile
    var currentVz: Float = 0f

    /**
     * When true, audio output is muted (silence written to buffer).
     */
    @Volatile
    var isMuted: Boolean = false

    // ── Pre-allocated resources ──────────────────────────────────────────────

    /** PCM sample buffer — allocated once, reused every write cycle. */
    private val buffer = ShortArray(BUFFER_SIZE)

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    @Volatile
    private var running = false

    // ── Phase & beep state (persist across buffer fills to avoid clicks) ─────

    /** Sine wave phase accumulator (radians). Wraps at 2π to avoid precision loss. */
    private var phase = 0.0

    /** Sample counter within the current beep on/off cycle. */
    private var beepCounter = 0

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Start the audio engine. Creates an [AudioTrack] in low-latency mode
     * and spawns a dedicated high-priority thread for PCM generation.
     */
    fun start() {
        if (running) return

        val minTrackBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufferSize = if (minTrackBufferSize > 0) {
            maxOf(minTrackBufferSize, BUFFER_SIZE * 2)
        } else {
            BUFFER_SIZE * 2
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        running = true
        track.play()

        audioThread = Thread({
            // Elevate to URGENT_AUDIO to get real-time scheduling priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            audioLoop(track)
        }, "VarioAudio").also { it.start() }
    }

    /**
     * Stop the audio engine. Joins the audio thread and releases the AudioTrack.
     */
    fun stop() {
        running = false
        audioThread?.join(1000)
        audioThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        phase = 0.0
        beepCounter = 0
    }

    // ── Audio loop — ZERO ALLOCATION ZONE ────────────────────────────────────

    /**
     * Main audio loop. Runs on a dedicated thread until [running] is set to false.
     *
     * **CRITICAL:** No object creation past this point. No strings, no collections,
     * no boxing, no lambda captures. Every call from here must be inline-safe.
     */
    private fun audioLoop(track: AudioTrack) {
        while (running) {
            val vz = currentVz // single volatile read per buffer fill
            generateBuffer(vz, buffer)
            track.write(buffer, 0, buffer.size)
        }
    }

    /**
     * Fills [outBuffer] with the appropriate PCM waveform for the given [vz].
     * Exposed as internal for unit testing without starting an [AudioTrack].
     */
    internal fun generateBuffer(vz: Float, outBuffer: ShortArray) {
        if (isMuted) {
            fillSilence(outBuffer)
            return
        }
        when {
            vz >= VarioMath.VZ_CLIMB_THRESHOLD -> fillClimbTone(vz, outBuffer)
            vz <= VarioMath.VZ_SINK_THRESHOLD -> fillSinkTone(vz, outBuffer)
            else -> fillSilence(outBuffer)
        }
    }

    internal fun resetPhaseAndBeep() {
        phase = 0.0
        beepCounter = 0
    }

    /**
     * Fill the buffer with an ascending beep tone using parameters from [VarioMath].
     */
    private fun fillClimbTone(vz: Float, outBuffer: ShortArray) {
        val freq = VarioMath.climbFrequency(vz)
        val period = VarioMath.climbPeriodSamples(vz)
        val dutyOnSamples = (period * VarioMath.climbDutyCycle(vz)).toInt()
        val phaseInc = TWO_PI * freq / SAMPLE_RATE

        for (i in outBuffer.indices) {
            val inOnPhase = beepCounter < dutyOnSamples
            outBuffer[i] = if (inOnPhase) {
                (sin(phase) * AMPLITUDE).toInt().toShort()
            } else {
                0
            }
            phase += phaseInc
            if (phase >= TWO_PI) phase -= TWO_PI

            beepCounter++
            if (beepCounter >= period) beepCounter = 0
        }
    }

    /**
     * Fill the buffer with a continuous descending alarm tone using [VarioMath.sinkFrequency].
     */
    private fun fillSinkTone(vz: Float, outBuffer: ShortArray) {
        val freq = VarioMath.sinkFrequency(vz)
        val phaseInc = TWO_PI * freq / SAMPLE_RATE

        for (i in outBuffer.indices) {
            outBuffer[i] = (sin(phase) * AMPLITUDE).toInt().toShort()
            phase += phaseInc
            if (phase >= TWO_PI) phase -= TWO_PI
        }
        beepCounter = 0
    }

    /**
     * Fill the buffer with silence. Resets phase to avoid DC offset/clicks.
     */
    private fun fillSilence(outBuffer: ShortArray) {
        for (i in outBuffer.indices) {
            outBuffer[i] = 0
        }
        phase = 0.0
        beepCounter = 0
    }
}

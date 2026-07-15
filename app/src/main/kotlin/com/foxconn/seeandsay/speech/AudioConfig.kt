package com.foxconn.seeandsay.speech

import android.media.AudioFormat

/**
 * Centralizes the PCM format and buffer calculations shared by capture and debug playback.
 *
 * The object performs only deterministic arithmetic and constant lookup, so callers may use it on
 * any dispatcher. It owns no resource and has no cancellation behavior. Invalid device buffer
 * sizes and negative durations fail immediately with [IllegalArgumentException].
 */
object AudioConfig {

    /** Capture and playback sample rate required by the M1.1 cloud STT contract. */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /** Android input channel mask for one microphone channel. */
    const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO

    /** Android output channel mask used by the raw-PCM debug player. */
    const val OUTPUT_CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_OUT_MONO

    /** Signed little-endian 16-bit PCM encoding used at both audio boundaries. */
    const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT

    /** Number of channels represented by the mono input and output masks. */
    const val CHANNEL_COUNT: Int = 1

    /** Storage occupied by one PCM16 sample for one channel. */
    const val BYTES_PER_SAMPLE: Int = 2

    /** Target duration of each emitted microphone chunk. */
    const val CHUNK_DURATION_MS: Int = 100

    /**
     * Target microphone chunk size: 16,000 samples/s × 1 channel × 2 bytes/sample × 0.1 s.
     */
    const val CHUNK_SIZE_BYTES: Int = 3_200

    /** Maximum raw audio duration retained by the debug record-then-playback tool. */
    const val DEBUG_PLAYBACK_MAX_DURATION_MS: Int = 10_000

    /** Maximum debug PCM size, equivalent to exactly ten seconds of this audio format. */
    const val DEBUG_PLAYBACK_MAX_BYTES: Int = 320_000

    /**
     * Chooses an AudioRecord buffer that honors the device minimum and holds at least two chunks.
     *
     * @param deviceMinimumBytes positive byte count returned by `AudioRecord.getMinBufferSize`.
     * @return the larger of the device minimum and two 100 ms chunks.
     * @throws IllegalArgumentException when [deviceMinimumBytes] is zero or an Android error code.
     *
     * This pure calculation may run on any dispatcher, performs no blocking work, and has no
     * cancellation behavior. Two chunks provide scheduling headroom without changing Flow chunk
     * boundaries.
     */
    fun recorderBufferSizeBytes(deviceMinimumBytes: Int): Int {
        require(deviceMinimumBytes > 0) {
            "AudioRecord reported an invalid minimum buffer size: $deviceMinimumBytes"
        }
        return maxOf(deviceMinimumBytes, CHUNK_SIZE_BYTES * 2)
    }

    /**
     * Converts a duration to its exact byte capacity for the configured PCM format.
     *
     * @param durationMs non-negative whole-millisecond duration.
     * @return number of PCM bytes representing [durationMs], rounded down to a whole millisecond.
     * @throws IllegalArgumentException when [durationMs] is negative.
     *
     * This pure calculation may run on any dispatcher and has no cancellation behavior. The use of
     * [Long] avoids intermediate overflow for practical debug and test durations.
     */
    fun bytesForDurationMs(durationMs: Long): Long {
        require(durationMs >= 0) { "Audio duration must not be negative." }
        return SAMPLE_RATE_HZ.toLong() * CHANNEL_COUNT * BYTES_PER_SAMPLE * durationMs / 1_000L
    }

    /**
     * Converts a complete mono PCM byte count into AudioTrack frames.
     *
     * @param byteCount non-negative PCM byte count aligned to complete PCM16 samples.
     * @return frame count represented by [byteCount].
     * @throws IllegalArgumentException when the count is negative or not frame-aligned.
     *
     * This pure calculation is safe on any dispatcher and has no cancellation behavior.
     */
    fun frameCountForBytes(byteCount: Int): Int {
        val bytesPerFrame = CHANNEL_COUNT * BYTES_PER_SAMPLE
        require(byteCount >= 0 && byteCount % bytesPerFrame == 0) {
            "PCM byte count must be non-negative and frame-aligned: $byteCount"
        }
        return byteCount / bytesPerFrame
    }

    /**
     * Estimates playback duration for a complete mono PCM buffer.
     *
     * @param byteCount non-negative PCM byte count aligned to complete PCM16 samples.
     * @return playback duration rounded up to a whole millisecond.
     * @throws IllegalArgumentException when [byteCount] is invalid for [frameCountForBytes].
     *
     * This pure calculation may run on any dispatcher and has no cancellation behavior.
     */
    fun playbackDurationMs(byteCount: Int): Long {
        val frames = frameCountForBytes(byteCount)
        return (frames * 1_000L + SAMPLE_RATE_HZ - 1L) / SAMPLE_RATE_HZ
    }
}

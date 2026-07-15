package com.foxconn.seeandsay.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies PCM chunk, duration, frame, and AudioRecord buffer arithmetic without Android hardware.
 *
 * Tests execute synchronously on JUnit's thread, perform no I/O, and own no cancellable work.
 * Assertion failures identify a format mismatch that would change capture pitch, speed, or limits.
 */
class AudioConfigTest {

    /**
     * Verifies one 100 ms chunk and the ten-second cap match 16 kHz mono PCM16 arithmetic.
     *
     * @return This test has no return value.
     *
     * The test is pure, synchronous, and has no failure mode beyond assertion failure.
     */
    @Test
    fun chunkAndDebugCapacityMatchPcmFormat() {
        assertEquals(3_200, AudioConfig.CHUNK_SIZE_BYTES)
        assertEquals(
            AudioConfig.CHUNK_SIZE_BYTES.toLong(),
            AudioConfig.bytesForDurationMs(AudioConfig.CHUNK_DURATION_MS.toLong()),
        )
        assertEquals(
            AudioConfig.DEBUG_PLAYBACK_MAX_BYTES.toLong(),
            AudioConfig.bytesForDurationMs(AudioConfig.DEBUG_PLAYBACK_MAX_DURATION_MS.toLong()),
        )
    }

    /**
     * Verifies recorder buffering honors the device minimum and keeps two chunks of headroom.
     *
     * @return This test has no return value.
     *
     * The test is pure and synchronous. Invalid platform results must fail immediately rather than
     * allowing a capture Flow to hang.
     */
    @Test
    fun recorderBufferHonorsMinimumAndRejectsErrors() {
        assertEquals(6_400, AudioConfig.recorderBufferSizeBytes(1_024))
        assertEquals(12_000, AudioConfig.recorderBufferSizeBytes(12_000))
        assertThrows(IllegalArgumentException::class.java) {
            AudioConfig.recorderBufferSizeBytes(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioConfig.recorderBufferSizeBytes(-2)
        }
    }

    /**
     * Verifies byte/frame and playback-duration conversion at the configured sample rate.
     *
     * @return This test has no return value.
     *
     * The test is pure, synchronous, and has no cancellation behavior. Assertion failure indicates
     * an AudioTrack completion marker or timeout calculation regression.
     */
    @Test
    fun frameAndPlaybackDurationMathIsExact() {
        assertEquals(1_600, AudioConfig.frameCountForBytes(3_200))
        assertEquals(100L, AudioConfig.playbackDurationMs(3_200))
        assertThrows(IllegalArgumentException::class.java) {
            AudioConfig.frameCountForBytes(3)
        }
    }
}

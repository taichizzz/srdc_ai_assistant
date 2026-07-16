package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.speech.AudioConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the non-secret Phase 5 Google STT request settings without loading a provider SDK.
 *
 * Tests run synchronously on JUnit's thread, perform no network, filesystem, or Android I/O, and own
 * no coroutine or cancellable resource. Assertion failures identify accidental API/model/locale or
 * audio-format drift before the streaming client is implemented.
 */
class GcpSttConfigTest {

    /**
     * Verifies the chosen V1 short-command model and Taiwan Mandarin options remain explicit.
     *
     * @return This test has no return value.
     *
     * The test reads immutable constants on JUnit's thread, performs no I/O, and fails only through
     * an assertion when the agreed provider configuration changes unexpectedly.
     */
    @Test
    fun usesAgreedGoogleV1RecognitionSettings() {
        assertEquals("v1", GcpSttConfig.API_VERSION)
        assertEquals("speech.googleapis.com", GcpSttConfig.SERVICE_HOST)
        assertEquals("cmn-Hant-TW", GcpSttConfig.LANGUAGE_CODE)
        assertEquals("latest_short", GcpSttConfig.MODEL)
        assertEquals("LINEAR16", GcpSttConfig.AUDIO_ENCODING)
        assertTrue(GcpSttConfig.INTERIM_RESULTS_ENABLED)
    }

    /**
     * Verifies cloud request audio metadata exactly matches microphone capture constants.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and pure with no cancellation behavior. Assertion failure indicates
     * Phase 5 would require unintended transcoding or could send incorrect pitch/speed metadata.
     */
    @Test
    fun cloudAudioMetadataMatchesCaptureFormat() {
        assertEquals(AudioConfig.SAMPLE_RATE_HZ, GcpSttConfig.SAMPLE_RATE_HZ)
        assertEquals(AudioConfig.CHANNEL_COUNT, GcpSttConfig.CHANNEL_COUNT)
    }
}

package com.foxconn.seeandsay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure DEBUG echo-rule gates without Compose, Android, microphone, or audio resources.
 *
 * Tests execute synchronously with immutable state and have no coroutine, I/O, failure, or
 * cancellation behavior.
 */
class DebugAudioExclusionPolicyTest {

    /**
     * Verifies the full TTS Speaking state blocks every microphone entry point.
     *
     * @return This test has no return value.
     *
     * The pure assertion fails if the architecture echo rule can regress at the shared UI gate.
     */
    @Test
    fun speakingBlocksMicrophoneControls() {
        val ttsState = TtsUiState(status = TtsStatus.Speaking)

        assertFalse(DebugAudioExclusionPolicy.canUseMicrophone(ttsState))
        assertTrue(DebugAudioExclusionPolicy.isTtsSpeaking(ttsState))
    }

    /**
     * Verifies active capture and draining block TTS until microphone ownership is released.
     *
     * @return This test has no return value.
     *
     * Pure state checks cover production listening, debug recording, cloud smoke testing, and Stop
     * draining. No microphone or coroutine is started by this test.
     */
    @Test
    fun activeCaptureOrDrainBlocksTtsStart() {
        val idleTts = TtsUiState()

        assertFalse(
            DebugAudioExclusionPolicy.canStartTts(
                SttUiState(status = SttStatus.Listening),
                idleTts,
            ),
        )
        assertFalse(
            DebugAudioExclusionPolicy.canStartTts(
                SttUiState(isDebugRecording = true),
                idleTts,
            ),
        )
        assertFalse(
            DebugAudioExclusionPolicy.canStartTts(
                SttUiState(isCloudSttSmokeTestRunning = true),
                idleTts,
            ),
        )
        assertFalse(
            DebugAudioExclusionPolicy.canStartTts(
                SttUiState(status = SttStatus.Stopping),
                idleTts,
            ),
        )
        assertTrue(DebugAudioExclusionPolicy.canStartTts(SttUiState(), idleTts))
    }
}

package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.MainDispatcherRule
import com.foxconn.seeandsay.speech.AudioCaptureSource
import com.foxconn.seeandsay.speech.PcmAudioPlayer
import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies Phase 2 reducers remain correct after Phase 3 adds lifecycle-owned audio coroutines.
 *
 * Tests run against inert fake audio boundaries with a controlled main dispatcher. They perform no
 * Android, microphone, speaker, or network I/O. Each active fake capture is explicitly stopped or
 * cancelled so test completion exercises ViewModel session cancellation.
 */
class SttViewModelTest {

    /** Controlled `Dispatchers.Main` replacement required by `viewModelScope`. */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Verifies typed input uses the final-result path, appends once, and clears interim text.
     *
     * @return This test has no return value.
     *
     * The test runs on the test dispatcher without I/O. It fails only when transcript reduction
     * regresses and starts no cancellable audio session.
     */
    @Test
    fun typedInputAppendsOnceAndClearsPartialTranscript() {
        val viewModel = createViewModel()
        viewModel.onSttResult(SttResult(transcript = "你", isFinal = false))

        viewModel.submitTypedTranscript(" 你好 ")

        val state = viewModel.uiState.value
        assertEquals("你好", state.finalTranscript)
        assertTrue(state.partialTranscript.isEmpty())
        assertEquals(SttStatus.Completed, state.status)
    }

    /**
     * Verifies Retry clears a recoverable error and returns the debug screen to Idle.
     *
     * @return This test has no return value.
     *
     * The test executes synchronously on the controlled main dispatcher, performs no I/O, and fails
     * only when recovery does not restore usable state.
     */
    @Test
    fun retryClearsErrorAndReturnsToIdle() {
        val viewModel = createViewModel()
        viewModel.onExternalActionFailed("Settings unavailable")

        viewModel.onRetryRequested()

        val state = viewModel.uiState.value
        assertEquals(SttStatus.Idle, state.status)
        assertNull(state.errorMessage)
    }

    /**
     * Verifies Start begins fake capture after a grant and Stop completes after cancellation.
     *
     * @return This test has no return value.
     *
     * [runTest] controls coroutine progress. The fake capture suspends until cancelled and performs
     * no I/O; assertion failure identifies state/cancellation regressions. No text is fabricated.
     */
    @Test
    fun startAndStopDriveRealCaptureSessionState() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.onStartRequested()
            assertEquals(SttStatus.RequestingPermission, viewModel.uiState.value.status)

            viewModel.onMicrophonePermissionResult(
                isGranted = true,
                isPermanentlyDenied = false,
            )
            assertEquals(SttStatus.Listening, viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.finalTranscript.isEmpty())

            viewModel.onStopRequested()
            assertEquals(SttStatus.Completed, viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.finalTranscript.isEmpty())
        }

    /**
     * Verifies retryable permission denial remains distinguishable from permanent denial.
     *
     * @return This test has no return value.
     *
     * The test is synchronous, starts no audio coroutine, and fails only if denial cannot offer a
     * subsequent permission request.
     */
    @Test
    fun retryablePermissionDenialIsRecoverable() {
        val viewModel = createViewModel()
        viewModel.onStartRequested()

        viewModel.onMicrophonePermissionResult(
            isGranted = false,
            isPermanentlyDenied = false,
        )

        assertEquals(SttStatus.Error, viewModel.uiState.value.status)
        assertEquals(
            MicrophonePermissionStatus.Denied,
            viewModel.uiState.value.microphonePermission,
        )
        assertTrue(viewModel.uiState.value.errorMessage?.contains("Retry") == true)
    }

    /**
     * Verifies permanent permission denial retains the Settings-only recovery signal.
     *
     * @return This test has no return value.
     *
     * The test is synchronous, starts no audio coroutine, and fails only when permanent denial is
     * collapsed into a retryable permission state.
     */
    @Test
    fun permanentPermissionDenialRequiresSettingsRecovery() {
        val viewModel = createViewModel()
        viewModel.onStartRequested()

        viewModel.onMicrophonePermissionResult(
            isGranted = false,
            isPermanentlyDenied = true,
        )

        assertEquals(SttStatus.Error, viewModel.uiState.value.status)
        assertEquals(
            MicrophonePermissionStatus.PermanentlyDenied,
            viewModel.uiState.value.microphonePermission,
        )
        assertTrue(viewModel.uiState.value.errorMessage?.contains("settings") == true)
    }

    /**
     * Verifies revoking a granted permission cancels capture and restores requestable idle state.
     *
     * @return This test has no return value.
     *
     * [runTest] controls the capture coroutine. Revocation cancels the inert fake Flow, performs no
     * device I/O, and fails only when stale permission/session state survives.
     */
    @Test
    fun revokedGrantedPermissionReturnsToNotRequested() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            viewModel.onMicrophonePermissionObserved(isGranted = true)
            viewModel.onStartRequested()

            viewModel.onMicrophonePermissionObserved(isGranted = false)

            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertEquals(
                MicrophonePermissionStatus.NotRequested,
                viewModel.uiState.value.microphonePermission,
            )
        }

    /**
     * Verifies loopback joins microphone cleanup before beginning raw PCM playback.
     *
     * @return This test has no return value.
     *
     * [runTest] drives a fake capture that records cleanup ordering and a fake player that records
     * invocation. No platform I/O occurs. The explicit second button event cancels capture; failure
     * identifies an echo-rule regression or incorrect PCM concatenation.
     */
    @Test
    fun debugLoopbackReleasesCaptureBeforePlayback() =
        runTest(mainDispatcherRule.dispatcher) {
            val events = mutableListOf<String>()
            var playedPcm = ByteArray(0)
            val viewModel =
                createViewModel(
                    audioCaptureSource =
                        AudioCaptureSource {
                            flow {
                                events += "capture-started"
                                try {
                                    emit(byteArrayOf(1, 2, 3, 4))
                                    awaitCancellation()
                                } finally {
                                    events += "capture-released"
                                }
                            }
                        },
                    pcmAudioPlayer =
                        PcmAudioPlayer { pcm ->
                            events += "playback-started"
                            playedPcm = pcm
                        },
                )
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onDebugRecordAndPlaybackRequested()
            assertTrue(viewModel.uiState.value.isDebugRecording)
            viewModel.onDebugRecordAndPlaybackRequested()

            assertEquals(
                listOf("capture-started", "capture-released", "playback-started"),
                events,
            )
            assertEquals(listOf<Byte>(1, 2, 3, 4), playedPcm.toList())
            assertFalse(viewModel.uiState.value.isDebugPlaybackActive)
            assertEquals(SttStatus.Completed, viewModel.uiState.value.status)
        }

    /**
     * Creates a ViewModel with a cold fake capture that remains active until collector cancellation.
     *
     * @param audioCaptureSource cold fake PCM source for the test scenario.
     * @param pcmAudioPlayer fake raw PCM sink for the test scenario.
     * @return ViewModel whose capture and playback boundaries perform no platform I/O.
     *
     * Creation is synchronous on the installed test main dispatcher. The Flow is cold and throws no
     * failure; collection suspends cancellably until Stop, replacement, or test cleanup.
     */
    private fun createViewModel(
        audioCaptureSource: AudioCaptureSource =
            AudioCaptureSource {
                flow { awaitCancellation() }
            },
        pcmAudioPlayer: PcmAudioPlayer = PcmAudioPlayer { },
    ): SttViewModel =
        SttViewModel(
            audioCaptureSource = audioCaptureSource,
            pcmAudioPlayer = pcmAudioPlayer,
        )
}

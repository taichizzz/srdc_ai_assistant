package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.speech.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure Phase 2 state transitions without an emulator, microphone, or cloud service.
 *
 * Tests execute synchronously on JUnit's test thread. The ViewModel launches no coroutine in Phase
 * 2, so there is no dispatcher replacement or asynchronous cancellation requirement. Assertion
 * failures identify reducer regressions and no platform failure is expected.
 */
class SttViewModelTest {

    /**
     * Verifies typed input uses the final-result path, appends once, and clears interim text.
     *
     * @return This test has no return value.
     *
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when the reducer produces an incorrect transcript state.
     */
    @Test
    fun typedInputAppendsOnceAndClearsPartialTranscript() {
        val viewModel = SttViewModel()
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
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when error recovery does not restore a usable state.
     */
    @Test
    fun retryClearsErrorAndReturnsToIdle() {
        val viewModel = SttViewModel()
        viewModel.onExternalActionFailed("Settings unavailable")

        viewModel.onRetryRequested()

        val state = viewModel.uiState.value
        assertEquals(SttStatus.Idle, state.status)
        assertNull(state.errorMessage)
    }

    /**
     * Verifies Start requests permission, a grant enters Listening, and Stop completes the session.
     *
     * @return This test has no return value.
     *
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when push-to-talk state transitions regress; it intentionally verifies no transcript is
     * fabricated by Start or Stop.
     */
    @Test
    fun startAndStopDriveOnlyPermissionAndSessionState() {
        val viewModel = SttViewModel()

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
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when the denial branch cannot offer another permission request.
     */
    @Test
    fun retryablePermissionDenialIsRecoverable() {
        val viewModel = SttViewModel()
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
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when permanent denial is collapsed into a retryable permission state.
     */
    @Test
    fun permanentPermissionDenialRequiresSettingsRecovery() {
        val viewModel = SttViewModel()
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
     * Verifies revoking a previously granted permission restores a requestable idle state.
     *
     * @return This test has no return value.
     *
     * The test is synchronous, launches no coroutine, and has no cancellation behavior. It fails
     * only when a platform permission snapshot leaves stale Granted state in the debug UI.
     */
    @Test
    fun revokedGrantedPermissionReturnsToNotRequested() {
        val viewModel = SttViewModel()
        viewModel.onMicrophonePermissionObserved(isGranted = true)
        viewModel.onStartRequested()

        viewModel.onMicrophonePermissionObserved(isGranted = false)

        assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
        assertEquals(
            MicrophonePermissionStatus.NotRequested,
            viewModel.uiState.value.microphonePermission,
        )
    }
}

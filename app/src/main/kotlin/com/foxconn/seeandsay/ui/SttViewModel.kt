package com.foxconn.seeandsay.ui

import androidx.lifecycle.ViewModel
import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the lifecycle-aware M1.1 UI state and reduces permission/transcript events into that state.
 *
 * Phase 2 intentionally contains no audio capture, STT client, network call, or background
 * coroutine. Event methods are synchronous and intended to be called from the main thread. StateFlow
 * updates are atomic; methods throw no project-specific failures and own no cancellable resources.
 * A later phase can feed real `SttResult` values through [onSttResult] without changing the reducer
 * used by the required typed-input path.
 */
class SttViewModel : ViewModel() {

    private val mutableUiState = MutableStateFlow(SttUiState())

    /**
     * Exposes immutable, lifecycle-friendly UI state to the activity and Compose screen.
     *
     * Collection may occur on any coroutine context and can be cancelled without changing the
     * stored state. Reading this property performs no I/O and throws no project-specific failure.
     */
    val uiState: StateFlow<SttUiState> = mutableUiState.asStateFlow()

    /**
     * Handles a push-to-talk Start request as permission and state transitions only.
     *
     * @return This function has no return value.
     *
     * The function performs no audio or network work. It is synchronous, intended for the main
     * thread, and has no cancellation behavior. A permanent denial becomes a recoverable error;
     * otherwise the state either requests permission or enters the Phase 2 listening placeholder.
     */
    fun onStartRequested() {
        mutableUiState.update { current ->
            when (current.microphonePermission) {
                MicrophonePermissionStatus.Granted ->
                    current.copy(
                        status = SttStatus.Listening,
                        partialTranscript = "",
                        errorMessage = null,
                    )

                MicrophonePermissionStatus.PermanentlyDenied ->
                    current.copy(
                        status = SttStatus.Error,
                        errorMessage = PERMANENT_PERMISSION_ERROR,
                    )

                MicrophonePermissionStatus.NotRequested,
                MicrophonePermissionStatus.Denied,
                ->
                    current.copy(
                        status = SttStatus.RequestingPermission,
                        errorMessage = null,
                    )
            }
        }
    }

    /**
     * Handles a push-to-talk Stop request without touching a microphone or STT stream.
     *
     * @return This function has no return value.
     *
     * The synchronous main-thread event has no failure or cancellation mode. Active Phase 2 states
     * become `Completed`; idle, completed, and error states remain usable and unchanged.
     */
    fun onStopRequested() {
        mutableUiState.update { current ->
            if (
                current.status == SttStatus.Listening ||
                    current.status == SttStatus.Connecting ||
                    current.status == SttStatus.Stopping
            ) {
                current.copy(status = SttStatus.Completed, partialTranscript = "")
            } else {
                current
            }
        }
    }

    /**
     * Reduces Android's completed microphone permission request into recoverable UI state.
     *
     * @param isGranted whether Android granted `RECORD_AUDIO`.
     * @param isPermanentlyDenied whether Android will suppress future permission dialogs.
     * @return This function has no return value.
     *
     * The method is synchronous, intended for the main thread, and launches no coroutine. Denial is
     * represented as UI state rather than thrown; a granted result continues the pending Start
     * request into `Listening` but still allocates no microphone in Phase 2.
     */
    fun onMicrophonePermissionResult(
        isGranted: Boolean,
        isPermanentlyDenied: Boolean,
    ) {
        mutableUiState.update { current ->
            when {
                isGranted ->
                    current.copy(
                        status =
                            if (current.status == SttStatus.RequestingPermission) {
                                SttStatus.Listening
                            } else {
                                SttStatus.Idle
                            },
                        errorMessage = null,
                        microphonePermission = MicrophonePermissionStatus.Granted,
                    )

                isPermanentlyDenied ->
                    current.copy(
                        status = SttStatus.Error,
                        errorMessage = PERMANENT_PERMISSION_ERROR,
                        microphonePermission = MicrophonePermissionStatus.PermanentlyDenied,
                    )

                else ->
                    current.copy(
                        status = SttStatus.Error,
                        errorMessage = RETRYABLE_PERMISSION_ERROR,
                        microphonePermission = MicrophonePermissionStatus.Denied,
                    )
            }
        }
    }

    /**
     * Reconciles a platform permission snapshot observed during activity resume.
     *
     * @param isGranted whether Android currently grants `RECORD_AUDIO`.
     * @return This function has no return value.
     *
     * The method is synchronous and intended for the main thread. It launches no coroutine and has
     * no cancellation behavior. A newly granted permission clears permission-related errors without
     * starting a session. A revoked prior grant returns to `NotRequested`; known denial details are
     * otherwise preserved because Android alone does not reveal whether another dialog is allowed
     * outside a request result.
     */
    fun onMicrophonePermissionObserved(isGranted: Boolean) {
        mutableUiState.update { current ->
            when {
                isGranted ->
                    current.copy(
                        status =
                            if (current.status == SttStatus.Error) {
                                SttStatus.Idle
                            } else {
                                current.status
                            },
                        errorMessage = null,
                        microphonePermission = MicrophonePermissionStatus.Granted,
                    )

                current.microphonePermission == MicrophonePermissionStatus.Granted ->
                    current.copy(
                        status = SttStatus.Idle,
                        errorMessage = null,
                        microphonePermission = MicrophonePermissionStatus.NotRequested,
                    )

                else -> current
            }
        }
    }

    /**
     * Submits emulator/debug text through the same final-result reducer used by future cloud STT.
     *
     * @param transcript manually entered transcript; surrounding whitespace is ignored.
     * @return This function has no return value.
     *
     * The method is synchronous, permission-independent, intended for the main thread, and owns no
     * cancellable work. Blank input is ignored rather than treated as a failure.
     */
    fun submitTypedTranscript(transcript: String) {
        val normalizedTranscript = transcript.trim()
        if (normalizedTranscript.isEmpty()) return

        // Typed input is load-bearing: routing it through the normal STT reducer prevents later
        // phases from maintaining a separate emulator-only transcript path.
        onSttResult(
            SttResult(
                transcript = normalizedTranscript,
                isFinal = true,
            ),
        )
    }

    /**
     * Reduces one provider-neutral interim or final recognition result into UI state.
     *
     * @param result interim text that replaces the partial line, or a final segment to append once.
     * @return This function has no return value.
     *
     * The reducer is synchronous, intended for main-thread event delivery, and has no cancellation
     * behavior. Blank text is ignored. It performs no provider or network work and throws no
     * project-specific failure.
     */
    fun onSttResult(result: SttResult) {
        val transcript = result.transcript.trim()
        if (transcript.isEmpty()) return

        mutableUiState.update { current ->
            if (result.isFinal) {
                current.copy(
                    status = SttStatus.Completed,
                    partialTranscript = "",
                    finalTranscript = appendFinalTranscript(current.finalTranscript, transcript),
                    errorMessage = null,
                )
            } else {
                current.copy(
                    status = SttStatus.Listening,
                    partialTranscript = transcript,
                    errorMessage = null,
                )
            }
        }
    }

    /**
     * Clears a recoverable error while retaining permission information and committed transcripts.
     *
     * @return This function has no return value.
     *
     * The method is synchronous, intended for the main thread, and owns no cancellable work. It
     * never retries platform or network work directly; the user may press Start again or open
     * Settings according to the retained permission status.
     */
    fun onRetryRequested() {
        mutableUiState.update { current ->
            current.copy(status = SttStatus.Idle, errorMessage = null)
        }
    }

    /**
     * Converts failure to launch an external recovery action into a visible, retryable error.
     *
     * @param message non-secret description of the platform failure.
     * @return This function has no return value.
     *
     * The method is synchronous, intended for the main thread, and has no coroutine or cancellation
     * behavior. Blank messages are replaced with a generic failure so the UI never silently hangs.
     */
    fun onExternalActionFailed(message: String) {
        mutableUiState.update { current ->
            current.copy(
                status = SttStatus.Error,
                errorMessage = message.ifBlank { GENERIC_RECOVERY_ERROR },
            )
        }
    }

    /**
     * Appends one committed segment using a newline boundary suitable for the debug transcript log.
     *
     * @param existing previously committed transcript text.
     * @param segment newly committed non-blank segment.
     * @return `segment` when no prior text exists, otherwise both values separated by one newline.
     *
     * This pure function performs no I/O, is safe on any dispatcher, has no cancellation behavior,
     * and throws no project-specific failure.
     */
    private fun appendFinalTranscript(existing: String, segment: String): String =
        if (existing.isBlank()) segment else "$existing\n$segment"

    private companion object {
        const val RETRYABLE_PERMISSION_ERROR =
            "Microphone permission is required for speech input. Retry to ask again."
        const val PERMANENT_PERMISSION_ERROR =
            "Microphone permission is permanently denied. Open app settings to enable it."
        const val GENERIC_RECOVERY_ERROR = "The recovery action could not be opened. Please retry."
    }
}

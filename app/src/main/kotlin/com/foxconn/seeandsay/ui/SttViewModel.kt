package com.foxconn.seeandsay.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxconn.seeandsay.speech.AudioCaptureSource
import com.foxconn.seeandsay.speech.AudioConfig
import com.foxconn.seeandsay.speech.BoundedPcmBuffer
import com.foxconn.seeandsay.speech.PcmAudioPlayer
import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns M1.1 microphone sessions and reduces permission/transcript events into immutable UI state.
 *
 * @param audioCaptureSource cold provider-neutral PCM source shared by production capture and debug
 * loopback.
 * @param pcmAudioPlayer raw PCM output used only after debug capture has fully stopped.
 *
 * Public event methods are intended for Android's main thread. Capture and playback are children of
 * [viewModelScope]; replacing or clearing a session cancels and joins its predecessor so microphone
 * and playback resources are released in order. Device failures become recoverable UI errors, while
 * normal coroutine cancellation is never surfaced as an error.
 */
class SttViewModel(
    private val audioCaptureSource: AudioCaptureSource,
    private val pcmAudioPlayer: PcmAudioPlayer,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(SttUiState())
    private var sessionJob: Job? = null
    private var pendingCapture = PendingCapture.None
    private var debugPcmBuffer: BoundedPcmBuffer? = null

    /**
     * Exposes immutable, lifecycle-friendly UI state to the activity and Compose screen.
     *
     * @return hot state that always contains the latest permission, capture, playback, and
     * transcript snapshot.
     *
     * Collection may occur on any coroutine context and can be cancelled without changing stored
     * state. Reading this property performs no I/O and throws no project-specific failure.
     */
    val uiState: StateFlow<SttUiState> = mutableUiState.asStateFlow()

    /**
     * Handles a production push-to-talk Start request, requesting permission or starting capture.
     *
     * @return This function has no return value.
     *
     * The method is synchronous until [startProductionCapture] launches lifecycle-owned work. It is
     * intended for the main thread. Permanent denial becomes recoverable UI state; granted access
     * starts real microphone collection and cancels/joins any prior session before reading.
     */
    fun onStartRequested() {
        requestCapture(PendingCapture.Production)
    }

    /**
     * Stops the production microphone collector and transitions to Completed after release.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, this method immediately exposes `Stopping`, then launches a
     * [viewModelScope] child that cancels and joins the prior capture. Cancellation cleanup failures
     * remain owned by the recorder; normal cancellation is not shown as an error.
     */
    fun onStopRequested() {
        val state = mutableUiState.value
        if (state.isDebugRecording || state.isDebugPlaybackActive) return
        if (
            state.status != SttStatus.Listening &&
                state.status != SttStatus.Connecting &&
                state.status != SttStatus.Stopping
        ) {
            return
        }

        mutableUiState.update {
            it.copy(status = SttStatus.Stopping, partialTranscript = "", errorMessage = null)
        }
        replaceSession {
            mutableUiState.update {
                it.copy(status = SttStatus.Completed, partialTranscript = "")
            }
        }
    }

    /**
     * Starts the debug record-then-playback flow or stops its recording and begins playback.
     *
     * @return This function has no return value.
     *
     * The event is intended for the main thread. A first press follows the same permission path and
     * [AudioCaptureSource] as production capture. A second press cancels and joins capture before
     * [PcmAudioPlayer.play], enforcing the no-recording-during-playback echo rule. Failures become
     * recoverable errors and lifecycle cancellation releases both resources.
     */
    fun onDebugRecordAndPlaybackRequested() {
        val state = mutableUiState.value
        when {
            state.isDebugPlaybackActive -> Unit
            state.isDebugRecording -> stopDebugCaptureAndPlay()
            state.status == SttStatus.Listening || state.status == SttStatus.Connecting -> Unit
            else -> requestCapture(PendingCapture.DebugLoopback)
        }
    }

    /**
     * Reduces Android's completed microphone permission request and continues the pending action.
     *
     * @param isGranted whether Android granted `RECORD_AUDIO`.
     * @param isPermanentlyDenied whether Android will suppress future permission dialogs.
     * @return This function has no return value.
     *
     * The method is synchronous and intended for the main thread. A grant launches the exact action
     * that requested permission; denial remains visible/recoverable. It throws no project-specific
     * failure and owns no independently cancellable work.
     */
    fun onMicrophonePermissionResult(
        isGranted: Boolean,
        isPermanentlyDenied: Boolean,
    ) {
        val actionToStart = pendingCapture
        pendingCapture = PendingCapture.None

        when {
            isGranted -> {
                mutableUiState.update {
                    it.copy(
                        status = SttStatus.Idle,
                        errorMessage = null,
                        microphonePermission = MicrophonePermissionStatus.Granted,
                    )
                }
                when (actionToStart) {
                    PendingCapture.Production -> startProductionCapture()
                    PendingCapture.DebugLoopback -> startDebugCapture()
                    PendingCapture.None -> Unit
                }
            }

            isPermanentlyDenied ->
                mutableUiState.update {
                    it.copy(
                        status = SttStatus.Error,
                        errorMessage = PERMANENT_PERMISSION_ERROR,
                        microphonePermission = MicrophonePermissionStatus.PermanentlyDenied,
                    )
                }

            else ->
                mutableUiState.update {
                    it.copy(
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
     * The synchronous main-thread method launches no new session. A newly granted permission clears
     * permission errors without auto-starting. Revocation cancels active work and resets debug state;
     * recorder/player `finally` blocks release resources asynchronously through [viewModelScope].
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

                current.microphonePermission == MicrophonePermissionStatus.Granted -> {
                    pendingCapture = PendingCapture.None
                    sessionJob?.cancel()
                    debugPcmBuffer = null
                    current.copy(
                        status = SttStatus.Idle,
                        errorMessage = null,
                        microphonePermission = MicrophonePermissionStatus.NotRequested,
                        isDebugRecording = false,
                        isDebugPlaybackActive = false,
                        debugCapturedBytes = 0,
                        debugBufferLimitReached = false,
                    )
                }

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
     * behavior. Blank text is ignored. It performs no provider/network work and does not alter the
     * debug capture/playback flags.
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
     * The synchronous main-thread method cancels any stale session and returns to Idle. It does not
     * retry device work directly; normal lifecycle cancellation is not surfaced as another error.
     */
    fun onRetryRequested() {
        pendingCapture = PendingCapture.None
        sessionJob?.cancel()
        debugPcmBuffer = null
        mutableUiState.update {
            it.copy(
                status = SttStatus.Idle,
                errorMessage = null,
                isDebugRecording = false,
                isDebugPlaybackActive = false,
                debugCapturedBytes = 0,
                debugBufferLimitReached = false,
            )
        }
    }

    /**
     * Converts failure to launch an external recovery action into a visible, retryable error.
     *
     * @param message non-secret description of the platform failure.
     * @return This function has no return value.
     *
     * The method is synchronous, intended for the main thread, and has no coroutine or cancellation
     * behavior. Blank messages are replaced so the UI never silently hangs.
     */
    fun onExternalActionFailed(message: String) {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                errorMessage = message.ifBlank { GENERIC_RECOVERY_ERROR },
            )
        }
    }

    /**
     * Applies permission policy for either production or debug capture.
     *
     * @param requestedCapture action to continue if permission is already or becomes granted.
     * @return This function has no return value.
     *
     * Called synchronously on the main thread. It either launches lifecycle-owned capture, exposes
     * a permission request state, or exposes permanent-denial recovery; it throws no failure.
     */
    private fun requestCapture(requestedCapture: PendingCapture) {
        if (mutableUiState.value.isDebugPlaybackActive) return
        when (mutableUiState.value.microphonePermission) {
            MicrophonePermissionStatus.Granted -> {
                pendingCapture = PendingCapture.None
                when (requestedCapture) {
                    PendingCapture.Production -> startProductionCapture()
                    PendingCapture.DebugLoopback -> startDebugCapture()
                    PendingCapture.None -> Unit
                }
            }

            MicrophonePermissionStatus.PermanentlyDenied ->
                mutableUiState.update {
                    it.copy(status = SttStatus.Error, errorMessage = PERMANENT_PERMISSION_ERROR)
                }

            MicrophonePermissionStatus.NotRequested,
            MicrophonePermissionStatus.Denied,
            -> {
                pendingCapture = requestedCapture
                mutableUiState.update {
                    it.copy(status = SttStatus.RequestingPermission, errorMessage = null)
                }
            }
        }
    }

    /**
     * Starts real production capture and discards PCM only because cloud STT is out of Phase 3.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, it exposes Connecting and replaces the lifecycle-owned session.
     * Once the prior session is joined it enters Listening and collects on the source's dispatcher.
     * Capture failure becomes Error; replacement/lifecycle cancellation is rethrown and hidden.
     */
    private fun startProductionCapture() {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Connecting,
                partialTranscript = "",
                errorMessage = null,
                isDebugRecording = false,
                isDebugPlaybackActive = false,
            )
        }
        replaceSession {
            mutableUiState.update { it.copy(status = SttStatus.Listening) }
            try {
                // Phase 3 intentionally consumes without transcription; MicRecorder logs count and
                // sizes, and Phase 5 will replace this terminal collector with SttClient.stream.
                audioCaptureSource.capture().collect()
                mutableUiState.update { it.copy(status = SttStatus.Completed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showSessionError(error, CAPTURE_ERROR_FALLBACK)
            }
        }
    }

    /**
     * Starts bounded debug recording from the exact production AudioCaptureSource Flow.
     *
     * @return This function has no return value.
     *
     * The main-thread event creates a ten-second accumulator and replaces the active session. Flow
     * collection occurs on the source dispatcher. Reaching the cap completes upstream collection so
     * AudioRecord's `finally` releases the mic before playback. Device errors become recoverable UI
     * state; coroutine cancellation is propagated without an error.
     */
    private fun startDebugCapture() {
        val buffer = BoundedPcmBuffer(AudioConfig.DEBUG_PLAYBACK_MAX_BYTES)
        debugPcmBuffer = buffer
        mutableUiState.update {
            it.copy(
                status = SttStatus.Connecting,
                errorMessage = null,
                isDebugRecording = false,
                isDebugPlaybackActive = false,
                debugCapturedBytes = 0,
                debugBufferLimitReached = false,
            )
        }
        replaceSession {
            mutableUiState.update {
                it.copy(status = SttStatus.Listening, isDebugRecording = true)
            }
            try {
                audioCaptureSource
                    .capture()
                    .takeWhile { chunk ->
                        val limitReached = buffer.append(chunk)
                        mutableUiState.update {
                            it.copy(
                                debugCapturedBytes = buffer.sizeBytes,
                                debugBufferLimitReached = buffer.isLimitReached,
                            )
                        }
                        if (limitReached) {
                            Log.w(TAG, "Debug PCM cap reached at ${buffer.sizeBytes} bytes")
                        }
                        // takeWhile cancels upstream and waits for recorder cleanup before this
                        // coroutine proceeds into playback, which enforces the echo rule.
                        !limitReached
                    }.collect()

                mutableUiState.update {
                    it.copy(status = SttStatus.Stopping, isDebugRecording = false)
                }
                playRetainedDebugAudio()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showSessionError(error, CAPTURE_ERROR_FALLBACK)
            }
        }
    }

    /**
     * Cancels and joins bounded debug capture, then plays only the bytes retained before Stop.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, it exposes Stopping immediately and replaces the current session.
     * [replaceSession] guarantees recorder cleanup completes before [playRetainedDebugAudio]. Normal
     * cancellation is hidden; playback failure becomes a recoverable UI error.
     */
    private fun stopDebugCaptureAndPlay() {
        mutableUiState.update {
            it.copy(status = SttStatus.Stopping, isDebugRecording = false)
        }
        replaceSession {
            try {
                playRetainedDebugAudio()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showSessionError(error, PLAYBACK_ERROR_FALLBACK)
            }
        }
    }

    /**
     * Copies the retained bounded PCM and awaits raw AudioTrack playback to its final frame.
     *
     * @return after playback completes and the UI returns to Completed.
     * @throws IllegalStateException when capture produced no bytes.
     * @throws Throwable when the injected [PcmAudioPlayer] fails.
     *
     * This suspend function runs in the active [viewModelScope] session. It is called only after
     * prior capture was joined or naturally completed; cancellation propagates to the player, whose
     * contract requires stopping and releasing AudioTrack before returning.
     */
    private suspend fun playRetainedDebugAudio() {
        val pcm = debugPcmBuffer?.toByteArray() ?: ByteArray(0)
        debugPcmBuffer = null
        if (pcm.isEmpty()) {
            throw IllegalStateException("No microphone audio was captured for playback.")
        }

        mutableUiState.update {
            it.copy(
                status = SttStatus.Completed,
                isDebugRecording = false,
                isDebugPlaybackActive = true,
            )
        }
        pcmAudioPlayer.play(pcm)
        mutableUiState.update {
            it.copy(
                status = SttStatus.Completed,
                isDebugPlaybackActive = false,
            )
        }
    }

    /**
     * Replaces the lifecycle-owned session after cancelling and joining its predecessor.
     *
     * @param block new session body executed only after predecessor cleanup completes.
     * @return This function has no return value.
     *
     * Called from main-thread events, it launches in [viewModelScope]. Rapid replacements form an
     * ordered cancellation chain, preventing concurrent microphone/playback ownership. Cancellation
     * of a replacement prevents its body from starting and is never converted to UI error here.
     */
    private fun replaceSession(block: suspend () -> Unit) {
        val previousSession = sessionJob
        sessionJob =
            viewModelScope.launch {
                previousSession?.cancelAndJoin()
                block()
            }
    }

    /**
     * Converts an audio component failure into a stable recoverable UI state.
     *
     * @param error failure thrown by capture or playback.
     * @param fallback non-secret message used when the exception message is blank.
     * @return This function has no return value.
     *
     * Called from the active main-confined ViewModel coroutine after component cleanup. It performs
     * no suspension or cancellation. The function never logs or exposes raw PCM or credentials.
     */
    private fun showSessionError(error: Throwable, fallback: String) {
        Log.e(TAG, fallback, error)
        debugPcmBuffer = null
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                errorMessage = error.message?.takeIf(String::isNotBlank) ?: fallback,
                isDebugRecording = false,
                isDebugPlaybackActive = false,
            )
        }
    }

    /**
     * Appends one committed segment using a newline boundary suitable for the transcript log.
     *
     * @param existing previously committed transcript text.
     * @param segment newly committed non-blank segment.
     * @return [segment] alone or both values separated by one newline.
     *
     * This pure function performs no I/O, is safe on any dispatcher, has no cancellation behavior,
     * and throws no project-specific failure.
     */
    private fun appendFinalTranscript(existing: String, segment: String): String =
        if (existing.isBlank()) segment else "$existing\n$segment"

    /**
     * Identifies which permission-gated capture action resumes after Android's dialog.
     *
     * Values are main-thread-confined ViewModel bookkeeping. They perform no work, have no failure
     * or cancellation behavior, and are never exposed outside this ViewModel.
     */
    private enum class PendingCapture {
        /** No permission-gated action is waiting. */
        None,

        /** Normal push-to-talk capture that Phase 5 will stream to STT. */
        Production,

        /** Bounded debug capture followed by raw PCM playback. */
        DebugLoopback,
    }

    /**
     * Creates [SttViewModel] with Android-backed audio components while preserving test injection.
     *
     * @param audioCaptureSource cold microphone source shared by both capture paths.
     * @param pcmAudioPlayer record-then-playback verifier.
     *
     * Factory creation is synchronous on Android's main thread. It performs no audio I/O and owns no
     * cancellable resource; unsupported ViewModel types fail with [IllegalArgumentException].
     */
    class Factory(
        private val audioCaptureSource: AudioCaptureSource,
        private val pcmAudioPlayer: PcmAudioPlayer,
    ) : ViewModelProvider.Factory {

        /**
         * Constructs the requested [SttViewModel].
         *
         * @param modelClass ViewModel class requested by Android's provider.
         * @return a new [SttViewModel] cast to the requested ViewModel type.
         * @throws IllegalArgumentException when [modelClass] is not [SttViewModel].
         *
         * Creation is synchronous on the main thread, performs no I/O, and has no cancellation
         * behavior. Audio resources remain cold until the ViewModel starts a session.
         */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SttViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return SttViewModel(audioCaptureSource, pcmAudioPlayer) as T
        }
    }

    private companion object {
        const val TAG = "SttViewModel"
        const val RETRYABLE_PERMISSION_ERROR =
            "Microphone permission is required for speech input. Retry to ask again."
        const val PERMANENT_PERMISSION_ERROR =
            "Microphone permission is permanently denied. Open app settings to enable it."
        const val GENERIC_RECOVERY_ERROR = "The recovery action could not be opened. Please retry."
        const val CAPTURE_ERROR_FALLBACK = "Microphone capture failed. Please retry."
        const val PLAYBACK_ERROR_FALLBACK = "Debug audio playback failed. Please retry."
    }
}

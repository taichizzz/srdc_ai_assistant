package com.foxconn.seeandsay.ui

/**
 * Describes the user-visible phase of one M1.1 push-to-talk session.
 *
 * The values are pure state with no dispatcher or cancellation behavior. `Connecting` and
 * `Stopping` are declared now so later audio/cloud phases can expose those asynchronous boundaries
 * without changing the UI contract.
 */
enum class SttStatus {
    /** No permission request or speech session is active. */
    Idle,

    /** Android's microphone permission request is in progress. */
    RequestingPermission,

    /** A future STT implementation is establishing its cloud stream. */
    Connecting,

    /** A production or debug microphone capture Flow is actively being collected. */
    Listening,

    /** A future STT implementation is closing audio and awaiting final results. */
    Stopping,

    /** The user stopped or a final transcript was committed. */
    Completed,

    /** A recoverable permission, platform, or future speech failure is visible. */
    Error,
}

/**
 * Describes the microphone permission state independently of the speech session state.
 *
 * Keeping this state explicit lets the UI distinguish a retryable denial from a denial that can be
 * recovered only through Android Settings. The values perform no work, fail in no way, and carry no
 * threading or cancellation requirements.
 */
enum class MicrophonePermissionStatus {
    /** Permission has not been requested during the current application flow. */
    NotRequested,

    /** Android currently grants microphone recording. */
    Granted,

    /** Permission was denied, but Android may show the request again. */
    Denied,

    /** Android will not show another request; the user must use application Settings. */
    PermanentlyDenied,
}

/**
 * Holds the single immutable source of truth for the M1.1 debug screen.
 *
 * @property status current permission/session state.
 * @property partialTranscript replaceable interim recognition text.
 * @property finalTranscript accumulated committed recognition text.
 * @property errorMessage recoverable user-facing failure, or `null` when no failure is active.
 * @property microphonePermission current platform permission outcome.
 * @property isDebugRecording whether the bounded record-then-playback capture is active.
 * @property isDebugPlaybackActive whether raw debug PCM is currently playing.
 * @property debugCapturedBytes number of bytes retained for the current/last debug recording.
 * @property debugBufferLimitReached whether the ten-second memory cap stopped debug recording.
 *
 * The value performs no work, throws no project-specific failure, and is safe to publish through a
 * StateFlow across coroutine contexts. It owns no cancellable resource.
 */
data class SttUiState(
    val status: SttStatus = SttStatus.Idle,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val errorMessage: String? = null,
    val microphonePermission: MicrophonePermissionStatus =
        MicrophonePermissionStatus.NotRequested,
    val isDebugRecording: Boolean = false,
    val isDebugPlaybackActive: Boolean = false,
    val debugCapturedBytes: Int = 0,
    val debugBufferLimitReached: Boolean = false,
)

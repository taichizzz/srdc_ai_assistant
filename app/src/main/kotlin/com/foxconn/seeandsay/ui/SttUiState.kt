package com.foxconn.seeandsay.ui

/**
 * Describes the user-visible phase of one M1.1 push-to-talk session.
 *
 * The values are pure state with no dispatcher or cancellation behavior. `Connecting` and
 * `Stopping` expose the asynchronous cloud-stream boundaries without leaking provider types into
 * UI state.
 */
enum class SttStatus {
    /** No permission request or speech session is active. */
    Idle,

    /** Android's microphone permission request is in progress. */
    RequestingPermission,

    /** The production or debug STT path is establishing its microphone/cloud stream. */
    Connecting,

    /** A production or debug microphone capture Flow is actively being collected. */
    Listening,

    /** Microphone input is closed and the active cloud stream is draining final results. */
    Stopping,

    /** The local ReplyEngine is selecting one response for the completed utterance. */
    Replying,

    /** The integrated M1.3 TtsClient is synthesizing or playing the generated response. */
    Speaking,

    /** The user stopped or a final transcript was committed. */
    Completed,

    /** A recoverable permission, platform, microphone, or cloud speech failure is visible. */
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
 * Describes whether the local installation has supplied cloud speech authentication material.
 *
 * This state reports API-key or OAuth-token presence only; `Configured` does not claim that Google
 * has accepted the selected credential because the check performs no network request. Values are
 * immutable, perform no work, fail in no way, and have no threading or cancellation behavior.
 */
enum class CloudConfigurationStatus {
    /** The user has not asked the development provider to inspect local configuration. */
    NotChecked,

    /** A non-blank API key or short-lived OAuth token is present; acceptance is unverified. */
    Configured,

    /** Neither a non-blank API key nor a short-lived OAuth token is present in this build. */
    NotConfigured,
}

/**
 * Holds the single immutable source of truth for the M1.1 debug screen.
 *
 * @property status current permission/session state.
 * @property partialTranscript replaceable interim recognition text.
 * @property finalTranscript accumulated committed recognition text.
 * @property lastReplyText generated assistant response for the current or latest completed loop.
 * @property voiceLoopEnabled whether completed production/typed transcripts automatically reply.
 * @property selectedVoiceLoopTtsModel cloud model used by the next automatic main-pipeline reply.
 * @property errorMessage recoverable user-facing failure, or `null` when no failure is active.
 * @property microphonePermission current platform permission outcome.
 * @property isDebugRecording whether the bounded record-then-playback capture is active.
 * @property isDebugPlaybackActive whether raw debug PCM is currently playing.
 * @property debugCapturedBytes number of bytes retained for the current/last debug recording.
 * @property debugBufferLimitReached whether the ten-second memory cap stopped debug recording.
 * @property cloudConfiguration local API-key/token presence without exposing credential content.
 * @property isCloudConfigurationCheckRunning whether the suspend provider check is active.
 * @property selectedDebugSttEngine engine/model selected for the DEBUG cloud test only.
 * @property debugSttMetrics latest running/completed DEBUG evaluation measurements.
 * @property isCloudSttSmokeTestRunning whether DEBUG mic audio is routed to the selected SttClient.
 * @property cloudSmokePartialTranscript latest raw interim text from the DEBUG cloud stream.
 * @property cloudSmokeFinalTranscript committed raw text from the DEBUG cloud stream.
 * @property cloudSmokeFinalConfidence final-only confidence from the latest DEBUG result.
 *
 * The value performs no work, throws no project-specific failure, and is safe to publish through a
 * StateFlow across coroutine contexts. It owns no cancellable resource.
 */
data class SttUiState(
    val status: SttStatus = SttStatus.Idle,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val lastReplyText: String = "",
    val voiceLoopEnabled: Boolean = true,
    val selectedVoiceLoopTtsModel: TtsModelOption = TtsModelOption.WaveNet,
    val errorMessage: String? = null,
    val microphonePermission: MicrophonePermissionStatus =
        MicrophonePermissionStatus.NotRequested,
    val isDebugRecording: Boolean = false,
    val isDebugPlaybackActive: Boolean = false,
    val debugCapturedBytes: Int = 0,
    val debugBufferLimitReached: Boolean = false,
    val cloudConfiguration: CloudConfigurationStatus = CloudConfigurationStatus.NotChecked,
    val isCloudConfigurationCheckRunning: Boolean = false,
    val selectedDebugSttEngine: DebugSttEngine = DebugSttEngine.V1LatestShort,
    val debugSttMetrics: DebugSttMetrics? = null,
    val isCloudSttSmokeTestRunning: Boolean = false,
    val cloudSmokePartialTranscript: String = "",
    val cloudSmokeFinalTranscript: String = "",
    val cloudSmokeFinalConfidence: Float? = null,
)

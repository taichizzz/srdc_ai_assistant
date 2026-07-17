package com.foxconn.seeandsay.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxconn.seeandsay.BuildConfig
import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.pipeline.VoicePipeline
import com.foxconn.seeandsay.speech.AudioCaptureSource
import com.foxconn.seeandsay.speech.AudioConfig
import com.foxconn.seeandsay.speech.BoundedPcmBuffer
import com.foxconn.seeandsay.speech.CloudSttException
import com.foxconn.seeandsay.speech.CloudSttFailureReason
import com.foxconn.seeandsay.speech.PcmAudioPlayer
import com.foxconn.seeandsay.speech.SttClient
import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns M1.1 microphone sessions and reduces permission/transcript events into immutable UI state.
 *
 * @param audioCaptureSource cold provider-neutral PCM source shared by production capture and debug
 * loopback.
 * @param pcmAudioPlayer raw PCM output used only after debug capture has fully stopped.
 * @param accessTokenProvider provider-neutral, suspendable source for cloud bearer tokens.
 * @param apiKeyProvider provider-neutral, suspendable source for an optional plain API key.
 * @param productionSttClient provider-neutral recognizer used by the normal Start/Stop flow.
 * @param debugV1SttClient existing V1 baseline used only by the DEBUG comparison harness.
 * @param debugChirp2SttClient V2 Chirp 2 client used only by the DEBUG comparison harness.
 * @param debugChirp3SttClient V2 Chirp 3 client used only by the DEBUG comparison harness.
 * @param voicePipeline composition boundary generating and speaking one post-STT response.
 * @param initialVoiceLoopEnabled initial automatic-reply toggle; production defaults to enabled.
 * @param monotonicClock clock used only for DEBUG latency metrics; tests inject virtual time.
 *
 * Public event methods are intended for Android's main thread. Capture and playback are children of
 * [viewModelScope]; replacing or clearing a session cancels and joins its predecessor so microphone
 * and playback resources are released in order. Device failures become recoverable UI errors, while
 * normal coroutine cancellation is never surfaced as an error.
 */
class SttViewModel(
    private val audioCaptureSource: AudioCaptureSource,
    private val pcmAudioPlayer: PcmAudioPlayer,
    private val accessTokenProvider: AccessTokenProvider,
    private val apiKeyProvider: ApiKeyProvider,
    private val productionSttClient: SttClient,
    private val debugV1SttClient: SttClient,
    private val debugChirp2SttClient: SttClient,
    private val debugChirp3SttClient: SttClient,
    private val voicePipeline: VoicePipeline,
    initialVoiceLoopEnabled: Boolean = true,
    private val monotonicClock: MonotonicClock = MonotonicClock.SYSTEM,
) : ViewModel() {

    private val mutableUiState =
        MutableStateFlow(SttUiState(voiceLoopEnabled = initialVoiceLoopEnabled))
    private var sessionJob: Job? = null
    private var pendingCapture = PendingCapture.None
    private var debugPcmBuffer: BoundedPcmBuffer? = null
    private var cloudConfigurationJob: Job? = null
    private var productionCaptureJob: Job? = null
    private var productionStopRequested: Boolean = false

    /** Main-thread-confined monotonic identity used to invalidate stale production watchdogs. */
    private var productionSessionId: Long = 0L

    /** Lifecycle-owned first-response or final-drain delay; cancellation performs no blocking work. */
    private var productionTimeoutJob: Job? = null

    /** Main-thread-confined owner identity for [productionTimeoutJob], or `null` when disarmed. */
    private var productionTimeoutSessionId: Long? = null

    /** Structured microphone child feeding the active DEBUG comparison RPC. */
    private var debugCloudCaptureJob: Job? = null

    /** Main-confined timing accumulator for the active DEBUG comparison run. */
    private var debugSttMetricsTracker: DebugSttMetricsTracker? = null

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
     * Enables or disables automatic reply/TTS for subsequent completed transcript sessions.
     *
     * @param enabled `true` to run final/typed transcripts through VoicePipeline; `false` to retain
     * pure STT/metrics behavior without assistant speech.
     * @return This function has no return value.
     *
     * The synchronous main-thread event performs no I/O and owns no coroutine. Changes are ignored
     * while reply generation or speech is active so an utterance cannot change policy mid-flight;
     * disabling never cancels an already-started TTS request.
     */
    fun onVoiceLoopEnabledChanged(enabled: Boolean) {
        val status = mutableUiState.value.status
        if (status == SttStatus.Replying || status == SttStatus.Speaking) return
        mutableUiState.update { it.copy(voiceLoopEnabled = enabled) }
    }

    /**
     * Stops production microphone input while allowing the cloud stream to drain its final result.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, this method immediately exposes `Stopping` and cancels only the
     * structured microphone child. Its `finally` releases AudioRecord before closing the bounded
     * audio channel; CloudSttClient then half-closes and may emit a final result before the session
     * becomes Completed. A five-second watchdog cancels a stalled drain and exposes recoverable
     * Error. Repeated Stop is harmless, and normal cancellation is never an error.
     */
    fun onStopRequested() {
        val state = mutableUiState.value
        if (
            state.isDebugRecording ||
                state.isDebugPlaybackActive ||
                state.isCloudSttSmokeTestRunning
        ) {
            return
        }
        if (
            state.status != SttStatus.Listening &&
                state.status != SttStatus.Connecting &&
                state.status != SttStatus.Stopping
        ) {
            return
        }

        mutableUiState.update {
            it.copy(status = SttStatus.Stopping, errorMessage = null)
        }
        if (!productionStopRequested) {
            productionStopRequested = true
            armProductionTimeout(
                sessionId = productionSessionId,
                timeoutMillis = FINAL_DRAIN_TIMEOUT_MS,
            )
        }
        productionCaptureJob?.cancel()
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
            state.isCloudSttSmokeTestRunning -> Unit
            state.status == SttStatus.Listening || state.status == SttStatus.Connecting -> Unit
            else -> requestCapture(PendingCapture.DebugLoopback)
        }
    }

    /**
     * Starts or stops the DEBUG-only selected-engine microphone round-trip evaluation session.
     *
     * @return This function has no return value.
     *
     * The event is intended for the main thread and follows the existing first-use mic permission
     * path. Starting launches one lifecycle-owned selected [SttClient]; stopping ends microphone
     * input and lets that stream half-close/drain its final response. Results/metrics update only
     * dedicated DEBUG fields and never call production [onSttResult]. Failures remain recoverable.
     */
    fun onCloudSttSmokeTestRequested() {
        val state = mutableUiState.value
        when {
            state.isCloudSttSmokeTestRunning -> stopCloudSttSmokeTest()
            state.isDebugRecording || state.isDebugPlaybackActive -> Unit
            state.status == SttStatus.Listening || state.status == SttStatus.Connecting -> Unit
            else -> requestCapture(PendingCapture.CloudSmokeTest)
        }
    }

    /**
     * Selects the API/model used by the next DEBUG cloud comparison run.
     *
     * @param engine V1 baseline, V2 Chirp 2, or V2 Chirp 3 selection.
     * @return This function has no return value.
     *
     * The synchronous main-thread event is ignored while any cloud smoke run is active so a single
     * run cannot mix engines or corrupt its metrics label. It performs no audio/network work, owns
     * no coroutine, throws no project-specific failure, and never changes production's V1 client.
     */
    fun onDebugSttEngineSelected(engine: DebugSttEngine) {
        val state = mutableUiState.value
        if (
            !BuildConfig.DEBUG ||
                state.isCloudSttSmokeTestRunning ||
                (state.status == SttStatus.Stopping &&
                    state.debugSttMetrics?.outcome == DebugSttOutcome.Running)
        ) {
            return
        }
        mutableUiState.update {
            it.copy(
                selectedDebugSttEngine = engine,
                debugSttMetrics = null,
                cloudSmokePartialTranscript = "",
                cloudSmokeFinalTranscript = "",
                cloudSmokeFinalConfidence = null,
            )
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
                    PendingCapture.CloudSmokeTest -> startCloudSttSmokeTest()
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
     * permission errors without auto-starting. Revocation invalidates watchdogs, cancels active work,
     * and resets debug state; recorder/player `finally` blocks release resources asynchronously
     * through [viewModelScope].
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
                    productionSessionId += 1L
                    cancelProductionTimeout()
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
                        isCloudSttSmokeTestRunning = false,
                    )
                }

                else -> current
            }
        }
    }

    /**
     * Submits emulator/debug text through the same final-result reducer used by production STT.
     *
     * @param transcript manually entered transcript; surrounding whitespace is ignored.
     * @return This function has no return value.
     *
     * The reducer update is synchronous and permission-independent. When automatic reply is enabled,
     * [replaceSession] launches the same lifecycle-owned post-transcript pipeline used after cloud
     * STT; blank input is ignored rather than treated as a failure. Cancellation stops TTS and is not
     * surfaced as an error.
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
        replaceSession {
            runVoiceLoop(normalizedTranscript)
        }
    }

    /**
     * Reduces one provider-neutral interim or final recognition result into UI state.
     *
     * @param result interim text that replaces the partial line, or a final segment to append once.
     * @return This function has no return value.
     *
     * The reducer is synchronous, intended for main-thread event delivery, and has no cancellation
     * behavior. Blank text is ignored. While a tracked production capture/drain is active, a final
     * result preserves Listening/Stopping so the push-to-talk Stop control remains usable;
     * permission-independent typed input still moves an inactive session to Completed. It performs
     * no provider/network work and does not alter debug capture/playback flags.
     */
    fun onSttResult(result: SttResult) {
        val transcript = result.transcript.trim()
        if (transcript.isEmpty()) return
        val isProductionSessionActive =
            productionCaptureJob?.isActive == true || productionStopRequested

        mutableUiState.update { current ->
            if (result.isFinal) {
                current.copy(
                    status =
                        when {
                            isProductionSessionActive &&
                                current.status == SttStatus.Connecting -> SttStatus.Connecting
                            isProductionSessionActive &&
                                current.status == SttStatus.Listening -> SttStatus.Listening
                            isProductionSessionActive &&
                                current.status == SttStatus.Stopping -> SttStatus.Stopping
                            else -> SttStatus.Completed
                        },
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
     * Checks whether either local cloud-speech credential provider has usable configuration.
     *
     * @return This function has no return value.
     *
     * The main-thread event launches one [viewModelScope] child and never performs a network call.
     * API-key presence takes precedence, matching CloudSttClient; otherwise the token provider is
     * consulted. A typed missing-token failure means neither credential exists and maps to visible
     * recoverable UI state. Other provider failures use a fixed non-secret message. Repeated requests
     * while running are ignored, and ViewModel or Retry cancellation propagates cooperatively.
     */
    fun onCloudConfigurationCheckRequested() {
        val current = mutableUiState.value
        if (
            current.isCloudConfigurationCheckRunning ||
                current.status == SttStatus.Connecting ||
                current.status == SttStatus.Listening ||
                current.status == SttStatus.Stopping ||
                current.isDebugRecording ||
                current.isDebugPlaybackActive
        ) {
            return
        }

        mutableUiState.update {
            it.copy(
                status = if (it.status == SttStatus.Error) SttStatus.Idle else it.status,
                errorMessage = null,
                isCloudConfigurationCheckRunning = true,
            )
        }
        cloudConfigurationJob =
            viewModelScope.launch {
                try {
                    // Match the client's API-key precedence so the check describes the credential
                    // mode the next RPC will actually use. Values are discarded immediately because
                    // local presence—not credential content or Google acceptance—is the only result.
                    val hasApiKey = apiKeyProvider.currentApiKey()?.isNotBlank() == true
                    if (!hasApiKey && accessTokenProvider.currentToken().isBlank()) {
                        throw CloudSpeechNotConfiguredException()
                    }
                    mutableUiState.update {
                        it.copy(
                            cloudConfiguration = CloudConfigurationStatus.Configured,
                            isCloudConfigurationCheckRunning = false,
                            errorMessage = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CloudSpeechNotConfiguredException) {
                    showCloudNotConfigured()
                } catch (error: Throwable) {
                    showCloudProviderFailure()
                }
            }
    }

    /**
     * Clears a recoverable error while retaining permission information and committed transcripts.
     *
     * @return This function has no return value.
     *
     * The synchronous main-thread method invalidates watchdogs, cancels any stale session, and
     * returns to Idle. It does not retry device work directly; normal lifecycle cancellation is not
     * surfaced as another error.
     */
    fun onRetryRequested() {
        pendingCapture = PendingCapture.None
        productionSessionId += 1L
        cancelProductionTimeout()
        productionStopRequested = false
        productionCaptureJob?.cancel()
        sessionJob?.cancel()
        cloudConfigurationJob?.cancel()
        debugPcmBuffer = null
        mutableUiState.update {
            it.copy(
                status = SttStatus.Idle,
                errorMessage = null,
                isDebugRecording = false,
                isDebugPlaybackActive = false,
                debugCapturedBytes = 0,
                debugBufferLimitReached = false,
                isCloudConfigurationCheckRunning = false,
                isCloudSttSmokeTestRunning = false,
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
        if (
            mutableUiState.value.isDebugPlaybackActive ||
                mutableUiState.value.isCloudSttSmokeTestRunning ||
                mutableUiState.value.status == SttStatus.Replying ||
                mutableUiState.value.status == SttStatus.Speaking
        ) {
            return
        }
        when (mutableUiState.value.microphonePermission) {
            MicrophonePermissionStatus.Granted -> {
                pendingCapture = PendingCapture.None
                when (requestedCapture) {
                    PendingCapture.Production -> startProductionCapture()
                    PendingCapture.DebugLoopback -> startDebugCapture()
                    PendingCapture.CloudSmokeTest -> startCloudSttSmokeTest()
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
     * Starts one production microphone-to-STT stream and reduces its live recognition results.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, it exposes Connecting and replaces the lifecycle-owned session.
     * After the prior session is joined it creates a bounded audio channel, starts capture as a
     * structured child, and collects [productionSttClient] results through [onSttResult]. Stop
     * cancels only capture so channel completion can half-close/drain cloud results. All final
     * segments from that one stream are accumulated, then passed to [runVoiceLoop] exactly once only
     * after capture cancellation/join proves microphone release. A first non-blank transcript
     * disarms the connection watchdog; Stop installs a separate drain bound. Cloud/device/timeout or
     * reply/TTS failures become recoverable Error, while replacement/lifecycle cancellation is
     * rethrown and hidden.
     */
    private fun startProductionCapture() {
        productionSessionId += 1L
        val sessionId = productionSessionId
        cancelProductionTimeout()
        productionStopRequested = false
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
            if (productionStopRequested) {
                cancelProductionTimeout(sessionId)
                mutableUiState.update {
                    it.copy(status = SttStatus.Completed, partialTranscript = "")
                }
                productionStopRequested = false
                return@replaceSession
            }

            val audioChannel = Channel<ByteArray>(capacity = PRODUCTION_AUDIO_BUFFER_CAPACITY)
            var completedSessionTranscript = ""
            try {
                coroutineScope {
                    val captureJob =
                        launch {
                            try {
                                audioCaptureSource.capture().collect { chunk ->
                                    audioChannel.send(chunk)
                                }
                            } finally {
                                // Closing only after MicRecorder's Flow has unwound guarantees the
                                // mic release precedes the cloud half-close and any later playback.
                                audioChannel.close()
                            }
                        }
                    productionCaptureJob = captureJob
                    if (productionStopRequested) captureJob.cancel()

                    mutableUiState.update {
                        it.copy(
                            status =
                                if (productionStopRequested) {
                                    SttStatus.Stopping
                                } else {
                                    SttStatus.Listening
                                },
                        )
                    }
                    armProductionTimeout(
                        sessionId = sessionId,
                        timeoutMillis = FIRST_RESPONSE_TIMEOUT_MS,
                    )
                    var hasReceivedTranscript = false
                    try {
                        productionSttClient
                            .stream(audioChannel.receiveAsFlow())
                            .collect { result ->
                                if (!hasReceivedTranscript && result.transcript.isNotBlank()) {
                                    hasReceivedTranscript = true
                                    // Stop replaces the first-response watchdog with a drain
                                    // watchdog; a late response must not disable that drain bound.
                                    if (!productionStopRequested) {
                                        cancelProductionTimeout(sessionId)
                                    }
                                }
                                onSttResult(result)
                                if (result.isFinal) {
                                    val finalSegment = result.transcript.trim()
                                    if (finalSegment.isNotEmpty()) {
                                        completedSessionTranscript =
                                            appendFinalTranscript(
                                                completedSessionTranscript,
                                                finalSegment,
                                            )
                                    }
                                }
                            }
                    } finally {
                        // Provider completion or failure must never leave the microphone running.
                        captureJob.cancelAndJoin()
                    }
                }
                // Cancel the STT watchdog and clear microphone ownership before invoking TTS. The
                // stream may have completed naturally or after Stop, but both paths have joined the
                // capture child at this point and therefore obey the echo rule.
                cancelProductionTimeout(sessionId)
                productionCaptureJob = null
                productionStopRequested = false
                mutableUiState.update {
                    it.copy(status = SttStatus.Completed, partialTranscript = "")
                }
                if (completedSessionTranscript.isNotBlank()) {
                    runVoiceLoop(completedSessionTranscript)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudSttException) {
                showProductionCloudFailure(error)
            } catch (error: Throwable) {
                showProductionFailure()
            } finally {
                cancelProductionTimeout(sessionId)
                audioChannel.close()
                productionCaptureJob = null
                productionStopRequested = false
            }
        }
    }

    /**
     * Runs the optional exactly-once reply/TTS tail for one completed transcript session.
     *
     * @param transcript newline-joined final segments from one production stream, or one typed final.
     * @return after automatic speech completes, is disabled, or a recoverable failure is published.
     *
     * The function runs in the lifecycle-owned [sessionJob] after production capture has been joined.
     * It exposes Replying, then Speaking through VoicePipeline's pre-speech callback, and returns to
     * Idle only after TtsClient playback completes. Reply or TTS failure becomes recoverable Error;
     * structured cancellation propagates unchanged so client cancellation stops playback without a
     * user-visible failure. It never starts a microphone or automatic re-listen session.
     */
    private suspend fun runVoiceLoop(transcript: String) {
        if (!mutableUiState.value.voiceLoopEnabled) return

        mutableUiState.update {
            it.copy(status = SttStatus.Replying, errorMessage = null)
        }
        try {
            voicePipeline.replyAndSpeak(transcript) { reply ->
                mutableUiState.update {
                    it.copy(
                        status = SttStatus.Speaking,
                        lastReplyText = reply,
                        errorMessage = null,
                    )
                }
            }
            mutableUiState.update {
                it.copy(status = SttStatus.Idle, errorMessage = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            mutableUiState.update {
                it.copy(
                    status = SttStatus.Error,
                    partialTranscript = "",
                    errorMessage = VOICE_LOOP_ERROR,
                )
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
     * Starts one isolated DEBUG microphone-to-selected-engine comparison run.
     *
     * @return This function has no return value.
     *
     * Called on the main thread, it snapshots the selector, replaces the prior session, and creates
     * a bounded audio channel. MicRecorder and the selected [SttClient] remain structured children.
     * Timing is measured around the audio/result Flow without changing [SttResult]. Normal Stop
     * drains finals; cloud/audio failures finish one metrics record and become recoverable UI state.
     */
    private fun startCloudSttSmokeTest() {
        val engine = mutableUiState.value.selectedDebugSttEngine
        val tracker = DebugSttMetricsTracker(engine, monotonicClock)
        debugSttMetricsTracker = tracker
        mutableUiState.update {
            it.copy(
                status = SttStatus.Connecting,
                errorMessage = null,
                isCloudSttSmokeTestRunning = false,
                debugSttMetrics = tracker.snapshot(),
                cloudSmokePartialTranscript = "",
                cloudSmokeFinalTranscript = "",
                cloudSmokeFinalConfidence = null,
            )
        }
        replaceSession {
            val audioChannel = Channel<ByteArray>(capacity = DEBUG_CLOUD_AUDIO_BUFFER_CAPACITY)
            try {
                coroutineScope {
                    val captureJob =
                        launch {
                            try {
                                audioCaptureSource.capture().collect { chunk ->
                                    audioChannel.send(chunk)
                                }
                            } finally {
                                // Metrics Stop and cloud half-close must observe MicRecorder cleanup
                                // before the input Flow completes, preserving the echo rule.
                                audioChannel.close()
                            }
                        }
                    debugCloudCaptureJob = captureJob
                    mutableUiState.update {
                        it.copy(status = SttStatus.Listening, isCloudSttSmokeTestRunning = true)
                    }
                    tracker.onStreamStarted()
                    try {
                        selectedDebugSttClient(engine)
                            .stream(
                                audioChannel
                                    .receiveAsFlow()
                                    .onEach { chunk ->
                                        if (chunk.isNotEmpty()) {
                                            tracker.onAudioChunkSent()
                                            publishDebugSttMetrics(tracker.snapshot())
                                        }
                                    },
                            ).collect { result ->
                                tracker.onResult(result)
                                reduceCloudSmokeResult(result)
                                publishDebugSttMetrics(tracker.snapshot())
                            }
                    } finally {
                        captureJob.cancelAndJoin()
                    }
                }
                val outcome =
                    if (tracker.hasFinalResult()) {
                        DebugSttOutcome.Success
                    } else {
                        DebugSttOutcome.NoFinalResult
                    }
                finishDebugSttMetrics(tracker, outcome)
                mutableUiState.update {
                    it.copy(status = SttStatus.Completed, isCloudSttSmokeTestRunning = false)
                }
            } catch (error: CancellationException) {
                finishDebugSttMetrics(tracker, DebugSttOutcome.Cancelled)
                throw error
            } catch (error: CloudSttException) {
                finishDebugSttMetrics(tracker, mapDebugSttOutcome(error))
                showCloudSmokeFailure(error.message ?: CLOUD_STT_SMOKE_ERROR)
            } catch (error: Throwable) {
                finishDebugSttMetrics(tracker, DebugSttOutcome.Unknown)
                showCloudSmokeFailure(CLOUD_STT_SMOKE_ERROR)
            } finally {
                audioChannel.close()
                debugCloudCaptureJob = null
                if (debugSttMetricsTracker === tracker) debugSttMetricsTracker = null
            }
        }
    }

    /**
     * Stops DEBUG microphone input while allowing the selected cloud stream to return its final.
     *
     * @return This function has no return value.
     *
     * The synchronous main-thread event records end-of-audio, exposes Stopping, and cancels only the
     * structured capture child. Its finally releases MicRecorder and closes the audio channel; the
     * selected client then half-closes/drains within its own bounded final wait. Repeated Stop is
     * harmless, normal cancellation is hidden, and production STT is untouched.
     */
    private fun stopCloudSttSmokeTest() {
        val tracker = debugSttMetricsTracker
        tracker?.onStopRequested()
        tracker?.snapshot()?.let(::publishDebugSttMetrics)
        mutableUiState.update {
            it.copy(status = SttStatus.Stopping, isCloudSttSmokeTestRunning = false)
        }
        debugCloudCaptureJob?.cancel()
    }

    /**
     * Reduces one raw cloud smoke result into fields isolated from production transcript state.
     *
     * @param result provider-neutral interim or final value emitted by CloudSttClient.
     * @return This function has no return value.
     *
     * Called on the main-confined ViewModel session coroutine. It performs no I/O or suspension and
     * has no independent cancellation behavior. DEBUG logging contains recognized text/status only,
     * never token or metadata. Blank provider text is ignored.
     */
    private fun reduceCloudSmokeResult(result: SttResult) {
        val transcript = result.transcript.trim()
        if (transcript.isEmpty()) return
        logCloudSmokeResult(transcript, result.isFinal)
        mutableUiState.update { current ->
            if (result.isFinal) {
                current.copy(
                    cloudSmokePartialTranscript = "",
                    cloudSmokeFinalTranscript =
                        appendFinalTranscript(current.cloudSmokeFinalTranscript, transcript),
                    cloudSmokeFinalConfidence = result.confidence,
                )
            } else {
                current.copy(cloudSmokePartialTranscript = transcript)
            }
        }
    }

    /**
     * Selects the injected provider-neutral client for one immutable DEBUG engine snapshot.
     *
     * @param engine selector value captured before the run begins.
     * @return corresponding V1, Chirp 2, or Chirp 3 [SttClient].
     *
     * This pure main-confined lookup performs no I/O, starts no coroutine, and cannot expose a gRPC
     * type. Exhaustive enum matching prevents silent fallback to production when a new engine is added.
     */
    private fun selectedDebugSttClient(engine: DebugSttEngine): SttClient =
        when (engine) {
            DebugSttEngine.V1LatestShort -> debugV1SttClient
            DebugSttEngine.V2Chirp2 -> debugChirp2SttClient
            DebugSttEngine.V2Chirp3 -> debugChirp3SttClient
        }

    /**
     * Publishes one immutable running/completed metrics snapshot to the DEBUG panel.
     *
     * @param metrics non-secret timing/outcome/transcript snapshot.
     * @return This function has no return value.
     *
     * Called on the main-confined ViewModel event/session path, it performs no I/O or suspension and
     * owns no cancellation resource. StateFlow publication may fail only through normal allocation.
     */
    private fun publishDebugSttMetrics(metrics: DebugSttMetrics) {
        mutableUiState.update { it.copy(debugSttMetrics = metrics) }
    }

    /**
     * Freezes one DEBUG run's outcome, updates the panel, and emits its single CSV log line.
     *
     * @param tracker timing/transcript accumulator owned by the completing run.
     * @param outcome success, cancellation, or recoverable failure category.
     * @return This function has no return value.
     *
     * The synchronous main-confined method performs no network/audio work and starts no coroutine.
     * Logging is debug-only and isolated from state success; no credential, metadata, or PCM is used.
     */
    private fun finishDebugSttMetrics(
        tracker: DebugSttMetricsTracker,
        outcome: DebugSttOutcome,
    ) {
        val metrics = tracker.snapshot(outcome)
        publishDebugSttMetrics(metrics)
        logDebugSttMetrics(metrics)
    }

    /**
     * Converts the existing cloud failure reason into the DEBUG evaluation outcome vocabulary.
     *
     * @param error fixed non-secret provider-neutral cloud failure.
     * @return matching metrics outcome without retaining the exception or provider detail.
     *
     * This pure mapping is safe on any dispatcher, performs no I/O, and has no coroutine or
     * cancellation behavior. Unknown failures remain explicitly Unknown rather than guessed.
     */
    private fun mapDebugSttOutcome(error: CloudSttException): DebugSttOutcome =
        when (error.reason) {
            CloudSttFailureReason.NotConfigured -> DebugSttOutcome.NotConfigured
            CloudSttFailureReason.Unauthenticated -> DebugSttOutcome.Unauthenticated
            CloudSttFailureReason.PermissionDenied -> DebugSttOutcome.PermissionDenied
            CloudSttFailureReason.QuotaExceeded -> DebugSttOutcome.QuotaExceeded
            CloudSttFailureReason.Unavailable -> DebugSttOutcome.Unavailable
            CloudSttFailureReason.Timeout -> DebugSttOutcome.Timeout
            CloudSttFailureReason.Unknown -> DebugSttOutcome.Unknown
        }

    /**
     * Emits exactly one CSV-friendly DEBUG logcat payload for a completed comparison run.
     *
     * @param metrics finalized non-secret evaluation snapshot.
     * @return This function has no return value.
     *
     * The call is synchronous and starts no coroutine. Release builds skip it. Android logging
     * failures are isolated so diagnostics cannot alter recovery; transcript is intentionally
     * included for tester scoring, while credentials, metadata, headers, and raw audio are absent.
     */
    private fun logDebugSttMetrics(metrics: DebugSttMetrics) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.i(DEBUG_STT_METRICS_TAG, metrics.toCsvLine())
        } catch (_: RuntimeException) {
            // Evaluation logging must never turn an otherwise valid run into a UI failure.
        }
    }

    /**
     * Writes one DEBUG recognition value to logcat without affecting stream reduction.
     *
     * @param transcript normalized raw transcript returned by the cloud smoke stream.
     * @param isFinal whether the value is a committed Google result rather than an interim result.
     * @return This function has no return value.
     *
     * The call is synchronous on the ViewModel coroutine and starts no child work. Release builds
     * skip logging. Android logging failures are deliberately isolated because verification logging
     * must never turn a successful recognition stream into a user-visible failure; this also keeps
     * the pure JVM reducer test independent of an Android runtime. No credential is included.
     */
    private fun logCloudSmokeResult(transcript: String, isFinal: Boolean) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.d(TAG, "Cloud STT smoke ${if (isFinal) "final" else "partial"}: $transcript")
        } catch (_: RuntimeException) {
            // Logging is diagnostic only; recognition/state must remain valid if logcat is absent.
        }
    }

    /**
     * Maps smoke-only cloud failure text to the existing recoverable error presentation.
     *
     * @param message fixed non-secret CloudSttClient message or local fallback.
     * @return This function has no return value.
     *
     * Called on the main-confined session coroutine after per-call cleanup. It performs no I/O,
     * logging, or suspension, owns no cancellation, and does not alter production transcripts.
     */
    private fun showCloudSmokeFailure(message: String) {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                errorMessage = message,
                isCloudSttSmokeTestRunning = false,
            )
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
     * Arms a lifecycle-owned lack-of-progress watchdog for one production STT session.
     *
     * @param sessionId monotonic identity of the production session being guarded.
     * @param timeoutMillis positive virtual/real duration allowed before progress is required.
     * @return This function has no return value.
     * @throws IllegalArgumentException when [timeoutMillis] is not positive.
     *
     * Called on the main thread, it replaces any prior watchdog and launches one cancellable
     * [viewModelScope] delay. Expiry cancels and joins the captured session job so MicRecorder and
     * the RPC clean up before a fixed recoverable Error is published. A newer session or Retry
     * invalidates the identity, preventing stale timeout errors. No credential or audio is logged.
     */
    private fun armProductionTimeout(
        sessionId: Long,
        timeoutMillis: Long,
    ) {
        require(timeoutMillis > 0L) { "Production timeout must be positive." }
        cancelProductionTimeout()
        val sessionToCancel = sessionJob
        productionTimeoutSessionId = sessionId
        productionTimeoutJob =
            viewModelScope.launch {
                delay(timeoutMillis)
                if (
                    productionSessionId != sessionId ||
                        productionTimeoutSessionId != sessionId
                ) {
                    return@launch
                }

                // Clear ownership before joining: the cancelled session's finally block must not
                // cancel this watchdog before it can publish the recoverable timeout state.
                productionTimeoutJob = null
                productionTimeoutSessionId = null
                sessionToCancel?.cancelAndJoin()
                if (productionSessionId == sessionId) {
                    showProductionTimeout()
                }
            }
    }

    /**
     * Cancels the current production watchdog when it belongs to the requested session.
     *
     * @param sessionId optional identity guard; `null` cancels any active production watchdog.
     * @return This function has no return value.
     *
     * Called on the main thread, this function performs no I/O or suspension. Delay cancellation is
     * cooperative and lifecycle-owned; a mismatched identity is ignored so an older session's
     * cleanup cannot cancel a newer session's timeout. It throws no project-specific failure.
     */
    private fun cancelProductionTimeout(sessionId: Long? = null) {
        if (sessionId != null && productionTimeoutSessionId != sessionId) return
        productionTimeoutJob?.cancel()
        productionTimeoutJob = null
        productionTimeoutSessionId = null
    }

    /**
     * Publishes the stable recoverable state used by both production timeout boundaries.
     *
     * @return This function has no return value.
     *
     * Called on the main thread only after the timed-out session has been cancelled and joined. It
     * performs no I/O, logging, suspension, or independent cancellation, and uses a fixed message so
     * no credential, RPC detail, or audio can reach UI state.
     */
    private fun showProductionTimeout() {
        productionStopRequested = false
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                partialTranscript = "",
                errorMessage = PRODUCTION_TIMEOUT_ERROR,
            )
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
     * Exposes a known provider-neutral CloudSttClient failure as recoverable production state.
     *
     * @param error cloud failure carrying only a fixed non-secret user message and reason.
     * @return This function has no return value.
     *
     * Called on the main-confined production session after structured capture/stream cleanup. It
     * performs no I/O, logging, or suspension and owns no cancellation. Avoiding provider exception
     * logging guarantees credential metadata cannot enter diagnostics through this path.
     */
    private fun showProductionCloudFailure(error: CloudSttException) {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                partialTranscript = "",
                errorMessage = error.message ?: PRODUCTION_STT_ERROR,
            )
        }
    }

    /**
     * Exposes an unexpected production microphone/STT failure using a fixed recoverable message.
     *
     * @return This function has no return value.
     *
     * Called on the main-confined production session after child cleanup. It performs no I/O,
     * logging, or suspension and owns no cancellation. A fixed message intentionally avoids leaking
     * raw provider, audio, or credential detail from an unexpected exception.
     */
    private fun showProductionFailure() {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                partialTranscript = "",
                errorMessage = PRODUCTION_STT_ERROR,
            )
        }
    }

    /**
     * Maps absence of both supported credential modes to a recoverable, non-secret UI error.
     *
     * @return This function has no return value.
     *
     * Called on the main-confined ViewModel coroutine after the API-key provider returns absent and
     * [AccessTokenProvider.currentToken] reports missing configuration. It performs no I/O or
     * suspension, has no independent cancellation behavior, and uses a fixed existing cloud message
     * so credential values cannot reach logs or state.
     */
    private fun showCloudNotConfigured() {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                errorMessage = CLOUD_NOT_CONFIGURED_ERROR,
                cloudConfiguration = CloudConfigurationStatus.NotConfigured,
                isCloudConfigurationCheckRunning = false,
            )
        }
    }

    /**
     * Maps an unexpected future token-provider failure without exposing its exception message.
     *
     * @return This function has no return value.
     *
     * Called on the main-confined ViewModel coroutine. It performs no I/O, logging, or suspension,
     * and has no independent cancellation behavior. A fixed message prevents a broker exception
     * from accidentally disclosing an Authorization header or token through the UI.
     */
    private fun showCloudProviderFailure() {
        mutableUiState.update {
            it.copy(
                status = SttStatus.Error,
                errorMessage = CLOUD_PROVIDER_ERROR,
                cloudConfiguration = CloudConfigurationStatus.NotConfigured,
                isCloudConfigurationCheckRunning = false,
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

        /** Normal push-to-talk capture streamed through the production SttClient. */
        Production,

        /** Bounded debug capture followed by raw PCM playback. */
        DebugLoopback,

        /** DEBUG-only MicRecorder → selected SttClient comparison run. */
        CloudSmokeTest,
    }

    /**
     * Creates [SttViewModel] with Android-backed audio components while preserving test injection.
     *
     * @param audioCaptureSource cold microphone source shared by both capture paths.
     * @param pcmAudioPlayer record-then-playback verifier.
     * @param accessTokenProvider lazy provider for the debug-only local token presence check and
     * cloud authentication.
     * @param apiKeyProvider lazy provider for the debug-only API-key presence check and cloud
     * authentication.
     * @param productionSttClient provider-neutral recognizer used by production Start/Stop.
     * @param debugV1SttClient existing V1 baseline used only by DEBUG smoke UI.
     * @param debugChirp2SttClient V2 Chirp 2 client used only by DEBUG smoke UI.
     * @param debugChirp3SttClient V2 Chirp 3 client used only by DEBUG smoke UI.
     * @param voicePipeline production M1.3 reply/TTS composition owned by this ViewModel.
     * @param initialVoiceLoopEnabled initial automatic-reply behavior, true in production.
     * @param monotonicClock debug latency clock; defaults to the platform monotonic source.
     *
     * Factory creation is synchronous on Android's main thread. It performs no audio I/O and owns no
     * cancellable resource; unsupported ViewModel types fail with [IllegalArgumentException].
     */
    class Factory(
        private val audioCaptureSource: AudioCaptureSource,
        private val pcmAudioPlayer: PcmAudioPlayer,
        private val accessTokenProvider: AccessTokenProvider,
        private val apiKeyProvider: ApiKeyProvider,
        private val productionSttClient: SttClient,
        private val debugV1SttClient: SttClient,
        private val debugChirp2SttClient: SttClient,
        private val debugChirp3SttClient: SttClient,
        private val voicePipeline: VoicePipeline,
        private val initialVoiceLoopEnabled: Boolean = true,
        private val monotonicClock: MonotonicClock = MonotonicClock.SYSTEM,
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
            return SttViewModel(
                audioCaptureSource,
                pcmAudioPlayer,
                accessTokenProvider,
                apiKeyProvider,
                productionSttClient,
                debugV1SttClient,
                debugChirp2SttClient,
                debugChirp3SttClient,
                voicePipeline,
                initialVoiceLoopEnabled,
                monotonicClock,
            ) as T
        }
    }

    /**
     * Releases production STT, integrated TTS, and all DEBUG cloud clients on disposal.
     *
     * @return This lifecycle callback has no return value.
     *
     * Android invokes this on the main thread after lifecycle-owned coroutines are cancelled. The
     * generic AutoCloseable checks keep UI independent of Google/gRPC types. Identity checks close
     * shared V1 only once and each distinct V2 channel once; close is non-suspending, cancels active
     * RPCs, and throws no expected project-specific failure.
     */
    override fun onCleared() {
        voicePipeline.close()
        (productionSttClient as? AutoCloseable)?.close()
        if (debugV1SttClient !== productionSttClient) {
            (debugV1SttClient as? AutoCloseable)?.close()
        }
        if (
            debugChirp2SttClient !== productionSttClient &&
                debugChirp2SttClient !== debugV1SttClient
        ) {
            (debugChirp2SttClient as? AutoCloseable)?.close()
        }
        if (
            debugChirp3SttClient !== productionSttClient &&
                debugChirp3SttClient !== debugV1SttClient &&
                debugChirp3SttClient !== debugChirp2SttClient
        ) {
            (debugChirp3SttClient as? AutoCloseable)?.close()
        }
        super.onCleared()
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
        const val PRODUCTION_STT_ERROR = "Speech recognition failed. Please retry."
        const val VOICE_LOOP_ERROR = "The assistant reply could not be spoken. Please retry."
        const val CLOUD_PROVIDER_ERROR =
            "Cloud speech authentication is unavailable. Check local configuration and retry."
        const val CLOUD_STT_SMOKE_ERROR = "Cloud STT test failed. Please retry."
        const val CLOUD_NOT_CONFIGURED_ERROR =
            "Cloud speech is not configured. Add an approved credential and retry."
        const val PRODUCTION_AUDIO_BUFFER_CAPACITY = 4
        const val DEBUG_CLOUD_AUDIO_BUFFER_CAPACITY = 4
        const val DEBUG_STT_METRICS_TAG = "SttMetricsCsv"

        /** Maximum wait for the first non-blank production recognition result. */
        const val FIRST_RESPONSE_TIMEOUT_MS = 15_000L

        /** Maximum wait for cloud stream completion after production microphone Stop. */
        const val FINAL_DRAIN_TIMEOUT_MS = 5_000L

        /** Fixed non-secret recovery message shared by both production watchdogs. */
        const val PRODUCTION_TIMEOUT_ERROR = "Speech recognition timed out. Please retry."
    }
}

package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.MainDispatcherRule
import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.FakeAccessTokenProvider
import com.foxconn.seeandsay.speech.AudioCaptureSource
import com.foxconn.seeandsay.speech.CloudSttException
import com.foxconn.seeandsay.speech.CloudSttFailureReason
import com.foxconn.seeandsay.speech.FakeSttClient
import com.foxconn.seeandsay.speech.PcmAudioPlayer
import com.foxconn.seeandsay.speech.SttClient
import com.foxconn.seeandsay.speech.SttResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies production STT reduction, structured Start/Stop lifecycle, recovery, and debug isolation.
 *
 * Tests run against fake audio/STT boundaries with a controlled main dispatcher. They perform no
 * Android, microphone, speaker, or network I/O. Each active fake session is explicitly stopped or
 * cancelled so test completion exercises structured cleanup and never depends on timing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
     * Verifies both missing credential modes become recoverable state without breaking typed input.
     *
     * @return This test has no return value.
     *
     * [runTest] executes the suspend fake provider on the controlled main dispatcher. No credential,
     * filesystem, or network I/O occurs. The test fails if the exception escapes, the message is not
     * recoverable, Retry cannot restore Idle, or typed transcript injection gains a cloud dependency.
     */
    @Test
    fun cloudNotConfiguredMapsToRecoverableStateAndTypedInputStillWorks() =
        runTest(mainDispatcherRule.dispatcher) {
            val tokenProvider =
                FakeAccessTokenProvider(failure = CloudSpeechNotConfiguredException())
            val viewModel = createViewModel(accessTokenProvider = tokenProvider)

            viewModel.onCloudConfigurationCheckRequested()

            assertEquals(1, tokenProvider.requestCount)
            assertEquals(SttStatus.Error, viewModel.uiState.value.status)
            assertEquals(
                CloudConfigurationStatus.NotConfigured,
                viewModel.uiState.value.cloudConfiguration,
            )
            assertTrue(viewModel.uiState.value.errorMessage?.contains("approved credential") == true)

            viewModel.onRetryRequested()
            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)

            viewModel.submitTypedTranscript("顯示")
            assertEquals("顯示", viewModel.uiState.value.finalTranscript)
        }

    /**
     * Verifies local configuration recognizes every supported credential-presence combination.
     *
     * @return This test has no return value.
     *
     * [runTest] executes four in-memory providers on the controlled dispatcher with no credential,
     * filesystem, logging, or network access. It fails if API-key-only, token-only, or both are not
     * Configured, or if absence of both does not become NotConfigured. No child remains active.
     */
    @Test
    fun cloudConfigurationCheckRecognizesApiKeyTokenBothAndNeither() =
        runTest(mainDispatcherRule.dispatcher) {
            val missingToken = CloudSpeechNotConfiguredException()
            val apiKeyOnly =
                createViewModel(
                    apiKeyProvider = ApiKeyProvider { "test-api-key" },
                    accessTokenProvider = FakeAccessTokenProvider(failure = missingToken),
                )
            val tokenOnly =
                createViewModel(
                    apiKeyProvider = ApiKeyProvider { null },
                    accessTokenProvider = FakeAccessTokenProvider(token = "test-token"),
                )
            val both =
                createViewModel(
                    apiKeyProvider = ApiKeyProvider { "test-api-key" },
                    accessTokenProvider = FakeAccessTokenProvider(token = "test-token"),
                )
            val neither =
                createViewModel(
                    apiKeyProvider = ApiKeyProvider { null },
                    accessTokenProvider = FakeAccessTokenProvider(failure = missingToken),
                )

            apiKeyOnly.onCloudConfigurationCheckRequested()
            tokenOnly.onCloudConfigurationCheckRequested()
            both.onCloudConfigurationCheckRequested()
            neither.onCloudConfigurationCheckRequested()

            assertEquals(
                CloudConfigurationStatus.Configured,
                apiKeyOnly.uiState.value.cloudConfiguration,
            )
            assertEquals(
                CloudConfigurationStatus.Configured,
                tokenOnly.uiState.value.cloudConfiguration,
            )
            assertEquals(
                CloudConfigurationStatus.Configured,
                both.uiState.value.cloudConfiguration,
            )
            assertEquals(
                CloudConfigurationStatus.NotConfigured,
                neither.uiState.value.cloudConfiguration,
            )
        }

    /**
     * Verifies the Phase 5 DEBUG cloud path displays raw results without reducing production text.
     *
     * @return This test has no return value.
     *
     * [runTest] collects deterministic fake cloud values on the controlled main dispatcher. It
     * performs no microphone or network I/O and fails if smoke results reach the production
     * transcript reducer or if final-only confidence is lost. The finite fake Flow needs no manual
     * cancellation.
     */
    @Test
    fun cloudSmokeResultsRemainIsolatedFromProductionTranscript() =
        runTest(mainDispatcherRule.dispatcher) {
            val cloudClient =
                FakeSttClient {
                    flow {
                        emit(SttResult(transcript = "你", isFinal = false))
                        emit(SttResult(transcript = "你好", isFinal = true, confidence = 0.87f))
                    }
                }
            val viewModel = createViewModel(debugCloudSttClient = cloudClient)
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onCloudSttSmokeTestRequested()

            val state = viewModel.uiState.value
            assertEquals(SttStatus.Completed, state.status)
            assertFalse(state.isCloudSttSmokeTestRunning)
            assertTrue(state.cloudSmokePartialTranscript.isEmpty())
            assertEquals("你好", state.cloudSmokeFinalTranscript)
            assertEquals(0.87f, state.cloudSmokeFinalConfidence)
            assertTrue(state.partialTranscript.isEmpty())
            assertTrue(state.finalTranscript.isEmpty())
        }

    /**
     * Verifies the DEBUG selector routes Chirp 2 and Chirp 3 without touching production text.
     *
     * @return This test has no return value.
     *
     * [runTest] injects finite provider-neutral fakes for both V2 choices and performs no microphone,
     * Google, gRPC, logging, or real-time work. It fails if selection labels/results/metrics mismatch,
     * if a client is reused incorrectly, or if debug finals enter the production reducer.
     */
    @Test
    fun debugEngineSelectorRoutesChirpClientsAndKeepsProductionIsolated() =
        runTest(mainDispatcherRule.dispatcher) {
            val chirp2 =
                FakeSttClient {
                    flowOf(SttResult("Chirp 2 result", isFinal = true, confidence = 0.8f))
                }
            val chirp3 =
                FakeSttClient {
                    flowOf(SttResult("Chirp 3 result", isFinal = true, confidence = 0.9f))
                }
            val viewModel =
                createViewModel(
                    debugChirp2SttClient = chirp2,
                    debugChirp3SttClient = chirp3,
                )
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onDebugSttEngineSelected(DebugSttEngine.V2Chirp2)
            viewModel.onCloudSttSmokeTestRequested()

            assertEquals("Chirp 2 result", viewModel.uiState.value.cloudSmokeFinalTranscript)
            assertEquals(
                DebugSttEngine.V2Chirp2,
                viewModel.uiState.value.debugSttMetrics?.engine,
            )

            viewModel.onDebugSttEngineSelected(DebugSttEngine.V2Chirp3)
            viewModel.onCloudSttSmokeTestRequested()

            val state = viewModel.uiState.value
            assertEquals("Chirp 3 result", state.cloudSmokeFinalTranscript)
            assertEquals(DebugSttEngine.V2Chirp3, state.debugSttMetrics?.engine)
            assertEquals(DebugSttOutcome.Success, state.debugSttMetrics?.outcome)
            assertTrue(state.finalTranscript.isEmpty())
        }

    /**
     * Verifies production interim replacement and final append/partial-clear reduction.
     *
     * @return This test has no return value.
     *
     * [runTest] uses a gated fake SttClient on the controlled main dispatcher so interim state can
     * be asserted before final delivery. It performs no device/network I/O; completing the gate lets
     * the finite stream finish and structured cleanup cancels the inert microphone child.
     */
    @Test
    fun productionPartialReplacesAndFinalAppendsOnce() =
        runTest(mainDispatcherRule.dispatcher) {
            val allowFinal = CompletableDeferred<Unit>()
            val productionClient =
                FakeSttClient {
                    flow {
                        emit(SttResult(transcript = "你", isFinal = false))
                        emit(SttResult(transcript = "你好嗎", isFinal = false))
                        allowFinal.await()
                        emit(SttResult(transcript = "你好", isFinal = true))
                    }
                }
            val viewModel = createViewModel(productionSttClient = productionClient)
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onStartRequested()
            assertEquals("你好嗎", viewModel.uiState.value.partialTranscript)
            assertEquals(SttStatus.Listening, viewModel.uiState.value.status)

            allowFinal.complete(Unit)

            val state = viewModel.uiState.value
            assertEquals("你好", state.finalTranscript)
            assertTrue(state.partialTranscript.isEmpty())
            assertEquals(SttStatus.Completed, state.status)
        }

    /**
     * Verifies starting again cancels/joins the prior production session before replacement.
     *
     * @return This test has no return value.
     *
     * [runTest] uses two cancellable fake result streams and no platform I/O. The first stream's
     * `finally` records cancellation; the second remains active until Stop. Failure indicates
     * overlapping sessions or cancellation being exposed as a user-visible error.
     */
    @Test
    fun startCancelsPriorProductionSessionWithoutVisibleError() =
        runTest(mainDispatcherRule.dispatcher) {
            var sessionCount = 0
            val firstSessionCancelled = CompletableDeferred<Unit>()
            val productionClient =
                FakeSttClient { audio ->
                    val sessionNumber = ++sessionCount
                    flow {
                        try {
                            audio.collect()
                        } finally {
                            if (sessionNumber == 1) firstSessionCancelled.complete(Unit)
                        }
                    }
                }
            val viewModel = createViewModel(productionSttClient = productionClient)
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onStartRequested()
            viewModel.onStartRequested()

            assertEquals(2, sessionCount)
            assertTrue(firstSessionCancelled.isCompleted)
            assertEquals(SttStatus.Listening, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)

            viewModel.onStopRequested()
            assertEquals(SttStatus.Completed, viewModel.uiState.value.status)
        }

    /**
     * Verifies Stop releases capture, drains a delayed final result, and then completes.
     *
     * @return This test has no return value.
     *
     * [runTest] records deterministic fake cleanup events and gates the final provider response. It
     * performs no microphone/network I/O. Stop must leave the UI in Stopping after capture release;
     * completing the gate emits one final result and lets the session reach Completed.
     */
    @Test
    fun stopReleasesCaptureThenDrainsFinalResult() =
        runTest(mainDispatcherRule.dispatcher) {
            val events = mutableListOf<String>()
            val allowFinal = CompletableDeferred<Unit>()
            val audioSource =
                AudioCaptureSource {
                    flow {
                        try {
                            emit(byteArrayOf(1, 2, 3, 4))
                            awaitCancellation()
                        } finally {
                            events += "capture-released"
                        }
                    }
                }
            val productionClient =
                FakeSttClient { audio ->
                    flow {
                        audio.collect()
                        events += "audio-input-completed"
                        allowFinal.await()
                        emit(SttResult(transcript = "你好", isFinal = true))
                    }
                }
            val viewModel =
                createViewModel(
                    audioCaptureSource = audioSource,
                    productionSttClient = productionClient,
                )
            viewModel.onMicrophonePermissionObserved(isGranted = true)
            viewModel.onStartRequested()

            viewModel.onStopRequested()

            assertEquals(
                listOf("capture-released", "audio-input-completed"),
                events,
            )
            assertEquals(SttStatus.Stopping, viewModel.uiState.value.status)
            allowFinal.complete(Unit)

            val state = viewModel.uiState.value
            assertEquals("你好", state.finalTranscript)
            assertTrue(state.partialTranscript.isEmpty())
            assertEquals(SttStatus.Completed, state.status)
        }

    /**
     * Verifies a production cloud failure is recoverable and Retry restores usable state.
     *
     * @return This test has no return value.
     *
     * [runTest] injects a fixed provider-neutral CloudSttException without network I/O. It fails if
     * the exception escapes, leaves a busy status, or Retry cannot restore Idle. Structured capture
     * cancellation is performed by the production session's `finally` block.
     */
    @Test
    fun productionCloudFailureIsRecoverableAndRetryable() =
        runTest(mainDispatcherRule.dispatcher) {
            val productionClient =
                FakeSttClient {
                    flow {
                        throw CloudSttException(
                            CloudSttFailureReason.NotConfigured,
                            "Cloud speech is not configured. Add an approved credential and retry.",
                        )
                    }
                }
            val viewModel = createViewModel(productionSttClient = productionClient)
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onStartRequested()

            assertEquals(SttStatus.Error, viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("not configured") == true)

            viewModel.onRetryRequested()
            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies explicit recovery cancellation is not converted into a production error.
     *
     * @return This test has no return value.
     *
     * [runTest] cancels an indefinitely active fake production stream through Retry. It performs no
     * device/network I/O; structured CancellationException must remain hidden while state returns to
     * Idle. The test scope owns and completes all child cleanup.
     */
    @Test
    fun productionCancellationIsNotUserVisible() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            viewModel.onMicrophonePermissionObserved(isGranted = true)
            viewModel.onStartRequested()

            viewModel.onRetryRequested()

            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies a production stream with no first recognition result cannot remain Listening forever.
     *
     * @return This test has no return value.
     *
     * [runTest] uses the fake client's non-emitting Flow and advances virtual time without a real
     * delay. Watchdog expiry must cancel structured capture/stream work, publish recoverable Error,
     * and allow Retry to restore Idle. No microphone or network I/O occurs.
     */
    @Test
    fun stalledFirstResponseTimesOutAndRetryRecovers() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            viewModel.onMicrophonePermissionObserved(isGranted = true)

            viewModel.onStartRequested()
            assertEquals(SttStatus.Listening, viewModel.uiState.value.status)

            advanceUntilIdle()

            assertEquals(SttStatus.Error, viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("timed out") == true)
            viewModel.onRetryRequested()
            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Verifies Stop cannot remain in final-result draining indefinitely.
     *
     * @return This test has no return value.
     *
     * [runTest] injects a fake that consumes completed audio then suspends forever. Advancing virtual
     * time expires the drain watchdog, cancels the fake RPC, and exposes recoverable Error; Retry
     * returns to Idle. The test uses no real delay, microphone, credential, or network.
     */
    @Test
    fun stalledFinalDrainTimesOutAndRetryRecovers() =
        runTest(mainDispatcherRule.dispatcher) {
            val stalledClient =
                FakeSttClient { audio ->
                    flow {
                        audio.collect()
                        awaitCancellation()
                    }
                }
            val viewModel = createViewModel(productionSttClient = stalledClient)
            viewModel.onMicrophonePermissionObserved(isGranted = true)
            viewModel.onStartRequested()

            viewModel.onStopRequested()
            assertEquals(SttStatus.Stopping, viewModel.uiState.value.status)

            advanceUntilIdle()

            assertEquals(SttStatus.Error, viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("timed out") == true)
            viewModel.onRetryRequested()
            assertEquals(SttStatus.Idle, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * Creates a ViewModel with a cold fake capture that remains active until collector cancellation.
     *
     * @param audioCaptureSource cold fake PCM source for the test scenario.
     * @param pcmAudioPlayer fake raw PCM sink for the test scenario.
     * @param accessTokenProvider fake suspend token source for configuration checks.
     * @param apiKeyProvider fake suspend API-key source for configuration checks.
     * @param productionSttClient deterministic recognizer for normal Start/Stop sessions.
     * @param debugCloudSttClient deterministic V1 baseline stream for smoke-test events.
     * @param debugChirp2SttClient deterministic Chirp 2 stream; defaults to the V1 fake.
     * @param debugChirp3SttClient deterministic Chirp 3 stream; defaults to the V1 fake.
     * @param monotonicClock fake timing source for DEBUG evaluation metrics.
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
        accessTokenProvider: AccessTokenProvider = FakeAccessTokenProvider(),
        apiKeyProvider: ApiKeyProvider = ApiKeyProvider { null },
        productionSttClient: SttClient =
            FakeSttClient { audio ->
                flow { audio.collect() }
            },
        debugCloudSttClient: SttClient = FakeSttClient(),
        debugChirp2SttClient: SttClient = debugCloudSttClient,
        debugChirp3SttClient: SttClient = debugCloudSttClient,
        monotonicClock: MonotonicClock = MonotonicClock.SYSTEM,
    ): SttViewModel =
        SttViewModel(
            audioCaptureSource = audioCaptureSource,
            pcmAudioPlayer = pcmAudioPlayer,
            accessTokenProvider = accessTokenProvider,
            apiKeyProvider = apiKeyProvider,
            productionSttClient = productionSttClient,
            debugV1SttClient = debugCloudSttClient,
            debugChirp2SttClient = debugChirp2SttClient,
            debugChirp3SttClient = debugChirp3SttClient,
            monotonicClock = monotonicClock,
        )
}

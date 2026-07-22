package com.foxconn.seeandsay.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxconn.seeandsay.BuildConfig
import com.foxconn.seeandsay.config.BuildConfigAccessTokenProvider
import com.foxconn.seeandsay.config.BuildConfigApiKeyProvider
import com.foxconn.seeandsay.config.GcpSttV2Config
import com.foxconn.seeandsay.config.GcpTtsConfig
import com.foxconn.seeandsay.pipeline.RuleBasedReplyEngine
import com.foxconn.seeandsay.pipeline.VoicePipeline
import com.foxconn.seeandsay.pipeline.createIntegratedCommandCoordinator
import com.foxconn.seeandsay.speech.CloudSttClient
import com.foxconn.seeandsay.speech.CloudSttV2Client
import com.foxconn.seeandsay.speech.CloudTtsClient
import com.foxconn.seeandsay.speech.DebugAudioPlayer
import com.foxconn.seeandsay.speech.DeviceTtsClient
import com.foxconn.seeandsay.speech.FallbackTtsClient
import com.foxconn.seeandsay.speech.MicRecorder
import com.foxconn.seeandsay.speech.SwitchableTtsClient

/**
 * Hosts the M1.1 debug UI and coordinates Android's microphone permission APIs.
 *
 * The activity translates platform permission results into provider-neutral ViewModel events. It
 * performs lifecycle and UI work on Android's main thread, launches no coroutine itself, and owns
 * no microphone or network coroutine itself. The ViewModel owns cancellation of the injected audio
 * components, the local-only credential-presence check, selectable V1/Chirp 3 DEBUG production
 * recognition, and the M1.3 ReplyEngine/TTS tail through VoicePipeline. A separate TtsViewModel
 * owns an independent cloud-first Taiwan-Mandarin client for standalone DEBUG M1.2 controls,
 * avoiding cross-ViewModel client ownership. DEBUG-only inspectors lazily receive the scripted
 * matching bridge and the live integrated command coordinator; release creates neither. Permission
 * requests can be denied or suppressed by Android; both outcomes become recoverable UI state.
 */
class MainActivity : ComponentActivity() {

    private val sttViewModel: SttViewModel by viewModels {
        val accessTokenProvider = BuildConfigAccessTokenProvider()
        val apiKeyProvider = BuildConfigApiKeyProvider()
        val cloudSttClient = CloudSttClient(accessTokenProvider, apiKeyProvider)
        val chirp2Client =
            if (BuildConfig.DEBUG) {
                CloudSttV2Client(
                    accessTokenProvider = accessTokenProvider,
                    apiKeyProvider = apiKeyProvider,
                    model = GcpSttV2Config.CHIRP_2_MODEL,
                )
            } else {
                // Release has no selector or V2 dev location, so reuse V1 without allocating
                // unreachable regional channels that can never be selected.
                cloudSttClient
            }
        val chirp3Client =
            if (BuildConfig.DEBUG) {
                CloudSttV2Client(
                    accessTokenProvider = accessTokenProvider,
                    apiKeyProvider = apiKeyProvider,
                    model = GcpSttV2Config.CHIRP_3_MODEL,
                )
            } else {
                cloudSttClient
            }
        val productionWavenetCloudClient =
            CloudTtsClient(
                context = applicationContext,
                accessTokenProvider = accessTokenProvider,
                apiKeyProvider = apiKeyProvider,
                synthesisProfile = GcpTtsConfig.WAVENET_PROFILE,
            )
        val productionGeminiCloudClient =
            if (BuildConfig.DEBUG) {
                CloudTtsClient(
                    context = applicationContext,
                    accessTokenProvider = accessTokenProvider,
                    apiKeyProvider = apiKeyProvider,
                    synthesisProfile = GcpTtsConfig.GEMINI_PROFILE,
                )
            } else {
                // Release exposes no evaluation selector and therefore owns no unused Gemini
                // channel; the main pipeline remains on its stable WaveNet default.
                productionWavenetCloudClient
            }
        val selectableProductionCloudClient =
            SwitchableTtsClient(
                initialClient = productionWavenetCloudClient,
                clients = listOf(productionWavenetCloudClient, productionGeminiCloudClient),
            )
        val productionVoiceTtsClient =
            FallbackTtsClient(
                cloudClient = selectableProductionCloudClient,
                deviceClient = DeviceTtsClient(applicationContext),
                engineReporter = { engine ->
                    // Production loop diagnostics expose only the selected route, never reply text,
                    // audio, API keys, access tokens, prompts, or authorization metadata.
                    Log.i("TtsEngine", "engine=${engine.logValue}")
                },
            )
        SttViewModel.Factory(
            audioCaptureSource = MicRecorder(applicationContext),
            pcmAudioPlayer = DebugAudioPlayer(),
            accessTokenProvider = accessTokenProvider,
            apiKeyProvider = apiKeyProvider,
            productionSttClient = cloudSttClient,
            productionChirp3SttClient = chirp3Client,
            debugV1SttClient = cloudSttClient,
            debugChirp2SttClient = chirp2Client,
            debugChirp3SttClient = chirp3Client,
            voicePipeline =
                VoicePipeline(
                    replyEngine = RuleBasedReplyEngine(),
                    ttsClient = productionVoiceTtsClient,
                ),
            selectVoiceLoopTtsModel = { model ->
                selectableProductionCloudClient.select(
                    when (model) {
                        TtsModelOption.WaveNet -> productionWavenetCloudClient
                        TtsModelOption.Gemini -> productionGeminiCloudClient
                    },
                )
                // Selection diagnostics contain only a stable model category, never credentials,
                // prompt text, user text, authorization metadata, or synthesized audio.
                Log.i("TtsModel", "pipeline_model=${model.logValue}")
            },
        )
    }

    /**
     * Owns DEBUG TTS state with lifecycle-bound cloud-first and on-device fallback clients.
     *
     * Android creates this ViewModel lazily on the main thread. The cloud client performs no RPC
     * until Speak; the device fallback begins asynchronous engine initialization without speaking.
     * Lifecycle disposal cancels playback and closes both selectable cloud clients plus the device
     * fallback. Cloud/device failures become recoverable ViewModel state only if fallback cannot
     * complete.
     */
    private val ttsViewModel: TtsViewModel by viewModels {
        val accessTokenProvider = BuildConfigAccessTokenProvider()
        val apiKeyProvider = BuildConfigApiKeyProvider()
        val wavenetCloudClient =
            CloudTtsClient(
                context = applicationContext,
                accessTokenProvider = accessTokenProvider,
                apiKeyProvider = apiKeyProvider,
                synthesisProfile = GcpTtsConfig.WAVENET_PROFILE,
            )
        val geminiCloudClient =
            CloudTtsClient(
                context = applicationContext,
                accessTokenProvider = accessTokenProvider,
                apiKeyProvider = apiKeyProvider,
                synthesisProfile = GcpTtsConfig.GEMINI_PROFILE,
            )
        val selectableCloudClient =
            SwitchableTtsClient(
                initialClient = wavenetCloudClient,
                clients = listOf(wavenetCloudClient, geminiCloudClient),
            )
        val deviceClient = DeviceTtsClient(applicationContext)
        val fallbackClient =
            FallbackTtsClient(
                cloudClient = selectableCloudClient,
                deviceClient = deviceClient,
                engineReporter = { engine ->
                    // The route label is intentionally the only logged field: no text, model
                    // prompt, audio, token, API key, or authorization metadata may reach Logcat.
                    Log.i("TtsEngine", "engine=${engine.logValue}")
                },
            )
        TtsViewModel.Factory(
            ttsClient = fallbackClient,
            selectDebugModel = { model ->
                selectableCloudClient.select(
                    when (model) {
                        TtsModelOption.WaveNet -> wavenetCloudClient
                        TtsModelOption.Gemini -> geminiCloudClient
                    },
                )
            },
            playbackEngine = fallbackClient.playbackEngine,
        )
    }

    /**
     * Owns the DEBUG-only provider-neutral snapshot and text-decision inspector state.
     *
     * Android creates this ViewModel lazily only when the DEBUG composition reads it. The current
     * variant factory supplies a pure scripted bridge; release composition never accesses this
     * delegate. Screen-read failures remain recoverable state and no action method is invoked.
     */
    private val matchingInspectorViewModel: MatchingInspectorViewModel by viewModels {
        MatchingInspectorViewModel.Factory(createDebugUiBridge())
    }

    /**
     * Owns the DEBUG-only typed live bridge → decision → action → verification flow.
     *
     * The variant factory is accessed only from DEBUG composition. Its LM client stays lazy until a
     * command runs; release neither accesses this delegate nor constructs a provider/bridge.
     */
    private val integratedCommandViewModel: IntegratedCommandViewModel by viewModels {
        IntegratedCommandViewModel.Factory(createIntegratedCommandCoordinator())
    }

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // A false rationale result after a completed request means Android will not present the
            // dialog again, so recovery must move to the application's system Settings page.
            val isPermanentlyDenied =
                !isGranted &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    )
            sttViewModel.onMicrophonePermissionResult(
                isGranted = isGranted,
                isPermanentlyDenied = isPermanentlyDenied,
            )
        }

    /**
     * Creates the activity and installs the lifecycle-aware M1.1/M1.2 Compose debug screen.
     *
     * @param savedInstanceState framework state from a prior activity instance, or `null` for a new
     * instance.
     * @return This lifecycle callback has no return value.
     *
     * The callback runs on Android's main thread. StateFlow collection is automatically started and
     * stopped with the activity lifecycle; cancelling that collection does not alter ViewModel
     * state. Failures are limited to Android or Compose initialization failures because all
     * recoverable permission and Settings-launch failures are converted to UI errors.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by sttViewModel.uiState.collectAsStateWithLifecycle()
            val ttsUiState =
                if (BuildConfig.DEBUG) {
                    ttsViewModel.uiState.collectAsStateWithLifecycle().value
                } else {
                    // Release has no standalone TTS controls; avoiding ViewModel access also avoids
                    // initializing an on-device engine that no release code can invoke in M1.2.
                    TtsUiState()
                }
            val matchingInspectorState =
                if (BuildConfig.DEBUG) {
                    matchingInspectorViewModel.uiState.collectAsStateWithLifecycle().value
                } else {
                    null
                }
            val integratedCommandState =
                if (BuildConfig.DEBUG) {
                    integratedCommandViewModel.uiState.collectAsStateWithLifecycle().value
                } else {
                    null
                }

            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SttDebugScreen(
                        state = uiState,
                        ttsState = ttsUiState,
                        onStart = ::handleStartRequest,
                        onStop = sttViewModel::onStopRequested,
                        onDebugRecordAndPlayback = ::handleDebugRecordAndPlaybackRequest,
                        onCloudSttSmokeTest = ::handleCloudSttSmokeTestRequest,
                        onMainSttEngineSelected = sttViewModel::onMainSttEngineSelected,
                        onDebugSttEngineSelected = sttViewModel::onDebugSttEngineSelected,
                        onCloudConfigurationCheck =
                            sttViewModel::onCloudConfigurationCheckRequested,
                        onRetry = sttViewModel::onRetryRequested,
                        onOpenSettings = ::openApplicationSettings,
                        onTypedTranscriptSubmitted = sttViewModel::submitTypedTranscript,
                        onVoiceLoopEnabledChanged =
                            sttViewModel::onVoiceLoopEnabledChanged,
                        onVoiceLoopTtsModelSelected =
                            sttViewModel::onVoiceLoopTtsModelSelected,
                        onTtsSpeak = { text ->
                            if (
                                BuildConfig.DEBUG &&
                                    DebugAudioExclusionPolicy.canStartTts(uiState, ttsUiState)
                            ) {
                                ttsViewModel.onSpeakRequested(text)
                            }
                        },
                        onDebugTtsModelSelected = ttsViewModel::onDebugModelSelected,
                        onTtsStop = {
                            if (BuildConfig.DEBUG) ttsViewModel.onStopRequested()
                        },
                        matchingInspectorState = matchingInspectorState,
                        onMatchingInspectorRefresh = {
                            if (BuildConfig.DEBUG) {
                                matchingInspectorViewModel.onRefreshRequested()
                            }
                        },
                        onMatchingCommandSubmitted = { command ->
                            if (BuildConfig.DEBUG) {
                                matchingInspectorViewModel.onCommandSubmitted(command)
                            }
                        },
                        onVerificationBeforeSelected = {
                            if (BuildConfig.DEBUG) {
                                matchingInspectorViewModel.onVerificationBeforeSelected()
                            }
                        },
                        onVerificationAfterSelected = {
                            if (BuildConfig.DEBUG) {
                                matchingInspectorViewModel.onVerificationAfterSelected()
                            }
                        },
                        onVerificationRequested = { kind, expectedText ->
                            if (BuildConfig.DEBUG) {
                                matchingInspectorViewModel.onVerificationRequested(
                                    kind,
                                    expectedText,
                                )
                            }
                        },
                        integratedCommandState = integratedCommandState,
                        onIntegratedCommandRun = { command ->
                            if (BuildConfig.DEBUG) {
                                integratedCommandViewModel.onRunRequested(command) {
                                    moveTaskToBack(true)
                                }
                            }
                        },
                        onIntegratedLmInspect = { command ->
                            if (BuildConfig.DEBUG) {
                                integratedCommandViewModel.onLmInspectRequested(command) {
                                    moveTaskToBack(true)
                                }
                            }
                        },
                        onOpenAccessibilitySettings = {
                            if (BuildConfig.DEBUG) openAccessibilitySettings()
                        },
                    )
                }
            }
        }
    }

    /**
     * Reconciles permission state after returning from Android's application Settings screen.
     *
     * @return This lifecycle callback has no return value.
     *
     * The callback runs on the main thread and launches no coroutine. A revoked permission remains
     * recoverable through the existing denial state; a newly granted permission clears the denial
     * without automatically starting a listening session.
     */
    override fun onResume() {
        super.onResume()
        sttViewModel.onMicrophonePermissionObserved(hasMicrophonePermission())
    }

    /**
     * Starts the production permission, microphone, and cloud-recognition flow.
     *
     * @return This function has no return value.
     *
     * The function must run on the main thread because Activity Result launchers are lifecycle UI
     * APIs. The ViewModel launches and owns capture/recognition after permission is granted. A
     * permanently denied permission is left in a recoverable error state and is not requested
     * again; cloud and microphone failures are converted by the ViewModel rather than escaping.
     */
    private fun handleStartRequest() {
        if (hasMicrophonePermission()) {
            sttViewModel.onMicrophonePermissionObserved(isGranted = true)
        }

        sttViewModel.onStartRequested()
        if (sttViewModel.uiState.value.status == SttStatus.RequestingPermission) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Starts or stops the debug record-then-playback flow, requesting permission on first use.
     *
     * @return This function has no return value.
     *
     * The function runs on Android's main thread. Starting capture delegates lifecycle and
     * cancellation to the ViewModel; stopping delegates the ordered capture-join/playback sequence.
     * Permanent denial remains a recoverable error and does not relaunch a suppressed dialog.
     */
    private fun handleDebugRecordAndPlaybackRequest() {
        if (hasMicrophonePermission()) {
            sttViewModel.onMicrophonePermissionObserved(isGranted = true)
        }

        sttViewModel.onDebugRecordAndPlaybackRequested()
        if (sttViewModel.uiState.value.status == SttStatus.RequestingPermission) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Starts or stops the DEBUG microphone-to-Google streaming smoke test.
     *
     * @return This function has no return value.
     *
     * The function runs on Android's main thread. First use delegates microphone permission to the
     * existing Activity Result flow; granted requests are owned and cancelled by the ViewModel. A
     * denied request stays recoverable, and this function performs no network or microphone work
     * itself.
     */
    private fun handleCloudSttSmokeTestRequest() {
        if (hasMicrophonePermission()) {
            sttViewModel.onMicrophonePermissionObserved(isGranted = true)
        }

        sttViewModel.onCloudSttSmokeTestRequested()
        if (sttViewModel.uiState.value.status == SttStatus.RequestingPermission) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Opens this application's Android Settings page for permanent-denial recovery.
     *
     * @return This function has no return value.
     *
     * The function runs on the main thread and launches no coroutine. If the device cannot resolve
     * or authorize the Settings intent, the failure is reported through the ViewModel so the UI
     * remains responsive and retryable.
     */
    private fun openApplicationSettings() {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            sttViewModel.onExternalActionFailed(
                "Android could not open the application Settings screen.",
            )
        } catch (error: SecurityException) {
            sttViewModel.onExternalActionFailed(
                "Android blocked access to the application Settings screen.",
            )
        }
    }

    /**
     * Opens Android's accessibility settings for enabling the live bridge service.
     *
     * @return no value.
     *
     * This main-thread DEBUG helper starts only the system Settings activity. Resolution/security
     * failures become existing fixed UI error state; it performs no command, provider call, action,
     * event wait, credential access, or logging.
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            sttViewModel.onExternalActionFailed(
                "Android could not open accessibility Settings.",
            )
        } catch (error: SecurityException) {
            sttViewModel.onExternalActionFailed(
                "Android blocked access to accessibility Settings.",
            )
        }
    }

    /**
     * Checks the current platform grant for microphone recording.
     *
     * @return `true` when `RECORD_AUDIO` is currently granted; otherwise `false`.
     *
     * The check is synchronous, performs no blocking I/O, launches no coroutine, and is intended for
     * the main thread. Android reports absence as `false`; this function throws no project-specific
     * failure.
     */
    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

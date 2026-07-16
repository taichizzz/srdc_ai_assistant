package com.foxconn.seeandsay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxconn.seeandsay.BuildConfig
import com.foxconn.seeandsay.R

/**
 * Renders the M1.1 speech-input and DEBUG M1.2 text-to-speech verification interface.
 *
 * @param state immutable ViewModel state to display.
 * @param ttsState immutable standalone DEBUG TTS state to display.
 * @param onStart invoked when the user requests production microphone/cloud recognition.
 * @param onStop invoked when the user requests microphone release and final-result draining.
 * @param onDebugRecordAndPlayback invoked to start debug recording or stop it and play retained PCM.
 * @param onCloudSttSmokeTest invoked to start or stop the isolated DEBUG cloud round trip.
 * @param onDebugSttEngineSelected invoked to select only the next DEBUG comparison client.
 * @param onCloudConfigurationCheck invoked to inspect local key/token presence without a network call.
 * @param onRetry invoked to clear a recoverable error.
 * @param onOpenSettings invoked when permanent permission denial requires Android Settings.
 * @param onTypedTranscriptSubmitted invoked with manually entered text; the ViewModel routes it
 * through the same final-result reducer as production cloud STT.
 * @param onTtsSpeak invoked with DEBUG text to pass to the injected provider-neutral TtsClient.
 * @param onDebugTtsModelSelected invoked to select the cloud model for the next DEBUG Speak.
 * @param onTtsStop invoked to cancel the active standalone DEBUG TTS request.
 * @return This composable emits UI and has no return value.
 *
 * Composition and callbacks run on Android's main thread. The composable launches no coroutine,
 * owns no microphone/network resource, and has no cancellation behavior. Callback failures are
 * expected to be converted into ViewModel error state by the activity rather than thrown through
 * composition.
 */
@Composable
fun SttDebugScreen(
    state: SttUiState,
    ttsState: TtsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDebugRecordAndPlayback: () -> Unit,
    onCloudSttSmokeTest: () -> Unit,
    onDebugSttEngineSelected: (DebugSttEngine) -> Unit,
    onCloudConfigurationCheck: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onTypedTranscriptSubmitted: (String) -> Unit,
    onTtsSpeak: (String) -> Unit,
    onDebugTtsModelSelected: (DebugTtsModel) -> Unit,
    onTtsStop: () -> Unit,
) {
    var typedTranscript by rememberSaveable { mutableStateOf("") }
    var ttsText by rememberSaveable { mutableStateOf("") }
    val isTtsSpeaking = DebugAudioExclusionPolicy.isTtsSpeaking(ttsState)
    val isSessionActive =
        !state.isDebugRecording &&
            !state.isCloudSttSmokeTestRunning &&
            (state.status == SttStatus.Connecting || state.status == SttStatus.Listening)
    val isTransitioning =
        state.status == SttStatus.RequestingPermission || state.status == SttStatus.Stopping
    val isAudioBusy = !DebugAudioExclusionPolicy.canStartTts(state, ttsState)
    val permissionLabel =
        when (state.microphonePermission) {
            MicrophonePermissionStatus.NotRequested ->
                stringResource(R.string.permission_not_requested)
            MicrophonePermissionStatus.Granted -> stringResource(R.string.permission_granted)
            MicrophonePermissionStatus.Denied -> stringResource(R.string.permission_denied)
            MicrophonePermissionStatus.PermanentlyDenied ->
                stringResource(R.string.permission_permanently_denied)
        }
    val cloudConfigurationLabel =
        when (state.cloudConfiguration) {
            CloudConfigurationStatus.NotChecked ->
                stringResource(R.string.cloud_configuration_not_checked)
            CloudConfigurationStatus.Configured ->
                stringResource(R.string.cloud_configuration_configured)
            CloudConfigurationStatus.NotConfigured ->
                stringResource(R.string.cloud_configuration_not_configured)
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.m1_1_debug_title),
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider()

        Text(
            text = stringResource(R.string.permission_status, permissionLabel),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.session_status, state.status.name),
            style = MaterialTheme.typography.bodyLarge,
        )

        Button(
            onClick = if (isSessionActive) onStop else onStart,
            enabled =
                !isTransitioning &&
                    !state.isDebugRecording &&
                    !state.isDebugPlaybackActive &&
                    !state.isCloudSttSmokeTestRunning &&
                    !isTtsSpeaking,
        ) {
            Text(
                text =
                    when {
                        state.status == SttStatus.RequestingPermission ->
                            stringResource(R.string.requesting_permission)
                        state.status == SttStatus.Stopping -> stringResource(R.string.stopping)
                        isSessionActive -> stringResource(R.string.stop)
                        else -> stringResource(R.string.start)
                    },
            )
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.cloud_configuration_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.cloud_configuration_status, cloudConfigurationLabel),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.cloud_configuration_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onCloudConfigurationCheck,
            enabled = !isAudioBusy && !state.isCloudConfigurationCheckRunning,
        ) {
            Text(
                if (state.isCloudConfigurationCheckRunning) {
                    stringResource(R.string.cloud_configuration_checking)
                } else {
                    stringResource(R.string.cloud_configuration_check)
                },
            )
        }

        if (BuildConfig.DEBUG) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.cloud_stt_smoke_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.cloud_stt_smoke_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.cloud_stt_engine_selector),
                style = MaterialTheme.typography.labelLarge,
            )
            DebugSttEngine.entries.forEach { engine ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = state.selectedDebugSttEngine == engine,
                        onClick = { onDebugSttEngineSelected(engine) },
                        enabled = !isAudioBusy,
                    )
                    Text(
                        text = engine.displayName,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            state.debugSttMetrics?.let { metrics ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cloud_stt_metrics_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(
                                R.string.cloud_stt_metrics_engine,
                                metrics.engine.apiVersion,
                                metrics.engine.model,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.cloud_stt_metrics_first_token,
                                metrics.firstTokenLatencyMs?.toString()
                                    ?: stringResource(R.string.cloud_stt_metrics_unavailable),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.cloud_stt_metrics_final,
                                metrics.finalSentenceLatencyMs?.toString()
                                    ?: stringResource(R.string.cloud_stt_metrics_unavailable),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.cloud_stt_metrics_total,
                                metrics.totalLatencyMs?.toString()
                                    ?: stringResource(R.string.cloud_stt_metrics_unavailable),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.cloud_stt_metrics_outcome,
                                metrics.outcome.csvValue,
                            ),
                        )
                        Text(
                            text = stringResource(R.string.cloud_stt_metrics_auto_rubric),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.cloud_stt_metrics_manual_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.cloud_stt_metrics_log_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.cloud_stt_smoke_partial_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text =
                    state.cloudSmokePartialTranscript.ifBlank {
                        stringResource(R.string.cloud_stt_smoke_no_partial)
                    },
            )
            Text(
                text = stringResource(R.string.cloud_stt_smoke_final_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text =
                    state.cloudSmokeFinalTranscript.ifBlank {
                        stringResource(R.string.cloud_stt_smoke_no_final)
                    },
            )
            state.cloudSmokeFinalConfidence?.let { confidence ->
                Text(
                    text = stringResource(R.string.cloud_stt_smoke_confidence, confidence),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onCloudSttSmokeTest,
                enabled =
                    !isTransitioning &&
                        !state.isDebugRecording &&
                        !state.isDebugPlaybackActive &&
                        !isTtsSpeaking &&
                        (!isSessionActive || state.isCloudSttSmokeTestRunning),
            ) {
                Text(
                    if (
                        state.isCloudSttSmokeTestRunning ||
                            (state.status == SttStatus.Stopping &&
                                state.debugSttMetrics?.outcome == DebugSttOutcome.Running)
                    ) {
                        stringResource(R.string.cloud_stt_smoke_stop)
                    } else {
                        stringResource(R.string.cloud_stt_smoke_start)
                    },
                )
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.debug_loopback_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.debug_loopback_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.debug_captured_bytes, state.debugCapturedBytes),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.debugBufferLimitReached) {
                Text(
                    text = stringResource(R.string.debug_limit_reached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.isDebugPlaybackActive) {
                Text(
                    text = stringResource(R.string.debug_playing),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(
                onClick = onDebugRecordAndPlayback,
                enabled =
                    !state.isDebugPlaybackActive &&
                        !state.isCloudSttSmokeTestRunning &&
                        !isTtsSpeaking &&
                        !isTransitioning &&
                        (!isSessionActive || state.isDebugRecording),
            ) {
                Text(
                    if (state.isDebugRecording) {
                        stringResource(R.string.debug_stop_and_play)
                    } else {
                        stringResource(R.string.debug_record_and_play)
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.partial_transcript_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = state.partialTranscript.ifBlank { stringResource(R.string.no_partial_transcript) },
        )

        Text(
            text = stringResource(R.string.final_transcript_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = state.finalTranscript.ifBlank { stringResource(R.string.no_final_transcript) },
        )

        HorizontalDivider()
        Text(
            text = stringResource(R.string.typed_input_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.typed_input_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = typedTranscript,
            onValueChange = { typedTranscript = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.typed_transcript_label)) },
            singleLine = true,
            enabled = !isAudioBusy,
        )
        Button(
            onClick = {
                val submittedTranscript = typedTranscript.trim()
                if (submittedTranscript.isNotEmpty()) {
                    onTypedTranscriptSubmitted(submittedTranscript)
                    typedTranscript = ""
                }
            },
            enabled = typedTranscript.isNotBlank() && !isAudioBusy,
        ) {
            Text(stringResource(R.string.submit_transcript))
        }

        if (BuildConfig.DEBUG) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.tts_debug_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tts_cloud_fallback_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.tts_model_selector),
                style = MaterialTheme.typography.labelLarge,
            )
            DebugTtsModel.entries.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = ttsState.selectedDebugModel == model,
                        onClick = { onDebugTtsModelSelected(model) },
                        enabled = !isAudioBusy,
                    )
                    Text(
                        text = model.displayName,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Text(
                text =
                    stringResource(
                        R.string.tts_selected_model,
                        ttsState.selectedDebugModel.model,
                        ttsState.selectedDebugModel.speaker
                            ?: stringResource(R.string.tts_classic_voice),
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.tts_status, ttsState.status.name),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    stringResource(
                        R.string.tts_playback_engine,
                        ttsState.playbackEngine.displayName,
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.tts_playback_engine_log_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    stringResource(
                        R.string.tts_current_text,
                        ttsState.currentText.ifBlank {
                            stringResource(R.string.tts_no_current_text)
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = ttsText,
                onValueChange = { ttsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tts_text_label)) },
                singleLine = true,
                enabled = !isAudioBusy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onTtsSpeak(ttsText) },
                    enabled = ttsText.isNotBlank() && !isAudioBusy,
                ) {
                    Text(stringResource(R.string.tts_speak))
                }
                OutlinedButton(
                    onClick = onTtsStop,
                    enabled = isTtsSpeaking,
                ) {
                    Text(stringResource(R.string.tts_stop))
                }
            }
            ttsState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.errorMessage?.let { errorMessage ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.retry))
                        }
                        if (
                            state.microphonePermission ==
                                MicrophonePermissionStatus.PermanentlyDenied
                        ) {
                            OutlinedButton(onClick = onOpenSettings) {
                                Text(stringResource(R.string.open_settings))
                            }
                        }
                    }
                }
            }

        }

        if (state.microphonePermission == MicrophonePermissionStatus.Denied) {
            Text(
                text = stringResource(R.string.microphone_rationale),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

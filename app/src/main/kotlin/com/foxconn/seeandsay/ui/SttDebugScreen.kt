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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxconn.seeandsay.R

/**
 * Renders the minimal M1.1 push-to-talk and typed-transcript debug interface.
 *
 * @param state immutable ViewModel state to display.
 * @param onStart invoked when the user requests a permission/state-only Start transition.
 * @param onStop invoked when the user requests a permission/state-only Stop transition.
 * @param onRetry invoked to clear a recoverable error.
 * @param onOpenSettings invoked when permanent permission denial requires Android Settings.
 * @param onTypedTranscriptSubmitted invoked with manually entered text; the ViewModel routes it
 * through the same final-result reducer as future cloud STT.
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onTypedTranscriptSubmitted: (String) -> Unit,
) {
    var typedTranscript by rememberSaveable { mutableStateOf("") }
    val isSessionActive =
        state.status == SttStatus.Connecting || state.status == SttStatus.Listening
    val isTransitioning =
        state.status == SttStatus.RequestingPermission || state.status == SttStatus.Stopping
    val permissionLabel =
        when (state.microphonePermission) {
            MicrophonePermissionStatus.NotRequested ->
                stringResource(R.string.permission_not_requested)
            MicrophonePermissionStatus.Granted -> stringResource(R.string.permission_granted)
            MicrophonePermissionStatus.Denied -> stringResource(R.string.permission_denied)
            MicrophonePermissionStatus.PermanentlyDenied ->
                stringResource(R.string.permission_permanently_denied)
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
            enabled = !isTransitioning,
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
        )
        Button(
            onClick = {
                val submittedTranscript = typedTranscript.trim()
                if (submittedTranscript.isNotEmpty()) {
                    onTypedTranscriptSubmitted(submittedTranscript)
                    typedTranscript = ""
                }
            },
            enabled = typedTranscript.isNotBlank(),
        ) {
            Text(stringResource(R.string.submit_transcript))
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

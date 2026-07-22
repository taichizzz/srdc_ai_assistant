package com.foxconn.seeandsay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.VerificationResult

/**
 * Renders the DEBUG screen-snapshot and tiered-decision inspector.
 *
 * @param state immutable provider-neutral snapshot and latest matching result.
 * @param onRefresh invoked to read a new snapshot without performing an action.
 * @param onCommandSubmitted invoked with typed text for pure matching against the shown snapshot.
 * @param onVerificationBeforeSelected captures the shown scripted snapshot as the before-state.
 * @param onVerificationAfterSelected captures the shown scripted snapshot as the after-state.
 * @param onVerificationRequested previews one explicitly selected decision and expected edit text.
 * @return This composable emits UI and has no return value.
 *
 * Composition and callbacks run on Android's main thread. The composable owns only saveable typed
 * text, launches no coroutine, performs no bridge action or I/O, and relies on the ViewModel to
 * convert read failures into [MatchingInspectorUiState.errorMessage].
 */
@Composable
fun MatchingInspectorSection(
    state: MatchingInspectorUiState,
    onRefresh: () -> Unit,
    onCommandSubmitted: (String) -> Unit,
    onVerificationBeforeSelected: () -> Unit,
    onVerificationAfterSelected: () -> Unit,
    onVerificationRequested: (VerificationDecisionKind, String) -> Unit,
) {
    var command by rememberSaveable { mutableStateOf("") }
    var expectedText by rememberSaveable { mutableStateOf("Roxanne") }

    HorizontalDivider()
    Text(
        text = stringResource(R.string.matching_inspector_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.matching_inspector_explanation),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) {
        Text(
            if (state.isLoading) {
                stringResource(R.string.matching_inspector_loading)
            } else {
                stringResource(R.string.matching_inspector_refresh)
            },
        )
    }

    val snapshot = state.snapshot
    Text(
        text =
            if (snapshot == null) {
                stringResource(R.string.matching_inspector_no_snapshot)
            } else {
                stringResource(
                    R.string.matching_inspector_snapshot,
                    snapshot.screen,
                    snapshot.capturedAt,
                )
            },
        style = MaterialTheme.typography.labelLarge,
    )
    snapshot?.elements?.forEach { element ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    stringResource(
                        R.string.matching_inspector_element,
                        element.i,
                        element.text,
                        element.clickable.toString(),
                        element.editable.toString(),
                    ),
                modifier = Modifier.padding(10.dp),
            )
        }
    }

    OutlinedTextField(
        value = command,
        onValueChange = { command = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.matching_inspector_command)) },
        singleLine = true,
        enabled = snapshot != null && !state.isLoading,
    )
    Button(
        onClick = { onCommandSubmitted(command) },
        enabled = command.isNotBlank() && snapshot != null && !state.isLoading,
    ) {
        Text(stringResource(R.string.matching_inspector_match))
    }

    state.result?.let { result ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.matching_inspector_decision, decisionLabel(result.decision)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text =
                        stringResource(
                            R.string.matching_inspector_confidence,
                            result.tier?.name ?: stringResource(R.string.matching_inspector_no_tier),
                            result.score,
                        ),
                )
            }
        }
    }

    HorizontalDivider()
    Text(
        text = stringResource(R.string.verification_inspector_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.verification_inspector_explanation),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text =
            stringResource(
                R.string.verification_inspector_selection,
                state.verificationBefore?.screen
                    ?: stringResource(R.string.matching_inspector_no_tier),
                state.verificationAfter?.screen
                    ?: stringResource(R.string.matching_inspector_no_tier),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onVerificationBeforeSelected,
            enabled = snapshot != null && !state.isLoading,
        ) {
            Text(stringResource(R.string.verification_inspector_use_before))
        }
        OutlinedButton(
            onClick = onVerificationAfterSelected,
            enabled = snapshot != null && !state.isLoading,
        ) {
            Text(stringResource(R.string.verification_inspector_use_after))
        }
    }
    OutlinedTextField(
        value = expectedText,
        onValueChange = { expectedText = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.verification_inspector_expected_text)) },
        singleLine = true,
    )
    Text(
        text = stringResource(R.string.verification_inspector_decision_prompt),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onVerificationRequested(VerificationDecisionKind.Click, expectedText)
            },
            enabled = state.verificationBefore != null && state.verificationAfter != null,
        ) {
            Text(stringResource(R.string.verification_inspector_click))
        }
        Button(
            onClick = {
                onVerificationRequested(VerificationDecisionKind.SetText, expectedText)
            },
            enabled =
                expectedText.isNotBlank() &&
                    state.verificationBefore != null &&
                    state.verificationAfter != null,
        ) {
            Text(stringResource(R.string.verification_inspector_set_text))
        }
        Button(
            onClick = {
                onVerificationRequested(VerificationDecisionKind.Back, expectedText)
            },
            enabled = state.verificationBefore != null && state.verificationAfter != null,
        ) {
            Text(stringResource(R.string.verification_inspector_back))
        }
    }
    state.verificationDecision?.let { decision ->
        Text(
            text =
                stringResource(
                    R.string.verification_inspector_decision,
                    decisionLabel(decision),
                ),
        )
    }
    state.verificationResult?.let { result ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    stringResource(
                        R.string.verification_inspector_result,
                        verificationResultLabel(result),
                    ),
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    state.errorMessage?.let { message ->
        Text(text = message, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Formats a typed verification result and fixed reason for DEBUG inspection.
 *
 * @param result immutable pure comparison outcome.
 * @return `Verified`, or the typed failure category followed by its fixed non-secret reason.
 *
 * This pure function performs synchronous string formatting only, is safe on any dispatcher,
 * performs no I/O, waiting, timer, or suspension, and cannot fail for sealed result values.
 */
private fun verificationResultLabel(result: VerificationResult): String =
    when (result) {
        VerificationResult.Verified -> "Verified"
        is VerificationResult.NotVerified -> "NotVerified: ${result.reason}"
        is VerificationResult.Inconclusive -> "Inconclusive: ${result.reason}"
    }

/**
 * Formats one decision for DEBUG display without executing it.
 *
 * @param decision immutable pure decision from the matcher.
 * @return concise label containing the selected index/text or non-action result.
 *
 * This pure function performs synchronous string formatting only, is safe on any dispatcher,
 * performs no I/O, and has no expected failure for sealed decision values.
 */
private fun decisionLabel(decision: Decision): String =
    when (decision) {
        is Decision.Click -> "Click(index=${decision.index})"
        is Decision.SetText -> "SetText(index=${decision.index}, text=${decision.text})"
        Decision.Back -> "Back"
        is Decision.Speak -> "Speak(${decision.text})"
        Decision.NoMatch -> "NoMatch"
    }

package com.foxconn.seeandsay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import com.foxconn.seeandsay.bridge.ScreenSettleResult
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.IntentResult
import com.foxconn.seeandsay.decision.LmDiagnosticIssue
import com.foxconn.seeandsay.decision.LmInterpretationDiagnostic
import com.foxconn.seeandsay.decision.VerificationResult
import com.foxconn.seeandsay.pipeline.CommandAction
import com.foxconn.seeandsay.pipeline.CommandSnapshotDiagnostic
import com.foxconn.seeandsay.pipeline.IntegratedCommandOutcome
import com.foxconn.seeandsay.pipeline.LmDebugOutcome

/**
 * Appends the live DEBUG command → action → event wait → verification inspector.
 *
 * @param state immutable lifecycle-owned run state.
 * @param onRun invoked with typed text; the activity backgrounds itself only after the coordinator
 * subscribes to accessibility events.
 * @param onInspectLm runs live LM resolution against the same revealed target without acting.
 * @param onOpenAccessibilitySettings opens Android's service-enable settings.
 * @return emitted Compose UI; no value.
 *
 * Composition is main-thread and performs no bridge/provider work itself. It owns only saveable
 * typed text. Callback failures are converted by the ViewModel to fixed state.
 */
@Composable
fun IntegratedCommandSection(
    state: IntegratedCommandUiState,
    onRun: (String) -> Unit,
    onInspectLm: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var command by rememberSaveable { mutableStateOf("開設定") }

    HorizontalDivider()
    Text(text = stringResource(R.string.integrated_command_title))
    Text(text = stringResource(R.string.integrated_command_explanation))
    OutlinedButton(onClick = onOpenAccessibilitySettings, enabled = !state.isRunning) {
        Text(stringResource(R.string.integrated_command_accessibility_settings))
    }
    OutlinedTextField(
        value = command,
        onValueChange = { command = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.integrated_command_input)) },
        singleLine = true,
        enabled = !state.isRunning,
    )
    Button(
        onClick = { onRun(command) },
        enabled = command.isNotBlank() && !state.isRunning,
    ) {
        Text(
            if (state.isRunning) {
                stringResource(R.string.integrated_command_running)
            } else {
                stringResource(R.string.integrated_command_run)
            },
        )
    }
    OutlinedButton(
        onClick = { onInspectLm(command) },
        enabled = command.isNotBlank() && !state.isRunning,
    ) {
        Text(stringResource(R.string.integrated_command_inspect_lm))
    }

    state.outcome?.let { outcome ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (outcome) {
                    is IntegratedCommandOutcome.Failed ->
                        Text(stringResource(R.string.integrated_command_failure, outcome.reason))
                    is IntegratedCommandOutcome.Completed -> {
                        val result = outcome.result
                        Text(
                            stringResource(
                                R.string.integrated_command_decision,
                                decisionLabel(result.resolution.decision),
                            ),
                        )
                        Text(snapshotLabel(result.snapshot))
                        Text(
                            stringResource(
                                R.string.integrated_command_path,
                                result.resolution.path.name,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.integrated_command_action,
                                actionLabel(result.action),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.integrated_command_settle,
                                settleLabel(result.settleResult),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.integrated_command_verification,
                                verificationLabel(result.verification),
                            ),
                        )
                    }
                }
            }
        }
    }
    state.lmDebugOutcome?.let { outcome ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.lm_debug_title))
                when (outcome) {
                    is LmDebugOutcome.Failed ->
                        Text(stringResource(R.string.integrated_command_failure, outcome.reason))
                    is LmDebugOutcome.Completed -> {
                        Text(snapshotLabel(outcome.snapshot))
                        Text(
                            stringResource(
                                R.string.integrated_command_path,
                                outcome.resolution.path.name,
                            ),
                        )
                        Text(lmDiagnosticLabel(outcome.resolution.lmDiagnostic))
                    }
                }
            }
        }
    }
    state.errorMessage?.let { message -> Text(message) }
}

/** Pure, non-secret DEBUG formatting of one decision. */
private fun decisionLabel(decision: Decision): String =
    when (decision) {
        is Decision.Click -> "Click(index=${decision.index})"
        is Decision.SetText -> "SetText(index=${decision.index})"
        Decision.Back -> "Back"
        is Decision.Speak -> "Speak(${decision.text})"
        Decision.NoMatch -> "NoMatch"
    }

/** Pure, non-secret DEBUG formatting of bridge dispatch state. */
private fun actionLabel(action: CommandAction): String =
    when (action) {
        CommandAction.None -> "None"
        is CommandAction.Attempted ->
            "${decisionLabel(action.decision)} · accepted=${action.accepted}"
    }

/** Pure DEBUG formatting of an event-wait observation. */
private fun settleLabel(result: ScreenSettleResult?): String =
    when (result) {
        ScreenSettleResult.ChangeObserved -> "ChangeObserved"
        ScreenSettleResult.TimedOut -> "TimedOut"
        null -> "Not required"
    }

/** Pure DEBUG formatting of typed verification and its fixed reason. */
private fun verificationLabel(result: VerificationResult?): String =
    when (result) {
        VerificationResult.Verified -> "Verified"
        is VerificationResult.NotVerified -> "NotVerified: ${result.reason}"
        is VerificationResult.Inconclusive -> "Inconclusive: ${result.reason}"
        null -> "Not required"
    }

/** Pure formatting that makes foreground-versus-underlying snapshot provenance explicit. */
private fun snapshotLabel(snapshot: CommandSnapshotDiagnostic): String =
    "Snapshot: source=${snapshot.source.name} · screen=${snapshot.screen} · elements=${snapshot.elementCount}"

/** Pure safe formatting of validated LM result/attempt metadata without raw model output. */
private fun lmDiagnosticLabel(diagnostic: LmInterpretationDiagnostic?): String {
    if (diagnostic == null) return "LM: disabled/skipped · attempts=0"
    val result =
        when (val intent = diagnostic.result) {
            is IntentResult.Resolved ->
                "Resolved(goal=${intent.goal}, confidence=${intent.confidence})"
            is IntentResult.Clarify -> "Clarify(${intent.question})"
            IntentResult.NoMatch -> "NoMatch"
        }
    val issue =
        when (val rejected = diagnostic.lastRejectedIssue) {
            is LmDiagnosticIssue.ClientFailure -> rejected.reason.name
            LmDiagnosticIssue.InvalidOrUnsafeResponse -> "InvalidOrUnsafeResponse"
            null -> "None"
        }
    return "LM: $result · attempts=${diagnostic.attempts} · lastIssue=$issue"
}

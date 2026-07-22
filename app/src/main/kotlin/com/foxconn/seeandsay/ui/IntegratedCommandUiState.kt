package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.pipeline.IntegratedCommandOutcome
import com.foxconn.seeandsay.pipeline.LmDebugOutcome

/**
 * Immutable DEBUG state for one live typed-command run.
 *
 * @property isRunning true while target reveal, decision, action, waiting, or verification runs.
 * @property outcome latest completed/fixed-failure diagnostic, or null before the first run.
 * @property lmDebugOutcome latest no-action live LM inspection, or null before inspection.
 * @property errorMessage fixed UI orchestration failure, or null.
 *
 * Values contain no credential, prompt, raw model output, Android node, or callback. They are safe
 * across threads and perform no I/O, suspension, cancellation, or logging.
 */
data class IntegratedCommandUiState(
    val isRunning: Boolean = false,
    val outcome: IntegratedCommandOutcome? = null,
    val lmDebugOutcome: LmDebugOutcome? = null,
    val errorMessage: String? = null,
)

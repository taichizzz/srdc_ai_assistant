package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * Provider-neutral primary semantic interpretation contract for every enabled non-empty utterance.
 *
 * Implementations receive a snapshot as context only and must never return an element index,
 * coordinate, or UI function. Calls may suspend and must propagate coroutine cancellation. Domain
 * failures should become typed [IntentResult] values rather than provider exceptions where the
 * implementation contract documents that behavior.
 */
interface IntentInterpreter {

    /**
     * Interprets one transcript as a high-level user goal or clarification/no-match outcome.
     *
     * @param transcript shared-normalizer non-empty utterance supplied before fallback matching.
     * @param screen immutable contextual snapshot projected to index-free provider context internally;
     * its indices and bounds are never valid provider input or interpreter output.
     * @return provider-neutral semantic result containing no executable action or element index.
     *
     * Implementations may suspend for a provider seam. Cancellation must propagate unchanged;
     * ordinary malformed/provider failures may return [IntentResult.NoMatch] as documented.
     */
    suspend fun interpret(transcript: String, screen: ScreenSnapshot): IntentResult
}

/**
 * Provider-neutral result of semantic intent interpretation.
 *
 * Values are immutable pure Kotlin data with no Android/provider types, I/O, coroutine,
 * cancellation, network, logging, or failure-prone behavior.
 */
sealed interface IntentResult {

    /**
     * Reports one schema-validated high-level goal.
     *
     * @property goal goal containing no index, coordinate, or UI function.
     * @property confidence validated model confidence in the inclusive range 0.0..1.0.
     */
    data class Resolved(
        val goal: UserGoal,
        val confidence: Float,
    ) : IntentResult

    /**
     * Requests user clarification instead of guessing from low-confidence or explicit uncertainty.
     *
     * @property question non-blank fixed or schema-validated user-facing question.
     */
    data class Clarify(val question: String) : IntentResult

    /** Reports that two strict attempts could not produce a safe schema-valid interpretation. */
    object NoMatch : IntentResult
}

/**
 * Safe reason the LM debugger can show for a rejected interpretation attempt.
 *
 * Values contain no raw response, transcript, prompt, URL, token, header, or credential. They are
 * immutable pure diagnostics with no I/O, logging, coroutine, or failure behavior.
 */
sealed interface LmDiagnosticIssue {

    /** @property reason fixed recoverable client category from the provider-neutral LM seam. */
    data class ClientFailure(val reason: LmClientFailureReason) : LmDiagnosticIssue

    /** The response was malformed, schema-invalid, semantically incomplete, or safety-rejected. */
    data object InvalidOrUnsafeResponse : LmDiagnosticIssue
}

/**
 * Schema-safe LM interpretation diagnostics suitable for DEBUG display.
 *
 * @property result final provider-neutral interpretation result.
 * @property attempts number of LM completion attempts made, from one through the configured bound.
 * @property lastRejectedIssue latest fixed rejection category, null when no attempt was rejected.
 *
 * This value deliberately excludes raw model output and credentials. It performs no work and is
 * safe across threads and dispatchers.
 */
data class LmInterpretationDiagnostic(
    val result: IntentResult,
    val attempts: Int,
    val lastRejectedIssue: LmDiagnosticIssue? = null,
)

/**
 * Optional interpreter extension exposing safe attempt diagnostics without changing intent output.
 *
 * Implementations must preserve [IntentInterpreter] cancellation and safety rules and must never
 * expose raw provider output or credential material.
 */
interface DiagnosticIntentInterpreter : IntentInterpreter {

    /**
     * Interprets one utterance and reports only schema-safe result/attempt metadata.
     *
     * @param transcript normalized semantic input.
     * @param screen immutable contextual snapshot projected index-free at the LM seam.
     * @return safe result and bounded attempt diagnostics.
     *
     * Provider work may suspend. Cancellation propagates unchanged; ordinary failures are reported
     * by fixed [LmDiagnosticIssue] values.
     */
    suspend fun interpretWithDiagnostics(
        transcript: String,
        screen: ScreenSnapshot,
    ): LmInterpretationDiagnostic
}

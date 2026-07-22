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

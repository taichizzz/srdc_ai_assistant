package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * Composes primary semantic interpretation, local grounding, and deterministic failure fallback.
 *
 * Implementations may suspend for primary interpretation. They must preserve coroutine cancellation,
 * keep direct commands available through disabled/provider-failure fallback, validate every local
 * action against the supplied snapshot, and never fabricate success or an off-screen action.
 */
interface DecisionEngine {

    /**
     * Chooses one safe response for an utterance and current immutable screen.
     *
     * @param transcript raw user utterance normalized before deterministic comparison.
     * @param screen current immutable snapshot used for both matching and local validation.
     * @return validated action, clarification/honest speech, or no-match.
     *
     * Calls may suspend for [IntentInterpreter]. Cancellation must propagate unchanged; recoverable
     * interpretation/provider failure must not crash or become an invalid action.
     */
    suspend fun decide(transcript: String, screen: ScreenSnapshot): Decision
}

/**
 * Observable route that produced a decision without exposing provider or wire-format details.
 *
 * Values are immutable pure diagnostics. They perform no I/O, logging, suspension, or credential
 * handling and are safe to display in DEBUG UI.
 */
enum class DecisionResolutionPath {
    /** The enabled LM produced a schema-validated goal or clarification. */
    LanguageModel,

    /** Deterministic TextMatcher handled disabled, unavailable, rejected, or failed LM resolution. */
    DeterministicFallback,
}

/**
 * A safe decision paired with the high-level route that resolved it.
 *
 * @property decision validated action, clarification, honest limitation, or no-match.
 * @property path provider-neutral primary/fallback diagnostic; never contains model output/secrets.
 * @property lmDiagnostic safe schema/result attempt metadata, null when the LM was disabled.
 *
 * This immutable value performs no work and is safe across threads and dispatchers.
 */
data class DecisionResolution(
    val decision: Decision,
    val path: DecisionResolutionPath,
    val lmDiagnostic: LmInterpretationDiagnostic? = null,
)

/**
 * Decision engine extension used by integration diagnostics to identify LM versus fallback routing.
 *
 * Implementations preserve all [DecisionEngine] safety and cancellation requirements. The added
 * result is provider-neutral and must never expose prompts, model output, tokens, or HTTP details.
 */
interface TraceableDecisionEngine : DecisionEngine {

    /**
     * Resolves a safe decision and its provider-neutral route.
     *
     * @param transcript raw user utterance.
     * @param screen immutable current screen used for grounding and validation.
     * @return decision plus LM/fallback route.
     *
     * Calls may suspend for interpretation. Cancellation propagates; recoverable LM failures enter
     * deterministic fallback exactly as [decide] does.
     */
    suspend fun decideWithResolution(
        transcript: String,
        screen: ScreenSnapshot,
    ): DecisionResolution
}

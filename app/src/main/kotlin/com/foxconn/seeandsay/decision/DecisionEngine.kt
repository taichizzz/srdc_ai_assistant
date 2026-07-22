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

package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot

/**
 * Validates an emitted decision against the exact snapshot used for planning.
 *
 * Click/SetText indices must be non-negative, within snapshot list range, unique in the snapshot,
 * and backed by the required clickable/editable capability. This rejects stale, malformed, and
 * mismatched action candidates before any bridge call. Non-action decisions pass unchanged.
 *
 * The object is stateless, deterministic, thread-safe, and safe on any dispatcher. It performs no
 * I/O, Android/accessibility work, coroutine, cancellation, timer, or expected failure work.
 */
object LocalDecisionValidator {

    /**
     * Checks one decision against current immutable snapshot capabilities.
     *
     * @param decision candidate decision from deterministic matching or local goal planning.
     * @param screen exact snapshot against which the decision was produced.
     * @return unchanged valid decision, or [Decision.NoMatch] for any invalid actionable decision.
     *
     * This pure function performs synchronous in-memory checks only, no I/O or suspension, and has
     * no expected failure for ordinary values.
     */
    fun validate(decision: Decision, screen: ScreenSnapshot): Decision =
        when (decision) {
            is Decision.Click ->
                if (
                    decision.index in screen.elements.indices &&
                    screen.elements.singleOrNull { element -> element.i == decision.index }?.clickable == true
                ) {
                    decision
                } else {
                    Decision.NoMatch
                }
            is Decision.SetText ->
                if (
                    decision.index in screen.elements.indices &&
                    screen.elements.singleOrNull { element -> element.i == decision.index }?.editable == true
                ) {
                    decision
                } else {
                    Decision.NoMatch
                }
            Decision.Back,
            is Decision.Speak,
            Decision.NoMatch,
            -> decision
        }
}

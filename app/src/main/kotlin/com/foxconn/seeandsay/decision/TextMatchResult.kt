package com.foxconn.seeandsay.decision

/**
 * Confidence tier used to produce a [TextMatchResult].
 *
 * Declaration order is strongest to weakest and is the matcher evaluation order. Enum values are
 * immutable pure data with no I/O, threading, coroutine, or failure behavior.
 */
enum class MatchTier {
    /** Normalized command and element label are equal. */
    Exact,

    /** One normalized value contains the other. */
    Substring,

    /** Both normalized values resolve to one centralized canonical alias group. */
    Alias,

    /** Bounded edit distance meets the conservative similarity policy. */
    Fuzzy,
}

/**
 * Explicit action kind requested by a caller before matching an element label.
 *
 * Keeping action selection outside text interpretation avoids introducing a Week 3 intent table.
 * Values are immutable and safe across dispatchers, perform no I/O, and have no failure behavior.
 */
sealed interface RequestedUiAction {

    /** Requests a clickable element and produces [Decision.Click] on one safe match. */
    object Click : RequestedUiAction

    /**
     * Requests an editable element and produces [Decision.SetText] on one safe match.
     *
     * @property text exact replacement text retained without normalization or semantic parsing.
     */
    data class SetText(val text: String) : RequestedUiAction
}

/**
 * Observable result of one tiered text-matching evaluation.
 *
 * @property decision safe action, clarification, or no-match result.
 * @property tier first successful confidence tier, or `null` when no candidate qualified.
 * @property score highest score in that tier in the inclusive range 0.0..1.0; ambiguity retains the
 * best candidate's score, while no-match is 0.0.
 * @property candidateIndices ordered element identifiers considered by the selected tier.
 *
 * This immutable pure value is safe across threads and dispatchers and performs no I/O,
 * suspension, cancellation, or failure-prone work.
 */
data class TextMatchResult(
    val decision: Decision,
    val tier: MatchTier?,
    val score: Double,
    val candidateIndices: List<Int>,
)

package com.foxconn.seeandsay.decision

/**
 * Pure result of deciding how the assistant should respond to the current screen.
 *
 * Values contain intent only and never execute an action. They are immutable, deterministic data,
 * safe to pass between dispatchers, and have no I/O, suspension, cancellation, or failure behavior.
 */
sealed class Decision {

    /**
     * Requests activation of the element identified by [index].
     *
     * @property index identifier from the screen snapshot; execution may later fail if stale.
     */
    data class Click(val index: Int) : Decision()

    /**
     * Requests replacement text for one editable element.
     *
     * @property index identifier from the screen snapshot; execution may later fail if stale.
     * @property text complete replacement text, including any user-significant spacing.
     */
    data class SetText(val index: Int, val text: String) : Decision()

    /** Requests backward navigation without selecting a screen element. */
    object Back : Decision()

    /**
     * Requests speech without a UI action, including clarification for ambiguous matches.
     *
     * @property text non-blank text intended for the assistant's speech layer.
     */
    data class Speak(val text: String) : Decision()

    /** Reports that deterministic matching found no safe action. */
    object NoMatch : Decision()
}

package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.normalization.TextNormalizer

/**
 * Observable evidence expected after one UI action has been dispatched.
 *
 * Expectations contain immutable comparison data only; they do not dispatch, wait for, or observe
 * Android events. Values are safe across threads and dispatchers and perform no I/O, suspension,
 * cancellation, timer, or failure-prone work.
 */
sealed interface ActionExpectation {

    /**
     * Expects a click to change screen identity/content or remove the clicked label.
     *
     * @property clickedIndex identifier used for the executed click.
     * @property clickedText label captured from the before-snapshot and normalized only at compare
     * time; raw text is retained for diagnostics and is never compared directly.
     */
    data class ScreenChanged(
        val clickedIndex: Int,
        val clickedText: String,
    ) : ActionExpectation

    /**
     * Expects replacement text to become observable in the edited element or another element.
     *
     * @property index identifier used for the executed set-text action.
     * @property expectedText exact requested replacement text, normalized only for comparison.
     */
    data class TextEntered(
        val index: Int,
        val expectedText: String,
    ) : ActionExpectation

    /** Expects backward navigation to change normalized screen identity or visible element data. */
    object NavigatedBack : ActionExpectation
}

/**
 * Typed outcome of comparing before/after snapshots with an [ActionExpectation].
 *
 * Results are immutable pure data with fixed non-secret reasons. They are safe across threads and
 * perform no I/O, suspension, cancellation, timer, logging, or failure-prone work.
 */
sealed interface VerificationResult {

    /** Reports that the after-snapshot contains sufficient evidence of the expected change. */
    object Verified : VerificationResult

    /**
     * Reports a usable after-snapshot that lacks evidence of the expected change.
     *
     * @property reason fixed, non-secret diagnostic suitable for DEBUG display and logs.
     */
    data class NotVerified(val reason: String) : VerificationResult

    /**
     * Reports that snapshot or expectation data is insufficient to prove success or failure.
     *
     * @property reason fixed, non-secret diagnostic suitable for DEBUG display and logs.
     */
    data class Inconclusive(val reason: String) : VerificationResult
}

/**
 * Derives observable evidence from an executed decision and its before-snapshot.
 *
 * @param decision decision whose action was dispatched.
 * @param before immutable snapshot against which the decision was executed.
 * @return click, set-text, or back expectation; `null` for [Decision.Speak], [Decision.NoMatch], or
 * a click whose index is absent from [before].
 *
 * This pure function is deterministic and safe on any dispatcher. It performs no I/O, action,
 * waiting, suspension, timer, or raw string comparison and has no expected failure.
 */
fun expectationFor(
    decision: Decision,
    before: ScreenSnapshot,
): ActionExpectation? =
    when (decision) {
        is Decision.Click ->
            before.elements
                .firstOrNull { element -> element.i == decision.index }
                ?.let { element ->
                    ActionExpectation.ScreenChanged(
                        clickedIndex = decision.index,
                        clickedText = element.text,
                    )
                }
        is Decision.SetText -> ActionExpectation.TextEntered(decision.index, decision.text)
        Decision.Back -> ActionExpectation.NavigatedBack
        is Decision.Speak,
        Decision.NoMatch,
        -> null
    }

/**
 * Compares already-captured before/after snapshots against one expected action effect.
 *
 * @param before usable snapshot captured before dispatching the action.
 * @param after fresh snapshot supplied after Person 1's event-driven waiting completes.
 * @param expectation observable evidence derived from the executed decision.
 * @return [VerificationResult.Verified] for sufficient evidence,
 * [VerificationResult.NotVerified] for a usable unchanged/wrong result, or
 * [VerificationResult.Inconclusive] when snapshot/expectation data cannot prove either outcome.
 *
 * This pure deterministic function delegates to [ActionVerifier]. It is thread-safe and safe on
 * any dispatcher, performs no I/O, accessibility access, event listening, delay, suspension, timer,
 * or logging, and has no expected failure for immutable snapshot values.
 */
fun verify(
    before: ScreenSnapshot,
    after: ScreenSnapshot,
    expectation: ActionExpectation,
): VerificationResult = ActionVerifier.verify(before, after, expectation)

/**
 * Pure snapshot comparison policy for post-action verification.
 *
 * Element equality is an unordered normalized multiset of `(text, clickable, editable)`. Indices,
 * bounds, capture timestamps, and traversal order are excluded because they may change without a
 * visible semantic change. Duplicate elements remain significant because the sorted signature
 * retains every occurrence. Screen identity is normalized and compared separately.
 *
 * The object is stateless and thread-safe. All functions are deterministic CPU-only operations,
 * safe on any dispatcher, and perform no Android work, I/O, event waiting, delay, suspension,
 * cancellation, logging, or expected domain-failure work.
 */
object ActionVerifier {

    /**
     * Applies expectation-specific evidence rules to two immutable snapshots.
     *
     * @param before snapshot captured before action dispatch.
     * @param after fresh snapshot captured after external event-driven waiting.
     * @param expectation evidence required for the executed action.
     * @return typed verified, not-verified, or inconclusive outcome with fixed reasons.
     *
     * This pure function is synchronous and safe on any dispatcher. It performs no I/O, waiting,
     * timers, accessibility access, or suspension and has no expected failure.
     */
    fun verify(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        expectation: ActionExpectation,
    ): VerificationResult {
        if (after.elements.isEmpty()) {
            return VerificationResult.Inconclusive(AFTER_SNAPSHOT_UNUSABLE)
        }
        if (before.elements.isEmpty()) {
            return VerificationResult.Inconclusive(BEFORE_SNAPSHOT_UNUSABLE)
        }
        return when (expectation) {
            is ActionExpectation.ScreenChanged -> verifyScreenChanged(before, after, expectation)
            is ActionExpectation.TextEntered -> verifyTextEntered(before, after, expectation)
            ActionExpectation.NavigatedBack -> verifyNavigatedBack(before, after)
        }
    }

    /**
     * Verifies click evidence from identity, clicked-label disappearance, or element-set change.
     *
     * @param before usable pre-click snapshot.
     * @param after usable post-click snapshot.
     * @param expectation clicked index/text captured before action dispatch.
     * @return verified when any accepted signal exists, otherwise fixed not-verified result.
     *
     * This pure helper performs normalized in-memory comparison only, no I/O or suspension, and has
     * no expected failure for usable snapshots.
     */
    private fun verifyScreenChanged(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        expectation: ActionExpectation.ScreenChanged,
    ): VerificationResult {
        if (screenIdentityChanged(before, after)) return VerificationResult.Verified
        if (clickedElementDisappeared(after, expectation)) return VerificationResult.Verified
        if (elementSignature(before) != elementSignature(after)) return VerificationResult.Verified
        return VerificationResult.NotVerified(CLICK_PRODUCED_NO_CHANGE)
    }

    /**
     * Verifies normalized expected text at the original index or anywhere in the new snapshot.
     *
     * @param before usable pre-edit snapshot.
     * @param after usable post-edit snapshot.
     * @param expectation edited index and exact requested text.
     * @return verified for normalized containment plus an effective element change, inconclusive
     * for noise-only expected text, or fixed not-verified result for wrong/unchanged content.
     *
     * This pure helper performs normalized string comparison only, no I/O or suspension, and has no
     * expected failure.
     */
    private fun verifyTextEntered(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        expectation: ActionExpectation.TextEntered,
    ): VerificationResult {
        val normalizedExpected = TextNormalizer.normalize(expectation.expectedText)
        if (normalizedExpected.isEmpty()) {
            return VerificationResult.Inconclusive(EXPECTED_TEXT_UNUSABLE)
        }
        val indexedElements = after.elements.filter { element -> element.i == expectation.index }
        val otherElements = after.elements.filterNot { element -> element.i == expectation.index }
        val expectedTextFound =
            (indexedElements + otherElements).any { element ->
                TextNormalizer.normalize(element.text).contains(normalizedExpected)
            }
        return if (expectedTextFound && elementSignature(before) != elementSignature(after)) {
            VerificationResult.Verified
        } else {
            VerificationResult.NotVerified(EXPECTED_TEXT_NOT_FOUND)
        }
    }

    /**
     * Verifies backward navigation through normalized identity or element-set change.
     *
     * @param before usable pre-back snapshot.
     * @param after usable post-back snapshot.
     * @return verified when identity/content changed, otherwise fixed not-verified result.
     *
     * This pure helper performs normalized in-memory comparison only, no I/O or suspension, and has
     * no expected failure.
     */
    private fun verifyNavigatedBack(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): VerificationResult =
        if (
            screenIdentityChanged(before, after) ||
            elementSignature(before) != elementSignature(after)
        ) {
            VerificationResult.Verified
        } else {
            VerificationResult.NotVerified(BACK_PRODUCED_NO_CHANGE)
        }

    /**
     * Detects a meaningful normalized screen identifier change when both identifiers are usable.
     *
     * @param before pre-action snapshot.
     * @param after post-action snapshot.
     * @return `true` only when both normalized identifiers are non-empty and unequal.
     *
     * This pure helper performs no I/O or suspension and cannot fail for Kotlin strings.
     */
    private fun screenIdentityChanged(before: ScreenSnapshot, after: ScreenSnapshot): Boolean {
        val beforeIdentity = TextNormalizer.normalize(before.screen)
        val afterIdentity = TextNormalizer.normalize(after.screen)
        return beforeIdentity.isNotEmpty() &&
            afterIdentity.isNotEmpty() &&
            beforeIdentity != afterIdentity
    }

    /**
     * Detects absence of the normalized clicked label from the post-click screen.
     *
     * @param after usable post-click snapshot.
     * @param expectation clicked text retained from the before-snapshot.
     * @return `true` only for a non-empty normalized label absent from all post-click elements.
     *
     * This pure helper performs no raw comparison, I/O, or suspension and has no expected failure.
     */
    private fun clickedElementDisappeared(
        after: ScreenSnapshot,
        expectation: ActionExpectation.ScreenChanged,
    ): Boolean {
        val clickedText = TextNormalizer.normalize(expectation.clickedText)
        return clickedText.isNotEmpty() &&
            after.elements.none { element ->
                TextNormalizer.normalize(element.text) == clickedText
            }
    }

    /**
     * Builds an order-independent, duplicate-preserving normalized element signature.
     *
     * @param snapshot immutable snapshot whose material visible element set is compared.
     * @return sorted immutable signature values excluding index, bounds, time, and traversal order.
     *
     * This pure deterministic helper performs bounded CPU/allocation work proportional to the
     * snapshot size, no hashing collision risk, I/O, or suspension, and has no expected failure.
     */
    private fun elementSignature(snapshot: ScreenSnapshot): List<ElementSignature> =
        snapshot.elements
            .map { element -> element.toSignature() }
            .sortedWith(
                compareBy<ElementSignature>(ElementSignature::normalizedText)
                    .thenBy(ElementSignature::clickable)
                    .thenBy(ElementSignature::editable),
            )

    /**
     * Converts one element to its normalized material comparison value.
     *
     * @receiver immutable snapshot element.
     * @return signature containing normalized text and action capabilities only.
     *
     * This pure conversion performs no I/O or suspension and cannot fail for ordinary text.
     */
    private fun ScreenElement.toSignature(): ElementSignature =
        ElementSignature(
            normalizedText = TextNormalizer.normalize(text),
            clickable = clickable,
            editable = editable,
        )

    /**
     * Collision-free structural value used in the sorted snapshot multiset.
     *
     * @property normalizedText shared-normalizer comparison key.
     * @property clickable material activation capability.
     * @property editable material text-entry capability.
     *
     * This private immutable value performs no I/O, threading, or failure-prone work.
     */
    private data class ElementSignature(
        val normalizedText: String,
        val clickable: Boolean,
        val editable: Boolean,
    )

    /** Fixed, non-secret reason for an after-read with no usable elements. */
    private const val AFTER_SNAPSHOT_UNUSABLE =
        "The after-snapshot contains no usable elements."

    /** Fixed, non-secret reason for a before-snapshot with no comparison evidence. */
    private const val BEFORE_SNAPSHOT_UNUSABLE =
        "The before-snapshot contains no usable elements."

    /** Fixed, non-secret reason for a noise-only expected edit value. */
    private const val EXPECTED_TEXT_UNUSABLE =
        "The expected text contains no matchable characters."

    /** Fixed, non-secret reason for a usable unchanged post-click snapshot. */
    private const val CLICK_PRODUCED_NO_CHANGE =
        "The click produced no observable screen change."

    /** Fixed, non-secret reason for missing normalized text-entry evidence. */
    private const val EXPECTED_TEXT_NOT_FOUND =
        "The expected text change was not observable after editing."

    /** Fixed, non-secret reason for a usable unchanged post-back snapshot. */
    private const val BACK_PRODUCED_NO_CHANGE =
        "Back produced no observable screen change."
}

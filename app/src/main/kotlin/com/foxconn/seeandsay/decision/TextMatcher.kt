package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.normalization.TextNormalizer

/**
 * Resolves a spoken or typed command against clickable text in one immutable screen snapshot.
 *
 * Exact normalized matches take precedence over substring containment. A match is actionable only
 * when exactly one clickable element remains; ambiguity is surfaced as speech instead of guessed.
 * Empty normalized commands and labels are never candidates.
 *
 * The matcher is stateless. [match] is deterministic, synchronous, pure, thread-safe, and safe on
 * any dispatcher. It performs no Android work or I/O, never suspends, and has no expected failure
 * for ordinary strings and snapshots.
 */
class TextMatcher {

    /**
     * Selects a click, clarification, or no-match decision for the current screen.
     *
     * @param command raw user command; it is normalized before every comparison.
     * @param snapshot immutable screen whose clickable elements are eligible for matching.
     * @return [Decision.Click] for one match, [Decision.Speak] for multiple matches, or
     * [Decision.NoMatch] when no safe clickable match exists.
     *
     * This pure function runs synchronously on the caller's thread, is safe on any dispatcher,
     * performs no I/O or suspension, and has no expected failure for ordinary input.
     */
    fun match(command: String, snapshot: ScreenSnapshot): Decision {
        val normalizedCommand = TextNormalizer.normalize(command)
        if (normalizedCommand.isEmpty()) return Decision.NoMatch

        val candidates =
            snapshot.elements
                .asSequence()
                .filter(ScreenElement::clickable)
                .map { element -> NormalizedElement(element, TextNormalizer.normalize(element.text)) }
                .filter { candidate -> candidate.normalizedText.isNotEmpty() }
                .toList()

        val exactMatches = candidates.filter { candidate -> candidate.normalizedText == normalizedCommand }
        val matches =
            exactMatches.ifEmpty {
                candidates.filter { candidate ->
                    normalizedCommand.contains(candidate.normalizedText) ||
                        candidate.normalizedText.contains(normalizedCommand)
                }
            }
        return decide(matches)
    }

    /**
     * Converts a completed match set into a safe deterministic decision.
     *
     * @param matches clickable candidates from one matching tier.
     * @return no match, one click, or a clarification naming the first matching screen label.
     *
     * This pure helper performs only in-memory allocation, has no threading restriction or I/O,
     * and cannot fail for candidates created by [match].
     */
    private fun decide(matches: List<NormalizedElement>): Decision =
        when (matches.size) {
            0 -> Decision.NoMatch
            1 -> Decision.Click(matches.single().element.i)
            else -> Decision.Speak("有兩個『${matches.first().element.text.trim()}』，你要哪一個？")
        }

    /**
     * Keeps one screen element beside its shared-normalizer comparison key.
     *
     * @property element original immutable snapshot element.
     * @property normalizedText canonical non-raw key; it may be empty before candidate filtering.
     *
     * This private immutable value has no I/O, threading, or failure behavior.
     */
    private data class NormalizedElement(
        val element: ScreenElement,
        val normalizedText: String,
    )
}

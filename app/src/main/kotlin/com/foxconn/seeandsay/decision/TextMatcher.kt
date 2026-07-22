package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.normalization.TextNormalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves a normalized command against eligible text in one immutable screen snapshot.
 *
 * Evaluation stops at the first tier containing candidates: exact, substring, centralized alias,
 * then conservative fuzzy similarity. Every comparison uses [TextNormalizer]. More than one
 * candidate at the winning tier is always ambiguous, even when scores differ, so the matcher never
 * guesses among multiple visible choices. [RequestedUiAction] controls click/edit eligibility
 * without interpreting a Week 3 user intent.
 *
 * The matcher is stateless. Its functions are deterministic, synchronous, pure, thread-safe, and
 * safe on any dispatcher. They perform no Android work or I/O, never suspend, and have no expected
 * failure for ordinary strings and snapshots.
 */
class TextMatcher {

    /**
     * Preserves the Phase 1 convenience API for a requested click.
     *
     * @param command raw user command normalized before every comparison.
     * @param snapshot immutable screen whose clickable elements are eligible.
     * @return safe click, ambiguity speech, or no-match decision.
     *
     * This pure function delegates to [evaluate], performs no I/O or suspension, is safe on any
     * dispatcher, and has no expected failure for ordinary input.
     */
    fun match(command: String, snapshot: ScreenSnapshot): Decision =
        evaluate(command, snapshot, RequestedUiAction.Click).decision

    /**
     * Evaluates all confidence tiers for an explicit requested action.
     *
     * @param command raw user command normalized before every comparison.
     * @param snapshot immutable screen containing candidate elements.
     * @param action requested click or set-text operation; this is not inferred from the command.
     * @return decision plus winning [MatchTier], score, and candidate indices for diagnostics.
     *
     * This pure function runs synchronously on the caller's thread, performs no I/O or suspension,
     * is safe on any dispatcher, and returns no-match rather than throwing for empty/noisy input.
     */
    fun evaluate(
        command: String,
        snapshot: ScreenSnapshot,
        action: RequestedUiAction = RequestedUiAction.Click,
    ): TextMatchResult {
        val normalizedCommand = TextNormalizer.normalize(command)
        if (normalizedCommand.isEmpty()) return noMatch()

        val candidates =
            snapshot.elements
                .asSequence()
                .filter { element -> isEligible(element, action) }
                .map { element -> NormalizedElement(element, TextNormalizer.normalize(element.text)) }
                .filter { candidate -> candidate.normalizedText.isNotEmpty() }
                .toList()

        val exact = exactMatches(normalizedCommand, candidates)
        if (exact.isNotEmpty()) return decide(MatchTier.Exact, exact, action)
        val substring = substringMatches(normalizedCommand, candidates)
        if (substring.isNotEmpty()) return decide(MatchTier.Substring, substring, action)
        val aliases = aliasMatches(normalizedCommand, candidates)
        if (aliases.isNotEmpty()) return decide(MatchTier.Alias, aliases, action)
        val fuzzy = fuzzyMatches(normalizedCommand, candidates)
        if (fuzzy.isNotEmpty()) return decide(MatchTier.Fuzzy, fuzzy, action)
        return noMatch()
    }

    /**
     * Determines whether an element supports the explicitly requested action.
     *
     * @param element immutable screen element under consideration.
     * @param action requested click or edit operation.
     * @return `true` only for clickable clicks or editable set-text requests.
     *
     * This pure predicate performs no I/O, suspension, or raw text comparison and cannot fail.
     */
    private fun isEligible(element: ScreenElement, action: RequestedUiAction): Boolean =
        when (action) {
            RequestedUiAction.Click -> element.clickable
            is RequestedUiAction.SetText -> element.editable
        }

    /**
     * Finds normalized equality candidates.
     *
     * @param command normalized command key.
     * @param candidates normalized eligible elements.
     * @return candidates with fixed maximum confidence 1.0.
     *
     * This pure helper performs no I/O or suspension and cannot fail for matcher-created values.
     */
    private fun exactMatches(
        command: String,
        candidates: List<NormalizedElement>,
    ): List<ScoredElement> =
        candidates
            .filter { candidate -> candidate.normalizedText == command }
            .map { candidate -> ScoredElement(candidate.element, EXACT_SCORE) }

    /**
     * Finds candidates where either normalized value contains the other.
     *
     * @param command normalized command key.
     * @param candidates normalized eligible elements.
     * @return containment candidates scored by the shorter-to-longer length ratio.
     *
     * This pure helper performs no I/O or suspension and cannot divide by zero because empty labels
     * and commands are removed before invocation.
     */
    private fun substringMatches(
        command: String,
        candidates: List<NormalizedElement>,
    ): List<ScoredElement> =
        candidates.mapNotNull { candidate ->
            val label = candidate.normalizedText
            if (command.contains(label) || label.contains(command)) {
                val ratio = minOf(command.length, label.length).toDouble() / max(command.length, label.length)
                ScoredElement(candidate.element, SUBSTRING_BASE_SCORE + ratio * SUBSTRING_SCORE_RANGE)
            } else {
                null
            }
        }

    /**
     * Finds candidates sharing a centralized canonical alias group with the command.
     *
     * @param command normalized command key.
     * @param candidates normalized eligible elements.
     * @return alias candidates with a fixed confidence below substring matching.
     *
     * This pure helper performs normalized map lookups only, no I/O or suspension, and has no
     * expected failure after [TextAliases] initializes.
     */
    private fun aliasMatches(
        command: String,
        candidates: List<NormalizedElement>,
    ): List<ScoredElement> {
        val commandCanonical = TextAliases.canonicalForm(command) ?: return emptyList()
        return candidates
            .filter { candidate ->
                TextAliases.canonicalForm(candidate.normalizedText) == commandCanonical
            }.map { candidate -> ScoredElement(candidate.element, ALIAS_SCORE) }
    }

    /**
     * Finds bounded edit-distance candidates meeting every conservative fuzzy guard.
     *
     * @param command normalized command key.
     * @param candidates normalized eligible elements.
     * @return candidates with similarity at least [MIN_FUZZY_SIMILARITY], edit distance no more than
     * [MAX_FUZZY_DISTANCE], and both lengths inside the supported short-label range.
     *
     * This pure CPU-only helper performs no I/O or suspension. It returns an empty list for short,
     * long, or length-incompatible input instead of risking a low-confidence action.
     */
    private fun fuzzyMatches(
        command: String,
        candidates: List<NormalizedElement>,
    ): List<ScoredElement> {
        if (command.length !in MIN_FUZZY_LENGTH..MAX_FUZZY_LENGTH) return emptyList()
        return candidates.mapNotNull { candidate ->
            val label = candidate.normalizedText
            if (
                label.length !in MIN_FUZZY_LENGTH..MAX_FUZZY_LENGTH ||
                abs(command.length - label.length) > MAX_FUZZY_DISTANCE
            ) {
                return@mapNotNull null
            }
            val distance = boundedEditDistance(command, label, MAX_FUZZY_DISTANCE)
            val similarity = 1.0 - distance.toDouble() / max(command.length, label.length)
            if (distance <= MAX_FUZZY_DISTANCE && similarity >= MIN_FUZZY_SIMILARITY) {
                ScoredElement(candidate.element, similarity)
            } else {
                null
            }
        }.sortedByDescending(ScoredElement::score)
    }

    /**
     * Computes Levenshtein distance while capping work and results beyond [limit].
     *
     * @param left first normalized non-empty key.
     * @param right second normalized non-empty key.
     * @param limit largest distance relevant to the caller.
     * @return exact distance up to [limit], otherwise [limit] plus one.
     *
     * This pure bounded-memory function performs no I/O or suspension. Non-negative [limit] is
     * required by its private caller and ordinary matcher input cannot cause a domain failure.
     */
    private fun boundedEditDistance(left: String, right: String, limit: Int): Int {
        if (abs(left.length - right.length) > limit) return limit + 1
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            var rowMinimum = current[0]
            right.forEachIndexed { rightIndex, rightCharacter ->
                val substitutionCost = if (leftCharacter == rightCharacter) 0 else 1
                current[rightIndex + 1] =
                    minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + substitutionCost,
                    )
                rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
            }
            if (rowMinimum > limit) return limit + 1
            previous = current
        }
        return previous.last()
    }

    /**
     * Converts one winning-tier candidate set into an action or named clarification.
     *
     * @param tier first tier that produced candidates.
     * @param matches score-ranked or snapshot-ordered eligible candidates.
     * @param action explicit action to emit only when exactly one candidate exists.
     * @return observable result containing a safe action or option-naming ambiguity question.
     *
     * This pure helper allocates immutable result data, performs no I/O or suspension, and cannot
     * fail for the non-empty match sets passed by [evaluate].
     */
    private fun decide(
        tier: MatchTier,
        matches: List<ScoredElement>,
        action: RequestedUiAction,
    ): TextMatchResult {
        val decision =
            if (matches.size == 1) {
                when (action) {
                    RequestedUiAction.Click -> Decision.Click(matches.single().element.i)
                    is RequestedUiAction.SetText ->
                        Decision.SetText(matches.single().element.i, action.text)
                }
            } else {
                Decision.Speak(ambiguityQuestion(matches.map(ScoredElement::element)))
            }
        return TextMatchResult(
            decision = decision,
            tier = tier,
            score = matches.maxOf(ScoredElement::score),
            candidateIndices = matches.map { match -> match.element.i },
        )
    }

    /**
     * Names every visible option in a clarification question without comparing raw labels.
     *
     * @param elements two or more eligible matched elements.
     * @return Mandarin question distinguishing identical pairs by first/second and otherwise listing
     * every label in snapshot/ranking order.
     *
     * This pure formatter performs no matching, I/O, or suspension and cannot fail for matcher
     * output. Raw labels are used only for user-facing display, never for comparison.
     */
    private fun ambiguityQuestion(elements: List<ScreenElement>): String {
        val labels = elements.map { element -> element.text.trim() }
        return if (labels.size == 2 && TextNormalizer.normalize(labels[0]) == TextNormalizer.normalize(labels[1])) {
            "我看到兩個『${labels[0]}』，你要第一個還是第二個？"
        } else {
            val displayedOptions =
                labels.map { label -> "『$label』" }.joinToString(separator = "、")
            "我看到$displayedOptions，你要哪一個？"
        }
    }

    /**
     * Creates the stable absence result.
     *
     * @return no-match decision with no tier/candidates and score 0.0.
     *
     * This pure factory performs no I/O, suspension, or failure-prone work.
     */
    private fun noMatch(): TextMatchResult =
        TextMatchResult(Decision.NoMatch, tier = null, score = 0.0, candidateIndices = emptyList())

    /** Immutable element beside its shared-normalizer key; pure data with no failure behavior. */
    private data class NormalizedElement(
        val element: ScreenElement,
        val normalizedText: String,
    )

    /** Immutable candidate beside its tier-local score; pure data with no failure behavior. */
    private data class ScoredElement(
        val element: ScreenElement,
        val score: Double,
    )

    /** Stable conservative confidence policy shared by all matcher instances. */
    companion object {
        /** Exact normalized equality is maximum confidence. */
        const val EXACT_SCORE = 1.0

        /** Base confidence for containment before adding label coverage. */
        const val SUBSTRING_BASE_SCORE = 0.90

        /** Maximum additional containment confidence from label coverage. */
        const val SUBSTRING_SCORE_RANGE = 0.05

        /** Fixed confidence for a reviewed synonym-table match. */
        const val ALIAS_SCORE = 0.88

        /** Minimum normalized edit similarity allowed to enter the fuzzy tier. */
        const val MIN_FUZZY_SIMILARITY = 0.80

        /** Maximum edit operations allowed even when the similarity threshold passes. */
        const val MAX_FUZZY_DISTANCE = 2

        /** Minimum key length eligible for fuzzy matching, preventing unsafe tiny-label edits. */
        const val MIN_FUZZY_LENGTH = 3

        /** Maximum key length eligible for fuzzy matching, preventing confident long-text clicks. */
        const val MAX_FUZZY_LENGTH = 12
    }
}

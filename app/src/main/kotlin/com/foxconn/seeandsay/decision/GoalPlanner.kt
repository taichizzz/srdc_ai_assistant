package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.normalization.TextNormalizer

/**
 * Maps a high-level [UserGoal] to an action available on the current immutable screen only.
 *
 * Implementations must never navigate speculatively, launch another application, or fabricate an
 * index for an off-screen target. Planning is synchronous pure Kotlin with no I/O, Android,
 * accessibility, coroutine, cancellation, timer, or failure behavior for ordinary values.
 */
interface GoalPlanner {

    /**
     * Plans one goal against the elements visible in [screen].
     *
     * @param goal schema-validated high-level goal containing no element index.
     * @param screen immutable current snapshot; only its eligible elements may become actions.
     * @return actionable decision, honest off-screen result, clarification, or unsupported result.
     *
     * This pure function runs synchronously on the caller's thread, is safe on any dispatcher,
     * performs no I/O or suspension, and has no expected failure for ordinary values.
     */
    fun plan(goal: UserGoal, screen: ScreenSnapshot): PlanResult
}

/**
 * Typed result of local goal-to-current-screen planning.
 *
 * Values are immutable pure data, safe across threads/dispatchers, and perform no I/O, Android,
 * coroutine, cancellation, timer, or failure-prone work.
 */
sealed interface PlanResult {

    /** @property decision locally selected action or non-action speech decision. */
    data class Actionable(val decision: Decision) : PlanResult

    /**
     * Reports that a understood goal has no suitable control on this screen.
     *
     * @property goal original goal retained for an honest caller response or later task planning.
     */
    data class NotOnThisScreen(val goal: UserGoal) : PlanResult

    /** @property question non-blank option-naming clarification instead of a guessed action. */
    data class NeedsClarification(val question: String) : PlanResult

    /** Reports a goal that the local planner deliberately does not execute. */
    object Unsupported : PlanResult
}

/**
 * Conservative local grounding planner for current-screen goals.
 *
 * A model-provided [UserGoal.controlQuery] is preferred and grounded through [TextMatcher]'s
 * exact/substring/alias/fuzzy tiers. [UserGoal.OpenTarget.target] is the next choice. Adjustment
 * goals without either hint use small residual normalized concept/direction sets so non-LM callers
 * can still plan known goals. Multiple candidates are always clarified; only clickable elements
 * can become Click decisions.
 *
 * Off-screen reachability is intentionally unresolved: this planner returns
 * [PlanResult.NotOnThisScreen] and never presses Back/Home or launches an Intent. Choosing between
 * accessibility HOME + launcher navigation and an explicitly labelled Intent fallback remains an
 * open architecture/mentor decision.
 *
 * The planner is stateless, deterministic, thread-safe, and safe on any dispatcher. It performs no
 * I/O, Android/accessibility operation, coroutine, cancellation, timer, or expected failure work.
 */
class KeywordGoalPlanner(
    private val textMatcher: TextMatcher = TextMatcher(),
) : GoalPlanner {

    /**
     * Maps one goal to the current screen without speculative navigation.
     *
     * @param goal high-level goal from deterministic or LM interpretation.
     * @param screen immutable current snapshot.
     * @return safe current-screen planning result.
     *
     * This pure function performs normalized in-memory matching only, no I/O or suspension, and has
     * no expected failure for ordinary goals and snapshots.
     */
    override fun plan(goal: UserGoal, screen: ScreenSnapshot): PlanResult =
        when (goal) {
            is UserGoal.OpenTarget -> planOpenTarget(goal, screen)
            is UserGoal.AdjustTextSize ->
                planAdjustment(
                    goal,
                    screen,
                    TEXT_SIZE_KEYWORDS,
                    textDirectionKeywords(goal.direction),
                )
            is UserGoal.AdjustVolume ->
                planAdjustment(goal, screen, VOLUME_KEYWORDS, volumeDirectionKeywords(goal.direction))
            is UserGoal.AdjustBrightness ->
                planAdjustment(
                    goal,
                    screen,
                    BRIGHTNESS_KEYWORDS,
                    brightnessDirectionKeywords(goal.direction),
                )
            UserGoal.GoBack -> PlanResult.Actionable(Decision.Back)
            UserGoal.Stop -> PlanResult.Actionable(Decision.Speak(STOP_RESPONSE))
            UserGoal.Unknown -> PlanResult.Unsupported
        }

    /**
     * Grounds the preferred control query or explicit target through [TextMatcher].
     *
     * @param goal target-opening goal.
     * @param screen immutable current snapshot.
     * @return click, matcher clarification, blank-target clarification, or off-screen result.
     *
     * This pure helper performs no I/O or suspension and cannot fail for ordinary values.
     */
    private fun planOpenTarget(
        goal: UserGoal.OpenTarget,
        screen: ScreenSnapshot,
    ): PlanResult {
        val query = goal.controlQuery ?: goal.target
        if (TextNormalizer.normalize(query).isEmpty()) {
            return PlanResult.NeedsClarification(OPEN_TARGET_CLARIFICATION)
        }
        return groundQuery(query, goal, screen)
    }

    /**
     * Grounds one semantic description through the existing tiered matcher.
     *
     * @param query model-provided control description or target text.
     * @param goal understood goal retained for an honest off-screen result.
     * @param screen immutable current snapshot.
     * @return one safe click, matcher-provided clarification, or NotOnThisScreen.
     *
     * This pure helper normalizes only through [TextMatcher], performs no I/O or suspension, and
     * cannot execute an action or expose a provider-selected index.
     */
    private fun groundQuery(
        query: String,
        goal: UserGoal,
        screen: ScreenSnapshot,
    ): PlanResult =
        when (val decision = textMatcher.match(query, screen)) {
            is Decision.Click -> PlanResult.Actionable(decision)
            is Decision.Speak -> PlanResult.NeedsClarification(decision.text)
            Decision.NoMatch -> PlanResult.NotOnThisScreen(goal)
            is Decision.SetText,
            Decision.Back,
            -> PlanResult.Unsupported
        }

    /**
     * Selects direction-specific or concept-level controls for an adjustment goal.
     *
     * @param goal adjustment goal retained if no control is present.
     * @param screen immutable current snapshot.
     * @param conceptKeywords normalized aliases naming the setting category.
     * @param directionKeywords normalized phrases naming the requested direction.
     * @return one click, option-naming clarification, or honest off-screen result.
     *
     * This pure helper compares only shared-normalizer keys, performs no I/O or suspension, and has
     * no expected failure for ordinary snapshot data.
     */
    private fun planAdjustment(
        goal: UserGoal,
        screen: ScreenSnapshot,
        conceptKeywords: Set<String>,
        directionKeywords: Set<String>,
    ): PlanResult {
        goal.controlQuery
            ?.takeIf { query -> TextNormalizer.normalize(query).isNotEmpty() }
            ?.let { query -> return groundQuery(query, goal, screen) }

        val candidates =
            screen.elements
                .asSequence()
                .filter(ScreenElement::clickable)
                .map { element ->
                    NormalizedCandidate(element, TextNormalizer.normalize(element.text))
                }.filter { candidate -> candidate.normalizedText.isNotEmpty() }
                .toList()
        val conceptCandidates =
            candidates.filter { candidate ->
                conceptKeywords.any(candidate.normalizedText::contains)
            }
        val directionalCandidates =
            conceptCandidates.filter { candidate ->
                directionKeywords.any(candidate.normalizedText::contains)
            }
        val selectedTier = directionalCandidates.ifEmpty { conceptCandidates }
        return when (selectedTier.size) {
            0 -> PlanResult.NotOnThisScreen(goal)
            1 -> PlanResult.Actionable(Decision.Click(selectedTier.single().element.i))
            else ->
                PlanResult.NeedsClarification(
                    ambiguityQuestion(selectedTier.map(NormalizedCandidate::element)),
                )
        }
    }

    /**
     * Names all equally plausible on-screen controls instead of selecting the first.
     *
     * @param elements two or more clickable candidates from one planning tier.
     * @return Mandarin clarification distinguishing normalized duplicates by ordinal or listing text.
     *
     * This pure formatter performs no raw comparisons, I/O, or suspension and cannot fail for
     * planner-produced candidates.
     */
    private fun ambiguityQuestion(elements: List<ScreenElement>): String {
        val labels = elements.map { element -> element.text.trim() }
        val normalizedLabels = labels.map(TextNormalizer::normalize)
        return if (labels.size == 2 && normalizedLabels.distinct().size == 1) {
            "我看到兩個『${labels.first()}』，你要第一個還是第二個？"
        } else {
            "我看到${labels.joinToString("、") { label -> "『$label』" }}，你要哪一個？"
        }
    }

    /**
     * Returns normalized text-size direction phrases.
     *
     * @param direction requested increase/decrease.
     * @return immutable shared-normalizer keyword set.
     *
     * This pure lookup performs no I/O, suspension, or failure-prone work.
     */
    private fun textDirectionKeywords(direction: Direction): Set<String> =
        when (direction) {
            Direction.Increase -> TEXT_SIZE_INCREASE_KEYWORDS
            Direction.Decrease -> TEXT_SIZE_DECREASE_KEYWORDS
        }

    /**
     * Returns normalized volume direction phrases.
     *
     * @param direction requested increase/decrease.
     * @return immutable shared-normalizer keyword set.
     *
     * This pure lookup performs no I/O, suspension, or failure-prone work.
     */
    private fun volumeDirectionKeywords(direction: Direction): Set<String> =
        when (direction) {
            Direction.Increase -> VOLUME_INCREASE_KEYWORDS
            Direction.Decrease -> VOLUME_DECREASE_KEYWORDS
        }

    /**
     * Returns normalized brightness direction phrases.
     *
     * @param direction requested increase/decrease.
     * @return immutable shared-normalizer keyword set.
     *
     * This pure lookup performs no I/O, suspension, or failure-prone work.
     */
    private fun brightnessDirectionKeywords(direction: Direction): Set<String> =
        when (direction) {
            Direction.Increase -> BRIGHTNESS_INCREASE_KEYWORDS
            Direction.Decrease -> BRIGHTNESS_DECREASE_KEYWORDS
        }

    /** Immutable candidate beside its one shared-normalizer comparison key. */
    private data class NormalizedCandidate(
        val element: ScreenElement,
        val normalizedText: String,
    )

    /** Stable planner responses and small residual non-LM grounding keyword sets. */
    private companion object {
        const val STOP_RESPONSE = "已停止目前的操作。"
        const val OPEN_TARGET_CLARIFICATION = "你想開啟哪個功能？"

        fun normalizedKeywords(vararg values: String): Set<String> =
            values.mapTo(linkedSetOf(), TextNormalizer::normalize)

        val TEXT_SIZE_KEYWORDS =
            normalizedKeywords("文字大小", "字型大小", "font size")
        val TEXT_SIZE_INCREASE_KEYWORDS =
            normalizedKeywords("放大", "增加", "increase")
        val TEXT_SIZE_DECREASE_KEYWORDS =
            normalizedKeywords("縮小", "減少", "decrease")

        val VOLUME_KEYWORDS = normalizedKeywords("音量", "volume")
        val VOLUME_INCREASE_KEYWORDS =
            normalizedKeywords("調高", "增加", "volume up")
        val VOLUME_DECREASE_KEYWORDS =
            normalizedKeywords("調低", "減少", "volume down")

        val BRIGHTNESS_KEYWORDS = normalizedKeywords("亮度", "brightness")
        val BRIGHTNESS_INCREASE_KEYWORDS =
            normalizedKeywords("調亮", "增加", "increase")
        val BRIGHTNESS_DECREASE_KEYWORDS =
            normalizedKeywords("調暗", "減少", "decrease")
    }
}

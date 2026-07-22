package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.config.FeatureFlags
import com.foxconn.seeandsay.normalization.TextNormalizer
import kotlinx.coroutines.CancellationException

/**
 * LM-first decision composition with deterministic matching retained as a failure fallback.
 *
 * @param lmEnabled primary semantic path gate; defaults to [FeatureFlags.LM_ENABLED], now true.
 * @param intentInterpreterFactory lazy provider-neutral interpreter factory, invoked only when an
 * enabled non-empty utterance first needs interpretation.
 * @param textMatcher local goal-grounding dependency and deterministic provider-failure fallback.
 * @param goalPlanner local index-selection planner using only the current snapshot.
 *
 * Every enabled non-empty utterance reaches the interpreter before raw-transcript matching.
 * Resolved goals are grounded locally; clarification is returned directly. Interpreter NoMatch or
 * any non-cancellation factory/provider failure falls back to deterministic matching. With the flag
 * false, no provider is constructed and the same fallback serves direct commands. Every actionable
 * output passes [LocalDecisionValidator]. [CancellationException] propagates unchanged. No action
 * is executed and no success is claimed here.
 */
class DefaultDecisionEngine(
    private val lmEnabled: Boolean = FeatureFlags.LM_ENABLED,
    private val intentInterpreterFactory: (() -> IntentInterpreter)? = null,
    private val textMatcher: TextMatcher = TextMatcher(),
    private val goalPlanner: GoalPlanner = KeywordGoalPlanner(textMatcher),
) : DecisionEngine {

    /** Lazily constructed only for the first enabled, normalized non-empty utterance. */
    private val intentInterpreter: IntentInterpreter? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (lmEnabled) intentInterpreterFactory?.invoke() else null
    }

    /**
     * Produces one validated action, clarification, honest limitation, or no-match.
     *
     * @param transcript raw utterance normalized before interpretation or fallback matching.
     * @param screen exact immutable snapshot used through matching, planning, and validation.
     * @return safe [Decision] containing no invalid action index.
     *
     * The enabled primary interpreter may suspend. NoMatch and normal factory/provider failures
     * enter deterministic fallback; cancellation is rethrown without a user-visible conversion.
     * Blank-after-normalization input returns NoMatch without provider construction or matching.
     */
    override suspend fun decide(transcript: String, screen: ScreenSnapshot): Decision {
        val normalizedTranscript = TextNormalizer.normalize(transcript)
        if (normalizedTranscript.isEmpty()) return Decision.NoMatch
        if (!lmEnabled) return deterministicFallback(normalizedTranscript, screen)
        val interpreter =
            try {
                intentInterpreter
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return deterministicFallback(normalizedTranscript, screen)
            } ?: return deterministicFallback(normalizedTranscript, screen)

        val interpreted =
            try {
                interpreter.interpret(normalizedTranscript, screen)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return deterministicFallback(normalizedTranscript, screen)
            }
        return when (interpreted) {
            is IntentResult.Resolved -> plan(interpreted.goal, screen)
            is IntentResult.Clarify -> Decision.Speak(interpreted.question)
            IntentResult.NoMatch -> deterministicFallback(normalizedTranscript, screen)
        }
    }

    /**
     * Applies raw-command matching only when the primary LM path is disabled or unavailable.
     *
     * @param normalizedTranscript non-empty shared-normalizer output.
     * @param screen exact immutable snapshot used for matching and final validation.
     * @return validated deterministic decision, ambiguity question, or no-match.
     *
     * This pure synchronous helper performs no provider construction, I/O, suspension, logging, or
     * action execution. Invalid actionable output becomes NoMatch through local validation.
     */
    private fun deterministicFallback(
        normalizedTranscript: String,
        screen: ScreenSnapshot,
    ): Decision =
        when (val deterministic = textMatcher.match(normalizedTranscript, screen)) {
            is Decision.Click,
            is Decision.SetText,
            Decision.Back,
            -> LocalDecisionValidator.validate(deterministic, screen)
            is Decision.Speak -> deterministic
            Decision.NoMatch -> Decision.NoMatch
        }

    /**
     * Maps local planning output to an honest validated Decision.
     *
     * @param goal schema-validated high-level goal.
     * @param screen exact current snapshot used by the planner and validator.
     * @return validated action, clarification/limitation speech, or no-match.
     *
     * This pure helper performs no I/O, suspension, speculative navigation, or failure-prone work.
     */
    private fun plan(goal: UserGoal, screen: ScreenSnapshot): Decision =
        when (val planned = goalPlanner.plan(goal, screen)) {
            is PlanResult.Actionable -> LocalDecisionValidator.validate(planned.decision, screen)
            is PlanResult.NeedsClarification -> Decision.Speak(planned.question)
            is PlanResult.NotOnThisScreen -> Decision.Speak(offScreenMessage(planned.goal))
            PlanResult.Unsupported -> Decision.NoMatch
        }

    /**
     * Builds a truthful response for an understood goal unavailable on this screen.
     *
     * @param goal goal retained by [PlanResult.NotOnThisScreen].
     * @return fixed Mandarin limitation naming only safe goal text/category.
     *
     * This pure formatter performs no navigation, I/O, suspension, or raw text comparison and has
     * no expected failure.
     */
    private fun offScreenMessage(goal: UserGoal): String =
        when (goal) {
            is UserGoal.OpenTarget ->
                "目前畫面找不到「${goal.target.trim()}」，請先切換到包含這個功能的畫面。"
            is UserGoal.AdjustTextSize -> "目前畫面沒有可用的文字大小控制項。"
            is UserGoal.AdjustVolume -> "目前畫面沒有可用的音量控制項。"
            is UserGoal.AdjustBrightness -> "目前畫面沒有可用的亮度控制項。"
            UserGoal.GoBack -> "目前畫面無法規劃返回操作。"
            UserGoal.Stop -> "目前沒有可停止的畫面操作。"
            UserGoal.Unknown -> "我目前無法確認要執行的操作。"
        }
}

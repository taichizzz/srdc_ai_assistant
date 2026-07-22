package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies conservative pure goal-to-current-screen planning on the JVM.
 *
 * Tests use immutable snapshots only and perform no Android, accessibility, LM, network, coroutine,
 * timer, delay, filesystem, coordinate action, or external I/O work.
 */
class GoalPlannerTest {

    /** Pure planner shared by independent immutable test cases. */
    private val planner = KeywordGoalPlanner()

    /**
     * Verifies OpenTarget reuses TextMatcher aliases and emits the current clickable index.
     *
     * @return no value; assertion failure reports matcher/planner composition drift.
     *
     * This pure test runs synchronously without I/O, suspension, timers, or cancellation behavior.
     */
    @Test
    fun openTargetUsesTieredMatcher() {
        val result = planner.plan(UserGoal.OpenTarget("Settings"), screen(element(0, "設定")))

        assertEquals(PlanResult.Actionable(Decision.Click(0)), result)
    }

    /**
     * Verifies a model control query takes precedence and is grounded by tiered TextMatcher logic.
     *
     * @return no value; assertion failure reports target-first or residual-keyword grounding.
     *
     * This pure test performs no LM call, I/O, suspension, timer, or cancellation work.
     */
    @Test
    fun controlQueryTakesPrecedenceAndGroundsThroughTextMatcher() {
        val result =
            planner.plan(
                UserGoal.OpenTarget(target = "系統設定", controlQuery = "Music"),
                screen(element(0, "設定"), element(1, "音樂")),
            )

        assertEquals(PlanResult.Actionable(Decision.Click(1)), result)
    }

    /**
     * Verifies adjustment grounding uses a model query outside the residual keyword vocabulary.
     *
     * @return no value; assertion failure reports bypassing model-supplied semantic grounding.
     *
     * This pure test performs no LM call, I/O, suspension, timer, or cancellation work.
     */
    @Test
    fun adjustmentControlQueryGroundsWithoutResidualKeywordKnowledge() {
        val result =
            planner.plan(
                UserGoal.AdjustTextSize(Direction.Increase, controlQuery = "字體縮放選項"),
                screen(element(0, "字體縮放選項")),
            )

        assertEquals(PlanResult.Actionable(Decision.Click(0)), result)
    }

    /**
     * Verifies direction-specific text-size controls outrank broader concept-level controls.
     *
     * @return no value; assertion failure reports incorrect keyword-tier precedence.
     *
     * This pure test performs normalized in-memory matching only, no I/O or suspension.
     */
    @Test
    fun textSizeDirectionSpecificControlWins() {
        val result =
            planner.plan(
                UserGoal.AdjustTextSize(Direction.Increase),
                screen(
                    element(0, "文字大小"),
                    element(1, "字型大小放大"),
                ),
            )

        assertEquals(PlanResult.Actionable(Decision.Click(1)), result)
    }

    /**
     * Verifies volume and brightness Chinese/English keyword aliases select matching controls.
     *
     * @return no value; assertion failure reports missing adjustment keyword coverage.
     *
     * This pure test performs no I/O, coroutine, cancellation, timer, or delay work.
     */
    @Test
    fun volumeAndBrightnessKeywordsPlanCurrentControls() {
        assertEquals(
            PlanResult.Actionable(Decision.Click(0)),
            planner.plan(
                UserGoal.AdjustVolume(Direction.Decrease),
                screen(element(0, "降低音量")),
            ),
        )
        assertEquals(
            PlanResult.Actionable(Decision.Click(0)),
            planner.plan(
                UserGoal.AdjustBrightness(Direction.Increase),
                screen(element(0, "Brightness increase")),
            ),
        )
    }

    /**
     * Verifies equally plausible controls produce a question naming every option.
     *
     * @return no value; assertion failure reports first-candidate guessing or missing labels.
     *
     * This pure test runs synchronously without I/O, suspension, timers, or cancellation behavior.
     */
    @Test
    fun equallyPlausibleAdjustmentControlsNeedClarification() {
        val result =
            planner.plan(
                UserGoal.AdjustTextSize(Direction.Increase),
                screen(
                    element(0, "字型大小放大"),
                    element(1, "文字大小增加"),
                ),
            )

        assertTrue(result is PlanResult.NeedsClarification)
        val question = (result as PlanResult.NeedsClarification).question
        assertTrue(question.contains("字型大小放大"))
        assertTrue(question.contains("文字大小增加"))
    }

    /**
     * Verifies an understood absent target remains off-screen and never becomes an action.
     *
     * @return no value; assertion failure reports speculative navigation or fabricated index.
     *
     * This pure test performs no Android action, I/O, coroutine, timer, or delay work.
     */
    @Test
    fun absentUnderstoodGoalIsNotOnThisScreen() {
        val goal = UserGoal.OpenTarget("導航")

        assertEquals(
            PlanResult.NotOnThisScreen(goal),
            planner.plan(goal, screen(element(0, "設定"))),
        )
    }

    /**
     * Verifies Back, Stop, and Unknown retain their explicit non-guessing semantics.
     *
     * @return no value; assertion failure reports unsupported goal fabrication.
     *
     * This pure test performs no I/O, suspension, cancellation, timer, or delay work.
     */
    @Test
    fun backStopAndUnknownMapExplicitly() {
        val screen = screen(element(0, "設定"))

        assertEquals(PlanResult.Actionable(Decision.Back), planner.plan(UserGoal.GoBack, screen))
        assertEquals(
            PlanResult.Actionable(Decision.Speak("已停止目前的操作。")),
            planner.plan(UserGoal.Stop, screen),
        )
        assertEquals(PlanResult.Unsupported, planner.plan(UserGoal.Unknown, screen))
    }

    /**
     * Builds one immutable screen from ordered test elements.
     *
     * @param elements current-screen elements available to the planner.
     * @return deterministic pure snapshot.
     *
     * This helper performs no I/O, coroutine, timer, delay, or failure-prone work.
     */
    private fun screen(vararg elements: ScreenElement): ScreenSnapshot =
        ScreenSnapshot("test", 1L, elements.toList())

    /**
     * Builds one clickable test element with a snapshot-local index and label.
     *
     * @param index sequential element identifier.
     * @param text visible label normalized only by production logic.
     * @return immutable clickable element without operational coordinates.
     *
     * This helper performs no I/O, coroutine, timer, delay, or failure-prone work.
     */
    private fun element(index: Int, text: String): ScreenElement =
        ScreenElement(index, text, clickable = true, editable = false, bounds = emptyList())
}

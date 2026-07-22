package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies pure action-expectation derivation and before/after snapshot comparison on the JVM.
 *
 * Tests use immutable values only and perform no Android accessibility work, I/O, event listening,
 * waiting, delay, timer, coroutine, device, network, or filesystem operation.
 */
class ActionVerificationTest {

    /**
     * Verifies action decisions derive typed expectations while non-actions derive none.
     *
     * @return no value; assertion failure reports incorrect action/expectation mapping.
     *
     * This pure test runs synchronously without I/O, waiting, timers, or cancellation behavior.
     */
    @Test
    fun decisionsDeriveOnlyApplicableExpectations() {
        val before = snapshot("home", element(7, "設定", clickable = true))

        assertEquals(
            ActionExpectation.ScreenChanged(7, "設定"),
            expectationFor(Decision.Click(7), before),
        )
        assertEquals(
            ActionExpectation.TextEntered(8, "Roxanne"),
            expectationFor(Decision.SetText(8, "Roxanne"), before),
        )
        assertEquals(ActionExpectation.NavigatedBack, expectationFor(Decision.Back, before))
        assertNull(expectationFor(Decision.Speak("請選擇"), before))
        assertNull(expectationFor(Decision.NoMatch, before))
        assertNull(expectationFor(Decision.Click(99), before))
    }

    /**
     * Verifies a normalized screen identity change proves a dispatched click changed the screen.
     *
     * @return no value; assertion failure reports missing click verification evidence.
     *
     * This pure test performs in-memory comparison only, no I/O, waiting, or timer work.
     */
    @Test
    fun clickWithChangedScreenIsVerified() {
        val before = snapshot("home", element(0, "設定", clickable = true))
        val after = snapshot("settings", element(0, "顯示", clickable = true))

        assertEquals(
            VerificationResult.Verified,
            verify(before, after, ActionExpectation.ScreenChanged(0, "設定")),
        )
    }

    /**
     * Verifies an effectively identical usable snapshot is failed rather than reported successful.
     *
     * @return no value; assertion failure reports fire-and-forget click semantics.
     *
     * This pure test performs no I/O, accessibility work, waiting, timer, or cancellation.
     */
    @Test
    fun clickWithIdenticalSnapshotsIsNotVerified() {
        val before =
            snapshot(
                "home",
                element(0, "設定", clickable = true),
                element(1, "音樂", clickable = true),
            )

        val result = verify(before, before.copy(capturedAt = 99L), expectationFor(Decision.Click(0), before)!!)

        assertEquals(
            VerificationResult.NotVerified("The click produced no observable screen change."),
            result,
        )
    }

    /**
     * Verifies disappearance of the normalized clicked label is sufficient click evidence.
     *
     * @return no value; assertion failure reports ignored clicked-element disappearance.
     *
     * This pure test performs deterministic in-memory work only, no I/O or waiting.
     */
    @Test
    fun clickWhoseElementDisappearedIsVerified() {
        val before =
            snapshot(
                "home",
                element(0, "設定", clickable = true),
                element(1, "音樂", clickable = true),
            )
        val after = snapshot("home", element(1, "音樂", clickable = true))

        assertEquals(
            VerificationResult.Verified,
            verify(before, after, ActionExpectation.ScreenChanged(0, " 設定！ ")),
        )
    }

    /**
     * Verifies a material element-set change proves a click even when its label remains visible.
     *
     * @return no value; assertion failure reports missing unordered-set evidence.
     *
     * This pure test performs no I/O, event waiting, timer, or coroutine work.
     */
    @Test
    fun clickWithMaterialElementChangeIsVerified() {
        val before = snapshot("home", element(0, "設定", clickable = true))
        val after =
            snapshot(
                "home",
                element(9, "設定", clickable = true),
                element(10, "顯示", clickable = true),
            )

        assertEquals(
            VerificationResult.Verified,
            verify(before, after, ActionExpectation.ScreenChanged(0, "設定")),
        )
    }

    /**
     * Verifies normalized expected text can appear at the edit index with compatibility noise.
     *
     * @return no value; assertion failure reports raw-string text-entry comparison.
     *
     * This pure test performs no I/O, accessibility work, waiting, timer, or coroutine work.
     */
    @Test
    fun setTextWithNormalizedContainedValueIsVerified() {
        val before = snapshot("settings", element(3, "", editable = true))
        val after = snapshot("settings", element(3, "姓名： Ｒｏｘａｎｎｅ！", editable = true))

        assertEquals(
            VerificationResult.Verified,
            verify(before, after, ActionExpectation.TextEntered(3, " Roxanne ")),
        )
    }

    /**
     * Verifies text may move to another element while wrong/unchanged content fails verification.
     *
     * @return no value; assertion failure reports fabricated edit success or index-only matching.
     *
     * This pure test performs deterministic normalized comparison only, no I/O or waiting.
     */
    @Test
    fun setTextFindsMovedValueButRejectsWrongOrUnchangedText() {
        val before = snapshot("settings", element(3, "原名稱", editable = true))
        val moved =
            snapshot(
                "settings",
                element(3, "", editable = true),
                element(4, "Roxanne", editable = false),
            )
        val wrong = snapshot("settings", element(3, "別的名稱", editable = true))

        assertEquals(
            VerificationResult.Verified,
            verify(before, moved, ActionExpectation.TextEntered(3, "Roxanne")),
        )
        assertEquals(
            VerificationResult.NotVerified(
                "The expected text change was not observable after editing.",
            ),
            verify(before, wrong, ActionExpectation.TextEntered(3, "Roxanne")),
        )
        assertEquals(
            VerificationResult.NotVerified(
                "The expected text change was not observable after editing.",
            ),
            verify(before, before, ActionExpectation.TextEntered(3, "Roxanne")),
        )
        val alreadyPresent = snapshot("settings", element(3, "Roxanne", editable = true))
        assertEquals(
            VerificationResult.NotVerified(
                "The expected text change was not observable after editing.",
            ),
            verify(
                alreadyPresent,
                alreadyPresent.copy(capturedAt = 2L),
                ActionExpectation.TextEntered(3, "Roxanne"),
            ),
        )
    }

    /**
     * Verifies Back requires normalized identity or material element-set change.
     *
     * @return no value; assertion failure reports incorrect backward-navigation evidence.
     *
     * This pure test performs no I/O, event listening, waiting, timer, or cancellation.
     */
    @Test
    fun backChangedIsVerifiedAndBackUnchangedIsNotVerified() {
        val before = snapshot("settings", element(0, "顯示", clickable = true))
        val changed = snapshot("home", element(0, "設定", clickable = true))

        assertEquals(
            VerificationResult.Verified,
            verify(before, changed, ActionExpectation.NavigatedBack),
        )
        assertEquals(
            VerificationResult.NotVerified("Back produced no observable screen change."),
            verify(before, before.copy(capturedAt = 5L), ActionExpectation.NavigatedBack),
        )
    }

    /**
     * Verifies an empty after-snapshot is inconclusive even when screen identity differs.
     *
     * @return no value; assertion failure reports a failed read being treated as action failure.
     *
     * This pure test performs no I/O, event listening, waiting, timer, or coroutine work.
     */
    @Test
    fun emptyAfterSnapshotIsInconclusive() {
        val before = snapshot("home", element(0, "設定", clickable = true))
        val unusableAfter = snapshot("settings")

        assertEquals(
            VerificationResult.Inconclusive("The after-snapshot contains no usable elements."),
            verify(before, unusableAfter, ActionExpectation.ScreenChanged(0, "設定")),
        )
    }

    /**
     * Verifies full-width/case/spacing and traversal-only differences do not count as real change.
     *
     * @return no value; assertion failure reports unstable or raw snapshot equality.
     *
     * This pure test performs shared-normalizer comparison only, no I/O, waiting, or timer work.
     */
    @Test
    fun normalizedNoiseOrderingAndIndicesDoNotCountAsChange() {
        val before =
            snapshot(
                "ＨＯＭＥ",
                element(0, " Settings! ", clickable = true),
                element(1, "ＭＵＳＩＣ", clickable = true),
            )
        val after =
            snapshot(
                "home",
                element(44, "music", clickable = true, bounds = listOf(4, 3, 2, 1)),
                element(55, "settings", clickable = true, bounds = listOf(9, 9, 9, 9)),
            )

        assertEquals(
            VerificationResult.NotVerified("Back produced no observable screen change."),
            verify(before, after, ActionExpectation.NavigatedBack),
        )
        assertEquals(
            VerificationResult.NotVerified("The click produced no observable screen change."),
            verify(before, after, ActionExpectation.ScreenChanged(0, "settings")),
        )
    }

    /**
     * Builds one immutable snapshot with deterministic timestamp for comparison tests.
     *
     * @param screen raw identity normalized only by the verifier.
     * @param elements ordered values; order intentionally has no verification meaning.
     * @return immutable pure snapshot.
     *
     * This pure helper performs no I/O, waiting, timer, or failure-prone work.
     */
    private fun snapshot(
        screen: String,
        vararg elements: ScreenElement,
    ): ScreenSnapshot = ScreenSnapshot(screen, 1L, elements.toList())

    /**
     * Builds one immutable screen element for comparison tests.
     *
     * @param index unstable snapshot-local identifier.
     * @param text raw visible text normalized only by production comparison.
     * @param clickable activation capability.
     * @param editable edit capability.
     * @param bounds layout metadata intentionally ignored by verification.
     * @return immutable pure element value.
     *
     * This pure helper performs no I/O, waiting, timer, or failure-prone work.
     */
    private fun element(
        index: Int,
        text: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: List<Int> = listOf(0, 0, 1, 1),
    ): ScreenElement = ScreenElement(index, text, clickable, editable, bounds)
}

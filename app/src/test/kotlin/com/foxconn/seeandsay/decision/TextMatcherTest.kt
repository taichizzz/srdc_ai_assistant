package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.FakeUiBridge
import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.normalization.TextNormalizer
import com.foxconn.seeandsay.pipeline.RuleBasedReplyEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies Phase 1 deterministic matching and the shared normalization boundary on the JVM.
 *
 * Tests execute synchronously with immutable snapshots and an in-memory fake. They perform no
 * Android, device, network, filesystem, coroutine, or dispatcher work and fail through assertions.
 */
class TextMatcherTest {

    /** Stateless pure matcher shared by test cases. */
    private val matcher = TextMatcher()

    /**
     * Verifies one exact normalized label selects its stable snapshot index.
     *
     * @return no value; assertion failure reports an incorrect decision.
     *
     * This test runs synchronously without I/O, threading, or cancellation behavior.
     */
    @Test
    fun exactNormalizedMatchClicksRightIndex() {
        assertEquals(
            Decision.Click(1),
            matcher.match("音樂", FakeUiBridge.realisticHomeScreen()),
        )
    }

    /**
     * Verifies punctuation, spacing, and full-width compatibility forms do not alter matching.
     *
     * @return no value; assertion failure reports normalization drift.
     *
     * This test is pure and synchronous, performs no I/O, and owns no coroutine resources.
     */
    @Test
    fun matchingSurvivesNoiseAndFullWidthVariants() {
        val screen =
            snapshot(
                ScreenElement(7, "設定", clickable = true, editable = false, emptyList()),
                ScreenElement(8, "ＡＢＣ", clickable = true, editable = false, emptyList()),
            )

        assertEquals(Decision.Click(7), matcher.match(" 設定! ", screen))
        assertEquals(Decision.Click(8), matcher.match("abc", screen))
    }

    /**
     * Verifies a longer command can select the one clickable label it contains.
     *
     * @return no value; assertion failure reports a missing substring fallback.
     *
     * This test is pure and synchronous with no I/O, dispatcher, or cancellation behavior.
     */
    @Test
    fun substringFallbackMatchesContainedLabel() {
        assertEquals(
            Decision.Click(0),
            matcher.match("打開設定", FakeUiBridge.realisticHomeScreen()),
        )
    }

    /**
     * Verifies exact normalized matches win before broader substring candidates are considered.
     *
     * @return no value; assertion failure reports incorrect matching-tier precedence.
     *
     * This test runs synchronously with pure values and no I/O or cancellation behavior.
     */
    @Test
    fun exactMatchTakesPrecedenceOverSubstringCandidates() {
        val screen =
            snapshot(
                ScreenElement(5, "設定", clickable = true, editable = false, emptyList()),
                ScreenElement(6, "設定選單", clickable = true, editable = false, emptyList()),
            )

        assertEquals(Decision.Click(5), matcher.match("設定", screen))
    }

    /**
     * Verifies duplicate clickable labels ask for clarification instead of guessing.
     *
     * @return no value; assertion failure reports ambiguity suppression or incorrect wording.
     *
     * This test executes pure in-memory matching with no I/O or concurrency behavior.
     */
    @Test
    fun duplicateLabelsSpeakAmbiguityInsteadOfClicking() {
        val screen =
            snapshot(
                ScreenElement(3, "設定", clickable = true, editable = false, emptyList()),
                ScreenElement(9, "設定", clickable = true, editable = false, emptyList()),
            )

        assertEquals(Decision.Speak("有兩個『設定』，你要哪一個？"), matcher.match("設定", screen))
    }

    /**
     * Verifies an absent label returns an explicit no-match result.
     *
     * @return no value; assertion failure reports a fabricated screen action.
     *
     * This test runs synchronously without I/O, threading, or cancellation behavior.
     */
    @Test
    fun absentLabelReturnsNoMatch() {
        assertEquals(Decision.NoMatch, matcher.match("電話", FakeUiBridge.realisticHomeScreen()))
    }

    /**
     * Verifies visible text without click capability can never produce a click.
     *
     * @return no value; assertion failure reports an unsafe non-actionable match.
     *
     * This test is pure and synchronous with no Android, I/O, or coroutine dependency.
     */
    @Test
    fun nonClickableTextIsNeverClicked() {
        val screen =
            snapshot(ScreenElement(4, "設定", clickable = false, editable = false, emptyList()))

        assertEquals(Decision.NoMatch, matcher.match("設定", screen))
    }

    /**
     * Verifies matcher and M1.3 reply rules consume the same promoted normalizer behavior.
     *
     * @return no value; assertion failure reports a duplicate or divergent normalization boundary.
     *
     * This test is pure and synchronous and performs no I/O, threading, or cancellation work.
     */
    @Test
    fun replyEngineAndMatcherShareOneNormalizer() {
        val noisyGreeting = " ＨＥＬＬＯ，　"
        val normalizedGreeting = TextNormalizer.normalize(noisyGreeting)
        val screen =
            snapshot(ScreenElement(12, "hello", clickable = true, editable = false, emptyList()))

        assertEquals("hello", normalizedGreeting)
        assertEquals(Decision.Click(12), matcher.match(noisyGreeting, screen))
        assertEquals(
            "你好，我是AI 助理 Roxanne，很高興為你服務。",
            RuleBasedReplyEngine().replyTo(noisyGreeting),
        )
    }

    /**
     * Builds one immutable test snapshot from supplied elements.
     *
     * @param elements ordered element values to expose to the matcher.
     * @return deterministic snapshot with a stable test timestamp.
     *
     * This pure helper performs no I/O, is safe on any dispatcher, and has no expected failure.
     */
    private fun snapshot(vararg elements: ScreenElement): ScreenSnapshot =
        ScreenSnapshot("test", 1L, elements.toList())
}

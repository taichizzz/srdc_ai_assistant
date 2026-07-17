package com.foxconn.seeandsay.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies deterministic M1.3 reply rules and reusable transcript normalization.
 *
 * Tests execute pure JVM string operations with no Android runtime, coroutine, network, credential,
 * microphone, or speaker dependency. Every assertion completes synchronously and owns no resource.
 */
class RuleBasedReplyEngineTest {

    /** Rule engine shared by immutable, side-effect-free test cases. */
    private val engine = RuleBasedReplyEngine()

    /**
     * Verifies Chinese and English greeting variants select the required greeting reply.
     *
     * @return This test has no return value.
     *
     * The test runs synchronously and fails if spacing, punctuation, casing, or full-width noise
     * prevents a greeting match. It performs no I/O and has no cancellation behavior.
     */
    @Test
    fun greetingVariantsReturnRequiredReply() {
        val expected = "你好，我是 IVI AI 助理，很高興為你服務。"
        val variants = listOf("你好", " 你好! ", "哈囉。", "hello", "HELLO!", " Ｈｅｌｌｏ！ ")

        variants.forEach { transcript ->
            assertEquals(expected, engine.replyTo(transcript))
        }
    }

    /**
     * Verifies the optional identity rule remains deterministic after punctuation cleanup.
     *
     * @return This test has no return value.
     *
     * The test performs synchronous in-memory matching only and fails if the preset identity reply
     * or normalization boundary changes. It owns no coroutine or external resource.
     */
    @Test
    fun identityQuestionReturnsSelfIntroduction() {
        assertEquals(
            "我是 IVI AI 助理，可以協助你操作車載系統。",
            engine.replyTo(" 你 是 誰？ "),
        )
    }

    /**
     * Verifies unmatched input produces an acknowledgment containing the user's transcript.
     *
     * @return This test has no return value.
     *
     * The test executes synchronously without I/O and fails if unknown input becomes silent, loses
     * its trimmed transcript, or accidentally matches a preset rule.
     */
    @Test
    fun unmatchedTranscriptReturnsFallbackContainingTranscript() {
        val transcript = "請播放我最喜歡的歌"

        val reply = engine.replyTo("  $transcript  ")

        assertEquals("我聽到了：『$transcript』，但我還不知道怎麼回應。", reply)
        assertTrue(reply.contains(transcript))
    }

    /**
     * Verifies every supported input shape produces non-blank speech text.
     *
     * @return This test has no return value.
     *
     * The pure synchronous loop includes empty, whitespace-only, punctuation-only, matched, and
     * unknown inputs. It fails if any path violates the ReplyEngine non-silence contract.
     */
    @Test
    fun repliesAreNeverBlank() {
        val transcripts = listOf("", "   ", "？！", "你好", "unknown command")

        transcripts.forEach { transcript ->
            assertTrue(engine.replyTo(transcript).isNotBlank())
        }
    }

    /**
     * Verifies NFKC, Unicode whitespace/punctuation removal, and locale-stable lowercase behavior.
     *
     * @return This test has no return value.
     *
     * The test performs pure JVM normalization and fails if full-width Latin, ideographic spaces,
     * Chinese punctuation, or ASCII spacing survives the reusable comparison boundary.
     */
    @Test
    fun normalizationProducesStableComparisonKeys() {
        assertEquals("helloivi", TranscriptNormalizer.normalize("  ＨＥＬＬＯ，　IVI！ "))
        assertEquals("你好", TranscriptNormalizer.normalize("\u00A0你 好？！\u3000"))
        assertEquals("", TranscriptNormalizer.normalize(" ！？... "))
    }
}

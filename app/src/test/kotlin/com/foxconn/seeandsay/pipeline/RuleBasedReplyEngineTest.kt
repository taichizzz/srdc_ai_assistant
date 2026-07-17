package com.foxconn.seeandsay.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies contextual deterministic M1.3 replies, bounded memory, and transcript normalization.
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
        val variants = listOf(" 你 是 誰？ ", "你叫什麼？", "你叫什麼名字")

        variants.forEach { transcript ->
            assertEquals(
                "我是 IVI AI 助理，可以協助你操作車載系統。",
                engine.replyTo(transcript),
            )
        }
    }

    /**
     * Verifies help and gratitude synonyms produce useful deterministic replies.
     *
     * @return This test has no return value.
     *
     * The test performs pure synchronous matching and fails if common local phrases need an LM or
     * return silence. It owns no coroutine, network, microphone, or speaker resource.
     */
    @Test
    fun helpAndThanksSynonymsReturnUsefulReplies() {
        val expectedHelp =
            "你可以說「你好」、「你是誰」、「打開設定」、「播放音樂」、「再說一次」或「取消」。"

        listOf("幫助", "你可以做什麼？", "HELP!").forEach { transcript ->
            assertEquals(expectedHelp, engine.replyTo(transcript))
        }
        listOf("謝謝", "感謝！", "thanks").forEach { transcript ->
            assertEquals("不客氣，很高興能幫助你。", engine.replyTo(transcript))
        }
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
        val transcript = "今天天氣如何"

        val reply = engine.replyTo("  $transcript  ")

        assertEquals(
            "我聽到了：『$transcript』。目前我可以處理簡單指令；你可以說「幫助」查看範例。",
            reply,
        )
        assertTrue(reply.contains(transcript))
    }

    /**
     * Verifies open/play synonyms extract a target and retain it without claiming execution.
     *
     * @return This test has no return value.
     *
     * The test uses injected in-memory context and pure string matching. It fails if polite command
     * variants lose their target, fabricate success, or require Android/network work.
     */
    @Test
    fun targetRequestsExtractAndRememberTheirTarget() {
        val memory = ConversationMemory()
        val contextualEngine = RuleBasedReplyEngine(memory)

        assertEquals(
            "收到，你想開啟「設定」。目前尚未連接畫面操作，請稍後再試。",
            contextualEngine.replyTo("請幫我打開設定！"),
        )
        assertEquals("設定", memory.snapshot().lastRequestedTarget)
        assertEquals(
            "收到，你想播放「音樂」。目前尚未連接畫面操作，請稍後再試。",
            contextualEngine.replyTo("請幫忙播放音樂"),
        )
        assertEquals("音樂", memory.snapshot().lastRequestedTarget)
    }

    /**
     * Verifies an action verb without a target asks a concrete clarification question.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and fails if incomplete commands are treated as unknown, become
     * silent, or invent a target. It performs no I/O and has no cancellation behavior.
     */
    @Test
    fun missingTargetRequestsClarification() {
        val expected = "請告訴我要開啟或播放哪個功能，例如「打開設定」或「播放音樂」。"

        assertEquals(expected, engine.replyTo("請幫我打開"))
        assertEquals(expected, engine.replyTo("播放"))
    }

    /**
     * Verifies repeat speaks the previous reply and handles an empty history explicitly.
     *
     * @return This test has no return value.
     *
     * Both engines use only synchronized process-local memory. The test fails if repeat races,
     * returns blank, or fabricates prior context; it owns no coroutine or external resource.
     */
    @Test
    fun repeatUsesTheMostRecentReplyOrReportsNoHistory() {
        val contextualEngine = RuleBasedReplyEngine()
        val greeting = contextualEngine.replyTo("你好")

        assertEquals(greeting, contextualEngine.replyTo("再說一次"))
        assertEquals("目前沒有可以重複的回覆。", RuleBasedReplyEngine().replyTo("重複一次"))
    }

    /**
     * Verifies conversational cancel clears a pending target without claiming coroutine control.
     *
     * @return This test has no return value.
     *
     * The test performs atomic in-memory operations only and fails if cancellation leaves a stale
     * target or claims work existed on a second request. It has no coroutine cancellation itself.
     */
    @Test
    fun cancelClearsOnlyTheRememberedTarget() {
        val memory = ConversationMemory()
        val contextualEngine = RuleBasedReplyEngine(memory)
        contextualEngine.replyTo("進入導航")

        assertEquals("已取消目前記住的操作要求。", contextualEngine.replyTo("取消"))
        assertNull(memory.snapshot().lastRequestedTarget)
        assertEquals("目前沒有等待中的操作要求。", contextualEngine.replyTo("算了"))
    }

    /**
     * Verifies conversation memory retains required fields within a fixed text bound.
     *
     * @return This test has no return value.
     *
     * The pure synchronous test records no real UI action; it explicitly exercises the future
     * post-verification hook and fails if unbounded text or fabricated defaults enter the snapshot.
     */
    @Test
    fun conversationMemoryIsBoundedAndStoresVerifiedActionsExplicitly() {
        val memory = ConversationMemory()
        memory.rememberExchange("你好", "回覆")
        memory.recordVerifiedAction("已開啟設定")
        memory.rememberRequestedTarget("目".repeat(700))

        val snapshot = memory.snapshot()
        assertEquals("你好", snapshot.lastTranscript)
        assertEquals("回覆", snapshot.lastReply)
        assertEquals("已開啟設定", snapshot.lastSuccessfulAction)
        assertEquals(500, snapshot.lastRequestedTarget?.length)
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
        val transcripts =
            listOf("", "   ", "？！", "你好", "幫助", "打開", "打開設定", "unknown command")

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

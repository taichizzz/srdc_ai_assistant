package com.foxconn.seeandsay.pipeline

/**
 * Generates deterministic Mandarin replies from a small local rule set.
 *
 * The engine normalizes every transcript with [TranscriptNormalizer] before matching. Greetings
 * receive the M1.3 demo response, identity questions receive a short self-introduction, and every
 * other input receives a fixed acknowledgment containing the trimmed original transcript. It never
 * returns silence and performs no LM, Android, network, file, microphone, or speaker work.
 *
 * The class is immutable and safe to share across callers. [replyTo] is synchronous and safe on any
 * dispatcher; it creates no coroutine, has no cancellation behavior, and exposes no domain failure.
 */
class RuleBasedReplyEngine : ReplyEngine {

    /**
     * Selects a deterministic reply after normalizing the transcript comparison key.
     *
     * @param transcript final user transcript, which may include punctuation/spacing noise or be
     * blank.
     * @return a non-blank greeting, self-introduction, or fallback acknowledgment.
     *
     * This function performs only synchronous in-memory normalization and matching on the caller's
     * thread. It never suspends, launches work, performs I/O, or owns cancellation cleanup, and
     * ordinary string input cannot produce a recoverable domain error.
     */
    override fun replyTo(transcript: String): String {
        val normalizedTranscript = TranscriptNormalizer.normalize(transcript)
        return when (normalizedTranscript) {
            in GREETING_KEYS -> GREETING_REPLY
            IDENTITY_QUESTION_KEY -> IDENTITY_REPLY
            else -> fallbackReply(transcript)
        }
    }

    /**
     * Builds the guaranteed non-blank response for unknown or empty input.
     *
     * @param transcript unmatched original transcript.
     * @return acknowledgment containing the trimmed original text between Chinese quote marks.
     *
     * The function is synchronous and allocation-only, is safe on any dispatcher, performs no I/O,
     * has no cancellation behavior, and has no expected failure for ordinary strings.
     */
    private fun fallbackReply(transcript: String): String =
        "我聽到了：『${transcript.trim()}』，但我還不知道怎麼回應。"

    /** Stable local rules and responses shared by every engine instance. */
    private companion object {

        /** Normalized greeting keys accepted by the M1.3 preset rule. */
        val GREETING_KEYS = setOf("你好", "哈囉", "hello")

        /** Normalized identity-question key accepted by the demo-friendly secondary rule. */
        const val IDENTITY_QUESTION_KEY = "你是誰"

        /** Required greeting response spoken by the later M1.3 pipeline. */
        const val GREETING_REPLY = "你好，我是 IVI AI 助理，很高興為你服務。"

        /** Deterministic response for a direct assistant identity question. */
        const val IDENTITY_REPLY = "我是 IVI AI 助理，可以協助你操作車載系統。"
    }
}

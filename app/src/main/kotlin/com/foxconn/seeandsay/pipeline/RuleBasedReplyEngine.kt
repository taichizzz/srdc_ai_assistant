package com.foxconn.seeandsay.pipeline

/**
 * Generates contextual deterministic Mandarin replies from a local intent vocabulary.
 *
 * The engine normalizes every transcript with [TranscriptNormalizer], classifies greetings,
 * identity/help/thanks, repeat/cancel, and simple open/play target requests, then emits a useful
 * response. Unknown and missing-target input receive guidance instead of silence. [memory] retains
 * only bounded process-local context and never claims a requested UI action succeeded.
 *
 * @param memory thread-safe bounded context used for repeat, cancel, targets, and future verified
 * action descriptions.
 *
 * The class serializes reply decisions so a repeat cannot race the exchange it references. It is
 * safe to share across callers and dispatchers; [replyTo] creates no coroutine, performs no I/O,
 * has no cancellation behavior, and exposes no domain failure for ordinary string input.
 */
class RuleBasedReplyEngine(
    private val memory: ConversationMemory = ConversationMemory(),
) : ReplyEngine {

    /** Monitor making classification, contextual lookup, and memory publication one atomic turn. */
    private val replyLock = Any()

    /**
     * Selects a deterministic reply after normalizing the transcript comparison key.
     *
     * @param transcript final user transcript, which may include punctuation/spacing noise or be
     * blank.
     * @return a non-blank contextual rule response or useful fallback guidance.
     *
     * This function performs only synchronous in-memory normalization and matching on the caller's
     * thread. It never suspends, launches work, performs I/O, or owns cancellation cleanup, and
     * ordinary string input cannot produce a recoverable domain error.
     */
    override fun replyTo(transcript: String): String =
        synchronized(replyLock) {
            val intent =
                LocalReplyIntentClassifier.classify(TranscriptNormalizer.normalize(transcript))
            val reply = replyFor(intent, transcript)
            memory.rememberExchange(transcript, reply)
            reply
        }

    /**
     * Maps one classified local intent to a reply and applies its bounded memory transition.
     *
     * @param intent deterministic meaning produced from the normalized transcript.
     * @param transcript original transcript retained only for a readable unknown fallback.
     * @return guaranteed non-blank response for the intent.
     *
     * This helper runs while [replyLock] is held, performs synchronous in-memory work only, launches
     * no coroutine, and has no cancellation or ordinary domain-failure behavior.
     */
    private fun replyFor(
        intent: LocalReplyIntent,
        transcript: String,
    ): String =
        when (intent) {
            LocalReplyIntent.Greeting -> GREETING_REPLY
            LocalReplyIntent.Identity -> IDENTITY_REPLY
            LocalReplyIntent.Help -> HELP_REPLY
            LocalReplyIntent.Thanks -> THANKS_REPLY
            LocalReplyIntent.Repeat -> memory.snapshot().lastReply ?: NOTHING_TO_REPEAT_REPLY
            LocalReplyIntent.Cancel -> cancelReply()
            LocalReplyIntent.MissingTarget -> MISSING_TARGET_REPLY
            LocalReplyIntent.Empty -> EMPTY_TRANSCRIPT_REPLY
            is LocalReplyIntent.TargetRequest -> targetRequestReply(intent)
            LocalReplyIntent.Unknown -> unknownReply(transcript)
        }

    /**
     * Clears the pending conversational target and reports whether anything was cancelled.
     *
     * @return deterministic cancellation response; it never claims to stop an active coroutine.
     *
     * This synchronous memory update runs under [replyLock], performs no I/O, and has no coroutine
     * or cancellation behavior despite interpreting the user's conversational cancel intent.
     */
    private fun cancelReply(): String =
        if (memory.clearRequestedTarget()) {
            CANCELLED_TARGET_REPLY
        } else {
            NOTHING_TO_CANCEL_REPLY
        }

    /**
     * Remembers a parsed target and acknowledges it without claiming screen execution.
     *
     * @param request parsed open/play action plus normalized target.
     * @return accurate capability-boundary response containing the target.
     *
     * The synchronous helper updates bounded memory only. It performs no screen action, I/O,
     * coroutine launch, suspension, or cancellation work and cannot fail for classifier output.
     */
    private fun targetRequestReply(request: LocalReplyIntent.TargetRequest): String {
        memory.rememberRequestedTarget(request.target)
        val action =
            when (request.action) {
                LocalTargetAction.Open -> "開啟"
                LocalTargetAction.Play -> "播放"
            }
        return "收到，你想$action「${request.target}」。目前尚未連接畫面操作，請稍後再試。"
    }

    /**
     * Builds a useful fallback for unmatched non-blank input.
     *
     * @param transcript unmatched original transcript.
     * @return acknowledgment preserving trimmed text plus a discoverable help instruction.
     *
     * The function performs synchronous string allocation only, no I/O or suspension, and has no
     * cancellation or expected failure behavior for ordinary input.
     */
    private fun unknownReply(transcript: String): String =
        "我聽到了：『${transcript.trim()}』。目前我可以處理簡單指令；你可以說「幫助」查看範例。"

    /** Stable local rules and responses shared by every engine instance. */
    private companion object {

        /** Required greeting response spoken by the later M1.3 pipeline. */
        const val GREETING_REPLY = "你好，我是AI 助理 Roxanne，很高興為你服務。"

        /** Deterministic response for a direct assistant identity question. */
        const val IDENTITY_REPLY = "我是AI 助理 Roxanne，可以協助你操作車載系統。"

        /** Discoverable list of local commands available before screen reading/operation. */
        const val HELP_REPLY =
            "你可以說「你好」、「你是誰」、「打開設定」、「播放音樂」、「再說一次」或「取消」。"

        /** Courteous response for locally recognized gratitude. */
        const val THANKS_REPLY = "不客氣，很高興能幫助你。"

        /** Clarification used when an open/play verb has no target. */
        const val MISSING_TARGET_REPLY =
            "請告訴我要開啟或播放哪個功能，例如「打開設定」或「播放音樂」。"

        /** Guidance for blank, punctuation-only, or otherwise inaudible final text. */
        const val EMPTY_TRANSCRIPT_REPLY = "我沒有聽清楚。你可以說「你好」、「幫助」或「打開設定」。"

        /** Response when repeat is requested before any assistant reply exists. */
        const val NOTHING_TO_REPEAT_REPLY = "目前沒有可以重複的回覆。"

        /** Response confirming that a remembered target was cleared. */
        const val CANCELLED_TARGET_REPLY = "已取消目前記住的操作要求。"

        /** Response when cancel has no pending conversational target to clear. */
        const val NOTHING_TO_CANCEL_REPLY = "目前沒有等待中的操作要求。"
    }
}

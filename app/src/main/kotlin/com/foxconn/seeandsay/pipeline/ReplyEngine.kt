package com.foxconn.seeandsay.pipeline

/**
 * Converts one final speech transcript into assistant reply text.
 *
 * Implementations must always return non-blank text and must not perform speech recognition,
 * synthesis, UI actions, network access, or other side effects. A future LM-backed implementation
 * may implement this contract behind `LM_ENABLED`; the deterministic rule engine remains the
 * default while that flag is false.
 *
 * Implementations are synchronous and may be called from any dispatcher. The contract creates no
 * coroutine, has no cancellation behavior, and defines no recoverable domain exception.
 */
interface ReplyEngine {

    /**
     * Returns the assistant's spoken reply for one final transcript.
     *
     * @param transcript final user transcript; it may be empty, noisy, or unmatched.
     * @return non-blank reply text suitable for a later [com.foxconn.seeandsay.speech.TtsClient].
     *
     * Implementations perform synchronous in-memory work on the caller's thread, do not suspend,
     * and own no cancellable resource. Ordinary string input must never cause a domain failure.
     */
    fun replyTo(transcript: String): String
}

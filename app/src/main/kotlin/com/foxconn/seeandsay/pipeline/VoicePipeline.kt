package com.foxconn.seeandsay.pipeline

import com.foxconn.seeandsay.speech.TtsClient
import kotlinx.coroutines.CancellationException

/**
 * Composes deterministic reply generation with contract-compliant speech playback for M1.3.
 *
 * @param replyEngine pure transcript-to-reply decision boundary.
 * @param ttsClient provider-neutral speech client that suspends until playback completes.
 *
 * The caller owns STT capture and must invoke [replyAndSpeak] only after microphone release. This
 * class then keeps reply generation and TTS behind the architecture's `pipeline/` boundary instead
 * of allowing UI code to call speech and decision components independently. One invocation produces
 * exactly one reply and one TTS request. It performs no open-mic or automatic re-listen behavior.
 */
class VoicePipeline(
    private val replyEngine: ReplyEngine,
    private val ttsClient: TtsClient,
) : AutoCloseable {

    /**
     * Generates and speaks exactly one response for a completed final-transcript session.
     *
     * @param transcript complete non-blank transcript for one finished STT or typed-input session.
     * @param onReplyReady synchronous observer invoked once after generation and immediately before
     * TTS begins; callers use it to expose `Speaking` and the reply text.
     * @return the same non-blank reply after [TtsClient.speak] completes playback.
     * @throws IllegalArgumentException when [transcript] is blank.
     * @throws IllegalStateException when an injected ReplyEngine violates its non-blank contract.
     * @throws Throwable when reply generation, the observer, synthesis, fallback, or playback fails.
     *
     * The suspend function runs on the caller's coroutine context. Reply generation and observer
     * notification are synchronous; TTS may switch dispatchers internally. Cancellation propagates
     * unchanged so the active TtsClient must stop playback promptly and no user error is fabricated.
     */
    suspend fun replyAndSpeak(
        transcript: String,
        onReplyReady: (String) -> Unit,
    ): String {
        require(transcript.isNotBlank()) { "A completed voice-loop transcript must not be blank." }
        val reply = replyEngine.replyTo(transcript)
        check(reply.isNotBlank()) { "ReplyEngine returned blank speech text." }
        onReplyReady(reply)
        try {
            ttsClient.speak(reply)
        } catch (error: CancellationException) {
            throw error
        }
        return reply
    }

    /**
     * Releases the owned TTS implementation when it exposes lifecycle cleanup.
     *
     * @return This function has no return value.
     * @throws Exception when the owned closeable reports an unexpected cleanup failure.
     *
     * The synchronous call performs no coroutine launch and is intended after the owner has
     * cancelled active pipeline work. Non-closeable fakes require no cleanup; repeated safety is
     * delegated to the concrete client's documented idempotent close behavior.
     */
    override fun close() {
        (ttsClient as? AutoCloseable)?.close()
    }
}

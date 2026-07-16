package com.foxconn.seeandsay.speech

/**
 * Supplies deterministic provider-neutral speech behavior to TtsViewModel unit tests.
 *
 * @param speakAction suspend action invoked for each requested text; defaults to immediate success.
 *
 * Construction and request recording perform no platform, audio, credential, or network I/O.
 * Suspension, failure, and cancellation behavior are defined by [speakAction] and propagate
 * unchanged. Tests should confine mutable [requests] access to their controlled dispatcher.
 */
class FakeTtsClient(
    private val speakAction: suspend (String) -> Unit = {},
) : TtsClient {

    /** Ordered text values received by this fake during the current test. */
    val requests: MutableList<String> = mutableListOf()

    /**
     * Records one request and delegates its completion/failure behavior to the injected action.
     *
     * @param text normalized plain text supplied by TtsViewModel.
     * @return when [speakAction] completes successfully.
     *
     * The suspend function runs on the caller's test coroutine context, performs no I/O itself,
     * and propagates injected failure or cancellation without wrapping it.
     */
    override suspend fun speak(text: String) {
        requests += text
        speakAction(text)
    }
}

package com.foxconn.seeandsay.speech

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes each new utterance to one of several provider-neutral TTS clients.
 *
 * @param initialClient client used until an automatic-reply or DEBUG UI selection changes.
 * @param clients complete set of clients this router owns and may select.
 *
 * Selection is thread-safe and affects only calls that have not started; an in-flight [speak]
 * snapshots its client so a concurrent selection cannot split synthesis from playback. [close]
 * disposes each identity-distinct owned client once. Client failures and coroutine cancellation
 * propagate unchanged to the existing fallback and ViewModel layers.
 */
class SwitchableTtsClient(
    initialClient: TtsClient,
    clients: Collection<TtsClient>,
) : TtsClient,
    AutoCloseable {

    /** Identity-distinct clients retained for validation and lifecycle disposal. */
    private val ownedClients: List<TtsClient> =
        buildList {
            (clients + initialClient).forEach { candidate ->
                if (none { existing -> existing === candidate }) add(candidate)
            }
        }

    /** Atomically selected client snapshotted at the beginning of each utterance. */
    private val selectedClient = AtomicReference(initialClient)

    /** Thread-safe lifecycle flag preventing selection or speech after disposal. */
    private val isClosed = AtomicBoolean(false)

    /**
     * Selects the client that will handle the next utterance.
     *
     * @param client provider-neutral client already included in the owned client collection.
     * @return This function has no return value.
     * @throws IllegalArgumentException when the client is not owned by this router.
     * @throws IllegalStateException after [close].
     *
     * The synchronous operation is safe on any thread, performs no audio/network I/O, and owns no
     * coroutine. It deliberately does not cancel an active utterance; the UI disables selection
     * while Speaking and normal Stop/replacement remains the cancellation authority.
     */
    fun select(client: TtsClient) {
        check(!isClosed.get()) { CLIENT_CLOSED_MESSAGE }
        require(ownedClients.any { it === client }) { UNKNOWN_CLIENT_MESSAGE }
        selectedClient.set(client)
    }

    /**
     * Speaks through the client selected at this call's start.
     *
     * @param text caller-provided text passed unchanged to the selected client.
     * @return only after the selected client completes playback.
     * @throws IllegalStateException after [close], or any selected-client synthesis/playback error.
     *
     * The suspend function inherits the selected client's dispatcher and structured-cancellation
     * behavior. Selection changes after the snapshot affect only later calls.
     */
    override suspend fun speak(text: String) {
        check(!isClosed.get()) { CLIENT_CLOSED_MESSAGE }
        selectedClient.get().speak(text)
    }

    /**
     * Closes all identity-distinct owned clients exactly once.
     *
     * @return This function has no return value.
     * @throws Throwable after attempting every client when one or more close operations fail.
     *
     * The synchronous call is thread-safe and non-suspending. Active callers must first be
     * cancelled by their lifecycle owner; each compliant closeable client then stops its RPC/audio
     * resources without logging text, audio, or credentials.
     */
    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        var firstFailure: Throwable? = null
        ownedClients.forEach { client ->
            try {
                (client as? AutoCloseable)?.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    private companion object {
        /** Fixed non-secret message for use after router disposal. */
        const val CLIENT_CLOSED_MESSAGE = "Text-to-speech model selection is closed."

        /** Fixed programming-error message for a client outside the owned selector set. */
        const val UNKNOWN_CLIENT_MESSAGE = "The selected TTS client is not registered."
    }
}

package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.FeatureFlags
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides cloud-first speech with an on-device fallback behind CLOUD_TTS_ENABLED.
 *
 * @param cloudClient contract-compliant cloud synthesis/playback client.
 * @param deviceClient Android on-device fallback client.
 * @param engineReporter best-effort observer used for credential-free diagnostic logging.
 * @param isCloudEnabled flag source; production reads FeatureFlags and tests inject a constant.
 *
 * When enabled, [speak] tries cloud once and falls back only for recoverable [CloudTtsException].
 * When disabled, it performs no cloud call. Cancellation always propagates and never starts device
 * speech. [close] disposes both owned clients. No provider or Android type escapes TtsClient.
 */
class FallbackTtsClient(
    private val cloudClient: TtsClient,
    private val deviceClient: TtsClient,
    private val engineReporter: (TtsPlaybackEngine) -> Unit = {},
    private val isCloudEnabled: () -> Boolean = { FeatureFlags.CLOUD_TTS_ENABLED },
) : TtsClient,
    AutoCloseable {

    /** Thread-safe lifecycle flag preventing work after both clients are disposed. */
    private val isClosed = AtomicBoolean(false)

    /** Mutable route backing state; no client, text, audio, or credential escapes through it. */
    private val mutablePlaybackEngine = MutableStateFlow(TtsPlaybackEngine.NotUsed)

    /**
     * Exposes the active or most recently attempted cloud/device route.
     *
     * @return hot provider-neutral state beginning with [TtsPlaybackEngine.NotUsed].
     *
     * Collection is safe on any dispatcher and may be cancelled without affecting active speech.
     * Reading performs no I/O and cannot fail; the StateFlow owns no independent coroutine.
     */
    val playbackEngine: StateFlow<TtsPlaybackEngine> = mutablePlaybackEngine.asStateFlow()

    /**
     * Speaks with cloud when enabled and falls back once to the on-device client on cloud failure.
     *
     * @param text non-blank text passed unchanged to the selected client.
     * @return only after the successful cloud or device client's playback completes.
     * @throws Throwable when device playback fails, the client is closed, or a non-cloud programming
     * failure escapes the cloud client.
     *
     * The function runs on the caller's coroutine context and delegates dispatcher work. Structured
     * cancellation propagates immediately, never triggers fallback, and must stop the active client.
     */
    override suspend fun speak(text: String) {
        if (isClosed.get()) {
            throw CloudTtsException(CloudTtsFailureReason.ClientClosed, CLIENT_CLOSED_MESSAGE)
        }
        if (!isCloudEnabled()) {
            reportEngine(TtsPlaybackEngine.OnDevice)
            deviceClient.speak(text)
            return
        }
        try {
            reportEngine(TtsPlaybackEngine.Cloud)
            cloudClient.speak(text)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CloudTtsException) {
            reportEngine(TtsPlaybackEngine.OnDevice)
            deviceClient.speak(text)
        }
    }

    /**
     * Publishes and reports one actual route attempt without risking speech completion.
     *
     * @param engine Cloud or on-device route about to receive the utterance.
     * @return This function has no return value.
     *
     * The synchronous operation is safe on the caller's dispatcher and performs no suspension.
     * StateFlow publication happens before the attempt; diagnostic callback failure is swallowed so
     * logging can never interrupt synthesis, playback, fallback, or structured cancellation.
     */
    private fun reportEngine(engine: TtsPlaybackEngine) {
        mutablePlaybackEngine.value = engine
        runCatching { engineReporter(engine) }
    }

    /**
     * Disposes cloud and device resources exactly once.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, synchronous, and non-suspending. Both closeable clients
     * are attempted even if the first unexpectedly fails; the first cleanup error then propagates.
     * Active owners must cancel their speak coroutine before calling close.
     */
    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        var firstFailure: Throwable? = null
        try {
            (cloudClient as? AutoCloseable)?.close()
        } catch (error: Throwable) {
            firstFailure = error
        }
        try {
            (deviceClient as? AutoCloseable)?.close()
        } catch (error: Throwable) {
            if (firstFailure == null) firstFailure = error
        }
        firstFailure?.let { throw it }
    }

    private companion object {
        /** Fixed non-secret use-after-disposal message. */
        const val CLIENT_CLOSED_MESSAGE = "Text-to-speech is no longer available."
    }
}

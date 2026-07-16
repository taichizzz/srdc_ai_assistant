package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.FeatureFlags
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Provides cloud-first speech with an on-device fallback behind CLOUD_TTS_ENABLED.
 *
 * @param cloudClient contract-compliant cloud synthesis/playback client.
 * @param deviceClient Android on-device fallback client.
 * @param isCloudEnabled flag source; production reads FeatureFlags and tests inject a constant.
 *
 * When enabled, [speak] tries cloud once and falls back only for recoverable [CloudTtsException].
 * When disabled, it performs no cloud call. Cancellation always propagates and never starts device
 * speech. [close] disposes both owned clients. No provider or Android type escapes TtsClient.
 */
class FallbackTtsClient(
    private val cloudClient: TtsClient,
    private val deviceClient: TtsClient,
    private val isCloudEnabled: () -> Boolean = { FeatureFlags.CLOUD_TTS_ENABLED },
) : TtsClient,
    AutoCloseable {

    /** Thread-safe lifecycle flag preventing work after both clients are disposed. */
    private val isClosed = AtomicBoolean(false)

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
            deviceClient.speak(text)
            return
        }
        try {
            cloudClient.speak(text)
        } catch (error: CancellationException) {
            throw error
        } catch (_: CloudTtsException) {
            deviceClient.speak(text)
        }
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

package com.foxconn.seeandsay.speech

import android.content.Context
import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.BuildConfigApiKeyProvider
import com.foxconn.seeandsay.config.GcpTtsConfig
import com.foxconn.seeandsay.config.GcpTtsSynthesisProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Implements the binding TtsClient contract by synthesizing, parsing, and playing cloud speech.
 *
 * @param synthesizeAction provider-neutral Phase 3 synthesis function; tests inject a pure fake.
 * @param pcmPlayer speech-internal player that suspends through the final AudioTrack frame.
 * @param closeSynthesisAction lifecycle cleanup for the owned reusable cloud synthesis channel.
 *
 * [speak] returns only after cloud synthesis and audible PCM playback finish. WAV parsing strips all
 * RIFF/header/metadata bytes before the player sees samples. Normal cancellation propagates and
 * stops playback; cloud/parser/player failures remain typed [CloudTtsException] values for fallback.
 * [close] stops playback and closes the reusable cloud channel without exposing provider types.
 */
class CloudTtsClient internal constructor(
    private val synthesizeAction: suspend (String) -> SynthesizedAudio,
    private val pcmPlayer: TtsPcmPlayer,
    private val closeSynthesisAction: () -> Unit = {},
) : TtsClient,
    AutoCloseable {

    /** Thread-safe lifecycle state preventing work after composition-root disposal. */
    private val isClosed = AtomicBoolean(false)

    /**
     * Creates the production cloud client with an owned synthesizer and Android AudioTrack player.
     *
     * @param context Android context; only its application context reaches audio services.
     * @param accessTokenProvider provider for the bearer fallback credential.
     * @param apiKeyProvider provider for the preferred Cloud TTS API key.
     * @param synthesisProfile non-secret classic or Gemini-TTS single-speaker configuration.
     *
     * Construction creates one reusable TLS channel but performs no credential lookup, RPC,
     * synthesis, focus request, or playback. It must occur from normal Android composition. Local
     * channel/audio-service setup failures may propagate; [close] owns all created resources.
     */
    constructor(
        context: Context,
        accessTokenProvider: AccessTokenProvider,
        apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
        synthesisProfile: GcpTtsSynthesisProfile = GcpTtsConfig.WAVENET_A_PROFILE,
    ) : this(
        createProductionDependencies(
            context,
            accessTokenProvider,
            apiKeyProvider,
            synthesisProfile,
        ),
    )

    /**
     * Delegates production dependency ownership into the testable primary constructor.
     *
     * @param dependencies one synthesizer and one Android player created together.
     *
     * Construction performs no additional I/O or coroutine work. Failures are limited to dependency
     * creation by the public constructor; cancellation begins only when [speak] is called.
     */
    private constructor(dependencies: ProductionDependencies) : this(
        synthesizeAction = dependencies.synthesizer::synthesize,
        pcmPlayer = dependencies.player,
        closeSynthesisAction = dependencies.synthesizer::close,
    )

    /**
     * Synthesizes one text value, strips its WAV container, and plays PCM to completion.
     *
     * @param text non-blank plain text for Taiwan-Mandarin cloud synthesis.
     * @return only after Android reports the final PCM frame played.
     * @throws CloudTtsException for synthesis, malformed audio, focus, or playback failure.
     *
     * The function may run from any coroutine context and never blocks the caller's thread. Cloud
     * callbacks and Android work select their own contexts. Cancellation stops the RPC or AudioTrack
     * promptly and propagates unchanged so fallback never speaks after a user-requested Stop.
     */
    override suspend fun speak(text: String) {
        if (isClosed.get()) throw clientClosedFailure()
        try {
            val synthesized = synthesizeAction(text)
            val pcm = Linear16WavParser.parse(synthesized)
            pcmPlayer.play(pcm)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudTtsException) {
            throw error
        } catch (error: Throwable) {
            throw CloudTtsException(
                CloudTtsFailureReason.PlaybackFailed,
                "Cloud text-to-speech playback failed. Please retry.",
                error,
            )
        }
    }

    /**
     * Stops active PCM playback and closes the owned reusable synthesis channel.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, synchronous, and non-suspending. Player stop is
     * best-effort and channel shutdown cancels an active RPC. Cleanup never logs text/audio/secrets;
     * subsequent [speak] calls fail with a fixed recoverable ClientClosed exception.
     */
    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        pcmPlayer.stop()
        closeSynthesisAction()
    }

    /**
     * Groups resources that must share the production CloudTtsClient lifecycle.
     *
     * @property synthesizer Phase 3 unary synthesizer owning the reusable TLS channel.
     * @property player Android PCM player owning only per-call AudioTrack/focus resources.
     *
     * This passive holder performs no I/O or coroutine work and has no cancellation behavior.
     */
    private data class ProductionDependencies(
        val synthesizer: CloudTtsSynthesizer,
        val player: AudioTrackTtsPlayer,
    )

    private companion object {
        /**
         * Creates the production synthesis and playback components exactly once.
         *
         * @param context Android context used through its application context.
         * @param accessTokenProvider bearer fallback provider.
         * @param apiKeyProvider preferred API-key provider.
         * @param synthesisProfile immutable request settings owned by the synthesizer.
         * @return owned synthesizer/player pair for the client lifecycle.
         *
         * Construction is synchronous and starts no RPC/playback/coroutine. Local channel or Android
         * service initialization failures propagate to the composition root; later failures are
         * handled by [speak].
         */
        fun createProductionDependencies(
            context: Context,
            accessTokenProvider: AccessTokenProvider,
            apiKeyProvider: ApiKeyProvider,
            synthesisProfile: GcpTtsSynthesisProfile,
        ): ProductionDependencies =
            ProductionDependencies(
                synthesizer =
                    CloudTtsSynthesizer(
                        accessTokenProvider,
                        apiKeyProvider,
                        synthesisProfile,
                    ),
                player = AudioTrackTtsPlayer(context.applicationContext),
            )

        /**
         * Creates a fixed failure for calls after lifecycle disposal.
         *
         * @return recoverable ClientClosed exception containing no provider/resource detail.
         *
         * This synchronous helper performs no I/O/coroutine work and cannot be cancelled.
         */
        fun clientClosedFailure(): CloudTtsException =
            CloudTtsException(
                CloudTtsFailureReason.ClientClosed,
                "Cloud text-to-speech is no longer available.",
            )
    }
}

package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.BuildConfigApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.GcpTtsConfig
import com.foxconn.seeandsay.config.GcpTtsSynthesisProfile
import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse
import com.google.cloud.texttospeech.v1.TextToSpeechGrpc
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Synthesizes Taiwan-Mandarin speech bytes through Google Cloud Text-to-Speech V1.
 *
 * @param accessTokenProvider suspend provider for a current short-lived OAuth bearer token. It
 * takes precedence for IAM-gated Gemini-TTS profiles.
 * @param apiKeyProvider suspend provider for an optional API key. It takes precedence for classic
 * voices and remains the Gemini fallback when no bearer token is configured.
 * @param channel reusable managed channel; production owns one OkHttp TLS channel while tests inject
 * an in-process channel.
 * @param quotaProjectId optional non-secret quota project attached only to bearer requests.
 * @param synthesisProfile immutable classic-voice or Gemini-TTS single-speaker request settings.
 *
 * This component deliberately does not implement [TtsClient]: that binding contract returns only
 * after playback, so [CloudTtsClient] composes this synthesis boundary with WAV parsing and
 * AudioTrack. Each [synthesize] call performs one unary RPC and maps Google bytes/types to
 * [SynthesizedAudio]. No Google/gRPC type escapes `speech/`. Calls are safe from any coroutine
 * context, cancellation promptly cancels the RPC, and [close] releases the shared channel.
 */
class CloudTtsSynthesizer internal constructor(
    private val accessTokenProvider: AccessTokenProvider,
    private val apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
    private val channel: ManagedChannel,
    private val quotaProjectId: String?,
    private val synthesisProfile: GcpTtsSynthesisProfile = GcpTtsConfig.WAVENET_A_PROFILE,
) : AutoCloseable {

    /** Thread-safe lifecycle flag preventing calls after shared-channel disposal. */
    private val isClosed = AtomicBoolean(false)

    /**
     * Creates the production synthesizer with one TLS OkHttp channel to Google's TTS endpoint.
     *
     * @param accessTokenProvider suspend provider for a short-lived OAuth bearer token.
     * @param apiKeyProvider suspend provider for an optional debug-injected plain API key.
     * @param synthesisProfile classic WaveNet baseline or Gemini-TTS evaluation profile.
     *
     * Construction allocates a channel without starting DNS, credential lookup, or an RPC. It is
     * synchronous and non-suspending; local channel initialization failures propagate. [close]
     * owns cleanup and is safe during lifecycle cancellation.
     */
    constructor(
        accessTokenProvider: AccessTokenProvider,
        apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
        synthesisProfile: GcpTtsSynthesisProfile = GcpTtsConfig.WAVENET_A_PROFILE,
    ) : this(
        accessTokenProvider = accessTokenProvider,
        apiKeyProvider = apiKeyProvider,
        channel = createProductionChannel(),
        quotaProjectId = GcpTtsConfig.projectId,
        synthesisProfile = synthesisProfile,
    )

    /**
     * Synthesizes one non-blank text value into a provider-neutral WAV/PCM payload.
     *
     * @param text plain text encoded as UTF-8 for Google unary synthesis.
     * @return complete LINEAR16 WAV bytes and explicit 24 kHz/mono/PCM16 metadata.
     * @throws CloudTtsException for invalid text, missing/rejected credentials, quota, network,
     * timeout, empty provider audio, use after disposal, or another mapped provider failure.
     *
     * The function may run from any coroutine context and never blocks the caller's thread. It
     * performs credential lookup followed by one asynchronous unary gRPC call. Caller cancellation
     * propagates normally and promptly cancels that call without becoming a recoverable error.
     */
    suspend fun synthesize(text: String): SynthesizedAudio {
        val normalizedText = text.trim()
        validateText(normalizedText, synthesisProfile)
        if (isClosed.get()) throw clientClosedFailure()

        val credential = selectCredential()
        val request = createRequest(normalizedText, synthesisProfile)
        return executeSynthesis(request, credential)
    }

    /**
     * Cancels active calls and permanently releases the reusable managed channel.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, synchronous, and non-suspending. `shutdownNow` causes
     * active RPC continuations to finish through their normal gRPC callback; no credential, text,
     * or audio is logged during cleanup.
     */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) channel.shutdownNow()
    }

    /**
     * Selects exactly one credential according to the immutable synthesis profile.
     *
     * @return API-key or bearer credential retained only for the next unary call.
     * @throws CloudTtsException when configuration is absent or a provider cannot supply a value.
     *
     * Classic voices keep API-key-first behavior because the normal Cloud TTS path accepts the
     * restricted company key. A non-blank model name identifies the IAM-gated Gemini path, which
     * mirrors STT V2 Chirp by preferring the service-account bearer token. Exactly one credential is
     * returned; no request ever mixes an API key and Authorization header.
     *
     * The suspend function runs on the caller's coroutine and delegates any dispatcher choice to
     * the providers. Cancellation propagates unchanged; values and failures are never logged.
     */
    private suspend fun selectCredential(): UnaryCredential =
        if (synthesisProfile.modelName.isNullOrBlank()) {
            selectApiKeyFirstCredential()
        } else {
            selectBearerFirstCredential()
        }

    /**
     * Selects the classic Cloud TTS credential while preserving established API-key precedence.
     *
     * @return API key when configured, otherwise a bearer token with optional quota attribution.
     * @throws CloudTtsException when neither credential exists or a provider unexpectedly fails.
     *
     * The suspend function performs provider lookups sequentially on the caller's coroutine,
     * propagates cancellation unchanged, performs no logging, and returns exactly one credential.
     */
    private suspend fun selectApiKeyFirstCredential(): UnaryCredential =
        configuredApiKey()?.let { UnaryCredential.ApiKey(it) }
            ?: configuredBearerToken()?.let { UnaryCredential.BearerToken(it, quotaProjectId) }
            ?: throw notConfiguredFailure()

    /**
     * Selects the IAM-gated Gemini credential using the same bearer-first policy as STT V2 Chirp.
     *
     * @return bearer token when configured, otherwise the retained API-key fallback.
     * @throws CloudTtsException when neither credential exists or a provider unexpectedly fails.
     *
     * The suspend function performs provider lookups sequentially on the caller's coroutine,
     * propagates cancellation unchanged, performs no logging, and returns exactly one credential.
     */
    private suspend fun selectBearerFirstCredential(): UnaryCredential =
        configuredBearerToken()?.let { UnaryCredential.BearerToken(it, quotaProjectId) }
            ?: configuredApiKey()?.let { UnaryCredential.ApiKey(it) }
            ?: throw notConfiguredFailure()

    /**
     * Reads and normalizes the optional API key without exposing its value outside this class.
     *
     * @return trimmed non-blank API key, or `null` when the provider is intentionally unconfigured.
     * @throws CloudTtsException when the provider fails unexpectedly.
     *
     * The suspend helper delegates dispatcher choice to the provider, propagates cancellation,
     * performs no I/O itself, and never logs the credential.
     */
    private suspend fun configuredApiKey(): String? =
        try {
            apiKeyProvider.currentApiKey()?.trim()?.takeIf(String::isNotEmpty)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw credentialProviderFailure(error)
        }

    /**
     * Reads and normalizes the optional short-lived bearer token.
     *
     * @return trimmed non-blank token, or `null` for the typed not-configured condition.
     * @throws CloudTtsException when the token provider fails for another reason.
     *
     * The suspend helper delegates dispatcher choice to the provider, propagates cancellation,
     * performs no I/O itself, and never logs the token or Authorization header.
     */
    private suspend fun configuredBearerToken(): String? =
        try {
            accessTokenProvider.currentToken().trim().takeIf(String::isNotEmpty)
        } catch (_: CloudSpeechNotConfiguredException) {
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw credentialProviderFailure(error)
        }

    /**
     * Bridges one asynchronous unary gRPC call into cancellable coroutine completion.
     *
     * @param request fully configured Google request retained strictly inside this class.
     * @param credential exactly one selected credential for this call.
     * @return provider-neutral synthesized audio mapped from Google's single response.
     * @throws CloudTtsException when call startup, transport, status, or response mapping fails.
     *
     * gRPC callbacks may run on transport threads and perform only bounded mapping/resumption work.
     * Atomic terminal ownership prevents callback/cancellation races from completing twice. Caller
     * cancellation cancels the captured client call promptly and remains CancellationException.
     */
    private suspend fun executeSynthesis(
        request: SynthesizeSpeechRequest,
        credential: UnaryCredential,
    ): SynthesizedAudio =
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean(false)
            val call = AtomicReference<ClientCallStreamObserver<SynthesizeSpeechRequest>?>(null)

            continuation.invokeOnCancellation {
                if (terminal.compareAndSet(false, true)) {
                    call.get()?.cancel(CALLER_CANCEL_REASON, null)
                }
            }

            val observer =
                object : ClientResponseObserver<SynthesizeSpeechRequest, SynthesizeSpeechResponse> {
                    /**
                     * Captures the unary call so coroutine cancellation can reach gRPC promptly.
                     *
                     * @param requestStream gRPC call observer retained only for cancellation.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes it before response delivery. It is non-blocking; if cancellation
                     * won before capture, the new call is cancelled immediately.
                     */
                    override fun beforeStart(
                        requestStream: ClientCallStreamObserver<SynthesizeSpeechRequest>,
                    ) {
                        call.set(requestStream)
                        if (terminal.get() || !continuation.isActive) {
                            requestStream.cancel(CALLER_CANCEL_REASON, null)
                        }
                    }

                    /**
                     * Maps Google's unary response and resumes the suspended caller once.
                     *
                     * @param value provider response containing synthesized bytes.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes it on a callback executor. Mapping copies bytes and performs no
                     * blocking I/O. Empty audio resumes with a fixed recoverable failure.
                     */
                    override fun onNext(value: SynthesizeSpeechResponse) {
                        if (!terminal.compareAndSet(false, true)) return
                        val audio = value.audioContent.toByteArray()
                        if (audio.isEmpty()) {
                            continuation.resumeWithException(emptyAudioFailure())
                        } else {
                            continuation.resume(
                                SynthesizedAudio(
                                    bytes = audio,
                                    sampleRateHz = GcpTtsConfig.SAMPLE_RATE_HZ,
                                    channelCount = GcpTtsConfig.CHANNEL_COUNT,
                                    bitsPerSample = GcpTtsConfig.BITS_PER_SAMPLE,
                                    format = SynthesizedAudioFormat.Linear16Wav,
                                ),
                            )
                        }
                    }

                    /**
                     * Maps a non-cancellation gRPC failure to a fixed recoverable exception.
                     *
                     * @param throwable transport or provider-status failure.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes it on its callback executor. It never logs provider descriptions
                     * or metadata. A cancellation-owned terminal state ignores the late callback.
                     */
                    override fun onError(throwable: Throwable) {
                        if (terminal.compareAndSet(false, true)) {
                            continuation.resumeWithException(mapRpcFailure(throwable))
                        }
                    }

                    /**
                     * Rejects an invalid unary completion that contained no response message.
                     *
                     * @return This callback has no return value.
                     *
                     * gRPC invokes it on its callback executor. A normal response already owns the
                     * terminal flag; otherwise this resumes once with a recoverable empty-audio
                     * failure and performs no blocking or suspension.
                     */
                    override fun onCompleted() {
                        if (terminal.compareAndSet(false, true)) {
                            continuation.resumeWithException(emptyAudioFailure())
                        }
                    }
                }

            try {
                TextToSpeechGrpc
                    .newStub(channel)
                    .withCallCredentials(TtsCallCredentials(credential))
                    .withDeadlineAfter(SYNTHESIS_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .synthesizeSpeech(request, observer)
            } catch (error: Throwable) {
                if (terminal.compareAndSet(false, true)) {
                    continuation.resumeWithException(mapRpcFailure(error))
                }
            }
        }

    /**
     * Represents exactly one authentication mechanism selected before a unary call.
     *
     * Values remain private to CloudTtsSynthesizer, contain no gRPC type, perform no I/O, and own no
     * coroutine/cancellation resource. They retain credential text in memory only for call setup.
     */
    private sealed interface UnaryCredential {

        /**
         * Holds one API key sent only through `x-goog-api-key`.
         *
         * @property value non-blank API key for one RPC.
         *
         * This passive value performs no I/O and has no failure, threading, or cancellation work.
         */
        data class ApiKey(val value: String) : UnaryCredential

        /**
         * Holds one bearer token and optional non-secret quota project.
         *
         * @property value non-blank short-lived OAuth token for one RPC.
         * @property quotaProjectId optional `x-goog-user-project` value.
         *
         * This passive value performs no I/O and has no failure, threading, or cancellation work.
         */
        data class BearerToken(
            val value: String,
            val quotaProjectId: String?,
        ) : UnaryCredential
    }

    /**
     * Attaches exactly one selected credential to a unary Google TTS RPC.
     *
     * @param credential API key or bearer selection made before call construction.
     *
     * gRPC invokes [applyRequestMetadata] with its executor. The adapter never logs or exposes
     * headers; executor rejection becomes an unauthenticated call failure. It owns no coroutine.
     */
    private class TtsCallCredentials(
        private val credential: UnaryCredential,
    ) : CallCredentials() {

        /**
         * Applies mutually exclusive ASCII authentication metadata on gRPC's executor.
         *
         * @param requestInfo non-secret call information unused by this implementation.
         * @param appExecutor executor supplied by gRPC.
         * @param applier callback receiving headers or a fixed status failure.
         * @return This callback has no return value.
         *
         * The method schedules bounded non-blocking work and owns no coroutine cancellation. It
         * never logs the key, token, header, text, or audio.
         */
        override fun applyRequestMetadata(
            requestInfo: RequestInfo,
            appExecutor: Executor,
            applier: MetadataApplier,
        ) {
            try {
                appExecutor.execute {
                    val headers = Metadata()
                    when (val selected = credential) {
                        is UnaryCredential.ApiKey ->
                            headers.put(API_KEY_METADATA_KEY, selected.value)

                        is UnaryCredential.BearerToken -> {
                            headers.put(AUTHORIZATION_METADATA_KEY, "Bearer ${selected.value}")
                            selected.quotaProjectId
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?.let { headers.put(QUOTA_PROJECT_METADATA_KEY, it) }
                        }
                    }
                    applier.apply(headers)
                }
            } catch (_: RuntimeException) {
                applier.fail(Status.UNAUTHENTICATED.withDescription(CREDENTIAL_APPLICATION_MESSAGE))
            }
        }
    }

    private companion object {
        /** Standard TLS service port. */
        const val SERVICE_PORT = 443

        /** Bounded unary deadline preventing cloud synthesis from hanging indefinitely. */
        const val SYNTHESIS_DEADLINE_SECONDS = 15L

        /** Fixed non-secret message for blank synthesis input. */
        const val EMPTY_TEXT_MESSAGE = "Text-to-speech text must not be empty."

        /** Fixed non-secret message for input exceeding Google's unary text limit. */
        const val TEXT_TOO_LONG_MESSAGE = "Text-to-speech text is too long for one request."

        /** Fixed non-secret message for absent local credentials. */
        const val NOT_CONFIGURED_MESSAGE =
            "Cloud text-to-speech is not configured. Add an approved credential and retry."

        /** Fixed non-secret message for credential-provider failure. */
        const val CREDENTIAL_PROVIDER_MESSAGE =
            "Cloud text-to-speech authentication is unavailable. Refresh configuration and retry."

        /** Fixed non-secret message for use after lifecycle disposal. */
        const val CLIENT_CLOSED_MESSAGE =
            "Cloud text-to-speech is unavailable because the synthesizer has been closed."

        /** Fixed non-secret message when Google returns no synthesized bytes. */
        const val EMPTY_AUDIO_MESSAGE =
            "Cloud text-to-speech returned no audio. Please retry."

        /** Fixed gRPC metadata-application description containing no credential. */
        const val CREDENTIAL_APPLICATION_MESSAGE =
            "Cloud text-to-speech authentication metadata could not be applied."

        /** Internal cancellation reason containing no text, audio, or credential. */
        const val CALLER_CANCEL_REASON = "synthesis coroutine cancelled"

        /** ASCII metadata key for API-key mode. */
        val API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)

        /** ASCII metadata key for bearer mode. */
        val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** ASCII metadata key for optional bearer quota attribution. */
        val QUOTA_PROJECT_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-user-project", Metadata.ASCII_STRING_MARSHALLER)

        /**
         * Builds the reusable production TLS OkHttp channel.
         *
         * @return managed channel targeting `texttospeech.googleapis.com:443`.
         *
         * Construction is synchronous and performs no network call or credential access. Local
         * transport initialization failures propagate; the synthesizer owns shutdown through close.
         */
        fun createProductionChannel(): ManagedChannel =
            OkHttpChannelBuilder
                .forAddress(GcpTtsConfig.SERVICE_HOST, SERVICE_PORT)
                .useTransportSecurity()
                .build()

        /**
         * Validates normalized unary input before credential or network work starts.
         *
         * @param text trimmed caller text.
         * @param synthesisProfile selected model's explicit UTF-8 text-field limit.
         * @return This function has no return value.
         * @throws CloudTtsException when text is blank or exceeds the selected model's limit.
         *
         * This synchronous helper performs bounded UTF-8 encoding on the caller's thread and owns
         * no coroutine resource or cancellation cleanup.
         */
        fun validateText(
            text: String,
            synthesisProfile: GcpTtsSynthesisProfile,
        ) {
            if (text.isEmpty()) {
                throw CloudTtsException(CloudTtsFailureReason.InvalidInput, EMPTY_TEXT_MESSAGE)
            }
            if (text.toByteArray(Charsets.UTF_8).size > synthesisProfile.maximumTextBytes) {
                throw CloudTtsException(CloudTtsFailureReason.InvalidInput, TEXT_TOO_LONG_MESSAGE)
            }
        }

        /**
         * Creates the unary Google request from provider-neutral configuration.
         *
         * @param text validated non-blank plain text.
         * @param synthesisProfile selected classic or Gemini single-speaker configuration.
         * @return request carrying the profile plus 24 kHz LINEAR16 output settings.
         *
         * This pure builder performs no I/O/suspension and owns no cancellation resource. Invalid
         * constant/enum configuration throws synchronously before the RPC starts.
         */
        fun createRequest(
            text: String,
            synthesisProfile: GcpTtsSynthesisProfile,
        ): SynthesizeSpeechRequest {
            val inputBuilder = SynthesisInput.newBuilder().setText(text)
            synthesisProfile.prompt
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(inputBuilder::setPrompt)

            val voiceBuilder =
                VoiceSelectionParams.newBuilder()
                    .setLanguageCode(synthesisProfile.languageCode)
                    .setName(synthesisProfile.voiceName)
            // Cloud TTS uses `name` for a single Gemini speaker such as Kore. `speakerId` belongs
            // only to the multi-speaker config and would incorrectly add dialogue semantics here.
            synthesisProfile.modelName
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(voiceBuilder::setModelName)
            val audioConfig =
                AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.valueOf(GcpTtsConfig.AUDIO_ENCODING))
                    .setSampleRateHertz(GcpTtsConfig.SAMPLE_RATE_HZ)
                    .build()
            return SynthesizeSpeechRequest.newBuilder()
                .setInput(inputBuilder.build())
                .setVoice(voiceBuilder.build())
                .setAudioConfig(audioConfig)
                .build()
        }

        /**
         * Maps gRPC status to a fixed recoverable cloud TTS failure.
         *
         * @param throwable call startup, transport, or provider-status failure.
         * @return provider-neutral exception containing no provider description or credential.
         *
         * Mapping is synchronous/non-blocking and safe on any thread. Caller cancellation is handled
         * before this function; it performs no I/O or coroutine cancellation work.
         */
        fun mapRpcFailure(throwable: Throwable): CloudTtsException =
            when (Status.fromThrowable(throwable).code) {
                Status.Code.UNAUTHENTICATED ->
                    CloudTtsException(
                        CloudTtsFailureReason.Unauthenticated,
                        "Cloud text-to-speech credential was rejected. Check it and retry.",
                    )

                Status.Code.PERMISSION_DENIED ->
                    CloudTtsException(
                        CloudTtsFailureReason.PermissionDenied,
                        "Cloud text-to-speech permission was denied. Check API enablement.",
                    )

                Status.Code.RESOURCE_EXHAUSTED ->
                    CloudTtsException(
                        CloudTtsFailureReason.QuotaExceeded,
                        "Cloud text-to-speech quota was exceeded. Please retry later.",
                    )

                Status.Code.UNAVAILABLE ->
                    CloudTtsException(
                        CloudTtsFailureReason.Unavailable,
                        "Cloud text-to-speech is unreachable. Check the network and retry.",
                    )

                Status.Code.DEADLINE_EXCEEDED ->
                    CloudTtsException(
                        CloudTtsFailureReason.Timeout,
                        "Cloud text-to-speech timed out. Please retry.",
                    )

                Status.Code.INVALID_ARGUMENT ->
                    CloudTtsException(
                        CloudTtsFailureReason.InvalidInput,
                        "Cloud text-to-speech rejected the text or voice configuration.",
                    )

                else ->
                    CloudTtsException(
                        CloudTtsFailureReason.Unknown,
                        "Cloud text-to-speech failed. Please retry.",
                    )
            }

        /**
         * Creates a fixed missing-credential failure.
         *
         * @return recoverable NotConfigured exception with no credential content.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot be cancelled.
         */
        fun notConfiguredFailure(): CloudTtsException =
            CloudTtsException(CloudTtsFailureReason.NotConfigured, NOT_CONFIGURED_MESSAGE)

        /**
         * Creates a fixed credential-provider failure.
         *
         * @param cause local provider error retained only as the throwable cause.
         * @return recoverable Unauthenticated exception with a fixed non-secret message.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot be cancelled.
         */
        fun credentialProviderFailure(cause: Throwable): CloudTtsException =
            CloudTtsException(
                CloudTtsFailureReason.Unauthenticated,
                CREDENTIAL_PROVIDER_MESSAGE,
                cause,
            )

        /**
         * Creates a fixed no-audio response failure.
         *
         * @return recoverable EmptyAudio exception with no provider detail.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot be cancelled.
         */
        fun emptyAudioFailure(): CloudTtsException =
            CloudTtsException(CloudTtsFailureReason.EmptyAudio, EMPTY_AUDIO_MESSAGE)

        /**
         * Creates a fixed use-after-disposal failure.
         *
         * @return recoverable ClientClosed exception with no channel/provider detail.
         *
         * This synchronous helper performs no I/O or coroutine work and cannot be cancelled.
         */
        fun clientClosedFailure(): CloudTtsException =
            CloudTtsException(CloudTtsFailureReason.ClientClosed, CLIENT_CLOSED_MESSAGE)
    }
}

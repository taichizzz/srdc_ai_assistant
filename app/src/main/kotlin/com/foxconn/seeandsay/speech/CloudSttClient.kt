package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.BuildConfigApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.GcpSttConfig
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechGrpc
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.grpc.stub.StreamObserver
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams PCM audio to Google Cloud Speech-to-Text V1 over one reusable TLS gRPC channel.
 *
 * @param accessTokenProvider suspend provider for a current short-lived OAuth bearer token.
 * @param apiKeyProvider suspend provider for an optional plain API key, which takes precedence.
 * @param channel reusable managed channel; production uses one OkHttp TLS channel, while tests inject
 * an in-process channel through this internal constructor.
 * @param quotaProjectId optional non-secret project attached only to OAuth bearer RPCs.
 *
 * Each [stream] collection creates exactly one bidirectional RPC while reusing [channel]. gRPC and
 * protobuf callbacks/types remain inside this class and are converted to provider-neutral Flow
 * values/errors. Callback work never blocks; audio sending runs in a child coroutine with bounded
 * buffering and gRPC readiness backpressure. Cancellation cancels the RPC promptly. Call [close]
 * when the owning ViewModel/client scope is disposed to shut down the shared channel.
 */
class CloudSttClient internal constructor(
    private val accessTokenProvider: AccessTokenProvider,
    private val apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
    private val channel: ManagedChannel,
    private val quotaProjectId: String?,
) : SttClient,
    AutoCloseable {

    private val isClosed = AtomicBoolean(false)

    /**
     * Creates the production client with one TLS OkHttp channel to Google's speech endpoint.
     *
     * @param accessTokenProvider suspend provider for a short-lived OAuth bearer token.
     * @param apiKeyProvider suspend provider for an optional debug-injected plain API key.
     *
     * Construction allocates the reusable channel but performs no RPC or credential lookup. Channel
     * setup is thread-safe and non-suspending; DNS/TLS work begins only when a Flow is collected.
     * Setup may throw a platform/runtime initialization failure. [close] owns channel cleanup.
     */
    constructor(
        accessTokenProvider: AccessTokenProvider,
        apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
    ) : this(
        accessTokenProvider = accessTokenProvider,
        apiKeyProvider = apiKeyProvider,
        channel = createProductionChannel(),
        quotaProjectId = GcpSttConfig.projectId,
    )

    /**
     * Creates a cold provider-neutral recognition stream for one microphone/audio session.
     *
     * @param audio PCM16 mono 16 kHz chunks; collection starts only after authentication and config.
     * @return cold Flow of ordered interim and final [SttResult] values.
     *
     * Missing configuration and mapped gRPC status failures terminate the Flow with
     * [CloudSttException]. Input audio failures propagate unchanged. Collection may occur on any
     * coroutine context; gRPC callbacks are bridged by `callbackFlow`, the sender is a structured
     * child, and collector cancellation cancels both sender and RPC without surfacing a user error.
     */
    override fun stream(audio: Flow<ByteArray>): Flow<SttResult> =
        callbackFlow {
            if (isClosed.get()) {
                close(
                    CloudSttException(
                        CloudSttFailureReason.Unknown,
                        CLIENT_CLOSED_MESSAGE,
                    ),
                )
                return@callbackFlow
            }

            val apiKey =
                try {
                    apiKeyProvider.currentApiKey()?.trim()?.takeIf(String::isNotEmpty)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    close(
                        CloudSttException(
                            CloudSttFailureReason.Unauthenticated,
                            CREDENTIAL_PROVIDER_MESSAGE,
                        ),
                    )
                    return@callbackFlow
                }

            val credential =
                if (apiKey != null) {
                    StreamCredential.ApiKey(apiKey)
                } else {
                    val token =
                        try {
                            accessTokenProvider.currentToken()
                        } catch (error: CloudSpeechNotConfiguredException) {
                            close(
                                CloudSttException(
                                    CloudSttFailureReason.NotConfigured,
                                    CLOUD_NOT_CONFIGURED_MESSAGE,
                                ),
                            )
                            return@callbackFlow
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            close(
                                CloudSttException(
                                    CloudSttFailureReason.Unauthenticated,
                                    CREDENTIAL_PROVIDER_MESSAGE,
                                ),
                            )
                            return@callbackFlow
                        }
                    StreamCredential.BearerToken(token, quotaProjectId)
                }

            val readinessSignals = Channel<Unit>(Channel.CONFLATED)
            val rpcFinished = CompletableDeferred<Unit>()
            val cancelledByCollector = AtomicBoolean(false)
            var clientCall: ClientCallStreamObserver<StreamingRecognizeRequest>? = null
            var audioSender: Job? = null

            val responseObserver =
                object :
                    ClientResponseObserver<
                        StreamingRecognizeRequest,
                        StreamingRecognizeResponse,
                    > {
                    /**
                     * Captures the cancellable client call and installs non-blocking readiness signals.
                     *
                     * @param requestStream gRPC call observer used only inside this CloudSttClient.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this before the stub returns its request observer. It performs no
                     * blocking/suspending work; the conflated signal cannot grow without bound and
                     * late signals after cancellation are ignored.
                     */
                    override fun beforeStart(
                        requestStream: ClientCallStreamObserver<StreamingRecognizeRequest>,
                    ) {
                        clientCall = requestStream
                        requestStream.setOnReadyHandler {
                            readinessSignals.trySend(Unit)
                        }
                    }

                    /**
                     * Maps every usable Google result to the provider-neutral Flow boundary.
                     *
                     * @param value one Google streaming response, possibly containing many results.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this on its callback executor. Mapping and `trySend` never block.
                     * If the bounded result channel cannot accept data, the RPC is cancelled with a
                     * fixed non-secret failure rather than allowing memory growth.
                     */
                    override fun onNext(value: StreamingRecognizeResponse) {
                        mapResponse(value).forEach { result ->
                            val sendResult = trySend(result)
                            if (sendResult.isFailure && !cancelledByCollector.get()) {
                                val failure =
                                    CloudSttException(
                                        CloudSttFailureReason.Unknown,
                                        RESPONSE_BACKPRESSURE_MESSAGE,
                                    )
                                clientCall?.cancel(RESPONSE_BACKPRESSURE_CANCEL_REASON, null)
                                rpcFinished.complete(Unit)
                                close(failure)
                                return
                            }
                        }
                    }

                    /**
                     * Converts a gRPC callback failure to a fixed recoverable Flow error.
                     *
                     * @param throwable provider transport/status failure.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this on its callback executor. It performs no blocking work and
                     * never logs the throwable or metadata. Collector-triggered cancellation closes
                     * quietly; all other failures use [mapRpcFailure].
                     */
                    override fun onError(throwable: Throwable) {
                        rpcFinished.complete(Unit)
                        if (cancelledByCollector.get()) {
                            close()
                        } else {
                            close(mapRpcFailure(throwable))
                        }
                    }

                    /**
                     * Completes the Flow after Google has delivered all post-half-close responses.
                     *
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this on its callback executor. It is non-blocking, owns no new
                     * coroutine, and wakes the sender's bounded final-response wait before closing.
                     */
                    override fun onCompleted() {
                        rpcFinished.complete(Unit)
                        close()
                    }
                }

            val requestObserver =
                try {
                    SpeechGrpc
                        .newStub(this@CloudSttClient.channel)
                        .withCallCredentials(SpeechCallCredentials(credential))
                        // Google enforces an approximately five-minute/305-second stream limit.
                        // Setting the same deadline makes the boundary explicit for Phase 6/7.
                        .withDeadlineAfter(STREAMING_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .streamingRecognize(responseObserver)
                } catch (error: Throwable) {
                    close(mapRpcFailure(error))
                    return@callbackFlow
                }

            val call =
                clientCall
                    ?: run {
                        requestObserver.onError(
                            IllegalStateException("gRPC did not initialize the request stream."),
                        )
                        close(
                            CloudSttException(
                                CloudSttFailureReason.Unknown,
                                STREAM_INITIALIZATION_MESSAGE,
                            ),
                        )
                        return@callbackFlow
                    }

            audioSender =
                launch {
                    try {
                        awaitReady(call, readinessSignals)
                        // Google requires the recognition configuration to be the first request and
                        // rejects streams that mix config and audio in that initial message.
                        requestObserver.onNext(createConfigRequest())

                        audio
                            // Four ~100 ms chunks bound scheduling/uplink pressure to ~400 ms while
                            // gRPC's isReady gate prevents its internal outbound queue from growing.
                            .buffer(capacity = AUDIO_BUFFER_CAPACITY)
                            .collect { chunk ->
                                if (chunk.isEmpty()) return@collect
                                awaitReady(call, readinessSignals)
                                requestObserver.onNext(createAudioRequest(chunk))
                            }

                        requestObserver.onCompleted()
                        val finalResponseArrived =
                            withTimeoutOrNull(FINAL_RESPONSE_TIMEOUT_MS) {
                                rpcFinished.await()
                                true
                            } ?: false
                        if (!finalResponseArrived && !cancelledByCollector.get()) {
                            call.cancel(FINAL_RESPONSE_TIMEOUT_CANCEL_REASON, null)
                            close(
                                CloudSttException(
                                    CloudSttFailureReason.Timeout,
                                    FINAL_RESPONSE_TIMEOUT_MESSAGE,
                                ),
                            )
                        }
                    } catch (error: CancellationException) {
                        call.cancel(COLLECTOR_CANCEL_REASON, null)
                        throw error
                    } catch (error: Throwable) {
                        call.cancel(INPUT_FAILURE_CANCEL_REASON, null)
                        close(error)
                    }
                }

            awaitClose {
                cancelledByCollector.set(true)
                audioSender?.cancel()
                call.cancel(COLLECTOR_CANCEL_REASON, null)
                readinessSignals.close()
                rpcFinished.complete(Unit)
            }
        }.buffer(capacity = RESULT_BUFFER_CAPACITY)

    /**
     * Shuts down the single reusable channel owned by this client.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, and non-suspending. It promptly cancels active RPCs via
     * `shutdownNow`; affected collectors receive cancellation/status completion through their Flow.
     * No credential or metadata is logged, and repeated calls perform no additional work.
     */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            channel.shutdownNow()
        }
    }

    /**
     * Represents exactly one selected authentication mechanism for a streaming RPC.
     *
     * Values contain no gRPC type and never cross the CloudSttClient boundary. Instances perform no
     * I/O, have no coroutine or cancellation behavior, and fail only on normal allocation errors.
     */
    private sealed interface StreamCredential {

        /**
         * Holds a plain API key that will be sent only as `x-goog-api-key`.
         *
         * @property value non-blank API key retained in memory for one RPC.
         *
         * This value performs no I/O and has no threading, failure, or cancellation behavior.
         */
        data class ApiKey(val value: String) : StreamCredential

        /**
         * Holds a bearer token and optional non-secret quota project for one RPC.
         *
         * @property value short-lived OAuth token retained in memory for one RPC.
         * @property quotaProjectId optional project for the `x-goog-user-project` header.
         *
         * This value performs no I/O and has no threading, failure, or cancellation behavior.
         */
        data class BearerToken(
            val value: String,
            val quotaProjectId: String?,
        ) : StreamCredential
    }

    /**
     * Attaches exactly one already selected credential mode to one RPC.
     *
     * @param credential API key or OAuth bearer selection made before RPC construction.
     *
     * gRPC invokes [applyRequestMetadata] with an executor. The implementation never logs, persists,
     * or exposes headers; executor rejection becomes an unauthenticated call failure. There is no
     * coroutine or cancellation resource inside this synchronous credentials adapter.
     */
    private class SpeechCallCredentials(
        private val credential: StreamCredential,
    ) : CallCredentials() {

        /**
         * Applies one mutually exclusive ASCII credential on gRPC's supplied executor.
         *
         * @param requestInfo non-secret call information unused by this credential adapter.
         * @param appExecutor executor required by the gRPC credentials contract.
         * @param applier callback that receives headers or a fixed authentication failure.
         * @return This callback has no return value.
         *
         * The method performs no blocking work. It schedules one short task and owns no coroutine;
         * gRPC cancels the call if metadata application fails. Neither success nor failure logs the
         * API key, token, or credential header.
         */
        override fun applyRequestMetadata(
            requestInfo: RequestInfo,
            appExecutor: Executor,
            applier: MetadataApplier,
        ) {
            try {
                appExecutor.execute {
                    val headers = Metadata()
                    when (val selectedCredential = credential) {
                        is StreamCredential.ApiKey ->
                            headers.put(API_KEY_METADATA_KEY, selectedCredential.value)

                        is StreamCredential.BearerToken -> {
                            headers.put(
                                AUTHORIZATION_METADATA_KEY,
                                "Bearer ${selectedCredential.value}",
                            )
                            selectedCredential.quotaProjectId
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?.let { headers.put(QUOTA_PROJECT_METADATA_KEY, it) }
                        }
                    }
                    applier.apply(headers)
                }
            } catch (error: RuntimeException) {
                applier.fail(
                    Status.UNAUTHENTICATED.withDescription(CREDENTIAL_APPLICATION_MESSAGE),
                )
            }
        }
    }

    companion object {
        private const val SERVICE_PORT = 443
        private const val STREAMING_DEADLINE_SECONDS = 305L
        private const val FINAL_RESPONSE_TIMEOUT_MS = 3_000L
        private const val AUDIO_BUFFER_CAPACITY = 4
        private const val RESULT_BUFFER_CAPACITY = 16

        private const val CLOUD_NOT_CONFIGURED_MESSAGE =
            "Cloud speech is not configured. Add an approved credential and retry."
        private const val CREDENTIAL_PROVIDER_MESSAGE =
            "Cloud speech authentication is unavailable. Refresh configuration and retry."
        private const val CLIENT_CLOSED_MESSAGE =
            "Cloud speech is unavailable because the client has been closed."
        private const val STREAM_INITIALIZATION_MESSAGE =
            "Cloud speech could not initialize its streaming call. Please retry."
        private const val RESPONSE_BACKPRESSURE_MESSAGE =
            "Cloud speech responses arrived faster than they could be displayed. Please retry."
        private const val CREDENTIAL_APPLICATION_MESSAGE =
            "Cloud speech authentication metadata could not be applied."
        private const val FINAL_RESPONSE_TIMEOUT_MESSAGE =
            "Cloud speech timed out while waiting for the final result. Please retry."

        private const val RESPONSE_BACKPRESSURE_CANCEL_REASON = "response consumer too slow"
        private const val FINAL_RESPONSE_TIMEOUT_CANCEL_REASON = "final response timeout"
        private const val COLLECTOR_CANCEL_REASON = "collector cancelled"
        private const val INPUT_FAILURE_CANCEL_REASON = "audio input failed"

        private val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)
        private val QUOTA_PROJECT_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-user-project", Metadata.ASCII_STRING_MARSHALLER)

        /**
         * Builds the production TLS OkHttp channel used by all streams from one client instance.
         *
         * @return managed channel targeting `speech.googleapis.com:443` with transport security.
         *
         * Construction is synchronous and performs no RPC, token access, or logging. It may throw a
         * transport/platform initialization failure; the caller owns shutdown through [close].
         */
        private fun createProductionChannel(): ManagedChannel =
            OkHttpChannelBuilder
                .forAddress(GcpSttConfig.SERVICE_HOST, SERVICE_PORT)
                .useTransportSecurity()
                .build()

        /**
         * Suspends audio sending until gRPC reports outbound capacity.
         *
         * @param call active per-stream client-call observer.
         * @param readinessSignals conflated notifications from gRPC's callback executor.
         * @return when [call] is ready to accept another request.
         *
         * This function is cancellation-safe and performs no blocking work. Closing/cancelling the
         * stream closes the signal channel or cancels the caller, immediately ending the wait.
         */
        private suspend fun awaitReady(
            call: ClientCallStreamObserver<StreamingRecognizeRequest>,
            readinessSignals: Channel<Unit>,
        ) {
            while (!call.isReady) readinessSignals.receive()
        }

        /**
         * Creates Google's required first streaming request from non-secret shared configuration.
         *
         * @return request containing only StreamingRecognizeConfig and no audio bytes.
         *
         * This pure builder performs no I/O, is safe on any dispatcher, and has no cancellation
         * behavior. Invalid enum configuration throws synchronously before audio collection starts.
         */
        private fun createConfigRequest(): StreamingRecognizeRequest {
            val recognitionConfig =
                RecognitionConfig.newBuilder()
                    .setEncoding(
                        RecognitionConfig.AudioEncoding.valueOf(GcpSttConfig.AUDIO_ENCODING),
                    )
                    .setSampleRateHertz(GcpSttConfig.SAMPLE_RATE_HZ)
                    .setAudioChannelCount(GcpSttConfig.CHANNEL_COUNT)
                    .setLanguageCode(GcpSttConfig.LANGUAGE_CODE)
                    .setModel(GcpSttConfig.MODEL)
                    .setMaxAlternatives(1)
                    .build()
            val streamingConfig =
                StreamingRecognitionConfig.newBuilder()
                    .setConfig(recognitionConfig)
                    .setInterimResults(GcpSttConfig.INTERIM_RESULTS_ENABLED)
                    .build()
            return StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingConfig).build()
        }

        /**
         * Copies one immutable PCM chunk into a Google audio-content streaming request.
         *
         * @param chunk non-empty PCM16 mono audio bytes from the provider-neutral input Flow.
         * @return request containing only audio content and no recognition configuration.
         *
         * This synchronous copy performs no I/O or suspension and has no cancellation behavior.
         * Allocation failure is the only expected local failure.
         */
        private fun createAudioRequest(chunk: ByteArray): StreamingRecognizeRequest =
            StreamingRecognizeRequest.newBuilder()
                .setAudioContent(ByteString.copyFrom(chunk))
                .build()

        /**
         * Maps all usable alternatives in one Google response to provider-neutral results.
         *
         * @param response provider response kept strictly inside CloudSttClient.
         * @return ordered result list, omitting entries with no first alternative.
         *
         * This pure mapper is safe on any dispatcher, performs no I/O, and has no cancellation
         * behavior. Google supplies confidence on final results only, so interim confidence is
         * always `null`; final values preserve Google's reported float, including zero.
         */
        private fun mapResponse(response: StreamingRecognizeResponse): List<SttResult> =
            response.resultsList.mapNotNull { recognitionResult ->
                val alternative = recognitionResult.alternativesList.firstOrNull() ?: return@mapNotNull null
                SttResult(
                    transcript = alternative.transcript,
                    isFinal = recognitionResult.isFinal,
                    confidence =
                        if (recognitionResult.isFinal) {
                            alternative.confidence
                        } else {
                            null
                        },
                )
            }

        /**
         * Converts gRPC status codes to fixed recoverable failures without retaining provider detail.
         *
         * @param throwable gRPC startup/callback failure.
         * @return provider-neutral [CloudSttException] with no metadata, token, or server description.
         *
         * Mapping is synchronous, non-blocking, safe on any dispatcher, and has no cancellation
         * behavior. Cancellation caused by the collector is handled before this function is called.
         */
        private fun mapRpcFailure(throwable: Throwable): CloudSttException {
            val statusCode = Status.fromThrowable(throwable).code
            return when (statusCode) {
                Status.Code.UNAUTHENTICATED ->
                    CloudSttException(
                        CloudSttFailureReason.Unauthenticated,
                        "Cloud speech credential was rejected. Check or refresh it and retry.",
                    )

                Status.Code.PERMISSION_DENIED ->
                    CloudSttException(
                        CloudSttFailureReason.PermissionDenied,
                        "Cloud speech permission was denied. Check API enablement and quota project.",
                    )

                Status.Code.RESOURCE_EXHAUSTED ->
                    CloudSttException(
                        CloudSttFailureReason.QuotaExceeded,
                        "Cloud speech quota or rate limit was exceeded. Please retry later.",
                    )

                Status.Code.UNAVAILABLE ->
                    CloudSttException(
                        CloudSttFailureReason.Unavailable,
                        "Cloud speech is unreachable. Check the network and retry.",
                    )

                Status.Code.DEADLINE_EXCEEDED ->
                    CloudSttException(
                        CloudSttFailureReason.Timeout,
                        "Cloud speech timed out. Please retry.",
                    )

                else ->
                    CloudSttException(
                        CloudSttFailureReason.Unknown,
                        "Cloud speech failed. Please retry.",
                    )
            }
        }
    }
}

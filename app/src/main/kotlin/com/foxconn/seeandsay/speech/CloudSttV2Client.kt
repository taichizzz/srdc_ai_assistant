package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.BuildConfigApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.GcpSttV2Config
import com.google.cloud.speech.v2.ExplicitDecodingConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.SpeechGrpc
import com.google.cloud.speech.v2.StreamingRecognitionConfig
import com.google.cloud.speech.v2.StreamingRecognitionFeatures
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import com.google.protobuf.ByteString
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
 * Streams PCM audio to Google Cloud Speech-to-Text V2 for Chirp 2 or Chirp 3 evaluation.
 *
 * @param accessTokenProvider suspend provider for a current short-lived OAuth bearer token.
 * @param apiKeyProvider suspend provider for an optional API key, which takes precedence.
 * @param model V2 model identifier; only `chirp_2` and `chirp_3` are accepted.
 * @param projectId GCP project required by the inline recognizer resource path.
 * @param location regional endpoint/recognizer location selected for Chirp availability.
 * @param channel reusable managed channel; production uses regional TLS OkHttp, tests inject
 * in-process transport.
 *
 * Each [stream] collection creates one bidirectional V2 RPC while reusing [channel]. Google/gRPC/
 * protobuf types remain private to `speech/` and are mapped to the unchanged [SttResult] contract.
 * Audio and response buffering are bounded, collector cancellation promptly cancels the RPC, and
 * [close] shuts down the reusable channel. Missing project/location/credential and remote model or
 * region rejection terminate the Flow with a fixed recoverable [CloudSttException].
 */
class CloudSttV2Client internal constructor(
    private val accessTokenProvider: AccessTokenProvider,
    private val apiKeyProvider: ApiKeyProvider,
    model: String,
    private val projectId: String?,
    private val location: String?,
    private val channel: ManagedChannel,
) : SttClient,
    AutoCloseable {

    /** Validated Chirp model retained as non-secret immutable per-client configuration. */
    private val model: String = GcpSttV2Config.requireSupportedModel(model)

    /** Thread-safe channel disposal state shared by all collectors. */
    private val isClosed = AtomicBoolean(false)

    /**
     * Creates a regional TLS V2 client for one selected Chirp evaluation model.
     *
     * @param accessTokenProvider suspend provider for a short-lived OAuth bearer token.
     * @param apiKeyProvider suspend provider for an optional debug-injected API key.
     * @param model `chirp_2` or `chirp_3`.
     * @param projectId project used in the V2 recognizer resource and OAuth quota metadata.
     * @param location endpoint/recognizer region; model availability varies by region.
     *
     * Construction validates only the model and allocates a reusable OkHttp channel without making
     * an RPC or reading credentials. Missing project/location is deliberately deferred to [stream]
     * so the debug UI receives a recoverable error rather than an Activity construction crash.
     */
    constructor(
        accessTokenProvider: AccessTokenProvider,
        apiKeyProvider: ApiKeyProvider = BuildConfigApiKeyProvider(),
        model: String,
        projectId: String? = GcpSttV2Config.projectId,
        location: String? = GcpSttV2Config.location,
    ) : this(
        accessTokenProvider = accessTokenProvider,
        apiKeyProvider = apiKeyProvider,
        model = model,
        projectId = projectId,
        location = location,
        channel = createProductionChannel(location),
    )

    /**
     * Creates one cold V2 recognition Flow from raw PCM audio.
     *
     * @param audio PCM16 mono 16 kHz chunks; collection starts after local configuration/auth checks.
     * @return ordered interim and final provider-neutral results.
     *
     * Missing configuration and mapped gRPC failures terminate with [CloudSttException]; input Flow
     * failures propagate unchanged. Collection may run on any coroutine context. The callback bridge
     * is bounded, the sender is a structured child, normal cancellation is not converted to a user
     * failure, and stream completion waits briefly for final server responses after half-close.
     */
    override fun stream(audio: Flow<ByteArray>): Flow<SttResult> =
        callbackFlow {
            if (isClosed.get()) {
                close(CloudSttException(CloudSttFailureReason.Unknown, CLIENT_CLOSED_MESSAGE))
                return@callbackFlow
            }

            val recognizerName =
                try {
                    val configuredProject = projectId?.trim()?.takeIf(String::isNotEmpty)
                    val configuredLocation = location?.trim()?.takeIf(String::isNotEmpty)
                    if (configuredProject == null || configuredLocation == null) {
                        close(
                            CloudSttException(
                                CloudSttFailureReason.NotConfigured,
                                V2_NOT_CONFIGURED_MESSAGE,
                            ),
                        )
                        return@callbackFlow
                    }
                    GcpSttV2Config.recognizerPath(configuredProject, configuredLocation)
                } catch (error: IllegalArgumentException) {
                    close(
                        CloudSttException(
                            CloudSttFailureReason.NotConfigured,
                            V2_NOT_CONFIGURED_MESSAGE,
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
                    StreamCredential.BearerToken(token, projectId)
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
                     * Captures the cancellable V2 call and installs bounded readiness notification.
                     *
                     * @param requestStream active gRPC request stream retained only by this client.
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this before request sending. It performs no blocking/suspending
                     * work; conflation prevents readiness callbacks from growing memory unbounded.
                     */
                    override fun beforeStart(
                        requestStream: ClientCallStreamObserver<StreamingRecognizeRequest>,
                    ) {
                        clientCall = requestStream
                        requestStream.setOnReadyHandler { readinessSignals.trySend(Unit) }
                    }

                    /**
                     * Maps one V2 response into the provider-neutral bounded result Flow.
                     *
                     * @param value V2 provider response, possibly containing multiple results.
                     * @return This callback has no return value.
                     *
                     * The gRPC callback thread performs only pure mapping and non-blocking `trySend`.
                     * Consumer backpressure cancels the RPC with a fixed non-secret failure.
                     */
                    override fun onNext(value: StreamingRecognizeResponse) {
                        mapResponse(value).forEach { result ->
                            val sendResult = trySend(result)
                            if (sendResult.isFailure && !cancelledByCollector.get()) {
                                clientCall?.cancel(RESPONSE_BACKPRESSURE_CANCEL_REASON, null)
                                rpcFinished.complete(Unit)
                                close(
                                    CloudSttException(
                                        CloudSttFailureReason.Unknown,
                                        RESPONSE_BACKPRESSURE_MESSAGE,
                                    ),
                                )
                                return
                            }
                        }
                    }

                    /**
                     * Converts a V2 gRPC callback failure into a fixed recoverable Flow failure.
                     *
                     * @param throwable provider transport/status failure.
                     * @return This callback has no return value.
                     *
                     * The callback is non-blocking and never logs provider detail or metadata.
                     * Collector-triggered cancellation closes quietly; all other statuses are mapped.
                     */
                    override fun onError(throwable: Throwable) {
                        rpcFinished.complete(Unit)
                        if (cancelledByCollector.get()) close() else close(mapRpcFailure(throwable))
                    }

                    /**
                     * Completes the result Flow after all post-half-close V2 responses arrive.
                     *
                     * @return This callback has no return value.
                     *
                     * gRPC invokes this synchronously on its callback executor. It performs no I/O,
                     * suspension, or child creation and wakes the sender's final-response wait.
                     */
                    override fun onCompleted() {
                        rpcFinished.complete(Unit)
                        close()
                    }
                }

            val requestObserver =
                try {
                    SpeechGrpc
                        .newStub(this@CloudSttV2Client.channel)
                        .withCallCredentials(SpeechCallCredentials(credential))
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
                            IllegalStateException("gRPC did not initialize the V2 request stream."),
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
                        // V2 requires recognizer + full inline config first and rejects audio in it.
                        requestObserver.onNext(createConfigRequest(recognizerName, model))

                        audio
                            .buffer(capacity = AUDIO_BUFFER_CAPACITY)
                            .collect { chunk ->
                                if (chunk.isEmpty()) return@collect
                                var offset = 0
                                while (offset < chunk.size) {
                                    val length =
                                        minOf(MAX_AUDIO_REQUEST_BYTES, chunk.size - offset)
                                    awaitReady(call, readinessSignals)
                                    // V2 caps each request's audio field at 15 KB; splitting here
                                    // keeps the generic SttClient boundary safe for larger producers.
                                    requestObserver.onNext(
                                        createAudioRequest(chunk, offset, length),
                                    )
                                    offset += length
                                }
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
     * Shuts down the reusable regional channel owned by this V2 client.
     *
     * @return This function has no return value.
     *
     * The call is thread-safe, idempotent, and non-suspending. `shutdownNow` promptly cancels active
     * RPCs; affected collectors finish through normal Flow cancellation/status handling. It performs
     * no logging and never exposes credential metadata.
     */
    override fun close() {
        if (isClosed.compareAndSet(false, true)) channel.shutdownNow()
    }

    /**
     * Represents exactly one credential selected for a V2 streaming RPC.
     *
     * Values remain private to CloudSttV2Client, perform no I/O, and have no coroutine/cancellation
     * behavior. Allocation failure is their only local failure mode.
     */
    private sealed interface StreamCredential {

        /**
         * Holds a non-blank API key for one mutually exclusive `x-goog-api-key` header.
         *
         * @property value secret retained in memory only for the active RPC.
         */
        data class ApiKey(val value: String) : StreamCredential

        /**
         * Holds an OAuth bearer token and optional quota project for one RPC.
         *
         * @property value short-lived secret retained only in memory.
         * @property quotaProjectId optional non-secret `x-goog-user-project` value.
         */
        data class BearerToken(
            val value: String,
            val quotaProjectId: String?,
        ) : StreamCredential
    }

    /**
     * Attaches exactly one selected credential mode to a V2 RPC.
     *
     * @param credential API-key or bearer selection made before RPC construction.
     *
     * gRPC invokes [applyRequestMetadata] with its executor. Metadata never escapes this class and
     * is never logged. Executor rejection becomes a fixed unauthenticated call failure; the adapter
     * performs no blocking I/O and owns no coroutine or cancellable resource.
     */
    private class SpeechCallCredentials(
        private val credential: StreamCredential,
    ) : CallCredentials() {

        /**
         * Applies one mutually exclusive ASCII credential through gRPC's metadata callback.
         *
         * @param requestInfo non-secret call information unused by this adapter.
         * @param appExecutor executor required by the gRPC credentials contract.
         * @param applier callback receiving headers or a fixed authentication failure.
         * @return This callback has no return value.
         *
         * The method schedules one non-blocking task and owns no coroutine. It never logs the key,
         * token, Authorization value, or metadata; runtime executor failure rejects the call safely.
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
                        is StreamCredential.ApiKey ->
                            headers.put(API_KEY_METADATA_KEY, selected.value)

                        is StreamCredential.BearerToken -> {
                            headers.put(AUTHORIZATION_METADATA_KEY, "Bearer ${selected.value}")
                            selected.quotaProjectId
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
        /** Standard TLS port for the regional Google Speech-to-Text endpoint. */
        private const val SERVICE_PORT = 443

        /** V2 streaming hard limit (approximately five minutes) applied per RPC. */
        private const val STREAMING_DEADLINE_SECONDS = 305L

        /** Bounded wait for final responses after audio input half-closes. */
        private const val FINAL_RESPONSE_TIMEOUT_MS = 3_000L

        /** Maximum queued upstream audio chunks between MicRecorder and the request observer. */
        private const val AUDIO_BUFFER_CAPACITY = 4

        /** Maximum queued mapped results between gRPC callbacks and the Flow collector. */
        private const val RESULT_BUFFER_CAPACITY = 16

        /** Maximum PCM payload accepted in one V2 streaming audio request. */
        private const val MAX_AUDIO_REQUEST_BYTES = 15_000

        /** Safe channel-construction host used only when stream-time location validation will fail. */
        private const val FALLBACK_SERVICE_HOST = "speech.googleapis.com"

        /** Fixed non-secret message for missing V2 project or location. */
        private const val V2_NOT_CONFIGURED_MESSAGE =
            "Cloud speech V2 requires a project ID and supported location. Check local configuration."

        /** Fixed non-secret message for missing API-key and bearer credential modes. */
        private const val CLOUD_NOT_CONFIGURED_MESSAGE =
            "Cloud speech is not configured. Add an approved credential and retry."

        /** Fixed non-secret message for a credential-provider failure. */
        private const val CREDENTIAL_PROVIDER_MESSAGE =
            "Cloud speech authentication is unavailable. Refresh configuration and retry."

        /** Fixed non-secret message returned when a disposed client is collected. */
        private const val CLIENT_CLOSED_MESSAGE =
            "Cloud speech V2 is unavailable because the client has been closed."

        /** Fixed non-secret message for failure to obtain the request observer. */
        private const val STREAM_INITIALIZATION_MESSAGE =
            "Cloud speech V2 could not initialize its streaming call. Please retry."

        /** Fixed non-secret message when a slow result consumer exhausts the bounded bridge. */
        private const val RESPONSE_BACKPRESSURE_MESSAGE =
            "Cloud speech V2 responses exceeded the display buffer. Please retry."

        /** Fixed status description for a local CallCredentials executor failure. */
        private const val CREDENTIAL_APPLICATION_MESSAGE =
            "Cloud speech V2 authentication metadata could not be applied."

        /** Fixed non-secret message when final-response draining exceeds its bound. */
        private const val FINAL_RESPONSE_TIMEOUT_MESSAGE =
            "Cloud speech V2 timed out waiting for the final result. Please retry."

        /** Fixed non-secret message shared by model, region, and recognizer rejections. */
        private const val V2_CONFIGURATION_REJECTED_MESSAGE =
            "Cloud speech V2 rejected the model, region, or recognizer configuration."

        /** Internal gRPC cancellation reason for result backpressure. */
        private const val RESPONSE_BACKPRESSURE_CANCEL_REASON = "response consumer too slow"

        /** Internal gRPC cancellation reason for final-response timeout. */
        private const val FINAL_RESPONSE_TIMEOUT_CANCEL_REASON = "final response timeout"

        /** Internal gRPC cancellation reason for normal Flow collector cancellation. */
        private const val COLLECTOR_CANCEL_REASON = "collector cancelled"

        /** Internal gRPC cancellation reason for an upstream audio Flow failure. */
        private const val INPUT_FAILURE_CANCEL_REASON = "audio input failed"

        /** ASCII bearer metadata key retained entirely inside the speech implementation. */
        private val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** ASCII API-key metadata key retained entirely inside the speech implementation. */
        private val API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)

        /** ASCII OAuth quota-project metadata key omitted in API-key mode. */
        private val QUOTA_PROJECT_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-user-project", Metadata.ASCII_STRING_MARSHALLER)

        /**
         * Builds the regional production TLS OkHttp channel for one V2 client.
         *
         * @param location configured V2 region, or `null`/blank for a no-RPC fallback channel.
         * @return reusable managed channel with transport security enabled.
         *
         * Construction is synchronous and makes no RPC or credential lookup. Missing location uses
         * the global host only so object creation remains safe; [stream] rejects it before network
         * work. Transport initialization failures propagate, and the client owns channel shutdown.
         */
        private fun createProductionChannel(location: String?): ManagedChannel {
            val host =
                location
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(GcpSttV2Config::serviceHost)
                    ?: FALLBACK_SERVICE_HOST
            return OkHttpChannelBuilder
                .forAddress(host, SERVICE_PORT)
                .useTransportSecurity()
                .build()
        }

        /**
         * Suspends outbound sending until gRPC reports capacity.
         *
         * @param call active per-stream V2 client-call observer.
         * @param readinessSignals conflated callbacks from gRPC's executor.
         * @return when [call] can accept another request.
         *
         * The function performs no blocking work. Collector/channel cancellation immediately ends
         * the wait; closed readiness input propagates as the active stream's cancellation/failure.
         */
        private suspend fun awaitReady(
            call: ClientCallStreamObserver<StreamingRecognizeRequest>,
            readinessSignals: Channel<Unit>,
        ) {
            while (!call.isReady) readinessSignals.receive()
        }

        /**
         * Builds Google's required config-first V2 streaming request.
         *
         * @param recognizerName full implicit recognizer resource for project/location.
         * @param model validated `chirp_2` or `chirp_3` identifier.
         * @return request containing recognizer and streaming config, with no audio.
         *
         * This pure builder is safe on any dispatcher and has no I/O/cancellation behavior. Invalid
         * local proto values fail synchronously before microphone audio is collected.
         */
        private fun createConfigRequest(
            recognizerName: String,
            model: String,
        ): StreamingRecognizeRequest {
            val decodingConfig =
                ExplicitDecodingConfig.newBuilder()
                    .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(GcpSttV2Config.SAMPLE_RATE_HZ)
                    .setAudioChannelCount(GcpSttV2Config.CHANNEL_COUNT)
                    .build()
            val recognitionConfig =
                RecognitionConfig.newBuilder()
                    .setExplicitDecodingConfig(decodingConfig)
                    .setModel(model)
                    .addLanguageCodes(GcpSttV2Config.LANGUAGE_CODE)
                    .build()
            val streamingFeatures =
                StreamingRecognitionFeatures.newBuilder()
                    .setInterimResults(GcpSttV2Config.INTERIM_RESULTS_ENABLED)
                    .build()
            val streamingConfig =
                StreamingRecognitionConfig.newBuilder()
                    .setConfig(recognitionConfig)
                    .setStreamingFeatures(streamingFeatures)
                    .build()
            return StreamingRecognizeRequest.newBuilder()
                .setRecognizer(recognizerName)
                .setStreamingConfig(streamingConfig)
                .build()
        }

        /**
         * Copies a bounded slice of immutable PCM into one V2 audio-only request.
         *
         * @param chunk source PCM byte array.
         * @param offset first source byte to copy.
         * @param length number of bytes, at most the V2 15 KB request limit.
         * @return request containing audio only, with no recognizer/config.
         * @throws IndexOutOfBoundsException when the requested slice is outside [chunk].
         *
         * This synchronous copy performs no I/O or suspension and has no cancellation behavior.
         */
        private fun createAudioRequest(
            chunk: ByteArray,
            offset: Int,
            length: Int,
        ): StreamingRecognizeRequest =
            StreamingRecognizeRequest.newBuilder()
                .setAudio(ByteString.copyFrom(chunk, offset, length))
                .build()

        /**
         * Maps all usable V2 recognition results to the provider-neutral contract.
         *
         * @param response V2 provider response retained inside `speech/`.
         * @return ordered values, omitting results without a first alternative.
         *
         * This pure mapper is safe on any dispatcher and performs no I/O or cancellation work.
         * Confidence is deliberately copied only for finals; interim confidence is always `null`.
         */
        private fun mapResponse(response: StreamingRecognizeResponse): List<SttResult> =
            response.resultsList.mapNotNull { recognitionResult ->
                val alternative =
                    recognitionResult.alternativesList.firstOrNull() ?: return@mapNotNull null
                SttResult(
                    transcript = alternative.transcript,
                    isFinal = recognitionResult.isFinal,
                    confidence = if (recognitionResult.isFinal) alternative.confidence else null,
                )
            }

        /**
         * Converts V2 gRPC statuses into fixed recoverable provider-neutral failures.
         *
         * @param throwable startup/callback failure from gRPC.
         * @return [CloudSttException] containing no server description, metadata, or credential.
         *
         * Mapping is synchronous/non-blocking on any dispatcher and owns no cancellation resource.
         * Invalid/not-found/precondition failures intentionally share one model/region/config message
         * because Chirp availability changes independently of the application.
         */
        private fun mapRpcFailure(throwable: Throwable): CloudSttException =
            when (Status.fromThrowable(throwable).code) {
                Status.Code.UNAUTHENTICATED ->
                    CloudSttException(
                        CloudSttFailureReason.Unauthenticated,
                        "Cloud speech credential was rejected. Check or refresh it and retry.",
                    )

                Status.Code.PERMISSION_DENIED ->
                    CloudSttException(
                        CloudSttFailureReason.PermissionDenied,
                        "Cloud speech permission was denied. Check API enablement and project access.",
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

                Status.Code.INVALID_ARGUMENT,
                Status.Code.NOT_FOUND,
                Status.Code.FAILED_PRECONDITION,
                -> CloudSttException(CloudSttFailureReason.Unknown, V2_CONFIGURATION_REJECTED_MESSAGE)

                else ->
                    CloudSttException(
                        CloudSttFailureReason.Unknown,
                        "Cloud speech V2 failed. Please retry.",
                    )
            }
    }
}

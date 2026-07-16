package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.FakeAccessTokenProvider
import com.foxconn.seeandsay.config.GcpSttV2Config
import com.google.cloud.speech.v2.ExplicitDecodingConfig
import com.google.cloud.speech.v2.SpeechGrpc
import com.google.cloud.speech.v2.SpeechRecognitionAlternative
import com.google.cloud.speech.v2.StreamingRecognitionResult
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies CloudSttV2Client protocol, mapping, recovery, and cancellation without real networking.
 *
 * Tests use only in-process gRPC direct executors and non-secret fake credentials. They perform no
 * DNS, TLS, Google, Android, microphone, or filesystem I/O. Every fixture closes its client/channel
 * and server; coroutine cancellation is bounded and never interpreted as a user-facing failure.
 */
class CloudSttV2ClientTest {

    /**
     * Verifies config-first ordering, both Chirp identifiers, and interim/final mapping.
     *
     * @return This test has no return value.
     *
     * [runTest] performs two finite in-process RPCs. It fails if recognizer/config/audio ordering,
     * explicit PCM settings, Taiwan-Mandarin locale, model selection, final-only confidence, or
     * provider-neutral transcript mapping changes. It uses no real delay or external resource.
     */
    @Test
    fun configFirstCarriesBothModelsAndMapsResults() =
        runTest {
            listOf(GcpSttV2Config.CHIRP_2_MODEL, GcpSttV2Config.CHIRP_3_MODEL).forEach { model ->
                val service = SuccessfulSpeechService()
                GrpcFixture(service, model = model).use { fixture ->
                    val results = fixture.client.stream(flowOf(byteArrayOf(1, 2, 3, 4))).toList()

                    assertEquals(2, service.requests.size)
                    val configRequest = service.requests[0]
                    val audioRequest = service.requests[1]
                    assertEquals(TEST_RECOGNIZER, configRequest.recognizer)
                    assertTrue(configRequest.hasStreamingConfig())
                    assertTrue(configRequest.audio.isEmpty)
                    val config = configRequest.streamingConfig.config
                    assertEquals(model, config.model)
                    assertEquals(listOf(GcpSttV2Config.LANGUAGE_CODE), config.languageCodesList)
                    assertEquals(
                        ExplicitDecodingConfig.AudioEncoding.LINEAR16,
                        config.explicitDecodingConfig.encoding,
                    )
                    assertEquals(
                        GcpSttV2Config.SAMPLE_RATE_HZ,
                        config.explicitDecodingConfig.sampleRateHertz,
                    )
                    assertEquals(
                        GcpSttV2Config.CHANNEL_COUNT,
                        config.explicitDecodingConfig.audioChannelCount,
                    )
                    assertTrue(configRequest.streamingConfig.streamingFeatures.interimResults)
                    assertFalse(audioRequest.hasStreamingConfig())
                    assertTrue(audioRequest.recognizer.isEmpty())
                    assertEquals(listOf<Byte>(1, 2, 3, 4), audioRequest.audio.toByteArray().toList())

                    assertEquals(2, results.size)
                    assertEquals(SttResult("你", isFinal = false, confidence = null), results[0])
                    assertEquals(SttResult("你好", isFinal = true, confidence = 0.91f), results[1])
                }
            }
        }

    /**
     * Verifies V2 preserves API-key precedence and the bearer fallback without mixed credentials.
     *
     * @return This test has no return value.
     *
     * [runTest] performs two finite in-process RPCs and captures only fake metadata. It fails if an
     * API-key call also sends bearer/quota headers or reads the token provider, or if the no-key
     * call omits bearer/quota metadata. No real credential, network, log, or delay is involved.
     */
    @Test
    fun selectsExactlyOneCredentialMode() =
        runTest {
            val unusedToken = FakeAccessTokenProvider(token = "unused-v2-test-bearer")
            GrpcFixture(
                service = SuccessfulSpeechService(),
                tokenProvider = unusedToken,
                apiKeyProvider = ApiKeyProvider { "v2-test-api-key" },
            ).use { fixture ->
                fixture.client.stream(flowOf(byteArrayOf(1))).toList()

                assertEquals("v2-test-api-key", fixture.metadataCapture.apiKey)
                assertNull(fixture.metadataCapture.authorization)
                assertNull(fixture.metadataCapture.quotaProjectId)
                assertEquals(0, unusedToken.requestCount)
            }

            GrpcFixture(
                service = SuccessfulSpeechService(),
                tokenProvider = FakeAccessTokenProvider(token = "v2-test-bearer"),
            ).use { fixture ->
                fixture.client.stream(flowOf(byteArrayOf(1))).toList()

                assertNull(fixture.metadataCapture.apiKey)
                assertEquals("Bearer v2-test-bearer", fixture.metadataCapture.authorization)
                assertEquals(TEST_PROJECT, fixture.metadataCapture.quotaProjectId)
            }
        }

    /**
     * Verifies required transport statuses and V2 configuration rejection remain recoverable.
     *
     * @return This test has no return value.
     *
     * [runTest] creates an isolated in-process service per status. No network or real delay occurs;
     * each Flow must terminate with the expected fixed [CloudSttFailureReason] instead of exposing a
     * gRPC exception or hanging.
     */
    @Test
    fun mapsGrpcStatusesToRecoverableFailures() =
        runTest {
            val cases =
                listOf(
                    Status.UNAUTHENTICATED to CloudSttFailureReason.Unauthenticated,
                    Status.PERMISSION_DENIED to CloudSttFailureReason.PermissionDenied,
                    Status.RESOURCE_EXHAUSTED to CloudSttFailureReason.QuotaExceeded,
                    Status.UNAVAILABLE to CloudSttFailureReason.Unavailable,
                    Status.DEADLINE_EXCEEDED to CloudSttFailureReason.Timeout,
                    Status.INVALID_ARGUMENT to CloudSttFailureReason.Unknown,
                )

            cases.forEach { (status, reason) ->
                GrpcFixture(FailingSpeechService(status)).use { fixture ->
                    val error = collectFailure(fixture.client.stream(flowOf(byteArrayOf(1))))
                    assertTrue(error is CloudSttException)
                    assertEquals(reason, (error as CloudSttException).reason)
                    assertFalse(error.message.orEmpty().contains("configured-test-value"))
                }
            }
        }

    /**
     * Verifies missing V2 project/location fails recoverably before credentials, audio, or RPC work.
     *
     * @return This test has no return value.
     *
     * [runTest] injects an in-process channel but null local configuration. It fails if audio or the
     * token provider is touched, a server request begins, or the provider-neutral reason is not
     * NotConfigured. No external resource or real delay is used.
     */
    @Test
    fun missingProjectOrLocationFailsBeforeAudioCollection() =
        runTest {
            val service = SuccessfulSpeechService()
            val tokenProvider = FakeAccessTokenProvider()
            var audioCollected = false
            GrpcFixture(
                service = service,
                tokenProvider = tokenProvider,
                projectId = null,
                location = null,
            ).use { fixture ->
                val error =
                    collectFailure(
                        fixture.client.stream(
                            flow {
                                audioCollected = true
                                emit(byteArrayOf(1))
                            },
                        ),
                    )

                assertTrue(error is CloudSttException)
                assertEquals(
                    CloudSttFailureReason.NotConfigured,
                    (error as CloudSttException).reason,
                )
                assertFalse(audioCollected)
                assertEquals(0, tokenProvider.requestCount)
                assertTrue(service.requests.isEmpty())
            }
        }

    /**
     * Verifies cancelling a V2 Flow collector promptly cancels its active RPC without user error.
     *
     * @return This test has no return value.
     *
     * [runBlocking] uses bounded real time only for the external in-process gRPC executor. The fake
     * must observe CANCELLED after config; CancellationException is treated as normal structured
     * cancellation and no other exception may escape.
     */
    @Test
    fun collectorCancellationCancelsRpcWithoutUserError() =
        runBlocking {
            val service = CancellationSpeechService()
            val userErrors = mutableListOf<Throwable>()
            GrpcFixture(service).use { fixture ->
                val job =
                    launch {
                        try {
                            fixture.client.stream(flow { awaitCancellation() }).collect()
                        } catch (error: CancellationException) {
                            // Expected structured cancellation is intentionally not a user outcome.
                        } catch (error: Throwable) {
                            userErrors += error
                        }
                    }

                withTimeout(1_000) { service.configReceived.await() }
                job.cancelAndJoin()
                val cancellation = withTimeout(1_000) { service.cancelled.await() }

                assertEquals(Status.Code.CANCELLED, cancellation.code)
                assertTrue(userErrors.isEmpty())
            }
        }

    /**
     * Collects a result Flow that is expected to fail.
     *
     * @param flow provider-neutral Flow under test.
     * @return non-cancellation failure emitted by [flow].
     *
     * This suspend helper performs no I/O itself and propagates cancellation. Unexpected normal
     * completion fails the test because every caller models a recoverable error path.
     */
    private suspend fun collectFailure(flow: Flow<SttResult>): Throwable =
        try {
            flow.collect()
            fail("Expected V2 cloud STT Flow failure")
            AssertionError("unreachable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }

    /**
     * Records requests and emits deterministic interim/final V2 responses after first audio.
     *
     * The service runs on a direct in-process executor, performs no external I/O or coroutine work,
     * and owns no resource beyond the fixture RPC. Request storage is synchronized for assertions.
     */
    private class SuccessfulSpeechService : SpeechGrpc.SpeechImplBase() {
        /** Ordered requests received by the most recent fixture call. */
        val requests: MutableList<StreamingRecognizeRequest> =
            Collections.synchronizedList(mutableListOf())
        private val responsesSent = AtomicBoolean(false)

        /**
         * Creates one deterministic request observer.
         *
         * @param responseObserver callback receiving fabricated V2 responses.
         * @return observer recording config/audio and accepting call termination.
         *
         * gRPC invokes it on the direct executor; it performs no blocking, I/O, or suspension.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> =
            object : StreamObserver<StreamingRecognizeRequest> {
                /**
                 * Records a request and emits one interim/final pair after first non-empty audio.
                 *
                 * @param value incoming V2 config or audio request.
                 * @return This callback has no return value.
                 *
                 * The direct-executor callback is synchronous/non-blocking and emits at most once.
                 */
                override fun onNext(value: StreamingRecognizeRequest) {
                    requests += value
                    if (!value.audio.isEmpty && responsesSent.compareAndSet(false, true)) {
                        responseObserver.onNext(createResponse("你", false, 0f))
                        responseObserver.onNext(createResponse("你好", true, 0.91f))
                    }
                }

                /**
                 * Accepts expected client cancellation/input failure without extra test work.
                 *
                 * @param throwable client termination cause.
                 * @return This callback has no return value.
                 *
                 * The callback is synchronous, performs no I/O/suspension, and owns no resource.
                 */
                override fun onError(throwable: Throwable) = Unit

                /**
                 * Completes fabricated responses after client audio half-close.
                 *
                 * @return This callback has no return value.
                 *
                 * The direct-executor callback is non-blocking and has no cancellation resource.
                 */
                override fun onCompleted() {
                    responseObserver.onCompleted()
                }
            }
    }

    /**
     * Returns one injected status after the client's first V2 request.
     *
     * @param status fake gRPC status.
     *
     * The service is direct-executor/in-process only, performs no blocking or external I/O, and owns
     * no coroutine. Duplicate requests and later client termination are accepted quietly.
     */
    private class FailingSpeechService(
        private val status: Status,
    ) : SpeechGrpc.SpeechImplBase() {
        /**
         * Creates a request observer that fails exactly once.
         *
         * @param responseObserver callback receiving the injected status.
         * @return synchronous non-blocking observer.
         *
         * gRPC invokes callbacks on the fixture's direct executor; no resource or coroutine is made.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> =
            object : StreamObserver<StreamingRecognizeRequest> {
                private val failed = AtomicBoolean(false)

                /**
                 * Delivers the configured failure on the first request.
                 *
                 * @param value ignored incoming config/audio request.
                 * @return This callback has no return value.
                 *
                 * Execution is synchronous/non-blocking; duplicate calls are ignored.
                 */
                override fun onNext(value: StreamingRecognizeRequest) {
                    if (failed.compareAndSet(false, true)) {
                        responseObserver.onError(status.asRuntimeException())
                    }
                }

                /**
                 * Accepts subsequent client termination.
                 *
                 * @param throwable ignored client cause.
                 * @return This callback has no return value.
                 *
                 * It performs no I/O, suspension, or resource work.
                 */
                override fun onError(throwable: Throwable) = Unit

                /**
                 * Accepts client half-close after the fake failure.
                 *
                 * @return This callback has no return value.
                 *
                 * It performs no I/O, suspension, or cancellation work.
                 */
                override fun onCompleted() = Unit
            }
    }

    /**
     * Holds the RPC open and exposes config/cancellation signals.
     *
     * Deferred values bridge direct-executor callbacks to the test coroutine without blocking. The
     * fake performs no external I/O and owns no resource beyond its fixture RPC.
     */
    private class CancellationSpeechService : SpeechGrpc.SpeechImplBase() {
        /** Completed when the required config-first request arrives. */
        val configReceived = CompletableDeferred<Unit>()

        /** Completed with the status observed when the client cancels. */
        val cancelled = CompletableDeferred<Status>()

        /**
         * Creates an observer that stays open until client cancellation.
         *
         * @param responseObserver unused response side retained by gRPC.
         * @return observer exposing config and termination signals.
         *
         * All callbacks run on the direct executor and perform no blocking, I/O, or child launching.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> =
            object : StreamObserver<StreamingRecognizeRequest> {
                /**
                 * Signals receipt of a config request.
                 *
                 * @param value incoming V2 request.
                 * @return This callback has no return value.
                 *
                 * It completes a Deferred non-blockingly and owns no cancellation resource.
                 */
                override fun onNext(value: StreamingRecognizeRequest) {
                    if (value.hasStreamingConfig()) configReceived.complete(Unit)
                }

                /**
                 * Records client cancellation as a gRPC status.
                 *
                 * @param throwable transport termination delivered by gRPC.
                 * @return This callback has no return value.
                 *
                 * The conversion is synchronous/non-blocking and performs no external I/O.
                 */
                override fun onError(throwable: Throwable) {
                    cancelled.complete(Status.fromThrowable(throwable))
                }

                /**
                 * Accepts unexpected half-close without fabricating completion.
                 *
                 * @return This callback has no return value.
                 *
                 * It performs no I/O, suspension, failure mapping, or resource work.
                 */
                override fun onCompleted() = Unit
            }
    }

    /**
     * Captures only the three supported fake credential headers for deterministic assertions.
     *
     * The interceptor runs on the in-process direct executor, performs no I/O or logging, and
     * forwards every call immediately. Values are fixture-local and never contain live secrets.
     */
    private class CredentialMetadataInterceptor : ServerInterceptor {
        /** Most recently captured fake API key, or `null` when absent. */
        @Volatile var apiKey: String? = null

        /** Most recently captured fake Authorization value, or `null` when absent. */
        @Volatile var authorization: String? = null

        /** Most recently captured fake quota project, or `null` when absent. */
        @Volatile var quotaProjectId: String? = null

        /**
         * Captures supported headers, then delegates the in-process call unchanged.
         *
         * @param call fake server call.
         * @param headers incoming fake metadata.
         * @param next downstream fake service handler.
         * @return listener returned by [next].
         *
         * The callback is synchronous/non-blocking, starts no coroutine, and has no cancellation
         * ownership. Missing headers become `null`; downstream failures propagate to the test.
         */
        override fun <ReqT : Any?, RespT : Any?> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            apiKey = headers.get(API_KEY_METADATA_KEY)
            authorization = headers.get(AUTHORIZATION_METADATA_KEY)
            quotaProjectId = headers.get(QUOTA_PROJECT_METADATA_KEY)
            return next.startCall(call, headers)
        }

        private companion object {
            /** ASCII API-key header used only by the in-process fixture. */
            val API_KEY_METADATA_KEY: Metadata.Key<String> =
                Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)

            /** ASCII bearer header used only by the in-process fixture. */
            val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

            /** ASCII quota-project header used only by the in-process fixture. */
            val QUOTA_PROJECT_METADATA_KEY: Metadata.Key<String> =
                Metadata.Key.of("x-goog-user-project", Metadata.ASCII_STRING_MARSHALLER)
        }
    }

    /**
     * Owns one in-process V2 server/channel/client test environment.
     *
     * @param service fake V2 Speech service.
     * @param model selected Chirp identifier.
     * @param tokenProvider non-secret bearer provider.
     * @param apiKeyProvider optional fake API-key provider.
     * @param projectId inline recognizer project, nullable for configuration tests.
     * @param location inline recognizer/endpoint location, nullable for configuration tests.
     *
     * Construction starts only in-memory direct-executor resources. [close] cancels the client and
     * server and waits at most one second; setup/shutdown failures fail the test without network I/O.
     */
    private class GrpcFixture(
        service: SpeechGrpc.SpeechImplBase,
        model: String = GcpSttV2Config.CHIRP_2_MODEL,
        tokenProvider: AccessTokenProvider = FakeAccessTokenProvider(),
        apiKeyProvider: ApiKeyProvider = ApiKeyProvider { null },
        projectId: String? = TEST_PROJECT,
        location: String? = TEST_LOCATION,
    ) : AutoCloseable {
        private val serverName = InProcessServerBuilder.generateName()
        /** Captured non-production credentials for this fixture's assertions. */
        val metadataCapture = CredentialMetadataInterceptor()
        private val server: Server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .intercept(metadataCapture)
                .addService(service)
                .build()
                .start()
        private val channel: ManagedChannel =
            InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build()

        /** V2 client under test using only this fixture's in-process transport. */
        val client =
            CloudSttV2Client(
                accessTokenProvider = tokenProvider,
                apiKeyProvider = apiKeyProvider,
                model = model,
                projectId = projectId,
                location = location,
                channel = channel,
            )

        /**
         * Cancels client/server resources and awaits bounded in-process termination.
         *
         * @return This function has no return value.
         *
         * JUnit invokes it from test cleanup. Shutdown is idempotent and may block at most one second;
         * it performs no network I/O and owns no coroutine.
         */
        override fun close() {
            client.close()
            server.shutdownNow()
            server.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private companion object {
        /** Non-secret project used only in fabricated recognizer resources. */
        const val TEST_PROJECT = "test-project"

        /** Region used only by the in-process recognizer request. */
        const val TEST_LOCATION = "asia-southeast1"

        /** Exact implicit recognizer expected in every configured fixture request. */
        const val TEST_RECOGNIZER =
            "projects/$TEST_PROJECT/locations/$TEST_LOCATION/recognizers/_"

        /**
         * Builds one fabricated V2 response for mapper assertions.
         *
         * @param transcript fake non-secret text.
         * @param isFinal interim/final marker.
         * @param confidence fake provider confidence.
         * @return V2 response containing one result/alternative.
         *
         * This pure helper performs no I/O, suspension, or cancellation and fails only on allocation.
         */
        fun createResponse(
            transcript: String,
            isFinal: Boolean,
            confidence: Float,
        ): StreamingRecognizeResponse =
            StreamingRecognizeResponse.newBuilder()
                .addResults(
                    StreamingRecognitionResult.newBuilder()
                        .setIsFinal(isFinal)
                        .addAlternatives(
                            SpeechRecognitionAlternative.newBuilder()
                                .setTranscript(transcript)
                                .setConfidence(confidence)
                                .build(),
                        ).build(),
                ).build()
    }
}

package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.ApiKeyProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.GcpTtsConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse
import com.google.cloud.texttospeech.v1.TextToSpeechGrpc
import com.google.protobuf.ByteString
import io.grpc.Context
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies Google unary TTS request/response, authentication, failure, and cancellation behavior.
 *
 * Every test uses an in-process gRPC service and non-secret fake credentials. No DNS, TLS, Google,
 * Android audio, filesystem, or real-time delay is involved. Fixtures close client/server resources
 * after each test so cancellation and reusable-channel disposal remain deterministic.
 */
class CloudTtsSynthesizerTest {

    /**
     * Verifies request settings, API-key precedence, and provider-neutral audio mapping.
     *
     * @return This test has no return value.
     *
     * The in-process service records one request and returns fixed WAV-like bytes synchronously.
     * The test fails if text, cmn-TW voice, LINEAR16/24 kHz config, credential exclusivity, or output
     * metadata changes. It performs no external I/O or independent coroutine cancellation.
     */
    @Test
    fun requestAndAudioResponseAreMappedWithApiKeyPrecedence() =
        runBlocking {
            val expectedBytes = byteArrayOf(82, 73, 70, 70, 1, 2, 3, 4)
            val service = SuccessfulTtsService(expectedBytes)
            var tokenWasRequested = false
            val tokenProvider =
                AccessTokenProvider {
                    tokenWasRequested = true
                    "unused-token"
                }
            GrpcFixture(
                service = service,
                tokenProvider = tokenProvider,
                apiKeyProvider = ApiKeyProvider { "test-api-key" },
            ).use { fixture ->
                val audio = fixture.client.synthesize("你好")
                val request = service.requests.single()

                assertEquals("你好", request.input.text)
                assertEquals(GcpTtsConfig.LANGUAGE_CODE, request.voice.languageCode)
                assertEquals(GcpTtsConfig.VOICE_NAME, request.voice.name)
                assertEquals(AudioEncoding.LINEAR16, request.audioConfig.audioEncoding)
                assertEquals(GcpTtsConfig.SAMPLE_RATE_HZ, request.audioConfig.sampleRateHertz)
                assertArrayEquals(expectedBytes, audio.bytes)
                assertEquals(GcpTtsConfig.SAMPLE_RATE_HZ, audio.sampleRateHz)
                assertEquals(GcpTtsConfig.CHANNEL_COUNT, audio.channelCount)
                assertEquals(GcpTtsConfig.BITS_PER_SAMPLE, audio.bitsPerSample)
                assertEquals(SynthesizedAudioFormat.Linear16Wav, audio.format)
                assertEquals("test-api-key", fixture.metadata.apiKey)
                assertNull(fixture.metadata.authorization)
                assertNull(fixture.metadata.quotaProjectId)
                assertFalse(tokenWasRequested)
            }
        }

    /**
     * Verifies bearer authentication and quota attribution when no API key is configured.
     *
     * @return This test has no return value.
     *
     * The in-process call captures only fake metadata and completes normally. It fails if both
     * credentials are sent, bearer formatting changes, or quota attribution is omitted. No network,
     * Android, real delay, or cancellation behavior is involved.
     */
    @Test
    fun bearerIsUsedWhenApiKeyIsAbsent() =
        runBlocking {
            val service = SuccessfulTtsService(byteArrayOf(1))
            GrpcFixture(
                service = service,
                tokenProvider = AccessTokenProvider { "test-bearer-token" },
                apiKeyProvider = ApiKeyProvider { null },
            ).use { fixture ->
                fixture.client.synthesize("測試")

                assertNull(fixture.metadata.apiKey)
                assertEquals("Bearer test-bearer-token", fixture.metadata.authorization)
                assertEquals("test-quota-project", fixture.metadata.quotaProjectId)
            }
        }

    /**
     * Verifies a provider quota status maps to a recoverable cloud-TTS category.
     *
     * @return This test has no return value.
     *
     * The fake returns RESOURCE_EXHAUSTED immediately. Collection uses an event-driven unary call
     * with no external I/O or delay and fails if the request hangs or leaks provider descriptions.
     */
    @Test
    fun quotaStatusMapsToRecoverableFailure() =
        runBlocking {
            GrpcFixture(FailingTtsService(Status.RESOURCE_EXHAUSTED)).use { fixture ->
                val failure = captureFailure { fixture.client.synthesize("你好") }

                assertTrue(failure is CloudTtsException)
                assertEquals(
                    CloudTtsFailureReason.QuotaExceeded,
                    (failure as CloudTtsException).reason,
                )
                assertFalse(failure.message.orEmpty().contains("test-api-key"))
            }
        }

    /**
     * Verifies absent API-key and bearer configuration fails before a provider request.
     *
     * @return This test has no return value.
     *
     * Credential providers execute in memory and the server must receive nothing. It fails if
     * missing configuration crashes, hangs, or is categorized as an opaque provider error.
     */
    @Test
    fun missingCredentialsFailRecoverablyBeforeRpc() =
        runBlocking {
            val service = SuccessfulTtsService(byteArrayOf(1))
            val missingToken =
                AccessTokenProvider {
                    throw CloudSpeechNotConfiguredException()
                }
            GrpcFixture(
                service = service,
                tokenProvider = missingToken,
                apiKeyProvider = ApiKeyProvider { null },
            ).use { fixture ->
                val failure = captureFailure { fixture.client.synthesize("你好") }

                assertTrue(failure is CloudTtsException)
                assertEquals(
                    CloudTtsFailureReason.NotConfigured,
                    (failure as CloudTtsException).reason,
                )
                assertTrue(service.requests.isEmpty())
            }
        }

    /**
     * Verifies coroutine cancellation cancels an active unary RPC without a user-facing failure.
     *
     * @return This test has no return value.
     *
     * The fake keeps its response open and exposes event-driven request/cancellation signals. The
     * bounded waits protect the test process only; there is no fixed sleep, external I/O, or real
     * provider. CancellationException is expected and any other failure fails the assertion.
     */
    @Test
    fun coroutineCancellationCancelsUnaryRpcWithoutUserError() =
        runBlocking {
            val service = CancellationTtsService()
            val userFailures = mutableListOf<Throwable>()
            GrpcFixture(service).use { fixture ->
                val job =
                    launch {
                        try {
                            fixture.client.synthesize("請保持連線")
                        } catch (_: CancellationException) {
                            // Expected structured cancellation is not a recoverable user failure.
                        } catch (error: Throwable) {
                            userFailures += error
                        }
                    }

                withTimeout(1_000) { service.requestReceived.await() }
                job.cancelAndJoin()
                withTimeout(1_000) { service.cancellationObserved.await() }

                assertTrue(userFailures.isEmpty())
            }
        }

    /**
     * Executes a synthesis block expected to fail and returns its non-cancellation exception.
     *
     * @param block suspend scenario under test.
     * @return throwable produced by the scenario.
     *
     * The helper runs in the caller's coroutine, performs no I/O, and propagates cancellation.
     * Unexpected normal completion fails the test immediately.
     */
    private suspend fun captureFailure(block: suspend () -> Unit): Throwable =
        try {
            block()
            fail("Expected cloud TTS synthesis to fail")
            AssertionError("unreachable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }

    /**
     * Records unary requests and returns one deterministic byte response.
     *
     * @param responseBytes fake synthesized bytes copied into every response.
     *
     * gRPC invokes the service on the fixture's direct executor. The synchronized list is safe for
     * post-call assertions; the service performs no blocking, suspension, or external I/O.
     */
    private class SuccessfulTtsService(
        private val responseBytes: ByteArray,
    ) : TextToSpeechGrpc.TextToSpeechImplBase() {

        /** Thread-safe ordered requests received by the fake service. */
        val requests: MutableList<SynthesizeSpeechRequest> =
            Collections.synchronizedList(mutableListOf())

        /**
         * Records one unary request and completes with fabricated audio bytes.
         *
         * @param request incoming Google request proto.
         * @param responseObserver observer receiving the fake response and completion.
         * @return This callback has no return value.
         *
         * The direct gRPC executor invokes this synchronously. It performs bounded copies only, owns
         * no coroutine, and has no failure/cancellation work beyond normal observer delivery.
         */
        override fun synthesizeSpeech(
            request: SynthesizeSpeechRequest,
            responseObserver: StreamObserver<SynthesizeSpeechResponse>,
        ) {
            requests += request
            responseObserver.onNext(
                SynthesizeSpeechResponse.newBuilder()
                    .setAudioContent(ByteString.copyFrom(responseBytes))
                    .build(),
            )
            responseObserver.onCompleted()
        }
    }

    /**
     * Returns one injected gRPC status for each unary synthesis request.
     *
     * @param status fake provider status.
     *
     * The service runs synchronously on the direct executor, performs no external I/O or coroutine
     * work, and owns no cancellation resource after delivering the failure.
     */
    private class FailingTtsService(
        private val status: Status,
    ) : TextToSpeechGrpc.TextToSpeechImplBase() {

        /**
         * Fails the response observer with the configured status.
         *
         * @param request incoming request, deliberately not inspected.
         * @param responseObserver observer receiving the injected failure.
         * @return This callback has no return value.
         *
         * The direct executor invokes this synchronously; it performs no I/O, suspension, or
         * independent cancellation and cannot expose production credentials.
         */
        override fun synthesizeSpeech(
            request: SynthesizeSpeechRequest,
            responseObserver: StreamObserver<SynthesizeSpeechResponse>,
        ) {
            responseObserver.onError(status.asRuntimeException())
        }
    }

    /**
     * Keeps one unary response open and reports when client cancellation reaches the server.
     *
     * Deferred signals cross from the direct gRPC executor to the test coroutine without blocking.
     * The fake performs no network/audio work and owns no resource beyond the active RPC context.
     */
    private class CancellationTtsService : TextToSpeechGrpc.TextToSpeechImplBase() {
        /** Signals that the server received a unary synthesis request. */
        val requestReceived = CompletableDeferred<Unit>()

        /** Signals that collector cancellation propagated through the in-process transport. */
        val cancellationObserved = CompletableDeferred<Unit>()

        /**
         * Records the request and leaves its response open until client cancellation.
         *
         * @param request incoming request, used only as proof the call started.
         * @param responseObserver intentionally unused open response observer.
         * @return This callback has no return value.
         *
         * The direct executor invokes this synchronously. The Context listener is event-driven and
         * non-blocking; it completes a coroutine signal when gRPC cancels the call.
         */
        override fun synthesizeSpeech(
            request: SynthesizeSpeechRequest,
            responseObserver: StreamObserver<SynthesizeSpeechResponse>,
        ) {
            val callContext = Context.current()
            callContext.addListener(
                { cancellationObserved.complete(Unit) },
                DIRECT_EXECUTOR,
            )
            requestReceived.complete(Unit)
        }
    }

    /**
     * Owns one in-process server/channel and CloudTtsSynthesizer for a test scenario.
     *
     * @param service fake Google TTS service installed in the in-memory server.
     * @param tokenProvider fake bearer provider; defaults to a non-secret value.
     * @param apiKeyProvider fake API-key provider; defaults to a non-secret value.
     *
     * Construction starts only in-process direct-executor resources and cannot access DNS, TLS,
     * Android, or Google. [close] is idempotent and bounds server shutdown to one second.
     */
    private class GrpcFixture(
        service: TextToSpeechGrpc.TextToSpeechImplBase,
        tokenProvider: AccessTokenProvider = AccessTokenProvider { "test-token" },
        apiKeyProvider: ApiKeyProvider = ApiKeyProvider { "test-api-key" },
    ) : AutoCloseable {
        /** Unique in-process transport name isolating this fixture from other tests. */
        private val serverName = InProcessServerBuilder.generateName()

        /** Credential metadata captured for assertions without logging. */
        val metadata = CredentialMetadataInterceptor()

        /** Running in-process server hosting the supplied fake service. */
        private val server: Server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .intercept(metadata)
                .addService(service)
                .build()
                .start()

        /** Reusable in-process channel injected into the synthesizer. */
        private val channel: ManagedChannel =
            InProcessChannelBuilder.forName(serverName).directExecutor().build()

        /** Provider-neutral Phase 3 synthesizer under test. */
        val client =
            CloudTtsSynthesizer(
                accessTokenProvider = tokenProvider,
                apiKeyProvider = apiKeyProvider,
                channel = channel,
                quotaProjectId = "test-quota-project",
            )

        /**
         * Closes the synthesizer and force-stops the in-process server.
         *
         * @return This function has no return value.
         *
         * JUnit invokes it on the test thread through `use`. It is idempotent, performs no external
         * I/O, and may block for at most one second while direct-executor callbacks terminate.
         */
        override fun close() {
            client.close()
            server.shutdownNow()
            server.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    /**
     * Captures mutually exclusive fake credential metadata from in-process calls.
     *
     * The interceptor runs on the direct executor, performs no blocking, logging, or network work,
     * and overwrites test-only values for each call. It owns no coroutine/cancellation resource.
     */
    private class CredentialMetadataInterceptor : ServerInterceptor {
        /** Latest fake API-key value, or null when absent. */
        @Volatile var apiKey: String? = null
            private set

        /** Latest fake Authorization value, or null when absent. */
        @Volatile var authorization: String? = null
            private set

        /** Latest fake quota-project value, or null when absent. */
        @Volatile var quotaProjectId: String? = null
            private set

        /**
         * Captures credential headers and continues the in-process call.
         *
         * @param call active in-process server call.
         * @param headers metadata attached by the client.
         * @param next next fake-server handler.
         * @return listener created by the downstream handler.
         *
         * The direct executor invokes this synchronously. It performs no I/O/suspension, propagates
         * downstream failures, and leaves cancellation to the normal gRPC call lifecycle.
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
    }

    private companion object {
        /** Direct callback executor used only by the in-process cancellation fake. */
        val DIRECT_EXECUTOR = Executor { command -> command.run() }

        /** Test metadata key matching the production API-key header. */
        val API_KEY_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)

        /** Test metadata key matching the production bearer header. */
        val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** Test metadata key matching optional bearer quota attribution. */
        val QUOTA_PROJECT_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-goog-user-project", Metadata.ASCII_STRING_MARSHALLER)
    }
}

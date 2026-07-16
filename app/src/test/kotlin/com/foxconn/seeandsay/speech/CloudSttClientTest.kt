package com.foxconn.seeandsay.speech

import com.foxconn.seeandsay.config.AccessTokenProvider
import com.foxconn.seeandsay.config.CloudSpeechNotConfiguredException
import com.foxconn.seeandsay.config.FakeAccessTokenProvider
import com.google.cloud.speech.v1.SpeechGrpc
import com.google.cloud.speech.v1.SpeechRecognitionAlternative
import com.google.cloud.speech.v1.StreamingRecognitionResult
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import io.grpc.ManagedChannel
import io.grpc.Server
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies CloudSttClient against deterministic in-process gRPC services with no external network.
 *
 * Each test owns an in-memory server/channel fixture and closes it in `finally`. Tests execute in a
 * coroutine test scope while gRPC uses a direct executor, so callback ordering is deterministic.
 * Failures indicate request ordering, response mapping, status recovery, authentication gating, or
 * structured cancellation regressions; no microphone, credential, DNS, TLS, or Google API is used.
 */
class CloudSttClientTest {

    /**
     * Verifies config-first request ordering, audio payloads, and interim/final result mapping.
     *
     * @return This test has no return value.
     *
     * [runTest] collects one cold client Flow. The in-process fake returns two fabricated responses
     * without blocking; fixture cleanup cancels any leaked RPC and fails assertions on mismatches.
     */
    @Test
    fun sendsConfigBeforeAudioAndMapsInterimAndFinalResults() =
        runTest {
            val service = SuccessfulSpeechService()
            GrpcFixture(service).use { fixture ->
                val firstChunk = byteArrayOf(1, 2, 3, 4)
                val secondChunk = byteArrayOf(5, 6)

                val results = fixture.client.stream(flowOf(firstChunk, secondChunk)).toList()

                assertEquals(3, service.requests.size)
                val configRequest = service.requests[0]
                assertTrue(configRequest.hasStreamingConfig())
                assertTrue(configRequest.audioContent.isEmpty)
                val recognitionConfig = configRequest.streamingConfig.config
                assertEquals("LINEAR16", recognitionConfig.encoding.name)
                assertEquals(16_000, recognitionConfig.sampleRateHertz)
                assertEquals(1, recognitionConfig.audioChannelCount)
                assertEquals("cmn-Hant-TW", recognitionConfig.languageCode)
                assertEquals("latest_short", recognitionConfig.model)
                assertEquals(1, recognitionConfig.maxAlternatives)
                assertTrue(configRequest.streamingConfig.interimResults)

                assertFalse(service.requests[1].hasStreamingConfig())
                assertArrayEquals(firstChunk, service.requests[1].audioContent.toByteArray())
                assertFalse(service.requests[2].hasStreamingConfig())
                assertArrayEquals(secondChunk, service.requests[2].audioContent.toByteArray())

                assertEquals(2, results.size)
                assertEquals("你", results[0].transcript)
                assertFalse(results[0].isFinal)
                assertNull(results[0].confidence)
                assertEquals("你好", results[1].transcript)
                assertTrue(results[1].isFinal)
                assertEquals(0.87f, results[1].confidence ?: -1f, 0.0001f)
            }
        }

    /**
     * Verifies all required gRPC status codes become fixed recoverable CloudSttException reasons.
     *
     * @return This test has no return value.
     *
     * [runTest] creates an isolated in-process service per status and performs no real network work.
     * Each Flow must fail deterministically rather than hang or expose StatusRuntimeException.
     */
    @Test
    fun mapsRequiredGrpcStatusesToRecoverableFailures() =
        runTest {
            val cases =
                listOf(
                    Status.UNAUTHENTICATED to CloudSttFailureReason.Unauthenticated,
                    Status.PERMISSION_DENIED to CloudSttFailureReason.PermissionDenied,
                    Status.RESOURCE_EXHAUSTED to CloudSttFailureReason.QuotaExceeded,
                    Status.UNAVAILABLE to CloudSttFailureReason.Unavailable,
                    Status.DEADLINE_EXCEEDED to CloudSttFailureReason.Timeout,
                )

            cases.forEach { (status, expectedReason) ->
                GrpcFixture(FailingSpeechService(status)).use { fixture ->
                    val error = collectFailure(fixture.client.stream(flowOf(byteArrayOf(1, 2))))
                    assertTrue(error is CloudSttException)
                    assertEquals(expectedReason, (error as CloudSttException).reason)
                    assertFalse(error.message.orEmpty().contains("configured-test-value"))
                }
            }
        }

    /**
     * Verifies missing local authentication fails before the audio Flow or RPC is collected.
     *
     * @return This test has no return value.
     *
     * The coroutine test injects the typed Phase 4 failure. No server callback, microphone Flow, or
     * network work may begin; the resulting provider-neutral error must remain recoverable.
     */
    @Test
    fun missingTokenFailsRecoverablyBeforeAudioCollection() =
        runTest {
            val service = SuccessfulSpeechService()
            var audioWasCollected = false
            val missingProvider =
                AccessTokenProvider {
                    throw CloudSpeechNotConfiguredException()
                }
            GrpcFixture(service, missingProvider).use { fixture ->
                val audio =
                    flow {
                        audioWasCollected = true
                        emit(byteArrayOf(1, 2))
                    }

                val error = collectFailure(fixture.client.stream(audio))

                assertTrue(error is CloudSttException)
                assertEquals(
                    CloudSttFailureReason.NotConfigured,
                    (error as CloudSttException).reason,
                )
                assertFalse(audioWasCollected)
                assertTrue(service.requests.isEmpty())
            }
        }

    /**
     * Verifies collector cancellation cancels the RPC and creates no user-visible cloud failure.
     *
     * @return This test has no return value.
     *
     * [runBlocking] uses real time because gRPC's credentials executor is external to the coroutine
     * test scheduler. The service must observe CANCELLED within a bounded event-driven wait; normal
     * CancellationException is swallowed only by the test collector and no other error escapes.
     */
    @Test
    fun collectorCancellationCancelsRpcWithoutUserError() =
        runBlocking {
            val service = CancellationSpeechService()
            val userErrors = mutableListOf<Throwable>()
            GrpcFixture(service).use { fixture ->
                val collectionJob =
                    launch {
                        try {
                            fixture.client
                                .stream(
                                    flow {
                                        awaitCancellation()
                                    },
                                ).collect()
                        } catch (error: CancellationException) {
                            // Structured collector cancellation is expected and is not a user error.
                        } catch (error: Throwable) {
                            userErrors += error
                        }
                    }

                withTimeout(1_000) { service.configReceived.await() }
                collectionJob.cancelAndJoin()
                val cancellationStatus = withTimeout(1_000) { service.cancelled.await() }

                assertEquals(Status.Code.CANCELLED, cancellationStatus.code)
                assertTrue(userErrors.isEmpty())
            }
        }

    /**
     * Collects a Flow expected to fail and returns the thrown error for assertions.
     *
     * @param flow provider-neutral STT result Flow expected to terminate exceptionally.
     * @return the non-cancellation failure produced by [flow].
     *
     * This suspend helper runs in the caller's test scope, performs no I/O itself, and propagates
     * cancellation. Normal completion fails the test because the scenario requires recovery state.
     */
    private suspend fun collectFailure(flow: Flow<SttResult>): Throwable =
        try {
            flow.collect()
            fail("Expected cloud STT Flow failure")
            AssertionError("unreachable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }

    /**
     * Records client requests and emits one interim plus one final response after the first audio.
     *
     * gRPC invokes observer functions on the fixture's direct executor. The synchronized request list
     * is safe for test assertions after collection. The service performs no blocking work, external
     * I/O, or coroutine work; client cancellation is accepted through [RequestObserver.onError].
     */
    private class SuccessfulSpeechService : SpeechGrpc.SpeechImplBase() {
        val requests: MutableList<StreamingRecognizeRequest> =
            Collections.synchronizedList(mutableListOf())
        private val responsesSent = AtomicBoolean(false)

        /**
         * Creates the fake bidirectional request observer for one test RPC.
         *
         * @param responseObserver observer receiving fabricated Google response protos.
         * @return observer that records config/audio and half-close/cancellation events.
         *
         * gRPC calls this on the direct executor. It performs no blocking work or suspension and
         * owns no resource beyond the RPC; cancellation ends through the returned observer.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> = RequestObserver(responseObserver)

        /**
         * Records requests and owns fake response/completion callbacks for one successful RPC.
         *
         * @param responseObserver response side supplied by the in-process gRPC server.
         *
         * gRPC invokes every method on the direct executor. The observer performs no external I/O,
         * blocking, or coroutine launching; fabricated response failures propagate through gRPC,
         * while client cancellation is accepted without creating an additional test error.
         */
        private inner class RequestObserver(
            private val responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ) : StreamObserver<StreamingRecognizeRequest> {

            /**
             * Records config/audio and emits deterministic results after the first audio request.
             *
             * @param value incoming Google request proto.
             * @return This callback has no return value.
             *
             * Called on the direct gRPC executor; it is synchronous/non-blocking and emits once.
             */
            override fun onNext(value: StreamingRecognizeRequest) {
                requests += value
                if (!value.audioContent.isEmpty && responsesSent.compareAndSet(false, true)) {
                    responseObserver.onNext(createResponse("你", isFinal = false, confidence = 0f))
                    responseObserver.onNext(createResponse("你好", isFinal = true, confidence = 0.87f))
                }
            }

            /**
             * Accepts expected client cancellation without creating another provider error.
             *
             * @param throwable cancellation/input failure from the client.
             * @return This callback has no return value.
             *
             * Called by gRPC on its direct executor; it performs no work, blocking, or suspension.
             */
            override fun onError(throwable: Throwable) = Unit

            /**
             * Completes the fake response stream after the client half-closes audio input.
             *
             * @return This callback has no return value.
             *
             * Called synchronously by gRPC; completion is non-blocking and owns no cancellable work.
             */
            override fun onCompleted() {
                responseObserver.onCompleted()
            }
        }
    }

    /**
     * Fails each bidirectional RPC with one injected gRPC status after the first request.
     *
     * @param status fake provider status to return.
     *
     * The service runs on the fixture's direct executor, performs no blocking/external I/O, and owns
     * no coroutine. All callbacks are deterministic and cancellation-safe through no-op termination.
     */
    private class FailingSpeechService(
        private val status: Status,
    ) : SpeechGrpc.SpeechImplBase() {

        /**
         * Creates an observer that fails the response side when its first request arrives.
         *
         * @param responseObserver observer receiving the injected status failure.
         * @return non-blocking request observer for the fake RPC.
         *
         * gRPC invokes all callbacks on the direct executor; no coroutine or resource is created.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> =
            object : StreamObserver<StreamingRecognizeRequest> {
                private val failed = AtomicBoolean(false)

                /**
                 * Fails the response stream once when the client sends its first request.
                 *
                 * @param value incoming config/audio request; its content is irrelevant here.
                 * @return This callback has no return value.
                 *
                 * gRPC invokes the callback synchronously on the direct executor. It performs no
                 * blocking, suspension, or external I/O; duplicate requests are ignored. The
                 * deliberate status failure is delivered through the response observer.
                 */
                override fun onNext(value: StreamingRecognizeRequest) {
                    if (failed.compareAndSet(false, true)) {
                        responseObserver.onError(status.asRuntimeException())
                    }
                }

                /**
                 * Accepts client termination after the injected status without additional work.
                 *
                 * @param throwable client-side termination cause, deliberately ignored by the fake.
                 * @return This callback has no return value.
                 *
                 * The callback runs on the direct gRPC executor, performs no I/O or suspension, and
                 * owns no cancellable resource. It cannot create a test-specific failure.
                 */
                override fun onError(throwable: Throwable) = Unit

                /**
                 * Accepts client half-close after the injected status without additional work.
                 *
                 * @return This callback has no return value.
                 *
                 * The callback runs on the direct gRPC executor, performs no I/O or suspension, and
                 * has no failure or cancellation behavior because the fake response already ended.
                 */
                override fun onCompleted() = Unit
            }
    }

    /**
     * Waits for config and records the status delivered when the client cancels an active RPC.
     *
     * Deferred signals are completed from the fixture's direct gRPC executor and awaited by the test
     * coroutine. The fake performs no blocking/external I/O and has no resource beyond its RPC.
     */
    private class CancellationSpeechService : SpeechGrpc.SpeechImplBase() {
        val configReceived = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Status>()

        /**
         * Creates an observer that keeps the response stream open until client cancellation.
         *
         * @param responseObserver unused response observer retained by gRPC for the open RPC.
         * @return observer exposing config receipt and cancellation signals.
         *
         * All callbacks run on the direct executor and perform no blocking or coroutine launching.
         */
        override fun streamingRecognize(
            responseObserver: StreamObserver<StreamingRecognizeResponse>,
        ): StreamObserver<StreamingRecognizeRequest> =
            object : StreamObserver<StreamingRecognizeRequest> {
                /**
                 * Signals receipt of the required first config request without blocking.
                 *
                 * @param value incoming config or audio request.
                 * @return This callback has no return value.
                 *
                 * The callback runs on the direct gRPC executor and completes a coroutine signal
                 * only for configuration. It performs no I/O or suspension; duplicate completion
                 * is harmless and cancellation is observed separately through [onError].
                 */
                override fun onNext(value: StreamingRecognizeRequest) {
                    if (value.hasStreamingConfig()) configReceived.complete(Unit)
                }

                /**
                 * Records the cancellation status delivered by the in-process transport.
                 *
                 * @param throwable transport cancellation/failure delivered by gRPC.
                 * @return This callback has no return value.
                 *
                 * The callback runs on the direct executor, converts the throwable synchronously,
                 * and performs no I/O or suspension. Duplicate termination signals are harmless.
                 */
                override fun onError(throwable: Throwable) {
                    cancelled.complete(Status.fromThrowable(throwable))
                }

                /**
                 * Completes quietly if the client unexpectedly half-closes before cancellation.
                 *
                 * @return This callback has no return value.
                 *
                 * The callback executes synchronously on the direct gRPC executor and performs no
                 * I/O, suspension, failure mapping, or independent cancellation.
                 */
                override fun onCompleted() = Unit
            }
    }

    /**
     * Owns one in-process server, channel, and CloudSttClient for a deterministic test scenario.
     *
     * @param service fake Speech service implementation installed in the in-process server.
     * @param tokenProvider provider used by the client; defaults to a non-secret fake value.
     *
     * Construction starts only in-memory gRPC resources with direct executors and cannot access DNS,
     * TLS, Google, or Android. [close] promptly cancels active calls and shuts down both resources;
     * setup failures propagate to fail the test.
     */
    private class GrpcFixture(
        service: SpeechGrpc.SpeechImplBase,
        tokenProvider: AccessTokenProvider = FakeAccessTokenProvider(),
    ) : AutoCloseable {
        private val serverName = InProcessServerBuilder.generateName()
        private val server: Server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start()
        private val channel: ManagedChannel =
            InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build()

        val client =
            CloudSttClient(
                accessTokenProvider = tokenProvider,
                channel = channel,
                quotaProjectId = "test-quota-project",
            )

        /**
         * Cancels the client channel and fake server, then briefly awaits server termination.
         *
         * @return This function has no return value.
         *
         * JUnit calls this from the test coroutine's thread in `use` cleanup. Shutdown is idempotent;
         * it may block for at most one second waiting for in-process callbacks and owns no coroutine.
         */
        override fun close() {
            client.close()
            server.shutdownNow()
            server.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    companion object {
        /**
         * Creates one fabricated response containing one alternative/result pair.
         *
         * @param transcript fake recognized text.
         * @param isFinal whether the result is committed.
         * @param confidence fake confidence stored in the alternative.
         * @return immutable Google response proto used only inside this test fake.
         *
         * This pure builder performs no I/O, suspension, or cancellation and fails only on normal
         * protobuf allocation errors.
         */
        private fun createResponse(
            transcript: String,
            isFinal: Boolean,
            confidence: Float,
        ): StreamingRecognizeResponse {
            val alternative =
                SpeechRecognitionAlternative.newBuilder()
                    .setTranscript(transcript)
                    .setConfidence(confidence)
                    .build()
            val result =
                StreamingRecognitionResult.newBuilder()
                    .setIsFinal(isFinal)
                    .addAlternatives(alternative)
                    .build()
            return StreamingRecognizeResponse.newBuilder().addResults(result).build()
        }
    }
}

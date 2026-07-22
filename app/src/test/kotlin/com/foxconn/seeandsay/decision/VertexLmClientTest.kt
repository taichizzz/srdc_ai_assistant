package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.config.FakeAccessTokenProvider
import com.foxconn.seeandsay.config.GcpLmConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deterministic HTTP-contract tests for the live Vertex REST adapter; no real network is used. */
class VertexLmClientTest {

    private lateinit var server: MockWebServer

    /** Starts one loopback server for each test without a cloud credential or external dependency. */
    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    /** Stops the loopback server and releases queued/in-flight test calls. */
    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Proves the regional publisher path, bearer header, JSON mode, and fixed schema reach the wire.
     *
     * The test runs synchronously over loopback, logs no token/body, and leaves no live request.
     */
    @Test
    fun outgoingRequestUsesPublisherPathAndCarriesResponseSchema() =
        runBlocking {
            server.enqueue(vertexResponse(validGoalResponse()))

            val result = client().complete(lmRequest())

            assertEquals(validGoalResponse(), result)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            requireNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals(EXPECTED_PATH, recorded.path)
            assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
            val root = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val generation = root.getValue("generationConfig").jsonObject
            assertEquals("application/json", generation.getValue("responseMimeType").jsonPrimitive.content)
            val schema = generation.getValue("responseSchema").jsonObject
            assertEquals("OBJECT", schema.getValue("type").jsonPrimitive.content)
            assertEquals(
                EXPECTED_INTENTS,
                schema
                    .getValue("properties")
                    .jsonObject
                    .getValue("intent")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            val direction =
                schema
                    .getValue("properties")
                    .jsonObject
                    .getValue("direction")
                    .jsonObject
            assertEquals("STRING", direction.getValue("type").jsonPrimitive.content)
            assertTrue(direction.getValue("nullable").jsonPrimitive.content.toBoolean())
            val controlQuery =
                schema
                    .getValue("properties")
                    .jsonObject
                    .getValue("control_query")
                    .jsonObject
            assertEquals("STRING", controlQuery.getValue("type").jsonPrimitive.content)
            assertTrue(controlQuery.getValue("nullable").jsonPrimitive.content.toBoolean())

            val prompt =
                root
                    .getValue("contents")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("parts")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive
                    .content
            assertTrue(prompt.contains("設定"))
            assertFalse(prompt.contains("\"i\""))
            assertFalse(prompt.contains("bounds"))
        }

    /**
     * Verifies a mocked Vertex envelope flows through the strict interpreter into a domain goal.
     *
     * The test uses only loopback HTTP and deterministic JSON; it performs no provider call.
     */
    @Test
    fun validVertexEnvelopeResolvesThroughInterpreter() =
        runBlocking {
            server.enqueue(vertexResponse(validGoalResponse()))
            val interpreter = LmIntentInterpreter(client())

            val result = interpreter.interpret("字太小了", SCREEN)

            assertEquals(
                IntentResult.Resolved(UserGoal.AdjustTextSize(Direction.Increase), 0.91f),
                result,
            )
            assertEquals(1, server.requestCount)
        }

    /**
     * Verifies documented HTTP failures become stable recoverable categories, never raw bodies.
     *
     * Each case uses one loopback response and closes it before the next; no retry or live I/O occurs.
     */
    @Test
    fun providerStatusesMapToRecoverableCategories() =
        runBlocking {
            val cases =
                listOf(
                    401 to LmClientFailureReason.Authentication,
                    403 to LmClientFailureReason.Permission,
                    429 to LmClientFailureReason.Quota,
                    504 to LmClientFailureReason.Timeout,
                )
            cases.forEach { (status, expected) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive test body"))

                val failure = captureFailure { client().complete(lmRequest()) }

                assertEquals(expected, failure.reason)
                assertFalse(failure.message.orEmpty().contains("sensitive test body"))
            }
        }

    /**
     * Proves the bounded OkHttp deadline maps a non-responsive socket to Timeout.
     *
     * MockWebServer intentionally withholds a response; the client's 100 ms call timeout ends the
     * request. No sleep, emulator, device, or external network is involved.
     */
    @Test
    fun boundedClientTimeoutMapsToRecoverableTimeout() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val failure =
                captureFailure {
                    client(requestTimeoutMillis = 100L).complete(lmRequest())
                }

            assertEquals(LmClientFailureReason.Timeout, failure.reason)
        }

    /**
     * Proves coroutine cancellation cancels a pending OkHttp call and remains cancellation.
     *
     * The loopback server withholds its response. The parent cancels and joins the child directly;
     * no user-visible result, retry, fixed sleep, external request, or leaked child remains.
     */
    @Test
    fun cancellationCancelsInFlightCallWithoutUserError() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { client().complete(lmRequest()) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            pending.cancelAndJoin()

            assertTrue(pending.isCancelled)
        }

    /**
     * Proves false feature gating invokes neither factory, token provider, nor loopback server.
     *
     * Construction is checked synchronously and performs no coroutine, network, or provider work.
     */
    @Test
    fun disabledFeatureDoesNotConstructClientOrMakeRequest() {
        var constructed = false
        val tokenProvider = FakeAccessTokenProvider(token = "unused")

        val interpreter =
            LmIntentInterpreter.createWhenEnabled(enabled = false) {
                constructed = true
                VertexLmClient(tokenProvider, CONFIG, server.url("/").toString())
            }

        assertNull(interpreter)
        assertFalse(constructed)
        assertEquals(0, tokenProvider.requestCount)
        assertEquals(0, server.requestCount)
    }

    /**
     * Captures one expected typed client failure from a suspend block.
     *
     * @param block request expected to fail recoverably.
     * @return caught typed failure.
     * @throws AssertionError when the block succeeds or throws a different exception.
     *
     * This test helper adds no coroutine, delay, I/O, or logging of its own.
     */
    private suspend fun captureFailure(block: suspend () -> Unit): LmClientException =
        try {
            block()
            throw AssertionError("Expected LmClientException")
        } catch (error: LmClientException) {
            error
        }

    /** Returns a Vertex client pointed only at this test's loopback server. */
    private fun client(requestTimeoutMillis: Long = 2_000L): VertexLmClient =
        VertexLmClient(
            accessTokenProvider = FakeAccessTokenProvider(token = "test-token"),
            config = CONFIG,
            baseUrlOverride = server.url("/").toString(),
            requestTimeoutMillis = requestTimeoutMillis,
        )

    /** Returns the provider-neutral request used to inspect Vertex request conversion. */
    private fun lmRequest(): LmRequest =
        LmRequest("打開設定", SCREEN.toLmScreenContext(), LmIntentInterpreter.RESPONSE_SCHEMA)

    /** Wraps interpreter JSON in the documented Vertex candidate/content/part envelope. */
    private fun vertexResponse(structuredText: String): MockResponse {
        val envelope =
            buildJsonObject {
                putJsonArray("candidates") {
                    add(
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonObject {
                                    putJsonArray("parts") {
                                        add(buildJsonObject { put("text", structuredText) })
                                    }
                                },
                            )
                        },
                    )
                }
            }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(envelope.toString())
    }

    /** Returns one schema-valid high-confidence adjustment goal response. */
    private fun validGoalResponse(): String =
        """{"intent":"adjust_text_size","direction":"increase","target":null,"control_query":null,"confidence":0.91,"needs_clarification":false,"clarification_question":null}"""

    /** Stable test configuration, snapshot, path, and schema enums. */
    companion object {
        private val CONFIG = GcpLmConfig("test-project", "us-central1", "gemini-2.5-flash")
        private val SCREEN =
            ScreenSnapshot(
                screen = "launcher",
                capturedAt = 1L,
                elements =
                    listOf(
                        ScreenElement(7, "設定", clickable = true, editable = false, bounds = listOf(1, 2, 3, 4)),
                    ),
            )
        private const val EXPECTED_PATH =
            "/v1/projects/test-project/locations/us-central1/publishers/google/models/gemini-2.5-flash:generateContent"
        private val EXPECTED_INTENTS =
            listOf(
                "adjust_text_size",
                "adjust_volume",
                "adjust_brightness",
                "open_target",
                "go_back",
                "stop",
                "unknown",
            )
    }
}

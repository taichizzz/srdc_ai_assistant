package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies strict LM intent schema validation against a pure fake client on the JVM.
 *
 * Tests perform no network, provider SDK, credential, Android, accessibility, filesystem, timer,
 * delay, or logging work. Virtual coroutine execution covers suspension/cancellation deterministically.
 */
class LmIntentInterpreterTest {

    /**
     * Verifies one valid response resolves the target and preserves validated confidence/context.
     *
     * @return no value; assertion failure reports request or goal-mapping drift.
     *
     * This virtual-time test uses pure in-memory data and performs no network, delay, or timer work.
     */
    @Test
    fun validResponseResolvesGoalAndConfidence() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        response(
                            "open_target",
                            target = "系統設定",
                            controlQuery = "設定",
                            confidence = "0.91",
                        ),
                    ),
                )
            val interpreter = LmIntentInterpreter(client)

            val result = interpreter.interpret("請幫我開啟設定", screen())

            assertEquals(
                IntentResult.Resolved(UserGoal.OpenTarget("系統設定", "設定"), 0.91f),
                result,
            )
            assertEquals(1, client.requests().size)
            assertEquals("請幫我開啟設定", client.requests().single().transcript)
            assertEquals(screen().toLmScreenContext(), client.requests().single().screen)
            assertEquals(
                LmIntentInterpreter.RESPONSE_SCHEMA,
                client.requests().single().responseSchema,
            )
        }

    /**
     * Verifies every allowed intent and direction enum maps to its index-free domain goal.
     *
     * @return no value; assertion failure reports incomplete or incorrect schema enum mapping.
     *
     * This virtual-time test performs in-memory fake calls only, with no I/O, delay, or timer work.
     */
    @Test
    fun everyIntentAndDirectionMapsToCorrectGoal() =
        runTest {
            val cases =
                listOf(
                    response("adjust_text_size", direction = "increase") to
                        UserGoal.AdjustTextSize(Direction.Increase),
                    response("adjust_volume", direction = "decrease") to
                        UserGoal.AdjustVolume(Direction.Decrease),
                    response("adjust_brightness", direction = "increase") to
                        UserGoal.AdjustBrightness(Direction.Increase),
                    response("open_target", target = "導航") to UserGoal.OpenTarget("導航"),
                    response("go_back") to UserGoal.GoBack,
                    response("stop") to UserGoal.Stop,
                    response("unknown") to UserGoal.Unknown,
                )

            cases.forEach { (rawResponse, expectedGoal) ->
                val result =
                    LmIntentInterpreter(FakeLmClient(listOf(rawResponse)))
                        .interpret("unmatched", screen())
                assertEquals(IntentResult.Resolved(expectedGoal, 0.9f), result)
            }
        }

    /**
     * Verifies malformed JSON is attempted exactly twice before returning no-match.
     *
     * @return no value; assertion failure reports missing/beyond-limit retry behavior.
     *
     * This virtual-time test performs no network, real delay, timer, or external I/O.
     */
    @Test
    fun malformedJsonRetriesExactlyOnceThenReturnsNoMatch() =
        runTest {
            val client = FakeLmClient(listOf("not-json"))

            val result = LmIntentInterpreter(client).interpret("unmatched", screen())

            assertEquals(IntentResult.NoMatch, result)
            assertEquals(2, client.requests().size)
        }

    /**
     * Verifies a rejected first attempt may recover only through one valid second attempt.
     *
     * @return no value; assertion failure reports retry sequencing or validation bypass.
     *
     * This virtual-time test uses only scripted strings and performs no network or timer work.
     */
    @Test
    fun oneRetryCanRecoverWithValidSecondResponse() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        "{broken",
                        response("go_back", confidence = "0.82"),
                    ),
                )

            val result = LmIntentInterpreter(client).interpret("返回", screen())

            assertEquals(IntentResult.Resolved(UserGoal.GoBack, 0.82f), result)
            assertEquals(2, client.requests().size)
        }

    /**
     * Verifies unknown intent names and missing required fields are strict schema rejections.
     *
     * @return no value; assertion failure reports accepting an unrecognized/incomplete schema.
     *
     * This virtual-time test performs pure fake completion only, no network, delay, or timer work.
     */
    @Test
    fun unknownIntentAndMissingFieldsAreRejected() =
        runTest {
            val invalidResponses =
                listOf(
                    response("launch_missiles"),
                    """{"intent":"go_back","confidence":0.9}""",
                )

            invalidResponses.forEach { invalid ->
                val client = FakeLmClient(listOf(invalid))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("unmatched", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies unknown direction values and semantically missing confident directions are rejected.
     *
     * @return no value; assertion failure reports unsafe adjustment-goal construction.
     *
     * This virtual-time test performs no network, provider, delay, timer, or external I/O.
     */
    @Test
    fun invalidDirectionEnumAndMissingRequiredDirectionAreRejected() =
        runTest {
            listOf(
                response("adjust_volume", direction = "sideways"),
                response("adjust_volume", direction = null),
            ).forEach { invalid ->
                val client = FakeLmClient(listOf(invalid))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("音量調整", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies a present control query must be a non-blank semantic string.
     *
     * @return no value; assertion failure reports accepting an unusable grounding description.
     *
     * This virtual-time test uses scripted JSON only and performs no network, timer, delay, or I/O.
     */
    @Test
    fun blankOrNonStringControlQueryIsRejected() =
        runTest {
            val invalidResponses =
                listOf(
                    response("adjust_text_size", direction = "increase", controlQuery = "   "),
                    response("go_back").replace("\"control_query\":null", "\"control_query\":3"),
                )
            invalidResponses.forEach { invalid ->
                val client = FakeLmClient(listOf(invalid))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("文字放大", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies confidence above or below the schema range is rejected rather than clamped.
     *
     * @return no value; assertion failure reports unsafe out-of-range confidence acceptance.
     *
     * This virtual-time test performs pure parsing only, no network, delay, or timer work.
     */
    @Test
    fun outOfRangeConfidenceIsRejected() =
        runTest {
            listOf("1.7", "-0.2").forEach { confidence ->
                val client = FakeLmClient(listOf(response("go_back", confidence = confidence)))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("返回", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies any element index is rejected even when every required schema field is also valid.
     *
     * @return no value; assertion failure reports violation of the local index-selection boundary.
     *
     * This virtual-time safety test performs no network, provider, delay, timer, or action work.
     */
    @Test
    fun responseContainingElementIndexIsRejected() =
        runTest {
            listOf(
                response("go_back").dropLast(1) + ",\"element_index\":3}",
                response("open_target", target = "element index 3"),
                response("adjust_text_size", direction = "increase", controlQuery = "index 3"),
            ).forEach { unsafe ->
                val client = FakeLmClient(listOf(unsafe))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("返回", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies named UI functions are rejected even when hidden in an allowed string field.
     *
     * @return no value; assertion failure reports executable model output reaching a goal.
     *
     * This virtual-time safety test performs no action, network, delay, timer, or external I/O.
     */
    @Test
    fun responseContainingUiFunctionCallIsRejected() =
        runTest {
            listOf("ui_click(3)", "setText(2, value)", "performAction()").forEach { uiCall ->
                val client =
                    FakeLmClient(
                        listOf(
                            response(
                                "adjust_text_size",
                                direction = "increase",
                                controlQuery = uiCall,
                            ),
                        ),
                    )
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("unmatched", screen()),
                )
                assertEquals(2, client.requests().size)
            }
        }

    /**
     * Verifies coordinate material and unknown additional properties are rejected strictly.
     *
     * @return no value; assertion failure reports accepting coordinate or widened output schema.
     *
     * This virtual-time safety test performs no action, network, delay, timer, or external I/O.
     */
    @Test
    fun coordinateAndUnknownPropertyOutputIsRejected() =
        runTest {
            val invalidResponses =
                listOf(
                    response("open_target", target = "coordinates (10,20)"),
                    response("open_target", target = "(10,20)"),
                    response(
                        "adjust_brightness",
                        direction = "increase",
                        controlQuery = "coordinates (10,20)",
                    ),
                    response("go_back").dropLast(1) + ",\"action\":\"back\"}",
                )
            invalidResponses.forEach { invalid ->
                val client = FakeLmClient(listOf(invalid))
                assertEquals(
                    IntentResult.NoMatch,
                    LmIntentInterpreter(client).interpret("unmatched", screen()),
                )
            }
        }

    /**
     * Verifies explicit clarification and confidence below 0.70 ask instead of resolving/retrying.
     *
     * @return no value; assertion failure reports unsafe low-confidence guessing.
     *
     * This virtual-time test performs one fake call per case, no network, delay, or timer work.
     */
    @Test
    fun explicitOrLowConfidenceReturnsClarifyWithoutRetry() =
        runTest {
            val explicitClient =
                FakeLmClient(
                    listOf(
                        response(
                            "open_target",
                            target = null,
                            confidence = "0.95",
                            needsClarification = true,
                            question = "你要開啟哪個功能？",
                        ),
                    ),
                )
            assertEquals(
                IntentResult.Clarify("你要開啟哪個功能？"),
                LmIntentInterpreter(explicitClient).interpret("打開", screen()),
            )
            assertEquals(1, explicitClient.requests().size)

            val lowClient = FakeLmClient(listOf(response("go_back", confidence = "0.69")))
            val lowResult = LmIntentInterpreter(lowClient).interpret("可能返回", screen())
            assertTrue(lowResult is IntentResult.Clarify)
            assertTrue((lowResult as IntentResult.Clarify).question.isNotBlank())
            assertEquals(1, lowClient.requests().size)
        }

    /**
     * Verifies the false feature gate does not construct a client and leaves TextMatcher unchanged.
     *
     * @return no value; assertion failure reports eager provider initialization or matcher coupling.
     *
     * This pure synchronous test performs no provider, network, coroutine, delay, or timer work.
     */
    @Test
    fun disabledGateConstructsNoProviderAndDeterministicMatcherStillWorks() {
        var clientConstructed = false

        val interpreter =
            LmIntentInterpreter.createWhenEnabled(enabled = false) {
                clientConstructed = true
                FakeLmClient(listOf(response("go_back")))
            }

        assertNull(interpreter)
        assertFalse(clientConstructed)
        assertEquals(Decision.Click(0), TextMatcher().match("設定", screen()))
    }

    /**
     * Verifies interpreter cancellation propagates unchanged without retry or typed user result.
     *
     * @return no value; assertion failure reports cancellation being swallowed or user-surfaced.
     *
     * This test uses a fake cancellation throwable only, with no network, delay, timer, or I/O.
     */
    @Test
    fun cancellationPropagatesWithoutRetryOrUserVisibleError() {
        val cancellation = CancellationException("test cancellation")
        val client = FakeLmClient(failure = cancellation)

        val thrown =
            assertThrows(CancellationException::class.java) {
                runTest {
                    LmIntentInterpreter(client).interpret("unmatched", screen())
                }
            }

        assertEquals(cancellation, thrown)
        assertEquals(1, client.requests().size)
    }

    /**
     * Verifies provider failures use the same bounded two-attempt no-match recovery.
     *
     * @return no value; assertion failure reports leaked failure or unbounded retry behavior.
     *
     * This virtual-time fake test performs no network, real delay, timer, logging, or external I/O.
     */
    @Test
    fun providerFailureRetriesOnceThenReturnsNoMatch() =
        runTest {
            val client = FakeLmClient(failure = IllegalStateException("provider unavailable"))

            val result = LmIntentInterpreter(client).interpret("unmatched", screen())

            assertEquals(IntentResult.NoMatch, result)
            assertEquals(2, client.requests().size)
        }

    /**
     * Builds one immutable contextual screen for fake interpreter tests.
     *
     * @return home snapshot with one clickable 設定 element and no external resources.
     *
     * This pure factory performs no I/O, accessibility, coroutine, delay, timer, or failure work.
     */
    private fun screen(): ScreenSnapshot =
        ScreenSnapshot(
            screen = "home",
            capturedAt = 1L,
            elements =
                listOf(
                    ScreenElement(
                        i = 0,
                        text = "設定",
                        clickable = true,
                        editable = false,
                        bounds = emptyList(),
                    ),
                ),
        )

    /**
     * Builds one exact seven-field response string from controlled test values.
     *
     * @param intent schema intent text.
     * @param direction increase/decrease/invalid text or JSON null.
     * @param target target string or JSON null.
     * @param controlQuery semantic control description or JSON null.
     * @param confidence raw numeric token allowing range-validation tests.
     * @param needsClarification explicit uncertainty boolean.
     * @param question clarification string or JSON null.
     * @return deterministic raw JSON response for the fake client.
     *
     * This test-only pure formatter performs no general JSON escaping, I/O, coroutine, delay, or
     * timer work; callers supply controlled strings without quotes or backslashes.
     */
    private fun response(
        intent: String,
        direction: String? = null,
        target: String? = null,
        controlQuery: String? = null,
        confidence: String = "0.9",
        needsClarification: Boolean = false,
        question: String? = null,
    ): String =
        """{"intent":"$intent","direction":${direction.jsonStringOrNull()},"target":${target.jsonStringOrNull()},"control_query":${controlQuery.jsonStringOrNull()},"confidence":$confidence,"needs_clarification":$needsClarification,"clarification_question":${question.jsonStringOrNull()}}"""

    /**
     * Formats controlled nullable test text as a JSON string token or null.
     *
     * @receiver controlled test value without quotes/backslashes, or null.
     * @return JSON token used only by [response].
     *
     * This pure test helper performs no I/O, coroutine, delay, timer, or failure-prone work.
     */
    private fun String?.jsonStringOrNull(): String = this?.let { "\"$it\"" } ?: "null"
}

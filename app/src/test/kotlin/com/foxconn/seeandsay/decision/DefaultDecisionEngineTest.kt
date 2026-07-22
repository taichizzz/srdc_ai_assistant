package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies LM-first decision composition, deterministic fallback, planning, and local validation.
 *
 * Tests use pure fakes and immutable snapshots with no live provider, network, Android,
 * accessibility, filesystem, timer, delay, action execution, or credential dependency.
 */
class DefaultDecisionEngineTest {

    /**
     * Verifies every enabled non-empty command reaches the LM, including a direct screen phrase.
     *
     * @return no value; assertion failure reports accidental deterministic-first ordering.
     *
     * This virtual-time test performs no network, real delay, timer, or external I/O.
     */
    @Test
    fun directCommandCallsLmFirstThenGroundsResolvedGoal() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        validResponse(
                            intent = "open_target",
                            target = "系統設定",
                            controlQuery = "設定",
                        ),
                    ),
                )
            var interpreterConstructed = false
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = {
                        interpreterConstructed = true
                        LmIntentInterpreter(client)
                    },
                )

            val decision = engine.decide("打開設定", screen(element(0, "設定")))

            assertEquals(Decision.Click(0), decision)
            assertTrue(interpreterConstructed)
            assertEquals(1, client.requests().size)
            assertEquals("打開設定", client.requests().single().transcript)
        }

    /**
     * Verifies disabled composition resolves a direct command through deterministic fallback only.
     *
     * @return no value; assertion failure reports eager provider initialization or broken fallback.
     *
     * This virtual-time test performs no network, timer, delay, action, or external I/O.
     */
    @Test
    fun disabledLmConstructsNoProviderAndDirectCommandStillClicks() =
        runTest {
            var interpreterConstructed = false
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = false,
                    intentInterpreterFactory = {
                        interpreterConstructed = true
                        LmIntentInterpreter(FakeLmClient(listOf(validResponse("go_back"))))
                    },
                )

            assertEquals(Decision.Click(0), engine.decide("打開設定", screen(element(0, "設定"))))
            assertFalse(interpreterConstructed)
        }

    /**
     * Verifies enabled interpretation resolves a semantic goal then plans a current-screen click.
     *
     * @return no value; assertion failure reports broken interpreter/planner composition.
     *
     * This virtual-time fake test performs no live network, delay, timer, or action execution.
     */
    @Test
    fun enabledInterpreterResolvesGoalAndPlansDecision() =
        runTest {
            val client = FakeLmClient(listOf(validResponse("open_target", target = "導航")))
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = { LmIntentInterpreter(client) },
                )

            val decision = engine.decide("帶我去目的地", screen(element(0, "導航")))

            assertEquals(Decision.Click(0), decision)
            assertEquals(1, client.requests().size)
        }

    /**
     * Verifies an understood off-screen target produces honest speech, never a fabricated click.
     *
     * @return no value; assertion failure reports speculative navigation or false action output.
     *
     * This virtual-time test performs no Android action, network, delay, timer, or external I/O.
     */
    @Test
    fun understoodAbsentTargetSpeaksHonestly() =
        runTest {
            val engine = engineWith(validResponse("open_target", target = "導航"))

            val decision = engine.decide("帶我去目的地", screen(element(0, "設定")))

            assertTrue(decision is Decision.Speak)
            assertTrue((decision as Decision.Speak).text.contains("導航"))
            assertTrue(decision.text.contains("目前畫面找不到"))
        }

    /**
     * Verifies a resolved adjustment query absent from the current screen remains an honest limit.
     *
     * @return no value; assertion failure reports query fallback guessing or a fabricated action.
     *
     * This virtual-time test uses only fake JSON and immutable snapshots with no network or action.
     */
    @Test
    fun resolvedControlQueryAbsentFromScreenSpeaksHonestly() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        validResponse(
                            intent = "adjust_text_size",
                            direction = "increase",
                            controlQuery = "文字大小",
                        ),
                    ),
                )
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = { LmIntentInterpreter(client) },
                )

            val decision = engine.decide("字太小了", screen(element(0, "音量")))

            assertEquals(Decision.Speak("目前畫面沒有可用的文字大小控制項。"), decision)
            assertEquals(1, client.requests().size)
        }

    /**
     * Verifies punctuation/whitespace-only input performs neither LM work nor fallback matching.
     *
     * @return no value; assertion failure reports calling a provider without semantic input.
     *
     * This virtual-time test performs no provider construction, network, timer, delay, or action.
     */
    @Test
    fun normalizedEmptyTranscriptSkipsLmAndReturnsNoMatch() =
        runTest {
            var interpreterConstructed = false
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = {
                        interpreterConstructed = true
                        LmIntentInterpreter(FakeLmClient(listOf(validResponse("go_back"))))
                    },
                )

            assertEquals(Decision.NoMatch, engine.decide(" ！？ ", screen(element(0, "設定"))))
            assertFalse(interpreterConstructed)
        }

    /**
     * Verifies LM grounding ambiguity asks a question after the LM has been consulted.
     *
     * @return no value; assertion failure reports ambiguity guessing or LM bypass.
     *
     * This virtual-time test performs no network, delay, timer, action, or external I/O.
     */
    @Test
    fun resolvedGoalAmbiguityClarifiesAfterLmCall() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        validResponse(
                            "open_target",
                            target = "設定",
                            controlQuery = "設定",
                        ),
                    ),
                )
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = { LmIntentInterpreter(client) },
                )

            val decision =
                engine.decide(
                    "設定",
                    screen(element(0, "設定"), element(1, "設定")),
                )

            assertTrue(decision is Decision.Speak)
            assertTrue((decision as Decision.Speak).text.contains("第一個還是第二個"))
            assertEquals(1, client.requests().size)
        }

    /**
     * Verifies auth/network/timeout failures and malformed-after-retry all enter direct fallback.
     *
     * @return no value; assertion failure reports a provider failure breaking offline direct use.
     *
     * This virtual-time test uses scripted failures only, with no network, timer, delay, or action.
     */
    @Test
    fun enabledLmFailuresFallBackToDirectTextMatching() =
        runTest {
            val failures =
                listOf(
                    LmClientException(
                        LmClientFailureReason.Authentication,
                        "fixed auth test failure",
                    ),
                    LmClientException(LmClientFailureReason.Network, "fixed network test failure"),
                    LmClientException(LmClientFailureReason.Timeout, "fixed timeout test failure"),
                )
            failures.forEach { failure ->
                val client = FakeLmClient(failure = failure)
                val engine =
                    DefaultDecisionEngine(
                        lmEnabled = true,
                        intentInterpreterFactory = { LmIntentInterpreter(client) },
                    )

                assertEquals(
                    Decision.Click(0),
                    engine.decide("打開設定", screen(element(0, "設定"))),
                )
                assertEquals(2, client.requests().size)
            }

            val malformedClient = FakeLmClient(listOf("not-json"))
            val malformedEngine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = { LmIntentInterpreter(malformedClient) },
                )
            assertEquals(
                Decision.Click(0),
                malformedEngine.decide("打開設定", screen(element(0, "設定"))),
            )
            assertEquals(2, malformedClient.requests().size)
        }

    /**
     * Verifies interpreter clarification becomes Speak without local planning or action.
     *
     * @return no value; assertion failure reports clarification loss or guessed action.
     *
     * This virtual-time test performs no live provider, network, delay, timer, or action work.
     */
    @Test
    fun interpreterClarificationBecomesSpeak() =
        runTest {
            val raw =
                validResponse(
                    intent = "open_target",
                    confidence = "0.95",
                    needsClarification = true,
                    question = "你要開啟哪個功能？",
                )

            assertEquals(
                Decision.Speak("你要開啟哪個功能？"),
                engineWith(raw).decide("打開", screen(element(0, "設定"))),
            )
        }

    /**
     * Verifies low-confidence structured output becomes clarification and never falls back to click.
     *
     * @return no value; assertion failure reports a confidence-boundary guess or fallback bypass.
     *
     * This virtual-time fake test performs no network, timer, delay, action, or external I/O.
     */
    @Test
    fun lowConfidenceBecomesSpeakWithoutGuessedAction() =
        runTest {
            val client =
                FakeLmClient(
                    listOf(
                        validResponse(
                            intent = "open_target",
                            target = "設定",
                            controlQuery = "設定",
                            confidence = "0.69",
                        ),
                    ),
                )
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = true,
                    intentInterpreterFactory = { LmIntentInterpreter(client) },
                )

            val decision = engine.decide("打開設定", screen(element(0, "設定")))

            assertTrue(decision is Decision.Speak)
            assertEquals(1, client.requests().size)
        }

    /**
     * Verifies range, uniqueness, clickability, and editability gates reject invalid actions.
     *
     * @return no value; assertion failure reports a stale/mismatched action escaping validation.
     *
     * This pure test performs no coroutine, network, Android action, timer, delay, or I/O.
     */
    @Test
    fun localValidationRejectsInvalidActionCandidates() {
        val nonActionable =
            screen(
                element(0, "標籤", clickable = false, editable = false),
            )
        assertEquals(
            Decision.NoMatch,
            LocalDecisionValidator.validate(Decision.Click(2), nonActionable),
        )
        assertEquals(
            Decision.NoMatch,
            LocalDecisionValidator.validate(Decision.Click(0), nonActionable),
        )
        assertEquals(
            Decision.NoMatch,
            LocalDecisionValidator.validate(Decision.SetText(0, "value"), nonActionable),
        )
        val duplicateIndex =
            screen(
                element(0, "A", clickable = true),
                element(0, "B", clickable = true),
            )
        assertEquals(
            Decision.NoMatch,
            LocalDecisionValidator.validate(Decision.Click(0), duplicateIndex),
        )
    }

    /**
     * Verifies an injected planner cannot bypass action validation with invalid decisions.
     *
     * @return no value; assertion failure reports composition-level validation bypass.
     *
     * This virtual-time test performs no network, Android action, delay, timer, or external I/O.
     */
    @Test
    fun plannerActionsAreValidatedBeforeEmission() =
        runTest {
            val screen = screen(element(0, "標籤", clickable = false, editable = false))
            listOf(
                Decision.Click(4),
                Decision.Click(0),
                Decision.SetText(0, "value"),
            ).forEach { unsafeDecision ->
                val engine =
                    DefaultDecisionEngine(
                        lmEnabled = true,
                        intentInterpreterFactory = {
                            LmIntentInterpreter(FakeLmClient(listOf(validResponse("go_back"))))
                        },
                        goalPlanner =
                            object : GoalPlanner {
                                override fun plan(
                                    goal: UserGoal,
                                    screen: ScreenSnapshot,
                                ): PlanResult = PlanResult.Actionable(unsafeDecision)
                            },
                    )
                assertEquals(Decision.NoMatch, engine.decide("返回上一頁", screen))
            }
        }

    /**
     * Verifies interpreter cancellation propagates instead of becoming user-visible error speech.
     *
     * @return no value; assertion failure reports swallowed cancellation or synthetic error output.
     *
     * This test uses a fake cancellation only, with no network, delay, timer, action, or I/O.
     */
    @Test
    fun cancellationPropagatesWithoutUserError() {
        val cancellation = CancellationException("cancel decision")
        val engine =
            DefaultDecisionEngine(
                lmEnabled = true,
                intentInterpreterFactory = {
                    LmIntentInterpreter(FakeLmClient(failure = cancellation))
                },
            )

        val thrown =
            assertThrows(CancellationException::class.java) {
                runTest { engine.decide("無法直接匹配", screen(element(0, "設定"))) }
            }

        assertEquals(cancellation, thrown)
    }

    /**
     * Creates an enabled engine backed by one scripted raw response.
     *
     * @param rawResponse strict/fake interpreter response.
     * @return composed engine with no real provider or external resources.
     *
     * This pure factory performs no network, coroutine, timer, delay, or failure-prone work.
     */
    private fun engineWith(rawResponse: String): DefaultDecisionEngine =
        DefaultDecisionEngine(
            lmEnabled = true,
            intentInterpreterFactory = {
                LmIntentInterpreter(FakeLmClient(listOf(rawResponse)))
            },
        )

    /**
     * Builds one deterministic immutable screen.
     *
     * @param elements ordered snapshot elements.
     * @return pure home snapshot.
     *
     * This helper performs no I/O, coroutine, timer, delay, or failure-prone work.
     */
    private fun screen(vararg elements: ScreenElement): ScreenSnapshot =
        ScreenSnapshot("home", 1L, elements.toList())

    /**
     * Builds one immutable screen element.
     *
     * @param index snapshot-local identifier.
     * @param text visible label.
     * @param clickable activation capability.
     * @param editable text-entry capability.
     * @return pure element without operational coordinates.
     *
     * This helper performs no I/O, coroutine, timer, delay, or failure-prone work.
     */
    private fun element(
        index: Int,
        text: String,
        clickable: Boolean = true,
        editable: Boolean = false,
    ): ScreenElement = ScreenElement(index, text, clickable, editable, emptyList())

    /**
     * Builds one controlled strict interpreter response for composition tests.
     *
     * @param intent allowed schema intent.
     * @param direction optional direction.
     * @param target optional target.
     * @param confidence raw numeric confidence token.
     * @param needsClarification explicit clarification flag.
     * @param question optional clarification question.
     * @param controlQuery optional semantic on-screen control description.
     * @return raw seven-field JSON response for [FakeLmClient].
     *
     * This controlled test formatter performs no general escaping, I/O, coroutine, timer, or delay.
     */
    private fun validResponse(
        intent: String,
        direction: String? = null,
        target: String? = null,
        controlQuery: String? = null,
        confidence: String = "0.9",
        needsClarification: Boolean = false,
        question: String? = null,
    ): String =
        """{"intent":"$intent","direction":${direction.json()},"target":${target.json()},"control_query":${controlQuery.json()},"confidence":$confidence,"needs_clarification":$needsClarification,"clarification_question":${question.json()}}"""

    /**
     * Formats a controlled nullable test value as JSON string/null.
     *
     * @receiver controlled value without quotes/backslashes.
     * @return raw JSON token.
     *
     * This pure helper performs no I/O, coroutine, timer, delay, or failure-prone work.
     */
    private fun String?.json(): String = this?.let { value -> "\"$value\"" } ?: "null"
}

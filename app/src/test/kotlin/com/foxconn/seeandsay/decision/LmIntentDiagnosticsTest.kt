package com.foxconn.seeandsay.decision

import com.foxconn.seeandsay.bridge.FakeUiBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies safe LM debugger metadata without exposing or changing strict interpretation output. */
class LmIntentDiagnosticsTest {

    @Test
    fun `valid structured result reports resolved goal and one clean attempt`() =
        runTest {
            val interpreter =
                LmIntentInterpreter(
                    FakeLmClient(
                        listOf(
                            """{"intent":"open_target","direction":null,"target":"設定","control_query":"設定","confidence":0.93,"needs_clarification":false,"clarification_question":null}""",
                        ),
                    ),
                )

            val diagnostic =
                interpreter.interpretWithDiagnostics(
                    "開設定",
                    FakeUiBridge.realisticHomeScreen(),
                )

            assertTrue(diagnostic.result is IntentResult.Resolved)
            assertEquals(1, diagnostic.attempts)
            assertNull(diagnostic.lastRejectedIssue)
        }

    @Test
    fun `provider failure reports safe category after bounded retry`() =
        runTest {
            val interpreter =
                LmIntentInterpreter(
                    FakeLmClient(
                        failure =
                            LmClientException(
                                LmClientFailureReason.Authentication,
                                "fixed test failure",
                            ),
                    ),
                )

            val diagnostic =
                interpreter.interpretWithDiagnostics(
                    "開設定",
                    FakeUiBridge.realisticHomeScreen(),
                )

            assertEquals(IntentResult.NoMatch, diagnostic.result)
            assertEquals(2, diagnostic.attempts)
            assertEquals(
                LmDiagnosticIssue.ClientFailure(LmClientFailureReason.Authentication),
                diagnostic.lastRejectedIssue,
            )
        }

    @Test
    fun `unsafe response reports rejection without retaining raw output`() =
        runTest {
            val interpreter =
                LmIntentInterpreter(
                    FakeLmClient(
                        listOf(
                            """{"intent":"open_target","direction":null,"target":"設定","control_query":"設定","confidence":0.93,"needs_clarification":false,"clarification_question":null,"index":0}""",
                        ),
                    ),
                )

            val diagnostic =
                interpreter.interpretWithDiagnostics(
                    "開設定",
                    FakeUiBridge.realisticHomeScreen(),
                )

            assertEquals(IntentResult.NoMatch, diagnostic.result)
            assertEquals(2, diagnostic.attempts)
            assertEquals(
                LmDiagnosticIssue.InvalidOrUnsafeResponse,
                diagnostic.lastRejectedIssue,
            )
        }
}

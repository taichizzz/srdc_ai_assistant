package com.foxconn.seeandsay.pipeline

import com.foxconn.seeandsay.bridge.FakeUiBridge
import com.foxconn.seeandsay.bridge.ScreenSettleResult
import com.foxconn.seeandsay.bridge.ScreenSettler
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.DecisionResolutionPath
import com.foxconn.seeandsay.decision.DefaultDecisionEngine
import com.foxconn.seeandsay.decision.FakeLmClient
import com.foxconn.seeandsay.decision.LmClientException
import com.foxconn.seeandsay.decision.LmClientFailureReason
import com.foxconn.seeandsay.decision.LmIntentInterpreter
import com.foxconn.seeandsay.decision.VerificationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure end-to-end coordinator coverage using scripted bridge, LM, waiter, and snapshots. */
class IntegratedCommandCoordinatorTest {

    @Test
    fun `lm command reads acts waits reads and verifies in order`() =
        runTest {
            val bridge =
                FakeUiBridge(
                    listOf(
                        FakeUiBridge.realisticHomeScreen(),
                        FakeUiBridge.realisticSettingsScreen(),
                    ),
                )
            val client = FakeLmClient(listOf(openSettingsResponse()))
            val coordinator = coordinator(bridge, enabledEngine(client))

            val result = coordinator.run("開設定").completed()

            assertEquals(DecisionResolutionPath.LanguageModel, result.resolution.path)
            assertEquals(Decision.Click(0), result.resolution.decision)
            assertEquals(CommandAction.Attempted(Decision.Click(0), true), result.action)
            assertEquals(ScreenSettleResult.ChangeObserved, result.settleResult)
            assertEquals(VerificationResult.Verified, result.verification)
            assertTrue(result.isVerifiedSuccess)
            assertEquals(
                listOf(
                    FakeUiBridge.Call.ReadScreen,
                    FakeUiBridge.Call.Click(0),
                    FakeUiBridge.Call.ReadScreen,
                ),
                bridge.calls(),
            )
            assertEquals(1, client.requests().size)
        }

    @Test
    fun `unchanged after snapshot is not verified even when action dispatch succeeds`() =
        runTest {
            val screen = FakeUiBridge.realisticHomeScreen()
            val bridge = FakeUiBridge(listOf(screen, screen))
            val result = coordinator(bridge, disabledEngine()).run("打開設定").completed()

            assertEquals(DecisionResolutionPath.DeterministicFallback, result.resolution.path)
            assertTrue(result.verification is VerificationResult.NotVerified)
            assertFalse(result.isVerifiedSuccess)
            assertEquals(
                listOf(
                    FakeUiBridge.Call.ReadScreen,
                    FakeUiBridge.Call.Click(0),
                    FakeUiBridge.Call.ReadScreen,
                ),
                bridge.calls(),
            )
        }

    @Test
    fun `empty after snapshot is inconclusive rather than failed success`() =
        runTest {
            val bridge =
                FakeUiBridge(
                    listOf(
                        FakeUiBridge.realisticHomeScreen(),
                        ScreenSnapshot("unknown", 2L, emptyList()),
                    ),
                )
            val result = coordinator(bridge, disabledEngine()).run("打開設定").completed()

            assertTrue(result.verification is VerificationResult.Inconclusive)
            assertFalse(result.isVerifiedSuccess)
        }

    @Test
    fun `wait timeout still reads and verifies instead of crashing`() =
        runTest {
            val bridge =
                FakeUiBridge(
                    listOf(
                        FakeUiBridge.realisticHomeScreen(),
                        FakeUiBridge.realisticSettingsScreen(),
                    ),
                )
            val coordinator =
                IntegratedCommandCoordinator(
                    uiBridge = bridge,
                    screenSettler = ImmediateScreenSettler(ScreenSettleResult.TimedOut),
                    decisionEngine = disabledEngine(),
                )

            val result = coordinator.run("打開設定").completed()

            assertEquals(ScreenSettleResult.TimedOut, result.settleResult)
            assertEquals(VerificationResult.Verified, result.verification)
            assertEquals(
                listOf(
                    FakeUiBridge.Call.ReadScreen,
                    FakeUiBridge.Call.Click(0),
                    FakeUiBridge.Call.ReadScreen,
                ),
                bridge.calls(),
            )
        }

    @Test
    fun `lm clarification speaks and performs no bridge action`() =
        runTest {
            val bridge = FakeUiBridge()
            val client =
                FakeLmClient(
                    listOf(
                        """{"intent":"open_target","direction":null,"target":null,"control_query":null,"confidence":0.9,"needs_clarification":true,"clarification_question":"你要開哪一個功能？"}""",
                    ),
                )
            val result = coordinator(bridge, enabledEngine(client)).run("幫我打開").completed()

            assertEquals(DecisionResolutionPath.LanguageModel, result.resolution.path)
            assertEquals(Decision.Speak("你要開哪一個功能？"), result.resolution.decision)
            assertEquals(CommandAction.None, result.action)
            assertNull(result.verification)
            assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())
        }

    @Test
    fun `no match performs no bridge action`() =
        runTest {
            val bridge = FakeUiBridge()
            val result = coordinator(bridge, disabledEngine()).run("電話").completed()

            assertEquals(Decision.NoMatch, result.resolution.decision)
            assertEquals(CommandAction.None, result.action)
            assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())
        }

    @Test
    fun `disabled lm direct command completes through deterministic fallback`() =
        runTest {
            var interpreterConstructed = false
            val engine =
                DefaultDecisionEngine(
                    lmEnabled = false,
                    intentInterpreterFactory = {
                        interpreterConstructed = true
                        LmIntentInterpreter(FakeLmClient(listOf(openSettingsResponse())))
                    },
                )
            val bridge =
                FakeUiBridge(
                    listOf(
                        FakeUiBridge.realisticHomeScreen(),
                        FakeUiBridge.realisticSettingsScreen(),
                    ),
                )

            val result = coordinator(bridge, engine).run("打開設定").completed()

            assertFalse(interpreterConstructed)
            assertEquals(DecisionResolutionPath.DeterministicFallback, result.resolution.path)
            assertEquals(VerificationResult.Verified, result.verification)
        }

    @Test
    fun `failing lm direct command completes through deterministic fallback`() =
        runTest {
            val client =
                FakeLmClient(
                    failure =
                        LmClientException(
                            LmClientFailureReason.Network,
                            "fixed network test failure",
                        ),
                )
            val bridge =
                FakeUiBridge(
                    listOf(
                        FakeUiBridge.realisticHomeScreen(),
                        FakeUiBridge.realisticSettingsScreen(),
                    ),
                )

            val result = coordinator(bridge, enabledEngine(client)).run("打開設定").completed()

            assertEquals(DecisionResolutionPath.DeterministicFallback, result.resolution.path)
            assertEquals(VerificationResult.Verified, result.verification)
            assertEquals(2, client.requests().size)
        }

    @Test
    fun `lm debugger uses live snapshot context and performs no action`() =
        runTest {
            val bridge = FakeUiBridge()
            val client = FakeLmClient(listOf(openSettingsResponse()))
            val coordinator = coordinator(bridge, enabledEngine(client))

            val outcome = coordinator.inspectLm("開設定") as LmDebugOutcome.Completed

            assertEquals(CommandSnapshotSource.CurrentForeground, outcome.snapshot.source)
            assertEquals("home", outcome.snapshot.screen)
            assertEquals(3, outcome.snapshot.elementCount)
            assertEquals(DecisionResolutionPath.LanguageModel, outcome.resolution.path)
            assertEquals(1, outcome.resolution.lmDiagnostic?.attempts)
            assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())
        }

    @Test
    fun `lm debugger marks snapshot as underlying when reveal callback is supplied`() =
        runTest {
            val bridge = FakeUiBridge()
            val coordinator = coordinator(bridge, disabledEngine())
            var revealed = false

            val outcome =
                coordinator.inspectLm("開設定") { revealed = true } as LmDebugOutcome.Completed

            assertTrue(revealed)
            assertEquals(CommandSnapshotSource.UnderlyingTarget, outcome.snapshot.source)
            assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())
        }

    /** Event waiter fake completing synchronously without real or virtual delay. */
    private class ImmediateScreenSettler(
        private val result: ScreenSettleResult = ScreenSettleResult.ChangeObserved,
    ) : ScreenSettler {
        override suspend fun awaitScreenSettled(timeoutMillis: Long): ScreenSettleResult =
            result
    }

    private fun coordinator(
        bridge: FakeUiBridge,
        engine: DefaultDecisionEngine,
    ): IntegratedCommandCoordinator =
        IntegratedCommandCoordinator(
            uiBridge = bridge,
            screenSettler = ImmediateScreenSettler(),
            decisionEngine = engine,
        )

    private fun enabledEngine(client: FakeLmClient): DefaultDecisionEngine =
        DefaultDecisionEngine(
            lmEnabled = true,
            intentInterpreterFactory = { LmIntentInterpreter(client) },
        )

    private fun disabledEngine(): DefaultDecisionEngine = DefaultDecisionEngine(lmEnabled = false)

    private fun IntegratedCommandOutcome.completed(): IntegratedCommandResult =
        (this as IntegratedCommandOutcome.Completed).result

    private fun openSettingsResponse(): String =
        """{"intent":"open_target","direction":null,"target":"設定","control_query":"設定","confidence":0.94,"needs_clarification":false,"clarification_question":null}"""
}

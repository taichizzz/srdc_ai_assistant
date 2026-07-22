package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.MainDispatcherRule
import com.foxconn.seeandsay.bridge.FakeUiBridge
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.MatchTier
import com.foxconn.seeandsay.decision.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the DEBUG matching inspector against the provider-neutral scripted fake on the JVM.
 *
 * Tests replace the lifecycle main dispatcher and use only in-memory screens. They perform no
 * Android accessibility, device, network, filesystem, real-time delay, or bridge action work.
 */
class MatchingInspectorViewModelTest {

    /** Lifecycle dispatcher replacement allowing immediate ViewModel coroutine execution. */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Verifies initialization loads the fake screen and alias input exposes tiered decision data.
     *
     * @return no value; assertion failure reports injection, loading, or matching drift.
     *
     * The test runs on a virtual dispatcher, performs in-memory reads only, and has no external I/O.
     */
    @Test
    fun fakeSnapshotLoadsAndTypedCommandProducesDecision() {
        val bridge = FakeUiBridge()
        val viewModel = MatchingInspectorViewModel(bridge)

        val loadedState = viewModel.uiState.value
        assertFalse(loadedState.isLoading)
        assertEquals(FakeUiBridge.realisticHomeScreen(), loadedState.snapshot)
        assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())

        viewModel.onCommandSubmitted("Settings")

        val matchedState = viewModel.uiState.value
        assertEquals(Decision.Click(0), matchedState.result?.decision)
        assertEquals(MatchTier.Alias, matchedState.result?.tier)
        assertEquals(listOf(0), matchedState.result?.candidateIndices)
        assertEquals(listOf(FakeUiBridge.Call.ReadScreen), bridge.calls())
    }

    /**
     * Verifies refresh advances a script, clears the prior decision, and never invokes an action.
     *
     * @return no value; assertion failure reports incorrect provider-neutral refresh ordering.
     *
     * The test runs with virtual lifecycle scheduling and pure fake state, without I/O or delays.
     */
    @Test
    fun refreshAdvancesScriptAndClearsDecisionWithoutActing() {
        val first = FakeUiBridge.realisticHomeScreen()
        val second = ScreenSnapshot("settings", 2L, emptyList())
        val bridge = FakeUiBridge(listOf(first, second))
        val viewModel = MatchingInspectorViewModel(bridge)
        viewModel.onCommandSubmitted("設定")

        viewModel.onRefreshRequested()

        assertEquals(second, viewModel.uiState.value.snapshot)
        assertEquals(null, viewModel.uiState.value.result)
        assertEquals(
            listOf(FakeUiBridge.Call.ReadScreen, FakeUiBridge.Call.ReadScreen),
            bridge.calls(),
        )
    }

    /**
     * Verifies the default fake sequence previews Click, SetText, and Back without action calls.
     *
     * @return no value; assertion failure reports incorrect scripted selection or comparison output.
     *
     * The test uses virtual lifecycle scheduling and in-memory reads only. It performs no action,
     * Android accessibility work, I/O, event waiting, real delay, or timer operation.
     */
    @Test
    fun scriptedSnapshotsPreviewAllActionVerificationsWithoutActing() {
        val bridge = FakeUiBridge()
        val viewModel = MatchingInspectorViewModel(bridge)

        viewModel.onVerificationBeforeSelected()
        viewModel.onRefreshRequested()
        viewModel.onVerificationAfterSelected()
        viewModel.onVerificationRequested(VerificationDecisionKind.Click, "")
        assertEquals(Decision.Click(0), viewModel.uiState.value.verificationDecision)
        assertEquals(VerificationResult.Verified, viewModel.uiState.value.verificationResult)

        viewModel.onVerificationBeforeSelected()
        viewModel.onRefreshRequested()
        viewModel.onVerificationAfterSelected()
        viewModel.onVerificationRequested(VerificationDecisionKind.SetText, " Roxanne! ")
        assertEquals(
            Decision.SetText(3, " Roxanne! "),
            viewModel.uiState.value.verificationDecision,
        )
        assertEquals(VerificationResult.Verified, viewModel.uiState.value.verificationResult)

        viewModel.onVerificationBeforeSelected()
        viewModel.onRefreshRequested()
        viewModel.onVerificationAfterSelected()
        viewModel.onVerificationRequested(VerificationDecisionKind.Back, "")
        assertEquals(Decision.Back, viewModel.uiState.value.verificationDecision)
        assertEquals(VerificationResult.Verified, viewModel.uiState.value.verificationResult)

        assertEquals(
            listOf(
                FakeUiBridge.Call.ReadScreen,
                FakeUiBridge.Call.ReadScreen,
                FakeUiBridge.Call.ReadScreen,
                FakeUiBridge.Call.ReadScreen,
            ),
            bridge.calls(),
        )
    }
}

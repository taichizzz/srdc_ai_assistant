package com.foxconn.seeandsay.pipeline

import com.foxconn.seeandsay.bridge.ScreenSettleResult
import com.foxconn.seeandsay.bridge.ScreenSettler
import com.foxconn.seeandsay.bridge.UiBridge
import com.foxconn.seeandsay.bridge.model.ScreenSnapshot
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.DecisionResolution
import com.foxconn.seeandsay.decision.DecisionResolutionPath
import com.foxconn.seeandsay.decision.LocalDecisionValidator
import com.foxconn.seeandsay.decision.TraceableDecisionEngine
import com.foxconn.seeandsay.decision.VerificationResult
import com.foxconn.seeandsay.decision.expectationFor
import com.foxconn.seeandsay.decision.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope

/**
 * Observable action attempt made by the integrated typed-command coordinator.
 *
 * Values contain immutable decision data only, perform no work, and are safe across threads. A
 * platform-accepted action is not proof of success; [IntegratedCommandResult.verification] is the
 * only success evidence.
 */
sealed interface CommandAction {

    /** No bridge action was attempted for Speak, NoMatch, or locally rejected decisions. */
    data object None : CommandAction

    /**
     * Records an actionable decision and whether the platform accepted dispatch.
     *
     * @property decision validated Click, SetText, or Back sent to [UiBridge].
     * @property accepted `performAction`/global-action dispatch result, never a success claim.
     */
    data class Attempted(
        val decision: Decision,
        val accepted: Boolean,
    ) : CommandAction
}

/** Identifies which foreground context supplied the snapshot used for decision and LM context. */
enum class CommandSnapshotSource {
    /** No reveal callback ran; Android's currently foreground activity was captured. */
    CurrentForeground,

    /** The assistant was backgrounded event-first, then the revealed underlying screen was read. */
    UnderlyingTarget,
}

/**
 * Safe snapshot provenance shown by DEBUG tooling.
 *
 * @property screen coarse package/screen identifier from the snapshot.
 * @property elementCount number of pure snapshot elements sent index-free to the LM seam.
 * @property source foreground/revealed-target capture mode.
 *
 * Bounds, indices, Android nodes, and raw provider data are intentionally excluded.
 */
data class CommandSnapshotDiagnostic(
    val screen: String,
    val elementCount: Int,
    val source: CommandSnapshotSource,
)

/**
 * Completed integrated command diagnostics for DEBUG inspection and JVM assertions.
 *
 * @property resolution validated decision plus LM/fallback source.
 * @property snapshot snapshot identity/count/source used to produce the decision.
 * @property action action attempt, or [CommandAction.None].
 * @property settleResult event-wait observation for an accepted action, otherwise null.
 * @property verification read-back comparison for an attempted action, otherwise null.
 *
 * This immutable value performs no I/O or logging. Callers may report action success only when the
 * action was accepted and [verification] is [VerificationResult.Verified].
 */
data class IntegratedCommandResult(
    val resolution: DecisionResolution,
    val snapshot: CommandSnapshotDiagnostic,
    val action: CommandAction,
    val settleResult: ScreenSettleResult?,
    val verification: VerificationResult?,
) {
    /** True only for an accepted dispatch followed by verified observable evidence. */
    val isVerifiedSuccess: Boolean
        get() =
            (action as? CommandAction.Attempted)?.accepted == true &&
                verification == VerificationResult.Verified
}

/** No-action live LM/decision inspection result. */
sealed interface LmDebugOutcome {

    /**
     * @property resolution LM/fallback result including safe LM attempt diagnostics.
     * @property snapshot exact live snapshot provenance used as model context.
     */
    data class Completed(
        val resolution: DecisionResolution,
        val snapshot: CommandSnapshotDiagnostic,
    ) : LmDebugOutcome

    /** @property reason fixed non-secret read/decision failure. */
    data class Failed(val reason: String) : LmDebugOutcome
}

/**
 * Typed completion/failure of one integrated command run.
 *
 * Failures are fixed, non-secret diagnostics for stages that cannot produce a decision or usable
 * before-snapshot. Provider failure is normally absorbed by the decision engine's fallback.
 */
sealed interface IntegratedCommandOutcome {

    /** @property result completed decision/action/read-back diagnostics. */
    data class Completed(val result: IntegratedCommandResult) : IntegratedCommandOutcome

    /** @property reason fixed non-secret coordinator failure reason. */
    data class Failed(val reason: String) : IntegratedCommandOutcome
}

/**
 * Composes the live bridge, LM-first decision layer, event wait, and pure verification comparison.
 *
 * @param uiBridge provider-neutral snapshot/action bridge.
 * @param screenSettler event-driven settle waiter implemented by the live accessibility bridge.
 * @param decisionEngine LM-first resolver with deterministic failure fallback and route diagnostics.
 * @param settleTimeoutMillis positive bound for target reveal and accepted-action event waits.
 *
 * This is the only component in this flow that imports both bridge and decision contracts. It runs
 * one command at a time per instance and launches only structured child coroutines. Wait collection
 * starts undispatched immediately before screen reveal/action dispatch so the first event cannot be
 * missed. Cancellation propagates through decision/provider, wait, bridge read, and action work.
 * Ordinary bridge failures become fixed DEBUG outcomes and never expose credentials or model text.
 *
 * @throws IllegalArgumentException when [settleTimeoutMillis] is not positive.
 */
class IntegratedCommandCoordinator(
    private val uiBridge: UiBridge,
    private val screenSettler: ScreenSettler,
    private val decisionEngine: TraceableDecisionEngine,
    private val settleTimeoutMillis: Long = DEFAULT_SETTLE_TIMEOUT_MILLIS,
) {

    init {
        require(settleTimeoutMillis > 0L) { "settleTimeoutMillis must be positive" }
    }

    /**
     * Executes one typed command through read → decide → act → event wait → read → verify.
     *
     * @param transcript raw typed command.
     * @param revealTargetScreen optional DEBUG callback that backgrounds the assistant before the
     * first snapshot; its screen-change event is awaited with the same event-driven mechanism.
     * @return completed diagnostics or a fixed pre-decision failure.
     *
     * The function may suspend for screen events, LM interpretation, and bridge calls. It performs
     * no fixed sleep. Cancellation is rethrown. Speak/NoMatch call no action or post-action read.
     * Every attempted action receives a fresh read-back; accepted actions first await an event burst
     * or timeout, and timeout still proceeds to verification rather than throwing.
     */
    suspend fun run(
        transcript: String,
        revealTargetScreen: (() -> Unit)? = null,
    ): IntegratedCommandOutcome =
        coroutineScope {
            if (revealTargetScreen != null) {
                awaitAround(revealTargetScreen)
            }
            val before = readBefore() ?: return@coroutineScope failed(BEFORE_READ_FAILED)
            val snapshot = before.diagnostic(revealTargetScreen)
            val resolution =
                try {
                    decisionEngine.decideWithResolution(transcript, before)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@coroutineScope failed(DECISION_FAILED)
                }
            executeResolution(resolution, before, snapshot)
        }

    /**
     * Runs live LM-first resolution against a revealed snapshot without executing any action.
     *
     * @param transcript raw LM debugger utterance.
     * @param revealTargetScreen optional callback that backgrounds the assistant event-first.
     * @return safe intent/route diagnostics plus snapshot provenance, or fixed failure.
     *
     * This is inspection only: it never calls click, setText, Back, post-action wait, or verify.
     * Provider/read work may suspend; cancellation propagates and raw LM output is never returned.
     */
    suspend fun inspectLm(
        transcript: String,
        revealTargetScreen: (() -> Unit)? = null,
    ): LmDebugOutcome =
        coroutineScope {
            if (revealTargetScreen != null) awaitAround(revealTargetScreen)
            val before = readBefore() ?: return@coroutineScope LmDebugOutcome.Failed(BEFORE_READ_FAILED)
            val resolution =
                try {
                    decisionEngine.decideWithResolution(transcript, before)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@coroutineScope LmDebugOutcome.Failed(DECISION_FAILED)
                }
            LmDebugOutcome.Completed(resolution, before.diagnostic(revealTargetScreen))
        }

    /**
     * Runs an explicit DEBUG action control through the same validation/wait/read-back gate.
     *
     * @param decision explicit Click, SetText, or Back selected by a trusted local debug control.
     * @param path diagnostic label for this pre-resolved local control.
     * @param revealTargetScreen optional target-screen reveal callback awaited event-first.
     * @return completed diagnostics or fixed before-read failure.
     *
     * This helper exists for explicit bridge acceptance controls, not user-language interpretation.
     * It cannot bypass local validation or verification. Cancellation propagates, and it performs no
     * fixed sleep or provider work.
     */
    suspend fun runPreResolved(
        decision: Decision,
        path: DecisionResolutionPath = DecisionResolutionPath.DeterministicFallback,
        revealTargetScreen: (() -> Unit)? = null,
    ): IntegratedCommandOutcome =
        coroutineScope {
            if (revealTargetScreen != null) awaitAround(revealTargetScreen)
            val before = readBefore() ?: return@coroutineScope failed(BEFORE_READ_FAILED)
            executeResolution(
                DecisionResolution(decision, path),
                before,
                before.diagnostic(revealTargetScreen),
            )
        }

    /** Validates, dispatches, event-waits, re-reads, and verifies one resolved decision. */
    private suspend fun executeResolution(
        resolution: DecisionResolution,
        before: ScreenSnapshot,
        snapshot: CommandSnapshotDiagnostic,
    ): IntegratedCommandOutcome =
        coroutineScope {
            val validatedDecision = LocalDecisionValidator.validate(resolution.decision, before)
            val validatedResolution = resolution.copy(decision = validatedDecision)
            if (!validatedDecision.isActionable()) {
                return@coroutineScope completed(validatedResolution, snapshot, CommandAction.None)
            }
            val expectation = expectationFor(validatedDecision, before)
                ?: return@coroutineScope completed(
                    validatedResolution.copy(decision = Decision.NoMatch),
                    snapshot,
                    CommandAction.None,
                )
            val wait =
                async(start = CoroutineStart.UNDISPATCHED) {
                    screenSettler.awaitScreenSettled(settleTimeoutMillis)
                }
            val accepted = dispatch(validatedDecision)
            val settleResult =
                if (accepted) {
                    wait.await()
                } else {
                    wait.cancelAndJoin()
                    null
                }
            val after = readAfter()
            val verification =
                if (after == null) {
                    VerificationResult.Inconclusive(AFTER_READ_FAILED)
                } else {
                    verify(before, after, expectation)
                }
            completed(
                resolution = validatedResolution,
                snapshot = snapshot,
                action = CommandAction.Attempted(validatedDecision, accepted),
                settleResult = settleResult,
                verification = verification,
            )
        }

    /** Starts event collection before invoking [trigger], preventing a reveal/action race. */
    private suspend fun awaitAround(trigger: () -> Unit): ScreenSettleResult =
        coroutineScope {
            val wait =
                async(start = CoroutineStart.UNDISPATCHED) {
                    screenSettler.awaitScreenSettled(settleTimeoutMillis)
                }
            try {
                trigger()
            } catch (error: Throwable) {
                wait.cancelAndJoin()
                throw error
            }
            wait.await()
        }

    /** Reads the current before-snapshot while preserving cancellation. */
    private suspend fun readBefore(): ScreenSnapshot? = readSafely()

    /** Reads the fresh after-snapshot while preserving cancellation. */
    private suspend fun readAfter(): ScreenSnapshot? = readSafely()

    /** Converts ordinary bridge read failure to null without swallowing cancellation. */
    private suspend fun readSafely(): ScreenSnapshot? =
        try {
            uiBridge.readScreen()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

    /** Dispatches one validated action; ordinary platform failure is an explicit false result. */
    private suspend fun dispatch(decision: Decision): Boolean =
        try {
            when (decision) {
                is Decision.Click -> uiBridge.click(decision.index)
                is Decision.SetText -> uiBridge.setText(decision.index, decision.text)
                Decision.Back -> uiBridge.back()
                is Decision.Speak,
                Decision.NoMatch,
                -> false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }

    /** Returns whether a locally validated decision should reach the bridge action seam. */
    private fun Decision.isActionable(): Boolean =
        this is Decision.Click || this is Decision.SetText || this === Decision.Back

    /** Builds safe snapshot identity/count/provenance without indices or bounds. */
    private fun ScreenSnapshot.diagnostic(
        revealTargetScreen: (() -> Unit)?,
    ): CommandSnapshotDiagnostic =
        CommandSnapshotDiagnostic(
            screen = screen,
            elementCount = elements.size,
            source =
                if (revealTargetScreen == null) {
                    CommandSnapshotSource.CurrentForeground
                } else {
                    CommandSnapshotSource.UnderlyingTarget
                },
        )

    /** Builds a completed immutable outcome without side effects. */
    private fun completed(
        resolution: DecisionResolution,
        snapshot: CommandSnapshotDiagnostic,
        action: CommandAction,
        settleResult: ScreenSettleResult? = null,
        verification: VerificationResult? = null,
    ): IntegratedCommandOutcome =
        IntegratedCommandOutcome.Completed(
            IntegratedCommandResult(resolution, snapshot, action, settleResult, verification),
        )

    /** Builds a fixed non-secret failed outcome without side effects. */
    private fun failed(reason: String): IntegratedCommandOutcome =
        IntegratedCommandOutcome.Failed(reason)

    companion object {
        /** Bound long enough for an accessibility event burst plus the 300 ms quiet debounce. */
        const val DEFAULT_SETTLE_TIMEOUT_MILLIS = 2_500L

        const val BEFORE_READ_FAILED = "Unable to read the target screen before deciding."
        const val DECISION_FAILED = "Unable to resolve a safe decision."
        const val AFTER_READ_FAILED = "Unable to read the target screen after the action."
    }
}

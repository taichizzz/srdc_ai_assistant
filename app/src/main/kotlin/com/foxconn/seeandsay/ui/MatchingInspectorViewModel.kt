package com.foxconn.seeandsay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxconn.seeandsay.bridge.UiBridge
import com.foxconn.seeandsay.decision.TextMatcher
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.expectationFor
import com.foxconn.seeandsay.decision.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the DEBUG matching inspector's snapshot read and pure typed-command evaluation.
 *
 * @param uiBridge provider-neutral screen reader; DEBUG currently injects the scripted fake and a
 * future composition root can inject Person 1's implementation without changing this ViewModel/UI.
 * @param textMatcher pure tiered matcher used after a snapshot is available.
 *
 * Public events are intended for Android's main thread. Screen reads are structured under
 * [viewModelScope], replacement reads cancel their predecessor, and cancellation is propagated
 * without becoming an error. Bridge failures become fixed recoverable state. Matching itself is
 * synchronous, deterministic, and performs no I/O.
 */
class MatchingInspectorViewModel(
    private val uiBridge: UiBridge,
    private val textMatcher: TextMatcher = TextMatcher(),
) : ViewModel() {

    /** Mutable main-confined state hidden behind [uiState]. */
    private val mutableUiState = MutableStateFlow(MatchingInspectorUiState())

    /** Lifecycle-owned screen read, cancelled before a replacement begins. */
    private var readJob: Job? = null

    /**
     * Exposes the latest inspector state as a lifecycle-friendly read-only flow.
     *
     * @return hot state containing the current snapshot, decision diagnostic, and recoverable error.
     *
     * Collection is safe on any coroutine context, performs no I/O itself, and may be cancelled
     * without cancelling the ViewModel-owned screen read.
     */
    val uiState: StateFlow<MatchingInspectorUiState> = mutableUiState.asStateFlow()

    init {
        onRefreshRequested()
    }

    /**
     * Reads a fresh snapshot through the injected provider-neutral bridge.
     *
     * @return This event has no return value.
     *
     * The main-thread event cancels an older read and launches lifecycle-owned suspending work.
     * Success replaces the snapshot and clears the prior decision; bridge failure becomes a fixed
     * recoverable message. Coroutine cancellation is rethrown and never displayed as failure.
     */
    fun onRefreshRequested() {
        readJob?.cancel()
        mutableUiState.update { state -> state.copy(isLoading = true, errorMessage = null) }
        readJob =
            viewModelScope.launch {
                try {
                    val snapshot = uiBridge.readScreen()
                    mutableUiState.update { state ->
                        state.copy(
                            snapshot = snapshot,
                            result = null,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    mutableUiState.update { state ->
                        state.copy(isLoading = false, errorMessage = SCREEN_READ_ERROR)
                    }
                }
            }
    }

    /**
     * Evaluates a typed command against the currently displayed snapshot.
     *
     * @param command raw typed text passed intact to the shared-normalizer matcher.
     * @return This event has no return value.
     *
     * The event performs pure synchronous matching on the caller's thread. A missing snapshot or
     * blank normalized command is represented by recoverable/no-match state; no bridge action,
     * Android call, I/O, coroutine launch, or verification is performed.
     */
    fun onCommandSubmitted(command: String) {
        val snapshot = mutableUiState.value.snapshot
        if (snapshot == null) {
            mutableUiState.update { state -> state.copy(errorMessage = SNAPSHOT_REQUIRED_ERROR) }
            return
        }
        mutableUiState.update { state ->
            state.copy(result = textMatcher.evaluate(command, snapshot), errorMessage = null)
        }
    }

    /**
     * Captures the currently displayed scripted snapshot as verification's before-state.
     *
     * @return This event has no return value.
     *
     * The event copies immutable in-memory state synchronously on the main thread and clears older
     * verification output. It performs no bridge action/read, I/O, waiting, timer, or suspension;
     * a missing current snapshot becomes a fixed recoverable error.
     */
    fun onVerificationBeforeSelected() {
        val snapshot = mutableUiState.value.snapshot
        if (snapshot == null) {
            mutableUiState.update { state -> state.copy(errorMessage = SNAPSHOT_REQUIRED_ERROR) }
            return
        }
        mutableUiState.update { state ->
            state.copy(
                verificationBefore = snapshot,
                verificationDecision = null,
                verificationResult = null,
                errorMessage = null,
            )
        }
    }

    /**
     * Captures the currently displayed scripted snapshot as verification's after-state.
     *
     * @return This event has no return value.
     *
     * The event copies immutable in-memory state synchronously on the main thread and clears older
     * verification output. It performs no bridge action/read, I/O, waiting, timer, or suspension;
     * a missing current snapshot becomes a fixed recoverable error.
     */
    fun onVerificationAfterSelected() {
        val snapshot = mutableUiState.value.snapshot
        if (snapshot == null) {
            mutableUiState.update { state -> state.copy(errorMessage = SNAPSHOT_REQUIRED_ERROR) }
            return
        }
        mutableUiState.update { state ->
            state.copy(
                verificationAfter = snapshot,
                verificationDecision = null,
                verificationResult = null,
                errorMessage = null,
            )
        }
    }

    /**
     * Builds an inspection-only decision and compares the selected before/after snapshots.
     *
     * @param kind click, set-text, or back decision selected explicitly by the tester.
     * @param expectedText replacement text used only when [kind] is [VerificationDecisionKind.SetText].
     * @return This event has no return value.
     *
     * This main-thread event performs pure synchronous expectation derivation and verification. It
     * never calls a bridge action/read, listens for events, waits, launches a coroutine, or uses a
     * timer. Missing snapshots or compatible elements become fixed recoverable UI errors.
     */
    fun onVerificationRequested(
        kind: VerificationDecisionKind,
        expectedText: String,
    ) {
        val state = mutableUiState.value
        val before = state.verificationBefore
        val after = state.verificationAfter
        if (before == null || after == null) {
            mutableUiState.update { current ->
                current.copy(errorMessage = VERIFICATION_SNAPSHOTS_REQUIRED_ERROR)
            }
            return
        }
        val decision =
            when (kind) {
                VerificationDecisionKind.Click ->
                    before.elements.firstOrNull { element -> element.clickable }?.let { element ->
                        Decision.Click(element.i)
                    }
                VerificationDecisionKind.SetText ->
                    before.elements.firstOrNull { element -> element.editable }?.let { element ->
                        Decision.SetText(element.i, expectedText)
                    }
                VerificationDecisionKind.Back -> Decision.Back
            }
        if (decision == null) {
            mutableUiState.update { current ->
                current.copy(errorMessage = COMPATIBLE_ELEMENT_REQUIRED_ERROR)
            }
            return
        }
        val expectation = expectationFor(decision, before)
        if (expectation == null) {
            mutableUiState.update { current ->
                current.copy(errorMessage = EXPECTATION_REQUIRED_ERROR)
            }
            return
        }
        mutableUiState.update { current ->
            current.copy(
                verificationDecision = decision,
                verificationResult = verify(before, after, expectation),
                errorMessage = null,
            )
        }
    }

    /**
     * Cancels the active screen read when lifecycle ownership ends.
     *
     * @return This lifecycle callback has no return value.
     *
     * Android invokes this on the main thread. Cancellation is cooperative and non-blocking; the
     * ViewModel neither closes the injected bridge nor performs any accessibility operation.
     */
    override fun onCleared() {
        readJob?.cancel()
        super.onCleared()
    }

    /** Creates [MatchingInspectorViewModel] instances with explicit provider-neutral dependencies. */
    class Factory(
        private val uiBridge: UiBridge,
        private val textMatcher: TextMatcher = TextMatcher(),
    ) : ViewModelProvider.Factory {

        /**
         * Creates the requested matching inspector ViewModel.
         *
         * @param modelClass ViewModel type requested by Android lifecycle integration.
         * @return a new inspector cast to the requested type.
         * @throws IllegalArgumentException when [modelClass] is not assignable from the inspector.
         *
         * Creation is synchronous on Android's main thread and performs no screen read directly;
         * initialization launches structured work in the new ViewModel scope.
         */
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MatchingInspectorViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return MatchingInspectorViewModel(uiBridge, textMatcher) as T
        }
    }

    private companion object {
        /** Fixed user-safe message for provider-neutral snapshot read failure. */
        const val SCREEN_READ_ERROR = "Unable to read the matching-inspector screen."

        /** Fixed user-safe message when matching is requested before a snapshot exists. */
        const val SNAPSHOT_REQUIRED_ERROR = "Load a screen snapshot before matching."

        /** Fixed message when verification's before or after selection is missing. */
        const val VERIFICATION_SNAPSHOTS_REQUIRED_ERROR =
            "Select both before and after snapshots before verification."

        /** Fixed message when the before-snapshot cannot support the chosen action kind. */
        const val COMPATIBLE_ELEMENT_REQUIRED_ERROR =
            "The before-snapshot has no element compatible with that decision."

        /** Fixed defensive message when an action decision cannot produce an expectation. */
        const val EXPECTATION_REQUIRED_ERROR =
            "The selected decision cannot produce a verification expectation."
    }
}

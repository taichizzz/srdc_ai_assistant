package com.foxconn.seeandsay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxconn.seeandsay.pipeline.IntegratedCommandCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the DEBUG typed-command coordinator lifecycle and immutable result state.
 *
 * @param coordinator pipeline composition for live bridge, decision, wait, and verification work.
 *
 * Public events are main-thread UI events. Each new run cancels its predecessor; structured
 * cancellation propagates through the LM client and accessibility event collector and is never
 * displayed as an error. Ordinary orchestration failures become a fixed non-secret UI message.
 */
class IntegratedCommandViewModel(
    private val coordinator: IntegratedCommandCoordinator,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(IntegratedCommandUiState())
    private var runJob: Job? = null

    /** Read-only lifecycle-friendly state for the DEBUG integrated section. */
    val uiState: StateFlow<IntegratedCommandUiState> = mutableUiState.asStateFlow()

    /**
     * Runs a typed command after revealing the target screen beneath the assistant.
     *
     * @param command raw typed command passed to the LM-first decision engine.
     * @param revealTargetScreen synchronous main-thread callback, normally `moveTaskToBack(true)`;
     * the coordinator subscribes to accessibility events before invoking it.
     * @return no value; progress and completion are exposed through [uiState].
     *
     * The launched coroutine is owned by [viewModelScope]. Replacement/clear cancellation removes
     * event collectors and cancels provider work. No credential, model output, or command is logged.
     */
    fun onRunRequested(
        command: String,
        revealTargetScreen: () -> Unit,
    ) {
        if (command.isBlank()) return
        runJob?.cancel()
        mutableUiState.update { state ->
            state.copy(isRunning = true, outcome = null, errorMessage = null)
        }
        runJob =
            viewModelScope.launch {
                try {
                    val outcome = coordinator.run(command, revealTargetScreen)
                    mutableUiState.update { state ->
                        state.copy(isRunning = false, outcome = outcome, errorMessage = null)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    mutableUiState.update { state ->
                        state.copy(
                            isRunning = false,
                            outcome = null,
                            errorMessage = RUN_FAILED,
                        )
                    }
                }
            }
    }

    /**
     * Inspects live LM-first resolution against the underlying target without performing an action.
     *
     * @param command raw typed LM debugger utterance.
     * @param revealTargetScreen main-thread target reveal callback invoked after event subscription.
     * @return no value; safe attempt/result diagnostics are exposed through [uiState].
     *
     * The lifecycle-owned coroutine performs a live provider call when enabled/configured, but no
     * bridge action or verification. Replacement/clear cancellation propagates and removes event
     * collection. Raw response, prompt, transcript, and credentials are never logged or stored.
     */
    fun onLmInspectRequested(
        command: String,
        revealTargetScreen: () -> Unit,
    ) {
        if (command.isBlank()) return
        runJob?.cancel()
        mutableUiState.update { state ->
            state.copy(isRunning = true, lmDebugOutcome = null, errorMessage = null)
        }
        runJob =
            viewModelScope.launch {
                try {
                    val outcome = coordinator.inspectLm(command, revealTargetScreen)
                    mutableUiState.update { state ->
                        state.copy(
                            isRunning = false,
                            lmDebugOutcome = outcome,
                            errorMessage = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    mutableUiState.update { state ->
                        state.copy(
                            isRunning = false,
                            lmDebugOutcome = null,
                            errorMessage = LM_INSPECTION_FAILED,
                        )
                    }
                }
            }
    }

    /** Cancels an active integrated run when Android releases this lifecycle owner. */
    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }

    /** Creates explicitly injected [IntegratedCommandViewModel] instances. */
    class Factory(
        private val coordinator: IntegratedCommandCoordinator,
    ) : ViewModelProvider.Factory {

        /**
         * Creates the requested ViewModel or rejects an unrelated class.
         *
         * @param modelClass lifecycle-requested ViewModel class.
         * @return new integrated command ViewModel.
         * @throws IllegalArgumentException when [modelClass] is incompatible.
         *
         * Creation is synchronous and starts no command, event collection, provider request, or I/O.
         */
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(IntegratedCommandViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return IntegratedCommandViewModel(coordinator) as T
        }
    }

    private companion object {
        const val RUN_FAILED = "The integrated DEBUG command could not complete."
        const val LM_INSPECTION_FAILED = "The live LM inspection could not complete."
    }
}

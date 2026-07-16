package com.foxconn.seeandsay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxconn.seeandsay.speech.TtsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns standalone M1.2 DEBUG TTS state and one lifecycle-scoped provider-neutral speech request.
 *
 * @param ttsClient provider-neutral implementation whose `speak` call suspends through playback.
 *
 * Public events are intended for Android's main thread. Work is structured under [viewModelScope];
 * a new request cancels and joins its predecessor before using the client. Stop and ViewModel
 * disposal cancel active work. Normal cancellation is never surfaced as Error, while client
 * failures become a fixed recoverable state without exposing provider or credential details.
 */
class TtsViewModel(
    private val ttsClient: TtsClient,
) : ViewModel() {

    /** Mutable main-confined backing state; only immutable [uiState] escapes this ViewModel. */
    private val mutableUiState = MutableStateFlow(TtsUiState())

    /** Lifecycle-owned wrapper for the active or replacing TTS request. */
    private var speakingJob: Job? = null

    /**
     * Exposes the latest standalone TTS state as a read-only lifecycle-friendly StateFlow.
     *
     * @return hot state containing the current status, text, and recoverable error.
     *
     * Collection is safe on any coroutine context and may be cancelled without affecting the
     * stored state or active TTS request. Reading performs no I/O and cannot fail project-specifically.
     */
    val uiState: StateFlow<TtsUiState> = mutableUiState.asStateFlow()

    /**
     * Starts a provider-neutral TTS request, cancelling and joining any predecessor first.
     *
     * @param text user-entered plain text; surrounding whitespace is removed.
     * @return This function has no return value.
     *
     * The event is synchronous on the main thread until it launches lifecycle-owned work. Blank
     * input becomes a recoverable Error. Non-blank input exposes Speaking immediately, then calls
     * [TtsClient.speak]. Successful completion becomes Completed; client failure becomes Error.
     * Replacement/caller cancellation is propagated internally and never displayed as failure.
     */
    fun onSpeakRequested(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            mutableUiState.update {
                it.copy(status = TtsStatus.Error, errorMessage = EMPTY_TEXT_ERROR)
            }
            return
        }

        val previousJob = speakingJob
        // Cancel before scheduling the replacement so an immediate Stop cannot leave the previous
        // client call running while only cancelling its not-yet-started replacement wrapper.
        previousJob?.cancel()
        mutableUiState.value =
            TtsUiState(
                status = TtsStatus.Speaking,
                currentText = normalizedText,
            )
        speakingJob =
            viewModelScope.launch {
                try {
                    previousJob?.join()
                    ttsClient.speak(normalizedText)
                    mutableUiState.update {
                        it.copy(status = TtsStatus.Completed, errorMessage = null)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    mutableUiState.update {
                        it.copy(status = TtsStatus.Error, errorMessage = SPEAK_FAILURE_ERROR)
                    }
                }
            }
    }

    /**
     * Cancels the active standalone TTS request and returns the controls to a usable Idle state.
     *
     * @return This function has no return value.
     *
     * The main-thread event cancels lifecycle-owned work cooperatively without blocking or joining;
     * concrete clients must stop synthesis/playback through coroutine cancellation. The previous
     * text remains visible for retry, errors clear, and cancellation is never surfaced as Error.
     */
    fun onStopRequested() {
        speakingJob?.cancel()
        speakingJob = null
        mutableUiState.update { it.copy(status = TtsStatus.Idle, errorMessage = null) }
    }

    /**
     * Cancels standalone speech and disposes an owned closeable TtsClient with the ViewModel.
     *
     * @return This lifecycle callback has no return value.
     *
     * Android invokes it during main-thread ViewModel disposal. Cancellation is non-blocking; a
     * DeviceTtsClient close stops playback and shuts down TextToSpeech. Cleanup exceptions are not
     * expected from compliant clients and no provider callback or error is exposed to UI state.
     */
    override fun onCleared() {
        speakingJob?.cancel()
        (ttsClient as? AutoCloseable)?.close()
        super.onCleared()
    }

    /**
     * Creates TtsViewModel instances while keeping the UI typed only to [TtsClient].
     *
     * @param ttsClient provider-neutral on-device or future cloud implementation.
     *
     * Factory construction performs no synthesis/audio work and owns no coroutine. Android invokes
     * [create] on the main thread; unsupported ViewModel classes fail with IllegalArgumentException.
     */
    class Factory(
        private val ttsClient: TtsClient,
    ) : ViewModelProvider.Factory {

        /**
         * Constructs the requested TtsViewModel.
         *
         * @param modelClass ViewModel type requested by Android.
         * @return a new TtsViewModel cast to the requested type.
         * @throws IllegalArgumentException when [modelClass] is not TtsViewModel.
         *
         * Creation is synchronous on the main thread, performs no I/O, and owns no cancellation
         * resource. The injected client remains cold until Speak is requested.
         */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TtsViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return TtsViewModel(ttsClient) as T
        }
    }

    private companion object {
        /** Fixed recoverable message used when a caller bypasses the disabled blank-input UI. */
        const val EMPTY_TEXT_ERROR = "Enter text before requesting speech."

        /** Fixed non-secret message for synthesis or playback failures. */
        const val SPEAK_FAILURE_ERROR = "Text-to-speech failed. Please try again."
    }
}

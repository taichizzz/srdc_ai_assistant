package com.foxconn.seeandsay.ui

/**
 * Centralizes DEBUG-screen mutual exclusion between microphone, PCM, and TTS operations.
 *
 * Functions are pure and read immutable state only. They perform no I/O, launch no coroutine, and
 * have no failure/cancellation behavior. The policy prevents a UI event from starting TTS until all
 * capture/drain/playback work is released, and blocks every microphone entry while standalone or
 * integrated TTS is Speaking. The M1.3 pipeline additionally joins active microphone capture before
 * entering its Replying/Speaking states, so this UI gate reinforces rather than creates the handoff.
 */
internal object DebugAudioExclusionPolicy {

    /**
     * Reports whether the TTS client is synthesizing or playing the current utterance.
     *
     * @param ttsState immutable TTS UI state.
     * @return true only for TtsStatus.Speaking.
     *
     * This pure check is safe on any thread and has no I/O, failure, or cancellation behavior.
     */
    fun isTtsSpeaking(ttsState: TtsUiState): Boolean =
        ttsState.status == TtsStatus.Speaking

    /**
     * Reports whether any STT capture, transition, cloud test, or loopback playback owns audio.
     *
     * @param state immutable STT DEBUG state.
     * @return true while microphone/output resources are active or being acquired/released.
     *
     * This pure check is safe on any thread and has no I/O, failure, or cancellation behavior.
     */
    fun isSttAudioBusy(state: SttUiState): Boolean =
        state.status == SttStatus.RequestingPermission ||
            state.status == SttStatus.Connecting ||
            state.status == SttStatus.Listening ||
            state.status == SttStatus.Stopping ||
            state.status == SttStatus.Replying ||
            state.status == SttStatus.Speaking ||
            state.isDebugRecording ||
            state.isDebugPlaybackActive ||
            state.isCloudSttSmokeTestRunning

    /**
     * Determines whether DEBUG TTS may start without overlapping capture/output ownership.
     *
     * @param state immutable STT DEBUG state.
     * @param ttsState immutable TTS UI state.
     * @return true only when STT audio is fully released and TTS is not already Speaking.
     *
     * This pure gate is safe on any thread and has no I/O, failure, or cancellation behavior.
     */
    fun canStartTts(
        state: SttUiState,
        ttsState: TtsUiState,
    ): Boolean = !isSttAudioBusy(state) && !isTtsSpeaking(ttsState)

    /**
     * Determines whether a microphone action may start without recording assistant speech.
     *
     * @param ttsState immutable TTS UI state.
     * @return false for the full Speaking state, including cloud synthesis and fallback playback.
     *
     * This pure gate is safe on any thread and has no I/O, failure, or cancellation behavior.
     */
    fun canUseMicrophone(ttsState: TtsUiState): Boolean =
        !isTtsSpeaking(ttsState)
}

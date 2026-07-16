package com.foxconn.seeandsay.ui

/**
 * Describes the visible lifecycle of one standalone M1.2 text-to-speech request.
 *
 * Values are immutable, perform no I/O, have no failure mode, and own no coroutine or cancellation
 * resource. The future M1.3 pipeline will define its own broader `Speaking` state.
 */
enum class TtsStatus {
    /** No standalone TTS request is active. */
    Idle,

    /** The injected TtsClient is synthesizing or playing the current text. */
    Speaking,

    /** The injected TtsClient returned successfully after its playback contract completed. */
    Completed,

    /** A recoverable TTS failure is visible and a new Speak request may retry. */
    Error,
}

/**
 * Holds the immutable state rendered by the DEBUG M1.2 TTS controls.
 *
 * @property status current standalone synthesis/playback lifecycle state.
 * @property currentText normalized text owned by the active or most recent request.
 * @property errorMessage fixed recoverable failure text, or `null` outside Error.
 *
 * The value performs no I/O, throws no project-specific failure, and is safe to publish through a
 * StateFlow across coroutine contexts. It owns no job and has no cancellation behavior.
 */
data class TtsUiState(
    val status: TtsStatus = TtsStatus.Idle,
    val currentText: String = "",
    val errorMessage: String? = null,
)

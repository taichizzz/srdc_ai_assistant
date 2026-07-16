package com.foxconn.seeandsay.speech

/**
 * Speaks text through a provider-neutral text-to-speech implementation.
 *
 * Implementations must suspend [speak] until playback has completed, propagate synthesis or
 * playback failures to the caller, and stop promptly when the caller cancels. Google, Android TTS,
 * audio-format, and transport types must remain inside concrete `speech/` implementations.
 */
interface TtsClient {

    /**
     * Synthesizes and plays one text value, returning only after playback finishes.
     *
     * @param text non-blank plain text to speak.
     * @return when playback has completed successfully.
     *
     * Implementations may perform synthesis, network, and audio work on appropriate dispatchers but
     * must not block the caller's thread. Provider/audio failures propagate as exceptions. Coroutine
     * cancellation must terminate pending synthesis/playback and must not be converted to an error.
     */
    suspend fun speak(text: String)
}

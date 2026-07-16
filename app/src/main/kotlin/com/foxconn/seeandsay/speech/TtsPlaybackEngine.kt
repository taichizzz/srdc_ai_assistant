package com.foxconn.seeandsay.speech

/**
 * Identifies the actual synthesis/playback route attempted by the fallback TTS client.
 *
 * @property displayName human-readable DEBUG UI label.
 * @property logValue stable credential-free value for Logcat filtering and parsing.
 *
 * Values are provider-neutral and immutable, perform no I/O, fail in no way, and are safe across
 * coroutine dispatchers. They own no playback, coroutine, or cancellation resource.
 */
enum class TtsPlaybackEngine(
    val displayName: String,
    val logValue: String,
) {
    /** No utterance has selected a cloud or device route in this ViewModel lifetime. */
    NotUsed("Not used yet", "not_used"),

    /** Google Cloud TTS is currently being attempted or was the most recent route. */
    Cloud("Cloud", "cloud"),

    /** Android TextToSpeech is currently being attempted or was the most recent route. */
    OnDevice("On-device", "on_device"),
}

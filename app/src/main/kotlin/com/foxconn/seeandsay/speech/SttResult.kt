package com.foxconn.seeandsay.speech

/**
 * Represents one provider-neutral speech-recognition update.
 *
 * @property transcript recognized text for the current interim or final segment.
 * @property isFinal `true` when the provider has committed the segment and it may be appended to the
 * accumulated transcript; `false` when later updates may replace it.
 * @property confidence optional provider confidence for committed results; interim results normally
 * omit confidence.
 *
 * This immutable value performs no work, has no failure mode, and is safe to pass between coroutine
 * contexts. It owns no cancellable resource.
 */
data class SttResult(
    val transcript: String,
    val isFinal: Boolean,
    val confidence: Float? = null,
)

package com.foxconn.seeandsay.speech

/**
 * Identifies the provider-neutral container and sample representation of synthesized speech.
 *
 * Values contain no Google or Android type, perform no I/O, and have no threading, failure, or
 * cancellation behavior. Phase 4 uses this value to select parsing and playback behavior.
 */
enum class SynthesizedAudioFormat {
    /** WAV container whose data chunk contains signed little-endian mono PCM16 samples. */
    Linear16Wav,
}

/**
 * Carries synthesized speech bytes and the metadata required for later audio playback.
 *
 * @property bytes complete encoded response bytes; callers own this immutable-by-convention copy.
 * @property sampleRateHz samples per second requested from the synthesizer.
 * @property channelCount number of interleaved channels represented by the audio.
 * @property bitsPerSample signed PCM resolution inside the container.
 * @property format provider-neutral container/sample representation.
 *
 * Construction performs no I/O or coroutine work and cannot fail except for normal allocation.
 * The ByteArray must not be mutated after construction. Cancellation is owned by the synthesizer,
 * not this passive value.
 */
data class SynthesizedAudio(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val format: SynthesizedAudioFormat,
)

package com.foxconn.seeandsay.pipeline

import java.text.Normalizer
import java.util.Locale

/**
 * Produces one stable comparison key from speech-recognition text.
 *
 * Normalization applies Unicode NFKC, removes punctuation and whitespace/separator characters,
 * and lowercases Latin text with [Locale.ROOT]. Keeping this operation reusable prevents rule
 * matching from comparing raw STT strings and gives later pipeline matching one deterministic
 * boundary.
 *
 * The object is stateless and thread-safe. It performs synchronous CPU-only work on the caller's
 * thread, creates no coroutine, has no cancellation behavior, and throws no domain-specific error.
 */
object TranscriptNormalizer {

    /** Unicode punctuation, separator, and whitespace runs excluded from comparison keys. */
    private val ignoredCharacters = Regex("[\\p{P}\\p{Z}\\s]+")

    /**
     * Normalizes transcript text for deterministic rule matching.
     *
     * @param transcript raw final transcript, including any spacing, casing, or punctuation noise.
     * @return NFKC-normalized key with punctuation/whitespace removed and Latin text lowercased;
     * the result may be empty when the input contains no matchable characters.
     *
     * This function is synchronous, pure, thread-safe, and safe on any dispatcher. It does not
     * suspend or observe coroutine cancellation and has no expected failure for ordinary strings.
     */
    fun normalize(transcript: String): String {
        val compatibilityNormalized =
            Normalizer.normalize(transcript.trim(), Normalizer.Form.NFKC)
        return compatibilityNormalized
            .replace(ignoredCharacters, "")
            .lowercase(Locale.ROOT)
    }
}

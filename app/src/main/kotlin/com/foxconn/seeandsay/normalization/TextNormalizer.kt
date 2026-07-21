package com.foxconn.seeandsay.normalization

import java.text.Normalizer
import java.util.Locale

/**
 * Produces the single canonical comparison key used by text-based decisions and reply rules.
 *
 * Unicode NFKC folds compatibility forms such as full-width Latin characters, after which Unicode
 * punctuation, separators, and whitespace are removed and cased text is lowercased with
 * [Locale.ROOT]. The project does not currently ship a Traditional/Simplified Chinese conversion
 * table, so this boundary deliberately makes no partial or locale-dependent script conversion.
 *
 * This object is stateless. [normalize] is a pure, deterministic, synchronous function that is
 * thread-safe and safe on any dispatcher. It performs no I/O, launches no coroutine, and has no
 * expected domain failure for ordinary Kotlin strings.
 */
object TextNormalizer {

    /** Unicode punctuation, separator, and whitespace runs excluded from comparison keys. */
    private val ignoredCharacters = Regex("[\\p{P}\\p{Z}\\s]+")

    /**
     * Converts raw user- or screen-provided text into a stable comparison key.
     *
     * @param text raw text that may contain compatibility forms, casing, punctuation, or spacing.
     * @return an NFKC-normalized, punctuation/whitespace-free, locale-stably lowercased key; the
     * result is empty when [text] contains no matchable characters.
     *
     * This pure function runs synchronously on the caller's thread, is safe on any dispatcher,
     * performs no I/O or suspension, and has no expected failure for ordinary string input.
     */
    fun normalize(text: String): String {
        val compatibilityNormalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return compatibilityNormalized
            .replace(ignoredCharacters, "")
            .lowercase(Locale.ROOT)
    }
}

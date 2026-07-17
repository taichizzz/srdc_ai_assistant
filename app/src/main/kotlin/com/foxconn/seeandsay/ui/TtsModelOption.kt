package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.config.GcpTtsConfig

/**
 * Identifies cloud TTS choices shared by the automatic reply and standalone DEBUG selectors.
 *
 * @property model provider model or classic voice identifier shown to the tester.
 * @property speaker single-speaker label, or `null` when the classic voice name is self-contained.
 * @property displayName concise radio-button label that remains valid when config values change.
 * @property logValue stable credential-free value suitable for route-selection diagnostics.
 *
 * Values are immutable, contain no credential or Google SDK type, perform no I/O, and are safe on
 * every dispatcher. They own no coroutine/cancellation resource and cannot fail project-specifically.
 */
enum class TtsModelOption(
    val model: String,
    val speaker: String?,
    val displayName: String,
    val logValue: String,
) {
    /** Exact-locale Taiwan-Mandarin WaveNet baseline and release default. */
    WaveNet(
        model = GcpTtsConfig.WAVENET_VOICE_NAME,
        speaker = null,
        displayName = "WaveNet (${GcpTtsConfig.WAVENET_VOICE_NAME})",
        logValue = "wavenet",
    ),

    /** User-selected Gemini-TTS model, prompt, and Kore single-speaker evaluation path. */
    Gemini(
        model = GcpTtsConfig.GEMINI_MODEL_NAME,
        speaker = GcpTtsConfig.GEMINI_SPEAKER_NAME,
        displayName = "Gemini TTS (${GcpTtsConfig.GEMINI_SPEAKER_NAME})",
        logValue = "gemini",
    ),
}

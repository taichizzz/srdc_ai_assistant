package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.config.GcpTtsConfig

/**
 * Identifies the cloud voice/model choices exposed by the DEBUG TTS evaluation section.
 *
 * @property model provider model or classic voice identifier shown to the tester.
 * @property speaker single-speaker label, or `null` when the classic voice name is self-contained.
 * @property displayName concise radio-button label.
 *
 * Values are immutable, contain no credential or Google SDK type, perform no I/O, and are safe on
 * every dispatcher. They own no coroutine/cancellation resource and cannot fail project-specifically.
 */
enum class DebugTtsModel(
    val model: String,
    val speaker: String?,
    val displayName: String,
) {
    /** Existing exact-locale Taiwan-Mandarin WaveNet baseline. */
    WavenetA(
        model = GcpTtsConfig.VOICE_NAME,
        speaker = null,
        displayName = "WaveNet A (cmn-TW, current)",
    ),

    /** Low-latency preview Gemini-TTS single-speaker evaluation path. */
    GeminiFlashLiteKore(
        model = GcpTtsConfig.GEMINI_FLASH_LITE_MODEL,
        speaker = GcpTtsConfig.GEMINI_FLASH_LITE_SPEAKER,
        displayName = "Gemini 2.5 Flash-Lite Preview (Kore)",
    ),
}

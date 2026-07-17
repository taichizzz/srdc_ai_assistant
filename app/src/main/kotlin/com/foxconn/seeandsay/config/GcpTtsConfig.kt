package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Describes one non-secret Cloud Text-to-Speech single-speaker request profile.
 *
 * @property languageCode BCP-47 locale sent to Google for pronunciation and normalization.
 * @property voiceName classic voice name or Gemini single-speaker name.
 * @property modelName optional Gemini-TTS model identifier; `null` selects the classic voice path.
 * @property prompt optional style direction sent separately from text and never spoken verbatim.
 * @property maximumTextBytes provider limit for the UTF-8 text field.
 *
 * The immutable value performs no I/O, contains no credential or SDK type, and is safe on every
 * dispatcher. Invalid constants can only fail later when Google validates a request; the value owns
 * no coroutine or cancellation resource.
 */
data class GcpTtsSynthesisProfile(
    val languageCode: String,
    val voiceName: String,
    val modelName: String? = null,
    val prompt: String? = null,
    val maximumTextBytes: Int,
)

/**
 * Centralizes non-secret Google Cloud Text-to-Speech V1 synthesis settings.
 *
 * Constants contain no Google, gRPC, or protobuf type and are safe to read from any dispatcher.
 * Reading them performs no I/O, cannot expose credentials, and has no coroutine cancellation or
 * project-specific failure behavior. Cloud synthesis and playback consume the shared metadata.
 */
object GcpTtsConfig {

    /** Google Cloud Text-to-Speech API generation selected for unary synthesis. */
    const val API_VERSION: String = "v1"

    /** Google Cloud service host shared by all unary synthesis calls. */
    const val SERVICE_HOST: String = "texttospeech.googleapis.com"

    /** BCP-47 language code for Mandarin as spoken in Taiwan. */
    const val LANGUAGE_CODE: String = "cmn-tw"

    /** Current Taiwan-Mandarin premium WaveNet voice selected for deterministic evaluation. */
    const val WAVENET_VOICE_NAME: String = "cmn-TW-Wavenet-B"

    /** Gemini-TTS single-speaker model selected for DEBUG and main-pipeline evaluation. */
    const val GEMINI_MODEL_NAME: String = "gemini-3.1-flash-tts-preview"

    /** Gemini single-speaker name; Cloud TTS places this value in VoiceSelectionParams.name. */
    const val GEMINI_SPEAKER_NAME: String = "Kore"

    /** User-selected style direction for a calm, intelligible Taiwan-Mandarin driving assistant. */
    const val GEMINI_STYLE_PROMPT: String =
        "Speak as a calm, friendly in-vehicle assistant in natural Taiwan Mandarin. " +
            "(Taiwanese accent, Traditional Chinese — not Mainland). Steady, clearly enunciated, " +
            "concise, and low-distraction, easy to hear over road noise while driving."

    /** Classic unary text-field limit in UTF-8 bytes. */
    const val CLASSIC_MAXIMUM_TEXT_BYTES: Int = 5_000

    /** Gemini-TTS text-field limit in UTF-8 bytes; the separate prompt has its own bound. */
    const val GEMINI_MAXIMUM_TEXT_BYTES: Int = 4_000

    /** Google encoding requested from unary synthesis; the response includes a WAV header. */
    const val AUDIO_ENCODING: String = "LINEAR16"

    /** Requested 24 kHz output rate, preserving speech quality while remaining AudioTrack-friendly. */
    const val SAMPLE_RATE_HZ: Int = 24_000

    /** Google LINEAR16 contains signed 16-bit little-endian samples after its WAV header. */
    const val BITS_PER_SAMPLE: Int = 16

    /** Cloud TTS produces one mono speech channel for the selected voice/configuration. */
    const val CHANNEL_COUNT: Int = 1

    /** Stable Taiwan-Mandarin WaveNet baseline used by the default automatic/debug selection. */
    val WAVENET_PROFILE: GcpTtsSynthesisProfile =
        GcpTtsSynthesisProfile(
            languageCode = LANGUAGE_CODE,
            voiceName = WAVENET_VOICE_NAME,
            maximumTextBytes = CLASSIC_MAXIMUM_TEXT_BYTES,
        )

    /** Gemini-TTS profile using the user-selected model, prompt, and Kore single speaker. */
    val GEMINI_PROFILE: GcpTtsSynthesisProfile =
        GcpTtsSynthesisProfile(
            languageCode = LANGUAGE_CODE,
            voiceName = GEMINI_SPEAKER_NAME,
            modelName = GEMINI_MODEL_NAME,
            prompt = GEMINI_STYLE_PROMPT,
            maximumTextBytes = GEMINI_MAXIMUM_TEXT_BYTES,
        )

    /**
     * Optional non-secret quota project attached only to OAuth bearer requests.
     *
     * Reading performs in-memory normalization on the caller's thread and cannot block or suspend.
     * The existing shared GCP project BuildConfig field is blank in release builds. Invalid or
     * unauthorized projects are rejected remotely and never expose a credential.
     */
    val projectId: String? = BuildConfig.GCP_STT_PROJECT_ID.trim().ifEmpty { null }
}

package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Centralizes non-secret Google Cloud Text-to-Speech V1 synthesis settings.
 *
 * Constants contain no Google, gRPC, or protobuf type and are safe to read from any dispatcher.
 * Reading them performs no I/O, cannot expose credentials, and has no coroutine cancellation or
 * project-specific failure behavior. Phase 4 will consume the format metadata for playback.
 */
object GcpTtsConfig {

    /** Google Cloud Text-to-Speech API generation selected for unary synthesis. */
    const val API_VERSION: String = "v1"

    /** Google Cloud service host shared by all unary synthesis calls. */
    const val SERVICE_HOST: String = "texttospeech.googleapis.com"

    /** BCP-47 language code for Mandarin as spoken in Taiwan. */
    const val LANGUAGE_CODE: String = "cmn-TW"

    /** Current Taiwan-Mandarin premium voice selected for deterministic evaluation. */
    const val VOICE_NAME: String = "cmn-TW-Wavenet-A"

    /** Google encoding requested from unary synthesis; the response includes a WAV header. */
    const val AUDIO_ENCODING: String = "LINEAR16"

    /** Requested 24 kHz output rate, preserving speech quality while remaining AudioTrack-friendly. */
    const val SAMPLE_RATE_HZ: Int = 24_000

    /** Google LINEAR16 contains signed 16-bit little-endian samples after its WAV header. */
    const val BITS_PER_SAMPLE: Int = 16

    /** Cloud TTS produces one mono speech channel for the selected voice/configuration. */
    const val CHANNEL_COUNT: Int = 1

    /**
     * Optional non-secret quota project attached only to OAuth bearer requests.
     *
     * Reading performs in-memory normalization on the caller's thread and cannot block or suspend.
     * The existing shared GCP project BuildConfig field is blank in release builds. Invalid or
     * unauthorized projects are rejected remotely and never expose a credential.
     */
    val projectId: String? = BuildConfig.GCP_STT_PROJECT_ID.trim().ifEmpty { null }
}

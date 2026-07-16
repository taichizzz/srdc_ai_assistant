package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig
import com.foxconn.seeandsay.speech.AudioConfig

/**
 * Centralizes non-secret Google Cloud Speech-to-Text V1 settings for Phase 5.
 *
 * Values contain no Google SDK, gRPC, or protobuf types and initiate no client or network work.
 * Constants are safe to read on any dispatcher and have no failure or cancellation behavior. The
 * optional project ID comes from gitignored debug BuildConfig plumbing and is `null` when absent.
 */
object GcpSttConfig {

    /** Google Cloud Speech-to-Text API generation selected for the first streaming client. */
    const val API_VERSION: String = "v1"

    /** Google Cloud service host used by the Phase 5 streaming client. */
    const val SERVICE_HOST: String = "speech.googleapis.com"

    /** Taiwan Mandarin in Traditional Chinese script. */
    const val LANGUAGE_CODE: String = "cmn-Hant-TW"

    /** Recognition model tuned for short voice commands. */
    const val MODEL: String = "latest_short"

    /** Provider-neutral spelling of Google Cloud's raw signed PCM encoding. */
    const val AUDIO_ENCODING: String = "LINEAR16"

    /** Capture sample rate shared with [AudioConfig] to avoid Phase 5 transcoding. */
    const val SAMPLE_RATE_HZ: Int = AudioConfig.SAMPLE_RATE_HZ

    /** Single microphone channel required by the M1.1 capture contract. */
    const val CHANNEL_COUNT: Int = AudioConfig.CHANNEL_COUNT

    /** Enables live interim recognition updates in each streaming request. */
    const val INTERIM_RESULTS_ENABLED: Boolean = true

    /**
     * Optional non-secret project used for the `x-goog-user-project` quota header.
     *
     * Reading this property performs no I/O and is safe on any dispatcher. A missing/blank local
     * property maps to `null`; malformed or unauthorized IDs are not validated until Phase 5 because
     * this phase makes no network request.
     */
    val projectId: String? = BuildConfig.GCP_STT_PROJECT_ID.trim().ifEmpty { null }
}

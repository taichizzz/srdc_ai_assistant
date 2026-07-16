package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig
import com.foxconn.seeandsay.speech.AudioConfig

/**
 * Centralizes non-secret Google Cloud Speech-to-Text V2 settings for Chirp evaluation.
 *
 * Values contain no Google SDK, gRPC, protobuf, or credential type. Reading constants performs no
 * I/O and is safe on any dispatcher. Project/location absence is represented by `null` and is
 * converted by CloudSttV2Client into a recoverable not-configured Flow failure before audio starts.
 */
object GcpSttV2Config {

    /** Google Cloud Speech-to-Text API generation used by the Chirp comparison clients. */
    const val API_VERSION: String = "v2"

    /** Taiwan Mandarin in Traditional Chinese script. */
    const val LANGUAGE_CODE: String = "cmn-Hant-TW"

    /** V2 identifier for the Chirp 2 evaluation model. */
    const val CHIRP_2_MODEL: String = "chirp_2"

    /** V2 identifier for the Chirp 3 evaluation model. */
    const val CHIRP_3_MODEL: String = "chirp_3"

    /** Raw signed little-endian PCM encoding used by MicRecorder. */
    const val AUDIO_ENCODING: String = "LINEAR16"

    /** Capture sample rate shared with [AudioConfig], avoiding transcoding. */
    const val SAMPLE_RATE_HZ: Int = AudioConfig.SAMPLE_RATE_HZ

    /** Single input channel shared with the M1.1 microphone contract. */
    const val CHANNEL_COUNT: Int = AudioConfig.CHANNEL_COUNT

    /** Enables replaceable interim recognition values for latency measurement. */
    const val INTERIM_RESULTS_ENABLED: Boolean = true

    /**
     * Non-secret project required in every V2 inline recognizer resource path.
     *
     * Reading performs no I/O and is safe on any dispatcher. Blank debug configuration maps to
     * `null`; project existence/API access is validated only by Google during a live collection.
     */
    val projectId: String? = BuildConfig.GCP_STT_PROJECT_ID.trim().ifEmpty { null }

    /**
     * Regional endpoint and recognizer location injected only into debug BuildConfig.
     *
     * Reading performs no I/O and is safe on any dispatcher. Blank release/default configuration
     * maps to `null`; current Chirp model/language availability is validated remotely.
     */
    val location: String? = BuildConfig.GCP_STT_LOCATION.trim().ifEmpty { null }

    /**
     * Builds the regional Speech-to-Text service hostname without provider SDK types.
     *
     * @param location non-blank Google Cloud V2 region or multi-region identifier.
     * @return regional TLS host in `{location}-speech.googleapis.com` form.
     * @throws IllegalArgumentException when [location] is blank.
     *
     * This pure function is safe on any dispatcher, performs no I/O, and has no coroutine or
     * cancellation behavior. Google rejects unsupported regions only when an RPC begins.
     */
    fun serviceHost(location: String): String {
        require(location.isNotBlank()) { "Google STT V2 location must not be blank." }
        return "${location.trim()}-speech.googleapis.com"
    }

    /**
     * Builds the implicit inline-recognizer resource required by V2 streaming recognition.
     *
     * @param projectId non-blank GCP project identifier.
     * @param location non-blank endpoint/recognizer location.
     * @return `projects/{project}/locations/{location}/recognizers/_` resource path.
     * @throws IllegalArgumentException when either input is blank.
     *
     * This pure function is safe on any dispatcher, performs no I/O, and has no cancellation
     * behavior. Remote project/region/model authorization failures remain client Flow errors.
     */
    fun recognizerPath(projectId: String, location: String): String {
        require(projectId.isNotBlank()) { "Google STT V2 project ID must not be blank." }
        require(location.isNotBlank()) { "Google STT V2 location must not be blank." }
        return "projects/${projectId.trim()}/locations/${location.trim()}/recognizers/_"
    }

    /**
     * Validates that a debug client selects one of the two approved Chirp identifiers.
     *
     * @param model model identifier supplied to CloudSttV2Client.
     * @return trimmed `chirp_2` or `chirp_3` identifier.
     * @throws IllegalArgumentException when [model] is blank or outside the evaluation set.
     *
     * This pure function performs no I/O, is safe on any dispatcher, and has no coroutine or
     * cancellation behavior. Regional availability is deliberately left to Google/the Locations API.
     */
    fun requireSupportedModel(model: String): String {
        val normalized = model.trim()
        require(normalized == CHIRP_2_MODEL || normalized == CHIRP_3_MODEL) {
            "Google STT V2 evaluation supports only chirp_2 or chirp_3."
        }
        return normalized
    }
}

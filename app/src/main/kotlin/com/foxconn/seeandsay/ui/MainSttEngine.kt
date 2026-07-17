package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.config.GcpSttConfig
import com.foxconn.seeandsay.config.GcpSttV2Config

/**
 * Identifies the two speech recognizers available to the DEBUG main pipeline.
 *
 * @property apiVersion Google Speech-to-Text API generation shown to the tester.
 * @property model model identifier sent by the selected provider-neutral client.
 * @property displayName concise label for the main-pipeline selector.
 *
 * Values are immutable, perform no I/O, contain no credential or Google SDK type, and are safe on
 * every dispatcher. They own no coroutine/cancellation resource and cannot fail.
 */
enum class MainSttEngine(
    val apiVersion: String,
    val model: String,
    val displayName: String,
) {
    /** Stable V1 short-command baseline and release default. */
    LatestShort(
        apiVersion = GcpSttConfig.API_VERSION,
        model = GcpSttConfig.MODEL,
        displayName = "V1 (latest_short)",
    ),

    /** V2 Chirp 3 option for the next user-started main-pipeline recognition session. */
    Chirp3(
        apiVersion = GcpSttV2Config.API_VERSION,
        model = GcpSttV2Config.CHIRP_3_MODEL,
        displayName = "V2 Chirp 3",
    ),
}

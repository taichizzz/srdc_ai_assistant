package com.foxconn.seeandsay.ui

import com.foxconn.seeandsay.config.GcpSttConfig
import com.foxconn.seeandsay.config.GcpSttV2Config

/**
 * Identifies the three provider/model combinations available to the DEBUG smoke harness.
 *
 * @property apiVersion provider API generation shown in evaluation output.
 * @property model model identifier sent by the selected client.
 * @property displayName concise selector label for a tester.
 *
 * Values are immutable, contain no credential or provider SDK type, perform no I/O, and are safe on
 * any dispatcher. They have no coroutine/cancellation behavior or project-specific failure mode.
 */
enum class DebugSttEngine(
    val apiVersion: String,
    val model: String,
    val displayName: String,
) {
    /** Existing V1 short-command baseline and default main-pipeline engine. */
    V1LatestShort(GcpSttConfig.API_VERSION, GcpSttConfig.MODEL, "V1 (latest_short)"),

    /** Google Speech-to-Text V2 Chirp 2 evaluation path. */
    V2Chirp2(GcpSttV2Config.API_VERSION, GcpSttV2Config.CHIRP_2_MODEL, "V2 Chirp 2"),

    /** Google Speech-to-Text V2 Chirp 3 evaluation path. */
    V2Chirp3(GcpSttV2Config.API_VERSION, GcpSttV2Config.CHIRP_3_MODEL, "V2 Chirp 3"),
}

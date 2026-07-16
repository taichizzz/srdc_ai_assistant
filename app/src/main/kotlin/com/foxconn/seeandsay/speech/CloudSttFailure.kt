package com.foxconn.seeandsay.speech

/**
 * Classifies the small recoverable cloud-speech failure set required before Phase 7 polish.
 *
 * Values contain no gRPC type, metadata, token, or provider response. They perform no work, have no
 * failure mode, and are safe to pass across coroutine contexts without cancellation behavior.
 */
enum class CloudSttFailureReason {
    /** Neither a plain API key nor a short-lived OAuth token is available. */
    NotConfigured,

    /** Google rejected the selected API key or bearer token. */
    Unauthenticated,

    /** Google denied API/project access, commonly because API enablement or quota project is absent. */
    PermissionDenied,

    /** Google rejected the stream because quota or a rate limit was exhausted. */
    QuotaExceeded,

    /** The network or Google speech service was unavailable. */
    Unavailable,

    /** Stream establishment, recognition, or final-response waiting exceeded its deadline. */
    Timeout,

    /** A provider failure did not match the Phase 5 recoverable categories. */
    Unknown,
}

/**
 * Exposes a fixed, non-secret cloud-speech failure through the provider-neutral [SttClient] Flow.
 *
 * @param reason stable recoverable category suitable for UI branching.
 * @param message fixed user-readable explanation that contains no token, metadata, or server detail.
 *
 * Construction performs no I/O and is safe on any dispatcher. Throwing it terminates the active
 * Flow and participates in normal structured cancellation; it intentionally retains no provider
 * exception cause that could later expose response metadata through logging.
 */
class CloudSttException(
    val reason: CloudSttFailureReason,
    message: String,
) : IllegalStateException(message)

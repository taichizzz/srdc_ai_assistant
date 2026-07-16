package com.foxconn.seeandsay.speech

/**
 * Classifies recoverable cloud synthesis failures without exposing gRPC/provider details.
 *
 * Values perform no I/O and have no threading or cancellation behavior. UI/pipeline code may map
 * these stable categories in Phase 4 without inspecting provider messages or credentials.
 */
enum class CloudTtsFailureReason {
    /** Neither an API key nor an OAuth bearer token is configured. */
    NotConfigured,

    /** Google rejected the selected credential or the credential provider failed. */
    Unauthenticated,

    /** Cloud TTS is disabled, unavailable to the project, or otherwise permission-denied. */
    PermissionDenied,

    /** The project exceeded a Cloud TTS quota or rate limit. */
    QuotaExceeded,

    /** The network or Cloud TTS endpoint is temporarily unreachable. */
    Unavailable,

    /** The unary synthesis request exceeded its bounded deadline. */
    Timeout,

    /** Caller text or provider synthesis configuration is invalid. */
    InvalidInput,

    /** Google returned a successful response without playable audio bytes. */
    EmptyAudio,

    /** Synthesized bytes are malformed or use an unsupported WAV/PCM representation. */
    MalformedAudio,

    /** Android denied transient output focus or failed to play the decoded PCM. */
    PlaybackFailed,

    /** The owning lifecycle disposed the synthesizer before the request. */
    ClientClosed,

    /** A provider failure did not match a more specific recoverable category. */
    Unknown,
}

/**
 * Represents a fixed, non-secret recoverable failure from cloud speech synthesis.
 *
 * @property reason stable failure category for Phase 4 fallback and UI mapping.
 * @param message fixed user-safe description containing no provider response or credential.
 * @param cause optional local cause retained for structured diagnostics but never logged here.
 *
 * Construction performs no I/O or coroutine work. The exception propagates from synthesis on the
 * caller's coroutine; normal cancellation remains CancellationException rather than this type.
 */
class CloudTtsException(
    val reason: CloudTtsFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

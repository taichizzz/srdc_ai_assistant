package com.foxconn.seeandsay.decision

/** Recoverable failure categories exposed by the provider-neutral LM client boundary. */
enum class LmClientFailureReason {
    /** Required debug project, location, model, or short-lived token is absent. */
    NotConfigured,

    /** The bearer token is missing, invalid, or expired. */
    Authentication,

    /** The authenticated principal lacks API, model, or project permission. */
    Permission,

    /** The project exhausted quota or temporarily lacks shared model capacity. */
    Quota,

    /** The request could not reach or receive a usable response from the service. */
    Network,

    /** The bounded client or server deadline expired. */
    Timeout,

    /** A non-secret response or client failure did not fit another category. */
    Unknown,
}

/**
 * Typed recoverable failure from an [LmClient] implementation.
 *
 * @property reason stable category callers may use for fallback or diagnostics.
 * @param message fixed non-secret description; it must never contain a token or response body.
 * @param cause optional local transport/configuration cause retained without credential material.
 *
 * Construction performs no I/O, logging, coroutine, or cancellation work. The interpreter catches
 * ordinary instances and falls back after its bounded retry; coroutine cancellation is never wrapped.
 */
class LmClientException(
    val reason: LmClientFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

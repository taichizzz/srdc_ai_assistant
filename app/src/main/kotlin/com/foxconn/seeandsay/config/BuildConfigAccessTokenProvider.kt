package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Indicates that cloud speech authentication has not been configured on this installation.
 *
 * This typed exception contains a fixed, non-secret message suitable for recoverable UI display. It
 * performs no I/O, is safe to create on any dispatcher, and has no coroutine or cancellation
 * behavior. It never accepts credential text, preventing accidental secret disclosure in messages.
 */
class CloudSpeechNotConfiguredException : IllegalStateException(USER_MESSAGE) {

    /** Holds the stable, non-secret message shared by provider and UI tests. */
    companion object {
        const val USER_MESSAGE =
            "Cloud speech is not configured. Add a short-lived OAuth token to local.properties and retry."
    }
}

/**
 * Reads the debug-only short-lived OAuth token generated into Android's BuildConfig.
 *
 * @param configuredToken injected BuildConfig value; override only for deterministic unit tests.
 *
 * Construction and [currentToken] perform only in-memory string handling on the caller's coroutine
 * context. No token is persisted, refreshed, or logged. A blank value fails with
 * [CloudSpeechNotConfiguredException]. Developer tokens normally expire after approximately one
 * hour; refreshing or using an organization token broker is intentionally deferred beyond Phase 4.
 */
class BuildConfigAccessTokenProvider(
    private val configuredToken: String = BuildConfig.GCP_STT_ACCESS_TOKEN,
) : AccessTokenProvider {

    /**
     * Returns the BuildConfig token after rejecting blank local configuration.
     *
     * @return trimmed, non-blank OAuth bearer token without a `Bearer` prefix.
     * @throws CloudSpeechNotConfiguredException when the generated field is blank or whitespace.
     *
     * This suspend function performs no blocking work and does not switch dispatchers. It contains
     * no suspension point, so cancellation has no cleanup requirement; future remote providers may
     * implement the same interface with cancellable I/O.
     */
    override suspend fun currentToken(): String {
        val token = configuredToken.trim()
        if (token.isEmpty()) throw CloudSpeechNotConfiguredException()
        return token
    }
}

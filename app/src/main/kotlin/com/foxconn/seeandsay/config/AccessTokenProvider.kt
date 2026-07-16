package com.foxconn.seeandsay.config

/**
 * Supplies a currently valid OAuth bearer token without exposing provider SDK types to callers.
 *
 * Implementations may return an already injected token or fetch/refresh one through a future
 * organization broker. They must never log or persist the token. Calls are suspendable so future
 * implementations can perform cancellable I/O on an appropriate dispatcher; failures are reported
 * by throwing a typed configuration/authentication exception.
 */
fun interface AccessTokenProvider {

    /**
     * Returns the OAuth bearer token to use for the next authenticated request.
     *
     * @return a non-blank, currently valid bearer token without the `Bearer` prefix.
     * @throws CloudSpeechNotConfiguredException when no token is configured or available.
     *
     * Implementations choose any required dispatcher internally. The development implementation
     * performs no blocking work or suspension, while a future broker implementation must propagate
     * coroutine cancellation and cancel its underlying request.
     */
    suspend fun currentToken(): String
}

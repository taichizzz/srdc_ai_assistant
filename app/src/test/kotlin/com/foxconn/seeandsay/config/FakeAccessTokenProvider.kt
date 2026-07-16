package com.foxconn.seeandsay.config

/**
 * Supplies deterministic token or failure outcomes to Phase 4 and future cloud-client unit tests.
 *
 * @param token non-secret test value returned when [failure] is `null`.
 * @param failure optional exception thrown instead of returning [token].
 *
 * The fake performs no I/O, persistence, logging, dispatcher switch, or actual suspension. Calls run
 * on the test coroutine context and have no cleanup requirement; surrounding test cancellation is
 * unaffected because no child work is created.
 */
class FakeAccessTokenProvider(
    private val token: String = "configured-test-value",
    private val failure: Throwable? = null,
) : AccessTokenProvider {

    /** Number of times [currentToken] has been requested by the code under test. */
    var requestCount: Int = 0
        private set

    /**
     * Returns the configured test value or throws the configured test failure.
     *
     * @return [token] when no [failure] was supplied.
     * @throws Throwable exactly the injected [failure], when present.
     *
     * The suspend function runs synchronously on the caller's test dispatcher, performs no I/O or
     * logging, and owns no cancellable resource.
     */
    override suspend fun currentToken(): String {
        requestCount += 1
        failure?.let { throw it }
        return token
    }
}

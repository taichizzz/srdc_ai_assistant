package com.foxconn.seeandsay.decision

/**
 * Pure JVM [LmClient] fake returning scripted raw responses and recording requests.
 *
 * @param responses non-empty responses returned in order; after exhaustion the last response is
 * retained so a retry remains deterministic.
 * @param failure optional throwable raised for every request instead of returning a response.
 *
 * The fake performs no network, provider, credential, Android, filesystem, timer, delay, or logging
 * work. It is intended for one test coroutine and is not thread-safe. Construction fails with
 * [IllegalArgumentException] when neither a response nor failure is supplied. Cancellation
 * throwables are propagated exactly as supplied.
 */
class FakeLmClient(
    responses: List<String> = emptyList(),
    private val failure: Throwable? = null,
) : LmClient {

    /** Defensive immutable response script. */
    private val scriptedResponses = responses.toList()

    /** Mutable ordered request log retained privately. */
    private val recordedRequests = mutableListOf<LmRequest>()

    /** Zero-based response position advanced until the final scripted response. */
    private var nextResponseIndex = 0

    init {
        require(scriptedResponses.isNotEmpty() || failure != null) {
            "FakeLmClient requires at least one response or a failure"
        }
    }

    /**
     * Returns a defensive ordered view of all received requests.
     *
     * @return immutable request values recorded so far.
     *
     * This synchronous in-memory accessor performs no I/O or coroutine work, is not safe to race
     * with [complete] from another thread, and has no expected failure.
     */
    fun requests(): List<LmRequest> = recordedRequests.toList()

    /**
     * Records one request, then throws [failure] or returns the next scripted response.
     *
     * @param request provider-neutral schema-constrained completion input.
     * @return next raw response, retaining the final value after script exhaustion.
     * @throws Throwable exact configured [failure], including cancellation, when present.
     *
     * This suspend function completes immediately without network, delay, timer, child coroutine,
     * provider, credential, or logging work and does not intercept cancellation.
     */
    override suspend fun complete(request: LmRequest): String {
        recordedRequests += request
        failure?.let { throw it }
        val response = scriptedResponses[nextResponseIndex]
        if (nextResponseIndex < scriptedResponses.lastIndex) nextResponseIndex += 1
        return response
    }
}

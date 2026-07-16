package com.foxconn.seeandsay.config

/**
 * Supplies an optional plain API key without exposing BuildConfig or provider SDK types to callers.
 *
 * Implementations must never log, persist, or otherwise expose a returned key. Calls are
 * suspendable so a future approved secret source can perform cancellable I/O on its own dispatcher;
 * absence is represented by `null`, while provider failures may be thrown to the caller.
 */
fun interface ApiKeyProvider {

    /**
     * Returns the API key selected for the next request when one is configured.
     *
     * @return trimmed, non-blank plain API key, or `null` when no API-key mode is configured.
     * @throws Throwable when a future provider cannot inspect its approved secret source.
     *
     * Implementations choose any required dispatcher internally and must propagate coroutine
     * cancellation. The BuildConfig implementation performs only in-memory string handling and has
     * no suspension point or cleanup requirement.
     */
    suspend fun currentApiKey(): String?
}

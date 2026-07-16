package com.foxconn.seeandsay.config

import com.foxconn.seeandsay.BuildConfig

/**
 * Reads the debug-only plain Speech-to-Text API key generated into Android's BuildConfig.
 *
 * @param configuredApiKey injected BuildConfig value; override only for deterministic unit tests.
 *
 * Construction and [currentApiKey] perform only in-memory string handling on the caller's coroutine
 * context. The long-lived key is never persisted or logged by this provider. Blank configuration
 * maps to `null`; malformed or unauthorized values are rejected only by the remote API.
 */
class BuildConfigApiKeyProvider(
    private val configuredApiKey: String = BuildConfig.GCP_STT_API_KEY,
) : ApiKeyProvider {

    /**
     * Returns the configured API key after normalizing accidental surrounding whitespace.
     *
     * @return trimmed, non-blank API key, or `null` when the generated field is blank.
     *
     * This suspend function performs no blocking work, logging, or dispatcher switch. It has no
     * suspension point or cancellation cleanup; normal JVM allocation failure is its only local
     * failure mode.
     */
    override suspend fun currentApiKey(): String? =
        configuredApiKey.trim().ifEmpty { null }
}

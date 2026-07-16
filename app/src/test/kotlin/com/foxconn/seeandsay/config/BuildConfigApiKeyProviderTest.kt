package com.foxconn.seeandsay.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies debug API-key normalization without reading BuildConfig or a local credential file.
 *
 * Tests use non-secret in-memory values on the coroutine test dispatcher. They perform no Android,
 * filesystem, logging, or network work and own no independently cancellable resource.
 */
class BuildConfigApiKeyProviderTest {

    /**
     * Verifies a configured plain key is trimmed and returned without transformation.
     *
     * @return This test has no return value.
     *
     * [runTest] executes the provider synchronously on its test dispatcher. It fails only if
     * normalization changes the non-secret test value and requires no cleanup or cancellation.
     */
    @Test
    fun configuredKeyIsTrimmedAndReturned() =
        runTest {
            val provider = BuildConfigApiKeyProvider("  test-api-key  ")

            assertEquals("test-api-key", provider.currentApiKey())
        }

    /**
     * Verifies blank local API-key configuration is represented as absent.
     *
     * @return This test has no return value.
     *
     * [runTest] performs only in-memory work on the test dispatcher. It fails if whitespace becomes
     * a credential and creates no coroutine child or cancellation cleanup.
     */
    @Test
    fun blankKeyReturnsNull() =
        runTest {
            val provider = BuildConfigApiKeyProvider("   ")

            assertNull(provider.currentApiKey())
        }
}

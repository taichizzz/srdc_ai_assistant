package com.foxconn.seeandsay.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies development token presence handling without reading machine configuration or networking.
 *
 * Tests supply non-secret in-memory values to the provider constructor. They run on a coroutine test
 * dispatcher, perform no Android, filesystem, or network I/O, and have no resource cleanup beyond
 * normal structured test-scope cancellation.
 */
class BuildConfigAccessTokenProviderTest {

    /**
     * Verifies a configured short-lived value is trimmed and returned unchanged otherwise.
     *
     * @return This test has no return value.
     *
     * [runTest] supplies the coroutine context. The provider has no suspension point or failure for
     * this input; assertion failure indicates the credential plumbing altered a configured value.
     */
    @Test
    fun returnsConfiguredToken() =
        runTest {
            val provider = BuildConfigAccessTokenProvider("  configured-value  ")

            assertEquals("configured-value", provider.currentToken())
        }

    /**
     * Verifies blank or whitespace-only configuration throws the typed recoverable exception.
     *
     * @return This test has no return value.
     *
     * [runTest] supplies the coroutine context. No I/O or child job is created; the test fails if a
     * blank value escapes as a bearer token or if a generic exception replaces the typed contract.
     */
    @Test
    fun blankTokenThrowsCloudNotConfiguredException() =
        runTest {
            val blankValues = listOf("", "   ", "\t\n")

            blankValues.forEach { blankValue ->
                try {
                    BuildConfigAccessTokenProvider(blankValue).currentToken()
                    fail("Expected CloudSpeechNotConfiguredException for blank configuration")
                } catch (error: CloudSpeechNotConfiguredException) {
                    assertTrue(error.message?.contains("not configured") == true)
                }
            }
        }
}

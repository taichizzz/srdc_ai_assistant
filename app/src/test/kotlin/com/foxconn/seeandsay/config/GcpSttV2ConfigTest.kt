package com.foxconn.seeandsay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies provider-neutral Speech-to-Text V2 endpoint and recognizer resource construction.
 *
 * Tests are pure JVM calculations with no Android, coroutine, credential, DNS, or network work.
 * Failures identify a local routing regression before a live gRPC channel can be opened.
 */
class GcpSttV2ConfigTest {

    /**
     * Verifies the special global resource location uses Google's canonical service hostname.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and performs no I/O or coroutine work. It fails if `global` is
     * incorrectly converted into the nonexistent `global-speech.googleapis.com` hostname.
     */
    @Test
    fun globalLocationUsesCanonicalServiceHost() {
        assertEquals("speech.googleapis.com", GcpSttV2Config.serviceHost(" global "))
    }

    /**
     * Verifies regional and multi-regional locations retain their endpoint prefix.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and performs no I/O or coroutine work. It fails if normalization
     * changes Google's `{location}-speech.googleapis.com` regional routing convention.
     */
    @Test
    fun regionalLocationUsesPrefixedServiceHost() {
        assertEquals(
            "asia-southeast1-speech.googleapis.com",
            GcpSttV2Config.serviceHost(" asia-southeast1 "),
        )
        assertEquals("us-speech.googleapis.com", GcpSttV2Config.serviceHost("us"))
    }

    /**
     * Verifies endpoint specialization does not alter the V2 global recognizer resource path.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and performs no I/O/coroutine work. It fails if the endpoint-only
     * special case accidentally removes `locations/global` from the request resource.
     */
    @Test
    fun globalRecognizerPathRetainsGlobalLocation() {
        assertEquals(
            "projects/test-project/locations/global/recognizers/_",
            GcpSttV2Config.recognizerPath("test-project", "global"),
        )
    }

    /**
     * Verifies a blank location remains invalid instead of silently selecting an endpoint.
     *
     * @return This test has no return value.
     *
     * The test is synchronous and performs no I/O/coroutine work. It expects the documented
     * [IllegalArgumentException]; any other result is a configuration-validation regression.
     */
    @Test
    fun blankLocationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GcpSttV2Config.serviceHost("   ")
        }
    }
}
